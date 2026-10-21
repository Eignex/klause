package com.eignex.klause.backtrack

import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.search.VarRef
import kotlin.random.Random

/**
 * Phase-saving and target-phasing state for one backtrack run: the saved polarity of each
 * Boolean/integer variable, the target phase (the deepest conflict-free Boolean prefix), and the
 * rephasing schedule that rotates which polarity source a fresh decision tries first. Owned by
 * [BacktrackBrancher], so both the satisfaction path ([CpSatisfactionTraversal]) and the branch-and-bound
 * engine ([ResumableMinimize]) use it; all state persists across restarts.
 *
 * Phase saving follows Pipatsrisawat and Darwiche, "A Lightweight Component Caching Scheme for
 * Satisfiability Solvers", SAT 2007 (https://doi.org/10.1007/978-3-540-72788-0_28); target phases follow
 * Biere and Fleury, "Chasing Target Phases", POS 2020 (https://fmv.jku.at/papers/BiereFleury-POS20.pdf).
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
    private val solutionBools: BooleanArray? = if (params.solutionPhasing) BooleanArray(numBoolVars) else null
    private val solutionInts: LongArray? = if (params.solutionPhasing) LongArray(numIntVars) else null
    private var hasSolution = false

    private var bestTrailSize = -1
    private var rephaseMode = RephaseMode.TARGET
    private var conflictsSinceRephase = 0L

    // When a restart schedule manages the phase regime ([PhaseMode.STABLE] / [PhaseMode.FOCUSED]) the
    // polarity source is pinned to it and the autonomous rephase rotation is suspended; [UNMANAGED]
    // (the default) leaves the rotation in charge.
    private var managedMode = PhaseMode.UNMANAGED
    var candidateUses: Long = 0L
        private set

    /** Set the externally-managed phase regime (see [RestartSchedule.phaseMode]). */
    fun setManagedMode(mode: PhaseMode) {
        managedMode = mode
    }

    fun acceptsBooleanCandidate(variable: Int): Boolean {
        if (!boolPhaseTracking || boolPhaseSet?.getOrNull(variable) != false) return false
        val solution = solutionBools != null && hasSolution && variable < solutionBools.size
        return when (booleanSource(solution)) {
            RephaseMode.TARGET -> boolTargetSet?.getOrNull(variable) != true
            RephaseMode.SOLUTION -> !solution
            RephaseMode.SAVED -> true
            else -> false
        }
    }

    fun acceptsIntegerCandidate(variable: Int): Boolean {
        if (intPhaseSet?.getOrNull(variable) != false) return false
        return !(useIntegerSolution && solutionInts != null && hasSolution && variable < solutionInts.size)
    }

    private val useIntegerSolution: Boolean get() = managedMode == PhaseMode.STABLE ||
        (managedMode == PhaseMode.UNMANAGED && rephaseMode == RephaseMode.SOLUTION)

    private fun booleanSource(solution: Boolean): RephaseMode = when (managedMode) {
        PhaseMode.STABLE -> if (solution) RephaseMode.SOLUTION else RephaseMode.TARGET
        PhaseMode.FOCUSED -> RephaseMode.SAVED
        PhaseMode.UNMANAGED -> rephaseMode
    }

    /**
     * If a value is cached for [varRef], prepend it to the heuristic's [values] order (dropping it
     * from the rest so it isn't tried twice); otherwise pass [values] through unchanged. For a Boolean
     * the cached value is the target phase, the saved phase, or a rephase-mode fixed/random polarity;
     * for an integer it is the saved value. [rng] supplies the `RANDOM` rephase mode's coin flip.
     */
    fun applyPhase(
        varRef: VarRef,
        values: Sequence<Long>,
        rng: Random? = null,
        candidate: Boolean? = null,
        integerCandidate: Long? = null,
    ): Sequence<Long> = when (varRef) {
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
            fun savedOrCandidate(): Long? = savedFirst ?: if (boolPhaseTracking && candidate != null) {
                candidateUses++
                if (candidate) 1L else 0L
            } else null
            // A managing schedule pins the source (stable → dive on the best solution/target, focused →
            // plain saved); otherwise the autonomous rephase rotation chooses. Each source falls back to
            // the saved phase when its array isn't populated, so a disabled feature is a no-op.
            val source = booleanSource(solutionFirst != null)
            val preferred: Long? = when (source) {
                RephaseMode.SOLUTION -> solutionFirst ?: savedOrCandidate()

                RephaseMode.TARGET -> if (boolTarget != null && boolTargetSet != null && boolTargetSet[v]) {
                    if (boolTarget[v]) 1L else 0L
                } else {
                    savedOrCandidate()
                }

                RephaseMode.SAVED -> savedOrCandidate()

                RephaseMode.TRUE -> 1L

                RephaseMode.FALSE -> 0L

                RephaseMode.RANDOM -> if ((rng ?: Random.Default).nextBoolean()) 1L else 0L
            }
            if (preferred != null) sequenceOf(preferred) + values.filter { it != preferred } else values
        }

        is VarRef.IntVar -> {
            val vi = varRef.varId
            val sols = solutionInts
            // Stable / SOLUTION mode dives on the incumbent's value (objective-good); otherwise the plain
            // saved value. Out-of-domain values are harmless — [splitIntAlternatives] clamps the split into the domain.
            val preferred: Long? = when {
                useIntegerSolution && sols != null && hasSolution && vi < sols.size -> sols[vi]
                intPhase != null && intPhaseSet != null && intPhaseSet[vi] -> intPhase[vi]
                intPhase != null && integerCandidate != null -> {
                    candidateUses++
                    integerCandidate
                }
                else -> null
            }
            if (preferred != null) sequenceOf(preferred) + values.filter { it != preferred } else values
        }
    }

    /** Seed the solution phase source with a new feasible incumbent (see [BacktrackParams.solutionPhasing]). */
    fun onSolution(sample: Sample) {
        if (solutionBools == null && solutionInts == null) return
        solutionBools?.let { sample.copyBoolsInto(it, minOf(it.size, sample.numBoolVars)) }
        solutionInts?.let { sample.copyIntsInto(it, minOf(it.size, sample.numIntVars)) }
        hasSolution = true
    }

    /** Record [varRef]'s currently-pinned value for phase-saving. Called after every successful pin. */
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
        if (boolTarget == null || managedMode != PhaseMode.UNMANAGED) return
        conflictsSinceRephase++
        if (conflictsSinceRephase >= params.rephaseInterval) {
            conflictsSinceRephase = 0
            rephaseMode = rephaseMode.next()
        }
    }
}
