package com.eignex.klause.lp.engine

import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

internal enum class EngineConstruction { GENERAL, COMPONENT, TABLEAU, PERSISTENT }

internal data class EngineConstructionCall(
    val kind: EngineConstruction,
    val iterationLimit: Int = 0,
    val workLimit: Long = 0L,
    val refactorUpdateLimit: Int = 0,
    val trackDegeneracy: Boolean = false,
    val zeroObjectivePricing: LpZeroObjectivePricing = LpZeroObjectivePricing.MIN_BOUND_SUPPORT,
    val tieSeed: Long = 0L,
)

internal class RecordingLpEngineFactory(private val delegate: LpEngineFactory = ProductionLpEngineFactory) :
    LpEngineFactory {
    val calls = ArrayList<EngineConstructionCall>()
    val floatAllowances = ArrayList<LpFloatAllowance?>()

    override fun newGeneralSolver(
        model: LpModel,
        cancellation: Cancellation,
        workLimit: Long,
        pricing: LpPricingOptions,
    ): LpSolver {
        calls += EngineConstructionCall(
            EngineConstruction.GENERAL,
            workLimit = workLimit,
            zeroObjectivePricing = pricing.zeroObjective,
            tieSeed = pricing.tieSeed,
        )
        return delegate.newGeneralSolver(model, cancellation, workLimit, pricing)
    }

    override fun newComponentSolver(
        model: LpModel,
        parts: List<LpNeighborhood>,
        solvers: List<LpSolver>,
        isolated: IntArray,
    ): ComponentLpSolverCapability {
        calls += EngineConstructionCall(EngineConstruction.COMPONENT)
        return delegate.newComponentSolver(model, parts, solvers, isolated)
    }

    override fun newRetainedComponentSolver(
        model: LpModel,
        parts: List<LpNeighborhood>,
        solvers: List<LpSolver>,
        isolated: IntArray,
    ): RetainedComponentLpSolverCapability {
        calls += EngineConstructionCall(EngineConstruction.COMPONENT)
        return delegate.newRetainedComponentSolver(model, parts, solvers, isolated)
    }

    override fun newTableauSolver(
        model: LpModel,
        cancellation: Cancellation,
        iterationLimit: Int,
        workLimit: Long,
        trackDegeneracy: Boolean,
        pricing: LpPricingOptions,
    ): TableauCutSolver {
        calls += EngineConstructionCall(
            EngineConstruction.TABLEAU,
            iterationLimit = iterationLimit,
            workLimit = workLimit,
            trackDegeneracy = trackDegeneracy,
            zeroObjectivePricing = pricing.zeroObjective,
            tieSeed = pricing.tieSeed,
        )
        return delegate.newTableauSolver(model, cancellation, iterationLimit, workLimit, trackDegeneracy, pricing)
    }

    override fun newPersistentSolver(
        model: LpModel,
        cancellation: Cancellation,
        refactorUpdateLimit: Int,
        iterationLimit: Int,
        workLimit: Long,
        trackDegeneracy: Boolean,
        pricing: LpPricingOptions,
    ): PersistentLpSolver {
        calls += EngineConstructionCall(
            EngineConstruction.PERSISTENT,
            iterationLimit = iterationLimit,
            workLimit = workLimit,
            refactorUpdateLimit = refactorUpdateLimit,
            trackDegeneracy = trackDegeneracy,
            zeroObjectivePricing = pricing.zeroObjective,
            tieSeed = pricing.tieSeed,
        )
        val solver = delegate.newPersistentSolver(
            model,
            cancellation,
            refactorUpdateLimit,
            iterationLimit,
            workLimit,
            trackDegeneracy,
            pricing,
        )
        return object : PersistentLpSolver by solver {
            override fun resolveBounds(allowance: LpFloatAllowance?): FloatLpResult? {
                floatAllowances += allowance
                return solver.resolveBounds(allowance)
            }
        }
    }
}

class LpSolverTest {

    @Test
    fun `factory should receive general component persistent and tableau construction`() {
        val builder = LpBuilder()
        val x = builder.addVar(0L, 4L, cost = 1L)
        val y = builder.addVar(0L, 4L, cost = 1L)
        builder.addRow(intArrayOf(x), longArrayOf(1L), Relation.GE, 1L)
        builder.addRow(intArrayOf(y), longArrayOf(1L), Relation.GE, 2L)
        val model = builder.build(Sense.MINIMIZE)
        val factory = RecordingLpEngineFactory()

        newLpSolver(
            model,
            factory = factory,
            pricing = LpPricingOptions(LpZeroObjectivePricing.LARGEST_PIVOT, 17L),
        ).close()
        newTableauCutSolver(
            model,
            iterationLimit = 17,
            workLimit = 1234L,
            trackDegeneracy = true,
            factory = factory,
            pricing = LpPricingOptions(LpZeroObjectivePricing.LARGEST_PIVOT),
        ).close()
        newPersistentLpSolver(
            model,
            refactorUpdateLimit = 71,
            iterationLimit = 19,
            workLimit = 4321L,
            trackDegeneracy = true,
            factory = factory,
            pricing = LpPricingOptions(LpZeroObjectivePricing.MIN_BOUND_SUPPORT, 23L),
        ).close()

        val general = factory.calls.filter { it.kind == EngineConstruction.GENERAL }
        assertEquals(2, general.size)
        assertTrue(general.all { it.zeroObjectivePricing == LpZeroObjectivePricing.LARGEST_PIVOT && it.tieSeed == 17L })
        assertEquals(1, factory.calls.count { it.kind == EngineConstruction.COMPONENT })
        val tableau = EngineConstructionCall(
            EngineConstruction.TABLEAU,
            iterationLimit = 17,
            workLimit = 1234L,
            trackDegeneracy = true,
            zeroObjectivePricing = LpZeroObjectivePricing.LARGEST_PIVOT,
        )
        assertTrue(tableau in factory.calls)
        val persistent = EngineConstructionCall(EngineConstruction.PERSISTENT, 19, 4321L, 71, true, tieSeed = 23L)
        assertTrue(persistent in factory.calls)
    }

    @Test
    fun `component capability should survive an injected factory decorator`() {
        val builder = LpBuilder()
        repeat(50) {
            val column = builder.addRealVar(0.0, 1.0)
            builder.addRealRow(intArrayOf(column), doubleArrayOf(1.0), Relation.LE, 1.0)
        }
        val model = builder.build(Sense.MINIMIZE)
        val factory = object : LpEngineFactory by ProductionLpEngineFactory {
            override fun newComponentSolver(
                model: LpModel,
                parts: List<LpNeighborhood>,
                solvers: List<LpSolver>,
                isolated: IntArray,
            ): ComponentLpSolverCapability {
                val delegate = ProductionLpEngineFactory.newComponentSolver(model, parts, solvers, isolated)
                return object : ComponentLpSolverCapability, LpSolver by delegate {
                    override fun exactBound(
                        observer: LpCertificationObserver?,
                        policy: LpCertificationPolicy,
                    ): CertifiedLpBound? = delegate.exactBound(observer, policy)

                    override fun exactWitness(
                        observer: LpCertificationObserver?,
                        policy: LpCertificationPolicy,
                        cancellation: Cancellation,
                    ): ExactLpWitness? = delegate.exactWitness(observer, policy, cancellation)
                }
            }
        }

        val result = solveAndCertify(model, context = LpSolveContext(engineFactory = factory))

        assertEquals(LpVerdict.ATTAINED_OPTIMUM, result.verdict)
    }

    @Test
    fun `component close should release every child and preserve the first close failure`() {
        val first = IllegalStateException("first close")
        val second = IllegalStateException("second close")
        var created = 0
        var closed = 0
        val factory = object : LpEngineFactory by ProductionLpEngineFactory {
            override fun newGeneralSolver(
                model: LpModel,
                cancellation: Cancellation,
                workLimit: Long,
                pricing: LpPricingOptions,
            ): LpSolver {
                val failure = if (created++ == 0) first else second
                return closeTrackingSolver {
                    closed++
                    throw failure
                }
            }
        }
        val solver = newLpSolver(twoComponentModel(), factory = factory)

        val failure = assertFailsWith<IllegalStateException> { solver.close() }

        assertSame(first, failure)
        assertSame(second, failure.suppressedExceptions.single())
        assertEquals(2, closed)
        solver.close()
        assertEquals(2, closed)
    }

    @Test
    fun `component split should close owned solvers when subsolver construction throws`() {
        val model = twoComponentModel()
        var construction = 0
        var closed = 0
        val factory = object : LpEngineFactory by ProductionLpEngineFactory {
            override fun newGeneralSolver(
                model: LpModel,
                cancellation: Cancellation,
                workLimit: Long,
                pricing: LpPricingOptions,
            ): LpSolver {
                construction++
                if (construction == 2) error("injected construction failure")
                return closeTrackingSolver { closed++ }
            }
        }

        assertFailsWith<IllegalStateException> { newLpSolver(model, factory = factory) }

        assertEquals(1, closed)
    }

    @Test
    fun `a thrown solve should close the injected solver and preserve cancellation`() {
        val model = LpBuilder().apply { addVar(0L, 1L) }.build(Sense.MINIMIZE)
        var closed = false
        val token = Cancellation { true }
        val solver = object : LpSolver {
            override fun solve(warm: Basis?): FloatLpResult? = error("injected solve failure")
            override fun solvePrimal(warm: Basis?): FloatLpResult? = null
            override val infeasibleRay: DoubleArray? = null
            override fun close() {
                closed = true
            }
        }
        val factory = object : LpEngineFactory by ProductionLpEngineFactory {
            override fun newGeneralSolver(
                model: LpModel,
                cancellation: Cancellation,
                workLimit: Long,
                pricing: LpPricingOptions,
            ): LpSolver {
                assertSame(token, cancellation)
                return solver
            }
        }

        assertFailsWith<IllegalStateException> {
            solveAndCertify(
                model,
                cancellation = token,
                componentSplit = false,
                context = LpSolveContext(engineFactory = factory),
            )
        }
        assertTrue(closed)
    }

    private fun twoComponentModel(): LpModel {
        val builder = LpBuilder()
        val x = builder.addVar(0L, 1L)
        val y = builder.addVar(0L, 1L)
        builder.addRow(intArrayOf(x), longArrayOf(1L), Relation.LE, 1L)
        builder.addRow(intArrayOf(y), longArrayOf(1L), Relation.LE, 1L)
        return builder.build(Sense.MINIMIZE)
    }

    private fun closeTrackingSolver(onClose: () -> Unit): LpSolver = object : LpSolver {
        override fun solve(warm: Basis?): FloatLpResult? = null
        override fun solvePrimal(warm: Basis?): FloatLpResult? = null
        override val infeasibleRay: DoubleArray? = null
        override fun close() = onClose()
    }
}
