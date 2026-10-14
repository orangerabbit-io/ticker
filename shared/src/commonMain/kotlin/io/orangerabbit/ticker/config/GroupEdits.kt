package io.orangerabbit.ticker.config

// GroupTarget identifies the group a config edit acts on. The promoted top-level
// default group is tracked structurally (it has no stored name of its own); stored
// groups are identified by name. Group names are unique (validation rejects
// duplicates), so a name identifies exactly one group across queued edits. Identity
// survives normalization: when a group edit materializes the promoted default into a
// stored "default" group, PromotedDefault targets follow it there. A target that no
// longer exists when its edit runs leaves the config unchanged — never a redirect onto
// whichever group shifted into its place.
sealed interface GroupTarget {
    // the promoted top-level default group: the top-level watchlist/lots while the
    // config uses the promoted shape
    data object PromotedDefault : GroupTarget

    // the default group of an empty config, created by its first edit
    data object NewDefault : GroupTarget

    // a stored group by name
    data class Named(val name: String) : GroupTarget
}

// groupTarget maps a displayed group index (default group first, per withDefaultGroup)
// to the identity of the group shown there. The mapping happens at action time so
// later config changes cannot retarget a queued edit.
fun groupTarget(config: TickerConfig, index: Int): GroupTarget {
    val displayed = config.withDefaultGroup().groups
    if (displayed.isEmpty()) {
        return GroupTarget.NewDefault
    }
    val selected = index.coerceIn(0, displayed.lastIndex)
    return if ((config.watchlist.isNotEmpty() || config.lots.isNotEmpty()) && selected == 0) {
        GroupTarget.PromotedDefault
    } else {
        GroupTarget.Named(displayed[selected].name)
    }
}

// addGroup appends a named group at the end of the displayed list. This materializes
// the promoted default group into a stored "default" group (normalization). Duplicate
// names are rejected so names stay unique.
fun addGroup(config: TickerConfig, name: String): TickerConfig {
    if (name.isBlank()) return config
    val displayed = config.withDefaultGroup().groups
    if (displayed.any { it.name == name }) return config
    return config.copy(
        watchlist = emptyList(),
        lots = emptyList(),
        groups = displayed + ConfigGroup(name = name),
    )
}

// renameGroup renames the target: a named group in place; the promoted default group is
// materialized under the new name at its displayed position. Duplicate names are
// rejected; a target that is already gone leaves the config unchanged.
fun renameGroup(config: TickerConfig, target: GroupTarget, name: String): TickerConfig {
    if (name.isBlank()) return config
    val current = when (target) {
        GroupTarget.PromotedDefault, GroupTarget.NewDefault -> defaultGroupName
        is GroupTarget.Named -> target.name
    }
    val taken = config.withDefaultGroup().groups.mapTo(HashSet()) { it.name }
    taken.remove(current)
    if (name in taken) return config

    return when (target) {
        GroupTarget.PromotedDefault, GroupTarget.NewDefault -> {
            if (promoted(config)) {
                config.copy(
                    watchlist = emptyList(),
                    lots = emptyList(),
                    groups = listOf(ConfigGroup(name = name, watchlist = config.watchlist, lots = config.lots)) + config.groups,
                )
            } else {
                // a group edit materialized the promoted default into a stored
                // "default" group — the target follows it there
                editStoredGroup(config, defaultGroupName) { group -> group.copy(name = name) }
            }
        }
        is GroupTarget.Named -> editStoredGroup(config, target.name) { group -> group.copy(name = name) }
    }
}

// removeGroup removes the target: the promoted default group's top-level entries, a
// named group by name. A target that is already gone leaves the config unchanged.
fun removeGroup(config: TickerConfig, target: GroupTarget): TickerConfig =
    when (target) {
        GroupTarget.PromotedDefault, GroupTarget.NewDefault ->
            if (config.watchlist.isEmpty() && config.lots.isEmpty()) {
                // materialized successor, or nothing left to remove
                editStoredGroup(config, defaultGroupName) { null }
            } else {
                config.copy(watchlist = emptyList(), lots = emptyList())
            }
        is GroupTarget.Named -> editStoredGroup(config, target.name) { null }
    }

// editGroup applies edit to the CURRENT contents of the target: the promoted top-level
// default group (or the empty config's new default group) writes back to the top-level
// watchlist/lots, a named group to its own stored entry. A group edit that materialized
// the promoted default into a stored "default" group keeps the target valid — queued
// edits follow it there. A target that is truly gone leaves the config unchanged.
fun editGroup(config: TickerConfig, target: GroupTarget, edit: (ConfigGroup) -> ConfigGroup): TickerConfig =
    when (target) {
        GroupTarget.NewDefault -> withTopLevelDefault(config, edit)
        GroupTarget.PromotedDefault ->
            if (promoted(config)) {
                withTopLevelDefault(config, edit)
            } else {
                editStoredGroup(config, defaultGroupName, edit)
            }
        is GroupTarget.Named -> editStoredGroup(config, target.name, edit)
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
