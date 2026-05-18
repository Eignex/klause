package com.eignex.klause.solver.backtrack

import com.eignex.klause.solver.propagation.PropagationSession
import kotlin.random.Random

/**
 * Which variable [BacktrackSolver] is branching on. Independent of value selection so var
 * and value strategies can be combined freely (mirroring MiniZinc's
 * `int_search(vars, var_strategy, value_strategy, complete)`).
 */
sealed interface VarRef {
    val varId: Int
    data class Bool(override val varId: Int) : VarRef
    data class IntVar(override val varId: Int) : VarRef
}

/**
 * Picks the next variable to branch on. Returns `null` when every variable is determined.
 *
 * The optional notification hooks ([onConflict], [onCommit], [onRestart]) let activity-,
 * conflict-, or weight-driven heuristics (VSIDS, dom/wdeg, last-conflict, impact-based)
 * accumulate state across the search without smuggling listeners through the engine.
 * Pure heuristics (random, smallest-domain, input-order) ignore them via the defaults.
 */
interface VariableHeuristic {
    fun pick(session: PropagationSession, rng: Random): VarRef?
    /** Called once per propagation conflict at [varRef]; bump activity / failure weight. */
    fun onConflict(varRef: VarRef) {}
    /** Called once per successful pin of [varRef]; useful for phase-saving-like state. */
    fun onCommit(varRef: VarRef) {}
    /** Called when the engine restarts (Luby / geometric); decay activity or reset
     *  per-run counters here. */
    fun onRestart() {}
}

/**
 * Picks the order of values to try for a chosen variable. Returns a `Sequence` so iteration
 * is lazy — for bool vars the sequence is at most 2 elements; for int vars at most
 * `domain.size`. The engine pops each yielded value into the session in order; on conflict
 * it advances to the next.
 *
 * For bool vars, the int values are `0` (false) and `1` (true).
 *
 * Notification hooks parallel [VariableHeuristic]'s, scoped to the (var, value) pair
 * that the engine actually attempted. Impact-based value selection and solution-guided
 * heuristics consume these.
 */
interface ValueHeuristic {
    fun values(session: PropagationSession, varRef: VarRef, rng: Random): Sequence<Int>
    fun onConflict(varRef: VarRef, value: Int) {}
    fun onCommit(varRef: VarRef, value: Int) {}
    fun onRestart() {}
}

// ---- Variable heuristics ---------------------------------------------------------------

/** First unpinned bool, else first int with domain size > 1, in variable-id order. */
object InputOrder : VariableHeuristic {
    override fun pick(session: PropagationSession, rng: Random): VarRef? {
        val problem = session.problem
        for (v in 0 until problem.numBoolVars) {
            if (session.boolValue(v) == null) return VarRef.Bool(v)
        }
        for (v in 0 until problem.numIntVars) {
            if (session.intDomain(v).size > 1) return VarRef.IntVar(v)
        }
        return null
    }
}

/**
 * "First-fail": smallest current domain wins. Bools count as size 2 when unpinned. Tied
 * candidates are broken by variable id (bools precede ints). The classic CSP default.
 */
object SmallestDomain : VariableHeuristic {
    override fun pick(session: PropagationSession, rng: Random): VarRef? {
        var best: VarRef? = null
        var bestSize = Int.MAX_VALUE
        val problem = session.problem
        for (v in 0 until problem.numBoolVars) {
            if (session.boolValue(v) == null && 2 < bestSize) {
                best = VarRef.Bool(v); bestSize = 2
            }
        }
        for (v in 0 until problem.numIntVars) {
            val size = session.intDomain(v).size
            if (size > 1 && size < bestSize) {
                best = VarRef.IntVar(v); bestSize = size
            }
        }
        return best
    }
}

/** Largest current domain. Useful as a contrast / for `solve` annotations that ask for it. */
object LargestDomain : VariableHeuristic {
    override fun pick(session: PropagationSession, rng: Random): VarRef? {
        var best: VarRef? = null
        var bestSize = 1
        val problem = session.problem
        for (v in 0 until problem.numBoolVars) {
            if (session.boolValue(v) == null && 2 > bestSize) {
                best = VarRef.Bool(v); bestSize = 2
            }
        }
        for (v in 0 until problem.numIntVars) {
            val size = session.intDomain(v).size
            if (size > bestSize) {
                best = VarRef.IntVar(v); bestSize = size
            }
        }
        return best
    }
}

/** Uniformly random among undetermined variables. */
object RandomVariable : VariableHeuristic {
    override fun pick(session: PropagationSession, rng: Random): VarRef? {
        val problem = session.problem
        val candidates = ArrayList<VarRef>()
        for (v in 0 until problem.numBoolVars) {
            if (session.boolValue(v) == null) candidates.add(VarRef.Bool(v))
        }
        for (v in 0 until problem.numIntVars) {
            if (session.intDomain(v).size > 1) candidates.add(VarRef.IntVar(v))
        }
        if (candidates.isEmpty()) return null
        return candidates[rng.nextInt(candidates.size)]
    }
}

// ---- Value heuristics ------------------------------------------------------------------

/** Smallest value first (a.k.a. `indomain_min`). For bools: `false` then `true`. */
object IndomainMin : ValueHeuristic {
    override fun values(session: PropagationSession, varRef: VarRef, rng: Random): Sequence<Int> =
        when (varRef) {
            is VarRef.Bool -> sequenceOf(0, 1)
            is VarRef.IntVar -> {
                val d = session.intDomain(varRef.varId)
                (d.min..d.max).asSequence()
            }
        }
}

/** Largest value first (`indomain_max`). For bools: `true` then `false`. */
object IndomainMax : ValueHeuristic {
    override fun values(session: PropagationSession, varRef: VarRef, rng: Random): Sequence<Int> =
        when (varRef) {
            is VarRef.Bool -> sequenceOf(1, 0)
            is VarRef.IntVar -> {
                val d = session.intDomain(varRef.varId)
                (d.max downTo d.min).asSequence()
            }
        }
}

/**
 * Value closest to the domain midpoint first, then alternating outward (`indomain_middle`).
 * Useful when the SAT distribution clusters around the middle of the domain.
 */
object IndomainMiddle : ValueHeuristic {
    override fun values(session: PropagationSession, varRef: VarRef, rng: Random): Sequence<Int> =
        when (varRef) {
            is VarRef.Bool -> sequenceOf(0, 1)
            is VarRef.IntVar -> {
                val d = session.intDomain(varRef.varId)
                val mid = d.min + d.size / 2
                sequence {
                    yield(mid)
                    var off = 1
                    while (mid - off >= d.min || mid + off <= d.max) {
                        if (mid + off <= d.max) yield(mid + off)
                        if (mid - off >= d.min) yield(mid - off)
                        off++
                    }
                }
            }
        }
}

/** Uniformly random shuffle of the domain (`indomain_random`). */
object IndomainRandom : ValueHeuristic {
    override fun values(session: PropagationSession, varRef: VarRef, rng: Random): Sequence<Int> =
        when (varRef) {
            is VarRef.Bool -> if (rng.nextBoolean()) sequenceOf(1, 0) else sequenceOf(0, 1)
            is VarRef.IntVar -> {
                val d = session.intDomain(varRef.varId)
                val list = (d.min..d.max).toMutableList()
                list.shuffle(rng)
                list.asSequence()
            }
        }
}

/**
 * Allow-list value selection: tries only [allowedValues] (in order) regardless of the
 * current domain. Useful when constraints have carved holes in a contiguous [IntDomain]
 * range — skipping the forbidden values up-front avoids the per-hole conflict-propagate
 * round.
 */
class IndomainSet(private val allowedValues: IntArray) : ValueHeuristic {
    override fun values(session: PropagationSession, varRef: VarRef, rng: Random): Sequence<Int> =
        when (varRef) {
            is VarRef.Bool -> allowedValues.asSequence().filter { it == 0 || it == 1 }
            is VarRef.IntVar -> {
                val d = session.intDomain(varRef.varId)
                allowedValues.asSequence().filter { it in d.min..d.max }
            }
        }
}
