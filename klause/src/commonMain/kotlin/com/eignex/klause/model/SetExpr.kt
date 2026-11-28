package com.eignex.klause.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Anything that can be coerced into a [SetExpr] inside the constraint DSL. */
interface SetTerm {
    /** Coerce this term into a [SetExpr] node. */
    fun toSetExpr(): SetExpr
}

/** A set-valued node in the constraint AST. */
@Serializable(with = SetExprSerializer::class)
sealed interface SetExpr : SetTerm {
    override fun toSetExpr(): SetExpr = this
}

/** Reference to a named set variable. */
@Serializable
@SerialName("setref")
data class SetRef(
    /** Name of the referenced set variable. */
    val name: String,
) : SetExpr

/** Concrete set literal over an integer universe. */
@Serializable
@SerialName("setlit")
data class SetLiteral(
    /** The literal set's elements. */
    val elements: List<Int>,
) : SetExpr

/** Concrete set literal over a nominal universe. */
@Serializable
@SerialName("setlitnom")
data class SetNominalLiteral(
    /** The literal set's nominal labels, resolved against the operand's universe. */
    val labels: List<String>,
) : SetExpr

/** Set union `left ∪ right`. */
@Serializable
@SerialName("setunion")
data class SetUnion(
    /** Left operand. */
    val left: SetExpr,
    /** Right operand. */
    val right: SetExpr,
) : SetExpr

/** Set intersection `left ∩ right`. */
@Serializable
@SerialName("setisect")
data class SetIntersect(
    /** Left operand. */
    val left: SetExpr,
    /** Right operand. */
    val right: SetExpr,
) : SetExpr

/** Set difference `left \ right`. */
@Serializable
@SerialName("setdiff")
data class SetDiff(
    /** Left operand. */
    val left: SetExpr,
    /** Right operand subtracted from [left]. */
    val right: SetExpr,
) : SetExpr
