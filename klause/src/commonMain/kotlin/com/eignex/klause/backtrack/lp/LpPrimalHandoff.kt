package com.eignex.klause.backtrack.lp

import com.eignex.klause.lp.bounding.LpEngine
import com.eignex.klause.lp.relaxation.LpAssemblyCancelled
import com.eignex.klause.lp.relaxation.LpRelaxation
import com.eignex.klause.lp.relaxation.checkLpAssembly
import com.eignex.klause.lp.relaxation.withModel
import com.eignex.klause.util.Cancellation
import kotlin.time.TimeSource

// The fresh canonical root is fixed within one uninterrupted strategy call; pinning uses other sessions.
internal class LpPrimalHandoff private constructor(
    private var owner: LpEngine?,
    private var setupWork: Long,
) : AutoCloseable {
    private var definition: LpRelaxation? = null
    private var captureAttempted = false

    fun hasDefinitionFor(engine: LpEngine): Boolean = owner === engine && definition != null

    fun setupWorkFor(engine: LpEngine): Long = if (owner === engine) setupWork else 0L

    fun capture(engine: LpEngine, root: LpRelaxation, cancellation: Cancellation) {
        if (owner !== engine || captureAttempted) return
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
                return
            }
            val detached = root.withModel(root.model, sources = null)
            token.checkLpAssembly()
            definition = detached
            outcome = "CAPTURED"
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
