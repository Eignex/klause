package com.eignex.klause.lp.engine

import com.eignex.klause.lp.engine.authoritativeModel
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.numbers.BIG_ONE
import com.eignex.util.numbers.BigFraction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RefinementLifecycleTest {
    @Test
    fun `a caller funded correction after round sixty four should establish attainment`() {
        val builder = LpBuilder()
        val x = builder.addRealVar(0.0, 2.0, cost = 1.0)
        builder.addRealRow(intArrayOf(x), doubleArrayOf(2.0), Relation.EQ, 1.0)
        val state = LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
        val basis = Basis(intArrayOf(0), arrayOf(VarStatus.BASIC, VarStatus.FIXED))
        val sourceBasis = Basis(intArrayOf(1), arrayOf(VarStatus.AT_LOWER, VarStatus.BASIC))
        val hint = FloatLpResult(sourceBasis, 0.5, doubleArrayOf(0.0), doubleArrayOf(0.5),
            exactState = state)
        for (rounds in listOf(64, 65)) {
            var corrections = 0
            val factory = persistentDecorator { delegate, _ ->
                object : PersistentLpSolver by delegate {
                    override val lastPivots = 1
                    override val lastMetrics = LpSolveMetrics(workOps = 1L, pivots = 1)
                    override fun solve(warm: Basis?) = FloatLpResult(basis, 0.0,
                        doubleArrayOf(if (++corrections <= 64) 0.0 else 0.5), doubleArrayOf(0.0))
                    override fun resolveBounds(allowance: LpFloatAllowance?) = solve(null)
                }
            }
            LpScopedSolver(state, context = LpSolveContext(engineFactory = factory)).use { owner ->
                val result = certifyLpResult(
                    assertNotNull(state.toWorkingModel()), hintSolver(state, hint), hint,
                    refinement = LpRefinementRequest(owner, owner.refinementCache,
                        LpRefinementLimits(maxRounds = rounds, maxAuxiliaries = 0)),
                )

                assertEquals(if (rounds == 64) LpVerdict.FEASIBLE else LpVerdict.ATTAINED_OPTIMUM, result.verdict,
                    result.refinement.toString())
                assertEquals(rounds, assertNotNull(result.refinement).rounds)
                assertNull(result.continuation)
            }
        }
    }

    @Test
    fun `refinement should honor a funded basis beyond the default dimension`() {
        val rows = 129
        val zero = ExactLpNumber.of(0L)
        val state = LpExactState(ExactLpModel(
            emptyList(), List(rows) { zero }, List(rows) { ExactLpColumn(ExactLpBounds()) },
            List(rows) { ExactLpRow() }, ExactLpObjective(List(rows) { zero }),
        ))
        val basis = Basis(IntArray(rows) { it }, Array(rows) { VarStatus.BASIC })
        for (dimension in listOf(128, rows)) {
            LpScopedSolver(state).use { owner ->
                val result = refineLp(
                    assertNotNull(state.toWorkingModel()),
                    LpRefinementRequest(owner, owner.refinementCache,
                        LpRefinementLimits(maxAuxiliaries = 0, maxBasisDimension = dimension,
                            maxWork = 100_000_000L, maxAllocation = 256L * 1024 * 1024)),
                    basis = basis, preferBasis = true,
                )

                if (dimension == rows) {
                    assertEquals(emptyList(), assertNotNull(result.witness, result.metrics.toString()).primal)
                    assertEquals(BigFraction.ZERO, assertNotNull(result.bound).value)
                } else {
                    assertNull(result.witness)
                    assertNull(result.bound)
                }
            }
        }
    }

    @Test
    fun `correction headings should not replace preferred source factors`() {
        val builder = LpBuilder()
        val x = builder.addRealVar(0.0, 2.0, cost = -1.0)
        builder.addRealRow(intArrayOf(x), doubleArrayOf(1.0), Relation.LE, 1.0)
        val state = LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
        val model = assertNotNull(state.toWorkingModel())
        val basis = Basis(intArrayOf(1), arrayOf(VarStatus.AT_LOWER, VarStatus.BASIC))
        val cache = ExactBasisCache()
        assertNotNull(verifyExactBasis(model, basis, cache = cache).witness)
        LpScopedSolver(state).use { owner ->
            val result = refineLp(
                model,
                LpRefinementRequest(owner, owner.refinementCache, LpRefinementLimits()),
                doubleArrayOf(0.0),
                doubleArrayOf(0.0),
                basis,
                preferBasis = true,
                preferredBasisCache = cache,
            )
            val retained = verifyExactBasis(model, basis, cache = cache)

            assertEquals(BigFraction.MINUS_ONE, assertNotNull(result.witness).objective)
            assertEquals(result.witness.objective, assertNotNull(result.bound).value)
            assertTrue(result.metrics.rounds > 0)
            assertEquals(1, result.metrics.luReuse)
            assertEquals(0, result.metrics.luFactories)
            assertEquals(1, retained.metrics.reuse)
            assertEquals(0, retained.metrics.factoryCalls)
        }
    }

    @Test
    fun `a stale source state should not publish a refinement proof`() {
        val builder = LpBuilder()
        builder.addRealVar(0.0, 2.0)
        val state = LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
        LpScopedSolver(state).use { owner ->
            assertTrue(owner.assertBound(0, false, ExactLpSide(ExactLpNumber.of(1L)), 7L))
            val result = refineLp(
                assertNotNull(state.toWorkingModel()),
                LpRefinementRequest(owner, owner.refinementCache, LpRefinementLimits()),
            )

            assertEquals(LpRefinementDecline.AUTHORITY, result.metrics.decline)
            assertNull(result.witness)
            assertNull(result.bound)
            assertNull(result.conflict)
            assertTrue(owner.state !== state)
        }
    }

    @Test
    fun `a singular source basis should be rejected as a candidate`() {
        val state = singularState()
        LpScopedSolver(state).use { owner ->
            val result = refineLp(
                assertNotNull(state.toWorkingModel()),
                LpRefinementRequest(owner, owner.refinementCache, LpRefinementLimits()),
                basis = singularBasis,
                preferBasis = true,
            )

            assertEquals(LpRefinementDecline.CANDIDATE, result.metrics.decline)
            assertNotNull(result.sourceSingularBasis)
            assertNull(result.witness)
        }
    }

    @Test
    fun `work exhaustion during source basis verification should stay a resource decline`() {
        val state = singularState()
        var basisDecline: ExactBasisDecline? = null
        LpScopedSolver(state).use { owner ->
            val result = refineLp(
                assertNotNull(state.toWorkingModel()),
                LpRefinementRequest(
                    owner,
                    owner.refinementCache,
                    LpRefinementLimits(maxWork = 54),
                    onBasisVerification = { basisDecline = it.decline },
                ),
                basis = singularBasis,
                preferBasis = true,
            )

            assertEquals(ExactBasisDecline.WORK, basisDecline)
            assertEquals(LpRefinementDecline.WORK, result.metrics.decline)
            assertNull(result.sourceSingularBasis)
        }
    }

    @Test
    fun `a reconstructed source witness should survive a withheld LU bound`() {
        val builder = LpBuilder()
        val x = builder.addRealVar(0.0, 2.0, cost = 1.0)
        builder.addRealRow(intArrayOf(x), doubleArrayOf(3.0), Relation.EQ, 1.0)
        val state = LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
        val basis = Basis(intArrayOf(0), arrayOf(VarStatus.BASIC, VarStatus.FIXED))
        val hint = FloatLpResult(basis, 0.25, doubleArrayOf(0.0), doubleArrayOf(0.25), exactState = state)
        var solves = 0
        val factory = persistentDecorator { delegate, _ ->
            object : PersistentLpSolver by delegate {
                override val lastPivots = 0
                override val lastMetrics = LpSolveMetrics(workOps = 1L)
                override fun solve(warm: Basis?): FloatLpResult {
                    solves++
                    val primal = if (solves == 1) 1.0 / 3.0 else 0.0
                    return FloatLpResult(basis, 0.0, duals = doubleArrayOf(0.0), primal = doubleArrayOf(primal))
                }
                override fun resolveBounds(allowance: LpFloatAllowance?) = solve(null)
            }
        }
        LpScopedSolver(state, context = LpSolveContext(engineFactory = factory)).use { owner ->
            val result = certifyLpResult(
                assertNotNull(state.toWorkingModel()),
                hintSolver(state, hint),
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
    fun `dual scaling growth should be bounded independently of primal scaling`() {
        val zero = ExactLpNumber.of(0L)
        val tiny = ExactLpNumber.of(BigFraction.of(BIG_ONE, BIG_ONE shl 200))
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
        val factory = persistentDecorator { delegate, model ->
            object : PersistentLpSolver by delegate {
                private var current = assertNotNull(model.exactState)
                override val lastPivots = 1
                override val lastMetrics = LpSolveMetrics(workOps = 1L, pivots = 1)
                override fun adopt(state: LpExactState, token: ExecutionControl): Boolean {
                    current = state
                    return delegate.adopt(state, token)
                }
                override fun solve(warm: Basis?): FloatLpResult {
                    costs += current.model.objective.cost(0).value
                    val dual = if (costs.size == 2) 1.0 else 0.0
                    return FloatLpResult(basis, 0.0, duals = doubleArrayOf(dual), primal = doubleArrayOf(0.0))
                }
                override fun resolveBounds(allowance: LpFloatAllowance?) = solve(null)
            }
        }
        LpScopedSolver(state, context = LpSolveContext(engineFactory = factory)).use { owner ->
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
        assertEquals(BigFraction.of(BIG_ONE, BIG_ONE shl 120), costs.last())
    }

    @Test
    fun `exhausted refinement admission should not buy an ordinary basis proof`() {
        val builder = LpBuilder()
        val x = builder.addRealVar(0.0, 2.0, cost = 1.0)
        builder.addRealRow(intArrayOf(x), doubleArrayOf(3.0), Relation.EQ, 1.0)
        val state = LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
        val basis = Basis(intArrayOf(0), arrayOf(VarStatus.BASIC, VarStatus.FIXED))
        val hint = FloatLpResult(basis, 0.25, doubleArrayOf(0.25), doubleArrayOf(0.0), exactState = state)
        LpScopedSolver(state).use { owner ->
            val result = certifyLpResult(
                assertNotNull(state.toWorkingModel()),
                hintSolver(state, hint),
                hint,
                refinement = LpRefinementRequest(owner, owner.refinementCache, LpRefinementLimits(maxWork = 0L)),
            )

            assertEquals(LpRefinementDecline.WORK, assertNotNull(result.refinement).decline)
            assertNull(result.basisVerification)
            assertNotNull(result.continuation)
        }
    }

    @Test
    fun `failed child adoption should retire the child and preserve source availability`() {
        val builder = LpBuilder()
        builder.addVar(0, 2, cost = 1)
        val state = LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
        var fail = false
        val failure = IllegalStateException("adoption failed")
        val factory = persistentDecorator { delegate, _ ->
            object : PersistentLpSolver by delegate {
                override fun adopt(state: LpExactState, token: ExecutionControl): Boolean {
                    if (fail) throw failure
                    return delegate.adopt(state, token)
                }
            }
        }
        LpScopedSolver(state, context = LpSolveContext(engineFactory = factory)).use { owner ->
            owner.withWorkingModel(LpWorkingModel.overrides(state), allowance = LpFloatAllowance(10000L, 10)) { scope ->
                assertNotNull(scope.solveFloat())
                fail = true
                val next = LpWorkingModel.overrides(state, objective = ExactLpObjective(listOf(ExactLpNumber.of(-1L))))

                assertSame(failure, assertFailsWith<IllegalStateException> { scope.replaceState(next) })
            }
            fail = false

            assertSame(state, owner.state)
            assertEquals(LpVerdict.ATTAINED_OPTIMUM, assertNotNull(owner.solve()).verdict)
        }
    }

    @Test
    fun `cleanup failure should record consumed refinement work without publishing a result`() {
        val builder = LpBuilder()
        val x = builder.addVar(0, 2, cost = 1)
        builder.addRow(intArrayOf(x), longArrayOf(1L), Relation.GE, 1L)
        val state = LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
        val failure = IllegalStateException("cleanup failed")
        val factory = persistentDecorator { delegate, _ ->
            object : PersistentLpSolver by delegate {
                override fun close() {
                    delegate.close()
                    throw failure
                }
            }
        }
        LpScopedSolver(state, context = LpSolveContext(engineFactory = factory)).use { owner ->
            val model = assertNotNull(state.toWorkingModel())
            val request = LpRefinementRequest(owner, owner.refinementCache, LpRefinementLimits())

            assertSame(failure, assertFailsWith<IllegalStateException> { refineLp(model, request) })

            assertTrue(owner.refinementCache.lastMetrics.work > 0L)
            assertEquals(LpRefinementDecline.FAILURE, owner.refinementCache.lastMetrics.decline)
            assertNull(owner.lastResult)
        }
    }

    @Test
    fun `rejected refinement witnesses should not be republished through another certifier`() {
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
        val basis = Basis(intArrayOf(0), arrayOf(VarStatus.BASIC, VarStatus.AT_LOWER))
        val hint = FloatLpResult(basis, 0.25, doubleArrayOf(0.25), doubleArrayOf(0.0), exactState = state)
        LpScopedSolver(state).use { owner ->
            val result = certifyLpResult(
                assertNotNull(state.toWorkingModel()),
                hintSolver(state, hint),
                hint,
                policy = LpCertificationPolicy { kind, success -> success && kind != LpCertifier.RATIONAL },
                refinement = LpRefinementRequest(owner, owner.refinementCache, LpRefinementLimits()),
            )

            assertNull(result.witness)
            assertNull(result.unboundedness)
            assertNull(result.rationalConflict)
            assertNotNull(result.bound)
        }
    }

    private val singularBasis = Basis(intArrayOf(0), arrayOf(VarStatus.BASIC, VarStatus.FIXED))

    private fun singularState(): LpExactState {
        val zero = ExactLpNumber.of(0L)
        return LpExactState(
            ExactLpModel(
                listOf(emptyList()),
                listOf(zero),
                listOf(
                    ExactLpColumn(ExactLpBounds(ExactLpSide(zero, strict = true)), integral = false),
                    ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(zero)), integral = false),
                ),
                listOf(ExactLpRow()),
                ExactLpObjective(listOf(zero, zero)),
            ),
        )
    }

    private fun hintSolver(state: LpExactState, hint: FloatLpResult) = object : LpSolver {
        override val solvedExactState = state
        override val infeasibleRay: DoubleArray? = null
        override fun solve(warm: Basis?) = hint
        override fun solvePrimal(warm: Basis?) = hint
    }

    private fun persistentDecorator(wrap: (PersistentLpSolver, LpModel) -> PersistentLpSolver) =
        object : LpEngineFactory by ProductionLpEngineFactory {
            override fun newPersistentSolver(
                model: LpModel,
                cancellation: ExecutionControl,
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
                model,
            )
        }
}
