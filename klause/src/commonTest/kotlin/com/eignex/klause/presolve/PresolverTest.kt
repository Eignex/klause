package com.eignex.klause.presolve

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.presolve.PresolveShared.withPassDelta
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.Sample
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PresolverTest {

    @Test
    fun `parse handles aliases and comma-lists`() {
        val ctx = PresolveContext.EMPTY
        val autoProblem = listOf(
            PresolvePass.STRENGTHEN_COEFFICIENTS,
            PresolvePass.REDUCE_DIOPHANTINE,
            PresolvePass.DERIVE_XOR_UNITS,
            PresolvePass.FUSE_LINEAR_BOUNDS,
            PresolvePass.ELIMINATE_AFFINE_SINGLETONS,
            PresolvePass.AGGREGATE_SUB_SUMS,
            PresolvePass.REMOVE_REDUNDANT,
            PresolvePass.DROP_DEPENDENT_EQUALITIES,
            PresolvePass.REDUCE_STRUCTURAL,
            PresolvePass.FOLD_COMPARISON_CLAUSES,
            PresolvePass.MERGE_DUPLICATE_COLUMNS,
            PresolvePass.PROJECT_SINGLETON_INEQUALITIES,
            PresolvePass.BREAK_SYMMETRIES,
            PresolvePass.DUAL_FIX,
            PresolvePass.MERGE_AMO_CLIQUES,
        )
        assertEquals(autoProblem, PresolveConfig.parse(null).problemPasses(ctx))
        assertEquals(autoProblem, PresolveConfig.parse("default").problemPasses(ctx))
        assertEquals(autoProblem, PresolveConfig.parse("auto").problemPasses(ctx))
        assertEquals(emptyList(), PresolveConfig.parse("none").problemPasses(ctx))
        assertEquals(
            listOf(PresolvePass.STRENGTHEN_COEFFICIENTS, PresolvePass.ELIMINATE_AFFINE_SINGLETONS),
            PresolveConfig.parse("affine, strengthen").problemPasses(ctx),
        )
        assertFailsWith<IllegalStateException> { PresolveConfig.parse("bogus") }
    }

    @Test
    fun `parse emphasis plus deltas toggles a single pass`() {
        val ctx = PresolveContext.EMPTY
        val noSymmetry = PresolveConfig.parse("default,-symmetry")
        assertEquals(PresolveEmphasis.DEFAULT, noSymmetry.emphasis)
        assertEquals(false, noSymmetry.resolved(PresolvePass.BREAK_SYMMETRIES, ctx))
        assertTrue(noSymmetry.resolved(PresolvePass.STRENGTHEN_COEFFICIENTS, ctx))
        val justSymmetry = PresolveConfig.parse("off,+symmetry")
        assertEquals(PresolveEmphasis.OFF, justSymmetry.emphasis)
        assertEquals(true, justSymmetry.resolved(PresolvePass.BREAK_SYMMETRIES, ctx))
        assertEquals(false, justSymmetry.resolved(PresolvePass.STRENGTHEN_COEFFICIENTS, ctx))
        assertFailsWith<IllegalStateException> { PresolveConfig.parse("default,symmetry") }
        assertFailsWith<IllegalStateException> { PresolveConfig.parse("default,+bogus") }
    }

    @Test
    fun `the source lane drops a solution-set-altering pass for a sensitive query`() {
        // Model counting runs through the shared source phase, so the gate that keeps it exact has to
        // hold on the SOURCE overload too — and until dual fixing was ported, no SOURCE pass altered
        // the solution set, so nothing exercised it. Every pass the source lane offers a sensitive
        // query must preserve the set.
        val auto = PresolveConfig.AUTO
        val sensitive = PresolveContext(solutionSetSensitive = true)
        val sourcePasses = auto.problemPasses(sensitive, PresolvePass.Capability.SOURCE)

        assertTrue(
            PresolvePass.DUAL_FIX in auto.problemPasses(PresolveContext.EMPTY, PresolvePass.Capability.SOURCE),
            "dual fixing is a source pass for an ordinary solve",
        )
        assertTrue(PresolvePass.DUAL_FIX !in sourcePasses, "dual fixing discards optimum-equivalent solutions")
        assertTrue(sourcePasses.all { it.preservesSolutionSet }, "a sensitive query gets only exact source passes")
        // The cheap exact reductions stay, so the gate narrows the lane rather than closing it.
        assertTrue(PresolvePass.STRENGTHEN_COEFFICIENTS in sourcePasses)
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
        // The enum is the registry: ids must be unique and round-trip through fromId, and every
        // problem-stage pass must apply cleanly (no unhandled entry) — a malformed addition fails loudly.
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
        val baked = trivial.bake()
        for (p in PresolvePass.entries.filter { it.stage == PresolvePass.Stage.PROBLEM }) {
            val applied = baked.withPassDelta(p.applyFinite(baked, PresolveContext.EMPTY), BakeConfig.NONE)
            assertEquals(trivial.numIntVars, applied.numIntVars, "${p.id} returned a malformed problem")
        }
    }

    @Test
    fun `the open-range probe gives way to the full probe`() {
        val passes = PresolveConfig.parse("aggressive")
            .problemPasses(PresolveContext.EMPTY, PresolvePass.Capability.SOURCE)

        assertEquals(listOf(PresolvePass.PROBE), passes.filter { it.id.startsWith("probe") })
    }

    @Test
    fun `an already-fired cancellation makes presolve a no-op`() {
        // Same problem the default pipeline transforms above; with the deadline already past, the round
        // engine must not run a single pass and must return the input verbatim. The transforms are
        // individually sound, so returning early is safe — this guards that the exit check is honoured.
        val problem = Problem(
            0,
            3,
            arrayOf(IntDomain(0, 9), IntDomain(0, 3), IntDomain(0, 3)),
            listOf(
                Linear(intArrayOf(1, -2), intArrayOf(0, 1), LinearOp.EQ, 1),
                Linear(intArrayOf(2, 2), intArrayOf(1, 2), LinearOp.LE, 4),
            ),
        )
        val baked = problem.bake()
        val pre = Presolver.run(baked, PresolveConfig.DEFAULT, cancellation = { true })
        assertSame(baked, pre.problem, "a fired cancellation must skip every pass and return the input")
        val s = Sample(BooleanArray(0), longArrayOf(3, 1, 0))
        assertSame(s, pre.reconstruct(s), "no pass ran, so reconstruct is the identity")
    }

    @Test
    fun `affine elimination is gated off for solution-set-sensitive queries`() {
        // Affine elimination leaves the eliminated variable unconstrained in the reduced problem
        // (its value is rebuilt from its partner on the way back). That is fine for solve/optimize, but
        // a complete enumerator would branch over the freed variable's whole domain and yield each real
        // solution once per spurious value (#507). So it must NOT run when the caller needs the exact
        // solution set / count.
        val auto = PresolveConfig.AUTO
        assertTrue(PresolvePass.ELIMINATE_AFFINE_SINGLETONS in auto.problemPasses(PresolveContext.EMPTY))
        assertTrue(
            PresolvePass.ELIMINATE_AFFINE_SINGLETONS !in
                auto.problemPasses(PresolveContext(solutionSetSensitive = true)),
        )
        // ...but local search (which never enumerates) keeps it on — it only shrinks the problem there.
        val lsPasses = auto.forLocalSearch().problemPasses(PresolveContext.EMPTY)
        assertTrue(PresolvePass.ELIMINATE_AFFINE_SINGLETONS in lsPasses)
    }

    private fun reducibleChain(): Problem = Problem(
        0,
        3,
        Array(3) { IntDomain(0, 9) },
        arrayOf<Factor>(
            Linear(intArrayOf(2, -2, -2), intArrayOf(0, 1, 2), LinearOp.EQ, 0),
            Linear(intArrayOf(1, 1), intArrayOf(1, 2), LinearOp.LE, 4),
        ),
    )

    @Test
    fun `a pass that charges nothing itself still spends the budget`() {
        val budget = PresolveBudget(Long.MAX_VALUE)

        Presolver.run(
            reducibleChain().bake(),
            PresolveConfig.parse(PresolvePass.FUSE_LINEAR_BOUNDS.id),
            PresolveContext.EMPTY.withPresolveBudget(budget),
            budget.orSpent(Cancellation.Never),
        )

        assertTrue(budget.spent() > 0L)
    }

    @Test
    fun `a spent work budget runs no pass`() {
        val problem = reducibleChain().bake()
        val budget = PresolveBudget(0L)

        val presolved = Presolver.run(
            problem,
            PresolveConfig.DEFAULT,
            PresolveContext.EMPTY.withPresolveBudget(budget),
            budget.orSpent(Cancellation.Never),
        )

        assertSame(problem, presolved.problem)
    }
}
