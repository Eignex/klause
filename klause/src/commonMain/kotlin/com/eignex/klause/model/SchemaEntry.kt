package com.eignex.klause.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One row of a klause schema. Variables and named constraints are siblings under
 * [com.eignex.skema.SchemaDef]'s entries map; the map key is the entry's name, so
 * spec types don't carry a redundant `name` field.
 */
@Serializable
sealed interface SchemaEntry

/** A schema entry that declares a decision variable (as opposed to a named constraint). */
@Serializable
sealed interface VarSpec : SchemaEntry

/** A single Boolean decision variable. */
@Serializable
@SerialName("bool")
data object BoolSpec : VarSpec

/**
 * Presence marker for an optional variable: a Boolean that gates the value variable named
 * [valueName]. Compiles to an ordinary Boolean var, but its dedicated type lets the optional
 * machinery (notably absent-value pinning) recognise the (presence, value) pairing
 * *explicitly* — by type and the carried [valueName] — rather than by a fragile name
 * convention that could misfire on an unrelated bool that merely shares a suffix.
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
) : VarSpec

/** An integer variable ranging over the inclusive interval `[min, max]`. */
@Serializable
@SerialName("int")
data class IntSpec(
    /** Inclusive lower bound of the domain. */
    val min: Int,
    /** Inclusive upper bound of the domain. */
    val max: Int,
) : VarSpec

/**
 * Float variable bucketed to [buckets] uniformly-spaced values across `[min, max]`. The
 * solver represents it as an int domain `[0, buckets-1]`; the compiler stores a decoder so
 * solutions can be read back as Double.
 */
@Serializable
@SerialName("float")
data class FloatSpec(
    /** Inclusive lower bound of the real interval. */
    val min: Double,
    /** Inclusive upper bound of the real interval. */
    val max: Double,
    /** Number of uniformly-spaced values the interval is discretised into (≥ 2). */
    val buckets: Int,
) : VarSpec {
    init {
        require(buckets >= 2) { "FloatSpec needs at least 2 buckets" }
    }

    /** Real-value step between adjacent buckets: `(max - min) / (buckets - 1)`. The single
     *  source of truth for the bucket-to-real affine map — decode and the float objectives
     *  all route through it, and downstream consumers should too rather than re-deriving it. */
    val scale: Double get() = (max - min) / (buckets - 1)

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
) : SchemaEntry
