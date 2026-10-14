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

    @Test
    fun `group target maps displayed indices to identities`() {
        assertEquals(GroupTarget.NewDefault, groupTarget(TickerConfig(), 0))
        assertEquals(GroupTarget.PromotedDefault, groupTarget(base, 0))
        assertEquals(GroupTarget.Named("b"), groupTarget(base, 1))

        val grouped = TickerConfig(groups = listOf(ConfigGroup(name = "b")))
        assertEquals(GroupTarget.Named("b"), groupTarget(grouped, 0))
    }

    @Test
    fun `add group materializes the promoted default group`() {
        val added = addGroup(base, "c")

        assertEquals(listOf("default", "b", "c"), added.groups.map { it.name })
        assertEquals(listOf("AAPL"), added.groups.first().watchlist)
        assertEquals(listOf(Lot(symbol = "AAPL", quantity = 1.0)), added.groups.first().lots)
        assertTrue(added.watchlist.isEmpty())
        assertTrue(added.lots.isEmpty())
    }

    @Test
    fun `queued removal still targets the default group after add group materialized it`() {
        val added = addGroup(base, "c")
        val removed = removeGroup(added, GroupTarget.PromotedDefault)

        assertEquals(listOf("b", "c"), removed.groups.map { it.name })
    }

    @Test
    fun `queued rename still targets the default group after add group materialized it`() {
        val added = addGroup(base, "c")
        val renamed = renameGroup(added, GroupTarget.PromotedDefault, "long term")

        assertEquals(listOf("long term", "b", "c"), renamed.groups.map { it.name })
        assertEquals(listOf("AAPL"), renamed.groups.first().watchlist)
    }

    @Test
    fun `queued edit still targets the default group after add group materialized it`() {
        val added = addGroup(base, "c")
        val edited = editGroup(added, GroupTarget.PromotedDefault) { group ->
            group.copy(watchlist = group.watchlist + "TSLA")
        }

        assertEquals(listOf("AAPL", "TSLA"), edited.groups.first().watchlist)
        assertEquals(listOf("b", "c"), edited.groups.drop(1).map { it.name })
    }

    @Test
    fun `actions on a group that is already gone are no-ops`() {
        val withoutB = base.copy(groups = emptyList())

        assertEquals(withoutB, removeGroup(withoutB, GroupTarget.Named("b")))
        assertEquals(withoutB, renameGroup(withoutB, GroupTarget.Named("b"), "x"))
        assertEquals(withoutB, editGroup(withoutB, GroupTarget.Named("b")) { it.copy(name = "x") })
    }

    @Test
    fun `duplicate group names are rejected by add and rename`() {
        assertEquals(base, addGroup(base, "b"))
        assertEquals(base, renameGroup(base, GroupTarget.PromotedDefault, "b"))
    }

    @Test
    fun `new default group is created by its first edit`() {
        val edited = editGroup(TickerConfig(), GroupTarget.NewDefault) { group ->
            group.copy(watchlist = group.watchlist + "AAPL")
        }

        assertEquals(listOf("AAPL"), edited.watchlist)
    }
}
