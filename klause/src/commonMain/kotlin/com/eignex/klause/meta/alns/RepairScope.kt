package com.eignex.klause.meta.alns

import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.DefinitionalSweep
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.solver.Sample
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.IntHashSet

internal object RepairScope {
    fun effective(requested: FreedVars, assumptions: Assumptions, sweep: DefinitionalSweep?,
        cancellation: Cancellation,
    ): FreedVars? {
        val bools = sweep?.expandUnitBooleanScope(requested.bools, cancellation)
            ?: if (sweep == null) requested.bools else return null
        return FreedVars(bools.filterNot(assumptions::isFrozenBool).toIntArray(),
            requested.ints.filterNot(assumptions::isFrozenInt).toIntArray())
    }

    fun pins(problem: Problem, center: Sample, freed: FreedVars): Assumptions {
        val boolSet = IntHashSet().apply { for (b in freed.bools) add(b) }
        val intSet = IntHashSet().apply { for (i in freed.ints) add(i) }
        val boolKeys = IntArray(problem.numBoolVars - boolSet.size)
        val boolValues = BooleanArray(boolKeys.size)
        var bi = 0
        for (b in 0 until problem.numBoolVars) {
            if (b in boolSet) continue
            boolKeys[bi] = b
            boolValues[bi++] = center.boolValue(b)
        }
        val intKeys = IntArray(problem.numIntVars - intSet.size)
        val intValues = LongArray(intKeys.size)
        var ii = 0
        for (i in 0 until problem.numIntVars) {
            if (i in intSet) continue
            intKeys[ii] = i
            intValues[ii++] = center.intValue(i)
        }
        return Assumptions(boolKeys, boolValues, intKeys, intValues)
    }
}
