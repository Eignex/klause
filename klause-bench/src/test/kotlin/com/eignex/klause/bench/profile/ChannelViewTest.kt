package com.eignex.klause.bench.profile

import com.eignex.klause.factor.global.Inverse
import com.eignex.klause.formats.flatzinc.parseFlatZinc
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.DefinitionalSweep
import com.eignex.klause.localsearch.LocalSearchModel
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.MoveSink
import com.eignex.klause.util.Cancellation
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class ChannelViewTest {
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `inverse role views expose the same small source swap neighborhood`(transpose: Boolean) {
        val source = Problem(0, 6, Array(6) { IntDomain(0, 2) },
            arrayOf(Inverse(intArrayOf(0, 1, 2), intArrayOf(3, 4, 5))))
        val problem = if (transpose) assertNotNull(inverseAssignmentView(source, Cancellation.Never)) else source
        val model = LocalSearchModel.open(problem)
        val neighbors = HashSet<List<Long>>()

        for (seed in 0L until 16L) {
            val state = LocalSearchState(model, Random(seed))
            for ((variable, value) in longArrayOf(2, 0, 1, 1, 2, 0).withIndex()) {
                state.assignment.setInt(variable, value)
            }
            state.recompute()
            val sink = MoveSink()
            state.factors[0].proposeStructuredMoves(state, 0, sink)
            for (move in sink.list) neighbors += state.inspectMove(move).assignment.ints.toList()
        }

        assertEquals(setOf(
            listOf(0L, 2L, 1L, 0L, 2L, 1L),
            listOf(1L, 0L, 2L, 1L, 0L, 2L),
            listOf(2L, 1L, 0L, 2L, 1L, 0L),
        ), neighbors)
    }

    @Test
    fun `searched channel factories use the admitted definition network`() {
        val program = parseFlatZinc("var bool: b; var 0..1: y; " +
            "constraint bool2int(b, y) :: defines_var(y); solve satisfy;")
        val maintained = assertNotNull(DefinitionalSweep.fromSource(program.problem, program.definitionHints))

        val searched = ChannelView.SearchedValues.sweep(maintained, Cancellation.Never)

        assertNull(searched)
        assertEquals(1, maintained.size)
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15])
    fun `inverse role transposition preserves the grade at every source assignment`(bits: Int) {
        val source = Problem(0, 4, Array(4) { IntDomain(1, 2) },
            arrayOf(Inverse(intArrayOf(0, 1), intArrayOf(2, 3), 1, 1)))
        val view = assertNotNull(inverseAssignmentView(source, Cancellation.Never))
        val primal = LocalSearchState(LocalSearchModel.open(source), Random(0))
        val inverse = LocalSearchState(LocalSearchModel.open(view), Random(0))

        for (variable in 0 until 4) {
            val value = 1L + ((bits shr variable) and 1)
            primal.assignment.setInt(variable, value)
            inverse.assignment.setInt(variable, value)
        }
        primal.recompute()
        inverse.recompute()

        assertEquals(primal.cost, inverse.cost)
    }

    @Test
    fun `inverse role transposition retains source holes semantics and implied roles`() {
        val source = Problem(0, 6, Array(6) { IntDomain(4, 6).excludeValue(5) },
            arrayOf(Inverse(intArrayOf(0, 1, 2), intArrayOf(3, 4, 5), 4, 4)),
            impliedFactorMask = booleanArrayOf(true))

        val view = assertNotNull(inverseAssignmentView(source, Cancellation.Never))

        assertSame(source.declaredIntDomains, view.declaredIntDomains)
        assertSame(source.semantics, view.semantics)
        assertSame(source.impliedFactorMask, view.impliedFactorMask)
    }

    @Test
    fun `inverse role transposition accepts a source permutation with unequal offsets`() {
        val source = Problem(0, 4, arrayOf(IntDomain(4, 5), IntDomain(4, 5),
            IntDomain(-2, -1), IntDomain(-2, -1)),
            arrayOf(Inverse(intArrayOf(0, 1), intArrayOf(2, 3), -2, 4)))
        val view = assertNotNull(inverseAssignmentView(source, Cancellation.Never))
        val state = LocalSearchState(LocalSearchModel.open(view), Random(0))
        for ((variable, value) in longArrayOf(5, 4, -1, -2).withIndex()) {
            state.assignment.setInt(variable, value)
        }

        state.recompute()

        assertEquals(0L, state.cost)
    }

    @Test
    fun `aliased inverse channels decline without a partial view`() {
        val source = Problem(0, 2, Array(2) { IntDomain(0, 1) },
            arrayOf(Inverse(intArrayOf(0, 1), intArrayOf(0, 1))))

        assertNull(inverseAssignmentView(source, Cancellation.Never))
    }

    @Test
    fun `competing inverse channels decline without a partial view`() {
        val source = Problem(0, 6, Array(6) { IntDomain(0, 1) },
            arrayOf(Inverse(intArrayOf(0, 1), intArrayOf(2, 3)),
                Inverse(intArrayOf(2, 3), intArrayOf(4, 5))))

        assertNull(inverseAssignmentView(source, Cancellation.Never))
    }

    @Test
    fun `many to one value channels do not supply an inverse assignment view`() {
        val source = parseFlatZinc("var 1..2: x; var 7..7: y; " +
            "constraint array_int_element(x, [7, 7], y) :: defines_var(y); solve satisfy;")

        assertNull(inverseAssignmentView(source.problem, Cancellation.Never))
    }

    @Test
    fun `cancelled inverse role transposition publishes no view`() {
        val source = Problem(0, 4, Array(4) { IntDomain(0, 1) },
            arrayOf(Inverse(intArrayOf(0, 1), intArrayOf(2, 3))))

        assertNull(inverseAssignmentView(source, Cancellation { true }))
    }
}
