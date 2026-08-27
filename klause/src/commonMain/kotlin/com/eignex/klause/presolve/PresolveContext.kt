package com.eignex.klause.presolve

import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.Cancellation

/**
 * Information a presolve pass needs to stay sound and to reuse round-engine state.
 */
data class PresolveContext(
    /** Whether the caller needs every solution. */
    val solutionSetSensitive: Boolean = false,
    /** Minimize-sense integer objective coefficients. */
    val objectiveIntCoeffs: Map<Int, Long> = emptyMap(),
    /** Minimize-sense Boolean objective weights. */
    val objectiveBoolCoeffs: Map<Int, Long> = emptyMap(),
    /** Whether the source model already supplies symmetry breaking. */
    val modelBreaksSymmetry: Boolean = false,
    /** Cooperative cancellation polled by expensive passes. */
    val cancellation: Cancellation = Cancellation.Never,
    /** Root-bake probing policy for transformed problems. */
    val bakeConfig: BakeConfig = BakeConfig.NONE,
    /** Incremental integer-variable occurrence index. */
    val sharedIntOcc: SharedIntOccurrence? = null,
    /** Whether affine elimination should cap wide folds. */
    val affineUnderdetermined: Boolean = false,
    /** Configured affine pivot order. */
    val affinePivotOrder: AffinePivotOrder = AffinePivotOrder.MARKOWITZ,
    /** Remaining presolve time budget. */
    val presolveBudget: PresolveBudget? = null,
    /** Incremental subsumption state. */
    val subsumeIncremental: SubsumeState? = null,
    /** Integer variables changed since affine elimination last ran. */
    val affineTouchedVars: IntArray? = null,
    /** Integer variables changed since duplicate-column merging last ran. */
    val dupColumnsTouchedVars: IntArray? = null,
) {
    /** Integer variables read by the objective. */
    val objectiveIntVars: Set<Int> get() = objectiveIntCoeffs.keys

    /** Boolean variables read by the objective. */
    val objectiveBoolVars: Set<Int> get() = objectiveBoolCoeffs.keys

    /** This context with [cancellation] set. */
    fun withCancellation(cancellation: Cancellation): PresolveContext = copy(cancellation = cancellation)

    /** This context with [bakeConfig] set. */
    fun withBakeConfig(bakeConfig: BakeConfig): PresolveContext = copy(bakeConfig = bakeConfig)

    /** This context with the affine wide-fold cap configured. */
    fun withAffineUnderdetermined(underdetermined: Boolean): PresolveContext =
        copy(affineUnderdetermined = underdetermined)

    /** This context with [pivotOrder] set. */
    fun withAffinePivotOrder(pivotOrder: AffinePivotOrder): PresolveContext = copy(affinePivotOrder = pivotOrder)

    /** This context with [budget] set. */
    fun withPresolveBudget(budget: PresolveBudget?): PresolveContext = copy(presolveBudget = budget)

    /** This context with [sharedIntOcc] set. */
    fun withSharedIntOcc(sharedIntOcc: SharedIntOccurrence?): PresolveContext = copy(sharedIntOcc = sharedIntOcc)

    /** This context with [subsumeIncremental] set. */
    fun withSubsumeIncremental(subsumeIncremental: SubsumeState?): PresolveContext =
        copy(subsumeIncremental = subsumeIncremental)

    /** This context with [affineTouchedVars] set. */
    fun withAffineTouchedVars(affineTouchedVars: IntArray?): PresolveContext =
        copy(affineTouchedVars = affineTouchedVars)

    /** This context with [dupColumnsTouchedVars] set. */
    fun withDupColumnsTouchedVars(dupColumnsTouchedVars: IntArray?): PresolveContext =
        copy(dupColumnsTouchedVars = dupColumnsTouchedVars)

    /** Common presolve contexts. */
    companion object {
        /** Context for pure feasibility. */
        val EMPTY = PresolveContext()

        /** Build a context which protects the objective's nonzero coefficients. */
        fun of(
            objective: LinearObjective?,
            solutionSetSensitive: Boolean = false,
            modelBreaksSymmetry: Boolean = false,
        ): PresolveContext {
            if (objective == null) {
                return PresolveContext(
                    solutionSetSensitive = solutionSetSensitive,
                    modelBreaksSymmetry = modelBreaksSymmetry,
                )
            }
            val intCoeffs = HashMap<Int, Long>()
            for (i in objective.intCoefficients.indices) {
                val c = objective.intCoefficients[i]
                if (c != 0L) intCoeffs[i] = c
            }
            val boolCoeffs = HashMap<Int, Long>()
            for (b in objective.boolWeights.indices) {
                val w = objective.boolWeights[b]
                if (w != 0L) boolCoeffs[b] = w
            }
            return PresolveContext(solutionSetSensitive, intCoeffs, boolCoeffs, modelBreaksSymmetry)
        }
    }
}
