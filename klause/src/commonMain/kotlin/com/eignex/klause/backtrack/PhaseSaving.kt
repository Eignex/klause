package com.eignex.klause.backtrack

import com.eignex.klause.backtrack.selector.VarRef
import com.eignex.klause.propagation.PropagationSession
import kotlin.random.Random

/**
 * Phase-saving and target-phasing state for one backtrack run: the saved polarity of each
 * Boolean/integer variable, the target phase (the deepest conflict-free Boolean prefix), and the
 * rephasing schedule that rotates which polarity source a fresh decision tries first. Shared by both
 * the satisfaction path ([BacktrackSolver] `driveSearch`) and the branch-and-bound engine
 * ([ResumableMinimize]); all state persists across restarts.
 *
 * Arrays are allocated only for the enabled features — the Boolean saves whenever
 * [BacktrackParams.phaseSaving] or [BacktrackParams.targetPhasing] is on (target phasing's `SAVED`
 * mode falls back to them), the integer saves on [BacktrackParams.phaseSaving] alone (target phasing
 * is pure-Boolean), the target arrays on [BacktrackParams.targetPhasing]. A disabled feature leaves
 * its arrays null and every method degrades to a no-op or the identity ordering.
 */
internal class PhaseSaving(numBoolVars: Int, numIntVars: Int, private val params: BacktrackParams) {
    private val boolPhaseTracking = params.phaseSaving || params.targetPhasing
    private val boolPhase: BooleanArray? = if (boolPhaseTracking) BooleanArray(numBoolVars) else null
    private val boolPhaseSet: BooleanArray? = if (boolPhaseTracking) BooleanArray(numBoolVars) else null
    private val intPhase: LongArray? = if (params.phaseSaving) LongArray(numIntVars) else null
    private val intPhaseSet: BooleanArray? = if (params.phaseSaving) BooleanArray(numIntVars) else null
    private val boolTarget: BooleanArray? = if (params.targetPhasing) BooleanArray(numBoolVars) else null
    private val boolTargetSet: BooleanArray? = if (params.targetPhasing) BooleanArray(numBoolVars) else null

    private var bestTrailSize = -1
    private var rephaseMode = RephaseMode.TARGET
    private var conflictsSinceRephase = 0L

    /**
     * If a value is cached for [varRef], prepend it to the heuristic's [values] order (dropping it
     * from the rest so it isn't tried twice); otherwise pass [values] through unchanged. For a Boolean
     * the cached value is the target phase, the saved phase, or a rephase-mode fixed/random polarity;
     * for an integer it is the saved value. [rng] supplies the `RANDOM` rephase mode's coin flip.
     */
    fun applyPhase(varRef: VarRef, values: Sequence<Long>, rng: Random? = null): Sequence<Long> = when (varRef) {
        is VarRef.Bool -> {
            val v = varRef.varId
            val savedFirst: Long? = if (boolPhase != null && boolPhaseSet != null && boolPhaseSet[v]) {
                if (boolPhase[v]) 1L else 0L
            } else {
                null
            }
            // Target phasing rotates the polarity source; plain phase saving just uses the
            // saved value. The chosen value (if any) is tried first, with the heuristic's
            // order filling the rest.
            val preferred: Long? = if (boolTarget != null && boolTargetSet != null) {
                when (rephaseMode) {
                    // Target: the deepest conflict-free phase, falling back to saved.
                    RephaseMode.TARGET -> if (boolTargetSet[v]) (if (boolTarget[v]) 1L else 0L) else savedFirst

                    RephaseMode.SAVED -> savedFirst

                    RephaseMode.TRUE -> 1L

                    RephaseMode.FALSE -> 0L

                    RephaseMode.RANDOM -> if ((rng ?: Random.Default).nextBoolean()) 1L else 0L
                }
            } else {
                savedFirst
            }
            if (preferred != null) sequenceOf(preferred) + values.filter { it != preferred } else values
        }

        is VarRef.IntVar -> {
            if (intPhase != null && intPhaseSet != null && intPhaseSet[varRef.varId]) {
                val saved = intPhase[varRef.varId]
                sequenceOf(saved) + values.filter { it != saved }
            } else {
                values
            }
        }
    }

    /** Record [varRef]'s currently-pinned value for phase-saving. Called after every successful pin. */
    fun capture(varRef: VarRef, session: PropagationSession) {
        when (varRef) {
            is VarRef.Bool -> {
                if (boolPhase != null && boolPhaseSet != null) {
                    val v = session.boolValue(varRef.varId)
                    if (v != null) {
                        boolPhase[varRef.varId] = v
                        boolPhaseSet[varRef.varId] = true
                    }
                }
            }

            is VarRef.IntVar -> {
                if (intPhase != null && intPhaseSet != null) {
                    val d = session.intDomain(varRef.varId)
                    if (d.min == d.max) {
                        intPhase[varRef.varId] = d.min
                        intPhaseSet[varRef.varId] = true
                    }
                }
            }
        }
    }

    /**
     * When [trailSize] is a new maximum depth, snapshot the current Boolean assignment as the target
     * phase (the deepest conflict-free prefix). Variables not yet pinned keep their previous target
     * entry — a deeper later descent fills them in. A no-op when target phasing is off.
     */
    fun captureTargetIfDeeper(session: PropagationSession, trailSize: Int) {
        if (boolTarget == null || boolTargetSet == null || trailSize <= bestTrailSize) return
        bestTrailSize = trailSize
        for (v in boolTarget.indices) {
            val value = session.boolValue(v) ?: continue
            boolTarget[v] = value
            boolTargetSet[v] = true
        }
    }

    /**
     * Advance the rephasing schedule on a conflict and rotate [rephaseMode] every
     * [BacktrackParams.rephaseInterval] conflicts. The mode change takes effect on the next fresh
     * descent — no need to pop to root, since rephasing only reorders which polarity a new decision
     * tries first. A no-op when target phasing is off.
     */
    fun onConflictTick() {
        if (boolTarget == null) return
        conflictsSinceRephase++
        if (conflictsSinceRephase >= params.rephaseInterval) {
            conflictsSinceRephase = 0
            rephaseMode = rephaseMode.next()
        }
    }
}
