package io.orangerabbit.ticker.config

// GroupEditState pairs a config with the materialization lineage of its default group.
// The lineage lives in this edit layer only — user-visible config data carries no
// tokens — and is threaded through queued edits so a captured target keeps identifying
// the same group through materialization. Identity is the materialization event, never
// the group name.
data class GroupEditState(
    val config: TickerConfig,
    val lineage: GroupLineage = GroupLineage(),
)

// GroupLineage identifies one default-group lineage. Targets captured against the
// default group carry `token`; when a group edit materializes the promoted entries
// into a stored group, that group is stamped with the same token and `materializedAs`
// follows it through renames. A group without a matching token — parsed from YAML, or
// created after the lineage died — is unrelated and never matches. `next()` starts a
// fresh lineage, retiring this one.
data class GroupLineage(
    val token: String = mintLineageToken(),
    val materializedAs: String? = null,
) {
    fun next(): GroupLineage = GroupLineage()
}

// lineage tokens only need to be unique within one process's edit layer, which runs
// its edits serialized; a plain counter suffices
private var lineageCounter = 0

private fun mintLineageToken(): String = "lineage-${lineageCounter++}"

// GroupTarget identifies the group an edit acts on. Default targets carry the lineage
// token they were created with and are matched by token only; Named targets are
// matched by name (group names are unique — validation rejects duplicates). A target
// whose group is gone leaves the config unchanged — never a redirect onto whichever
// group took its place.
sealed interface GroupTarget {
    // the default group of one lineage: the promoted top-level entries, the empty
    // config's future default group, or the stored group that materialized from them
    data class Default(val token: String) : GroupTarget

    // a stored group by name
    data class Named(val name: String) : GroupTarget
}

// target maps a displayed group index (default group first, per withDefaultGroup) to
// the identity of the group shown there. The mapping happens at action time so later
// config changes cannot retarget a queued edit.
fun GroupEditState.target(index: Int): GroupTarget {
    val displayed = config.withDefaultGroup().groups
    if (displayed.isEmpty()) {
        return GroupTarget.Default(lineage.token)
    }
    val selected = index.coerceIn(0, displayed.lastIndex)
    return if (promoted(config) && selected == 0) {
        GroupTarget.Default(lineage.token)
    } else {
        GroupTarget.Named(displayed[selected].name)
    }
}

// replaced pairs a wholesale configuration replacement with a lineage reset: the new
// configuration's groups are unrelated to anything captured against the old one, so
// the old lineage retires and queued edits against it become no-ops instead of landing
// on whichever new group shares a name. Import runs this inside the same serialization
// boundary as the edits, so no queued mutation can observe the new config under the old
// lineage.
fun GroupEditState.replaced(config: TickerConfig): GroupEditState =
    GroupEditState(config = config, lineage = lineage.next())

// addGroup appends a named group at the end of the displayed list, materializing the
// promoted default group into a stored "default" group along the way (normalization).
// Duplicate names are rejected so names stay unique.
fun GroupEditState.addGroup(name: String): GroupEditState {
    if (name.isBlank()) return this
    val displayed = config.withDefaultGroup().groups
    if (displayed.any { it.name == name }) return this
    val materialized = config.watchlist.isNotEmpty() || config.lots.isNotEmpty()
    return GroupEditState(
        config = config.copy(
            watchlist = emptyList(),
            lots = emptyList(),
            groups = displayed + ConfigGroup(name = name),
        ),
        lineage = if (materialized) lineage.copy(materializedAs = defaultGroupName) else lineage,
    )
}

// renameGroup renames the target: a named group in place; the default group of a live
// lineage is materialized under the new name at its displayed position (or renamed
// with it when already materialized). Duplicate names are rejected.
fun GroupEditState.renameGroup(target: GroupTarget, name: String): GroupEditState {
    if (name.isBlank()) return this
    val current = when (target) {
        is GroupTarget.Default -> lineage.materializedAs ?: defaultGroupName
        is GroupTarget.Named -> target.name
    }
    val taken = config.withDefaultGroup().groups.mapTo(HashSet()) { it.name }
    taken.remove(current)
    if (name in taken) return this

    return when (target) {
        is GroupTarget.Default -> when (val home = homeOf(target)) {
            DefaultHome.TopLevel -> GroupEditState(
                config = config.copy(
                    watchlist = emptyList(),
                    lots = emptyList(),
                    groups = listOf(ConfigGroup(name = name, watchlist = config.watchlist, lots = config.lots)) + config.groups,
                ),
                lineage = lineage.copy(materializedAs = name),
            )
            is DefaultHome.Materialized -> GroupEditState(
                config = editStoredGroup(config, home.name) { group -> group.copy(name = name) },
                lineage = lineage.copy(materializedAs = name),
            )
            DefaultHome.Retired -> this
        }
        is GroupTarget.Named -> {
            if (config.groups.none { it.name == target.name }) {
                this
            } else {
                GroupEditState(
                    config = editStoredGroup(config, target.name) { group -> group.copy(name = name) },
                    lineage = if (target.name == lineage.materializedAs) lineage.copy(materializedAs = name) else lineage,
                )
            }
        }
    }
}

// removeGroup removes the target: the default group's top-level entries or its
// materialized stored group (retiring the lineage either way), a named group by name.
fun GroupEditState.removeGroup(target: GroupTarget): GroupEditState =
    when (target) {
        is GroupTarget.Default -> when (val home = homeOf(target)) {
            DefaultHome.TopLevel -> GroupEditState(
                config = config.copy(watchlist = emptyList(), lots = emptyList()),
                lineage = if (config.watchlist.isEmpty() && config.lots.isEmpty()) lineage else lineage.next(),
            )
            is DefaultHome.Materialized -> GroupEditState(
                config = editStoredGroup(config, home.name) { null },
                lineage = lineage.next(),
            )
            DefaultHome.Retired -> this
        }
        is GroupTarget.Named -> {
            if (config.groups.none { it.name == target.name }) {
                this
            } else {
                GroupEditState(
                    config = editStoredGroup(config, target.name) { null },
                    lineage = if (target.name == lineage.materializedAs) lineage.next() else lineage,
                )
            }
        }
    }

// editGroup applies edit to the CURRENT contents of the target: the default group's
// top-level entries (created on first edit of an empty config), or its materialized
// stored group.
fun GroupEditState.editGroup(target: GroupTarget, edit: (ConfigGroup) -> ConfigGroup): GroupEditState =
    when (target) {
        is GroupTarget.Default -> when (val home = homeOf(target)) {
            DefaultHome.TopLevel -> copy(config = withTopLevelDefault(config, edit))
            is DefaultHome.Materialized -> copy(config = editStoredGroup(config, home.name, edit))
            DefaultHome.Retired -> this
        }
        is GroupTarget.Named -> copy(config = editStoredGroup(config, target.name, edit))
    }

// DefaultHome is where a default-group target lives right now: the promoted top-level
// entries, the materialized stored group carrying the lineage token, or nowhere (the
// lineage is retired).
private sealed interface DefaultHome {
    data object TopLevel : DefaultHome
    data class Materialized(val name: String) : DefaultHome
    data object Retired : DefaultHome
}

// homeOf resolves where a default-group target lives by LINEAGE, never by name: only
// the stored group stamped with the target's own materialization matches it. A group
// without that token — parsed from YAML, or created after the lineage died — is
// unrelated and never matches.
private fun GroupEditState.homeOf(target: GroupTarget.Default): DefaultHome =
    when {
        target.token != lineage.token -> DefaultHome.Retired
        lineage.materializedAs != null -> DefaultHome.Materialized(lineage.materializedAs)
        else -> DefaultHome.TopLevel
    }

// promoted reports whether the config still holds its default group as top-level
// entries (or has no groups at all yet) rather than a materialized stored group.
private fun promoted(config: TickerConfig): Boolean =
    config.watchlist.isNotEmpty() || config.lots.isNotEmpty() || config.groups.isEmpty()

// withTopLevelDefault applies edit to the top-level entries as the default group.
private fun withTopLevelDefault(config: TickerConfig, edit: (ConfigGroup) -> ConfigGroup): TickerConfig {
    val edited = edit(ConfigGroup(name = defaultGroupName, watchlist = config.watchlist, lots = config.lots))
    return config.copy(watchlist = edited.watchlist, lots = edited.lots)
}

// editStoredGroup applies edit to the stored group with the given name and returns the
// updated config; edit returning null removes the group. A missing group leaves the
// config unchanged.
private fun editStoredGroup(
    config: TickerConfig,
    name: String,
    edit: (ConfigGroup) -> ConfigGroup?,
): TickerConfig {
    val stored = config.groups.indexOfFirst { it.name == name }
    if (stored < 0) return config
    val groups = config.groups.mapIndexedNotNull { i, group ->
        if (i != stored) {
            group
        } else {
            edit(group)
        }
    }
    return config.copy(groups = groups)
}
