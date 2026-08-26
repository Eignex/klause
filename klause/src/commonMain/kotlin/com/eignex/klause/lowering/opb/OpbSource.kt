package com.eignex.klause.lowering.opb

import com.eignex.klause.model.PbOp
import com.ionspin.kotlin.bignum.integer.BigInteger

/** Parsed OPB statements before lowering to a solver model. */
data class OpbDocument(
    /** Number of declared Boolean variables. */
    val numDeclaredVars: Int,
    /** Statements in source order. */
    val statements: List<OpbStatement>,
)

/** One parsed OPB statement. */
sealed interface OpbStatement {
    /** A minimisation objective. */
    data class Objective(val terms: List<OpbTerm>) : OpbStatement

    /** A WBO soft-constraint header. */
    data class SoftHeader(val top: Long?) : OpbStatement

    /** A hard or soft pseudo-Boolean relation. */
    data class Constraint(val softCost: Long?, val relation: OpbRelation) : OpbStatement
}

/** A coefficient multiplied by a conjunction of literals. */
data class OpbTerm(val coefficient: BigInteger, val literals: IntArray)

/** A pseudo-Boolean relation from an OPB source document. */
data class OpbRelation(val terms: List<OpbTerm>, val op: PbOp, val bound: BigInteger)
