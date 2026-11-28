package com.eignex.klause.backtrack

import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.search.VarRef
import kotlin.random.Random

// Phase-saving and target-phasing state for one search, persisting across restarts: the saved value of each
// variable, the target phase (the deepest conflict-free Boolean prefix), and the rephasing schedule.
//
// Phase saving follows Pipatsrisawat and Darwiche, "A Lightweight Component Caching Scheme for
// Satisfiability Solvers", SAT 2007 (https://doi.org/10.1007/978-3-540-72788-0_28); target phases follow
// Biere and Fleury, "Chasing Target Phases", POS 2020 (https://fmv.jku.at/papers/BiereFleury-POS20.pdf).
//
// Arrays exist only for enabled features: Boolean saves when phaseSaving or targetPhasing is on (targetPhasing's
// SAVED mode falls back to them), integer saves on phaseSaving alone, target arrays on targetPhasing. A disabled
// feature degrades every method to a no-op or the identity ordering.
internal class PhaseSaving(numBoolVars: Int, numIntVars: Int, private val params: BacktrackParams) {
    private val boolPhaseTracking = params.phaseSaving || params.targetPhasing
    private val boolPhase: BooleanArray? = if (boolPhaseTracking) BooleanArray(numBoolVars) else null
    private val boolPhaseSet: BooleanArray? = if (boolPhaseTracking) BooleanArray(numBoolVars) else null
    private val intPhase: LongArray? = if (params.phaseSaving) LongArray(numIntVars) else null
    private val intPhaseSet: BooleanArray? = if (params.phaseSaving) BooleanArray(numIntVars) else null
    private val boolTarget: BooleanArray? = if (params.targetPhasing) BooleanArray(numBoolVars) else null
    private val boolTargetSet: BooleanArray? = if (params.targetPhasing) BooleanArray(numBoolVars) else null
    private val solutionBools: BooleanArray? = if (params.solutionPhasing) BooleanArray(numBoolVars) else null
    private val solutionInts: LongArray? = if (params.solutionPhasing) LongArray(numIntVars) else null
    private var hasSolution = false

    private var bestTrailSize = -1
    private var rephaseMode = RephaseMode.TARGET
    private var conflictsSinceRephase = 0L

    // A restart schedule managing the phase regime (STABLE / FOCUSED) pins the polarity source and suspends the
    // autonomous rephase rotation; UNMANAGED leaves the rotation in charge.
    private var managedMode = PhaseMode.UNMANAGED

    fun setManagedMode(mode: PhaseMode) {
        managedMode = mode
    }

    // For a Boolean the preferred value is the target phase, the saved phase, or a rephase-mode polarity; for an
    // integer it is the saved value. It is tried first and dropped from the rest of the order.
    fun applyPhase(varRef: VarRef, values: Sequence<Long>, rng: Random? = null): Sequence<Long> = when (varRef) {
        is VarRef.Bool -> {
            val v = varRef.varId
            val savedFirst: Long? = if (boolPhase != null && boolPhaseSet != null && boolPhaseSet[v]) {
                if (boolPhase[v]) 1L else 0L
            } else {
                null
            }
            val solutionFirst: Long? = if (solutionBools != null && hasSolution && v < solutionBools.size) {
                if (solutionBools[v]) 1L else 0L
            } else {
                null
            }
            // A managing schedule pins the source (stable → dive on the best solution/target, focused →
            // plain saved); otherwise the autonomous rephase rotation chooses. Each source falls back to
            // the saved phase when its array isn't populated, so a disabled feature is a no-op.
            val source = when (managedMode) {
                PhaseMode.STABLE -> if (solutionFirst != null) RephaseMode.SOLUTION else RephaseMode.TARGET
                PhaseMode.FOCUSED -> RephaseMode.SAVED
                PhaseMode.UNMANAGED -> rephaseMode
            }
            val preferred: Long? = when (source) {
                RephaseMode.SOLUTION -> solutionFirst ?: savedFirst

                RephaseMode.TARGET -> if (boolTarget != null && boolTargetSet != null && boolTargetSet[v]) {
                    if (boolTarget[v]) 1L else 0L
                } else {
                    savedFirst
                }

                RephaseMode.SAVED -> savedFirst

                RephaseMode.TRUE -> 1L

                RephaseMode.FALSE -> 0L

                RephaseMode.RANDOM -> if ((rng ?: Random.Default).nextBoolean()) 1L else 0L
            }
            if (preferred != null) sequenceOf(preferred) + values.filter { it != preferred } else values
        }

        is VarRef.IntVar -> {
            val vi = varRef.varId
            val sols = solutionInts
            // Stable / SOLUTION mode dives on the incumbent's value; otherwise the saved value. An out-of-domain
            // value is harmless: splitIntAlternatives clamps the split into the domain.
            val useSolution = managedMode == PhaseMode.STABLE ||
                (managedMode == PhaseMode.UNMANAGED && rephaseMode == RephaseMode.SOLUTION)
            val preferred: Long? = when {
                useSolution && sols != null && hasSolution && vi < sols.size -> sols[vi]
                intPhase != null && intPhaseSet != null && intPhaseSet[vi] -> intPhase[vi]
                else -> null
            }
            if (preferred != null) sequenceOf(preferred) + values.filter { it != preferred } else values
        }
    }

    fun onSolution(sample: Sample) {
        if (solutionBools == null && solutionInts == null) return
        solutionBools?.let { sample.copyBoolsInto(it, minOf(it.size, sample.numBoolVars)) }
        solutionInts?.let { sample.copyIntsInto(it, minOf(it.size, sample.numIntVars)) }
        hasSolution = true
    }

    fun capture(varRef: VarRef, session: PropagationSession) = capture(
        when (varRef) {
            is VarRef.Bool -> varRef.varId
            is VarRef.IntVar -> session.problem.numBoolVars + varRef.varId
        },
        session,
    )

    fun capture(variable: Int, session: PropagationSession) {
        if (variable < session.problem.numBoolVars) {
            if (boolPhase == null || boolPhaseSet == null) return
            val value = session.boolValue(variable) ?: return
            boolPhase[variable] = value
            boolPhaseSet[variable] = true
        } else {
            if (intPhase == null || intPhaseSet == null) return
            val id = variable - session.problem.numBoolVars
            val domain = session.intDomain(id)
            if (!domain.isFixed) return
            intPhase[id] = domain.min
            intPhaseSet[id] = true
        }
    }

    // Unpinned variables keep their previous target entry; a deeper later descent fills them in.
    fun captureTargetIfDeeper(session: PropagationSession, trailSize: Int) {
        if (boolTarget == null || boolTargetSet == null || trailSize <= bestTrailSize) return
        bestTrailSize = trailSize
        for (v in boolTarget.indices) {
            val value = session.boolValue(v) ?: continue
            boolTarget[v] = value
            boolTargetSet[v] = true
        }
    }

    // A mode change takes effect on the next fresh descent: rephasing only reorders which polarity a new
    // decision tries first, so no restart is needed.
    fun onConflictTick() {
        if (boolTarget == null || managedMode != PhaseMode.UNMANAGED) return
        conflictsSinceRephase++
        if (conflictsSinceRephase >= params.rephaseInterval) {
            conflictsSinceRephase = 0
            rephaseMode = rephaseMode.next()
        }
    }
}
