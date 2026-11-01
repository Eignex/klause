package com.eignex.klause.lp.bounding

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.engine.Cut
import com.eignex.klause.lp.engine.ExactLpBounds
import com.eignex.klause.lp.engine.ExactLpColumn
import com.eignex.klause.lp.engine.ExactLpEntry
import com.eignex.klause.lp.engine.ExactLpModel
import com.eignex.klause.lp.engine.ExactLpNumber
import com.eignex.klause.lp.engine.ExactLpObjective
import com.eignex.klause.lp.engine.ExactLpPremises
import com.eignex.klause.lp.engine.ExactLpRow
import com.eignex.klause.lp.engine.ExactLpSide
import com.eignex.klause.lp.engine.FloatLpResult
import com.eignex.klause.lp.engine.LpBoundBatchResult
import com.eignex.klause.lp.engine.LpEngineFactory
import com.eignex.klause.lp.engine.LpExactState
import com.eignex.klause.lp.engine.LpFloatAllowance
import com.eignex.klause.lp.engine.LpLayoutRemap
import com.eignex.klause.lp.engine.LpModel
import com.eignex.klause.lp.engine.LpPricingOptions
import com.eignex.klause.lp.engine.LpScopedRow
import com.eignex.klause.lp.engine.LpSolveContext
import com.eignex.klause.lp.engine.LpVerdict
import com.eignex.klause.lp.engine.PersistentLpSolver
import com.eignex.klause.lp.engine.ProductionLpEngineFactory
import com.eignex.klause.lp.engine.RecordingLpEngineFactory
import com.eignex.klause.lp.engine.Relation
import com.eignex.klause.lp.engine.RevisedSimplex
import com.eignex.klause.lp.engine.authoritativeModel
import com.eignex.klause.lp.relaxation.CpToLpRelaxation
import com.eignex.klause.lp.relaxation.LpRetainedCuts
import com.eignex.klause.lp.relaxation.LpRetainedSources
import com.eignex.klause.lp.relaxation.RelaxationDomains
import com.eignex.klause.lp.relaxation.RootDomains
import com.eignex.klause.lp.relaxation.withModel
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.simplex.basis.BasisArithmeticException
import com.eignex.klause.simplex.basis.BasisSolver
import com.eignex.klause.simplex.basis.IndexedVector
import com.eignex.klause.simplex.basis.KotlinBasisSolver
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.simplex.exact.ExactContinuationLimits
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.LpStatsSink
import com.eignex.klause.solver.result.SolveStatsSink
import com.eignex.klause.solver.search.ComponentCheck
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.SearchAtomPremise
import com.eignex.klause.solver.search.SearchAtomRegistry
import com.eignex.klause.solver.search.SearchContext
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.SearchSession
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LpPropagatorTest {
    @Test
    fun `consumer query grants respect bounded and unbounded containing allowances`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(0, 3)), emptyArray())
        val base = CpToLpRelaxation(problem, LinearObjective(intCoefficients = longArrayOf(1L)))
            .build(PropagationSession(problem))
        for ((containing, requested, expected) in listOf(
            Triple(100_000L, 50_000L, 50_000L),
            Triple(100_000L, 200_000L, 100_000L),
            Triple(0L, 50_000L, 50_000L),
            Triple(0L, null, 0L),
        )) {
            val factory = RecordingLpEngineFactory()
            LpPropagator(object : LpSearchPolicy {},
                effort = { LpEffortProfile(work = 100_000L) },
                solveContext = LpSolveContext(factory),
                queryWorkAllowance = { containing },
            ).use { lp ->
                assertTrue(lp.install(base, assertNotNull(base.model.authoritativeModel())))

                assertNotNull(lp.solveFloat(queryAllowance = { requested }))

                assertEquals(expected, assertNotNull(factory.floatAllowances.single()).work)
            }
        }
    }

    @Test
    fun `spent consumer query grants supply no floating work`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(0, 3)), emptyArray())
        val base = CpToLpRelaxation(problem, LinearObjective(intCoefficients = longArrayOf(1L)))
            .build(PropagationSession(problem))
        LpPropagator(object : LpSearchPolicy {},
            effort = { LpEffortProfile(work = 100_000L) },
            queryWorkAllowance = { 100_000L },
        ).use { lp ->
            assertTrue(lp.install(base, assertNotNull(base.model.authoritativeModel())))

            val result = lp.solveFloat(queryAllowance = { 0L })

            assertNull(result)
            assertEquals(0L, lp.lastMetrics.workOps - assertNotNull(lp.metrics).preparationWork)
        }
    }

    @Test
    fun `cold preparation is charged before query and continuation grants are read`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(0, 3)), emptyArray())
        val base = CpToLpRelaxation(problem, LinearObjective(intCoefficients = longArrayOf(1L)))
            .build(PropagationSession(problem))
        for (continuation in listOf(false, true)) {
            val factory = RecordingLpEngineFactory()
            var reported = 0L
            var queryGrant = 0L
            var continuationGrant = 0L
            LpPropagator(
                object : LpSearchPolicy {},
                effort = { LpEffortProfile(work = 100_000L) },
                solveContext = LpSolveContext(factory),
                queryWorkAllowance = { (100_000L - reported).also { queryGrant = it } },
                continuationAllowance = {
                    (100_000L - reported).takeIf { continuation }?.also { continuationGrant = it }
                },
                onPreparation = { reported += it.workOps },
            ).use { lp ->
                assertTrue(lp.install(base, assertNotNull(base.model.authoritativeModel())))

                val solved = assertNotNull(lp.solveFloat())

                assertTrue(reported > 0L)
                assertEquals(100_000L - reported, queryGrant)
                if (continuation) assertEquals(queryGrant, continuationGrant)
                assertEquals(queryGrant, assertNotNull(factory.floatAllowances.single()).work)
                assertEquals(solved.first.lastMetrics.workOps, lp.lastMetrics.workOps)
            }
        }
    }

    @Test
    fun `interrupted cold preparation cannot repeat a previous query charge`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(0, 3)), emptyArray())
        val base = CpToLpRelaxation(problem, LinearObjective(intCoefficients = longArrayOf(1L)))
            .build(PropagationSession(problem))
        var stopped = false
        var reported = 0L
        var queries = 0
        LpPropagator(
            object : LpSearchPolicy {},
            cancellation = Cancellation { stopped },
            queryWorkAllowance = { queries++; 100_000L },
            onPreparation = { reported += it.workOps },
        ).use { lp ->
            assertTrue(lp.install(base, assertNotNull(base.model.authoritativeModel())))
            assertNotNull(lp.solveFloat())
            val charged = reported
            stopped = true

            assertNull(lp.solveFloat())

            assertEquals(1, queries)
            assertEquals(charged, reported)
            assertEquals(0L, lp.lastMetrics.workOps)
        }
    }

    @Test
    fun `declined source preparation should report its spent work`() {
        val problem = Problem(0, 8, Array(8) { IntDomain(0, 3) }, arrayOf(
            Linear(IntArray(8) { 1 }, IntArray(8) { it }, LinearOp.GE, 1),
        ))
        val sources = LpRetainedSources(problem, CpToLpRelaxation(problem, LinearObjective()))
        var reported = 0L
        LpPropagator(
            object : LpSearchPolicy {},
            effort = { LpEffortProfile(work = 100L) },
            onPreparation = { reported += it.workOps },
        ).use { lp ->
            assertTrue(lp.install(sources, LpRetainedSources.emptyModel()))
            val edit = assertNotNull(sources.prepare(assertNotNull(lp.state), RootDomains(problem)))

            assertFalse(lp.editSources(edit))

            assertTrue(reported > 0L)
            assertEquals(lp.metrics?.preparationWork, reported)
        }
    }

    @Test
    fun `reported source preparation should not be charged again with the float solve`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(0, 3)), emptyArray())
        val sources = LpRetainedSources(problem, CpToLpRelaxation(
            problem, LinearObjective(intCoefficients = longArrayOf(1L)),
        ))
        var reported = 0L
        LpPropagator(object : LpSearchPolicy {}, onPreparation = { reported += it.workOps }).use { lp ->
            assertTrue(lp.install(sources, LpRetainedSources.emptyModel()))
            assertTrue(lp.editSources(assertNotNull(sources.prepare(assertNotNull(lp.state), RootDomains(problem)))))

            val solved = assertNotNull(lp.solveFloat())

            assertTrue(reported > 0L)
            assertEquals(solved.first.lastMetrics.workOps, lp.lastMetrics.workOps)
        }
    }

    @Test
    fun `premised bound batches should explain the first conflicting prefix`() {
        val problem = Problem(3, 2, Array(2) { IntDomain(0, 10) }, emptyArray())
        val base = CpToLpRelaxation(problem, LinearObjective(intCoefficients = longArrayOf(1, 1)))
            .build(PropagationSession(problem))
        LpPropagator(object : LpSearchPolicy {}).use { lp ->
            assertTrue(lp.install(base, assertNotNull(base.model.authoritativeModel())))
            val session = SearchSession(listOf(lp))
            session.initialize()
            for (variable in 0..2) session.publish(SearchDecision.Bool(Lit.make(variable, true)))
            val first = base.intColOf[0]
            val second = base.intColOf[1]

            val result = lp.assertBounds(
                listOf(
                    LpPremisedBound(
                        first,
                        false,
                        ExactLpSide(ExactLpNumber.of(4L)),
                        SearchAtomPremise.Asserted(SearchDecision.Bool(0)),
                    ),
                    LpPremisedBound(
                        first,
                        true,
                        ExactLpSide(ExactLpNumber.of(3L)),
                        SearchAtomPremise.Asserted(SearchDecision.Bool(2)),
                    ),
                    LpPremisedBound(
                        second,
                        false,
                        ExactLpSide(ExactLpNumber.of(7L)),
                        SearchAtomPremise.Asserted(SearchDecision.Bool(4)),
                    ),
                ),
            )

            assertEquals(2, assertIs<LpBoundBatchResult.Conflict>(result).count)
            val conflict = assertIs<ComponentResult.Conflict>(lp.propagate(session))
            assertEquals(listOf(1, 3), assertNotNull(conflict.explanation).literals.sorted())
            assertEquals(BigFraction.ZERO, assertNotNull(lp.state?.activeSide(second, false)).side.number.value)
        }
    }

    @Test
    fun `premised bound batches should retract before a sibling explanation`() {
        val problem = Problem(4, 1, arrayOf(IntDomain(0, 10)), emptyArray())
        val base = CpToLpRelaxation(problem, LinearObjective(intCoefficients = longArrayOf(1)))
            .build(PropagationSession(problem))
        LpPropagator(object : LpSearchPolicy {}).use { lp ->
            assertTrue(lp.install(base, assertNotNull(base.model.authoritativeModel())))
            val session = SearchSession(listOf(lp))
            session.initialize()
            for (variable in 0..3) session.publish(SearchDecision.Bool(Lit.make(variable, true)))
            val column = base.intColOf[0]
            assertTrue(lp.atLevel(1))
            assertIs<LpBoundBatchResult.Applied>(
                lp.assertBounds(
                    listOf(
                        LpPremisedBound(
                            column,
                            false,
                            ExactLpSide(ExactLpNumber.of(5L)),
                            SearchAtomPremise.Asserted(SearchDecision.Bool(0)),
                        ),
                        LpPremisedBound(
                            column,
                            true,
                            ExactLpSide(ExactLpNumber.of(5L)),
                            SearchAtomPremise.Asserted(SearchDecision.Bool(2)),
                        ),
                    ),
                ),
            )
            assertTrue(lp.atLevel(0))
            assertTrue(lp.atLevel(1))

            assertIs<LpBoundBatchResult.Conflict>(
                lp.assertBounds(
                    listOf(
                        LpPremisedBound(
                            column,
                            false,
                            ExactLpSide(ExactLpNumber.of(7L)),
                            SearchAtomPremise.Asserted(SearchDecision.Bool(4)),
                        ),
                        LpPremisedBound(
                            column,
                            true,
                            ExactLpSide(ExactLpNumber.of(6L)),
                            SearchAtomPremise.Asserted(SearchDecision.Bool(6)),
                        ),
                    ),
                ),
            )

            val conflict = assertIs<ComponentResult.Conflict>(lp.propagate(session))
            assertEquals(listOf(5, 7), assertNotNull(conflict.explanation).literals.sorted())
        }
    }

    @Test
    fun `unavailable batched bound premise should withhold the whole conflict`() {
        for (upperUnavailable in listOf(false, true)) {
            val problem = Problem(1, 1, arrayOf(IntDomain(0, 10)), emptyArray())
            val base = CpToLpRelaxation(problem, LinearObjective(intCoefficients = longArrayOf(1)))
                .build(PropagationSession(problem))
            LpPropagator(object : LpSearchPolicy {}).use { lp ->
                assertTrue(lp.install(base, assertNotNull(base.model.authoritativeModel())))
                val session = SearchSession(listOf(lp))
                session.initialize()
                session.publish(SearchDecision.Bool(0))
                val premise = SearchAtomPremise.Asserted(SearchDecision.Bool(0))
                val column = base.intColOf[0]

                assertIs<LpBoundBatchResult.Conflict>(
                    lp.assertBounds(
                        listOf(
                            LpPremisedBound(
                                column,
                                false,
                                ExactLpSide(ExactLpNumber.of(4L)),
                                if (upperUnavailable) premise else SearchAtomPremise.Unavailable,
                            ),
                            LpPremisedBound(
                                column,
                                true,
                                ExactLpSide(ExactLpNumber.of(3L)),
                                if (upperUnavailable) SearchAtomPremise.Unavailable else premise,
                            ),
                        ),
                    ),
                )

                assertNull(assertIs<ComponentResult.Conflict>(lp.propagate(session)).explanation)
            }
        }
    }

    @Test
    fun `stopped premised batch should retain spent work and publish no bounds`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(0, 10)), emptyArray())
        val base = CpToLpRelaxation(problem, LinearObjective(intCoefficients = longArrayOf(1)))
            .build(PropagationSession(problem))
        var stopped = false
        var spent = 0L
        LpPropagator(object : LpSearchPolicy {}, cancellation = Cancellation { stopped }, onEdit = {
            spent += it
            stopped = true
        }).use { lp ->
            assertTrue(lp.install(base, assertNotNull(base.model.authoritativeModel())))
            val before = assertNotNull(lp.state)
            val column = base.intColOf[0]
            val premise = SearchAtomPremise.All(emptyList())

            val result = lp.assertBounds(
                listOf(
                    LpPremisedBound(column, false, ExactLpSide(ExactLpNumber.of(2L)), premise),
                    LpPremisedBound(column, true, ExactLpSide(ExactLpNumber.of(8L)), premise),
                ),
            )

            assertIs<LpBoundBatchResult.Declined>(result)
            assertSame(before, lp.state)
            assertEquals(before.model.numVars.toLong() * 2L, spent)
        }
    }

    @Test
    fun `owner and call stops should both withhold LP proofs`() {
        for (stopOwner in listOf(false, true)) {
            var ownerStopped = false
            var callStopped = false
            val problem = Problem(0, 1, arrayOf(IntDomain(0, 3)), emptyArray())
            val base = CpToLpRelaxation(problem, LinearObjective(intCoefficients = longArrayOf(1)))
                .build(PropagationSession(problem))
            LpPropagator(object : LpSearchPolicy {}, cancellation = Cancellation { ownerStopped }).use { lp ->
                assertTrue(lp.install(base, assertNotNull(base.model.authoritativeModel())))
                ownerStopped = stopOwner
                callStopped = !stopOwner

                val result = lp.solve(token = Cancellation { callStopped })

                assertTrue(result == null || result.verdict == LpVerdict.INDETERMINATE)
                assertNull(result?.witness)
                assertNull(result?.bound)
                assertNull(result?.conflictSupport)
            }
        }
    }

    @Test
    fun `compaction should decline a cut edit that would publish uninstalled rows`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(0, 3)), emptyArray())
        val base = CpToLpRelaxation(problem, LinearObjective(intCoefficients = longArrayOf(1)))
            .build(PropagationSession(problem))
        val cuts = LpRetainedCuts()
        LpPropagator(object : LpSearchPolicy {}).use { lp ->
            assertTrue(lp.install(base, assertNotNull(base.model.authoritativeModel())))
            assertTrue(lp.atLevel(1))
            assertTrue(
                lp.append(
                    LpScopedRow(
                        0L,
                        emptyList(),
                        ExactLpNumber.of(0L),
                        ExactLpColumn(ExactLpBounds(ExactLpSide(ExactLpNumber.of(0L)))),
                    ),
                    true,
                ),
            )
            assertTrue(lp.atLevel(0))
            val before = assertNotNull(lp.state)
            val remap = LpLayoutRemap(before.model.n, before.rows)
            val current = base.withModel(assertNotNull(before.ownerWorkingModel()))
            val replacement = assertNotNull(
                cuts.prepare(
                    before,
                    current,
                    listOf(Cut(base.intColOf, longArrayOf(1L), Relation.GE, 0L, global = true)),
                ),
            )

            assertFalse(lp.compact(before, remap, replacement))

            assertSame(before, lp.state)
            assertTrue(cuts.parentRows(before).isEmpty())
            assertTrue(replacement.isCurrent())
            assertEquals(BigFraction.ZERO, assertNotNull(lp.solve()).lowerBound)
        }
    }

    @Test
    fun `published source refresh should invalidate prior preparations but keep numerical state`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(0, 3)), emptyArray())
        val domains = RootDomains(problem)
        val sources = LpRetainedSources(
            problem,
            CpToLpRelaxation(
                problem,
                LinearObjective(intCoefficients = longArrayOf(1)),
            ),
        )
        LpPropagator(object : LpSearchPolicy {}).use { lp ->
            assertTrue(lp.install(sources, LpRetainedSources.emptyModel()))
            assertTrue(lp.editSources(assertNotNull(sources.prepare(assertNotNull(lp.state), domains))))
            val before = assertNotNull(lp.state)
            val first = assertNotNull(sources.prepare(before, domains))
            val stale = assertNotNull(sources.prepare(before, domains))
            assertTrue(lp.editSources(first))

            assertFalse(lp.editSources(stale))

            assertSame(before, lp.state)
            assertEquals(BigFraction.ZERO, assertNotNull(lp.solve()).lowerBound)
        }
    }

    @Test
    fun `declined cut batch should retain numerical state and source publication`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(0, 3)), emptyArray())
        val base = CpToLpRelaxation(problem, LinearObjective(intCoefficients = longArrayOf(1)))
            .build(PropagationSession(problem))
        val cuts = LpRetainedCuts()
        LpPropagator(object : LpSearchPolicy {}, effort = { LpEffortProfile(maxRows = 1) }).use { lp ->
            assertTrue(lp.install(base, assertNotNull(base.model.authoritativeModel())))
            val edit = assertNotNull(
                cuts.prepare(
                    assertNotNull(lp.state),
                    base,
                    listOf(
                        Cut(base.intColOf, longArrayOf(1), Relation.GE, 0, global = true),
                        Cut(base.intColOf, longArrayOf(1), Relation.LE, 3, global = true),
                    ),
                ),
            )
            val before = lp.state

            assertFalse(lp.editCuts(edit))

            assertSame(before, lp.state)
            assertTrue(cuts.parentRows(assertNotNull(lp.state)).isEmpty())
            assertEquals(BigFraction.ZERO, assertNotNull(lp.solve()).lowerBound)
        }
    }

    @Test
    fun `source edits should assert live bounds before numerical preparation and retain rollback`() {
        val problem = Problem(
            1,
            1,
            arrayOf(IntDomain(3, 10)),
            arrayOf(ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.GE, 8)),
        )
        var live = problem.finiteIntDomain(0).withMinAtLeast(4L)
        val domains = object : RelaxationDomains {
            override fun intDomain(varId: Int): IntDomain = live
            override fun boolValue(varId: Int): Boolean? = null
        }
        val sources = LpRetainedSources(
            problem,
            CpToLpRelaxation(problem, LinearObjective(intCoefficients = longArrayOf(1L))),
        )
        LpPropagator(object : LpSearchPolicy {}).use { lp ->
            assertTrue(lp.install(sources, LpRetainedSources.emptyModel()))
            val root = assertNotNull(sources.prepare(assertNotNull(lp.state), domains))
            assertTrue(lp.editSources(root) { _, _ -> SearchAtomPremise.All(emptyList()) })
            assertEquals(BigFraction.ofLong(4L), assertNotNull(lp.solve()).lowerBound)
            assertEquals(1L, lp.metrics?.preparationAttempts)
            assertTrue(assertIs<SearchAtomPremise.All>(lp.activeBoundPremise(0, false)).premises.isEmpty())
            assertTrue(lp.atLevel(1))
            live = live.withMinAtLeast(6L)
            val child = assertNotNull(sources.prepare(assertNotNull(lp.state), domains))

            assertTrue(lp.editSources(child))

            assertEquals(BigFraction.ofLong(6L), assertNotNull(lp.solve()).lowerBound)
            assertEquals(2L, lp.metrics?.preparationAttempts)
            assertEquals(SearchAtomPremise.Unavailable, lp.activeBoundPremise(0, false))
            assertTrue(lp.atLevel(0))
            sources.retract(0)
            assertEquals(BigFraction.ofLong(4L), assertNotNull(lp.solve()).lowerBound)
            assertTrue(lp.resetRoot())
            assertEquals(2L, lp.metrics?.createdOwners)
            assertTrue(assertIs<SearchAtomPremise.All>(lp.activeBoundPremise(0, false)).premises.isEmpty())
            live = problem.finiteIntDomain(0)
            assertTrue(lp.editSources(assertNotNull(sources.prepare(assertNotNull(lp.state), domains))))
            assertEquals(BigFraction.ofLong(3L), assertNotNull(lp.solve()).lowerBound)
            assertEquals(3L, lp.metrics?.createdOwners)
        }
    }

    @Test
    fun `continuous CP rows should decline the legacy bound adapter without changing domains`() {
        val problem = Problem(
            0,
            1,
            arrayOf(IntDomain(0, 4)),
            arrayOf(
                Linear(intArrayOf(0), doubleArrayOf(1.0), intArrayOf(0), doubleArrayOf(0.5), LinearOp.GE, 1.0),
            ),
            numRealVars = 1,
            realLower = doubleArrayOf(-2.0),
            realUpper = doubleArrayOf(2.0),
        )
        val objective = LinearObjective(intCoefficients = longArrayOf(1))
        val relaxation = CpToLpRelaxation(problem, objective).build(RootDomains(problem))
        val session = PropagationSession(problem)
        val domain = session.intDomain(0)
        val stats = SolveStatsSink(backend = "mixed-adapter")
        LpEngine(problem, objective, LpParams(), stats).use { engine ->
            assertNull(engine.cpAdapter.relaxation(relaxation, session))
            assertEquals(domain, session.intDomain(0))
            assertTrue(relaxation.colRealId.any { it == 0 })
        }
    }

    @Test
    fun `raising live effort should resume exact state and record only new work`() {
        val zero = ExactLpNumber.of(0L)
        val source = ExactLpModel(
            List(3) { listOf(ExactLpEntry(it, ExactLpNumber.of(-1L))) },
            List(3) { ExactLpNumber.of(-1L) },
            List(6) { ExactLpColumn(ExactLpBounds(lower = ExactLpSide(zero))) },
            List(3) { ExactLpRow() },
            ExactLpObjective(List(6) { zero }),
        )
        val factory = object : LpEngineFactory by ProductionLpEngineFactory {
            override fun newPersistentSolver(
                model: LpModel,
                cancellation: Cancellation,
                refactorUpdateLimit: Int,
                iterationLimit: Int,
                workLimit: Long,
                trackDegeneracy: Boolean,
                pricing: LpPricingOptions,
            ): PersistentLpSolver = RevisedSimplex(model, cancellation, basisSolverFactory = { matrix ->
                val delegate = KotlinBasisSolver(matrix)
                object : BasisSolver by delegate {
                    override fun ftran(x: IndexedVector, expectedDensity: Double): Unit =
                        throw BasisArithmeticException("injected numerical solve failure")
                }
            })
        }
        var profile = LpEffortProfile(continuation = ExactContinuationLimits(maxPivots = 1))
        val stats = LpStatsSink()
        LpPropagator(
            object : LpSearchPolicy {},
            effort = { profile },
            solveContext = LpSolveContext(engineFactory = factory),
            certificationObserver = stats.certificationObserver(),
        ).use { lp ->
            assertTrue(lp.install(Any(), source))
            val short = assertNotNull(lp.solve())
            profile = profile.copy(continuation = ExactContinuationLimits(maxPivots = 3))

            val full = assertNotNull(lp.solve())

            assertEquals(LpVerdict.INDETERMINATE, short.verdict)
            assertEquals(List(3) { BigFraction.ONE }, full.exactPrimal)
            assertEquals(0, assertNotNull(full.continuation).builds)
            assertEquals(2, full.continuation.pivots)
            val observed = stats.snapshot().continuation
            assertEquals(2L, observed.calls)
            assertEquals(3L, observed.pivots)
            assertEquals(assertNotNull(short.continuation).work + full.continuation.work, observed.work.values.sum())
        }
    }

    @Test
    fun `unnamed local row should withhold the entire exact conflict clause`() {
        val zero = ExactLpNumber.of(0L)
        val source = ExactLpModel(
            listOf(listOf(ExactLpEntry(0, ExactLpNumber.of(1L)))),
            listOf(ExactLpNumber.of(-1L)),
            listOf(
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(zero))),
                ExactLpColumn(ExactLpBounds(lower = ExactLpSide(zero))),
            ),
            listOf(ExactLpRow(global = false, premises = ExactLpPremises(emptyList()))),
            ExactLpObjective(listOf(zero, zero)),
        )
        LpPropagator(object : LpSearchPolicy {}).use { lp ->
            assertTrue(lp.install(Any(), source))
            val session = SearchSession(listOf(lp), atoms = SearchAtomRegistry(0))
            session.initialize()
            val result = assertNotNull(lp.solve())
            assertNotNull(result.conflictSupport)
            assertNull(lp.explainConflict(result.conflictSupport, session))
        }
    }

    @Test
    fun `failed bound invalidation should override a cached feasible consumer check`() {
        val zero = ExactLpNumber.of(0L)
        val source = ExactLpModel(
            listOf(emptyList()),
            emptyList(),
            listOf(ExactLpColumn(ExactLpBounds())),
            emptyList(),
            ExactLpObjective(listOf(zero)),
        )
        val policy = object : LpSearchPolicy {
            override fun check(context: SearchContext): ComponentCheck = ComponentCheck.Feasible
        }
        LpPropagator(policy).use { lp ->
            assertTrue(lp.install(Any(), source))
            val session = SearchSession(listOf(lp))
            session.initialize()
            assertEquals(ComponentCheck.Feasible, lp.check(session))
            assertFalse(lp.assertBound(1, true, ExactLpSide(zero)))
            assertEquals(ComponentCheck.Indeterminate, lp.check(session))
        }
    }

    @Test
    fun `cancelled rollback should restore bounds and a declined adoption should invalidate authority`() {
        var cancelled = false
        var reject = false
        val factory = wrappingFactory { delegate ->
            object : PersistentLpSolver by delegate {
                override fun adopt(state: LpExactState, token: Cancellation): Boolean =
                    !reject && delegate.adopt(state, token)
            }
        }
        val source = ExactLpModel(
            listOf(emptyList()),
            emptyList(),
            listOf(ExactLpColumn(ExactLpBounds(ExactLpSide(ExactLpNumber.of(0L)), ExactLpSide(ExactLpNumber.of(5L))))),
            emptyList(),
            ExactLpObjective(listOf(ExactLpNumber.of(1L))),
        )
        LpPropagator(
            object : LpSearchPolicy {},
            solveContext = LpSolveContext(factory),
            cancellation = Cancellation { cancelled },
        ).use { lp ->
            assertTrue(lp.install(Any(), source))
            assertNotNull(lp.solve())
            assertTrue(lp.atLevel(1))
            assertTrue(lp.assertBound(0, false, ExactLpSide(ExactLpNumber.of(3L))))
            cancelled = true
            lp.retract(0)
            assertEquals(BigFraction.ZERO, lp.state?.model?.column(0)?.bounds?.lower?.number?.value)
            cancelled = false
            assertTrue(lp.atLevel(1))
            assertTrue(lp.assertBound(0, false, ExactLpSide(ExactLpNumber.of(2L))))
            reject = true
            lp.retract(0)
            assertNull(lp.state)
            assertNull(lp.solve())
            reject = false
            assertTrue(lp.install(Any(), source))
            assertEquals(BigFraction.ZERO, assertNotNull(lp.solve()).witness?.objective)
        }
    }

    @Test
    fun `row budget decline should not become a feasible neutral check`() {
        val source = ExactLpModel(
            listOf(emptyList()),
            emptyList(),
            listOf(ExactLpColumn(ExactLpBounds())),
            emptyList(),
            ExactLpObjective(listOf(ExactLpNumber.of(0L))),
        )
        LpPropagator(object : LpSearchPolicy {}, effort = { LpEffortProfile(maxRows = 0) }).use { lp ->
            assertTrue(lp.install(Any(), source))
            assertFalse(
                lp.append(
                    LpScopedRow(
                        0,
                        listOf(0 to ExactLpNumber.of(1L)),
                        ExactLpNumber.of(0L),
                        ExactLpColumn(ExactLpBounds()),
                    ),
                    scoped = true,
                ),
            )
            assertEquals(ComponentCheck.Indeterminate, lp.check(SearchSession(emptyList())))
        }
    }

    @Test
    fun `throwing solve should invalidate its owner and preserve the cleanup failure`() {
        val primary = IllegalStateException("solve failed")
        val cleanup = IllegalStateException("close failed")
        var fail = true
        var constructions = 0
        var closes = 0
        val factory = wrappingFactory { delegate ->
            constructions++
            object : PersistentLpSolver by delegate {
                override fun resolveBounds(allowance: LpFloatAllowance?): FloatLpResult? {
                    if (fail) throw primary
                    return delegate.resolveBounds(allowance)
                }
                override fun close() {
                    closes++
                    delegate.close()
                    if (fail) throw cleanup
                }
            }
        }
        val zero = ExactLpNumber.of(0L)
        val source = ExactLpModel(
            listOf(emptyList()),
            emptyList(),
            listOf(ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(ExactLpNumber.of(1L))))),
            emptyList(),
            ExactLpObjective(listOf(zero)),
        )
        LpPropagator(object : LpSearchPolicy {}, solveContext = LpSolveContext(factory)).use { lp ->
            assertTrue(lp.install(Any(), source))

            val thrown = assertFailsWith<IllegalStateException> { lp.solveFloat() }

            assertSame(primary, thrown)
            assertEquals(listOf(cleanup), thrown.suppressedExceptions)
            assertEquals(1, closes)
            assertNull(lp.state)
            assertNull(lp.solveFloat())
            fail = false
            assertTrue(lp.install(Any(), source))
            assertNotNull(lp.solveFloat()?.second)
            assertEquals(2, constructions)
        }
        assertEquals(2, closes)
    }

    @Test
    fun `root restoration should retain the installation authority across owner release`() {
        val zero = ExactLpNumber.of(0L)
        val source = ExactLpModel(
            listOf(emptyList()),
            emptyList(),
            listOf(ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(ExactLpNumber.of(5L))))),
            emptyList(),
            ExactLpObjective(listOf(ExactLpNumber.of(1L))),
        )
        LpPropagator(object : LpSearchPolicy {}).use { lp ->
            assertTrue(lp.install(Any(), source))
            assertTrue(lp.assertBound(0, false, ExactLpSide(ExactLpNumber.of(3L))))
            assertEquals(BigFraction.ofLong(3), assertNotNull(lp.solve()).witness?.objective)
            lp.releaseSolver()

            assertTrue(lp.resetRoot())

            assertTrue(source.sameAuthority(assertNotNull(lp.state).model))
            assertTrue(assertNotNull(lp.state).assertions.isEmpty())
            assertEquals(BigFraction.ZERO, assertNotNull(lp.solve()).witness?.objective)
        }
    }

    @Test
    fun `unavailable root reset should invalidate bound authority`() {
        for (cancelled in listOf(false, true)) {
            var fail = false
            var closes = 0
            val factory = wrappingFactory { delegate ->
                object : PersistentLpSolver by delegate {
                    override fun adopt(state: LpExactState, token: Cancellation): Boolean =
                        !(fail && !cancelled) && delegate.adopt(state, token)
                    override fun close() {
                        closes++
                        delegate.close()
                    }
                }
            }
            val zero = ExactLpNumber.of(0L)
            val source = ExactLpModel(
                listOf(emptyList()),
                emptyList(),
                listOf(ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(ExactLpNumber.of(5L))))),
                emptyList(),
                ExactLpObjective(listOf(ExactLpNumber.of(1L))),
            )
            LpPropagator(
                object : LpSearchPolicy {},
                solveContext = LpSolveContext(factory),
                cancellation = Cancellation { fail && cancelled },
            ).use { lp ->
                assertTrue(lp.install(Any(), source))
                assertNotNull(lp.solve())
                assertTrue(lp.assertBound(0, false, ExactLpSide(ExactLpNumber.of(3L))))
                fail = true

                assertFalse(lp.resetRoot())

                assertNull(lp.state)
                assertNull(lp.solve())
                assertEquals(1, closes)
            }
        }
    }

    private fun wrappingFactory(wrap: (PersistentLpSolver) -> PersistentLpSolver): LpEngineFactory =
        object : LpEngineFactory by ProductionLpEngineFactory {
            override fun newPersistentSolver(
                model: LpModel,
                cancellation: Cancellation,
                refactorUpdateLimit: Int,
                iterationLimit: Int,
                workLimit: Long,
                trackDegeneracy: Boolean,
                pricing: LpPricingOptions,
            ): PersistentLpSolver = wrap(
                ProductionLpEngineFactory.newPersistentSolver(
                    model,
                    cancellation,
                    refactorUpdateLimit,
                    iterationLimit,
                    workLimit,
                    trackDegeneracy,
                    pricing,
                ),
            )
        }
}
