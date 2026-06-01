package com.eignex.klause.solver

/**
 * Optional sidecar on [Problem] that records the original real-valued view of float
 * variables when the schema or front-end has bucketed them to integer vars in the
 * factor system. Backends that can solve over reals natively (Z3) read this and emit
 * native real arithmetic; all other backends ignore it and solve the bucketed int
 * factors as usual.
 *
 * The contract is:
 *  - `intervals[fv]` is the real domain of float var `fv` in `[0, intervals.size)`.
 *  - `intVarByFloatVar[fv]` is the [Problem.intDomains] id of the bucketed integer
 *    that represents `fv` in the factor list.
 *  - `bucketCounts[fv]` is the bucket count used when discretising `fv`. Together
 *    with `intervals[fv]` this lets a native backend reverse the discretisation —
 *    `real(fv) = lo + bucket * (hi - lo) / (buckets - 1)`.
 *  - `constraints` lists the original real-valued constraints. These mirror a subset
 *    of [Problem.factors] (the bucketed scaled-int versions); native backends consume
 *    `constraints` instead.
 *
 * Keeping floats here means [Problem.factors] stays pure int+bool — every existing
 * backend continues to work unchanged.
 */
data class FloatMetadata(
    /** Real domain per float variable. */
    val intervals: Array<FloatInterval>,
    /** Bucket count per float variable. */
    val bucketCounts: IntArray,
    /** Backing integer var id per float variable. */
    val intVarByFloatVar: IntArray,
    /** Original real-valued linear constraints. */
    val constraints: List<RealLinearConstraint>,
) {
    init {
        require(intervals.size == bucketCounts.size && bucketCounts.size == intVarByFloatVar.size) {
            "FloatMetadata parallel arrays size mismatch: intervals=${intervals.size}, " +
                "bucketCounts=${bucketCounts.size}, intVarByFloatVar=${intVarByFloatVar.size}"
        }
    }

    /** Number of float variables. */
    val numFloatVars: Int get() = intervals.size

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FloatMetadata) return false
        return intervals.contentEquals(other.intervals) &&
            bucketCounts.contentEquals(other.bucketCounts) &&
            intVarByFloatVar.contentEquals(other.intVarByFloatVar) &&
            constraints == other.constraints
    }

    override fun hashCode(): Int {
        var h = intervals.contentHashCode()
        h = 31 * h + bucketCounts.contentHashCode()
        h = 31 * h + intVarByFloatVar.contentHashCode()
        h = 31 * h + constraints.hashCode()
        return h
    }
}

/**
 * Sidecar record of one real-valued linear constraint: `Σ coeffs[k] · floatVarIds[k] ⟨op⟩ bound`.
 * Stored in [FloatMetadata.constraints]; not part of klause's [com.eignex.klause.solver.Factor]
 * hierarchy (the core solver stays int+bool only). Schema-layer authors build the analogous
 * [com.eignex.klause.ast.FloatLinearConstraint] AST node which the compiler converts to one
 * of these at lowering time.
 */
data class RealLinearConstraint(
    /** Real coefficients, parallel to [floatVarIds]. */
    val coeffs: DoubleArray,
    /** Float variable ids, parallel to [coeffs]. */
    val floatVarIds: IntArray,
    /** Comparison relating the weighted sum to [bound]. */
    val op: com.eignex.klause.solver.factor.LinearOp,
    /** Right-hand-side bound. */
    val bound: Double,
) {
    init {
        require(coeffs.size == floatVarIds.size) {
            "coeffs/floatVarIds length mismatch"
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RealLinearConstraint) return false
        return coeffs.contentEquals(other.coeffs) &&
            floatVarIds.contentEquals(other.floatVarIds) &&
            op == other.op && bound == other.bound
    }

    override fun hashCode(): Int {
        var h = coeffs.contentHashCode()
        h = 31 * h + floatVarIds.contentHashCode()
        h = 31 * h + op.hashCode()
        h = 31 * h + bound.hashCode()
        return h
    }
}
