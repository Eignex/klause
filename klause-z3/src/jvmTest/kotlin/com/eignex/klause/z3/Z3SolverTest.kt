package com.eignex.klause.z3

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.localsearch.LocalSearchParams
import com.eignex.klause.solver.localsearch.LocalSearchSolver
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.factor.AllDifferent
import com.eignex.klause.solver.factor.Cardinality
import com.eignex.klause.solver.factor.Clause
import com.eignex.klause.solver.factor.Linear
import com.eignex.klause.solver.factor.LinearOp
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

class Z3SolverTest {

    private data class Case(val name: String, val problem: Problem)

    @Test
    fun `solve returns sat for sat portfolio`() {
        for (case in satPortfolio()) {
            val sampler = Z3Solver(case.problem)
            val verdict = sampler.solve(Z3Params())
            val sat = verdict as? SolveResult.Sat
                ?: fail("${case.name}: expected Sat, got $verdict")
            assertSatisfiesProblem(case.problem, sat.assignment, case.name)
        }
    }

    @Test
    fun `solve returns unsat for unsat portfolio`() {
        for (case in unsatPortfolio()) {
            val verdict = Z3Solver(case.problem).solve(Z3Params())
            assertIs<SolveResult.Unsat>(verdict, "${case.name}: expected Unsat")
        }
    }

    @Test
    fun `solve populates unsat core for tracked factor contradictions`() {
        // Two-clause direct contradiction: factor 0 forces var=true, factor 1 forces
        // var=false. Z3's unsat core for this problem must mention both factor ids; an
        // empty or single-id core would mean Z3 didn't see one of them as load-bearing.
        val problem = Problem(
            numBoolVars = 1, numIntVars = 0, intDomains = emptyArray(),
            factors = arrayOf<Factor>(
                Clause(intArrayOf(Lit.make(0, true))),
                Clause(intArrayOf(Lit.make(0, false))),
            ),
        )
        val verdict = Z3Solver(problem).solve(Z3Params())
        val unsat = assertIs<SolveResult.Unsat>(verdict)
        val core = unsat.core ?: fail("expected populated unsat core, got null")
        assertEquals(setOf(0, 1), core.factorIds.toSet(),
            "core should mention both contradicting clauses, got ${core.factorIds.toList()}")
    }

    @Test
    fun `unsat core does not flag uninvolved factors`() {
        // Five factors, only 0 and 1 are contradictory; 2..4 are irrelevant but valid.
        // Z3's core extraction is not guaranteed minimal — but it should not include
        // factors that aren't needed to derive UNSAT. The pair {0,1} is itself unsat,
        // so a sound non-minimal extractor still won't grow the core much.
        val problem = Problem(
            numBoolVars = 4, numIntVars = 0, intDomains = emptyArray(),
            factors = arrayOf<Factor>(
                Clause(intArrayOf(Lit.make(0, true))),
                Clause(intArrayOf(Lit.make(0, false))),
                Clause(intArrayOf(Lit.make(1, true), Lit.make(2, true))),
                Clause(intArrayOf(Lit.make(2, false), Lit.make(3, true))),
                Clause(intArrayOf(Lit.make(3, false), Lit.make(1, false))),
            ),
        )
        val verdict = Z3Solver(problem).solve(Z3Params())
        val core = (verdict as SolveResult.Unsat).core
            ?: fail("expected populated core")
        assertTrue(0 in core.factorIds && 1 in core.factorIds,
            "core must include the contradicting pair, got ${core.factorIds.toList()}")
        // No silly bloat — the unrelated chain shouldn't be in the core.
        for (irrelevant in 2..4) {
            assertTrue(irrelevant !in core.factorIds,
                "factor $irrelevant is irrelevant to the contradiction, got core=${core.factorIds.toList()}")
        }
    }

    @Test
    fun `enumerate produces all solutions exactly once`() {
        val factor = Cardinality.exactlyOne(intArrayOf(
            Lit.make(0, true), Lit.make(1, true), Lit.make(2, true), Lit.make(3, true),
        ))
        val problem = Problem(4, 0, emptyArray(), listOf(factor))
        val samples = Z3Solver(problem).enumerate(Z3Params()).take(10).toList()
        assertEquals(4, samples.size, "expected exactly 4 solutions")
        assertEquals(4, samples.toSet().size, "all solutions must be distinct")
    }

    @Test
    fun `sample honours max models`() {
        val factor = Cardinality.exactlyOne(intArrayOf(
            Lit.make(0, true), Lit.make(1, true), Lit.make(2, true), Lit.make(3, true),
        ))
        val problem = Problem(4, 0, emptyArray(), listOf(factor))
        val samples = Z3Solver(problem).samples(Z3Params(maxModels = 5)).toList()
        assertEquals(5, samples.size, "with-replacement honours maxModels")
        assertTrue(samples.toSet().size <= 4, "all draws must come from the 4 solutions")
    }

    @Test
    fun `max models caps enumerate`() {
        val factor = Cardinality.exactlyOne(intArrayOf(
            Lit.make(0, true), Lit.make(1, true), Lit.make(2, true), Lit.make(3, true),
        ))
        val problem = Problem(4, 0, emptyArray(), listOf(factor))
        val samples = Z3Solver(problem).enumerate(Z3Params(maxModels = 2)).take(10).toList()
        assertTrue(samples.size <= 2, "maxModels caps enumerate; got ${samples.size}")
    }

    @Test
    fun `enumerate honours min hamming distance`() {
        val factor = Cardinality.exactlyOne(intArrayOf(
            Lit.make(0, true), Lit.make(1, true), Lit.make(2, true), Lit.make(3, true),
        ))
        val problem = Problem(4, 0, emptyArray(), listOf(factor))
        val samples = Z3Solver(problem).enumerate(
            Z3Params(minHammingDistance = 3, recentWindow = 8),
        ).take(8).toList()
        for (i in samples.indices) {
            for (j in (i + 1) until samples.size) {
                val d = samples[i].hammingDistanceTo(samples[j])
                assertTrue(d >= 3, "samples $i, $j at distance $d violates minHammingDistance=3")
            }
        }
    }

    @Test
    fun `local search and z3 agree on satisfiability`() {
        for (case in satPortfolio() + unsatPortfolio()) {
            val ls = LocalSearchSolver(case.problem)
                .solve(LocalSearchParams(maxFlips = 50_000L, randomSeed = 0L))
            val z3 = Z3Solver(case.problem).solve(Z3Params())
            when (z3) {
                is SolveResult.Sat -> assertTrue(ls is SolveResult.Sat,
                    "${case.name}: Z3 SAT but LS got $ls")
                is SolveResult.Unsat -> assertTrue(ls is SolveResult.Unknown || ls is SolveResult.Unsat,
                    "${case.name}: Z3 UNSAT but LS got $ls (should be Unknown or Unsat-via-propagation)")
                is SolveResult.Unknown -> {}
            }
        }
    }

    private fun satPortfolio(): List<Case> = listOf(
        Case("threeClauses",
            Problem(numBoolVars = 4, numIntVars = 0, intDomains = emptyArray(), factors = arrayOf<Factor>(
                Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, false))),
                Clause(intArrayOf(Lit.make(0, false), Lit.make(2, true), Lit.make(3, true))),
                Clause(intArrayOf(Lit.make(1, false), Lit.make(3, true))),
            )),
        ),
        Case("linearLE",
            Problem(numBoolVars = 0, numIntVars = 2,
                intDomains = arrayOf(IntDomain(0, 3), IntDomain(0, 3)),
                factors = arrayOf<Factor>(
                    Linear(coeffs = intArrayOf(1, 1), vars = intArrayOf(0, 1), op = LinearOp.LE, 4),
                    Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 1),
                    Linear(intArrayOf(1), intArrayOf(1), LinearOp.LE, 2),
                ),
            ),
        ),
        Case("permutation3",
            Problem(numBoolVars = 0, numIntVars = 3,
                intDomains = arrayOf(IntDomain(0, 2), IntDomain(0, 2), IntDomain(0, 2)),
                factors = arrayOf<Factor>(AllDifferent(vars = intArrayOf(0, 1, 2), domainMin = 0, domainSize = 3)),
            ),
        ),
    )

    private fun unsatPortfolio(): List<Case> = listOf(
        Case("clauseContradiction",
            Problem(numBoolVars = 1, numIntVars = 0, intDomains = emptyArray(), factors = arrayOf<Factor>(
                Clause(intArrayOf(Lit.make(0, true))),
                Clause(intArrayOf(Lit.make(0, false))),
            )),
        ),
        Case("intEqContradiction",
            Problem(numBoolVars = 0, numIntVars = 1, intDomains = arrayOf(IntDomain(0, 3)), factors = arrayOf<Factor>(
                Linear(intArrayOf(1), intArrayOf(0), LinearOp.EQ, 1),
                Linear(intArrayOf(1), intArrayOf(0), LinearOp.EQ, 3),
            )),
        ),
        Case("pigeonhole",
            Problem(numBoolVars = 0, numIntVars = 3,
                intDomains = arrayOf(IntDomain(0, 1), IntDomain(0, 1), IntDomain(0, 1)),
                factors = arrayOf<Factor>(AllDifferent(vars = intArrayOf(0, 1, 2), domainMin = 0, domainSize = 2)),
            ),
        ),
    )

    private fun assertSatisfiesProblem(problem: Problem, sample: Sample, label: String) {
        val state = LocalSearchState(problem, Random(0))
        for (b in 0 until problem.numBoolVars) state.assignment.setBool(b, sample.bools[b])
        for (i in 0 until problem.numIntVars) state.assignment.setInt(i, sample.ints[i])
        state.recompute()
        assertEquals(0, state.cost,
            "$label: sample $sample violates ${state.violated.size} hard factor(s)")
    }}
