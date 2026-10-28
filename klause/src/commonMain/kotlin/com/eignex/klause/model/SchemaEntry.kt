package com.eignex.klause.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One row of a klause schema. Variables and named constraints are siblings under
 * [com.eignex.skema.SchemaDef]'s entries map; the map key is the entry's name, so
 * spec types don't carry a redundant `name` field.
 */
@Serializable(with = SchemaEntrySerializer::class)
sealed interface SchemaEntry

/** A schema entry that declares a decision variable (as opposed to a named constraint). */
@Serializable(with = VarSpecSerializer::class)
sealed interface VarSpec : SchemaEntry

/** A single Boolean decision variable. */
@Serializable
@SerialName("bool")
data object BoolSpec : VarSpec

/**
 * Presence marker for an optional variable: a Boolean that gates the value variable named
 * [valueName]. Compiles to an ordinary Boolean var, but its dedicated type lets the optional
 * machinery (notably absent-value pinning) recognise the (presence, value) pairing by type
 * and the carried [valueName], never by name.
 */
@Serializable
@SerialName("presence")
data class PresenceSpec(val valueName: String) : VarSpec

/** A categorical variable taking exactly one of the named [labels]. */
@Serializable
@SerialName("nominal")
data class NominalSpec(
    /** The mutually-exclusive category labels this variable can take. */
    val labels: List<String>,
) : VarSpec {
    init {
        require(labels.isNotEmpty()) { "NominalSpec needs at least one label" }
    }
}

/** An integer variable ranging over the inclusive interval `[min, max]`. */
@Serializable
@SerialName("int")
data class IntSpec(
    /** Inclusive lower bound of the domain. */
    val min: Int,
    /** Inclusive upper bound of the domain. */
    val max: Int,
) : VarSpec {
    init {
        require(min <= max) { "IntSpec needs min <= max, got [$min, $max]" }
    }
}

/**
 * An integer variable over a 64-bit range, either side of which may be open, optionally restricted to
 * the sparse value set [values]. An absent [lower] or [upper] leaves that side unbounded; [values]
 * lists disjoint ascending `[lo, hi]` runs inside a closed range.
 */
@Serializable
@SerialName("intDomain")
data class IntDomainSpec(
    /** Inclusive lower bound, or `null` when open below. */
    val lower: Long? = null,
    /** Inclusive upper bound, or `null` when open above. */
    val upper: Long? = null,
    /** Declared value set as `[lo, hi]` runs, or `null` for the whole range. */
    val values: List<List<Long>>? = null,
) : VarSpec {
    init {
        require(lower == null || upper == null || lower <= upper) { "IntDomainSpec needs lower <= upper" }
        if (values != null) {
            require(lower != null && upper != null) { "IntDomainSpec values need a closed range" }
            require(values.isNotEmpty()) { "IntDomainSpec values need at least one run" }
            values.forEachIndexed { i, run ->
                require(run.size == 2 && run[0] <= run[1]) { "IntDomainSpec run $i must be [lo, hi]" }
                require(i == 0 || (run[0] > values[i - 1][1] && run[0] - 1 > values[i - 1][1])) {
                    "IntDomainSpec runs must ascend with gaps"
                }
            }
            require(values.first()[0] >= lower && values.last()[1] <= upper) {
                "IntDomainSpec values must lie in [lower, upper]"
            }
        }
    }
}

/**
 * Float declaration with an optional explicit grid of [buckets] values across `[min, max]`.
 * Components requiring a grid use an integer index and a retained decoder. Other supported
 * components retain continuous real coordinates. An infinite side is open; a grid over it uses the
 * effective semantics' unranged window.
 */
@Serializable
@SerialName("float")
data class FloatSpec(
    /** Inclusive lower bound of the real interval, negative infinity when open. */
    val min: Double = Double.NEGATIVE_INFINITY,
    /** Inclusive upper bound of the real interval, positive infinity when open. */
    val max: Double = Double.POSITIVE_INFINITY,
    /** Explicit grid size (at least two), or null to use the compiler's effective source policy. */
    val buckets: Int? = null,
) : VarSpec {
    init {
        require(buckets == null || buckets >= 2) { "FloatSpec needs at least 2 buckets" }
    }

    /** Real-value step `(max - min) / (buckets - 1)` for a grid resolved by compilation. */
    val scale: Double
        get() {
            val count = requireNotNull(buckets) { "float grid must be resolved by compilation" }
            return (max - min) / (count - 1)
        }

    /** Decode bucket index [bucket] to its real value: `min + scale * bucket`. */
    fun realValue(bucket: Int): Double = min + scale * bucket
}

/** Set variable over an integer universe. The universe is fixed at schema-construction
 *  time and need not be contiguous, though contiguous ranges are the common case. */
@Serializable
@SerialName("set")
data class SetSpec(
    /** The (fixed, not necessarily contiguous) integer universe the set draws from. */
    val universe: List<Int>,
) : VarSpec {
    init {
        require(universe.isNotEmpty()) { "SetSpec needs a non-empty universe" }
    }
}

/** Set variable over a nominal universe of [labels]. Internally lowers to an indicator
 *  bool per label, mirroring the encoding of [SetSpec] but typed against strings on the
 *  decoder side. */
@Serializable
@SerialName("multiple")
data class MultipleSpec(
    /** The nominal label universe the set draws from. */
    val labels: List<String>,
) : VarSpec {
    init {
        require(labels.isNotEmpty()) { "MultipleSpec needs at least one label" }
    }
}

/** A named constraint schema entry wrapping a Boolean expression. */
@Serializable
@SerialName("constraint")
data class NamedConstraint(
    /** The constraint expression. */
    val expr: BoolExpr,
    /** Whether the model requires this constraint or declares it implied. */
    val role: ConstraintRole = ConstraintRole.REQUIRED,
) : SchemaEntry

/** How a [NamedConstraint] relates to the model's solutions. */
enum class ConstraintRole {
    /** Part of the model. */
    REQUIRED,

    /** Implied by the other constraints; still posted, but local search weighs it lower. */
    IMPLIED,

    /** Implied and breaking symmetry; presolve skips its own symmetry breaking for the model. */
    SYMMETRY_BREAKING,
}
