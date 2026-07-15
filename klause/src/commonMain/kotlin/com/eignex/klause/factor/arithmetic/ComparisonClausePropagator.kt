package com.eignex.klause.factor.arithmetic

import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.Propagator
import com.eignex.klause.propagation.excludeIntValueImpl
import com.eignex.klause.propagation.tightenIntMaxImpl
import com.eignex.klause.propagation.tightenIntMinImpl
import com.eignex.klause.solver.Lit
import com.eignex.klause.util.IntArrayList

/**
 * CP propagator for [ComparisonClause]: the disjunction `⋁ᵢ (vars(i) ⟨ops(i)⟩ consts(i))`. Each
 * literal's truth is read straight from the live domain — no reifying Boolean, order atom, or
 * channeling clause. Unit propagation: with every literal but one falsified, the survivor is
 * enforced; with all falsified, the clause conflicts.
 *
 * Wakeup uses the default occurrence-list path (fire on any change to a member variable), which is
 * always correct for literal statuses that can flip on an interior value removal (`=`/`≠`).
 */
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

                // clause satisfied — nothing to enforce
                FALSIFIED -> {}

                else -> {
                    // A second undecided literal means the clause can still be satisfied two ways, so
                    // no bound is forced; the falsified prefix scanned so far is irrelevant.
                    if (undecided >= 0) return true
                    undecided = i
                }
            }
        }
        // No literal is entailed. All falsified → conflict (the engine seeds this factor and reads
        // the atom-level nogood from [conflictReason]); exactly one undecided → enforce it.
        return if (undecided < 0) false else enforce(state, undecided)
    }

    /** Enforce literal [k] (all others falsified) with the falsifiers of the other literals as the
     *  antecedent, so the recorded bound reason traces back through them. */
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

    /** The falsifiers of every literal except [k]; all are currently-false literals whose being false
     *  is exactly why literal [k] must now hold. */
    private fun otherFalsifiers(state: PropagationState, k: Int): IntArray {
        val out = IntArrayList()
        for (i in vars.indices) if (i != k) out.add(falsifier(state, i))
        return out.toIntArray()
    }

    /**
     * The reason literal for falsified literal [i] — the negation of the domain fact that falsifies
     * it, hence currently false. `x ≤ c` false ⟺ `[x ≥ c+1]` holds (cite `¬[x ≥ c+1]`); `x ≥ c` false
     * ⟺ `[x ≤ c−1]` holds; `x = c` false ⟺ `[x = c]` is false (cite `[x = c]`); `x ≠ c` false ⟺
     * `[x = c]` holds (cite `¬[x = c]`).
     */
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
