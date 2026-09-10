package com.eignex.klause.lp.engine

import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
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

    override fun newGeneralSolver(model: LpModel, cancellation: Cancellation): LpSolver {
        calls += EngineConstructionCall(EngineConstruction.GENERAL)
        return delegate.newGeneralSolver(model, cancellation)
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
        return delegate.newPersistentSolver(
            model,
            cancellation,
            refactorUpdateLimit,
            iterationLimit,
            workLimit,
            trackDegeneracy,
            pricing,
        )
    }
}

private class SolveRecordingObserver : LpCertificationObserver {
    val solves = ArrayList<Pair<LpSolveMetrics, Boolean>>()

    override fun observe(certifier: LpCertifier, success: Boolean) = Unit

    override fun observeExactInput(accepted: Boolean) = Unit

    override fun observeSolve(metrics: LpSolveMetrics, component: Boolean) {
        solves += metrics to component
    }
}

class LpSolverInjectionTest {

    @Test
    fun `explicit production dependencies preserve result proof and metrics`() {
        val builder = LpBuilder()
        val x = builder.addVar(0L, 8L, cost = 2L)
        builder.addRow(intArrayOf(x), longArrayOf(1L), Relation.GE, 3L)
        val model = builder.build(Sense.MINIMIZE)
        val defaultObserver = SolveRecordingObserver()
        val explicitObserver = SolveRecordingObserver()

        val baseline = solveAndCertify(model, observer = defaultObserver)
        val explicit = solveAndCertify(
            model,
            observer = explicitObserver,
            context = LpSolveContext(ProductionLpEngineFactory, ProductionLpCertificationPolicy),
        )

        assertEquals(baseline.verdict, explicit.verdict)
        assertEquals(baseline.integerObjectiveLowerBound, explicit.integerObjectiveLowerBound)
        assertEquals(baseline.float?.objective, explicit.float?.objective)
        assertContentEquals(baseline.float?.basis?.basicVars, explicit.float?.basis?.basicVars)
        assertContentEquals(baseline.farkasRay, explicit.farkasRay)
        assertEquals(defaultObserver.solves, explicitObserver.solves)
    }

    @Test
    fun `factory receives general component persistent and tableau construction`() {
        val builder = LpBuilder()
        val x = builder.addVar(0L, 4L, cost = 1L)
        val y = builder.addVar(0L, 4L, cost = 1L)
        builder.addRow(intArrayOf(x), longArrayOf(1L), Relation.GE, 1L)
        builder.addRow(intArrayOf(y), longArrayOf(1L), Relation.GE, 2L)
        val model = builder.build(Sense.MINIMIZE)
        val factory = RecordingLpEngineFactory()

        newLpSolver(model, factory = factory).close()
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
        ).use {
            assertIs<PersistentLpSolver>(it)
        }

        assertEquals(2, factory.calls.count { it.kind == EngineConstruction.GENERAL })
        assertEquals(1, factory.calls.count { it.kind == EngineConstruction.COMPONENT })
        assertTrue(
            factory.calls.any {
                it == EngineConstructionCall(
                    EngineConstruction.TABLEAU,
                    17,
                    1234L,
                    0,
                    true,
                    LpZeroObjectivePricing.LARGEST_PIVOT,
                )
            },
        )
        assertTrue(
            factory.calls.any {
                it == EngineConstructionCall(
                    EngineConstruction.PERSISTENT,
                    19,
                    4321L,
                    71,
                    true,
                    LpZeroObjectivePricing.MIN_BOUND_SUPPORT,
                    23L,
                )
            },
        )
    }

    @Test
    fun `component capability survives an injected factory decorator`() {
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
                    ): ExactLpWitness? = delegate.exactWitness(observer, policy)
                }
            }
        }

        val result = solveAndCertify(model, context = LpSolveContext(engineFactory = factory))

        assertEquals(LpVerdict.ATTAINED_OPTIMUM, result.verdict)
    }

    @Test
    fun `component split closes owned solvers when subsolver construction throws`() {
        val model = twoComponentModel()
        var construction = 0
        var closed = 0
        val factory = object : LpEngineFactory by ProductionLpEngineFactory {
            override fun newGeneralSolver(model: LpModel, cancellation: Cancellation): LpSolver {
                construction++
                if (construction == 2) error("injected construction failure")
                return closeTrackingSolver { closed++ }
            }
        }

        assertFailsWith<IllegalStateException> { newLpSolver(model, factory = factory) }

        assertEquals(1, closed)
    }

    @Test
    fun `component split closes owned solvers when wrapper construction throws`() {
        val model = twoComponentModel()
        var closed = 0
        val factory = object : LpEngineFactory by ProductionLpEngineFactory {
            override fun newGeneralSolver(model: LpModel, cancellation: Cancellation): LpSolver =
                closeTrackingSolver { closed++ }

            override fun newComponentSolver(
                model: LpModel,
                parts: List<LpNeighborhood>,
                solvers: List<LpSolver>,
                isolated: IntArray,
            ): ComponentLpSolverCapability = error("injected component construction failure")
        }

        assertFailsWith<IllegalStateException> { newLpSolver(model, factory = factory) }

        assertEquals(2, closed)
    }

    @Test
    fun `thrown solve closes the injected solver and preserves cancellation`() {
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
            override fun newGeneralSolver(model: LpModel, cancellation: Cancellation): LpSolver {
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
