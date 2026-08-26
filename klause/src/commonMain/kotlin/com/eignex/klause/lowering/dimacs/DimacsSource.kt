package com.eignex.klause.lowering.dimacs

/** A parsed DIMACS CNF document before lowering to a solver problem. */
data class CnfDocument(
    /** Number of declared Boolean variables. */
    val numBoolVars: Int,
    /** Clauses as DIMACS-encoded klause literals. */
    val clauses: List<IntArray>,
    /** Whether the source contained an empty clause. */
    val triviallyUnsat: Boolean,
)

/** A weighted DIMACS clause. */
data class WeightedCnfClause(
    /** Penalty when this clause is false. */
    val weight: Long,
    /** Clause literals. */
    val literals: IntArray,
)

/** A parsed WCNF document before relaxation variables and objectives are constructed. */
data class WcnfDocument(
    /** Number of original Boolean variables. */
    val numOriginalBoolVars: Int,
    /** Hard clauses. */
    val hardClauses: List<IntArray>,
    /** Soft clauses. */
    val softClauses: List<WeightedCnfClause>,
    /** Whether a hard empty clause occurred. */
    val triviallyUnsat: Boolean,
    /** Cost from soft empty clauses. */
    val fixedCost: Long,
)
