package com.eignex.klause.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Anything that can be coerced into an [IntExpr] inside the constraint DSL. */
interface IntTerm {
    /** Coerce this term into an [IntExpr] node. */
    fun toIntExpr(): IntExpr
}

/** An integer-valued node in the constraint AST. */
@Serializable
sealed interface IntExpr : IntTerm {
    override fun toIntExpr(): IntExpr = this
}

/** Reference to a named integer variable. */
@Serializable
@SerialName("intref")
data class IntRef(
    /** Name of the referenced integer variable. */
    val name: String,
) : IntExpr

/** Integer constant. */
@Serializable
@SerialName("intlit")
data class IntLit(
    /** The literal value. */
    val value: Int,
) : IntExpr

/** `coeff * child`. */
@Serializable
@SerialName("intscale")
data class IntScale(
    /** Multiplier applied to [child]. */
    val coeff: Int,
    /** The scaled sub-expression. */
    val child: IntExpr,
) : IntExpr

/** Sum of children. */
@Serializable
@SerialName("intsum")
data class IntSum(
    /** Summands; must be non-empty. */
    val children: List<IntExpr>,
) : IntExpr {
    init {
        require(children.isNotEmpty()) { "IntSum must have at least one child" }
    }
}

/** Minimum of [children]. */
@Serializable
@SerialName("intmin")
data class IntMin(
    /** Operands; must be non-empty. */
    val children: List<IntExpr>,
) : IntExpr {
    init {
        require(children.isNotEmpty()) { "IntMin must have at least one child" }
    }
}

/** Maximum of [children]. */
@Serializable
@SerialName("intmax")
data class IntMax(
    /** Operands; must be non-empty. */
    val children: List<IntExpr>,
) : IntExpr {
    init {
        require(children.isNotEmpty()) { "IntMax must have at least one child" }
    }
}

/** Absolute value of [child]. */
@Serializable
@SerialName("intabs")
data class IntAbs(
    /** The operand. */
    val child: IntExpr,
) : IntExpr

/** `items[index]` — array element selection. */
@Serializable
@SerialName("intelem")
data class IntElement(
    /** Zero-based index expression. */
    val index: IntExpr,
    /** The indexable items; must be non-empty. */
    val items: List<IntExpr>,
) : IntExpr {
    init {
        require(items.isNotEmpty()) { "IntElement must have at least one item" }
    }
}

/** `left * right`. */
@Serializable
@SerialName("intmul")
data class IntMul(
    /** Left factor. */
    val left: IntExpr,
    /** Right factor. */
    val right: IntExpr,
) : IntExpr

/** Integer (truncating) division `num / den`. */
@Serializable
@SerialName("intdiv")
data class IntDiv(
    /** Dividend. */
    val num: IntExpr,
    /** Divisor. */
    val den: IntExpr,
) : IntExpr

/** Integer remainder `num % den`. */
@Serializable
@SerialName("intmod")
data class IntMod(
    /** Dividend. */
    val num: IntExpr,
    /** Divisor. */
    val den: IntExpr,
) : IntExpr

/** Cardinality `|setExpr|` — returns the count of universe elements indicated true. */
@Serializable
@SerialName("setcard")
data class SetCard(
    /** The set whose cardinality is taken. */
    val set: SetExpr,
) : IntExpr

// -----------------------------------------------------------------------------------
//  Optional-variable globals
// -----------------------------------------------------------------------------------
// Each *Opt node mirrors its non-opt sibling but carries a parallel [presents] list of
// Boolean expressions. The compiler reads each [BoolExpr] as a presence literal, threads
// it into the corresponding factor's `presents: IntArray`, and the factor handles the
// rest natively (see [com.eignex.klause.factor.OptPresence]).
//
// AllDifferentOpt over zero or one present element is trivially true and emits no factor;
// the constructor still requires `terms.size >= 2` because the compiler uses the same
// pair-by-pair pigeonhole guard as the non-opt form for non-empty cases.
