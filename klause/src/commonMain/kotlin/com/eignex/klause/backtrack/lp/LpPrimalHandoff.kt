package com.eignex.klause.backtrack.lp

import com.eignex.klause.lp.bounding.LpEngine
import com.eignex.klause.lp.bounding.RootPrimalContinuation
import com.eignex.klause.lp.relaxation.LpAssemblyCancelled
import com.eignex.klause.lp.relaxation.LpRelaxation
import com.eignex.klause.lp.relaxation.checkLpAssembly
import com.eignex.klause.lp.relaxation.withModel
import com.eignex.klause.util.Cancellation
import kotlin.time.TimeSource

// The completed model is immutable; the handoff confines its use to one root-primal call.
internal class LpPrimalHandoff private constructor(
    private var owner: LpEngine?,
    private var setupWork: Long,
) : AutoCloseable {
    private var definition: LpRelaxation? = null
    private var captureAttempted = false
    private var sourceWork = 0L
    private var continuation: RootPrimalContinuation? = null

    fun hasDefinitionFor(engine: LpEngine): Boolean = owner === engine && definition != null

    fun setupWorkFor(engine: LpEngine): Long = if (owner === engine) setupWork else 0L

    fun includeSourceWork(engine: LpEngine, work: Long) {
        require(work >= 0L)
        if (owner === engine) {
            setupWork += work
            sourceWork += work
        }
    }

    fun includeDiscardedWork(engine: LpEngine, work: Long) {
        require(work >= 0L)
        if (owner === engine) setupWork += work
    }

    fun attachContinuation(engine: LpEngine, source: RootPrimalContinuation?) {
        if (owner === engine) continuation = source
    }

    fun capture(engine: LpEngine, root: LpRelaxation, cancellation: Cancellation): LpRelaxation? {
        if (owner !== engine || captureAttempted) return null
        engine.requireOpen()
        captureAttempted = true
        val accounting = LpPrimalAssembly(engine, cancellation)
        val token = accounting.token
        val started = TimeSource.Monotonic.markNow()
        var outcome = "FAILED"
        try {
            token.charge(1L)
            token.checkLpAssembly()
            if (!LpPumpPreparation.hasAffordableShape(engine, root, setupWork + accounting.work + 1L)) {
                outcome = "DECLINED"
                return null
            }
            val detached = root.withModel(root.model, sources = null, primalDefinition = root.primalDefinition)
            token.checkLpAssembly()
            definition = detached
            outcome = "CAPTURED"
            return detached
        } catch (cancelled: LpAssemblyCancelled) {
            definition = null
            outcome = "CANCELLED"
            throw cancelled
        } finally {
            setupWork += accounting.work
            engine.sink.lp.observePrimalPhase("PRIMAL_LP_HANDOFF", outcome,
                started.elapsedNow().inWholeNanoseconds, accounting.work)
        }
    }

    fun prepareFor(engine: LpEngine, cancellation: Cancellation): LpPumpPreparation? {
        if (owner !== engine) return null
        engine.requireOpen()
        val root = definition ?: return null
        val accounting = LpPrimalAssembly(engine, cancellation)
        val token = accounting.token
        val started = TimeSource.Monotonic.markNow()
        var outcome = "FAILED"
        try {
            token.charge(1L)
            token.checkLpAssembly()
            definition = null
            token.checkLpAssembly()
            outcome = "OFFERED"
        } catch (cancelled: LpAssemblyCancelled) {
            definition = null
            outcome = "CANCELLED"
            throw cancelled
        } finally {
            setupWork += accounting.work
            engine.sink.lp.observePrimalPhase("PRIMAL_LP_HANDOFF", outcome,
                started.elapsedNow().inWholeNanoseconds, accounting.work)
        }
        return LpPumpPreparation.create(engine, root, cancellation, setupWork)
    }

    override fun close() {
        val engine = owner ?: return
        val started = TimeSource.Monotonic.markNow()
        val outcome = if (definition == null) "RETIRED" else "DISCARDED"
        continuation?.includeSetupWork(engine, setupWork - sourceWork)
        continuation = null
        definition = null
        owner = null
        engine.sink.lp.observePrimalPhase("PRIMAL_LP_HANDOFF", outcome, started.elapsedNow().inWholeNanoseconds)
    }

    companion object {
        fun open(engine: LpEngine, cancellation: Cancellation): LpPrimalHandoff? {
            if (!engine.reusePumpPreparation) return null
            engine.requireOpen()
            val accounting = LpPrimalAssembly(engine, cancellation)
            val token = accounting.token
            val started = TimeSource.Monotonic.markNow()
            var outcome = "FAILED"
            return try {
                token.charge(1L)
                token.checkLpAssembly()
                if (engine.problem.numRealVars != 0 || engine.projections?.builtInRelaxation != true ||
                    !engine.projections.nativePropagation
                ) {
                    outcome = "DECLINED"
                    null
                } else {
                    // Reserve the constant retirement work before retaining any definition.
                    token.charge(1L)
                    token.checkLpAssembly()
                    val scope = LpPrimalHandoff(engine, accounting.work)
                    token.checkLpAssembly()
                    outcome = "OPENED"
                    scope
                }
            } catch (_: LpAssemblyCancelled) {
                outcome = "CANCELLED"
                null
            } finally {
                engine.sink.lp.observePrimalPhase("PRIMAL_LP_HANDOFF", outcome,
                    started.elapsedNow().inWholeNanoseconds, accounting.work)
            }
        }
    }
}
