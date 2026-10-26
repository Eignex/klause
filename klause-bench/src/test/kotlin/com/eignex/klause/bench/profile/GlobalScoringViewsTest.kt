package com.eignex.klause.bench.profile

import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.factor.table.Mdd
import com.eignex.klause.factor.table.Table
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.LocalSearchModel
import com.eignex.klause.localsearch.LocalSearchProblem
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move
import com.eignex.klause.solver.FactorProjectionProviders
import com.eignex.klause.util.Cancellation
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class GlobalScoringViewsTest {
    @Test
    fun `pairwise grading changes the collision gradient while retaining native proposals`() {
        val problem = Problem(0, 4, Array(4) { IntDomain(0, 3) }, arrayOf(AllDifferent(intArrayOf(0, 1, 2, 3))))
        val native = LocalSearchState(LocalSearchModel.open(problem), Random(7))
        val pairwise = pairwise(problem)
        for (state in listOf(native, pairwise)) {
            for (i in 0..3) state.assignment.setInt(i, if (i == 3) 1 else 0)
            state.recompute()
            state.moveSink.clear()
            state.factors[0].proposeRepairMoves(state, 0, state.moveSink)
        }

        assertSame(problem, pairwise.problem)
        assertEquals(2L, native.cost)
        assertEquals(3L, pairwise.cost)
        assertEquals(-1L, native.netDelta(Move.IntSet(0, 2)))
        assertEquals(-2L, pairwise.netDelta(Move.IntSet(0, 2)))
        assertEquals(native.moveSink.list, pairwise.moveSink.list)
    }

    @Test
    fun `pairwise deltas preserve repeated coordinates optional polarity and excepted values`() {
        val factor = AllDifferent(intArrayOf(0, 0, 1, 2),
            intArrayOf(Lit.make(0, true), Lit.make(1, false), Lit.make(0, true), Lit.make(1, true)), longArrayOf(0))
        val problem = Problem(2, 3, Array(3) { IntDomain(0, 2) }, arrayOf(factor))
        val state = pairwise(problem)
        state.assignment.setBool(0, true)
        state.assignment.setBool(1, false)
        for (i in 0..2) state.assignment.setInt(i, 1)
        state.recompute()
        assertEquals(3L, state.cost)
        val moves = listOf(Move.IntSet(0, 0), Move.BoolFlip(1), Move.BoolFlip(0),
            Move.Compound(listOf(Move.IntSet(0, 1), Move.IntSet(1, 1), Move.IntSet(0, 2))))
        for (move in moves) {
            val before = state.cost
            val delta = state.netDelta(move)
            assertEquals(before, state.cost)
            assertEquals(delta, state.netDelta(move))
            state.apply(move)
            assertEquals(before + delta, state.cost)
            val after = state.cost
            state.recompute()
            assertEquals(after, state.cost)
        }
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 1, 2, 3])
    fun `integer probes reconcile every aliased value under each presence assignment`(presence: Int) {
        val factor = AllDifferent(intArrayOf(0, 0, 1, 2),
            intArrayOf(Lit.make(0, true), Lit.make(1, false), Lit.make(0, true), Lit.make(1, true)), longArrayOf(0))
        val state = pairwise(Problem(2, 3, Array(3) { IntDomain(0, 2) }, arrayOf(factor)))
        state.assignment.setBool(0, presence and 1 != 0)
        state.assignment.setBool(1, presence and 2 != 0)
        for (a in 0L..2L) for (b in 0L..2L) for (c in 0L..2L) {
            state.assignment.setInt(0, a)
            state.assignment.setInt(1, b)
            state.assignment.setInt(2, c)
            state.recompute()
            for (id in 0..2) for (value in 0L..2L) {
                val old = state.assignment.intValue(id)
                val before = state.cost
                val move = Move.IntSet(id, value)
                val delta = state.netDelta(move)

                state.apply(move)

                assertEquals(before + delta, state.cost)
                state.apply(Move.IntSet(id, old))
                assertEquals(before, state.cost)
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `ground table and diagram grades agree for every small assignment and primitive probe`(repeated: Boolean) {
        val states = groundControls.getValue(repeated)
        for (a in 0L..2L) for (b in 0L..2L) for (c in 0L..2L) {
            for (state in states) {
                state.assignment.setInt(0, a)
                state.assignment.setInt(1, b)
                state.assignment.setInt(2, c)
                state.recompute()
            }
            assertEquals(1, states.map { it.cost }.distinct().size)
            for (id in 0..2) for (value in 0L..2L) {
                val move = Move.IntSet(id, value)
                assertEquals(1, states.map { it.netDelta(move) }.distinct().size)
            }
        }
    }

    @Test
    fun `weighted paths retain exact cost tuples while changing infeasible grades`() {
        val diagram = Mdd(intArrayOf(0, 1), intArrayOf(1, 2, 1), intArrayOf(0, 8, 16),
            longArrayOf(0, 0, 0, 2, 0, 1, 1, 3, 0, 1, 0, 3, 1, 0, 0, 4), 0, intArrayOf(0), 4, 2)
        val table = assertIs<Table>(GlobalScoringViews.alternate(diagram).factor)

        assertEquals(listOf(0L, 1L, 5L, 1L, 0L, 7L), table.tuples.toList())
        val native = state(diagram)
        val alternate = state(table)
        for (state in listOf(native, alternate)) {
            state.assignment.setInt(0, 0)
            state.assignment.setInt(1, 1)
            state.assignment.setInt(2, 2)
            state.recompute()
        }
        assertEquals(3L, native.cost)
        assertEquals(1L, alternate.cost)
        for (state in listOf(native, alternate)) {
            state.assignment.setInt(2, 5)
            state.recompute()
            assertEquals(0L, state.cost)
        }
    }

    @Test
    fun `cost aliases remain repeated columns instead of independent coordinates`() {
        val diagram = Mdd(intArrayOf(0), intArrayOf(1, 1), intArrayOf(0, 8),
            longArrayOf(0, 1, 0, 2, 0, 2, 0, 2), 0, intArrayOf(0), 4, 0)
        val table = assertIs<Table>(GlobalScoringViews.alternate(diagram).factor)

        assertEquals(listOf(0, 0), table.xs.toList())
        assertEquals(listOf(1L, 2L, 2L, 2L), table.tuples.toList())
    }

    @Test
    fun `weighted nondeterminism declines instead of selecting one cost path`() {
        val diagram = Mdd(intArrayOf(0), intArrayOf(1, 1), intArrayOf(0, 8),
            longArrayOf(0, 1, 0, 2, 0, 1, 0, 3), 0, intArrayOf(0), 4, 1)

        assertEquals("nondeterministic-weighted-diagram", GlobalScoringViews.alternate(diagram).reason)
    }

    @Test
    fun `oversized accepting data declines before indexing a small diagram`() {
        val diagram = Mdd(intArrayOf(0), intArrayOf(1, 1), intArrayOf(0, 3), longArrayOf(0, 0, 0),
            0, IntArray(GlobalScoringViews.MAX_CELLS + 1), 3)

        val result = GlobalScoringViews.alternate(diagram)

        assertEquals("cell-limit", result.reason)
        assertNull(result.factor)
    }

    @Test
    fun `path expansion stops before publishing a partial relation`() {
        val layers = 11
        val diagram = Mdd(IntArray(layers) { it }, IntArray(layers + 1) { 1 }, IntArray(layers + 1) { it * 6 },
            LongArray(layers * 6) { if (it % 6 == 4) 1 else 0 }, 0, intArrayOf(0), 3)
        val result = GlobalScoringViews.alternate(diagram)

        assertEquals("path-limit", result.reason)
        assertNull(result.factor)
    }

    @Test
    fun `short tables decline without enumerating interval values`() {
        val table = Table(intArrayOf(0), longArrayOf(Long.MIN_VALUE), longArrayOf(Long.MAX_VALUE))

        assertEquals("interval-table", GlobalScoringViews.alternate(table).reason)
    }

    @Test
    fun `trie output declines when explicit rows fit but transition words exceed the bound`() {
        val table = Table(intArrayOf(0), LongArray(GlobalScoringViews.MAX_CELLS / 3 + 1) { it.toLong() })

        val result = GlobalScoringViews.alternate(table)

        assertEquals("cell-limit", result.reason)
        assertNull(result.factor)
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 2, 8])
    fun `cancelled construction publishes no alternate relation`(polls: Int) {
        val table = Table(intArrayOf(0, 1, 2), longArrayOf(0, 1, 0, 1, 0, 2, 2, 1, 2, 0, 1, 0))
        var remaining = polls
        val result = GlobalScoringViews.alternate(table, Cancellation { remaining-- == 0 })

        assertEquals("cancelled", result.reason)
        assertNull(result.factor)
    }

    @Test
    fun `overflowing weighted paths decline without wrapping their cost`() {
        val diagram = Mdd(intArrayOf(0, 1), intArrayOf(1, 1, 1), intArrayOf(0, 4, 8),
            longArrayOf(0, 0, 0, Long.MAX_VALUE, 0, 1, 0, 1), 0, intArrayOf(0), 4, 2)

        assertEquals("weight-overflow", GlobalScoringViews.alternate(diagram).reason)
    }

    @Test
    fun `oversized distinctness keeps native scoring and an explicit decline`() {
        val count = GlobalScoringViews.MAX_ARITY + 1
        val problem = Problem(0, count, Array(count) { IntDomain(0, count.toLong()) },
            arrayOf(AllDifferent(IntArray(count) { it })))
        val selection = GlobalScoringViews.pairwise(problem, LocalSearchProblem(problem))

        assertEquals("arity-limit", selection.admissions.single().reason)
    }

    private fun pairwise(problem: Problem): LocalSearchState {
        val canonical = LocalSearchProblem(problem)
        val selection = GlobalScoringViews.pairwise(problem, canonical)
        val projections = FactorProjectionProviders(scoring = selection.provider).prepare(problem)
        return LocalSearchState(LocalSearchModel.open(problem, projections), Random(7))
    }

    private fun state(factor: Factor): LocalSearchState = LocalSearchState(LocalSearchModel.open(
        Problem(0, 3, Array(3) { IntDomain(0, 7) }, arrayOf(factor))), Random(7))

    companion object {
        private lateinit var groundControls: Map<Boolean, List<LocalSearchState>>

        @JvmStatic
        @BeforeAll
        fun prepareControls() {
            groundControls = listOf(false, true).associateWith { repeated ->
                val xs = if (repeated) intArrayOf(0, 1, 0) else intArrayOf(0, 1, 2)
                val table = Table(xs, longArrayOf(0, 1, 0, 1, 0, 2, 2, 1, 2, 0, 1, 0))
                val diagram = assertIs<Mdd>(GlobalScoringViews.alternate(table).factor)
                val roundTrip = assertIs<Table>(GlobalScoringViews.alternate(diagram).factor)
                listOf(table, diagram, roundTrip).map { factor ->
                    LocalSearchState(LocalSearchModel.open(
                        Problem(0, 3, Array(3) { IntDomain(0, 2) }, arrayOf(factor))),
                        Random(7))
                }
            }
        }
    }
}
