package com.eignex.klause.factor.table

import com.eignex.klause.factor.compressViolation
import com.eignex.klause.factor.distance
import com.eignex.klause.factor.saturatedSub
import com.eignex.klause.localsearch.Invariant
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.MoveSink

internal class ElementInvariant(
    private val idx: Int,
    private val result: Int,
    private val arr: LongArray,
    private val arrIsVars: Boolean,
    private val indexOffset: Int,
) : Invariant {

    override fun initialize(state: LocalSearchState, factorId: Int) {
        // Preserve the state's matching-index cursor across cost recomputation.
    }

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean {
        val len = arr.size
        val pos = state.assignment.intValue(idx) - indexOffset
        if (pos < 0 || pos >= len) return true
        val p = pos.toInt()
        val ev = if (arrIsVars) state.assignment.intValue(arr[p].toInt()) else arr[p]
        return state.assignment.intValue(result) != ev
    }

    override fun violationDegree(state: LocalSearchState, factorId: Int): Int {
        val len = arr.size
        val idxVal = state.assignment.intValue(idx)
        val resultVal = state.assignment.intValue(result)
        val cap = state.violationSoftCap
        return elementDegreeAt(idxVal, resultVal, len, indexOffset, cap) { pos ->
            if (arrIsVars) state.assignment.intValue(arr[pos].toInt()) else arr[pos]
        }
    }

    override fun deltaIfIntSet(state: LocalSearchState, factorId: Int, intVar: Int, newValue: Long): Int {
        val len = arr.size
        val curIdx = state.assignment.intValue(idx)
        val curResult = state.assignment.intValue(result)
        val newIdx = if (intVar == idx) newValue else curIdx
        val newResult = if (intVar == result) newValue else curResult
        val cap = state.violationSoftCap
        val newDeg = elementDegreeAt(newIdx, newResult, len, indexOffset, cap) { pos ->
            if (arrIsVars && arr[pos].toInt() == intVar) {
                newValue
            } else if (arrIsVars) {
                state.assignment.intValue(arr[pos].toInt())
            } else {
                arr[pos]
            }
        }
        val oldDeg = elementDegreeAt(curIdx, curResult, len, indexOffset, cap) { pos ->
            if (arrIsVars) state.assignment.intValue(arr[pos].toInt()) else arr[pos]
        }
        return newDeg - oldDeg
    }

    override fun applyIntSet(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Long): Int = 0

    override fun proposeRepairMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        val len = arr.size
        val idxVal = state.assignment.intValue(idx)
        val pos = idxVal - indexOffset
        val idxDom = state.rootDomains[idx]
        val resultVal = state.assignment.intValue(result)
        val inRange = pos >= 0 && pos < len
        val ev = if (inRange) {
            val p = pos.toInt()
            if (arrIsVars) state.assignment.intValue(arr[p].toInt()) else arr[p]
        } else {
            Long.MIN_VALUE
        }
        if (inRange && resultVal == ev) return

        if (inRange) {
            if (ev in state.rootDomains[result]) sink.addChannelingIntSet(state, result, ev)
            if (arrIsVars) {
                val sel = arr[pos.toInt()].toInt()
                if (resultVal in state.rootDomains[sel]) sink.addChannelingIntSet(state, sel, resultVal)
            }
        } else {
            val target = idxDom.clamp(
                if (idxVal < indexOffset) indexOffset.toLong() else indexOffset.toLong() + len - 1L,
            )
            if (target in idxDom && target != idxVal) sink.addElementIndexSet(state, idx, target)
        }
        var firstMatch = -1
        for (p in 0 until len) {
            val evp = if (arrIsVars) state.assignment.intValue(arr[p].toInt()) else arr[p]
            if (evp == resultVal) {
                val cand = indexOffset.toLong() + p
                if (cand != idxVal && cand in idxDom && sink.addElementIndexSet(state, idx, cand)) {
                    firstMatch = p
                    break
                }
            }
        }
        if (firstMatch >= 0) proposeAlternativeIndexes(state, factorId, sink, firstMatch)
    }

    private fun proposeAlternativeIndexes(
        state: LocalSearchState,
        factorId: Int,
        sink: MoveSink,
        firstMatch: Int,
    ) {
        val network = state.invariants
        if (network?.isDefinedInt(idx) == true &&
            network.linearDefinition(idx) == null && !network.hasExtremumRepair(idx)
        ) return
        val alternatives = sink.alternativeRepairs ?: sink
        alternatives.proposer = sink.proposer
        val idxVal = state.assignment.intValue(idx)
        val resultVal = state.assignment.intValue(result)
        var attempts = 0
        for (i in 0 until minOf(arr.size, MAX_EXTRA_POSITIONS)) {
            val position = state.intPayload[factorId]
            state.intPayload[factorId] = if (position + 1 == arr.size) 0 else position + 1
            if (position == firstMatch) continue
            val entry = arr[position]
            val value = if (arrIsVars) state.assignment.intValue(entry.toInt()) else entry
            val target = indexOffset.toLong() + position
            if (value != resultVal || target == idxVal || target !in state.rootDomains[idx]) continue
            alternatives.addElementIndexSet(state, idx, target)
            attempts++
            if (attempts >= MAX_EXTRA_REPAIRS) return
        }
    }

    private companion object {
        const val MAX_EXTRA_POSITIONS = 32
        const val MAX_EXTRA_REPAIRS = 2
    }
}

/** Graded degree for hypothetical (idxVal, resultVal); [elemAt] supplies the selected
 *  element's value. Non-inline version used from interface default methods. */
internal fun elementDegreeAt(
    idxVal: Long,
    resultVal: Long,
    len: Int,
    indexOffset: Int,
    softCap: Int,
    elemAt: (pos: Int) -> Long,
): Int {
    val pos = idxVal - indexOffset
    if (pos < 0) return compressViolation(saturatedSub(indexOffset.toLong(), idxVal), softCap)
    if (pos >= len) return compressViolation(saturatedSub(idxVal, indexOffset.toLong() + len - 1), softCap)
    val ev = elemAt(pos.toInt())
    return compressViolation(distance(resultVal, ev), softCap)
}
