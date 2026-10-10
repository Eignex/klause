package com.eignex.klause.localsearch

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.table.Element
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.util.IntArrayDeque
import com.eignex.klause.util.IntArrayList

internal data class ElementIndexDefinition(val variable: Int, val factor: Linear, val outputIndex: Int)

internal fun elementIndexDefinitions(
    factors: Array<Factor>,
    numIntVars: Int,
    definedHints: IntArray,
): List<ElementIndexDefinition> = ElementIndexInference(factors, numIntVars, definedHints).infer()

private class ElementIndexInference(
    private val factors: Array<Factor>,
    numIntVars: Int,
    definedHints: IntArray,
) {
    private val hinted = BooleanArray(numIntVars).also { flags ->
        for (v in definedHints) if (v in flags.indices) flags[v] = true
    }
    private val occurrences = arrayOfNulls<IntArrayList>(numIntVars)
    private val indexUse = BooleanArray(numIntVars)
    private val nonIndexCount = IntArray(numIntVars)
    private val acceptedRows = BooleanArray(factors.size)
    private val derived = BooleanArray(numIntVars)
    private val pending = IntArrayDeque()
    private val definitions = ArrayList<ElementIndexDefinition>()

    fun infer(): List<ElementIndexDefinition> {
        for (fid in factors.indices) {
            val factor = factors[fid]
            for (v in factor.intVars) {
                if (!hinted[v]) continue
                val readers = occurrences[v] ?: IntArrayList().also { occurrences[v] = it }
                readers.add(fid)
                if (factor.isIndexRead(v)) indexUse[v] = true else nonIndexCount[v]++
            }
        }
        for (fid in factors.indices) {
            val factor = factors[fid] as? Linear ?: continue
            if (factor.op != LinearOp.EQ) continue
            val row = factor.integerConstants ?: continue
            val j = factor.vars.indices.firstOrNull {
                val v = factor.vars[it]
                hinted[v] && indexUse[v] && nonIndexCount[v] == 1 &&
                    (row.coeff(it) == 1L || row.coeff(it) == -1L)
            } ?: continue
            accept(fid, factor, j)
        }
        while (!pending.isEmpty()) {
            val v = pending.removeFirst()
            if (derived[v]) continue
            val candidate = aliasRow(v) ?: continue
            val factor = factors[candidate] as Linear
            accept(candidate, factor, factor.vars.indexOf(v))
        }
        return definitions
    }

    private fun aliasRow(v: Int): Int? {
        val readers = occurrences[v] ?: return null
        var candidate = -1
        for (i in 0 until readers.size) {
            val fid = readers[i]
            val factor = factors[fid]
            if (acceptedRows[fid] || factor.isIndexRead(v)) continue
            if (candidate >= 0 || factor !is Linear || factor.op != LinearOp.EQ || factor.vars.size != 2) return null
            val row = factor.integerConstants ?: return null
            val j = factor.vars.indexOf(v)
            if (row.coeff(j) != 1L && row.coeff(j) != -1L) return null
            candidate = fid
        }
        return candidate.takeIf { it >= 0 }
    }

    private fun accept(fid: Int, factor: Linear, j: Int) {
        val output = factor.vars[j]
        if (derived[output]) return
        acceptedRows[fid] = true
        derived[output] = true
        definitions.add(ElementIndexDefinition(output, factor, j))
        for (v in factor.vars) if (v != output && hinted[v] && !derived[v]) pending.addLast(v)
    }

    private fun Factor.isIndexRead(v: Int): Boolean =
        this is Element && idx == v && result != v && (!arrIsVars || v.toLong() !in arr)
}
