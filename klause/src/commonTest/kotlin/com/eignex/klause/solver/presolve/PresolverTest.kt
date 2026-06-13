package com.eignex.klause.solver.presolve

import com.eignex.klause.solver.Assumptions
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.backtrack.BacktrackSolver
import com.eignex.klause.solver.factor.AllDifferent
import com.eignex.klause.solver.factor.Linear
import com.eignex.klause.solver.factor.LinearOp
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.propagation.PropagationResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PresolverTest {

    private fun isFeasible(problem: Problem, sample: Sample): Boolean {
        var a = Assumptions.None
        for (v in 0 until problem.numBoolVars) a = a.withBool(v, sample.bools[v])
        for (v in 0 until problem.numIntVars) a = a.withInt(v, sample.ints[v])
        return problem.propagate(a) !is PropagationResult.Unsat
    }

    @Test
    fun `parse handles aliases and comma-lists`() {
        val ctx = PresolveContext.EMPTY
        // null / default / auto → all auto: the three problem passes run for a non-sensitive query.
        val autoProblem = listOf(
            PresolvePass.STRENGTHEN_COEFFICIENTS,
            PresolvePass.ELIMINATE_AFFINE_SINGLETONS,
            PresolvePass.BREAK_SYMMETRIES,
        )
        assertEquals(autoProblem, PresolveConfig.parse(null).problemPasses(ctx))
        assertEquals(autoProblem, PresolveConfig.parse("default").problemPasses(ctx))
        assertEquals(autoProblem, PresolveConfig.parse("auto").problemPasses(ctx))
        // none → nothing runs.
        assertEquals(emptyList(), PresolveConfig.parse("none").problemPasses(ctx))
        // comma-list forces exactly those on, everything else off (application = enum order).
        assertEquals(
            listOf(PresolvePass.STRENGTHEN_COEFFICIENTS, PresolvePass.ELIMINATE_AFFINE_SINGLETONS),
            PresolveConfig.parse("affine, strengthen").problemPasses(ctx),
        )
        assertFailsWith<IllegalStateException> { PresolveConfig.parse("bogus") }
    }

    @Test
    fun `auto resolution is intent-aware and SAC is opt-in`() {
        val auto = PresolveConfig.AUTO
        // Symmetry breaking is solution-set-altering: auto-on for solve, auto-off when the query
        // needs the full solution set (enumeration / counting / sampling).
        assertTrue(PresolvePass.BREAK_SYMMETRIES in auto.problemPasses(PresolveContext.EMPTY))
        assertTrue(
            PresolvePass.BREAK_SYMMETRIES !in
                auto.problemPasses(PresolveContext(solutionSetSensitive = true)),
        )
        // Construction-time SAC probes are expensive → auto-off; explicit `all` turns them on.
        assertEquals(false, auto.resolved(PresolvePass.PROBE_INT_BOUNDS, PresolveContext.EMPTY))
        assertEquals(true, PresolveConfig.parse("all").resolved(PresolvePass.PROBE_INT_HOLES, PresolveContext.EMPTY))
        // forLocalSearch forces every solution-set-altering pass off, even under a non-sensitive
        // query — here the default emphasis plus an explicit value-precedence override.
        val ls = PresolveConfig(PresolveConfig.AUTO.emphasis, mapOf(PresolvePass.VALUE_PRECEDENCE to true))
            .forLocalSearch()
        val lsPasses = ls.problemPasses(PresolveContext.EMPTY)
        assertTrue(PresolvePass.BREAK_SYMMETRIES !in lsPasses)
        assertTrue(PresolvePass.VALUE_PRECEDENCE !in lsPasses)
        // …but the cheap solution-preserving reductions stay on.
        assertTrue(PresolvePass.STRENGTHEN_COEFFICIENTS in lsPasses)
    }

    @Test
    fun `every pass self-registers and is dispatchable`() {
        // The enum is the registry: a new entry must declare metadata + apply to compile, lands in
        // `entries` automatically, and the engine dispatches it polymorphically (no central `when`).
        // This guards the remaining conventions so a malformed addition fails loudly.
        val ids = PresolvePass.entries.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "pass ids must be unique")
        for (p in PresolvePass.entries) {
            assertSame(p, PresolvePass.fromId(p.id), "id `${p.id}` must round-trip through fromId")
        }
        // Every problem-stage pass applies cleanly to a trivial problem — no unhandled entry.
        val trivial = Problem(
            0,
            1,
            arrayOf(IntDomain(0, 2)),
            listOf(Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 2)),
        )
        for (p in PresolvePass.entries.filter { it.stage == PresolvePass.Stage.PROBLEM }) {
            val result = p.apply(trivial, PresolveContext.EMPTY)
            assertEquals(trivial.numIntVars, result.problem.numIntVars, "${p.id} returned a malformed problem")
        }
    }

    @Test
    fun `emphasis levels select cost tiers`() {
        val ctx = PresolveContext.EMPTY
        // off → nothing.
        assertEquals(emptyList(), PresolveConfig.parse("off").problemPasses(ctx))
        // conservative → FAST tier only (strengthen + affine), no symmetry, single round.
        assertEquals(
            listOf(PresolvePass.STRENGTHEN_COEFFICIENTS, PresolvePass.ELIMINATE_AFFINE_SINGLETONS),
            PresolveConfig.parse("conservative").problemPasses(ctx),
        )
        assertEquals(1, PresolveConfig.parse("conservative").emphasis.maxRounds)
        // default → adds symmetry (MEDIUM) and iterates; SAC probes (EXHAUSTIVE) stay off.
        assertTrue(PresolvePass.BREAK_SYMMETRIES in PresolveConfig.parse("default").problemPasses(ctx))
        assertEquals(false, PresolveConfig.parse("default").resolved(PresolvePass.PROBE_INT_HOLES, ctx))
        assertTrue(PresolveConfig.parse("default").emphasis.maxRounds > 1)
        // aggressive → also enables the EXHAUSTIVE SAC probes (the compilers read these via resolved).
        assertEquals(true, PresolveConfig.parse("aggressive").resolved(PresolvePass.PROBE_INT_HOLES, ctx))
        assertEquals(true, PresolveConfig.parse("aggressive").resolved(PresolvePass.PROBE_FAILED_LITERALS, ctx))
        // value precedence stays opt-in even at aggressive (it interacts with variable symmetry).
        assertEquals(false, PresolveConfig.parse("aggressive").resolved(PresolvePass.VALUE_PRECEDENCE, ctx))
        assertTrue(PresolvePass.VALUE_PRECEDENCE in PresolveConfig.parse("value-precede").problemPasses(ctx))
    }

    @Test
    fun `the round engine iterates to a fixpoint`() {
        // affine x=2y+1 substituted into 2x+4y<=10 leaves a row strengthen reduces — which only
        // happens on a second round (strengthen runs before affine in the first). Re-presolving the
        // result must then change nothing: the single run already reached the fixpoint.
        val problem = Problem(
            0,
            2,
            arrayOf(IntDomain(0, 9), IntDomain(0, 3)),
            listOf(
                Linear(intArrayOf(1, -2), intArrayOf(0, 1), LinearOp.EQ, 1),
                Linear(intArrayOf(2, 4), intArrayOf(0, 1), LinearOp.LE, 10),
            ),
        )
        val once = Presolver.run(problem, PresolveConfig.AUTO).problem
        assertTrue(once !== problem, "expected the engine to transform the problem")
        assertSame(once, Presolver.run(once, PresolveConfig.AUTO).problem, "re-presolving a fixpoint must be a no-op")
    }

    @Test
    fun `context extracts nonzero objective coefficients`() {
        val obj = LinearObjective(
            boolWeights = longArrayOf(0, 3, 0),
            intCoefficients = longArrayOf(5, 0, 2),
        )
        val ctx = PresolveContext.of(obj)
        assertEquals(setOf(0, 2), ctx.objectiveIntVars)
        assertEquals(setOf(1), ctx.objectiveBoolVars)
        assertTrue(PresolveContext.of(null).objectiveIntVars.isEmpty())
    }

    @Test
    fun `empty config is the identity`() {
        val problem = Problem(
            0,
            1,
            arrayOf(IntDomain(0, 3)),
            listOf(Linear(intArrayOf(2), intArrayOf(0), LinearOp.LE, 4)),
        )
        val pre = Presolver.run(problem, PresolveConfig.NONE)
        assertSame(problem, pre.problem)
        val s = Sample(BooleanArray(0), intArrayOf(2))
        assertSame(s, pre.reconstruct(s))
    }

    @Test
    fun `default pipeline composes and reconstructs to a feasible original solution`() {
        // var 0: affine singleton (x = 2y+1 via x - 2y = 1), only here.
        // vars 1,2: GCD-reducible sum 2y+2z<=4 with y,z interchangeable (equal coeff) -> symmetry.
        val problem = Problem(
            0,
            3,
            arrayOf(IntDomain(0, 9), IntDomain(0, 3), IntDomain(0, 3)),
            listOf(
                Linear(intArrayOf(1, -2), intArrayOf(0, 1), LinearOp.EQ, 1),
                Linear(intArrayOf(2, 2), intArrayOf(1, 2), LinearOp.LE, 4),
            ),
        )
        val pre = Presolver.run(problem, PresolveConfig.DEFAULT)
        assertTrue(pre.problem !== problem, "expected the pipeline to transform the problem")
        val result = BacktrackSolver(pre.problem).solve(BacktrackParams())
        assertTrue(result is SolveResult.Sat, "presolved problem should be SAT, got $result")
        val full = pre.reconstruct(result.assignment)
        assertEquals(2 * full.ints[1] + 1, full.ints[0], "affine var not reconstructed: x should be 2y+1")
        assertTrue(isFeasible(problem, full), "reconstructed sample infeasible in the original problem")
    }

    @Test
    fun `value-precede pass posts a precedence chain and stays satisfiable`() {
        // AllDifferent over {0,1,2}: value-anonymous, so the opt-in value-precedence pass posts a
        // value_precede_chain (native ValuePrecede factors, #432) collapsing the 6 permutations to the
        // single canonical 0,1,2. Same variable space — reconstruct is the identity.
        val problem = Problem(
            0,
            3,
            Array(3) { IntDomain(0, 2) },
            listOf(AllDifferent(intArrayOf(0, 1, 2), domainMin = 0, domainSize = 3)),
        )
        val pre = Presolver.run(problem, PresolveConfig.parse("value-precede"))
        assertTrue(pre.problem !== problem, "value precedence should add precedence factors")
        assertEquals(problem.numIntVars, pre.problem.numIntVars, "no auxiliary variables are added")
        val result = BacktrackSolver(pre.problem).solve(BacktrackParams())
        assertTrue(result is SolveResult.Sat, "presolved problem should be SAT, got $result")
        val full = pre.reconstruct(result.assignment)
        assertEquals(listOf(0, 1, 2), full.ints.toList(), "the single canonical permutation")
        assertTrue(isFeasible(problem, full), "reconstructed sample infeasible in the original problem")
    }

    @Test
    fun `affine pass protects objective variables`() {
        // x (0) is an affine singleton, but it is the objective variable -> must not be eliminated.
        val problem = Problem(
            0,
            2,
            arrayOf(IntDomain(0, 9), IntDomain(0, 3)),
            listOf(Linear(intArrayOf(1, -2), intArrayOf(0, 1), LinearOp.EQ, 1)),
        )
        val ctx = PresolveContext.of(LinearObjective(intCoefficients = longArrayOf(1, 0)))
        val pre = Presolver.run(problem, PresolveConfig.parse("affine"), ctx)
        // Nothing eliminated -> identity problem, identity reconstruct.
        assertSame(problem, pre.problem)
    }
}
