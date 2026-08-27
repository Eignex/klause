package com.eignex.klause.propagation

import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.intdomain.intDomainFromSurvivors
import com.eignex.klause.util.LongArrayList
import com.eignex.klause.util.MutableIntObjectMap
import com.eignex.klause.util.toSortedLongArray

/** Fold successful root-propagation deductions into a finite `Problem`'s domains. */
internal fun Problem.foldRootDeductionsIntoDomains(result: PropagationResult) {
    if (result !is PropagationResult.Implied) return
    result.forEachInt { v, value ->
        requireFiniteIntDomains()[v] = requireFiniteIntDomains()[v].withMinAtLeast(value).withMaxAtMost(value)
    }
    result.forEachIntMin { v, lo -> requireFiniteIntDomains()[v] = requireFiniteIntDomains()[v].withMinAtLeast(lo) }
    result.forEachIntMax { v, hi -> requireFiniteIntDomains()[v] = requireFiniteIntDomains()[v].withMaxAtMost(hi) }
    val holesByVar = MutableIntObjectMap<LongArrayList>()
    result.forEachIntHole { v, value -> holesByVar.getOrPut(v) { LongArrayList() }.add(value) }
    holesByVar.forEach { v, holes ->
        val sorted = holes.toSortedLongArray()
        requireFiniteIntDomains()[v] = requireNotNull(requireFiniteIntDomains()[v].excludeValues(sorted)) {
            "baked holes emptied domain $v despite an Implied bake"
        }
    }
    result.forEachIntSet { v, survivors -> requireFiniteIntDomains()[v] = intDomainFromSurvivors(survivors) }
}
