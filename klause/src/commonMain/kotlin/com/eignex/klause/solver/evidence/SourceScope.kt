package com.eignex.klause.solver.evidence

import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.simplex.exact.BigFraction

/** Original source coordinate, independent of engine column numbering. */
data class SourceCoordinate(val kind: Kind, val id: Int) {
    /** Source declaration namespace. */
    enum class Kind { Boolean, Integer, Real }

    init { require(id >= 0) }
}

/** Declared semantics and revision identity of the original source. */
data class SourceRevision(val identity: String, val semantics: String) {
    init { require(identity.isNotBlank() && semantics.isNotBlank()) }
}

/** Exact invocation or branch restrictions in original coordinates. */
class SourceCondition(
    bools: Map<Int, Boolean> = emptyMap(),
    lower: Map<SourceCoordinate, BigFraction> = emptyMap(),
    upper: Map<SourceCoordinate, BigFraction> = emptyMap(),
) {
    private val capturedBools = bools.toMap()
    private val capturedLower = lower.toMap()
    private val capturedUpper = upper.toMap()
    /** Boolean pins. */
    val bools: Map<Int, Boolean> get() = capturedBools.toMap()
    /** Inclusive numeric lower bounds, including exact pins. */
    val lower: Map<SourceCoordinate, BigFraction> get() = capturedLower.toMap()
    /** Inclusive numeric upper bounds, including exact pins. */
    val upper: Map<SourceCoordinate, BigFraction> get() = capturedUpper.toMap()

    init {
        require(capturedBools.keys.all { it >= 0 })
        require((capturedLower.keys + capturedUpper.keys).none { it.kind == SourceCoordinate.Kind.Boolean })
    }

    internal fun implies(other: SourceCondition): Boolean =
        other.capturedBools.all { (id, value) -> capturedBools[id] == value } &&
            other.capturedLower.all { (id, value) -> capturedLower[id]?.let { it >= value } == true } &&
            other.capturedUpper.all { (id, value) -> capturedUpper[id]?.let { it <= value } == true }

    internal fun pin(id: Int, value: Boolean): SourceCondition =
        SourceCondition(capturedBools + (id to value), capturedLower, capturedUpper)

    internal fun bound(id: SourceCoordinate, value: BigFraction, upper: Boolean): SourceCondition =
        if (upper) SourceCondition(capturedBools, capturedLower, capturedUpper + (id to
            (capturedUpper[id]?.let { minOf(it, value) } ?: value)))
        else SourceCondition(capturedBools, capturedLower + (id to
            (capturedLower[id]?.let { maxOf(it, value) } ?: value)), capturedUpper)

    override fun equals(other: Any?): Boolean = other is SourceCondition &&
        capturedBools == other.capturedBools && capturedLower == other.capturedLower && capturedUpper == other.capturedUpper

    override fun hashCode(): Int = 31 * (31 * capturedBools.hashCode() + capturedLower.hashCode()) + capturedUpper.hashCode()
}


/** Source objective identity and invocation direction. */
data class SourceObjective(val identity: String, val sense: ObjectiveSense) {
    init { require(identity.isNotBlank()) }
}

/** Complete scope required for every retained source claim. */
data class SourceScope(
    val revision: SourceRevision,
    val condition: SourceCondition = SourceCondition(),
    val objective: SourceObjective? = null,
)
