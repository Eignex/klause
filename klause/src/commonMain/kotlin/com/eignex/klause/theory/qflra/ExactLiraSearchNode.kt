package com.eignex.klause.theory.qflra

import com.eignex.klause.ir.LinearForm
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.complemented
import com.eignex.klause.ir.indices
import com.eignex.klause.ir.linearRows
import com.eignex.klause.lp.ExactRowForm
import com.eignex.klause.simplex.exact.ExactRationalInequality
import com.eignex.klause.solver.search.RegisteredTheoryDecision
import com.eignex.klause.solver.search.SearchAtomPremise
import com.eignex.klause.solver.search.SearchContext
import com.eignex.klause.solver.search.SearchIntValue
import com.eignex.klause.solver.search.SearchRealValue
import com.eignex.klause.solver.search.SearchTheoryDecision
import com.eignex.klause.solver.search.SourceBoundAtom
import com.eignex.klause.solver.search.SourceBoundPredicate
import com.eignex.util.collections.MutableIntIntMap
import com.eignex.util.numbers.BIG_ONE
import com.eignex.util.numbers.BigFraction
import com.eignex.util.numbers.BigInt
import com.eignex.util.numbers.bigIntOf
import com.eignex.util.numbers.compareTo
import com.eignex.util.numbers.maxOf
import com.eignex.util.numbers.minOf
import com.eignex.util.numbers.minus

internal data class IntegerBranch(val variable: Int, val lower: BigInt? = null, val upper: BigInt? = null)

internal data class RowAddress(val factor: Int, val row: Int) : Comparable<RowAddress> {
    override fun compareTo(other: RowAddress): Int = compareValuesBy(this, other, RowAddress::factor, RowAddress::row)
}

internal class PreparedSourceRow(
    val address: RowAddress,
    val row: LinearRow,
    val form: ExactRowForm,
    val booleans: IntArray,
    val disjunction: Boolean,
)

internal data class SearchNode(
    val branches: List<IntegerBranch> = emptyList(),
    val sourceBranches: List<SourceBoundAtom> = emptyList(),
    val retainedReduction: ExactLiraReduction.Bounded? = null,
    val reducedBranches: List<IntegerBranch> = emptyList(),
    val rowAssertions: Map<RowAddress, RegisteredTheoryDecision> = emptyMap(),
    val disequalityDirections: Map<RowAddress, LinearOp> = emptyMap(),
    val directionPremises: Map<RowAddress, SearchAtomPremise> = emptyMap(),
) {
    fun withReducedBranch(branch: IntegerBranch): SearchNode {
        val existing = reducedBranches.indexOfFirst { it.variable == branch.variable }
        if (existing < 0) return copy(reducedBranches = reducedBranches + branch)
        val merged = reducedBranches[existing].copy(
            lower = maxOfNullable(reducedBranches[existing].lower, branch.lower),
            upper = minOfNullable(reducedBranches[existing].upper, branch.upper),
        )
        return copy(reducedBranches = reducedBranches.toMutableList().also { it[existing] = merged })
    }

    fun withDirection(factor: RowAddress, direction: LinearOp): SearchNode =
        copy(disequalityDirections = disequalityDirections + (factor to direction))

    fun rowTruth(address: RowAddress, row: LinearRow, bools: IntArray): Boolean? =
        rowTruth(address, row.truthUnder(bools))

    fun rowTruth(prepared: PreparedSourceRow, bools: IntArray): Boolean? =
        rowTruth(prepared.address, prepared.truthUnder(bools))

    private fun rowTruth(address: RowAddress, truth: Boolean?): Boolean? {
        if (truth == null) return null
        val asserted = rowAssertions[address]?.payload as? NamedDisjunctDecision
        return if (asserted?.holds == false) !truth else truth
    }

    fun selectsEveryDisjunction(model: Problem, disjunctionAtoms: Map<Int, List<DisjunctAtom>>): Boolean =
        forEachSelectedRow(model, disjunctionAtoms) { _, _, _ -> }

    // Scalar atoms state their rows through sourceBranches; compound comparisons retain both polarities here.
    inline fun forEachSelectedRow(
        model: Problem,
        disjunctionAtoms: Map<Int, List<DisjunctAtom>>,
        action: (Int, Int, LinearRow) -> Unit,
    ): Boolean {
        for ((index, factor) in model.factors.withIndex()) {
            val rows = factor.linearRows
            val disjuncts = disjunctionAtoms[index]
            if (disjuncts != null) {
                if (disjuncts.none {
                        it.row?.let { row -> row in sourceBranches } == true ||
                            (rowAssertions[it.address]?.payload as? NamedDisjunctDecision)?.holds == true
                    }
                ) {
                    return false
                }
                for (disjunct in disjuncts) {
                    if (disjunct.address in rowAssertions) {
                        action(index, disjunct.address.row, rows[disjunct.address.row])
                    }
                }
            } else if (factor.linearForm is LinearForm.Disjunction) {
                return false
            } else {
                for (rowIndex in rows.indices) action(index, rowIndex, rows[rowIndex])
            }
        }
        return true
    }

    fun nextDisequality(model: Problem, disjunctionAtoms: Map<Int, List<DisjunctAtom>>, bools: IntArray): RowAddress? {
        forEachSelectedRow(model, disjunctionAtoms) { factor, index, row ->
            val truth = rowTruth(RowAddress(factor, index), row, bools) ?: return@forEachSelectedRow
            if ((if (truth) row.relation else row.relation.complemented()) != LinearOp.NE) return@forEachSelectedRow
            val address = RowAddress(factor, index)
            if (address !in disequalityDirections) return address
        }
        return null
    }
}

// Every bounded integer is folded into the node's branches in one pass: folding them one at a time scans and copies
// the branch list per bound, quadratic in the bounds a large root publishes.
internal fun SearchNode.withPublishedBounds(
    numIntVars: Int,
    lowerBound: (Int) -> Long?,
    upperBound: (Int) -> Long?,
): SearchNode {
    var merged: MutableList<IntegerBranch>? = null
    var positions: MutableIntIntMap? = null
    for (integer in 0 until numIntVars) {
        val lower = lowerBound(integer)?.let(::bigIntOf)
        val upper = upperBound(integer)?.let(::bigIntOf)
        if (lower == null && upper == null) continue
        val index = positions ?: MutableIntIntMap(branches.size * 2).also { map ->
            branches.forEachIndexed { at, branch -> map.put(branch.variable, at) }
            positions = map
        }
        val at = index.getOrDefault(integer, -1)
        if (at < 0) {
            val list = merged ?: branches.toMutableList().also { merged = it }
            index.put(integer, list.size)
            list += IntegerBranch(integer, lower, upper)
        } else {
            val existing = (merged ?: branches)[at]
            val nextLower = maxOfNullable(existing.lower, lower)
            val nextUpper = minOfNullable(existing.upper, upper)
            if (nextLower === existing.lower && nextUpper === existing.upper) continue
            val list = merged ?: branches.toMutableList().also { merged = it }
            list[at] = existing.copy(lower = nextLower, upper = nextUpper)
        }
    }
    return merged?.let { copy(branches = it) } ?: this
}

// holds states one row of a comparison disjunction and fails its exact complement.
internal class DisjunctAtom(
    val holds: RegisteredTheoryDecision,
    val fails: RegisteredTheoryDecision,
    val address: RowAddress,
) {
    val row = (holds.payload as? SourceBoundPredicate)?.sourceBound
    val complement = (fails.payload as? SourceBoundPredicate)?.sourceBound
}

// The source branches search made. A decided disjunct restates a model row the size limits already count.
internal fun SearchNode.searchedBranches(disjunctionAtoms: Map<Int, List<DisjunctAtom>>): List<SourceBoundAtom> {
    if (disjunctionAtoms.isEmpty()) return sourceBranches
    val restated = HashSet<SourceBoundAtom>()
    for (disjuncts in disjunctionAtoms.values) {
        for (disjunct in disjuncts) {
            disjunct.row?.let { restated += it }
            disjunct.complement?.let { restated += it }
        }
    }
    return sourceBranches.filter { it !in restated }
}

internal fun SearchContext.truth(decision: RegisteredTheoryDecision): Boolean? =
    boolValue(decision.literal ushr 1)?.let { it == (decision.literal and 1 == 0) }

// The owner fixes the entire source predicate, including its Boolean terms and activator, for this session.
internal data class NamedPrivateDisequality(val owner: Any, val address: RowAddress, val direction: LinearOp) :
    SearchTheoryDecision

internal data class NamedDisjunctDecision(val owner: Any, val address: RowAddress, val holds: Boolean) :
    SearchTheoryDecision

internal fun SourceBoundAtom.sourceRows(realColumns: Int): List<ExactRationalInequality> {
    val columns = terms.associate { term ->
        val column = when (val key = term.source) {
            is SearchIntValue -> realColumns + key.variable
            is SearchRealValue -> key.variable
            else -> error("unsupported registered source")
        }
        column to if (upper) term.coefficient else term.coefficient.negated()
    }.entries.sortedBy { it.key }
    return listOf(
        ExactRationalInequality(
            columns.map { it.key }.toIntArray(),
            columns.map { it.value },
            if (upper) threshold else threshold.negated(),
            strict,
        ),
    )
}

internal fun BigFraction.isInteger(): Boolean = den == BIG_ONE

internal fun maxOfNullable(a: BigInt?, b: BigInt?): BigInt? = if (a == null || b == null) a ?: b else maxOf(a, b)

internal fun minOfNullable(a: BigInt?, b: BigInt?): BigInt? = if (a == null || b == null) a ?: b else minOf(a, b)
