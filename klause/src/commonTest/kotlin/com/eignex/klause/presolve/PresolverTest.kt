package com.eignex.klause.presolve

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.presolve.PresolveShared.withPassDelta
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.isClausal
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PresolverTest {
    @Test
    fun `default clausal optimization should retain native clause propagation`() {
        val problem = Problem(
            3,
            0,
            emptyArray(),
            listOf(
                Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
                Clause(intArrayOf(Lit.make(0, true), Lit.make(2, true))),
                Clause(intArrayOf(Lit.make(1, true), Lit.make(2, true))),
            ),
        )
        val context = PresolveContext.of(LinearObjective(boolWeights = longArrayOf(1, 2, 3)), true)

        val source = Presolver.runSource(problem, PresolveConfig.DEFAULT, context)
        val finite = Presolver.run(source.problem.bake(), PresolveConfig.DEFAULT, context)

        assertTrue(finite.problem.isClausal())
        assertEquals(3, finite.problem.factors.size)
    }

    @Test
    fun `stronger preparation should permit clique conversion for clausal optimization`() {
        val problem = Problem(
            3,
            0,
            emptyArray(),
            listOf(
                Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
                Clause(intArrayOf(Lit.make(0, true), Lit.make(2, true))),
                Clause(intArrayOf(Lit.make(1, true), Lit.make(2, true))),
            ),
        )
        val context = PresolveContext.of(LinearObjective(boolWeights = longArrayOf(1, 2, 3)), true)

        for (spec in listOf("default,+amo-clique", "aggressive")) {
            val source = Presolver.runSource(problem, PresolveConfig.parse(spec), context)

            assertEquals(1, source.problem.factors.filterIsInstance<Cardinality>().size, spec)
        }
    }

    @Test
    fun `parse should handle aliases and comma lists`() {
        val ctx = PresolveContext.EMPTY
        val autoProblem = PresolveConfig.parse("default").problemPasses(ctx)
        assertTrue(autoProblem.isNotEmpty())
        assertEquals(autoProblem, PresolveConfig.parse(null).problemPasses(ctx))
        assertEquals(autoProblem, PresolveConfig.parse("auto").problemPasses(ctx))
        assertEquals(emptyList(), PresolveConfig.parse("none").problemPasses(ctx))
        assertEquals(
            listOf(PresolvePass.STRENGTHEN_COEFFICIENTS, PresolvePass.ELIMINATE_AFFINE_SINGLETONS),
            PresolveConfig.parse("affine, strengthen").problemPasses(ctx),
        )
        assertFailsWith<IllegalStateException> { PresolveConfig.parse("bogus") }
    }

    @Test
    fun `parse should toggle a single pass with an emphasis plus delta`() {
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
    fun `the source lane should drop solution-set-altering passes for a sensitive query`() {
        val auto = PresolveConfig.AUTO
        val sensitive = PresolveContext(solutionSetSensitive = true)
        val sourcePasses = auto.problemPasses(sensitive, PresolvePass.Capability.SOURCE)

        assertTrue(PresolvePass.DUAL_FIX in auto.problemPasses(PresolveContext.EMPTY, PresolvePass.Capability.SOURCE))
        assertTrue(PresolvePass.DUAL_FIX !in sourcePasses)
        assertTrue(sourcePasses.all { it.preservesSolutionSet })
        assertTrue(PresolvePass.STRENGTHEN_COEFFICIENTS in sourcePasses)
    }

    @Test
    fun `auto resolution should be intent-aware and keep SAC opt-in`() {
        val auto = PresolveConfig.AUTO
        assertTrue(PresolvePass.BREAK_SYMMETRIES in auto.problemPasses(PresolveContext.EMPTY))
        assertTrue(
            PresolvePass.BREAK_SYMMETRIES !in
                auto.problemPasses(PresolveContext(solutionSetSensitive = true)),
        )
        assertEquals(false, auto.resolved(PresolvePass.PROBE_INT_BOUNDS, PresolveContext.EMPTY))
        assertEquals(true, PresolveConfig.parse("all").resolved(PresolvePass.PROBE_INT_HOLES, PresolveContext.EMPTY))
        val ls = PresolveConfig(PresolveConfig.AUTO.emphasis, mapOf(PresolvePass.VALUE_PRECEDENCE to true))
            .forLocalSearch()
        val lsPasses = ls.problemPasses(PresolveContext.EMPTY)
        assertTrue(PresolvePass.BREAK_SYMMETRIES !in lsPasses)
        assertTrue(PresolvePass.VALUE_PRECEDENCE !in lsPasses)
        assertTrue(PresolvePass.STRENGTHEN_COEFFICIENTS in lsPasses)
    }

    @Test
    fun `every pass should round-trip its id and apply cleanly`() {
        val ids = PresolvePass.entries.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        for (p in PresolvePass.entries) {
            assertSame(p, PresolvePass.fromId(p.id), "id `${p.id}` must round-trip through fromId")
        }
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
    fun `the open-range probe should give way to the full probe`() {
        val passes = PresolveConfig.parse("aggressive")
            .problemPasses(PresolveContext.EMPTY, PresolvePass.Capability.SOURCE)

        assertEquals(listOf(PresolvePass.PROBE), passes.filter { it.id.startsWith("probe") })
    }

    @Test
    fun `an already-fired cancellation should make presolve a no-op`() {
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
        assertSame(baked, pre.problem)
        val s = Sample(BooleanArray(0), longArrayOf(3, 1, 0))
        assertSame(s, pre.reconstruct(s))
    }

    @Test
    fun `affine elimination should be off for solution-set-sensitive queries`() {
        // The eliminated variable is left free, so an enumerator would yield each solution once per spurious value.
        val auto = PresolveConfig.AUTO
        assertTrue(PresolvePass.ELIMINATE_AFFINE_SINGLETONS in auto.problemPasses(PresolveContext.EMPTY))
        assertTrue(
            PresolvePass.ELIMINATE_AFFINE_SINGLETONS !in
                auto.problemPasses(PresolveContext(solutionSetSensitive = true)),
        )
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
    fun `a pass that charges nothing itself should still spend the budget`() {
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
    fun `a spent work budget should run no pass`() {
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
