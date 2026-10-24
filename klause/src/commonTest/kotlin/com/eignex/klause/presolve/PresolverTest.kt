package com.eignex.klause.presolve

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.bool.Xor
import com.eignex.klause.factor.table.Table
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.presolve.PresolveShared.withPassDelta
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.bake
import com.eignex.klause.propagation.baked
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.isClausal
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.PresolveInvocationOutcome
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PresolverTest {
    @Test
    fun `source parity should consume pins resolved by a later BVE pass`() {
        val problem = Problem(6, 0, emptyArray(), listOf(
            Xor(intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true)), 0),
            Xor(intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(3, true)), 0),
            Clause(intArrayOf(Lit.make(2, true), Lit.make(4, true))),
            Clause(intArrayOf(Lit.make(2, true), Lit.make(4, false))),
            Clause(intArrayOf(Lit.make(3, false), Lit.make(5, true))),
            Clause(intArrayOf(Lit.make(3, false), Lit.make(5, false))),
        ))
        val budget = PresolveBudget(Long.MAX_VALUE)

        val prepared = Presolver.runSource(
            problem, PresolveConfig.parse("off,+xor-units,+bve"), PresolveContext(presolveBudget = budget),
        )

        assertTrue(prepared.infeasible)
        assertEquals(listOf(PresolvePass.ELIMINATE_BOOL_VARS, PresolvePass.DERIVE_XOR_UNITS), prepared.passesFired)
        assertEquals(
            listOf(PresolveInvocationOutcome.COMPLETE_MISS, PresolveInvocationOutcome.REFUTED),
            budget.invocations.filter { it.pass == "xor-units" }.map { it.outcome },
        )
    }

    @Test
    fun `source parity should stay parked after BVE produces unrelated pins`() {
        val problem = Problem(6, 0, emptyArray(), listOf(
            Xor(intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true)), 0),
            Xor(intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(3, true)), 0),
            Clause(intArrayOf(Lit.make(4, true), Lit.make(5, true))),
            Clause(intArrayOf(Lit.make(4, true), Lit.make(5, false))),
        ))
        val budget = PresolveBudget(Long.MAX_VALUE)

        val prepared = Presolver.runSource(
            problem, PresolveConfig.parse("off,+xor-units,+bve"),
            PresolveContext(objectiveBoolVars = setOf(4), presolveBudget = budget),
        )

        assertFalse(prepared.infeasible)
        assertEquals(listOf(PresolvePass.ELIMINATE_BOOL_VARS), prepared.passesFired)
        assertEquals(1, budget.passCalls["xor-units"])
        assertTrue(prepared.problem.factors.filterIsInstance<Clause>().any {
            it.literals.contentEquals(intArrayOf(Lit.make(4, true)))
        })
    }

    @Test
    fun `source parity should stay parked with unchanged premises`() {
        val problem = Problem(4, 0, emptyArray(), listOf(
            Xor(intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true)), 0),
            Xor(intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(3, true)), 0),
        ))
        val budget = PresolveBudget(Long.MAX_VALUE)

        val prepared = Presolver.runSource(
            problem, PresolveConfig.parse("off,+xor-units"), PresolveContext(presolveBudget = budget),
        )

        assertSame(problem, prepared.problem)
        assertEquals(1, budget.passCalls["xor-units"])
    }

    @Test
    fun `a completed root refutation should survive an empty finite schedule`() {
        val source = Problem(
            0, 3, Array(3) { IntDomain(0, 1) },
            listOf(
                Table(intArrayOf(0, 1), longArrayOf(0, 1, 1, 0)),
                Table(intArrayOf(1, 2), longArrayOf(0, 1, 1, 0)),
                Table(intArrayOf(2, 0), longArrayOf(0, 1, 1, 0)),
            ),
        ).bake()
        assertIs<PropagationResult.Implied>(source.baked)
        val root = RootBaker.reseed(source, BakeConfig(probeIntBounds = true))
        assertIs<PropagationResult.Unsat>(root.baked)

        for (config in listOf(PresolveConfig.NONE, PresolveConfig.parse("off,+strengthen"))) {
            val prepared = Presolver.run(root, config)

            assertTrue(prepared.infeasible)
            assertTrue(prepared.passesFired.isEmpty())
            assertSame(root, prepared.problem)
        }
    }

    @Test
    fun `explicit source strengthening under off should refute an indivisible equality`() {
        val problem = Problem(
            0, 1, arrayOf(IntDomain(0, 2)),
            listOf(Linear(intArrayOf(2), intArrayOf(0), LinearOp.EQ, 1)),
        )

        for (config in listOf(
            PresolveConfig.parse("off,+strengthen"),
            PresolveConfig(PresolveEmphasis.OFF, mapOf(PresolvePass.STRENGTHEN_COEFFICIENTS to true)),
        )) {
            val prepared = Presolver.runSource(problem, config)

            assertTrue(prepared.infeasible)
            assertEquals(listOf(PresolvePass.STRENGTHEN_COEFFICIENTS), prepared.passesFired)
        }
    }

    @Test
    fun `explicit finite strengthening under off should reduce coefficients`() {
        val problem = Problem(
            0, 2, Array(2) { IntDomain(0, 1) },
            listOf(Linear(intArrayOf(2, 2), intArrayOf(0, 1), LinearOp.LE, 3)),
        ).bake()

        val prepared = Presolver.run(problem, PresolveConfig.parse("off,+strengthen"))

        assertEquals(listOf(PresolvePass.STRENGTHEN_COEFFICIENTS), prepared.passesFired)
        val row = prepared.problem.factors.filterIsInstance<Linear>().single()
        assertEquals(1L, row.bound)
        assertEquals(listOf(1L, 1L), (0 until row.size).map { row.coeff(it) })
    }

    @Test
    fun `an explicit zero round cap should suppress enabled source and finite passes`() {
        val problem = Problem(
            0, 2, Array(2) { IntDomain(0, 1) },
            listOf(Linear(intArrayOf(2, 2), intArrayOf(0, 1), LinearOp.LE, 3)),
        )
        val baked = problem.bake()
        for (config in listOf(
            PresolveConfig.parse("off,+strengthen").withEffort(maxRounds = 0),
            PresolveConfig(
                PresolveEmphasis.OFF, mapOf(PresolvePass.STRENGTHEN_COEFFICIENTS to true), maxRounds = 0,
            ),
            PresolveConfig.parse("off"),
            PresolveConfig.parse("off,-strengthen"),
            PresolveConfig.parse("off,+strengthen,-strengthen"),
        )) {
            val budget = PresolveBudget(Long.MAX_VALUE)
            val context = PresolveContext(presolveBudget = budget)

            val source = Presolver.runSource(problem, config, context)
            val finite = Presolver.run(baked, config, context)

            assertSame(problem, source.problem)
            assertSame(baked, finite.problem)
            assertEquals(emptyList(), source.passesFired)
            assertEquals(emptyList(), finite.passesFired)
            assertEquals(emptyMap(), budget.passCalls)
        }
    }

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
