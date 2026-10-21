package com.eignex.klause.presolve

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.IntArrayList

/**
 * A mutable clause database over the pure-SAT part of a `Problem`, shared by bounded variable elimination
 * and blocked-clause elimination. It holds only clean all-Boolean [Clause]s (`allLiteralsBool`); every
 * other factor is left untouched, and any Boolean variable those factors touch is marked ineligible so the
 * passes never rewrite a variable they cannot reconstruct.
 *
 * Clauses live in slots (`clause(s)` is `null` once removed). A per-literal occurrence index ([occ]) maps
 * each literal to the slots that mention it, with removed slots skipped lazily on read. Tautological input
 * clauses are recorded as vacuous drops rather than entered.
 *
 * `eligible(v)` is true when Boolean variable `v` is safe for a pass to eliminate or flip: it is
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
    fun toDelta(rebuild: SourceRebuilds): PassDelta {
        val (dropped, added) = rewrite()
        if (dropped.isEmpty() && added.isEmpty()) return PassDelta()
        return PassDelta(droppedIndices = dropped, addedFactors = added, reconstruct = rebuild.asSampleLift())
            .also { it.rebuild = rebuild }
    }

    /** The same rewrite as the source lane's delta, stating [rebuild] as data rather than as a lift. */
    fun toSourceDelta(rebuild: SourceRebuilds): SourceDelta {
        val (dropped, added) = rewrite()
        if (dropped.isEmpty() && added.isEmpty()) return SourceDelta()
        return SourceDelta(droppedIndices = dropped, addedFactors = added, rebuild = rebuild)
    }

    private fun rewrite(): Pair<IntArray, List<Factor>> {
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
        return dropped.toIntArray() to added
    }

    companion object {
        /** Index only bounded-width original clauses; no variable is eligible for elimination. */
        fun buildShortClauses(problem: Problem, maxWidth: Int, visit: () -> Boolean): SatClauseDb? {
            if (!visit()) return null
            val slots = ArrayList<IntArray?>()
            val origins = IntArrayList()
            val occurrences = Array(2 * problem.numBoolVars) {
                if (!visit()) return null
                IntArrayList()
            }
            for (i in problem.factors.indices) {
                if (!visit()) return null
                val clause = problem.factors[i] as? Clause ?: continue
                val literals = clause.literals
                if (literals.size > maxWidth || clause.tautological) continue
                var clean = true
                for (literal in literals) {
                    if (!visit()) return null
                    if (Lit.variable(literal) >= problem.numBoolVars) clean = false
                }
                if (!clean) continue
                val slot = slots.size
                slots.add(literals)
                origins.add(i)
                for (literal in literals) {
                    if (!visit()) return null
                    occurrences[literal].add(slot)
                }
            }
            if (!visit()) return null
            return SatClauseDb(
                problem.numBoolVars,
                BooleanArray(problem.numBoolVars),
                slots,
                origins,
                occurrences,
                intArrayOf(),
            )
        }

        /** Build the database from [problem]'s clean clauses, protecting [objectiveBoolVars]. */
        fun build(
            problem: Problem,
            objectiveBoolVars: Set<Int>,
            cancellation: Cancellation = Cancellation.Never,
        ): SatClauseDb? {
            if (cancellation()) return null
            val nb = problem.numBoolVars
            val eligible = BooleanArray(nb) {
                if (!visit(cancellation)) return null
                it !in objectiveBoolVars
            }
            val litOcc = Array(2 * nb) {
                if (!visit(cancellation)) return null
                IntArrayList()
            }
            val slotLits = ArrayList<IntArray?>()
            val slotOrig = IntArrayList()
            val vacuous = IntArrayList()
            for (i in problem.factors.indices) {
                if (!visit(cancellation)) return null
                val factor = problem.factors[i]
                val clause = factor as? Clause
                var clean = clause != null
                if (clause != null) {
                    for (literal in clause.literals) {
                        if (!visit(cancellation)) return null
                        if (Lit.variable(literal) >= nb) clean = false
                    }
                }
                if (!clean) {
                    for (variable in factor.boolVars) {
                        if (!visit(cancellation)) return null
                        if (variable in 0 until nb) eligible[variable] = false
                    }
                    continue
                }
                checkNotNull(clause)
                if (!visit(cancellation)) return null
                if (clause.tautological) {
                    vacuous.add(i)
                    continue
                }
                val slot = slotLits.size
                slotLits.add(clause.literals)
                slotOrig.add(i)
                for (literal in clause.literals) {
                    if (!visit(cancellation)) return null
                    litOcc[literal].add(slot)
                }
            }
            if (cancellation()) return null
            return SatClauseDb(nb, eligible, slotLits, slotOrig, litOcc, vacuous.toIntArray())
        }

        private fun visit(cancellation: Cancellation): Boolean {
            cancellation.charge(SAT_PREPARATION_WORK_WEIGHT)
            return !cancellation()
        }
    }
}

private const val SAT_PREPARATION_WORK_WEIGHT = 10L
