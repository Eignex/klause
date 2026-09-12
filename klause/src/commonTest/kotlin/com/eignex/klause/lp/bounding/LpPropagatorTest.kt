package com.eignex.klause.lp.bounding

import com.eignex.klause.lp.engine.LpFloatAllowance
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
import com.eignex.klause.lp.engine.RevisedSimplex
import com.eignex.klause.simplex.basis.BasisArithmeticException
import com.eignex.klause.simplex.basis.BasisSolver
import com.eignex.klause.simplex.basis.IndexedVector
import com.eignex.klause.simplex.basis.KotlinBasisSolver
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.simplex.exact.ExactContinuationLimits
import com.eignex.klause.solver.result.LpStatsSink
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
}
