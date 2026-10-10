package com.eignex.klause.localsearch

import com.eignex.klause.factor.circuit.internals.cycleScan
import kotlin.math.abs

internal class CircuitScoring(private val succ: IntArray, private val subcircuit: Boolean) {
    private val n: Int = succ.size

    fun computeCost(state: LocalSearchState, replaceAt: Int, replaceWith: Long): Int =
        if (subcircuit) subcircuitCost(state, replaceAt, replaceWith) else circuitCost(state, replaceAt, replaceWith)

    private fun circuitCost(state: LocalSearchState, replaceAt: Int, replaceWith: Long): Int {
        if (n == 1) {
            val v = if (replaceAt == 0) replaceWith else state.assignment.intValue(succ[0])
            return if (v == 0L) 0 else 1
        }
        val next = IntArray(n)
        var numSelfLoops = 0
        var numOob = 0
        for (i in 0 until n) {
            val s = if (i == replaceAt) replaceWith else state.assignment.intValue(succ[i])
            if (s < 0 || s >= n) {
                next[i] = -1
                numOob++
            } else if (s == i.toLong()) {
                next[i] = -1
                numSelfLoops++
            } else {
                next[i] = s.toInt()
            }
        }
        val scan = cycleScan(next, n)
        return abs(scan.numCycles - 1) + (n - scan.nodesInCycles) + numSelfLoops + numOob
    }

    private fun subcircuitCost(state: LocalSearchState, replaceAt: Int, replaceWith: Long): Int {
        val effective = LongArray(n) { i ->
            if (i == replaceAt) replaceWith else state.assignment.intValue(succ[i])
        }
        var numOob = 0
        var numIncluded = 0
        var numPointToExcluded = 0
        val included = BooleanArray(n)
        for (i in 0 until n) {
            val s = effective[i]
            if (s < 0 || s >= n) {
                numOob++
                continue
            }
            if (s != i.toLong()) {
                included[i] = true
                numIncluded++
            }
        }
        for (i in 0 until n) {
            if (!included[i]) continue
            val s = effective[i]
            if (s in 0 until n && !included[s.toInt()] && effective[s.toInt()] in 0 until n &&
                effective[s.toInt()] == s
            ) {
                numPointToExcluded++
            }
        }
        if (numIncluded == 0) return numOob
        val next = IntArray(n) { i ->
            if (!included[i]) {
                -1
            } else {
                val s = effective[i]
                if (s in 0 until n && s != i.toLong() && included[s.toInt()]) s.toInt() else -1
            }
        }
        val scan = cycleScan(next, n)
        return abs(scan.numCycles - 1) + (numIncluded - scan.nodesInCycles) + numPointToExcluded + numOob
    }
}
