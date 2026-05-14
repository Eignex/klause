package com.eignex.klause.solver
import com.eignex.klause.solver.localsearch.LocalSearchFactor

import com.eignex.klause.solver.localsearch.SolverState

import com.eignex.klause.ast.IntCmpOp
import com.eignex.klause.ast.PbOp
import com.eignex.klause.solver.factor.AllDifferent
import com.eignex.klause.solver.factor.Cardinality
import com.eignex.klause.solver.factor.Clause
import com.eignex.klause.solver.factor.Linear
import com.eignex.klause.solver.factor.LinearOp
import com.eignex.klause.solver.factor.PseudoBoolean
import com.eignex.klause.solver.factor.ReifiedCardinality
import com.eignex.klause.solver.factor.ReifiedIntCompare
import com.eignex.klause.solver.factor.ReifiedLinear
import com.eignex.klause.solver.factor.Xor
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SolverStateRecomputeTest {

    private data class Case(val name: String, val problem: Problem)

    private val cases: List<Case> = listOf(
        boolHeavyCase(),
        intHeavyCase(),
        mixedReifiedCase(),
        permutationCase(),
    )

    @Test
    fun `after random moves state matches fresh recompute`() {
        for (case in cases) {
            for (seed in 0 until 8) {
                val state = SolverState(case.problem, Random(seed.toLong()))
                state.restart()

                val rng = Random(seed.toLong() xor 0xBEEFL)
                repeat(50) {
                    val move = randomMove(case.problem, state, rng) ?: return@repeat
                    state.apply(move)
                }

                val sibling = SolverState(case.problem, Random(seed.toLong()))
                copyAssignment(state, sibling)
                sibling.recompute()

                assertEquals(sibling.cost, state.cost,
                    "${case.name} seed=$seed: cost drifted from recompute")
                assertEquals(sibling.violated.toIntArray().sortedArray().toList(),
                    state.violated.toIntArray().sortedArray().toList(),
                    "${case.name} seed=$seed: violated set drifted")
                for (fid in 0 until case.problem.numFactors) {
                    assertEquals(sibling.intPayload[fid], state.intPayload[fid],
                        "${case.name} seed=$seed: intPayload[$fid] drifted")
                    val fa = case.problem.factors[fid] as LocalSearchFactor
                    assertEquals(
                        fa.isViolated(sibling, fid),
                        fa.isViolated(state, fid),
                        "${case.name} seed=$seed: factor $fid (${fa::class.simpleName}) " +
                            "isViolated drifted",
                    )
                }
            }
        }
    }

    @Test
    fun `violated set is subset of factor space`() {

        for (case in cases) {
            val state = SolverState(case.problem, Random(0))
            state.restart()
            val rng = Random(0xCAFEL)
            repeat(100) {
                val move = randomMove(case.problem, state, rng) ?: return@repeat
                state.apply(move)
                for (fid in state.violated.toIntArray()) {
                    assertTrue(fid in 0 until case.problem.numFactors,
                        "${case.name}: violated set contains out-of-range $fid")
                }
            }
        }
    }

    private fun randomMove(problem: Problem, state: SolverState, rng: Random): Move? {
        val haveBool = problem.numBoolVars > 0
        val haveInt = problem.numIntVars > 0
        val pickBool = when {
            !haveBool -> false
            !haveInt -> true
            else -> rng.nextBoolean()
        }
        return if (pickBool) {
            Move.BoolFlip(rng.nextInt(problem.numBoolVars))
        } else {
            val v = rng.nextInt(problem.numIntVars)
            val d = problem.intDomains[v]
            val cur = state.assignment.intValue(v)
            var target = cur
            repeat(8) {
                val cand = d.min + rng.nextInt(d.size)
                if (cand != cur) { target = cand; return@repeat }
            }
            if (target == cur) null else Move.IntSet(v, target)
        }
    }

    private fun copyAssignment(src: SolverState, dst: SolverState) {
        for (b in 0 until src.problem.numBoolVars) dst.assignment.setBool(b, src.assignment.boolValue(b))
        for (i in 0 until src.problem.numIntVars) dst.assignment.setInt(i, src.assignment.intValue(i))
    }

    private fun boolHeavyCase(): Case {

        val factors = listOf(
            Clause(intArrayOf(Lit.make(0, true), Lit.make(1, false), Lit.make(2, true))),
            Clause(intArrayOf(Lit.make(2, false), Lit.make(3, true), Lit.make(4, true))),
            Clause(intArrayOf(Lit.make(0, false), Lit.make(4, false))),
            Cardinality(
                literals = intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true), Lit.make(3, true)),
                min = 1, max = 3,
            ),
            Xor(intArrayOf(Lit.make(0, true), Lit.make(2, true), Lit.make(4, true)), targetParity = 1),
            PseudoBoolean(
                weights = intArrayOf(2, 1, 3, 1),
                literals = intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, false), Lit.make(3, true)),
                op = PbOp.LE, bound = 4,
            ),
        )
        return Case("boolHeavy", Problem(numBoolVars = 5, numIntVars = 0, intDomains = emptyArray(), factors = factors))
    }

    private fun intHeavyCase(): Case {

        val intDomains = arrayOf(IntDomain(-3, 3), IntDomain(-3, 3), IntDomain(0, 5))
        val factors = listOf(
            Linear(coeffs = intArrayOf(2, -1, 1), vars = intArrayOf(0, 1, 2), op = LinearOp.LE, 4),
            Linear(coeffs = intArrayOf(1, 1, 1), vars = intArrayOf(0, 1, 2), op = LinearOp.GE, -1),
            com.eignex.klause.solver.factor.Linear(intArrayOf(1), intArrayOf(2), LinearOp.LE, 4),
            com.eignex.klause.solver.factor.Linear(intArrayOf(1), intArrayOf(0), LinearOp.NE, 0),
        )
        return Case("intHeavy", Problem(numBoolVars = 0, numIntVars = 3, intDomains = intDomains, factors = factors))
    }

    private fun mixedReifiedCase(): Case {

        val intDomains = arrayOf(IntDomain(-2, 3), IntDomain(-2, 3))
        val factors = listOf(
            ReifiedLinear(
                auxBoolVar = 0,
                coeffs = intArrayOf(2, -1),
                vars = intArrayOf(0, 1),
                op = LinearOp.LE, bound = 3,
            ),
            ReifiedCardinality(
                auxBoolVar = 1,
                literals = intArrayOf(Lit.make(2, true), Lit.make(3, true), Lit.make(4, true)),
                min = 1, max = 2,
            ),
            ReifiedIntCompare(auxBoolVar = 5, intVar = 0, op = IntCmpOp.GE, 0),
            Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(5, false))),
        )
        return Case("mixedReified", Problem(numBoolVars = 6, numIntVars = 2, intDomains = intDomains, factors = factors))
    }

    private fun permutationCase(): Case {

        val intDomains = arrayOf(IntDomain(0, 3), IntDomain(0, 3), IntDomain(0, 3), IntDomain(0, 3))
        val factors = listOf(
            AllDifferent(vars = intArrayOf(0, 1, 2, 3), domainMin = 0, domainSize = 4),
            com.eignex.klause.solver.factor.Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 2),
            com.eignex.klause.solver.factor.Linear(intArrayOf(1), intArrayOf(3), LinearOp.GE, 1),
        )
        return Case("permutation", Problem(numBoolVars = 0, numIntVars = 4, intDomains = intDomains, factors = factors))
    }
}
