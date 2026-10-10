package com.eignex.klause.presolve

/** Independent guarantees of a transformation under its objective-protection context. */
data class TransformationGuarantees(
    /** Input and output are equisatisfiable. */
    val satisfiability: Boolean,
    /** Objective values use the stated adjustment and a source optimum remains reachable. */
    val objective: Boolean,
    /** Every reduced witness can be lifted to a source witness. */
    val reconstructability: Boolean,
    /** Reconstruction is one-to-one and onto the source solution set. */
    val bijection: Boolean,
    /** Reconstructed distinct source assignments cover the complete source solution set. */
    val projectedSolutions: Boolean,
) {
    /** Guarantees retained by applying this transformation before [next]. */
    fun then(next: TransformationGuarantees): TransformationGuarantees = TransformationGuarantees(
        satisfiability && next.satisfiability,
        objective && next.objective,
        reconstructability && next.reconstructability,
        bijection && next.bijection,
        projectedSolutions && next.projectedSolutions,
    )

    /** Guarantees for a transformation with no semantic change. */
    companion object {
        /** A transform that retains every guarantee. */
        val IDENTITY = TransformationGuarantees(true, true, true, true, true)
    }
}
