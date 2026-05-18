package com.eignex.klause.ast

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One row of a klause schema. Variables and named constraints are siblings under
 * [com.eignex.skema.SchemaDef]'s entries map; the map key is the entry's name, so
 * spec types don't carry a redundant `name` field.
 */
@Serializable
sealed interface SchemaEntry

@Serializable
sealed interface VarSpec : SchemaEntry

@Serializable
@SerialName("bool")
data object BoolSpec : VarSpec

@Serializable
@SerialName("nominal")
data class NominalSpec(val labels: List<String>) : VarSpec

@Serializable
@SerialName("int")
data class IntSpec(val min: Int, val max: Int) : VarSpec

/**
 * Float variable bucketed to [buckets] uniformly-spaced values across `[min, max]`. The
 * solver represents it as an int domain `[0, buckets-1]`; the compiler stores a decoder so
 * solutions can be read back as Double.
 */
@Serializable
@SerialName("float")
data class FloatSpec(
    val min: Double,
    val max: Double,
    val buckets: Int,
) : VarSpec {
    init { require(buckets >= 2) { "FloatSpec needs at least 2 buckets" } }
}

/** Anything that can be coerced into a [BoolExpr] inside the constraint DSL. */
interface BoolTerm {
    fun toExpr(): BoolExpr
}

@Serializable
sealed interface BoolExpr : BoolTerm {
    override fun toExpr(): BoolExpr = this
}

@Serializable
@SerialName("ref")
data class BoolRef(val name: String, val negated: Boolean = false) : BoolExpr

@Serializable
@SerialName("nomeq")
data class NominalEq(val name: String, val label: String) : BoolExpr

@Serializable
@SerialName("not")
data class Not(val child: BoolExpr) : BoolExpr

@Serializable
@SerialName("and")
data class And(val children: List<BoolExpr>) : BoolExpr {
    init { require(children.isNotEmpty()) { "And must have at least one child" } }
}

@Serializable
@SerialName("or")
data class Or(val children: List<BoolExpr>) : BoolExpr {
    init { require(children.isNotEmpty()) { "Or must have at least one child" } }
}

@Serializable
@SerialName("imp")
data class Implies(val left: BoolExpr, val right: BoolExpr) : BoolExpr

@Serializable
@SerialName("iff")
data class Iff(val left: BoolExpr, val right: BoolExpr) : BoolExpr

@Serializable
@SerialName("atmost")
data class AtMost(val children: List<BoolExpr>, val k: Int) : BoolExpr

@Serializable
@SerialName("atleast")
data class AtLeast(val children: List<BoolExpr>, val k: Int) : BoolExpr

@Serializable
@SerialName("card")
data class CardinalityExpr(val children: List<BoolExpr>, val min: Int, val max: Int) : BoolExpr

/** Anything that can be coerced into an [IntExpr] inside the constraint DSL. */
interface IntTerm {
    fun toIntExpr(): IntExpr
}

@Serializable
sealed interface IntExpr : IntTerm {
    override fun toIntExpr(): IntExpr = this
}

@Serializable
@SerialName("intref")
data class IntRef(val name: String) : IntExpr

@Serializable
@SerialName("intlit")
data class IntLit(val value: Int) : IntExpr

/** `coeff * child`. */
@Serializable
@SerialName("intscale")
data class IntScale(val coeff: Int, val child: IntExpr) : IntExpr

/** Sum of children. */
@Serializable
@SerialName("intsum")
data class IntSum(val children: List<IntExpr>) : IntExpr {
    init { require(children.isNotEmpty()) { "IntSum must have at least one child" } }
}

@Serializable
@SerialName("intmin")
data class IntMin(val children: List<IntExpr>) : IntExpr {
    init { require(children.isNotEmpty()) { "IntMin must have at least one child" } }
}

@Serializable
@SerialName("intmax")
data class IntMax(val children: List<IntExpr>) : IntExpr {
    init { require(children.isNotEmpty()) { "IntMax must have at least one child" } }
}

@Serializable
@SerialName("intabs")
data class IntAbs(val child: IntExpr) : IntExpr

@Serializable
@SerialName("intite")
data class IntIfThenElse(val cond: BoolExpr, val thenE: IntExpr, val elseE: IntExpr) : IntExpr

@Serializable
@SerialName("intelem")
data class IntElement(val index: IntExpr, val items: List<IntExpr>) : IntExpr {
    init { require(items.isNotEmpty()) { "IntElement must have at least one item" } }
}

@Serializable
@SerialName("intmul")
data class IntMul(val left: IntExpr, val right: IntExpr) : IntExpr

@Serializable
@SerialName("intdiv")
data class IntDiv(val num: IntExpr, val den: IntExpr) : IntExpr

@Serializable
@SerialName("intmod")
data class IntMod(val num: IntExpr, val den: IntExpr) : IntExpr

@Serializable
enum class IntCmpOp { LE, LT, GE, GT, EQ, NE }

@Serializable
@SerialName("intcmp")
data class IntCompare(val left: IntExpr, val op: IntCmpOp, val right: IntExpr) : BoolExpr

/**
 * Schema-layer view of a real-valued linear comparison: `Σ coeffs[k] · varNames[k] ⟨op⟩ bound`.
 * Built by [com.eignex.klause.schema.FloatExpr] / [com.eignex.klause.schema.FloatHandle]
 * comparison operators. The compiler does two things with it:
 *
 *  1. Bucket each float variable to an int variable in the factor system and emit a
 *     scaled-integer [com.eignex.klause.solver.factor.Linear] factor — what every
 *     existing backend solves over.
 *  2. Record the original real-valued form on [com.eignex.klause.solver.FloatMetadata]
 *     so a native-real backend (Z3) can solve over reals directly.
 *
 * The AST node lives in [BoolExpr] so the constraint pipeline can carry it through
 * `And` / `Or` / `Implies` etc. before the compiler intercepts it at lowering time.
 */
@Serializable
@SerialName("floatlin")
data class FloatLinearConstraint(
    val coeffs: DoubleArray,
    val varNames: List<String>,
    val op: IntCmpOp,
    val bound: Double,
) : BoolExpr {
    init {
        require(coeffs.size == varNames.size) {
            "coeffs/varNames length mismatch: ${coeffs.size} vs ${varNames.size}"
        }
    }
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FloatLinearConstraint) return false
        return coeffs.contentEquals(other.coeffs) &&
            varNames == other.varNames && op == other.op && bound == other.bound
    }
    override fun hashCode(): Int {
        var h = coeffs.contentHashCode()
        h = 31 * h + varNames.hashCode()
        h = 31 * h + op.hashCode()
        h = 31 * h + bound.hashCode()
        return h
    }
}

@Serializable
@SerialName("alldiff")
data class AllDifferent(val terms: List<IntExpr>) : BoolExpr {
    init { require(terms.size >= 2) { "AllDifferent needs at least two terms" } }
}

/**
 * Hamiltonian-cycle constraint over a successor array. `succ[i]` is the index of node `i`'s
 * successor; the assignment must form a single cycle visiting every node. [valueOffset] is
 * the integer that represents node 0 — `0` for klause's native 0-indexed form, `1` for
 * FlatZinc-style 1-indexed inputs.
 */
@Serializable
@SerialName("circuit")
data class CircuitExpr(val succ: List<IntExpr>, val valueOffset: Int = 0) : BoolExpr {
    init { require(succ.size >= 2) { "Circuit needs at least two nodes" } }
}

/**
 * Subcircuit — like [CircuitExpr] but `succ[i] = i + valueOffset` marks node `i` as
 * excluded; the included nodes (non-self-loops) must form a single cycle.
 */
@Serializable
@SerialName("subcircuit")
data class SubcircuitExpr(val succ: List<IntExpr>, val valueOffset: Int = 0) : BoolExpr {
    init { require(succ.isNotEmpty()) { "Subcircuit needs at least one node" } }
}

/**
 * Cumulative scheduling: at every integer time point the sum of resource use of tasks
 * running at that point stays under [capacity]. [starts] are integer-variable expressions;
 * [durations] and [resources] are constants (variable-duration / variable-resource
 * cumulative is not supported by the current klause factor).
 */
@Serializable
@SerialName("cumulative")
data class CumulativeExpr(
    val starts: List<IntExpr>,
    val durations: List<Int>,
    val resources: List<Int>,
    val capacity: Int,
) : BoolExpr {
    init {
        require(starts.size == durations.size && starts.size == resources.size) {
            "CumulativeExpr: starts/durations/resources must have the same length"
        }
        require(capacity >= 0) { "CumulativeExpr capacity must be ≥ 0, got $capacity" }
        for (i in durations.indices) {
            require(durations[i] >= 0) { "CumulativeExpr durations[$i] must be ≥ 0" }
            require(resources[i] >= 0) { "CumulativeExpr resources[$i] must be ≥ 0" }
        }
    }
}

/**
 * Disjunctive (one-machine / unary-resource) constraint. Tasks may not overlap.
 * Special case of [CumulativeExpr] with all-1 resources and capacity = 1, but ships its own
 * stronger propagator (time-tabling + detectable precedences + edge-finding).
 */
@Serializable
@SerialName("disjunctive")
data class DisjunctiveExpr(
    val starts: List<IntExpr>,
    val durations: List<Int>,
) : BoolExpr {
    init {
        require(starts.size == durations.size) {
            "DisjunctiveExpr: starts and durations must have the same length"
        }
        for (i in durations.indices) {
            require(durations[i] >= 0) { "DisjunctiveExpr durations[$i] must be ≥ 0" }
        }
    }
}

@Serializable
@SerialName("table")
data class TableConstraint(
    val terms: List<IntExpr>,
    val tuples: List<List<Int>>,
    val negative: Boolean = false,
) : BoolExpr {
    init {
        require(terms.isNotEmpty()) { "TableConstraint must have at least one term" }
        require(tuples.isNotEmpty()) { "TableConstraint must have at least one tuple" }
        require(tuples.all { it.size == terms.size }) {
            "TableConstraint: every tuple must match arity ${terms.size}"
        }
    }
}

@Serializable
enum class PbOp { LE, GE, EQ }

@Serializable
@SerialName("xor")
data class XorExpr(val children: List<BoolExpr>) : BoolExpr {
    init { require(children.isNotEmpty()) { "XorExpr needs at least one child" } }
}

@Serializable
@SerialName("pb")
data class PseudoBooleanExpr(
    val weights: List<Int>,
    val lits: List<BoolExpr>,
    val op: PbOp,
    val bound: Int,
) : BoolExpr {
    init {
        require(weights.size == lits.size) { "PseudoBooleanExpr: weights/lits length mismatch" }
        require(lits.isNotEmpty()) { "PseudoBooleanExpr: need at least one literal" }
    }
}

@Serializable
@SerialName("constraint")
data class NamedConstraint(val expr: BoolExpr) : SchemaEntry
