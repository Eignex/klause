package com.eignex.klause.solver.factor.symmetry

import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Propagator
import com.eignex.klause.solver.propagation.PropagationState
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.PermutationGroup

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
internal class SymmetryPropagator(
    private val generators: List<Generator>,
    private val unified: List<IntArray>,
    private val nInt: Int,
) : Propagator {

    /** A generator's lex sequence: position `k` compares [leftVar]`[k]` against its image
     *  [rightVar]`[k]`, both Boolean when [isBool]`[k]` (else both integer). */
    class Generator(val leftVar: IntArray, val rightVar: IntArray, val isBool: BooleanArray)

    /** Permutation degree (`nInt + nBool`). */
    private val n: Int = if (unified.isEmpty()) nInt else unified.first().size

    /** The group's support (variables some generator moves), as unified ids in ascending order — the
     *  fixed total order the orbital-fixing rule walks. */
    private val supportOrder: IntArray = run {
        val moved = HashSet<Int>()
        for (p in unified) for (i in p.indices) if (p[i] != i) moved.add(i)
        moved.toIntArray().also { it.sort() }
    }

    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        for (g in generators) if (!propagateLexLeader(state, g)) return false
        return orbitalFix(state)
    }

    /**
     * Orbital fixing: the lex-leader implies `x_k ≤ x_j` for every `j` in the orbit of `k` under the
     * pointwise stabiliser of the positions preceding `k` in the order — a group element that fixes
     * those positions auto-equalises the prefix, so position `k` decides `V ≤lex σ(V)`. Applied at the
     * order's first unfixed position (the frontier; later positions follow as search fixes this one),
     * this prunes using the whole group's stabiliser structure, not just the propagated generators —
     * the strength that survives beyond the bounded generator expansion on large groups. Sound by
     * construction (every orbit element comes from a genuine group element fixing the prefix).
     */
    @Suppress("ReturnCount")
    private fun orbitalFix(state: PropagationState): Boolean {
        if (unified.isEmpty()) return true
        val prefix = IntArrayList()
        for (k in supportOrder) {
            val kBool = k >= nInt
            val kVar = if (kBool) k - nInt else k
            if (lo(state, kVar, kBool) == hi(state, kVar, kBool)) {
                prefix.add(k)
                continue
            }
            val orbit = PermutationGroup.orbitUnderStabilizer(unified, prefix.toIntArray(), k, n)
            for (j in orbit) {
                if (j == k) continue
                val jBool = j >= nInt
                val jVar = if (jBool) j - nInt else j
                // x_k ≤ x_j: tighten x_k's max down to x_j's max, and x_j's min up to x_k's min.
                if (!tightenHi(
                        state,
                        kVar,
                        kBool,
                        hi(state, jVar, jBool),
                        boundReason(state, jVar, jBool),
                    )
                ) {
                    return false
                }
                if (!tightenLo(
                        state,
                        jVar,
                        jBool,
                        lo(state, kVar, kBool),
                        boundReason(state, kVar, kBool),
                    )
                ) {
                    return false
                }
            }
            return true // only the frontier position; deeper positions resolve as it fixes
        }
        return true
    }

    /** The premise of an `x ≤ y` bound transfer: the cited variable's current bound (an integer
     *  order-atom, or the Boolean's current literal). A superset is sound. */
    private fun boundReason(state: PropagationState, v: Int, isBool: Boolean): IntArray? = if (isBool) {
        val value = state.boolValues[v] ?: return null
        intArrayOf(Lit.make(v, !value))
    } else {
        state.composeIntVarAtomAntecedents(intArrayOf(v))
    }

    /**
     * The clause-form reason for a lex-leader conflict (`propagate` returned `false` on the
     * direct-conflict path): re-scan the generators, find the one whose fixed-equal prefix forces
     * `V >lex σ(V)` with no equal-tail rescue, and cite the prefix and the scanned suffix that
     * established it. Every solution surviving the symmetry break has `V ≤lex σ(V)`, so the clause is
     * entailed. `null` (chronological backtrack) when no generator is in that direct-conflict state —
     * a conflict reached only through a failed tightening already carries that pin's antecedents.
     */
    @Suppress("ReturnCount", "NestedBlockDepth")
    override fun conflictReason(state: PropagationState, factorId: Int): IntArray? {
        for (g in generators) {
            val len = g.leftVar.size
            var a = 0
            while (a < len) {
                val lLo = lo(state, g.leftVar[a], g.isBool[a])
                val lHi = hi(state, g.leftVar[a], g.isBool[a])
                val rLo = lo(state, g.rightVar[a], g.isBool[a])
                val rHi = hi(state, g.rightVar[a], g.isBool[a])
                if (lLo == lHi && rLo == rHi && lLo == rLo) a++ else break
            }
            if (a == len) continue
            if (hi(state, g.leftVar[a], g.isBool[a]) < lo(state, g.rightVar[a], g.isBool[a])) continue
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
            val betaInfinite = i == len
            if (!betaInfinite && b == -1) b = i
            if (!betaInfinite && b <= a) return reason(state, g, a, i, strictHere = true)
        }
        return null
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
