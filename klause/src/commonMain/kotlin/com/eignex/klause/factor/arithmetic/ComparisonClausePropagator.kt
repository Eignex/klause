package com.eignex.klause.factor.arithmetic

import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.Propagator
import com.eignex.klause.propagation.excludeIntValueImpl
import com.eignex.klause.propagation.tightenIntMaxImpl
import com.eignex.klause.propagation.tightenIntMinImpl
import com.eignex.util.collections.IntArrayList

// Literal truth is read from live domains. Wakes on any change, since `=`/`≠` literals flip on interior removals.
internal class ComparisonClausePropagator(
    private val vars: IntArray,
    private val ops: Array<LinearOp>,
    private val consts: LongArray,
) : Propagator {

    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        var undecided = -1
        for (i in vars.indices) {
            when (status(state, i)) {
                ENTAILED -> return true

                FALSIFIED -> {}

                else -> {
                    if (undecided >= 0) return true
                    undecided = i
                }
            }
        }
        return if (undecided < 0) false else enforce(state, undecided)
    }

    private fun enforce(state: PropagationState, k: Int): Boolean {
        val ant = otherFalsifiers(state, k)
        val v = vars[k]
        val c = consts[k]
        return when (ops[k]) {
            LinearOp.LE -> state.tightenIntMaxImpl(v, c, ant)
            LinearOp.GE -> state.tightenIntMinImpl(v, c, ant)
            LinearOp.EQ -> state.tightenIntMinImpl(v, c, ant) && state.tightenIntMaxImpl(v, c, ant)
            LinearOp.NE -> state.excludeIntValueImpl(v, c, ant)
        }
    }

    override fun conflictReason(state: PropagationState, factorId: Int): IntArray? {
        // A clean clausal nogood exists only for the all-falsified conflict: the negated true premise
        // of every literal. A conflict raised while *enforcing* the last literal (its tighten crossed
        // the opposite bound) leaves some literal non-falsified — defer to the engine's level analysis.
        val out = IntArrayList()
        for (i in vars.indices) {
            if (status(state, i) != FALSIFIED) return null
            out.add(falsifier(state, i))
        }
        return out.toIntArray()
    }

    private fun otherFalsifiers(state: PropagationState, k: Int): IntArray {
        val out = IntArrayList()
        for (i in vars.indices) if (i != k) out.add(falsifier(state, i))
        return out.toIntArray()
    }

    // The negation of the domain fact that falsifies literal [i], hence currently false.
    private fun falsifier(state: PropagationState, i: Int): Int {
        val v = vars[i]
        val c = consts[i]
        return when (ops[i]) {
            LinearOp.LE -> Lit.make(state.atomVarGe(v, c + 1), false)
            LinearOp.GE -> Lit.make(state.atomVarLe(v, c - 1), false)
            LinearOp.EQ -> Lit.make(state.atomVarEq(v, c), true)
            LinearOp.NE -> Lit.make(state.atomVarEq(v, c), false)
        }
    }

    private fun status(state: PropagationState, i: Int): Int {
        val d = state.intDomains[vars[i]]
        val c = consts[i]
        return when (ops[i]) {
            LinearOp.LE -> if (d.max <= c) {
                ENTAILED
            } else if (d.min > c) {
                FALSIFIED
            } else {
                UNDECIDED
            }

            LinearOp.GE -> if (d.min >= c) {
                ENTAILED
            } else if (d.max < c) {
                FALSIFIED
            } else {
                UNDECIDED
            }

            LinearOp.EQ -> if (d.min == c && d.max == c) {
                ENTAILED
            } else if (c !in d) {
                FALSIFIED
            } else {
                UNDECIDED
            }

            LinearOp.NE -> if (c !in d) {
                ENTAILED
            } else if (d.min == c && d.max == c) {
                FALSIFIED
            } else {
                UNDECIDED
            }
        }
    }

    private companion object {
        const val ENTAILED = 0
        const val FALSIFIED = 1
        const val UNDECIDED = 2
    }
}
