package com.eignex.klause.ast

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface VarSpec {
    val name: String
}

@Serializable
@SerialName("bool")
data class BoolSpec(override val name: String) : VarSpec

@Serializable
@SerialName("nominal")
data class NominalSpec(override val name: String, val labels: List<String>) : VarSpec

@Serializable
@SerialName("int")
data class IntSpec(override val name: String, val min: Int, val max: Int) : VarSpec

/**
 * Float variable bucketed to [buckets] uniformly-spaced values across `[min, max]`. The
 * solver represents it as an int domain `[0, buckets-1]`; the compiler stores a decoder so
 * solutions can be read back as Double.
 */
@Serializable
@SerialName("float")
data class FloatSpec(
    override val name: String,
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

@Serializable
@SerialName("alldiff")
data class AllDifferent(val terms: List<IntExpr>) : BoolExpr {
    init { require(terms.size >= 2) { "AllDifferent needs at least two terms" } }
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
data class NamedConstraint(
    val name: String,
    val expr: BoolExpr,
    val isHard: Boolean = true,
    val weight: Double = 1.0,
)

@Serializable
data class SchemaDef(
    val vars: List<VarSpec>,
    val constraints: List<NamedConstraint>,
)
