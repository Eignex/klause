package com.eignex.klause.presolve

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Lit
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.Sample
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.IntHashSet

/**
 * A mutable clause database over the pure-SAT part of a `Problem` — the shared substrate for the
 * SAT-part presolve passes (bounded variable elimination, blocked-clause elimination, and future
 * clique / subsumption work). It holds only the clean all-Boolean [Clause]s (`allLiteralsBool`);
 * every other factor is left untouched, and any Boolean variable those factors touch is marked
 * ineligible so the passes never rewrite a variable they cannot reconstruct.
 *
 * Clauses live in slots (`clause(s)` is `null` once removed); [add] appends a derived clause and
 * [remove] retires one. A per-literal occurrence index ([occ]) maps each Lit to the slots that
 * mention it — the query at the heart of every SAT-preprocessing technique — with removed slots
 * skipped lazily on read. Tautological input clauses are always true and recorded as vacuous drops
 * rather than entered.
 *
 * [eligible]`[v]` is true when Boolean variable `v` is safe for a pass to eliminate or flip: it is
 * objective-free and appears **only** in clean clauses, so setting it during reconstruction affects
 * nothing but this database. Eligibility is computed by scanning all factors directly (not the
 * deductive occurrence index, which omits invariant-only factors), so an LS-only factor cannot make a
 * variable look pure.
 */
internal class SatClauseDb private constructor(
    val numBoolVars: Int,
    val eligible: BooleanArray,
    private val slotLits: ArrayList<IntArray?>,
    private val slotOrig: IntArrayList,
    private val litOcc: Array<IntArrayList>,
    private val vacuous: IntArray,
) {
    /** Number of slots ever created (live or removed); slot ids are stable in `0 until slotCount`. */
    val slotCount: Int get() = slotLits.size

    /** The literals of slot [s], or `null` if the slot has been removed. */
    fun clause(s: Int): IntArray? = slotLits[s]

    /** The input factor index of slot [s], or `-1` for a clause [add]ed during the pass. */
    fun origin(s: Int): Int = slotOrig[s]

    /** The slot ids that mention literal [lit] (some may be removed — check [clause]). */
    fun occ(lit: Int): IntArrayList = litOcc[lit]

    /** Retire slot [s]. */
    fun remove(s: Int) {
        slotLits[s] = null
    }

    /** Append a derived clause and index it; returns its slot id. */
    fun add(lits: IntArray): Int {
        val s = slotLits.size
        slotLits.add(lits)
        slotOrig.add(-1)
        for (l in lits) litOcc[l].add(s)
        return s
    }

    /**
     * The pass delta: tautological and consumed input clauses dropped, surviving derived clauses added.
     * An input clause that survives unchanged is kept (absent from the delta); a derived clause that was
     * itself later removed contributes nothing.
     */
    fun toDelta(reconstruct: ((Sample) -> Sample)?): PassDelta {
        val dropped = IntArrayList()
        for (v in vacuous) dropped.add(v)
        val added = ArrayList<Factor>()
        for (s in 0 until slotLits.size) {
            val lits = slotLits[s]
            val orig = slotOrig[s]
            when {
                lits == null && orig >= 0 -> dropped.add(orig)
                lits != null && orig < 0 -> added.add(Clause(lits))
            }
        }
        if (dropped.isEmpty() && added.isEmpty()) return PassDelta()
        return PassDelta(droppedIndices = dropped.toIntArray(), addedFactors = added, reconstruct = reconstruct)
    }

    companion object {
        /** A clause containing a literal and its negation — always true. */
        fun isTautology(lits: IntArray): Boolean {
            val seen = IntHashSet(lits.size)
            for (l in lits) {
                if (Lit.negate(l) in seen) return true
                seen.add(l)
            }
            return false
        }

        private fun isCleanClause(f: Factor, nb: Int): Boolean = f is Clause && f.allLiteralsBool(nb)

        /** Build the database from [problem]'s clean clauses, protecting [objectiveBoolVars]. */
        fun build(problem: Problem, objectiveBoolVars: Set<Int>): SatClauseDb {
            val nb = problem.numBoolVars
            val factors = problem.factors

            val eligible = BooleanArray(nb) { it !in objectiveBoolVars }
            for (f in factors) {
                if (isCleanClause(f, nb)) continue
                for (w in f.boolVars) if (w in 0 until nb) eligible[w] = false
            }

            val slotLits = ArrayList<IntArray?>()
            val slotOrig = IntArrayList()
            val vacuous = IntArrayList()
            for (i in factors.indices) {
                val f = factors[i]
                if (!isCleanClause(f, nb)) continue
                val lits = (f as Clause).literals
                if (isTautology(lits)) {
                    vacuous.add(i)
                } else {
                    slotLits.add(lits)
                    slotOrig.add(i)
                }
            }
            val litOcc = Array(2 * nb) { IntArrayList() }
            for (s in slotLits.indices) {
                val lits = slotLits[s] ?: continue
                for (l in lits) if (Lit.variable(l) < nb) litOcc[l].add(s)
            }
            return SatClauseDb(nb, eligible, slotLits, slotOrig, litOcc, vacuous.toIntArray())
        }
    }
}
