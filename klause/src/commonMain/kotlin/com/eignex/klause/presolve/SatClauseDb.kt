package com.eignex.klause.presolve

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.collections.IntArrayList

// Mutable clause database over the clean all-Boolean clauses of a problem, shared by bounded variable
// elimination and blocked-clause elimination. Removed slots stay in [occ] and are skipped lazily on read;
// tautological input clauses are recorded as vacuous drops.
// `eligible(v)`: objective-free and only in clean clauses, so a pass may eliminate or flip `v` without
// affecting anything outside this database. It scans all factors, not the deductive occurrence index
// (which omits invariant-only factors), so an LS-only factor cannot make a variable look pure.
internal class SatClauseDb private constructor(
    val numBoolVars: Int,
    val eligible: BooleanArray,
    private val slotLits: ArrayList<IntArray?>,
    private val slotOrig: IntArrayList,
    private val litOcc: Array<IntArrayList>,
    private val vacuous: IntArray,
) {
    // Live and removed; slot ids are stable.
    val slotCount: Int get() = slotLits.size

    // Null once removed.
    fun clause(s: Int): IntArray? = slotLits[s]

    // -1 for a clause added during the pass.
    fun origin(s: Int): Int = slotOrig[s]

    // May include removed slots.
    fun occ(lit: Int): IntArrayList = litOcc[lit]

    fun remove(s: Int) {
        slotLits[s] = null
    }

    fun add(lits: IntArray): Int {
        val s = slotLits.size
        slotLits.add(lits)
        slotOrig.add(-1)
        for (l in lits) litOcc[l].add(s)
        return s
    }

    fun toDelta(rebuild: SourceRebuilds): PassDelta {
        val (dropped, added) = rewrite()
        if (dropped.isEmpty() && added.isEmpty()) return PassDelta()
        return PassDelta(droppedIndices = dropped, addedFactors = added, reconstruct = rebuild.asSampleLift())
            .also { it.rebuild = rebuild }
    }

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
        // No variable is eligible for elimination.
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

        fun build(
            problem: Problem,
            objectiveBoolVars: Set<Int>,
            cancellation: ExecutionControl = ExecutionControl.Never,
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

        private fun visit(cancellation: ExecutionControl): Boolean {
            cancellation.charge(SAT_PREPARATION_WORK_WEIGHT)
            return !cancellation()
        }
    }
}

private const val SAT_PREPARATION_WORK_WEIGHT = 10L
