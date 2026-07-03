package com.eignex.klause.factor.table

import com.eignex.klause.factor.compressViolation
import com.eignex.klause.localsearch.Invariant
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.MoveSink

/** LS invariant for [Element]. Constructed by [Element.asInvariant]. */
internal class ElementInvariant(
    private val idx: Int,
    private val result: Int,
    private val arr: IntArray,
    private val arrIsVars: Boolean,
    private val indexOffset: Int,
) : Invariant {

    override fun initialize(state: LocalSearchState, factorId: Int) {
        // Stateless — re-derived per query.
    }

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean {
        val len = arr.size
        val pos = state.assignment.intValue(idx) - indexOffset
        if (pos !in 0..<len) return true
        val ev = if (arrIsVars) state.assignment.intValue(arr[pos]) else arr[pos]
        return state.assignment.intValue(result) != ev
    }

    override fun violationDegree(state: LocalSearchState, factorId: Int): Int {
        val len = arr.size
        val idxVal = state.assignment.intValue(idx)
        val resultVal = state.assignment.intValue(result)
        val cap = state.violationSoftCap
        return elementDegreeAt(idxVal, resultVal, len, indexOffset, cap) { pos ->
            if (arrIsVars) state.assignment.intValue(arr[pos]) else arr[pos]
        }
    }

    override fun deltaIfIntSet(state: LocalSearchState, factorId: Int, intVar: Int, newValue: Int): Int {
        val len = arr.size
        val curIdx = state.assignment.intValue(idx)
        val curResult = state.assignment.intValue(result)
        val newIdx = if (intVar == idx) newValue else curIdx
        val newResult = if (intVar == result) newValue else curResult
        val cap = state.violationSoftCap
        val newDeg = elementDegreeAt(newIdx, newResult, len, indexOffset, cap) { pos ->
            if (arrIsVars && arr[pos] == intVar) {
                newValue
            } else if (arrIsVars) {
                state.assignment.intValue(arr[pos])
            } else {
                arr[pos]
            }
        }
        val oldDeg = elementDegreeAt(curIdx, curResult, len, indexOffset, cap) { pos ->
            if (arrIsVars) state.assignment.intValue(arr[pos]) else arr[pos]
        }
        return newDeg - oldDeg
    }

    override fun applyIntSet(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Int): Int = 0

    override fun proposeRepairMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        val len = arr.size
        val idxVal = state.assignment.intValue(idx)
        val pos = idxVal - indexOffset
        val idxDom = state.problem.intDomains[idx]
        val resultVal = state.assignment.intValue(result)
        val inRange = pos in 0 until len
        val ev = if (inRange) (if (arrIsVars) state.assignment.intValue(arr[pos]) else arr[pos]) else Int.MIN_VALUE
        if (inRange && resultVal == ev) return

        if (inRange) {
            if (ev in state.problem.intDomains[result]) sink.addChannelingIntSet(state, result, ev)
            if (arrIsVars) {
                val sel = arr[pos]
                if (resultVal in state.problem.intDomains[sel]) sink.addChannelingIntSet(state, sel, resultVal)
            }
        } else {
            val target = idxDom.clamp(if (idxVal < indexOffset) indexOffset else indexOffset + len - 1)
            if (target in idxDom && target != idxVal) sink.addChannelingIntSet(state, idx, target)
        }
        for (p in 0 until len) {
            val evp = if (arrIsVars) state.assignment.intValue(arr[p]) else arr[p]
            if (evp == resultVal) {
                val cand = p + indexOffset
                if (cand != idxVal && cand in idxDom) {
                    sink.addChannelingIntSet(state, idx, cand)
                    break
                }
            }
        }
    }
}

/** Graded degree for hypothetical (idxVal, resultVal); [elemAt] supplies the selected
 *  element's value. Non-inline version used from interface default methods. */
internal fun elementDegreeAt(
    idxVal: Int,
    resultVal: Int,
    len: Int,
    indexOffset: Int,
    softCap: Int,
    elemAt: (pos: Int) -> Int,
): Int {
    val pos = idxVal - indexOffset
    if (pos < 0) return compressViolation((indexOffset - idxVal).toLong(), softCap)
    if (pos >= len) return compressViolation((idxVal - (indexOffset + len - 1)).toLong(), softCap)
    val ev = elemAt(pos)
    val d = resultVal.toLong() - ev
    return compressViolation(if (d < 0) -d else d, softCap)
}
