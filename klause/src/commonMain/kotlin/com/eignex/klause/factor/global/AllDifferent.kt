package com.eignex.klause.factor.global

import com.eignex.klause.factor.OptPresence
import com.eignex.klause.factor.OptionalFactor
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.FactorKind
import com.eignex.klause.ir.FactorReduction
import com.eignex.klause.ir.FactorReduction.Rewrite
import com.eignex.klause.ir.FactorReduction.Unchanged
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.MixedVars
import com.eignex.klause.ir.StructuralKey
import com.eignex.klause.ir.VarList
import com.eignex.klause.ir.VarRemap
import com.eignex.klause.localsearch.Invariant
import com.eignex.util.arrays.emptyIntArray
import com.eignex.util.arrays.emptyLongArray

/**
 * `vars(i) != vars(j)` for every pair `i < j` of present positions, except where both take a value of
 * [exceptSet].
 *
 * Finite propagation uses bipartite matching and SCC pruning when live domains can be enumerated,
 * with bounds or fixed-value deductions for wider domains. Only definitely-present positions filter.
 * With [boundsConsistent], and no presence literals or excepted values, propagation uses bounds
 * consistency where endpoint arithmetic permits it.
 *
 * Local search counts, per value, how many present vars hold it; the graded violation is
 * `Σ max(0, count - 1)` — the number of vars that must move to clear every clash — and it is the
 * [Invariant.violationDegree] CBLS descends.
 */
class AllDifferent(
    /** Integer variable ids required to be pairwise distinct. */
    val vars: IntArray,
    /** Per-position presence literals, or empty when every position is present. Only present
     *  positions are required pairwise-different. */
    override val presents: IntArray = emptyIntArray(),
    /** Values any number of variables may share (`alldifferent_except`); empty for plain all-different. */
    val exceptSet: LongArray = emptyLongArray(),
    /** Request bounds consistency (a Hall-interval filter) instead of matching-based GAC. */
    val boundsConsistent: Boolean = false,
) : Factor,
    OptionalFactor {

    /** Retain a distinctness relation from a caller supplying a finite value-window hint. */
    constructor(
        vars: IntArray,
        @Suppress("UNUSED_PARAMETER") domainMin: Long,
        domainSize: Int,
        presents: IntArray = emptyIntArray(),
        exceptSet: LongArray = emptyLongArray(),
        boundsConsistent: Boolean = false,
    ) : this(vars, presents, exceptSet, boundsConsistent) {
        require(domainSize >= 1) { "AllDifferent domainSize must be >= 1, got $domainSize" }
    }

    init {
        require(vars.size >= 2) { "AllDifferent needs at least two variables" }
        require(presents.isEmpty() || presents.size == vars.size) {
            "AllDifferent: presents must be empty or match vars arity"
        }
    }

    private val exceptSorted: LongArray =
        if (exceptSet.isEmpty()) emptyLongArray() else exceptSet.distinct().sorted().toLongArray()

    override fun structuralKey(): StructuralKey = StructuralKey.of(FactorKind.ALL_DIFFERENT) {
        pairsByKey(vars) { presents.getOrElse(it) { -1 }.toLong() }
        longs(exceptSorted)
        bool(boundsConsistent)
    }

    override fun remapStructuralHash(mapping: VarRemap): Int = remap(mapping).structuralKey().hashCode()

    override fun isValueAnonymous(): Boolean = exceptSet.isEmpty()

    override fun remapValues(valueMap: (Long) -> Long): Factor? = if (exceptSet.isEmpty()) {
        this
    } else {
        AllDifferent(
            vars,
            presents,
            LongArray(exceptSet.size) { valueMap(exceptSet[it]) },
            boundsConsistent,
        )
    }

    override fun remap(mapping: VarRemap): Factor = AllDifferent(
        mapping.ints(vars),
        mapping.lits(presents),
        exceptSet,
        boundsConsistent,
    )

    // Only plain all-different reduces. Groups whose value ranges cannot overlap are independent, so
    // each becomes its own all-different, which also exposes per-component symmetry.
    override fun structuralReduce(domains: Array<IntDomain>): FactorReduction {
        if (presents.isNotEmpty() || exceptSet.isNotEmpty()) return Unchanged
        if (vars.size == 2) {
            return Rewrite(
                listOf(Linear(intArrayOf(1, -1), intArrayOf(vars[0], vars[1]), LinearOp.NE, 0)),
            )
        }
        return splitIntoValueDisjointComponents(domains)
    }

    private fun splitIntoValueDisjointComponents(domains: Array<IntDomain>): FactorReduction {
        // Components of overlapping `[min, max]` intervals are contiguous slices of the min-sorted order.
        val order = vars.indices.sortedBy { domains[vars[it]].min }
        val components = ArrayList<List<Int>>()
        var start = 0
        var runningMax: Long = domains[vars[order[0]]].max
        for (k in 1 until order.size) {
            val d = domains[vars[order[k]]]
            if (d.min <= runningMax) {
                if (d.max > runningMax) runningMax = d.max
            } else {
                components.add(order.subList(start, k).map { vars[it] })
                start = k
                runningMax = d.max
            }
        }
        components.add(order.subList(start, order.size).map { vars[it] })
        if (components.size == 1) return Unchanged
        val replacement = ArrayList<Factor>(components.size)
        for (group in components) {
            if (group.size < 2) continue
            replacement.add(AllDifferent(group.toIntArray(), boundsConsistent = boundsConsistent))
        }
        return Rewrite(replacement)
    }

    override val variables: VarList = MixedVars(spanInts = vars, boolVars = OptPresence.presenceVarIds(presents))
}
