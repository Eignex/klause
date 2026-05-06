package com.eignex.klause.logicng

import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.LocalSearchParams
import com.eignex.klause.solver.LocalSearchSolver
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.SolverState
import com.eignex.klause.solver.factor.AllDifferent
import com.eignex.klause.solver.factor.Cardinality
import com.eignex.klause.solver.factor.Clause
import com.eignex.klause.solver.factor.IntEq
import com.eignex.klause.solver.factor.IntGeq
import com.eignex.klause.solver.factor.IntLeq
import com.eignex.klause.solver.factor.Linear
import com.eignex.klause.solver.factor.LinearOp
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class LogicNGSamplerTest {

    private data class Case(val name: String, val problem: Problem)

    @Test
    fun solveReturnsSatForSatPortfolio() {
        for (case in satPortfolio()) {
            val sampler = LogicNGSampler(case.problem)
            val verdict = sampler.solve(LogicNGParams())
            val sat = verdict as? SolveResult.Sat
                ?: fail("${case.name}: expected Sat, got $verdict")
            assertSatisfiesProblem(case.problem, sat.assignment, case.name)
        }
    }

    @Test
    fun solveReturnsUnsatForUnsatPortfolio() {
        for (case in unsatPortfolio()) {
            val verdict = LogicNGSampler(case.problem).solve(LogicNGParams())
            assertEquals(SolveResult.Unsat, verdict, "${case.name}: expected Unsat")
        }
    }

    @Test
    fun enumerateProducesAllSolutionsExactlyOnce() {
        // ExactlyOne over 4 vars has exactly 4 solutions.
        val factor = Cardinality.exactlyOne(
            intArrayOf(
                Lit.make(0, true),
                Lit.make(1, true),
                Lit.make(2, true),
                Lit.make(3, true),
            )
        )
        val problem = Problem(4, 0, emptyArray(), listOf(factor))
        val samples = LogicNGSampler(problem).enumerate(LogicNGParams()).take(10).toList()
        assertEquals(4, samples.size, "expected exactly 4 solutions")
        assertEquals(4, samples.toSet().size, "all solutions must be distinct")
    }

    @Test
    fun sampleAllowsDuplicates() {
        // Same problem; sample (with replacement) draws independently. With 20 draws over
        // 4 solutions and a deterministic solver, we'll get exactly the same model 20
        // times — that's the "with replacement" extreme. Assert at most 4 unique values
        // appear, and the budget is exhausted (no early termination).
        val factor = Cardinality.exactlyOne(
            intArrayOf(
                Lit.make(0, true),
                Lit.make(1, true),
                Lit.make(2, true),
                Lit.make(3, true),
            )
        )
        val problem = Problem(4, 0, emptyArray(), listOf(factor))
        val samples = LogicNGSampler(problem).sample(LogicNGParams(maxModels = 5)).toList()
        assertEquals(5, samples.size, "with-replacement honours maxModels")
        assertTrue(samples.toSet().size <= 4, "all draws must come from the 4 solutions")
    }

    @Test
    fun maxModelsCapsEnumerate() {
        val factor = Cardinality.exactlyOne(
            intArrayOf(
                Lit.make(0, true),
                Lit.make(1, true),
                Lit.make(2, true),
                Lit.make(3, true),
            )
        )
        val problem = Problem(4, 0, emptyArray(), listOf(factor))
        val samples = LogicNGSampler(problem).enumerate(LogicNGParams(maxModels = 2)).take(10).toList()
        assertTrue(samples.size <= 2, "maxModels caps enumerate; got ${samples.size}")
    }

    @Test
    fun enumerateHonoursMinHammingDistance() {
        // Adjacent solutions of exactlyOne over 4 vars are at Hamming distance 2; require 3.
        val factor = Cardinality.exactlyOne(
            intArrayOf(
                Lit.make(0, true),
                Lit.make(1, true),
                Lit.make(2, true),
                Lit.make(3, true),
            )
        )
        val problem = Problem(4, 0, emptyArray(), listOf(factor))
        val samples = LogicNGSampler(problem).enumerate(
            LogicNGParams(minHammingDistance = 3, recentWindow = 8)
        ).take(8).toList()
        for (i in samples.indices) {
            for (j in (i + 1) until samples.size) {
                val d = hamming(samples[i], samples[j])
                assertTrue(d >= 3, "samples $i, $j at distance $d violates minHammingDistance=3")
            }
        }
    }

    @Test
    fun localSearchAndLogicNGAgreeOnSatisfiability() {
        // Both backends should agree on SAT/UNSAT for every portfolio problem.
        for (case in satPortfolio() + unsatPortfolio()) {
            val ls = LocalSearchSolver(case.problem)
                .solve(LocalSearchParams(maxFlips = 50_000L, randomSeed = 0L))
            val ng = LogicNGSampler(case.problem).solve(LogicNGParams())
            // LS never returns Unsat; it can be Sat or Unknown. LogicNG is exact.
            when (ng) {
                is SolveResult.Sat -> assertTrue(
                    ls is SolveResult.Sat,
                    "${case.name}: LogicNG SAT but LS got $ls"
                )
                SolveResult.Unsat -> assertTrue(
                    ls is SolveResult.Unknown,
                    "${case.name}: LogicNG UNSAT but LS got $ls (LS should run out of flips)"
                )
                SolveResult.Unknown -> {} // skip; portfolio is sized below LogicNG timeout
            }
        }
    }

    // ---------------------- Portfolio (mirror of SolverVsBitBlasterTest) ----------------------

    private fun satPortfolio(): List<Case> = listOf(
        Case(
            "threeClauses",
            Problem(
                numBoolVars = 4,
                numIntVars = 0,
                intDomains = emptyArray(),
                factors = listOf(
                    Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, false))),
                    Clause(intArrayOf(Lit.make(0, false), Lit.make(2, true), Lit.make(3, true))),
                    Clause(intArrayOf(Lit.make(1, false), Lit.make(3, true))),
                )
            ),
        ),
        Case(
            "linearLE",
            Problem(
                numBoolVars = 0,
                numIntVars = 2,
                intDomains = arrayOf(IntDomain(0, 3), IntDomain(0, 3)),
                factors = listOf(
                    Linear(coeffs = intArrayOf(1, 1), vars = intArrayOf(0, 1), op = LinearOp.LE, bound = 4),
                    IntGeq(intVar = 0, bound = 1),
                    IntLeq(intVar = 1, bound = 2),
                )
            ),
        ),
        Case(
            "permutation3",
            Problem(
                numBoolVars = 0,
                numIntVars = 3,
                intDomains = arrayOf(IntDomain(0, 2), IntDomain(0, 2), IntDomain(0, 2)),
                factors = listOf(AllDifferent(vars = intArrayOf(0, 1, 2), domainMin = 0, domainSize = 3)),
            ),
        ),
    )

    private fun unsatPortfolio(): List<Case> = listOf(
        Case(
            "clauseContradiction",
            Problem(
                numBoolVars = 1,
                numIntVars = 0,
                intDomains = emptyArray(),
                factors = listOf(
                    Clause(intArrayOf(Lit.make(0, true))),
                    Clause(intArrayOf(Lit.make(0, false))),
                )
            ),
        ),
        Case(
            "intEqContradiction",
            Problem(
                numBoolVars = 0,
                numIntVars = 1,
                intDomains = arrayOf(IntDomain(0, 3)),
                factors = listOf(
                    IntEq(intVar = 0, value = 1),
                    IntEq(intVar = 0, value = 3),
                )
            ),
        ),
        Case(
            "pigeonhole",
            Problem(
                numBoolVars = 0,
                numIntVars = 3,
                intDomains = arrayOf(IntDomain(0, 1), IntDomain(0, 1), IntDomain(0, 1)),
                factors = listOf(AllDifferent(vars = intArrayOf(0, 1, 2), domainMin = 0, domainSize = 2)),
            ),
        ),
    )

    // ---------------------- Helpers ----------------------

    private fun assertSatisfiesProblem(problem: Problem, sample: Sample, label: String) {
        // Verify the sample satisfies every hard factor of the original problem (not the
        // bit-blasted CNF — Tseitin aux vars aren't part of the sample so re-pinning would
        // give a false negative).
        val state = SolverState(problem, Random(0))
        for (b in 0 until problem.numBoolVars) state.assignment.setBool(b, sample.bools[b])
        for (i in 0 until problem.numIntVars) state.assignment.setInt(i, sample.ints[i])
        state.recompute()
        assertEquals(
            0,
            state.hardCost,
            "$label: sample $sample violates ${state.violated.size} hard factor(s)"
        )
    }

    private fun hamming(a: Sample, b: Sample): Int {
        var d = 0
        for (i in a.bools.indices) if (a.bools[i] != b.bools[i]) d++
        for (i in a.ints.indices) if (a.ints[i] != b.ints[i]) d++
        return d
    }
}
