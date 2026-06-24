package com.eignex.klause.solver.factor.symmetry

import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Propagator
import com.eignex.klause.solver.propagation.PropagationState
import com.eignex.klause.util.IntArrayList

/**
 * Dynamic symmetry handling: one global propagator for the whole automorphism group, replacing the
 * static enumeration of lex-leader factors. Holds the verified generators (each a kind-preserving
 * permutation of the variables); at every search node it enforces the lex-leader predicate
 * `V ≤lex σ(V)` for each generator, pruning the symmetric completions the prefix already rules
 * out. The lex-minimum of every orbit satisfies `V ≤lex σ(V)` for every group element, so each
 * predicate is a sound symmetry break and at least one representative per orbit always survives.
 *
 * A generator permutes integer and Boolean variables independently (detection is kind-preserving),
 * so the lex sequence orders the integer support in id order, then the Boolean support in id order;
 * each position compares a variable against its image of the same kind. The filtering is the
 * Frisch–Hnich–Kiziltan–Miguel–Walsh `α`/`β` rule generalised over typed positions: a Boolean reads
 * as the domain `[0,1]` (`true` ⇒ `[1,1]`, `false` ⇒ `[0,0]`), so a bound tightening at a Boolean
 * position is a pin. Reasons cite the fixed-equal prefix, the deciding pair, and — when the step is
 * forced strict — the scanned suffix, as integer order-atoms plus Boolean literals.
 */
internal class SymmetryPropagator(private val generators: List<Generator>) : Propagator {

    /** A generator's lex sequence: position `k` compares [leftVar]`[k]` against its image
     *  [rightVar]`[k]`, both Boolean when [isBool]`[k]` (else both integer). */
    class Generator(val leftVar: IntArray, val rightVar: IntArray, val isBool: BooleanArray)

    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        for (g in generators) if (!propagateLexLeader(state, g)) return false
        return true
    }

    /** `V ≤lex σ(V)` filtering for one generator. Returns `false` on contradiction. */
    @Suppress("ReturnCount", "CyclomaticComplexMethod", "NestedBlockDepth", "LongMethod")
    private fun propagateLexLeader(state: PropagationState, g: Generator): Boolean {
        val len = g.leftVar.size
        var a = 0
        while (true) {
            // Skip the fixed-equal prefix: positions where both sides are pinned to a common value.
            while (a < len) {
                val lLo = lo(state, g.leftVar[a], g.isBool[a])
                val lHi = hi(state, g.leftVar[a], g.isBool[a])
                val rLo = lo(state, g.rightVar[a], g.isBool[a])
                val rHi = hi(state, g.rightVar[a], g.isBool[a])
                if (lLo == lHi && rLo == rHi && lLo == rLo) a++ else break
            }
            if (a == len) return true // whole sequence forced equal: V = σ(V) satisfies ≤lex
            if (hi(state, g.leftVar[a], g.isBool[a]) < lo(state, g.rightVar[a], g.isBool[a])) return true

            // Scan for β: the most significant index from which the suffix is forced x ≥ y.
            var i = a
            var b = -1
            while (i < len) {
                val liLo = lo(state, g.leftVar[i], g.isBool[i])
                val riHi = hi(state, g.rightVar[i], g.isBool[i])
                if (liLo > riHi) break
                if (liLo == riHi) {
                    if (b == -1) b = i
                } else {
                    b = -1
                }
                i++
            }
            val betaInfinite = i == len // equal length, non-strict: the tail may stay equal
            if (betaInfinite) {
                b = Int.MAX_VALUE
            } else if (b == -1) {
                b = i
            }
            if (b <= a) return false

            val strictHere = !betaInfinite && b == a + 1
            val laLo = lo(state, g.leftVar[a], g.isBool[a])
            val raHi = hi(state, g.rightVar[a], g.isBool[a])
            val newLeftHi = if (strictHere) raHi - 1 else raHi
            val newRightLo = if (strictHere) laLo + 1 else laLo
            val ant = reason(state, g, a, i, strictHere)
            if (!tightenHi(state, g.leftVar[a], g.isBool[a], newLeftHi, ant)) return false
            if (!tightenLo(state, g.rightVar[a], g.isBool[a], newRightLo, ant)) return false

            // Re-examine α after the tightening.
            if (hi(state, g.leftVar[a], g.isBool[a]) < lo(state, g.rightVar[a], g.isBool[a])) return true
            val lFixed = lo(state, g.leftVar[a], g.isBool[a]) == hi(state, g.leftVar[a], g.isBool[a])
            val rFixed = lo(state, g.rightVar[a], g.isBool[a]) == hi(state, g.rightVar[a], g.isBool[a])
            if (lFixed && rFixed && lo(state, g.leftVar[a], g.isBool[a]) == lo(state, g.rightVar[a], g.isBool[a])) {
                a++
                continue
            }
            return true
        }
    }

    private fun lo(state: PropagationState, v: Int, isBool: Boolean): Int =
        if (isBool) (if (state.boolValues[v] == true) 1 else 0) else state.intDomains[v].min

    private fun hi(state: PropagationState, v: Int, isBool: Boolean): Int =
        if (isBool) (if (state.boolValues[v] == false) 0 else 1) else state.intDomains[v].max

    private fun tightenHi(state: PropagationState, v: Int, isBool: Boolean, newHi: Int, ant: IntArray?): Boolean =
        if (isBool) (newHi >= 1 || state.pinBool(v, false, ant)) else state.tightenIntMax(v, newHi, ant)

    private fun tightenLo(state: PropagationState, v: Int, isBool: Boolean, newLo: Int, ant: IntArray?): Boolean =
        if (isBool) (newLo <= 0 || state.pinBool(v, true, ant)) else state.tightenIntMin(v, newLo, ant)

    /** The premise of a tightening at α: the fixed-equal prefix, the α pair, and (when strict) the
     *  scanned suffix. Integer premises become order-atom antecedents; Boolean premises that are
     *  pinned become the negation of their current literal. A superset is sound. */
    private fun reason(state: PropagationState, g: Generator, a: Int, scanStop: Int, strictHere: Boolean): IntArray? {
        val intVars = IntArrayList()
        val boolVars = IntArrayList()
        fun add(pos: Int) {
            if (g.isBool[pos]) {
                boolVars.add(g.leftVar[pos])
                boolVars.add(g.rightVar[pos])
            } else {
                intVars.add(g.leftVar[pos])
                intVars.add(g.rightVar[pos])
            }
        }
        for (j in 0 until a) add(j)
        add(a)
        if (strictHere) {
            val last = minOf(scanStop, g.leftVar.size - 1)
            for (j in a + 1..last) add(j)
        }
        val intPart = if (intVars.size == 0) null else state.composeIntVarAtomAntecedents(intVars.toIntArray())
        val out = IntArrayList()
        if (intPart != null) for (l in intPart) out.add(l)
        for (k in 0 until boolVars.size) {
            val v = boolVars[k]
            val value = state.boolValues[v] ?: continue
            out.add(Lit.make(v, !value))
        }
        return if (out.size == 0) null else out.toIntArray()
    }
}
