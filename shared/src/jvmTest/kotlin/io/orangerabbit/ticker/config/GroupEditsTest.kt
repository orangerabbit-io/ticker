package io.orangerabbit.ticker.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GroupEditsTest {

    // top-level entries plus one named group: the default group is promoted and shows
    // first alongside "b"
    private val base = TickerConfig(
        watchlist = listOf("AAPL"),
        lots = listOf(Lot(symbol = "AAPL", quantity = 1.0)),
        groups = listOf(ConfigGroup(name = "b", watchlist = listOf("MSFT"))),
    )

    private fun state(config: TickerConfig = base) = GroupEditState(config)

    @Test
    fun `group target maps displayed indices to identities`() {
        val state = state()
        assertEquals(GroupTarget.Default(state.lineage.token), state.target(0))
        assertEquals(GroupTarget.Named("b"), state.target(1))

        val grouped = state(TickerConfig(groups = listOf(ConfigGroup(name = "b"))))
        assertEquals(GroupTarget.Named("b"), grouped.target(0))

        val empty = state(TickerConfig())
        assertEquals(GroupTarget.Default(empty.lineage.token), empty.target(0))
    }

    @Test
    fun `add group materializes and stamps the promoted default group`() {
        val state = state()
        val added = state.addGroup("c")

        assertEquals(listOf("default", "b", "c"), added.config.groups.map { it.name })
        assertEquals(listOf("AAPL"), added.config.groups.first().watchlist)
        assertEquals(listOf(Lot(symbol = "AAPL", quantity = 1.0)), added.config.groups.first().lots)
        assertTrue(added.config.watchlist.isEmpty())
        assertTrue(added.config.lots.isEmpty())
        assertEquals("default", added.lineage.materializedAs)
        assertEquals(state.lineage.token, added.lineage.token)
    }

    @Test
    fun `queued removal still targets the default group after add group materialized it`() {
        val state = state()
        val target = state.target(0)

        val removed = state.addGroup("c").removeGroup(target)

        assertEquals(listOf("b", "c"), removed.config.groups.map { it.name })
    }

    @Test
    fun `queued rename still targets the default group after add group materialized it`() {
        val state = state()
        val target = state.target(0)

        val renamed = state.addGroup("c").renameGroup(target, "long term")

        assertEquals(listOf("long term", "b", "c"), renamed.config.groups.map { it.name })
        assertEquals(listOf("AAPL"), renamed.config.groups.first().watchlist)
        assertEquals("long term", renamed.lineage.materializedAs)
    }

    @Test
    fun `queued edit still targets the default group after add group materialized it`() {
        val state = state()
        val target = state.target(0)

        val edited = state.addGroup("c").editGroup(target) { group ->
            group.copy(watchlist = group.watchlist + "TSLA")
        }

        assertEquals(listOf("AAPL", "TSLA"), edited.config.groups.first().watchlist)
        assertEquals(listOf("b", "c"), edited.config.groups.drop(1).map { it.name })
    }

    @Test
    fun `removed promoted target never matches an unrelated group that takes the default name`() {
        // the failing sequence: capture → removeGroup(target) → rename "b" to
        // "default" → removeGroup(target) again. The renamed group is unrelated to the
        // captured target and must survive untouched.
        val state = state()
        val target = state.target(0)

        val removed = state.removeGroup(target)
        val renamed = removed.renameGroup(GroupTarget.Named("b"), "default")
        val removedAgain = renamed.removeGroup(target)

        assertEquals(listOf("default"), removedAgain.config.groups.map { it.name })
        assertEquals(listOf("MSFT"), removedAgain.config.groups.single().watchlist)
    }

    @Test
    fun `a target from a retired lineage never follows a later materialization`() {
        val state = state()
        val target = state.target(0)

        // the first removal retires the lineage; a new default group appears and is
        // materialized by an unrelated add — the stale target must not follow it
        val removed = state.removeGroup(target)
        val recreated = removed.editGroup(GroupTarget.Default(removed.lineage.token)) { group ->
            group.copy(watchlist = group.watchlist + "TSLA")
        }
        val materialized = recreated.addGroup("c")
        val stale = materialized.removeGroup(target)

        assertEquals(listOf("default", "b", "c"), stale.config.groups.map { it.name })
    }

    @Test
    fun `the lineage map follows successor renames`() {
        val state = state()
        val target = state.target(0)

        val renamed = state.addGroup("c").renameGroup(target, "x")
        val edited = renamed.editGroup(target) { group -> group.copy(watchlist = group.watchlist + "TSLA") }

        assertEquals("x", renamed.lineage.materializedAs)
        assertEquals(listOf("AAPL", "TSLA"), edited.config.groups.first().watchlist)
    }

    @Test
    fun `a wholesale config replacement retires the lineage`() {
        val state = state()
        val target = state.target(0)

        // an import lands a config whose "default" group is unrelated to the captured
        // target; the replacement retires the lineage so a stale removal cannot delete
        // it through the old materialization mapping
        val replaced = state.addGroup("c").replaced(
            TickerConfig(groups = listOf(ConfigGroup(name = "default", watchlist = listOf("NET")))),
        )
        val stale = replaced.removeGroup(target)

        assertEquals(listOf("default"), stale.config.groups.map { it.name })
        assertEquals(listOf("NET"), stale.config.groups.single().watchlist)
    }

    @Test
    fun `actions on a group that is already gone are no-ops`() {
        val state = state(base.copy(groups = emptyList()))
        val target = GroupTarget.Named("b")

        assertEquals(state, state.removeGroup(target))
        assertEquals(state, state.renameGroup(target, "x"))
        assertEquals(state, state.editGroup(target) { it.copy(name = "x") })
    }

    @Test
    fun `duplicate group names are rejected by add and rename`() {
        val state = state()

        assertEquals(state, state.addGroup("b"))
        assertEquals(state, state.renameGroup(state.target(0), "b"))
    }

    @Test
    fun `new default group is created by its first edit`() {
        val empty = state(TickerConfig())

        val edited = empty.editGroup(empty.target(0)) { group ->
            group.copy(watchlist = group.watchlist + "AAPL")
        }

        assertEquals(listOf("AAPL"), edited.config.watchlist)
    }
}
