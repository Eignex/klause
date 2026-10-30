package com.eignex.klause.lp.bounding

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.arithmetic.ExactLinear
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.table.Element
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.Term
import com.eignex.klause.lp.OpenIntBounds
import com.eignex.klause.lp.RelaxationProjection
import com.eignex.klause.lp.RelaxationProjectionProvider
import com.eignex.klause.lp.engine.Basis
import com.eignex.klause.lp.engine.EngineConstruction
import com.eignex.klause.lp.engine.FloatLpResult
import com.eignex.klause.lp.engine.LpCertificationPolicy
import com.eignex.klause.lp.engine.LpEngineFactory
import com.eignex.klause.lp.engine.LpFloatAllowance
import com.eignex.klause.lp.engine.LpFloatTermination
import com.eignex.klause.lp.engine.LpModel
import com.eignex.klause.lp.engine.LpPricingOptions
import com.eignex.klause.lp.engine.LpSolveContext
import com.eignex.klause.lp.engine.LpSolveMetrics
import com.eignex.klause.lp.engine.LpSolver
import com.eignex.klause.lp.engine.LpVerdict
import com.eignex.klause.lp.engine.LpZeroObjectivePricing
import com.eignex.klause.lp.engine.PersistentLpSolver
import com.eignex.klause.lp.engine.ProductionLpEngineFactory
import com.eignex.klause.lp.engine.RecordingLpEngineFactory
import com.eignex.klause.lp.relaxation.CpToLpRelaxation
import com.eignex.klause.lp.relaxation.LpAssemblyCancelled
import com.eignex.klause.lp.relaxation.RootDomains
import com.eignex.klause.lp.tightenOpenIntBounds
import com.eignex.klause.propagation.CpSearchComponent
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.bake
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.FactorProjectionProviders
import com.eignex.klause.solver.RelaxationCapability
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.SolveStatsSink
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.SearchSession
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LpEngineTest {
    @Test
    fun `paused root layout preparation retains charged construction`() {
        val problem = boundedProblem().bake()
        val sink = SolveStatsSink("paused-root-layout")
        LpEngine(problem, LinearObjective(intCoefficients = LongArray(3) { 2L }),
            LpParams(lpPlan = LpPlan(bounding = true, componentOwnership = true,
                probe = false, rootCutHarvest = false)), sink,
            projections = FactorProjectionProviders.Default.prepare(problem)).use { engine ->
            val before = engine.totalSolveWork()

            val readyInFirstSlice = engine.prepareRootLayout(Cancellation.Never, Cancellation {
                engine.totalSolveWork() > before
            })
            val paid = engine.totalSolveWork()
            val prepared = sink.snapshot().lp.phases["TEMPLATE_PREPARATION_NODE"]?.calls
            val ready = engine.prepareRootLayout(Cancellation.Never, Cancellation.Never)
            val imported = sink.snapshot().lp.phases["TEMPLATE_ASSEMBLY_NODE"]?.outcomes?.get("EXACT_BOUND_OBJECTS")
            val bound = engine.rootLpRelaxationBound(assertNotNull(engine.lpRelaxer), emptyList())

            assertFalse(readyInFirstSlice)
            assertTrue(paid > before)
            assertTrue(ready)
            assertNotNull(prepared)
            assertEquals(prepared, sink.snapshot().lp.phases["TEMPLATE_PREPARATION_NODE"]?.calls)
            assertNotNull(imported)
            assertEquals(imported,
                sink.snapshot().lp.phases["TEMPLATE_ASSEMBLY_NODE"]?.outcomes?.get("EXACT_BOUND_OBJECTS"))
            assertEquals(3.0, bound)
        }
    }

    @Test
    fun `containing cancellation retires a paused root layout`() {
        var cancelled = false
        val lifetime = Cancellation { cancelled }
        val sink = SolveStatsSink("cancelled-root-layout")
        LpEngine(boundedProblem(), LinearObjective(intCoefficients = LongArray(3) { 2L }),
            LpParams(lpPlan = LpPlan(bounding = true, componentOwnership = true,
                probe = false, rootCutHarvest = false)), sink).use { engine ->
            val before = engine.totalSolveWork()
            assertFalse(engine.prepareRootLayout(lifetime, Cancellation { engine.totalSolveWork() > before }))
            val paid = engine.totalSolveWork()
            cancelled = true

            engine.prepareRootLayout(lifetime, Cancellation.Never)
            val bound = engine.rootLpRelaxationBound(assertNotNull(engine.lpRelaxer), emptyList(), lifetime)

            assertTrue(bound.isNaN())
            assertTrue(engine.totalSolveWork() >= paid)
            assertEquals(1L, sink.snapshot().lp.phases["ROOT_LAYOUT_PREPARATION_ROOT"]?.outcomes?.get("CANCELLED"))
        }
    }

    @Test
    fun `root layout resumption does not renew spent construction work`() {
        LpEngine(boundedProblem(), LinearObjective(intCoefficients = LongArray(3) { 2L }),
            LpParams(lpPlan = LpPlan(bounding = true, componentOwnership = true, rootMaxWork = 1L,
                probe = false, rootCutHarvest = false)), SolveStatsSink("spent-root-layout")).use { engine ->
            engine.prepareRootLayout(Cancellation.Never, Cancellation.Never)
            val spent = engine.totalSolveWork()

            engine.prepareRootLayout(Cancellation.Never, Cancellation.Never)
            val bound = engine.rootLpRelaxationBound(assertNotNull(engine.lpRelaxer), emptyList())

            assertTrue(spent > 0L)
            assertEquals(spent, engine.totalSolveWork())
            assertTrue(bound.isNaN())
        }
    }

    @Test
    fun `root query cancellation preserves completed preparation for node queries`() {
        val problem = boundedProblem().bake()
        val sink = SolveStatsSink("retained-root-preparation")
        var rootPolls = 0
        val rootToken = Cancellation { rootPolls++ > 0 }
        LpEngine(problem, LinearObjective(intCoefficients = LongArray(3) { 2L }),
            LpParams(lpPlan = LpPlan(bounding = true, componentOwnership = true,
                probe = false, rootCutHarvest = false)), sink,
            projections = FactorProjectionProviders.Default.prepare(problem)).use { engine ->
            val cp = CpSearchComponent(PropagationSession(problem))
            engine.cpAdapter.attach(cp.session, feasibility = false)
            SearchSession(listOf(cp, engine.propagator)).initialize()
            val relaxer = assertNotNull(engine.lpRelaxer)

            val root = engine.rootLpRelaxationBound(relaxer, emptyList(), rootToken,
                preparationCancellation = Cancellation.Never)
            val prepared = sink.snapshot().lp.phases["TEMPLATE_PREPARATION_NODE"]?.calls ?: 0L
            val node = assertNotNull(engine.nodeRelaxation(relaxer, cp.session))
            val bound = assertNotNull(engine.solveNode(node.model, null, Cancellation.Never)?.second).objective

            assertTrue(root.isNaN())
            assertTrue(prepared > 0L)
            assertEquals(prepared, sink.snapshot().lp.phases["TEMPLATE_PREPARATION_NODE"]?.calls)
            assertEquals(3.0, bound)
        }
    }

    @Test
    fun `source root bounds exclude live search restrictions`() {
        val problem = boundedProblem()
        LpEngine(problem, LinearObjective(intCoefficients = LongArray(3) { 2L }),
            LpParams(lpPlan = LpPlan(bounding = true, componentOwnership = true,
                probe = false, rootCutHarvest = false)),
            SolveStatsSink("unconditional-source-root")).use { engine ->
            val cp = CpSearchComponent(PropagationSession(problem))
            engine.cpAdapter.attach(cp.session, feasibility = false)
            val shared = SearchSession(listOf(cp, engine.propagator))
            shared.initialize()
            assertTrue(engine.prepareRootLayout(Cancellation.Never, Cancellation.Never))
            shared.push(SearchDecision.IntAtLeast(0, 1L))

            val root = engine.rootLpRelaxationBound(assertNotNull(engine.lpRelaxer), emptyList())
            val child = assertNotNull(engine.nodeRelaxation(assertNotNull(engine.lpRelaxer), cp.session))
            val childBound = assertNotNull(engine.solveNode(child.model, null, Cancellation.Never)?.second).objective

            assertEquals(3.0, root)
            assertEquals(4.0, childBound)
        }
    }

    @Test
    fun `source root bounds accept noncompact exact rows`() {
        val coefficient = BigFraction.ofLong(Long.MAX_VALUE) + BigFraction.ONE
        val problem = Problem(0, 4, Array(4) { IntDomain(0, 1) }, (0 until 2).map { block ->
            ExactLinear.of(intArrayOf(Term.ofIntVar(2 * block), Term.ofIntVar(2 * block + 1)),
                listOf(coefficient, coefficient + BigFraction.ONE), LinearOp.GE, coefficient)
        })
        LpEngine(problem, LinearObjective(intCoefficients = LongArray(4) { 1L }),
            LpParams(lpPlan = LpPlan(bounding = true, componentOwnership = true,
                probe = false, rootCutHarvest = false)),
            SolveStatsSink("noncompact-source-root")).use { engine ->

            val bound = engine.rootLpRelaxationBound(assertNotNull(engine.lpRelaxer), emptyList())

            assertTrue(bound in 1.0..2.0)
        }
    }

    @Test
    fun `root query observations distinguish work cancellation and checked bounds`() {
        val cases = listOf(
            Triple(0L, false, "NO_INITIAL_WORK"),
            Triple(100_000L, true, "CANCELLED_INITIAL"),
            Triple(100_000L, false, "BOUND"),
        )
        for ((grant, cancelled, outcome) in cases) {
            val sink = SolveStatsSink("root-query-admission")
            LpEngine(boundedProblem(), LinearObjective(intCoefficients = LongArray(3) { 2L }),
                LpParams(lpPlan = LpPlan(bounding = true, componentOwnership = true,
                    probe = false, rootCutHarvest = false)), sink,
                nodeWorkAllowance = { grant }).use { engine ->

                val bound = engine.rootLpRelaxationBound(assertNotNull(engine.lpRelaxer), emptyList(),
                    Cancellation { cancelled })

                if (outcome == "BOUND") assertEquals(3.0, bound) else assertTrue(bound.isNaN())
                assertEquals(1L, sink.snapshot().lp.phases["ROOT_QUERY_ROOT"]?.outcomes?.get("COMPONENT_$outcome"))
            }
        }
    }

    @Test
    fun `nonpersistent node layouts should emit each factor once`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(0, 10)), arrayOf<Factor>(
            Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 2),
        ))
        var emissions = 0
        val projections = FactorProjectionProviders(relaxation = RelaxationProjectionProvider {
            RelaxationProjection(RelaxationCapability.SOUND) { _, builder ->
                emissions++
                builder.linearRow(LinearOp.GE, intArrayOf(0), longArrayOf(1), 2)
            }
        }).prepare(problem)
        LpEngine(problem, LinearObjective(intCoefficients = longArrayOf(1)),
            LpParams(lpPlan = LpPlan(bounding = true)), SolveStatsSink("nonpersistent-emission"),
            projections = projections).use { engine ->

            assertNotNull(engine.nodeRelaxation(assertNotNull(engine.lpRelaxer), PropagationSession(problem)))

            assertEquals(1, emissions)
        }
    }

    @Test
    fun `retained layouts accept noncompact exact source rows`() {
        val coefficient = BigFraction.ofLong(Long.MAX_VALUE) + BigFraction.ONE
        val problem = Problem(0, 4, Array(4) { IntDomain(0, 1) }, (0 until 2).map { block ->
            ExactLinear.of(intArrayOf(Term.ofIntVar(2 * block), Term.ofIntVar(2 * block + 1)),
                listOf(coefficient, coefficient + BigFraction.ONE), LinearOp.GE, coefficient)
        })
        val objective = LinearObjective(intCoefficients = LongArray(4) { 1L })
        assertNotNull(CpToLpRelaxation(problem, objective).build(RootDomains(problem)).model.doubleView)
        LpEngine(problem, objective,
            LpParams(lpPlan = LpPlan(bounding = true, componentOwnership = true,
                probe = false, rootCutHarvest = false)),
            SolveStatsSink("exact-retained-layout")).use { engine ->

            val relaxation = engine.nodeRelaxation(assertNotNull(engine.lpRelaxer), PropagationSession(problem))

            assertNotNull(assertNotNull(relaxation).model.exactState)
        }
    }

    @Test
    fun `cancelled template preparation charges the containing LP allowance`() {
        val problem = boundedProblem().bake(FactorProjectionProviders.Default)
        var work = 0L
        val token = object : Cancellation {
            private val meter = WorkMeter { work += it }
            override fun isCancelled(): Boolean = work >= 3L
            override fun workMeter(): WorkMeter = meter
        }
        LpEngine(problem, LinearObjective(intCoefficients = LongArray(3) { 2L }),
            LpParams(lpPlan = LpPlan(bounding = true), cancellation = token),
            SolveStatsSink("cancelled-template-charge"),
            projections = assertNotNull(problem.factorProjections)).use { engine ->
            val before = engine.totalSolveWork()
            work = 0L

            assertFailsWith<LpAssemblyCancelled> {
                assertNotNull(engine.lpRelaxer).build(RootDomains(problem), cancellation = token)
            }

            assertTrue(work >= 3L)
            assertEquals(work, engine.totalSolveWork() - before)
        }
    }

    @Test
    fun `retained root layouts adopt propagated source bounds`() {
        val source = boundedProblem()
        val problem = source.withFactors(source.factors +
            Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 1))
        LpEngine(problem, LinearObjective(intCoefficients = LongArray(3) { 2L }),
            LpParams(lpPlan = LpPlan(bounding = true, componentOwnership = true,
                probe = false, rootCutHarvest = false)),
            SolveStatsSink("root-layout-propagation")).use { engine ->

            val bound = engine.rootLpRelaxationBound(assertNotNull(engine.lpRelaxer), emptyList())

            assertEquals(4.0, bound)
        }
    }

    @Test
    fun `complete component node deductions honor the injected proof policy`() {
        for (accepted in listOf(false, true)) {
            val problem = Problem(0, 4, Array(4) { IntDomain(0, 5) }, listOf(
                Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1),
                Linear(intArrayOf(1, 1), intArrayOf(2, 3), LinearOp.GE, 1),
            ))
            val sink = SolveStatsSink("complete-component-policy")
            val context = LpSolveContext(certificationPolicy = LpCertificationPolicy { _, success ->
                accepted && success
            })

            val pruned = LpEngine(problem,
                LinearObjective(intCoefficients = LongArray(4) { 1L }, constant = 7L),
                LpParams(lpPlan = LpPlan(bounding = true, componentOwnership = true,
                    probe = false, rootCutHarvest = false)), sink, context).use { engine ->
                engine.pruneNode(PropagationSession(problem), 8.0, -1, true)
            }

            assertEquals(accepted, pruned)
            assertEquals(1L, sink.snapshot().lp.phases["NODE_DEDUCTION_NODE"]?.outcomes?.get("COMPONENT_COMPLETE"))
        }
    }

    @Test
    fun `ordinary component roots yield after their adaptive numerical query`() {
        val problem = Problem(0, 120, Array(120) { IntDomain(0, 1) }, (0 until 120).map { variable ->
            val next = (variable / 60) * 60 + (variable + 1) % 60
            Linear(intArrayOf(1, 1), intArrayOf(variable, next), LinearOp.GE, 1)
        })
        LpEngine(problem, LinearObjective(intCoefficients = LongArray(120) { 1L }),
            LpParams(lpPlan = LpPlan(bounding = true, componentOwnership = true,
                probe = false, rootCutHarvest = false), solveBudgetMillis = 30_000L),
            SolveStatsSink("ordinary-root-query-quantum")).use { engine ->
            engine.observeNodeWork(reachedOptimum = true, degenerateColumns = 0, columns = 1, rows = 1)

            val bound = engine.rootLpRelaxationBound(assertNotNull(engine.lpRelaxer), emptyList())

            assertTrue(bound.isNaN())
            assertEquals(LpFloatTermination.WORK, engine.propagator.floatTermination)
            assertFalse(engine.propagator.floatPaused)
        }
    }

    @Test
    fun `ordinary component roots honor an empty cut selection from a nonempty pool`() {
        val problem = boundedProblem()
        LpEngine(problem, LinearObjective(intCoefficients = LongArray(3) { 2L }),
            LpParams(lpPlan = LpPlan(bounding = true, componentOwnership = true,
                probe = false, rootCutHarvest = false), solveBudgetMillis = 30_000L),
            SolveStatsSink("ordinary-root-cut-selection")).use { engine ->
            val relaxer = assertNotNull(engine.lpRelaxer)
            val base = relaxer.build(PropagationSession(problem))
            val cut = engine.dualSimplex(base.model, Cancellation.Never).use { simplex ->
                assertNotNull(simplex.solve())
                simplex.gomoryCuts(8).first()
            }
            assertTrue(engine.cutPool.add(cut, base))
            assertEquals(1, engine.lpGlobalCuts.size)
            assertEquals(4.0, engine.rootLpRelaxationBound(relaxer, engine.lpGlobalCuts))

            val bound = engine.rootLpRelaxationBound(relaxer, emptyList())

            assertEquals(3.0, bound)
        }
    }

    @Test
    fun `ordinary component roots retain global cuts for later node queries`() {
        val problem = Problem(0, 4, Array(4) { IntDomain(0, 5) }, listOf(
            Linear(intArrayOf(2, 2), intArrayOf(0, 1), LinearOp.LE, 3),
            Linear(intArrayOf(2, 2), intArrayOf(2, 3), LinearOp.LE, 3),
        ))
        val factory = RecordingLpEngineFactory()
        val sink = SolveStatsSink("ordinary-root-cuts")
        LpEngine(problem, LinearObjective(intCoefficients = LongArray(4) { -1L }),
            LpParams(lpPlan = LpPlan(bounding = true, componentOwnership = true,
                probe = false, rootCutHarvest = false), solveBudgetMillis = 30_000L),
            sink, LpSolveContext(factory)).use { engine ->
            val cp = CpSearchComponent(PropagationSession(problem))
            engine.cpAdapter.attach(cp.session, feasibility = false)
            SearchSession(listOf(cp, engine.propagator)).initialize()
            val relaxer = assertNotNull(engine.lpRelaxer)
            val base = relaxer.build(cp.session)
            val cut = engine.dualSimplex(base.model, Cancellation.Never).use { simplex ->
                assertNotNull(simplex.solve())
                simplex.gomoryCuts(8).first()
            }
            assertTrue(engine.cutPool.add(cut, base))
            assertEquals(1, engine.lpGlobalCuts.size)

            val bound = engine.rootLpRelaxationBound(relaxer, engine.lpGlobalCuts)
            val constructed = factory.calls.size
            val pruned = engine.pruneNode(cp.session, -1.0, -1, true)

            assertEquals(-2.0, bound)
            assertFalse(pruned)
            assertEquals(constructed, factory.calls.size)
            assertEquals(2L, sink.snapshot().lp.phases["COMPONENT_LIFECYCLE_NODE"]?.outcomes?.get("REUSED"))
        }
    }

    @Test
    fun `an ordinary component root solves only the child narrowed by a later decision`() {
        val problem = Problem(0, 4, Array(4) { IntDomain(0, 5) }, listOf(
            Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1),
            Linear(intArrayOf(1, 1), intArrayOf(2, 3), LinearOp.GE, 1),
        ))
        val factory = RecordingLpEngineFactory()
        val sink = SolveStatsSink("ordinary-root-child")
        LpEngine(problem, LinearObjective(intCoefficients = LongArray(4) { 1L }),
            LpParams(lpPlan = LpPlan(bounding = true, componentOwnership = true,
                probe = false, rootCutHarvest = false), solveBudgetMillis = 30_000L),
            sink, LpSolveContext(factory)).use { engine ->
            val cp = CpSearchComponent(PropagationSession(problem))
            engine.cpAdapter.attach(cp.session, feasibility = false)
            val shared = SearchSession(listOf(cp, engine.propagator))
            shared.initialize()
            assertEquals(2.0, engine.rootLpRelaxationBound(assertNotNull(engine.lpRelaxer), emptyList()))
            val constructed = factory.calls.size
            shared.push(SearchDecision.IntAtLeast(0, 1L))

            val pruned = engine.pruneNode(cp.session, 4.0, -1, true)

            assertFalse(pruned)
            assertEquals(constructed, factory.calls.size)
            val outcomes = sink.snapshot().lp.phases["COMPONENT_LIFECYCLE_NODE"]?.outcomes
            assertEquals(1L, outcomes?.get("SOLVED"))
            assertEquals(1L, outcomes?.get("REUSED"))
        }
    }

    @Test
    fun `ordinary component roots retain their owners for later node queries`() {
        val problem = Problem(0, 4, Array(4) { IntDomain(0, 5) }, listOf(
            Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1),
            Linear(intArrayOf(1, 1), intArrayOf(2, 3), LinearOp.GE, 1),
        ))
        val factory = RecordingLpEngineFactory()
        val sink = SolveStatsSink("ordinary-root-owner")
        LpEngine(problem, LinearObjective(intCoefficients = LongArray(4) { 1L }),
            LpParams(lpPlan = LpPlan(bounding = true, componentOwnership = true,
                probe = false, rootCutHarvest = false), solveBudgetMillis = 30_000L),
            sink, LpSolveContext(factory)).use { engine ->
            val cp = CpSearchComponent(PropagationSession(problem))
            engine.cpAdapter.attach(cp.session, feasibility = false)
            SearchSession(listOf(cp, engine.propagator)).initialize()
            val bound = engine.rootLpRelaxationBound(assertNotNull(engine.lpRelaxer), emptyList())
            val constructed = factory.calls.size

            val pruned = engine.pruneNode(cp.session, 3.0, -1, true)

            assertEquals(2.0, bound)
            assertFalse(pruned)
            assertEquals(constructed, factory.calls.size)
            assertEquals(2L, sink.snapshot().lp.phases["COMPONENT_LIFECYCLE_NODE"]?.outcomes?.get("REUSED"))
            assertEquals(Int.MAX_VALUE, engine.params.lpPlan.boundMaxDepth)
            assertFalse(engine.sparseTrialActive)
        }
    }

    @Test
    fun `ordinary node pruning consumes a checked partial component bound`() {
        val problem = Problem(0, 6, Array(6) { IntDomain(0, 5) }, listOf(
            Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1),
            Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 2),
            Linear(intArrayOf(1, 1), intArrayOf(2, 3), LinearOp.GE, 1),
            Linear(intArrayOf(1, 1), intArrayOf(4, 5), LinearOp.GE, 1),
        ))
        val sink = SolveStatsSink("partial-component-prune")
        val factory = object : LpEngineFactory by ProductionLpEngineFactory {
            override fun newPersistentSolver(
                model: LpModel, cancellation: Cancellation, refactorUpdateLimit: Int,
                iterationLimit: Int, workLimit: Long, trackDegeneracy: Boolean, pricing: LpPricingOptions,
            ): PersistentLpSolver {
                val delegate = ProductionLpEngineFactory.newPersistentSolver(
                    model, cancellation, refactorUpdateLimit, iterationLimit, workLimit, trackDegeneracy, pricing,
                )
                return object : PersistentLpSolver by delegate {
                    override fun resolveBounds(allowance: LpFloatAllowance?): FloatLpResult? =
                        delegate.resolveBounds(if (model.m == 2) LpFloatAllowance(1L, 0) else allowance)
                }
            }
        }

        val pruned = LpEngine(problem, LinearObjective(intCoefficients = LongArray(6) { 1L }, constant = 7L),
            LpParams(lpPlan = LpPlan(bounding = true, componentOwnership = true,
                probe = false, rootCutHarvest = false)), sink, LpSolveContext(factory)).use { engine ->
            engine.pruneNode(PropagationSession(problem), 8.0, -1, true).also {
                assertEquals(LpFloatTermination.WORK, engine.propagator.floatTermination)
            }
        }

        assertTrue(pruned)
        assertEquals(1.0, sink.snapshot().lp.pruned.sum)
        assertEquals(3L, sink.snapshot().lp.phases["COMPONENT_LIFECYCLE_NODE"]?.outcomes?.get("SOLVED"))
    }

    @Test
    fun `root pruning retains complete useful element property groups`() {
        val problem = Problem(
            0, 4, arrayOf(IntDomain(0, 2), IntDomain(0, 1), IntDomain(0, 1), IntDomain(0, 9)),
            arrayOf(
                Element(0, 1, longArrayOf(0, 1, 0), false, 0),
                Element(0, 2, longArrayOf(1, 0, 1), false, 0),
                Element(0, 3, longArrayOf(8, 9, 8), false, 0),
            ),
        )
        LpEngine(
            problem, LinearObjective(intCoefficients = longArrayOf(0, 1, 1, 0)),
            LpParams(lpConfig = LpConfig.AUTO), SolveStatsSink("groups"),
        ).use { engine ->
            engine.pruneIneffectiveHulls(Cancellation.Never)

            val relaxation = assertNotNull(engine.lpRelaxer).build(PropagationSession(problem))
            assertEquals(setOf(0, 1, 2), relaxation.hullFactorIds.toSet())
        }
    }

    @Test
    fun `fixed fractional real leaf should retain its exact contradiction`() {
        val problem = Problem(
            0,
            0,
            emptyArray(),
            arrayOf(
                Linear(
                    intArrayOf(),
                    doubleArrayOf(),
                    intArrayOf(0),
                    doubleArrayOf(0.1),
                    LinearOp.EQ,
                    0.010000000000000002,
                ),
            ),
            numRealVars = 1,
            realLower = doubleArrayOf(0.1),
            realUpper = doubleArrayOf(0.1),
        )
        val sink = SolveStatsSink(backend = "fractional-leaf")
        val session = PropagationSession(problem)

        LpEngine(
            problem,
            LinearObjective(),
            LpParams(lpPlan = LpPlan(bounding = true, realResidual = true)),
            sink,
        ).use { engine ->
            val relaxation = assertNotNull(engine.nodeRelaxation(assertNotNull(engine.lpRelaxer), session))
            assertNotNull(relaxation.model.exactState)
            val result = engine.leafCertify(session)

            assertEquals(LpVerdict.INFEASIBLE, result.verdict, "LP evidence: ${sink.snapshot().lp}")
        }
    }

    private val decline = LpCertificationPolicy { _, _ -> false }

    private fun boundedProblem(): Problem = Problem(
        numBoolVars = 0,
        numIntVars = 3,
        intDomains = Array(3) { IntDomain(0, 1) },
        factors = arrayOf<Factor>(
            Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1),
            Linear(intArrayOf(1, 1), intArrayOf(1, 2), LinearOp.GE, 1),
            Linear(intArrayOf(1, 1), intArrayOf(0, 2), LinearOp.GE, 1),
        ),
    )

    private fun metricSolver(
        workOps: Long = 0L,
        termination: LpFloatTermination? = null,
        pivots: Int = 0,
    ): LpSolver = object : LpSolver {
        override val infeasibleRay: DoubleArray? = null
        override val lastWorkOps: Long = workOps
        override val lastTermination = termination
        override val lastPivots = pivots
        override fun solve(warm: Basis?) = null
        override fun solvePrimal(warm: Basis?) = null
    }

    private fun budgetedEngine(
        sink: SolveStatsSink,
        plan: LpPlan = LpPlan(bounding = true),
        policy: LpRootQueryPolicy = LpRootQueryPolicy.SHARED_WORK,
        budgetMillis: Long = 4L,
    ): LpEngine {
        val problem = boundedProblem()
        return LpEngine(
            problem,
            LinearObjective(intCoefficients = LongArray(problem.numIntVars)),
            LpParams(lpPlan = plan, solveBudgetMillis = budgetMillis),
            sink,
            rootQueryPolicy = policy,
        )
    }

    @Test
    fun `root work spending the LP allowance should be reported before node work`() {
        val sink = SolveStatsSink(backend = "root-allowance")

        budgetedEngine(sink).use { it.observeRootSolve(metricSolver(), LpSolveMetrics(workOps = 20_000L)) }

        assertTrue(sink.lp.snapshot().workAllowanceSpent)
    }

    @Test
    fun `node solves should be capped at the remaining LP allowance`() {
        val engine = budgetedEngine(SolveStatsSink(backend = "node-cap"))

        val budget = engine.use {
            it.observeRootSolve(metricSolver(), LpSolveMetrics(workOps = 5_000L))
            it.nodeWorkBudget()
        }

        assertEquals(15_000L, budget)
    }

    @Test
    fun `root primal queries should use the smaller remaining root and LP allowance`() {
        for (
        (rootLimit, spent, expected) in listOf(
            Triple(12_000L, 5_000L, 7_000L),
            Triple(50_000L, 5_000L, 15_000L),
            Triple(0L, 5_000L, 15_000L),
            Triple(12_000L, 20_000L, 1L),
        )
        ) {
            val plan = LpPlan(bounding = true, rootMaxWork = rootLimit)
            budgetedEngine(SolveStatsSink(backend = "primal-root-cap"), plan).use { engine ->
                engine.observeRootSolve(metricSolver(), LpSolveMetrics(workOps = spent))

                assertEquals(expected, engine.rootPrimalWorkBudget())
            }
        }
    }

    @Test
    fun `private root probes should charge assembly and solves once to the containing engine`() {
        val sink = SolveStatsSink(backend = "private-root")
        budgetedEngine(sink, LpPlan(bounding = true, rootMaxWork = 12_000L)).use { parent ->
            parent.forObjective(LinearObjective(), Cancellation.Never, rootProbe = true).use { child ->
                child.noteNodeOverhead(3_000L)
                child.observeRootSolve(metricSolver(), LpSolveMetrics(workOps = 2_000L))

                assertEquals(5_000L, parent.totalSolveWork())
                assertEquals(5_000L, child.totalSolveWork())
                assertEquals(7_000L, child.nodeQueryWorkBudget())
                assertEquals(2_000.0, sink.snapshot().lp.rootWorkOps.sum)
                assertEquals(3_000.0, sink.snapshot().lp.overheadOps.sum)
            }
        }
    }

    @Test
    fun `standalone completion should reduce remaining root work without duplicate accounting`() {
        budgetedEngine(
            SolveStatsSink(backend = "completion-root"),
            LpPlan(bounding = true, rootMaxWork = 12_000L),
        ).use { engine ->
            engine.observeRootSolve(metricSolver(), LpSolveMetrics(workOps = 5_000L))

            assertEquals(4_000L, engine.rootPrimalWorkBudget(additionalWork = 3_000L))
            assertEquals(5_000L, engine.totalSolveWork())
            assertFalse(engine.workSpentExceeds(12_000L, additionalWork = 6_999L))
            assertTrue(engine.workSpentExceeds(12_000L, additionalWork = 7_000L))
            assertFalse(engine.lpWorkAllowanceSpent(additionalWork = 14_999L))
            assertTrue(engine.lpWorkAllowanceSpent(additionalWork = 15_000L))
        }
    }

    @Test
    fun `root primal work should be independent of the adaptive node allowance`() {
        val problem = boundedProblem()
        val plan = LpPlan(bounding = true, rootMaxWork = 50_000_000L)
        val engine = LpEngine(
            problem,
            LinearObjective(),
            LpParams(lpPlan = plan),
            SolveStatsSink(backend = "primal-root-allowance"),
        )

        engine.use {
            assertEquals(50_000_000L, it.rootPrimalWorkBudget())
            assertTrue(it.nodeWorkBudget() < it.rootPrimalWorkBudget())
        }
    }

    @Test
    fun `node queries should use the smaller containing and offered native allowances`() {
        val problem = boundedProblem()
        var allowance: Long? = null
        val engine = LpEngine(
            problem,
            LinearObjective(intCoefficients = LongArray(problem.numIntVars)),
            LpParams(lpPlan = LpPlan(bounding = true), solveBudgetMillis = 4L),
            SolveStatsSink(backend = "slice-cap"),
            nodeWorkAllowance = { allowance },
        )

        engine.use {
            it.observeRootSolve(metricSolver(), LpSolveMetrics(workOps = 5_000L))
            for ((offered, expected) in listOf(null to null, 30_000L to 15_000L, 500L to 500L, 0L to 1L)) {
                allowance = offered
                assertEquals(15_000L, it.nodeWorkBudget())
                assertEquals(expected, it.nodeQueryWorkBudget())
            }
        }
    }

    @Test
    fun `optional node queries should receive the offered cap after retained construction`() {
        val problem = boundedProblem()
        val factory = RecordingLpEngineFactory()
        val engine = LpEngine(
            problem,
            LinearObjective(intCoefficients = longArrayOf(1L, 1L, 1L)),
            LpParams(lpPlan = LpPlan(bounding = true)),
            SolveStatsSink(backend = "native-slice-cap"),
            LpSolveContext(factory, decline),
            nodeWorkAllowance = { 500L },
        )

        val pruned = engine.use { it.pruneNode(PropagationSession(problem), 1.5, -1, true) }

        assertFalse(pruned)
        assertTrue(factory.calls.single { it.kind == EngineConstruction.PERSISTENT }.workLimit > 500L)
        assertEquals(500L, assertNotNull(factory.floatAllowances.single()).work)
    }

    @Test
    fun `separate preparation should preserve the adaptive float query cap`() {
        val problem = boundedProblem()
        for (components in listOf(false, true)) {
            val factory = RecordingLpEngineFactory()
            LpEngine(
                problem,
                LinearObjective(intCoefficients = longArrayOf(1L, 1L, 1L)),
                LpParams(lpPlan = LpPlan(bounding = true, boundMaxDepth = 0, componentOwnership = components),
                    solveBudgetMillis = 30_000L),
                SolveStatsSink(backend = "separate-preparation-cap"),
                LpSolveContext(factory, decline),
                rootQueryPolicy = if (components) LpRootQueryPolicy.SHARED_WORK else
                    LpRootQueryPolicy.SEPARATE_PREPARATION,
            ).use { engine ->
                val queryCap = engine.nodeWorkBudget()

                engine.pruneNode(PropagationSession(problem), Double.POSITIVE_INFINITY, -1, true)

                assertTrue(factory.calls.single { it.kind == EngineConstruction.PERSISTENT }.workLimit > queryCap)
                assertEquals(queryCap, assertNotNull(factory.floatAllowances.single()).work)
            }
        }
    }

    @Test
    fun `component preparation without a timed allowance uses the remaining root work grant`() {
        val problem = boundedProblem()
        var offered: Long? = null
        LpEngine(
            problem,
            LinearObjective(),
            LpParams(lpPlan = LpPlan(bounding = true, componentOwnership = true, rootMaxWork = 80_000L)),
            SolveStatsSink(backend = "component-preparation-root-grant"),
            nodeWorkAllowance = { offered },
        ).use { engine ->
            engine.observeRootSolve(metricSolver(), LpSolveMetrics(workOps = 5_000L))

            assertEquals(75_000L, engine.nodePreparationWorkBudget())
            offered = 500L
            assertEquals(500L, engine.nodePreparationWorkBudget())
            offered = null
            engine.observeRootSolve(metricSolver(), LpSolveMetrics(workOps = 75_000L))
            assertEquals(1L, engine.nodePreparationWorkBudget())
        }
    }

    @Test
    fun `separate preparation should respect both remaining containing allowances`() {
        val problem = boundedProblem()
        for (components in listOf(false, true)) {
            val plan = LpPlan(bounding = true, boundMaxDepth = 0, componentOwnership = components)
            var offered: Long? = null
            LpEngine(
                problem,
                LinearObjective(),
                LpParams(lpPlan = plan, solveBudgetMillis = 4L),
                SolveStatsSink(backend = "preparation-remaining"),
                nodeWorkAllowance = { offered },
                rootQueryPolicy = if (components) LpRootQueryPolicy.SHARED_WORK else
                    LpRootQueryPolicy.SEPARATE_PREPARATION,
            ).use { engine ->
                engine.observeRootSolve(metricSolver(), LpSolveMetrics(workOps = 5_000L))

                for ((grant, expected) in listOf(null to 15_000L, 30_000L to 15_000L, 500L to 500L, 0L to 1L)) {
                    offered = grant
                    assertEquals(expected, engine.nodePreparationWorkBudget())
                }
            }
        }
    }

    @Test
    fun `adaptive node queries should grow only a pivoting work stop without a point`() {
        for (policy in LpRootQueryPolicy.entries) {
            for (termination in LpFloatTermination.entries + listOf(null)) {
                for (pivots in listOf(0, 1)) {
                    budgetedEngine(
                        SolveStatsSink(backend = "interrupted-root"),
                        LpPlan(bounding = true, boundMaxDepth = 0),
                        policy,
                        budgetMillis = 30_000L,
                    ).use { engine ->
                        val before = engine.nodeWorkBudget()

                        engine.observeNodeWorkStop(metricSolver(termination = termination, pivots = pivots))

                        val grows = (policy == LpRootQueryPolicy.SHARED_WORK ||
                            policy == LpRootQueryPolicy.INTERRUPTED_WORK) &&
                            termination == LpFloatTermination.WORK && pivots > 0
                        assertEquals(if (grows) before * 2 else before, engine.nodeWorkBudget(),
                            "$policy $termination pivots=$pivots")
                    }
                }
            }
        }
    }

    @Test
    fun `measured root query bounds should honor certificate acceptance`() {
        for (accept in listOf(true, false)) {
            val problem = boundedProblem()
            val factory = RecordingLpEngineFactory()
            LpEngine(
                problem,
                LinearObjective(intCoefficients = longArrayOf(1L, 1L, 1L)),
                LpParams(lpPlan = LpPlan(bounding = true, boundMaxDepth = 0), solveBudgetMillis = 30_000L),
                SolveStatsSink(backend = "measured-root-bound"),
                if (accept) LpSolveContext(factory) else LpSolveContext(factory, decline),
                rootQueryPolicy = LpRootQueryPolicy.INTERRUPTED_WORK,
            ).use { engine ->
                engine.pruneNode(PropagationSession(problem), Double.POSITIVE_INFINITY, -1, true)

                assertEquals(if (accept) 2.0 else null, engine.measuredRootLowerBound)
            }
        }
    }

    @Test
    fun `sliced LP queries should receive a native slice cap only behind a finite external cutoff`() {
        for (cutoff in listOf(Double.POSITIVE_INFINITY, 100.0)) {
            val problem = boundedProblem()
            val factory = RecordingLpEngineFactory()
            val params = BacktrackParams(
                lpPlan = LpPlan(bounding = true, rootCutHarvest = false),
                objectiveBoundSupplier = { cutoff },
            )
            val solver = BacktrackSolver(problem.bake(), LpSolveContext(factory, decline))

            solver.resumable(LinearObjective(intCoefficients = longArrayOf(1L, 1L, 1L)), params).use { search ->
                while (factory.floatAllowances.isEmpty() && !search.isDone) {
                    search.runSlice(Cancellation.Never, 1_000L, 64L) {}
                }
            }

            assertTrue(factory.floatAllowances.isNotEmpty())
            assertEquals(cutoff.isFinite(), factory.floatAllowances.first() != null)
        }
    }

    @Test
    fun `node construction and certification should stay isolated per engine`() {
        val problem = boundedProblem()
        val objective = LinearObjective(intCoefficients = longArrayOf(1L, 1L, 1L))
        val params = LpParams(
            lpPlan = LpPlan(bounding = true),
            randomSeed = 31L,
        )
        val rejectingFactory = RecordingLpEngineFactory()
        val rejecting = LpEngine(
            problem,
            objective,
            params,
            SolveStatsSink(backend = "rejecting"),
            LpSolveContext(rejectingFactory, decline),
        )
        val production = LpEngine(
            problem,
            objective,
            params,
            SolveStatsSink(backend = "production"),
        )

        val rejected = rejecting.pruneNode(PropagationSession(problem), 1.5, -1, true)
        val accepted = production.pruneNode(PropagationSession(problem), 1.5, -1, true)

        assertFalse(rejected)
        assertTrue(accepted)
        assertEquals(1, rejectingFactory.calls.count { it.kind == EngineConstruction.PERSISTENT })
        val construction = rejectingFactory.calls.single { it.kind == EngineConstruction.PERSISTENT }
        assertEquals(LpZeroObjectivePricing.MIN_BOUND_SUPPORT, construction.zeroObjectivePricing)
        assertEquals(31L, construction.tieSeed)
    }

    @Test
    fun `root bound should use the injected tableau factory and policy`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(5, 9)),
            factors = emptyArray(),
        )
        val objective = LinearObjective(intCoefficients = longArrayOf(1L))
        val factory = RecordingLpEngineFactory()
        val engine = LpEngine(
            problem,
            objective,
            LpParams(
                lpPlan = LpPlan(bounding = true),
                randomSeed = 37L,
                zeroObjectivePricing = LpZeroObjectivePricing.LARGEST_PIVOT,
            ),
            SolveStatsSink(backend = "root"),
            LpSolveContext(factory, decline),
            nodeWorkAllowance = { 1L },
        )

        val bound = engine.rootLpRelaxationBound(checkNotNull(engine.lpRelaxer), emptyList())

        assertTrue(bound.isNaN())
        assertEquals(1, factory.calls.count { it.kind == EngineConstruction.TABLEAU })
        val construction = factory.calls.single { it.kind == EngineConstruction.TABLEAU }
        assertEquals(LpZeroObjectivePricing.LARGEST_PIVOT, construction.zeroObjectivePricing)
        assertEquals(37L, construction.tieSeed)
        assertTrue(construction.workLimit > 1L)
    }

    @Test
    fun `root infeasibility should not bypass the injected policy`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(0, 1)),
            factors = arrayOf<Factor>(Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 2)),
        )
        val objective = LinearObjective(intCoefficients = longArrayOf(0L))
        val factory = RecordingLpEngineFactory()
        val rejecting = LpEngine(
            problem,
            objective,
            LpParams(
                lpPlan = LpPlan(bounding = true),
                randomSeed = 41L,
                zeroObjectivePricing = LpZeroObjectivePricing.LARGEST_PIVOT,
            ),
            SolveStatsSink(backend = "rejecting"),
            LpSolveContext(factory, decline),
        )
        val production = LpEngine(
            problem,
            objective,
            LpParams(lpPlan = LpPlan(bounding = true)),
            SolveStatsSink(backend = "production"),
        )

        assertFalse(rejecting.rootLpInfeasibleNoBake(Cancellation.Never))
        assertTrue(production.rootLpInfeasibleNoBake(Cancellation.Never))
        assertEquals(1, factory.calls.count { it.kind == EngineConstruction.TABLEAU })
        val construction = factory.calls.single { it.kind == EngineConstruction.TABLEAU }
        assertEquals(LpZeroObjectivePricing.LARGEST_PIVOT, construction.zeroObjectivePricing)
        assertEquals(41L, construction.tieSeed)
    }

    @Test
    fun `leaf certification should report unbounded only with a ray the factors keep`() {
        // r >= 0 with no row above it: minimizing -r descends without limit, minimizing nothing does not.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(
                Linear(longArrayOf(), intArrayOf(), doubleArrayOf(1.0), intArrayOf(0), LinearOp.GE, 1L),
            ),
            numRealVars = 1,
            realLower = doubleArrayOf(0.0),
            realUpper = doubleArrayOf(Double.POSITIVE_INFINITY),
        )
        val cases = listOf(doubleArrayOf(-1.0) to LpVerdict.UNBOUNDED, doubleArrayOf(0.0) to LpVerdict.ATTAINED_OPTIMUM)
        for ((costs, verdict) in cases) {
            val engine = LpEngine(
                problem,
                LinearObjective(realCoefficients = costs),
                LpParams(lpPlan = LpPlan(bounding = true, realResidual = true, componentSplit = false)),
                SolveStatsSink(backend = "leaf-unbounded"),
            )

            val leaf = engine.use { it.leafCertify(PropagationSession(problem)) }

            assertEquals(verdict, leaf.verdict)
            assertEquals(verdict == LpVerdict.UNBOUNDED, leaf.direction != null)
        }
    }

    @Test
    fun `leaf certification should report an accepted float optimum as a tolerance optimum`() {
        // 3x = 1 with x in [0, 2], minimizing x; every exact certifier is vetoed, so only float evidence can decide.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(
                Linear(longArrayOf(), intArrayOf(), doubleArrayOf(3.0), intArrayOf(0), LinearOp.EQ, 1L),
            ),
            numRealVars = 1,
            realLower = doubleArrayOf(0.0),
            realUpper = doubleArrayOf(2.0),
        )
        val engine = LpEngine(
            problem,
            LinearObjective(realCoefficients = doubleArrayOf(1.0)),
            LpParams(lpPlan = LpPlan(bounding = true, realResidual = true, componentSplit = false)),
            SolveStatsSink(backend = "leaf-tolerance"),
            LpSolveContext(certificationPolicy = LpCertificationPolicy { _, _ -> false }),
        )

        val leaf = engine.use { it.leafCertify(PropagationSession(problem), toleranceCheck = { true }) }

        assertEquals(LpVerdict.TOLERANCE_OPTIMUM, leaf.verdict)
        assertEquals(1.0 / 3.0, leaf.reals.single(), 1e-12)
    }

    @Test
    fun `leaf certification should cap its solve by work`() {
        val problem = boundedProblem()
        val factory = RecordingLpEngineFactory()
        val engine = LpEngine(
            problem,
            LinearObjective(intCoefficients = LongArray(problem.numIntVars)),
            LpParams(lpPlan = LpPlan(bounding = true, realResidual = true, componentSplit = false)),
            SolveStatsSink(backend = "leaf-work"),
            LpSolveContext(factory, decline),
            nodeWorkAllowance = { 1L },
        )

        engine.use { it.leafCertify(PropagationSession(problem)) }

        assertTrue(factory.calls.single { it.kind == EngineConstruction.PERSISTENT }.workLimit > 1L)
    }

    @Test
    fun `open bound probes should use one injected retained owner and preserve proof policy`() {
        val rows = listOf(
            Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.EQ, 0),
            Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 10),
        )
        val bounds = arrayOf(OpenIntBounds(null, null), OpenIntBounds(null, null))
        val factory = RecordingLpEngineFactory()

        val rejected = tightenOpenIntBounds(
            bounds,
            rows,
            context = LpSolveContext(factory, decline),
        )
        val accepted = tightenOpenIntBounds(bounds, rows)

        assertEquals(null, rejected.bounds[0].hi)
        assertEquals(5L, accepted.bounds[0].hi)
        assertEquals(1, factory.calls.count { it.kind == EngineConstruction.PERSISTENT })
        assertEquals(0, factory.calls.count { it.kind == EngineConstruction.GENERAL })
    }
}
