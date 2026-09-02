package com.eignex.klause.propagation

import com.eignex.klause.ir.intdomain.intDomainFromSurvivors
import com.eignex.klause.util.LongArrayList
import com.eignex.klause.util.MutableIntObjectMap
import com.eignex.klause.util.toSortedLongArray

/** Fold successful root-propagation deductions into the finite domains this projection owns. */
internal fun BakedProblem.foldRootDeductionsIntoDomains(result: PropagationResult) {
    if (result !is PropagationResult.Implied) return
    val domains = foldedIntDomains
    result.forEachInt { v, value -> domains[v] = domains[v].withMinAtLeast(value).withMaxAtMost(value) }
    result.forEachIntMin { v, lo -> domains[v] = domains[v].withMinAtLeast(lo) }
    result.forEachIntMax { v, hi -> domains[v] = domains[v].withMaxAtMost(hi) }
    val holesByVar = MutableIntObjectMap<LongArrayList>()
    result.forEachIntHole { v, value -> holesByVar.getOrPut(v) { LongArrayList() }.add(value) }
    holesByVar.forEach { v, holes ->
        val sorted = holes.toSortedLongArray()
        domains[v] = requireNotNull(domains[v].excludeValues(sorted)) {
            "baked holes emptied domain $v despite an Implied bake"
        }
    }
    result.forEachIntSet { v, survivors -> domains[v] = intDomainFromSurvivors(survivors) }
}
