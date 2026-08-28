package com.eignex.klause.formats.opb

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
    data class Objective(
        /** Objective terms. */
        val terms: List<OpbTerm>,
    ) : OpbStatement

    /** A WBO soft-constraint header. */
    data class SoftHeader(
        /** Optional top cost. */
        val top: Long?,
    ) : OpbStatement

    /** A hard or soft pseudo-Boolean relation. */
    data class Constraint(
        /** Optional cost of violating the relation. */
        val softCost: Long?,
        /** Pseudo-Boolean relation. */
        val relation: OpbRelation,
    ) : OpbStatement
}

/** A coefficient multiplied by a conjunction of literals. */
data class OpbTerm(
    /** Integer coefficient. */
    val coefficient: BigInteger,
    /** Conjoined DIMACS-encoded literals. */
    val literals: IntArray,
)

/** A pseudo-Boolean relation from an OPB source document. */
data class OpbRelation(
    /** Left-hand-side terms. */
    val terms: List<OpbTerm>,
    /** Relation operator. */
    val op: PbOp,
    /** Right-hand-side bound. */
    val bound: BigInteger,
)
