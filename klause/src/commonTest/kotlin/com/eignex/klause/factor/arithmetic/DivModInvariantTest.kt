package com.eignex.klause.factor.arithmetic

import com.eignex.klause.ir.Factor
import com.eignex.klause.formats.smtlib.SmtLib
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.DefinitionalSweep
import com.eignex.klause.localsearch.LocalSearchModel
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move
import com.eignex.klause.util.bigIntOf
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DivModInvariantTest {
    @Test
    fun `aliased moves predict the complete relation grade`() {
        for (roles in listOf(intArrayOf(0, 1, 2, 3), intArrayOf(0, 0, 1, 2), intArrayOf(0, 1, 0, 2),
            intArrayOf(0, 1, 2, 2), intArrayOf(0, 1, 2, 0))) {
            val problem = Problem(0, 4, Array(4) { IntDomain(-8, 8) },
                arrayOf<Factor>(DivMod(roles[0], roles[1], roles[2], roles[3])))
            val state = LocalSearchState(LocalSearchModel.open(problem), Random(1))
            for (v in 0..3) state.assignment.setInt(v, (v + 1).toLong())
            state.recompute()
            val before = state.cost
            val predicted = state.netDelta(Move.IntSet(0, -7))

            state.apply(Move.IntSet(0, -7))
            val committed = state.cost
            state.recompute()

            assertEquals(state.cost - before, predicted, roles.contentToString())
            assertEquals(state.cost, committed, roles.contentToString())
        }
    }

    @Test
    fun `maintained quotient and remainder follow dividend moves together`() {
        val source = Problem(0, 3, arrayOf(IntDomain(-8, 8), IntDomain(-3, 3), IntDomain(-2, 2)),
            arrayOf<Factor>(DivMod(0, bigIntOf(3), 1, 2)))
        val state = LocalSearchState(LocalSearchModel.open(source), Random(1))
        val sweep = assertNotNull(DefinitionalSweep.infer(source))
        state.invariants = sweep.network(source.numIntVars, source.numBoolVars)
        state.assignment.setInt(0, 7)
        sweep.sweep(state.assignment, state.rootDomains, source.factors)
        state.recompute()

        state.apply(Move.IntSet(0, -7))

        assertEquals(-2L, state.assignment.intValue(1))
        assertEquals(-1L, state.assignment.intValue(2))
        assertEquals(0L, state.cost)
    }

    @Test
    fun `wide digit outputs follow source moves without open scalar coordinates`() {
        val parsed = SmtLib.parse("""
            (declare-const a Int) (assert (<= 0 a 2))
            (assert (>= (mod (+ 1000000000000000000000000000000 a) 1000000000000000000000000000000) 0))
        """.trimIndent())
        val source = parsed.model
        val relation = source.factors.filterIsInstance<WideDivMod>().single()
        val factorId = source.factors.indexOf(relation)
        val state = LocalSearchState(LocalSearchModel.open(source), Random(1))
        val sweep = assertNotNull(DefinitionalSweep.infer(source))
        state.invariants = sweep.network(source.numIntVars, source.numBoolVars)
        for (variable in 0 until source.numIntVars) state.assignment.setInt(variable, state.rootDomains[variable].min)
        val input = parsed.intVarNames.getValue("a")
        state.assignment.setInt(input, 0)
        sweep.sweep(state.assignment, state.rootDomains, source.factors)
        state.recompute()

        state.apply(Move.IntSet(input, 2))

        assertTrue((0 until source.numIntVars).all {
            !source.intBounds.isOpenLower(it) && !source.intBounds.isOpenUpper(it)
        })
        assertEquals(0, state.factors[factorId].violationDegree(state, factorId))
        assertEquals(bigIntOf(1), relation.quotient.evaluate(state.assignment::intValue))
        assertEquals(bigIntOf(2), relation.remainder.evaluate(state.assignment::intValue))
    }

    @Test
    fun `direct divisor repairs use both current outputs`() {
        val source = Problem(0, 4, arrayOf(IntDomain(17, 17), IntDomain(1, 8),
            IntDomain(0, 8), IntDomain(0, 8)), arrayOf<Factor>(DivMod(0, 1, 2, 3)))
        val state = LocalSearchState(LocalSearchModel.open(source), Random(1))
        for ((variable, value) in listOf(0 to 17L, 1 to 6L, 2 to 3L, 3 to 2L)) {
            state.assignment.setInt(variable, value)
        }
        state.recompute()

        state.factors[0].proposeRepairMoves(state, 0, state.moveSink)

        assertTrue(state.moveSink.list.any { it is Move.IntSet && it.varId == 1 && it.newValue == 5L })
    }

    @Test
    fun `direct repairs offer a correlated output update`() {
        val source = Problem(0, 3, arrayOf(IntDomain(-8, 8), IntDomain(-3, 3), IntDomain(-2, 2)),
            arrayOf<Factor>(DivMod(0, bigIntOf(3), 1, 2)))
        val state = LocalSearchState(LocalSearchModel.open(source), Random(1))
        state.assignment.setInt(0, -7)
        state.assignment.setInt(1, 0)
        state.assignment.setInt(2, 0)
        state.recompute()

        state.factors[0].proposeRepairMoves(state, 0, state.moveSink)

        assertTrue(state.moveSink.list.any { move -> state.netDelta(move) == -state.cost })
    }
}
