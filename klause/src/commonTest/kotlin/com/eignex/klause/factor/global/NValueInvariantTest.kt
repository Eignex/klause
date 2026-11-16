package com.eignex.klause.factor.global

import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.LocalSearchModel
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move
import com.eignex.klause.propagation.bake
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NValueInvariantTest {

    private fun problem(domainHi: Int = 3): Problem = Problem(
        numBoolVars = 0,
        numIntVars = 4,
        intDomains = Array(4) { IntDomain(0, domainHi.toLong()) },
        factors = arrayOf<Factor>(NValue(n = 0, xs = intArrayOf(1, 2, 3))),
    )

    @Test
    fun `excepted counts should grade aliased moves and presence flips exactly`() {
        for (mode in NValue.Mode.entries) {
            val problem = Problem(
                numBoolVars = 1,
                numIntVars = 2,
                intDomains = arrayOf(IntDomain(0, 2), IntDomain(0, 2)),
                factors = arrayOf(NValue(0, intArrayOf(0, 1, 1), mode,
                    presents = intArrayOf(Lit.make(0, true), Lit.make(0, false), Lit.make(0, false)),
                    exceptSet = longArrayOf(0, 2))),
            )
            val state = LocalSearchState(LocalSearchModel.open(problem), Random(0))
            state.assignment.setBool(0, false)
            state.assignment.setInt(0, 1)
            state.assignment.setInt(1, 0)
            state.recompute()
            val factor = state.factors[0]
            for (move in listOf(Move.IntSet(1, 1), Move.IntSet(1, 2), Move.BoolFlip(0), Move.IntSet(0, 0))) {
                val before = factor.violationDegree(state, 0)
                val delta = state.netDelta(move)
                state.apply(move)
                val active = if (state.assignment.boolValue(0)) listOf(state.assignment.intValue(0))
                    else listOf(state.assignment.intValue(1))
                val count = active.count { it == 1L }.toLong()
                val n = state.assignment.intValue(0)
                val expected = when (mode) {
                    NValue.Mode.Eq -> if (n >= count) n - count else count - n
                    NValue.Mode.AtLeast -> maxOf(0L, n - count)
                    NValue.Mode.AtMost -> maxOf(0L, count - n)
                }

                assertEquals(expected.toInt(), factor.violationDegree(state, 0), "$mode $move")
                assertEquals(expected - before, delta, "$mode $move")
            }
        }
    }

    @Test
    fun `excepted seeds should recheck a target that is also a counted position`() {
        val problem = Problem(0, 2, arrayOf(IntDomain(0, 2), IntDomain(2, 2)),
            arrayOf(NValue(0, intArrayOf(0, 1), exceptSet = longArrayOf(0))))
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(0))

        assertFalse(state.factors[0].seedFeasible(state, 0))
    }

    @Test
    fun `excepted repair moves should introduce and remove counted values`() {
        for ((target, initial, repaired) in listOf(Triple(1L, 0L, 1L), Triple(0L, 1L, 0L))) {
            val problem = Problem(0, 2, arrayOf(IntDomain(target, target), IntDomain(0, 1)),
                arrayOf(NValue(0, intArrayOf(1), exceptSet = longArrayOf(0))))
            val state = LocalSearchState(LocalSearchModel.open(problem), Random(0))
            state.assignment.setInt(0, target)
            state.assignment.setInt(1, initial)
            state.recompute()
            state.factors[0].proposeRepairMoves(state, 0, state.moveSink)

            assertTrue(state.moveSink.list.any { it == Move.IntSet(1, repaired) })
        }
    }

    @Test
    fun `violation should equal the gap between the distinct count and n`() {
        val cases = listOf(
            listOf(0L, 0L, 1L) to 1,
            listOf(0L, 0L, 0L) to 2,
        )
        for ((xs, degree) in cases) {
            val state = LocalSearchState(problem().bake(), Random(0))
            state.assignment.setInt(0, 3)
            for (i in 0..2) state.assignment.setInt(1 + i, xs[i])
            state.recompute()
            assertTrue(state.factors[0].isViolated(state, 0), "xs=$xs")
            assertEquals(degree, state.factors[0].violationDegree(state, 0), "xs=$xs")
        }
    }

    @Test
    fun `delta should predict degree change when introducing a new distinct value`() {
        val p = problem()
        val state = LocalSearchState(p.bake(), Random(0))
        state.assignment.setInt(0, 3)
        state.assignment.setInt(1, 0)
        state.assignment.setInt(2, 0)
        state.assignment.setInt(3, 0)
        state.recompute()
        val before = state.factors[0].violationDegree(state, 0)
        val delta = state.factors[0].deltaIfIntSet(state, 0, 1, 1)
        state.apply(Move.IntSet(1, 1))
        val after = state.factors[0].violationDegree(state, 0)
        assertEquals(after - before, delta)
    }
}
