package com.eignex.klause.localsearch

import com.eignex.klause.factor.arithmetic.ArrayMinMax
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.LinearOp

internal class ExtremumDefinition(val variable: Int, val factor: Factor, val outputIndex: Int)

internal fun extremumDefinitions(
    factors: Array<Factor>,
    numIntVars: Int,
    hints: IntArray,
): List<ExtremumDefinition> {
    val hinted = BooleanArray(numIntVars)
    for (v in hints) if (v in hinted.indices) hinted[v] = true
    val known = BooleanArray(numIntVars)
    val extremeOutputs = BooleanArray(numIntVars)
    val needed = BooleanArray(numIntVars)
    val definitions = ArrayList<ExtremumDefinition>()
    for (f in factors) {
        if (f !is ArrayMinMax || !hinted[f.result]) continue
        definitions.add(ExtremumDefinition(f.result, f, -1))
        known[f.result] = true
        extremeOutputs[f.result] = true
        for (v in f.xs) needed[v] = true
    }
    val used = BooleanArray(factors.size)
    var changed = true
    while (changed) {
        changed = false
        for (i in factors.indices) {
            if (used[i]) continue
            val f = factors[i] as? Linear ?: continue
            if (f.op != LinearOp.EQ) continue
            val row = f.integerConstants ?: continue
            val candidates = f.vars.indices.filter {
                hinted[f.vars[it]] && !known[f.vars[it]] && (row.coeff(it) == 1L || row.coeff(it) == -1L)
            }
            val j = candidates.singleOrNull() ?: continue
            if (!needed[f.vars[j]] && f.vars.none { known[it] }) continue
            definitions.add(ExtremumDefinition(f.vars[j], f, j))
            used[i] = true
            known[f.vars[j]] = true
            for (k in f.vars.indices) if (k != j) needed[f.vars[k]] = true
            changed = true
        }
    }
    // Every competing row for an oriented output is a competing definition, including cycles.
    for (i in factors.indices) {
        if (used[i]) continue
        val f = factors[i] as? Linear ?: continue
        if (f.op != LinearOp.EQ) continue
        val row = f.integerConstants ?: continue
        if (f.vars.size < 2) continue
        val candidates = f.vars.indices.filter {
            known[f.vars[it]] && !extremeOutputs[f.vars[it]] && hinted[f.vars[it]] &&
                (row.coeff(it) == 1L || row.coeff(it) == -1L)
        }
        val j = candidates.singleOrNull() ?: candidates.singleOrNull { index ->
            definitions.any { it.variable == f.vars[index] && it.factor.structuralKey() == f.structuralKey() }
        } ?: continue
        definitions.add(ExtremumDefinition(f.vars[j], f, j))
    }
    return definitions
}
