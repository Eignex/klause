package com.eignex.klause.lp.engine

import com.eignex.klause.lp.bounding.trailModel
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation
import com.ionspin.kotlin.bignum.integer.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RefinementLifecycleTest {
    @Test
    fun `a reconstructed source witness survives a withheld LU bound`() {
        val builder = LpBuilder()
        val x = builder.addRealVar(0.0, 2.0, cost = 1.0)
        builder.addRealRow(intArrayOf(x), doubleArrayOf(3.0), Relation.EQ, 1.0)
        val state = LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).trailModel()))
        val basis = Basis(intArrayOf(0), arrayOf(VarStatus.BASIC, VarStatus.FIXED))
        val hint = FloatLpResult(
            basis,
            0.25,
            duals = doubleArrayOf(0.0),
            primal = doubleArrayOf(0.25),
            exactState = state,
        )
        val solver = object : LpSolver {
            override val solvedExactState = state
            override val infeasibleRay: DoubleArray? = null
            override fun solve(warm: Basis?) = hint
            override fun solvePrimal(warm: Basis?) = hint
        }
        var solves = 0
        val context = LpSolveContext(
            engineFactory = object : LpEngineFactory by ProductionLpEngineFactory {
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
                        override val lastPivots = 0
                        override val lastMetrics = LpSolveMetrics(workOps = 1L)
                        override fun solve(warm: Basis?): FloatLpResult {
                            solves++
                            return FloatLpResult(
                                basis,
                                0.0,
                                duals = doubleArrayOf(0.0),
                                primal = doubleArrayOf(if (solves == 1) 1.0 / 3.0 else 0.0),
                            )
                        }
                        override fun resolveBounds(allowance: LpFloatAllowance?) = solve(null)
                    }
                }
            },
        )
        LpScopedSolver(state, context = context).use { owner ->
            val result = certifyLpResult(
                assertNotNull(state.toWorkingModel()),
                solver,
                hint,
                policy = LpCertificationPolicy { kind, success -> success && kind != LpCertifier.EXACT_BASIS },
                refinement = LpRefinementRequest(owner, owner.refinementCache, LpRefinementLimits()),
            )

            assertEquals(LpVerdict.FEASIBLE, result.verdict)
            assertEquals(BigFraction.ONE, assertNotNull(result.witness).primal.single() * BigFraction.ofLong(3L))
            assertEquals(BigFraction.ZERO, assertNotNull(result.bound).value)
        }
    }

    @Test
    fun `dual scaling growth is bounded independently of primal scaling`() {
        val zero = ExactLpNumber.of(0L)
        val tiny = ExactLpNumber.of(BigFraction.of(BigInteger.ONE, BigInteger.ONE shl 200))
        val state = LpExactState(
            ExactLpModel(
                listOf(listOf(ExactLpEntry(0, ExactLpNumber.of(1L)))),
                listOf(tiny),
                listOf(
                    ExactLpColumn(ExactLpBounds(ExactLpSide(zero)), integral = false),
                    ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(zero)), integral = false),
                ),
                listOf(ExactLpRow()),
                ExactLpObjective(listOf(tiny, zero)),
            ),
        )
        val basis = Basis(intArrayOf(0), arrayOf(VarStatus.BASIC, VarStatus.FIXED))
        val costs = ArrayList<BigFraction>()
        var solves = 0
        val context = LpSolveContext(
            engineFactory = object : LpEngineFactory by ProductionLpEngineFactory {
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
                        private var current = assertNotNull(model.exactState)
                        override val lastPivots = 1
                        override val lastMetrics = LpSolveMetrics(workOps = 1L, pivots = 1)
                        override fun adopt(state: LpExactState, token: Cancellation): Boolean {
                            current = state
                            return delegate.adopt(state, token)
                        }
                        override fun solve(warm: Basis?): FloatLpResult {
                            costs += current.model.objective.cost(0).value
                            solves++
                            return FloatLpResult(
                                basis,
                                0.0,
                                duals = doubleArrayOf(if (solves == 2) 1.0 else 0.0),
                                primal = doubleArrayOf(0.0),
                            )
                        }
                        override fun resolveBounds(allowance: LpFloatAllowance?) = solve(null)
                    }
                }
            },
        )
        LpScopedSolver(state, context = context).use { owner ->
            refineLp(
                assertNotNull(state.toWorkingModel()),
                LpRefinementRequest(
                    owner,
                    owner.refinementCache,
                    LpRefinementLimits(maxRounds = 3, maxAuxiliaries = 0),
                ),
                doubleArrayOf(0.0),
                doubleArrayOf(-1.0),
                basis,
            )
        }

        assertEquals(3, costs.size)
        assertEquals(BigFraction.of(BigInteger.ONE, BigInteger.ONE shl 120), costs.last())
    }

    @Test
    fun `direct ray construction and source checking share the remaining resources`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val state = LpExactState(
            ExactLpModel(
                listOf(listOf(ExactLpEntry(0, one))),
                listOf(zero),
                List(2) { ExactLpColumn(ExactLpBounds(), integral = false) },
                listOf(ExactLpRow()),
                ExactLpObjective(listOf(one, zero)),
            ),
        )
        val limits = listOf(64L, 128L, 256L, 512L, 1024L).map {
            LpRefinementLimits(maxWork = it, maxAuxiliaries = 0)
        } + listOf(1024L, 2048L, 4096L, 8192L, 16384L).map {
            LpRefinementLimits(maxAllocation = it, maxAuxiliaries = 0)
        }
        for (limit in limits) {
            LpScopedSolver(state).use { owner ->
                val result = refineLp(
                    assertNotNull(state.toWorkingModel()),
                    LpRefinementRequest(owner, owner.refinementCache, limit),
                    basis = Basis(intArrayOf(1), arrayOf(VarStatus.FREE, VarStatus.BASIC)),
                    needPoint = false,
                )

                assertTrue(result.metrics.work <= limit.maxWork)
                assertTrue(result.metrics.allocation <= limit.maxAllocation)
                assertTrue(result.metrics.luFactories <= 1)
            }
        }
    }

    @Test
    fun `exhausted refinement admission cannot buy an ordinary basis proof`() {
        val builder = LpBuilder()
        val x = builder.addRealVar(0.0, 2.0, cost = 1.0)
        builder.addRealRow(intArrayOf(x), doubleArrayOf(3.0), Relation.EQ, 1.0)
        val state = LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).trailModel()))
        val model = assertNotNull(state.toWorkingModel())
        val hint = FloatLpResult(
            Basis(intArrayOf(0), arrayOf(VarStatus.BASIC, VarStatus.FIXED)),
            0.25,
            doubleArrayOf(0.25),
            doubleArrayOf(0.0),
            exactState = state,
        )
        val solver = object : LpSolver {
            override val solvedExactState = state
            override val infeasibleRay: DoubleArray? = null
            override fun solve(warm: Basis?) = hint
            override fun solvePrimal(warm: Basis?) = hint
        }
        LpScopedSolver(state).use { owner ->
            val result = certifyLpResult(
                model,
                solver,
                hint,
                refinement = LpRefinementRequest(owner, owner.refinementCache, LpRefinementLimits(maxWork = 0L)),
            )

            assertEquals(LpRefinementDecline.WORK, assertNotNull(result.refinement).decline)
            assertNull(result.basisVerification)
            assertNull(owner.lastWorkingMetrics)
            assertNotNull(result.continuation)
        }
    }

    @Test
    fun `failed child adoption retires the child and preserves source availability`() {
        val builder = LpBuilder()
        builder.addVar(0, 2, cost = 1)
        val state = LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).trailModel()))
        var fail = false
        val failure = IllegalStateException("adoption failed")
        val context = LpSolveContext(
            engineFactory = object : LpEngineFactory by ProductionLpEngineFactory {
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
                        override fun adopt(state: LpExactState, token: Cancellation): Boolean {
                            if (fail) throw failure
                            return delegate.adopt(state, token)
                        }
                    }
                }
            },
        )
        LpScopedSolver(state, context = context).use { owner ->
            owner.withWorkingModel(LpWorkingModel.overrides(state), allowance = LpFloatAllowance(10000L, 10)) { scope ->
                assertNotNull(scope.solveFloat())
                fail = true
                val next = LpWorkingModel.overrides(state, objective = ExactLpObjective(listOf(ExactLpNumber.of(-1L))))

                assertSame(failure, assertFailsWith<IllegalStateException> { scope.replaceState(next) })
                assertFailsWith<IllegalStateException> { scope.solveFloat() }
            }
            fail = false

            assertSame(state, owner.state)
            assertEquals(0L, assertNotNull(owner.lastWorkingMetrics).owners.currentOwners)
            assertEquals(LpVerdict.ATTAINED_OPTIMUM, assertNotNull(owner.solve()).verdict)
        }
    }

    @Test
    fun `cleanup failure records consumed refinement work without publishing a result`() {
        val builder = LpBuilder()
        val x = builder.addVar(0, 2, cost = 1)
        builder.addRow(intArrayOf(x), longArrayOf(1L), Relation.GE, 1L)
        val state = LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).trailModel()))
        val failure = IllegalStateException("cleanup failed")
        val context = LpSolveContext(
            engineFactory = object : LpEngineFactory by ProductionLpEngineFactory {
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
                        override fun close() {
                            delegate.close()
                            throw failure
                        }
                    }
                }
            },
        )
        LpScopedSolver(state, context = context).use { owner ->
            val request = LpRefinementRequest(owner, owner.refinementCache, LpRefinementLimits())

            assertSame(
                failure,
                assertFailsWith<IllegalStateException> {
                    refineLp(assertNotNull(state.toWorkingModel()), request)
                },
            )

            assertTrue(owner.refinementCache.lastMetrics.work > 0L)
            assertTrue(owner.refinementCache.lastMetrics.floatWork > 0L)
            assertEquals(LpRefinementDecline.FAILURE, owner.refinementCache.lastMetrics.decline)
            assertNull(owner.lastResult)
            assertSame(state, owner.state)
            assertEquals(0L, assertNotNull(owner.lastWorkingMetrics).owners.currentOwners)
        }
    }

    @Test
    fun `source rational factors survive a correction with different child headings`() {
        val builder = LpBuilder()
        val x = builder.addVar(0, 3, cost = 1)
        builder.addRow(intArrayOf(x), longArrayOf(1), Relation.GE, 1)
        val state = LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).trailModel()))
        val model = assertNotNull(state.toWorkingModel())
        LpScopedSolver(state).use { owner ->
            val (solver, hint) = assertNotNull(owner.solveFloat())
            val basis = assertNotNull(hint).basis
            val cache = assertNotNull(solver.exactBasisCache)
            assertNotNull(verifyExactBasis(model, basis, cache = cache).witness)
            val result = refineLp(
                model,
                LpRefinementRequest(owner, owner.refinementCache, LpRefinementLimits()),
                doubleArrayOf(2.0),
                doubleArrayOf(0.0),
                Basis(intArrayOf(1), arrayOf(VarStatus.AT_LOWER, VarStatus.BASIC)),
            )

            assertNotNull(result.witness)
            val checked = verifyExactBasis(model, basis, cache = cache)
            assertEquals(1, checked.metrics.reuse)
            assertEquals(0, checked.metrics.factoryCalls)
        }
    }

    @Test
    fun `rejected refinement witnesses cannot be republished through another certifier`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val state = LpExactState(
            ExactLpModel(
                listOf(listOf(ExactLpEntry(0, ExactLpNumber.of(3L)))),
                listOf(one),
                List(2) { ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(one)), integral = false) },
                listOf(ExactLpRow()),
                ExactLpObjective(listOf(zero, one)),
            ),
        )
        val model = assertNotNull(state.toWorkingModel())
        val hint = FloatLpResult(
            Basis(intArrayOf(0), arrayOf(VarStatus.BASIC, VarStatus.AT_LOWER)),
            0.25,
            doubleArrayOf(0.25),
            doubleArrayOf(0.0),
            exactState = state,
        )
        val solver = object : LpSolver {
            override val solvedExactState = state
            override val infeasibleRay: DoubleArray? = null
            override fun solve(warm: Basis?) = hint
            override fun solvePrimal(warm: Basis?) = hint
        }
        val policy = LpCertificationPolicy { kind, success -> success && kind != LpCertifier.RATIONAL }
        LpScopedSolver(state).use { owner ->
            val result = certifyLpResult(
                model,
                solver,
                hint,
                policy = policy,
                refinement = LpRefinementRequest(owner, owner.refinementCache, LpRefinementLimits()),
            )

            assertNull(result.witness)
            assertNull(result.unboundedness)
            assertNull(result.rationalConflict)
            assertNotNull(result.bound)
        }
    }

    @Test
    fun `two no pivot corrections schedule exact basis verification`() {
        val zero = ExactLpNumber.of(0L)
        val state = LpExactState(
            ExactLpModel(
                listOf(listOf(ExactLpEntry(0, ExactLpNumber.of(3L)))),
                listOf(ExactLpNumber.of(1L)),
                listOf(
                    ExactLpColumn(ExactLpBounds(ExactLpSide(zero)), integral = false),
                    ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(zero)), integral = false),
                ),
                listOf(ExactLpRow()),
                ExactLpObjective(listOf(ExactLpNumber.of(1L), zero)),
            ),
        )
        val basis = Basis(intArrayOf(0), arrayOf(VarStatus.BASIC, VarStatus.FIXED))
        val context = LpSolveContext(
            engineFactory = object : LpEngineFactory by ProductionLpEngineFactory {
                override fun newPersistentSolver(
                    model: LpModel,
                    cancellation: Cancellation,
                    refactorUpdateLimit: Int,
                    iterationLimit: Int,
                    workLimit: Long,
                    trackDegeneracy: Boolean,
                    pricing: LpPricingOptions,
                ): PersistentLpSolver {
                    assertTrue(workLimit > 0L && iterationLimit > 0)
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
                        override val lastPivots = 0
                        override val lastMetrics = LpSolveMetrics(workOps = 1L)
                        override fun solve(warm: Basis?) =
                            FloatLpResult(basis, 0.0, doubleArrayOf(0.0), doubleArrayOf(0.0))
                        override fun resolveBounds(allowance: LpFloatAllowance?) = solve(null)
                    }
                }
            },
        )
        LpScopedSolver(state, context = context).use { owner ->
            val result = refineLp(
                assertNotNull(state.toWorkingModel()),
                LpRefinementRequest(owner, owner.refinementCache, LpRefinementLimits()),
                doubleArrayOf(0.2),
                doubleArrayOf(0.0),
                basis,
            )

            assertTrue(result.sourceLuAttempted)
            assertTrue(result.usedBasis)
            assertEquals(2, result.metrics.stalls)
            assertTrue(result.metrics.luWork > 0L)
            assertEquals(assertNotNull(result.witness).objective, assertNotNull(result.bound).value)
        }
    }
}
