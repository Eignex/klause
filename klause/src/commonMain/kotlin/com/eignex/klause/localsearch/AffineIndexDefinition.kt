package com.eignex.klause.localsearch

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.table.Element
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.IntArrayDeque
import com.eignex.klause.util.IntArrayList

internal data class AffineIndexDefinition(val variable: Int, val factor: Linear, val outputIndex: Int)

internal fun affineIndexDefinitions(
    factors: Array<Factor>,
    numIntVars: Int,
    definedHints: IntArray,
    cancellation: Cancellation = Cancellation.Never,
): List<AffineIndexDefinition> = AffineIndexInference(factors, numIntVars, definedHints, cancellation).infer()

private class AffineIndexInference(
    private val factors: Array<Factor>,
    numIntVars: Int,
    private val definedHints: IntArray,
    private val cancellation: Cancellation,
) {
    private val hinted = BooleanArray(numIntVars)
    private val occurrences = arrayOfNulls<IntArrayList>(numIntVars)
    private val indexUse = BooleanArray(numIntVars)
    private val elementUse = BooleanArray(numIntVars)
    private val nonIndexCount = IntArray(numIntVars)
    private val acceptedRows = BooleanArray(factors.size)
    private val derived = BooleanArray(numIntVars)
    private val pending = IntArrayDeque()
    private val definitions = ArrayList<AffineIndexDefinition>()

    fun infer(): List<AffineIndexDefinition> {
        if (cancellation()) return emptyList()
        for (v in definedHints) {
            if (cancellation.definitionStopped()) return emptyList()
            if (v in hinted.indices) hinted[v] = true
        }
        for (fid in factors.indices) {
            if (cancellation.definitionStopped()) return emptyList()
            val factor = factors[fid]
            for (v in factor.intVars) {
                if (cancellation.definitionStopped()) return emptyList()
                if (!hinted[v]) continue
                val readers = occurrences[v] ?: IntArrayList().also { occurrences[v] = it }
                readers.add(fid)
                if (factor.isIndexRead(v)) {
                    indexUse[v] = true
                    if (factor is Element) elementUse[v] = true
                } else nonIndexCount[v]++
            }
        }
        for (fid in factors.indices) {
            if (cancellation.definitionStopped()) return emptyList()
            val factor = factors[fid] as? Linear ?: continue
            if (factor.op != LinearOp.EQ) continue
            val row = factor.integerConstants ?: continue
            val j = factor.vars.indices.firstOrNull {
                if (cancellation.definitionStopped()) return emptyList()
                val v = factor.vars[it]
                hinted[v] && indexUse[v] && nonIndexCount[v] == 1 &&
                    (elementUse[v] || factor.vars.size in 2..3) &&
                    (row.coeff(it) == 1L || row.coeff(it) == -1L)
            } ?: continue
            accept(fid, factor, j)
        }
        while (!pending.isEmpty()) {
            if (cancellation.definitionStopped()) return emptyList()
            val v = pending.removeFirst()
            if (derived[v]) continue
            val candidate = aliasRow(v) ?: continue
            val factor = factors[candidate] as Linear
            accept(candidate, factor, factor.vars.indexOf(v))
        }
        return if (cancellation()) emptyList() else definitions
    }

    private fun aliasRow(v: Int): Int? {
        val readers = occurrences[v] ?: return null
        var candidate = -1
        for (i in 0 until readers.size) {
            if (cancellation.definitionStopped()) return null
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
        definitions.add(AffineIndexDefinition(output, factor, j))
        for (v in factor.vars) {
            if (cancellation.definitionStopped()) return
            if (v != output && hinted[v] && !derived[v]) pending.addLast(v)
        }
    }

    private fun Factor.isIndexRead(v: Int): Boolean {
        return when (this) {
            is Element -> idx == v && result != v && (!arrIsVars || arr.none {
                if (cancellation.definitionStopped()) return false
                it == v.toLong()
            })
            is ReifiedLinear -> op == LinearOp.EQ && vars.size == 1 && vars[0] == v &&
                integerConstants?.coeff(0) == 1L
            else -> false
        }
    }
}
