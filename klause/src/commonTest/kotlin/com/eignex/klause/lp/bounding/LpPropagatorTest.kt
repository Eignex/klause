package com.eignex.klause.lp.bounding

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.engine.Basis
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
import com.eignex.klause.lp.engine.LpBuilder
import com.eignex.klause.lp.engine.LpEngineFactory
import com.eignex.klause.lp.engine.LpExactState
import com.eignex.klause.lp.engine.LpFloatAllowance
import com.eignex.klause.lp.engine.LpModel
import com.eignex.klause.lp.engine.LpPricingOptions
import com.eignex.klause.lp.engine.LpScopedRow
import com.eignex.klause.lp.engine.LpSolveContext
import com.eignex.klause.lp.engine.LpVerdict
import com.eignex.klause.lp.engine.PersistentLpSolver
import com.eignex.klause.lp.engine.ProductionLpEngineFactory
import com.eignex.klause.lp.engine.Relation
import com.eignex.klause.lp.engine.RevisedSimplex
import com.eignex.klause.lp.engine.Sense
import com.eignex.klause.lp.engine.authoritativeModel
import com.eignex.klause.lp.relaxation.CpToLpRelaxation
import com.eignex.klause.lp.relaxation.RootDomains
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
import com.eignex.klause.solver.search.SearchAtomRegistry
import com.eignex.klause.solver.search.SearchContext
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.SearchSession
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LpPropagatorTest {
    @Test
    fun `continuous CP rows decline the legacy bound adapter without changing domains`() {
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
    fun `ordinary owners use the current retained invocation allowance`() {
        val builder = LpBuilder()
        val x = builder.addVar(0, 4, cost = 1)
        builder.addRow(intArrayOf(x), longArrayOf(1), Relation.GE, 1)
        val source = assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel())
        val seen = ArrayList<LpFloatAllowance?>()
        val factory = object : LpEngineFactory by ProductionLpEngineFactory {
            override fun newPersistentSolver(
                model: LpModel,
                cancellation: Cancellation,
                refactorUpdateLimit: Int,
                iterationLimit: Int,
                workLimit: Long,
                trackDegeneracy: Boolean,
                pricing: LpPricingOptions,
            ): PersistentLpSolver {
                val delegate = ProductionLpEngineFactory.newPersistentSolver(
                    model,
                    cancellation,
                    refactorUpdateLimit,
                    iterationLimit,
                    workLimit,
                    trackDegeneracy,
                    pricing,
                )
                return object : PersistentLpSolver by delegate {
                    override fun resolveBounds(allowance: LpFloatAllowance?): FloatLpResult? {
                        seen.add(allowance)
                        return delegate.resolveBounds(allowance)
                    }
                }
            }
        }
        var effort = LpEffortProfile(work = 10_000L, iterations = 100)
        LpPropagator(object : LpSearchPolicy {}, { effort }, LpSolveContext(factory)).use { lp ->
            assertTrue(lp.install(Any(), source))
            assertNotNull(lp.solveFloat())
            effort = LpEffortProfile(work = 5000L, iterations = 30)
            assertNotNull(lp.solveFloat())
            effort = LpEffortProfile(work = 100L, iterations = 2)

            assertNotNull(lp.solveFloat())

            assertEquals(listOf(null, LpFloatAllowance(5000L, 30), LpFloatAllowance(100L, 2)), seen)
        }
    }

    @Test
    fun `raising live effort resumes exact state and records only new work`() {
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
    fun `an unnamed local row withholds the entire exact conflict clause`() {
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
    fun `a consumer can finish without installing optional LP state`() {
        val policy = object : LpSearchPolicy {
            override fun check(context: SearchContext): ComponentCheck = ComponentCheck.Feasible
        }
        LpPropagator(policy).use { lp ->
            val session = SearchSession(listOf(lp))
            session.initialize()

            assertEquals(ComponentCheck.Feasible, lp.check(session))
        }
    }

    @Test
    fun `failed bound invalidation overrides a cached feasible consumer check`() {
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
    fun `a cancelled source coefficient retains its local row assumption in the conflict`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val minus = ExactLpNumber.of(-1L)
        val free = ExactLpColumn(ExactLpBounds(), integral = false)
        val slack = ExactLpColumn(ExactLpBounds(lower = ExactLpSide(zero)), integral = false)
        val source = ExactLpModel(
            listOf(
                listOf(
                    ExactLpEntry(0, one),
                    ExactLpEntry(1, minus),
                ),
                listOf(
                    ExactLpEntry(0, minus),
                    ExactLpEntry(1, one),
                ),
            ),
            listOf(zero, minus),
            listOf(free, free, slack, slack),
            listOf(
                ExactLpRow(
                    global = false,
                    premises = ExactLpPremises(emptyList(), listOf(0)),
                ),
                ExactLpRow(),
            ),
            ExactLpObjective(List(4) { zero }),
        )
        LpPropagator(object : LpSearchPolicy {}).use { lp ->
            assertTrue(lp.install(Any(), source))
            val session = SearchSession(listOf(lp), atoms = SearchAtomRegistry(2))
            session.initialize()
            session.push(SearchDecision.Bool(0))
            val support = assertNotNull(lp.solve()).conflictSupport
            val clause = assertNotNull(lp.explainConflict(support, session))
            kotlin.test.assertContentEquals(intArrayOf(1), clause.literals)
            session.popTo(0)
            assertNull(lp.explainConflict(support, session))
            val inactive = assertNotNull(lp.solve()).conflictSupport
            assertNull(lp.explainConflict(inactive, session))
        }
    }

    @Test
    fun `scoped row pop restores exact feasibility while retaining the original output`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val source = ExactLpModel(
            listOf(emptyList()),
            emptyList(),
            listOf(ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(one)))),
            emptyList(),
            ExactLpObjective(listOf(one)),
        )
        LpPropagator(object : LpSearchPolicy {}).use { lp ->
            assertTrue(lp.install(Any(), source))
            val initial = assertNotNull(lp.solve())
            assertEquals(BigFraction.ZERO, initial.witness?.objective)
            assertTrue(lp.atLevel(1))
            assertTrue(
                lp.append(
                    LpScopedRow(
                        0,
                        listOf(0 to one),
                        one,
                        ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(zero))),
                    ),
                    scoped = true,
                ),
            )
            assertEquals(BigFraction.ONE, assertNotNull(lp.solve()).witness?.objective)

            lp.retract(0)

            assertEquals(BigFraction.ZERO, assertNotNull(lp.solve()).witness?.objective)
            assertEquals(BigFraction.ZERO, initial.witness?.objective)
            assertEquals(0, assertNotNull(lp.state).rows.activeCount)
            assertEquals(ComponentCheck.Indeterminate, lp.check(SearchSession(emptyList())))
        }
    }

    @Test
    fun `cancelled rollback restores bounds and a declined adoption invalidates authority`() {
        var cancelled = false
        var reject = false
        val factory = object : LpEngineFactory by ProductionLpEngineFactory {
            override fun newPersistentSolver(
                model: LpModel,
                cancellation: Cancellation,
                refactorUpdateLimit: Int,
                iterationLimit: Int,
                workLimit: Long,
                trackDegeneracy: Boolean,
                pricing: LpPricingOptions,
            ): PersistentLpSolver {
                val delegate = ProductionLpEngineFactory.newPersistentSolver(
                    model,
                    cancellation,
                    refactorUpdateLimit,
                    iterationLimit,
                    workLimit,
                    trackDegeneracy,
                    pricing,
                )
                return object : PersistentLpSolver by delegate {
                    override fun adopt(state: LpExactState, token: Cancellation): Boolean =
                        !reject && delegate.adopt(state, token)
                }
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
    fun `row budget decline does not become a feasible neutral check`() {
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
    fun `a throwing solve invalidates its owner and preserves the cleanup failure`() {
        val primary = IllegalStateException("solve failed")
        val cleanup = IllegalStateException("close failed")
        var fail = true
        var constructions = 0
        var closes = 0
        val factory = object : LpEngineFactory by ProductionLpEngineFactory {
            override fun newPersistentSolver(
                model: LpModel,
                cancellation: Cancellation,
                refactorUpdateLimit: Int,
                iterationLimit: Int,
                workLimit: Long,
                trackDegeneracy: Boolean,
                pricing: LpPricingOptions,
            ): PersistentLpSolver {
                constructions++
                val delegate = ProductionLpEngineFactory.newPersistentSolver(
                    model,
                    cancellation,
                    refactorUpdateLimit,
                    iterationLimit,
                    workLimit,
                    trackDegeneracy,
                    pricing,
                )
                return object : PersistentLpSolver by delegate {
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
    fun `root restoration retains the installation authority across owner release`() {
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
    fun `an unavailable root reset invalidates bound authority`() {
        for (cancelled in listOf(false, true)) {
            var fail = false
            var closes = 0
            val factory = object : LpEngineFactory by ProductionLpEngineFactory {
                override fun newPersistentSolver(
                    model: LpModel,
                    cancellation: Cancellation,
                    refactorUpdateLimit: Int,
                    iterationLimit: Int,
                    workLimit: Long,
                    trackDegeneracy: Boolean,
                    pricing: LpPricingOptions,
                ): PersistentLpSolver {
                    val delegate = ProductionLpEngineFactory.newPersistentSolver(
                        model,
                        cancellation,
                        refactorUpdateLimit,
                        iterationLimit,
                        workLimit,
                        trackDegeneracy,
                        pricing,
                    )
                    return object : PersistentLpSolver by delegate {
                        override fun adopt(state: LpExactState, token: Cancellation): Boolean =
                            !(fail && !cancelled) && delegate.adopt(state, token)
                        override fun close() {
                            closes++
                            delegate.close()
                        }
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

    @Test
    fun `failed ordinary logical preparation remains charged and closes its numerical owner`() {
        val builder = LpBuilder()
        val x = builder.addVar(0L, 3L)
        repeat(4) { builder.addRow(intArrayOf(x), longArrayOf(1L), Relation.GE, 1L) }
        val source = LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
        val model = assertNotNull(source.toWorkingModel())
        var closes = 0
        var logicalWork = 0L
        var numericalAllowance = 0L
        val factory = object : LpEngineFactory by ProductionLpEngineFactory {
            override fun newPersistentSolver(
                model: LpModel,
                cancellation: Cancellation,
                refactorUpdateLimit: Int,
                iterationLimit: Int,
                workLimit: Long,
                trackDegeneracy: Boolean,
                pricing: LpPricingOptions,
            ): PersistentLpSolver {
                numericalAllowance = workLimit
                val delegate = ProductionLpEngineFactory.newPersistentSolver(
                    model,
                    cancellation,
                    refactorUpdateLimit,
                    iterationLimit,
                    workLimit,
                    trackDegeneracy,
                    pricing,
                )
                return object : PersistentLpSolver by delegate {
                    override fun close() {
                        closes++
                        delegate.close()
                    }
                    override fun prepareLogicals(token: Cancellation): Basis? {
                        assertNotNull(delegate.prepareLogicals(token))
                        logicalWork = delegate.lastMetrics.workOps
                        return null
                    }
                }
            }
        }
        LpPropagator(
            object : LpSearchPolicy {},
            { LpEffortProfile(work = 1000L) },
            LpSolveContext(factory),
        ).use { owner ->
            assertTrue(owner.install(model, source.model))

            assertNull(owner.solveFloat())

            assertTrue(logicalWork > 0L)
            assertTrue(numericalAllowance in 1L until 1000L)
            assertEquals(1000L - numericalAllowance + logicalWork, owner.lastMetrics.workOps)
            assertTrue(owner.lastMetrics.workOps <= 1000L)
            assertNotNull(owner.state)
            assertEquals(1, closes)
            assertTrue(owner.install(model, source.model))
        }
    }

    @Test
    fun `a null first float result retains its owner and uses the next effort allowance`() {
        val builder = LpBuilder()
        val x = builder.addVar(0, 4, cost = 1)
        builder.addRow(intArrayOf(x), longArrayOf(1), Relation.GE, 1)
        val source = LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
        val model = assertNotNull(source.toWorkingModel())
        val seen = ArrayList<LpFloatAllowance?>()
        var constructions = 0
        var numericalAllowance = 0L
        var logicalWork = 0L
        val factory = object : LpEngineFactory by ProductionLpEngineFactory {
            override fun newPersistentSolver(
                model: LpModel,
                cancellation: Cancellation,
                refactorUpdateLimit: Int,
                iterationLimit: Int,
                workLimit: Long,
                trackDegeneracy: Boolean,
                pricing: LpPricingOptions,
            ): PersistentLpSolver {
                constructions++
                assertTrue(workLimit in 1L until 1000L)
                numericalAllowance = workLimit
                assertEquals(30, iterationLimit)
                val delegate = ProductionLpEngineFactory.newPersistentSolver(
                    model,
                    cancellation,
                    refactorUpdateLimit,
                    iterationLimit,
                    workLimit,
                    trackDegeneracy,
                    pricing,
                )
                return object : PersistentLpSolver by delegate {
                    override fun prepareLogicals(token: Cancellation): Basis? =
                        delegate.prepareLogicals(token).also { logicalWork = delegate.lastMetrics.workOps }
                    override fun resolveBounds(allowance: LpFloatAllowance?): FloatLpResult? {
                        seen.add(allowance)
                        delegate.resolveBounds(allowance)
                        return null
                    }
                }
            }
        }
        var profile = LpEffortProfile(work = 1000L, iterations = 30)
        LpPropagator(object : LpSearchPolicy {}, { profile }, LpSolveContext(factory)).use { owner ->
            assertTrue(owner.install(model, source.model))
            val installed = owner.state
            assertNull(assertNotNull(owner.solveFloat()).second)
            assertEquals(1000L - numericalAllowance + logicalWork, assertNotNull(owner.metrics).preparationWork)
            assertTrue(owner.install(model, source.model))
            assertSame(installed, owner.state)
            profile = LpEffortProfile(work = 100L, iterations = 2)

            assertNull(assertNotNull(owner.solveFloat()).second)

            assertEquals(listOf(null, LpFloatAllowance(100L, 2)), seen)
            assertEquals(1, constructions)
            assertSame(installed, owner.state)
        }
    }
}
