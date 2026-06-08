package com.eignex.klause.solver.backtrack

import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.LinearObjective
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.propagation.PropagationResult
import com.eignex.klause.solver.propagation.PropagationResult.Unsat
import com.eignex.klause.solver.propagation.PropagationSession
import com.eignex.klause.util.IndexedMaxHeap
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.IntHashSet
import kotlin.math.abs
import kotlin.math.ln
import kotlin.random.Random

/**
 * Which variable [BacktrackSolver] is branching on. Independent of value selection so var
 * and value strategies can be combined freely (mirroring MiniZinc's
 * `int_search(vars, var_strategy, value_strategy, complete)`).
 */
sealed interface VarRef {
    /** The referenced variable id. */
    val varId: Int

    /** A Boolean variable reference. */
    data class Bool(override val varId: Int) : VarRef

    /** An integer variable reference. */
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
    /** Pick the next variable to branch on, or null when all are determined. */
    fun pick(session: PropagationSession, rng: Random): VarRef?

    /** Called once per propagation conflict at [varRef]; bump activity / failure weight. */
    fun onConflict(varRef: VarRef) {}

    /** Called once per SAT leaf reached by the search. Solution-guided heuristics snapshot
     *  the assignment here so they can bias future picks toward it. Default no-op. */
    fun onSolution(snapshot: Sample) {}

    /**
     * Richer conflict notification: [varRef] is the decision that triggered the conflict,
     * [unsat] carries the full reason set (decision variables, decision levels, contributing
     * factor ids) the propagation engine assembled. VSIDS reads `conflictBools` /
     * `conflictInts`; dom/wdeg reads `conflictFactors`; impact-style heuristics could read
     * `conflictLevels` to score depth. Default forwards to [onConflict] (varRef only) so
     * pure / activity-agnostic heuristics ignore the extra info transparently.
     */
    fun onConflict(varRef: VarRef, unsat: PropagationResult.Unsat) {
        onConflict(varRef)
    }

    /** Called once per successful pin of [varRef]; useful for phase-saving-like state. */
    fun onCommit(varRef: VarRef) {}

    /**
     * Called after every successful propagation step (pin + fixpoint). [implied] carries
     * the variables newly forced into singletons during this step. Activity-Based Search
     * (Michel-Van Hentenryck 2012) bumps per-variable activity here; pure / activity-
     * agnostic heuristics ignore. Default no-op.
     */
    fun onPropagation(implied: PropagationResult.Implied) {}

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
    /** Candidate values for [varRef], yielded in trial order. */
    fun values(session: PropagationSession, varRef: VarRef, rng: Random): Sequence<Int>

    /** Hook: a conflict involved [varRef] taking [value]. */
    fun onConflict(varRef: VarRef, value: Int) {}

    /** Hook: [varRef] was committed to [value]. */
    fun onCommit(varRef: VarRef, value: Int) {}

    /** Hook: the search restarted. */
    fun onRestart() {}

    /** Called once per SAT leaf reached by the search. Solution-guided heuristics snapshot
     *  the assignment here so they can bias future picks toward it. Default no-op. */
    fun onSolution(snapshot: Sample) {}
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
                best = VarRef.Bool(v)
                bestSize = 2
            }
        }
        for (v in 0 until problem.numIntVars) {
            val size = session.intDomain(v).size
            if (size > 1 && size < bestSize) {
                best = VarRef.IntVar(v)
                bestSize = size
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
                best = VarRef.Bool(v)
                bestSize = 2
            }
        }
        for (v in 0 until problem.numIntVars) {
            val size = session.intDomain(v).size
            if (size > bestSize) {
                best = VarRef.IntVar(v)
                bestSize = size
            }
        }
        return best
    }
}

/**
 * Smallest lower bound first (MiniZinc's `smallest`): the free variable whose domain
 * minimum is lowest. Free bools count as minimum 0. Ties broken by variable id, bools
 * before ints. The scheduling staple — branching on the task that can start earliest.
 */
object SmallestLowerBound : VariableHeuristic {
    override fun pick(session: PropagationSession, rng: Random): VarRef? {
        var best: VarRef? = null
        var bestLb = Int.MAX_VALUE
        val problem = session.problem
        for (v in 0 until problem.numBoolVars) {
            if (session.boolValue(v) == null && 0 < bestLb) {
                best = VarRef.Bool(v)
                bestLb = 0
            }
        }
        for (v in 0 until problem.numIntVars) {
            val d = session.intDomain(v)
            if (d.size > 1 && d.min < bestLb) {
                best = VarRef.IntVar(v)
                bestLb = d.min
            }
        }
        return best
    }
}

/** Largest upper bound first (MiniZinc's `largest`). Free bools count as maximum 1. */
object LargestUpperBound : VariableHeuristic {
    override fun pick(session: PropagationSession, rng: Random): VarRef? {
        var best: VarRef? = null
        var bestUb = Int.MIN_VALUE
        val problem = session.problem
        for (v in 0 until problem.numBoolVars) {
            if (session.boolValue(v) == null && 1 > bestUb) {
                best = VarRef.Bool(v)
                bestUb = 1
            }
        }
        for (v in 0 until problem.numIntVars) {
            val d = session.intDomain(v)
            if (d.size > 1 && d.max > bestUb) {
                best = VarRef.IntVar(v)
                bestUb = d.max
            }
        }
        return best
    }
}

/**
 * Variable State Independent Decaying Sum (Moskewicz et al., Chaff 2001 / MiniSAT). The
 * activity counter for each variable is bumped on every conflict the variable is implicated
 * in, with the bump amount `increment` growing geometrically over time so recent conflicts
 * dominate. Equivalent to per-bump multiplicative decay by a factor of [decay] applied
 * uniformly to every prior activity, but cheaper (we only mutate the increment, not every
 * activity entry). Periodically rescales when `increment` approaches [Double] overflow.
 *
 * Picks the unpinned variable with the highest activity. Ties broken by variable-id order
 * (bools precede ints). Activities persist across [onRestart] — that's the whole point of
 * VSIDS, learning carries over.
 *
 * Pre-LCG limitation: today the engine's [Unsat]
 * carries the *decision variables* at conflict levels, not every variable on the
 * propagation-reason path. Bumping decision variables only gives a useful (if coarser)
 * signal — still consistently better than random / smallest-domain on hard instances. When
 * full conflict-graph attribution lands (alongside [no-good / lazy clause learning]) this
 * heuristic will see the richer set automatically through [onConflict].
 *
 * Defaults follow MiniSAT: `decay = 0.95`. Lower decay (e.g., 0.8) makes the heuristic more
 * aggressive about following recent conflicts; higher (e.g., 0.99) is more conservative.
 */
class Vsids(private val decay: Double = 0.95, private val rescaleThreshold: Double = 1e100) : VariableHeuristic {

    init {
        require(decay in 0.5..0.999) { "VSIDS decay must be in 0.5..0.999, got $decay" }
    }

    private var increment: Double = 1.0

    // Combined index space: 0..numBool-1 are bool ids; numBool..numBool+numInt-1 are int ids
    // offset by numBool. One indexed max-heap over both gives O(log n) bumps and amortised
    // O((1 + pinned-skip) · log n) picks instead of the O(numBool + numInt) linear scan the
    // hand-rolled loop did.
    private var heap: IndexedMaxHeap? = null
    private var numBoolCached: Int = 0
    private var numIntCached: Int = 0

    // Scratch buffer for picks: ids extracted but rejected because pinned. Restored at the
    // end of pick() so the heap stays complete across calls. Field rather than local so it
    // doesn't re-allocate per pick.
    private val pickSkipBuffer = IntArrayList(16)

    private fun ensureSized(numBool: Int, numInt: Int) {
        if (heap != null && numBoolCached == numBool && numIntCached == numInt) return
        val h = IndexedMaxHeap(numBool + numInt)
        for (i in 0 until numBool + numInt) h.insert(i, 0.0)
        heap = h
        numBoolCached = numBool
        numIntCached = numInt
    }

    override fun pick(session: PropagationSession, rng: Random): VarRef? {
        val problem = session.problem
        ensureSized(problem.numBoolVars, problem.numIntVars)
        val h = requireNotNull(heap)
        pickSkipBuffer.clear()
        var result: VarRef? = null
        while (h.size > 0) {
            val id = h.extractMax()
            if (id < numBoolCached) {
                if (session.boolValue(id) == null) {
                    result = VarRef.Bool(id)
                    break
                }
            } else {
                val intId = id - numBoolCached
                if (session.intDomain(intId).size > 1) {
                    result = VarRef.IntVar(intId)
                    break
                }
            }
            pickSkipBuffer.add(id)
        }
        // Restore every popped-but-pinned id (and the winner — it stays in the heap so
        // subsequent bumps can find it) at their stored keys. Pinned ones become candidates
        // again once propagation backtracks past their pin point.
        if (result != null) {
            when (result) {
                is VarRef.Bool -> h.restore(result.varId)
                is VarRef.IntVar -> h.restore(result.varId + numBoolCached)
            }
        }
        for (i in 0 until pickSkipBuffer.size) h.restore(pickSkipBuffer.get(i))
        return result
    }

    override fun onConflict(varRef: VarRef, unsat: PropagationResult.Unsat) {
        val h = heap ?: return
        // Bump every decision variable in the conflict reason set, plus the failed
        // decision itself (in case it isn't already in the set — typically it is).
        for (b in unsat.conflictBools) bumpBool(h, b)
        for (i in unsat.conflictInts) bumpInt(h, i)
        when (varRef) {
            is VarRef.Bool -> bumpBool(h, varRef.varId)
            is VarRef.IntVar -> bumpInt(h, varRef.varId)
        }
        // Grow the increment for the next conflict — implicit multiplicative decay of
        // every prior activity by `decay`, without touching the keys.
        increment /= decay
        if (increment > rescaleThreshold) rescaleAll(h)
    }

    private fun bumpBool(h: IndexedMaxHeap, v: Int) {
        if (v >= numBoolCached) return
        h.updateKey(v, h.keyOf(v) + increment)
    }

    private fun bumpInt(h: IndexedMaxHeap, v: Int) {
        if (v >= numIntCached) return
        val id = numBoolCached + v
        h.updateKey(id, h.keyOf(id) + increment)
    }

    /** Scale every activity (and `increment`) by `1/rescaleThreshold` so the relative
     *  ordering is preserved but we step well clear of `Double.MAX_VALUE`. Standard MiniSAT
     *  rescale; the heap's [IndexedMaxHeap.scaleKeys] applies the uniform factor without
     *  resifting. */
    private fun rescaleAll(h: IndexedMaxHeap) {
        val k = 1.0 / rescaleThreshold
        h.scaleKeys(k)
        increment *= k
    }
}

/**
 * Conflict-History-Based branching (Liang-Ganesh-Poupart-Czarnecki, "Learning Rate Based
 * Branching Heuristics for SAT Solvers", SAT 2016) in its ERWA (exponential recency
 * weighted average) form. A drop-in [VariableHeuristic] alternative to [Vsids] for
 * SAT-heavy configs: as cheap to maintain, and frequently stronger on structured SAT.
 *
 * Each variable carries a score `Q[v]`, updated by exponential recency weighting on every
 * event the variable participates in:
 *     Q[v] ← (1 − α)·Q[v] + α·r,   r = multiplier / (conflicts − lastConflict[v] + 1)
 * The reward `r` is *larger* the more recently `v` took part in a conflict (a smaller
 * denominator), and `multiplier` is [conflictReward] (1.0) for the variables in the conflict
 * reason set and [assignReward] (0.9) for variables merely assigned along the way — the
 * standard CHB split that values conflict participation above plain propagation. The step
 * size α starts at [alphaStart] and decays by [alphaStep] per conflict down to [alphaFloor],
 * so early conflicts move scores fast and the estimate settles as evidence accumulates —
 * "the learning rate decaying as conflicts accumulate" the issue asks for.
 *
 * Picks the unpinned variable with the highest `Q`. Ties broken by variable-id order (bools
 * precede ints). Scores persist across [onRestart] — like VSIDS, the learned ordering is the
 * point. Uses the same combined-id [IndexedMaxHeap] and pinned-skip pick machinery as
 * [Vsids], so it drops into the [BacktrackParams.variableHeuristic] slot with no engine
 * changes.
 *
 * Hook mapping (exactly the hooks VSIDS already consumes):
 *  - [onConflict] — advances the conflict counter, decays α, and bumps every variable in the
 *    conflict reason set (and the failed decision) with the conflict multiplier; those
 *    variables' `lastConflict` is set to the current count, so their reward denominator is 1.
 *  - [onPropagation] / [onCommit] — assigned-variable reward, recency-discounted by how long
 *    ago the variable last appeared in a conflict.
 *  - [onRestart] — no-op; scores persist.
 *
 * Since every `Q` is a convex combination of rewards in `[0, 1]`, scores stay in `[0, 1]` and
 * never need rescaling (unlike VSIDS's growing increment).
 */
class Chb(
    private val alphaStart: Double = 0.4,
    private val alphaFloor: Double = 0.06,
    private val alphaStep: Double = 1e-6,
    private val conflictReward: Double = 1.0,
    private val assignReward: Double = 0.9,
) : VariableHeuristic {

    init {
        require(alphaStart in alphaFloor..1.0) { "CHB alphaStart must be in alphaFloor..1.0, got $alphaStart" }
        require(alphaFloor in 0.0..1.0) { "CHB alphaFloor must be in 0.0..1.0, got $alphaFloor" }
        require(alphaStep >= 0.0) { "CHB alphaStep must be >= 0, got $alphaStep" }
    }

    private var alpha: Double = alphaStart
    private var conflicts: Long = 0

    // Combined index space: 0..numBool-1 are bool ids; numBool..numBool+numInt-1 are int ids
    // offset by numBool. Mirrors [Vsids] exactly so picks are O((1 + pinned-skip)·log n).
    private var heap: IndexedMaxHeap? = null
    private var lastConflict: LongArray = LongArray(0)
    private var numBoolCached: Int = 0
    private var numIntCached: Int = 0
    private val pickSkipBuffer = IntArrayList(16)

    private fun ensureSized(numBool: Int, numInt: Int) {
        if (heap != null && numBoolCached == numBool && numIntCached == numInt) return
        val n = numBool + numInt
        val h = IndexedMaxHeap(n)
        for (i in 0 until n) h.insert(i, 0.0)
        heap = h
        lastConflict = LongArray(n)
        numBoolCached = numBool
        numIntCached = numInt
    }

    override fun pick(session: PropagationSession, rng: Random): VarRef? {
        val problem = session.problem
        ensureSized(problem.numBoolVars, problem.numIntVars)
        val h = requireNotNull(heap)
        pickSkipBuffer.clear()
        var result: VarRef? = null
        while (h.size > 0) {
            val id = h.extractMax()
            if (id < numBoolCached) {
                if (session.boolValue(id) == null) {
                    result = VarRef.Bool(id)
                    break
                }
            } else {
                val intId = id - numBoolCached
                if (session.intDomain(intId).size > 1) {
                    result = VarRef.IntVar(intId)
                    break
                }
            }
            pickSkipBuffer.add(id)
        }
        if (result != null) {
            when (result) {
                is VarRef.Bool -> h.restore(result.varId)
                is VarRef.IntVar -> h.restore(result.varId + numBoolCached)
            }
        }
        for (i in 0 until pickSkipBuffer.size) h.restore(pickSkipBuffer.get(i))
        return result
    }

    override fun onConflict(varRef: VarRef, unsat: PropagationResult.Unsat) {
        val h = heap ?: return
        conflicts++
        for (b in unsat.conflictBools) bumpConflict(h, idOfBool(b))
        for (i in unsat.conflictInts) bumpConflict(h, idOfInt(i))
        when (varRef) {
            is VarRef.Bool -> bumpConflict(h, idOfBool(varRef.varId))
            is VarRef.IntVar -> bumpConflict(h, idOfInt(varRef.varId))
        }
        // Decay the learning rate toward the floor as conflicts accumulate.
        if (alpha > alphaFloor) alpha = (alpha - alphaStep).coerceAtLeast(alphaFloor)
    }

    override fun onPropagation(implied: PropagationResult.Implied) {
        val h = heap ?: return
        implied.forEachBool { id, _ -> if (id < numBoolCached) bumpAssign(h, id) }
        implied.forEachInt { id, _ -> if (id < numIntCached) bumpAssign(h, numBoolCached + id) }
    }

    override fun onCommit(varRef: VarRef) {
        val h = heap ?: return
        when (varRef) {
            is VarRef.Bool -> bumpAssign(h, idOfBool(varRef.varId))
            is VarRef.IntVar -> bumpAssign(h, idOfInt(varRef.varId))
        }
    }

    private fun idOfBool(v: Int): Int = if (v < numBoolCached) v else -1
    private fun idOfInt(v: Int): Int = if (v < numIntCached) numBoolCached + v else -1

    /** Conflict-side reward: the variable just took part in a conflict, so its recency
     *  denominator collapses to 1 and it earns the full [conflictReward]. */
    private fun bumpConflict(h: IndexedMaxHeap, id: Int) {
        if (id < 0) return
        lastConflict[id] = conflicts
        updateQ(h, id, conflictReward)
    }

    /** Assignment-side reward: recency-discounted by how long ago the variable last appeared
     *  in a conflict. Leaves `lastConflict` untouched. */
    private fun bumpAssign(h: IndexedMaxHeap, id: Int) {
        if (id < 0) return
        val recency = (conflicts - lastConflict[id] + 1).toDouble()
        updateQ(h, id, assignReward / recency)
    }

    private fun updateQ(h: IndexedMaxHeap, id: Int, reward: Double) {
        h.updateKey(id, (1.0 - alpha) * h.keyOf(id) + alpha * reward)
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

/**
 * Domain-over-weighted-degree (Boussemart-Hemery-Lecoutre-Sais 2004). Maintains a
 * per-factor *failure weight* that's bumped every time the factor participates in a
 * conflict — read off [PropagationResult.Unsat.conflictFactors], which carries the full
 * propagation-graph attribution shipped earlier. The variable score is then
 *     wdeg(v) / dom(v)
 * where `wdeg(v) = Σ factor_weights[f]` over every factor mentioning `v`. Pick the
 * variable with the highest score; first-fail with a conflict-driven prior on which
 * constraints have proven hard so far.
 *
 * Compared to [Vsids]: dom/wdeg attributes "hardness" to *constraints* (so two variables
 * touched by the same hard constraint are both prioritised), while VSIDS attributes
 * directly to *variables*. Empirically dom/wdeg wins on CSPs with structured propagators;
 * VSIDS wins on SAT-style problems. They compose — a portfolio bandit can pick between
 * them per restart.
 *
 * Weights persist across [onRestart]; that's intentional, mirroring VSIDS's persistence.
 * Resizes the factor-weights array across problems for instance reuse.
 */
internal class DomWdeg : VariableHeuristic {

    private var factorWeights: DoubleArray = DoubleArray(0)

    // Combined bool+int heap keyed on `wdeg(v) = Σ factorWeights[f]`; the dom(v) divider
    // is applied at pick time via an upper-bound prune. See [pickByActivityWithDomDivider].
    private var heap: IndexedMaxHeap? = null
    private var problemRef: Problem? = null
    private var numBoolCached: Int = 0
    private var numIntCached: Int = 0
    private val pickSkipBuffer = IntArrayList(16)

    /** Conflict bumps that arrived before the first [pick] (so before we'd captured the
     *  problem reference). Applied as part of the next [pick]'s heap init. */
    private val pendingBumps = IntArrayList(8)

    private fun ensureInitialized(problem: Problem) {
        if (heap != null && problemRef === problem) return
        val numFactors = problem.numFactors
        if (factorWeights.size != numFactors) factorWeights = DoubleArray(numFactors) { 1.0 }
        val numBool = problem.numBoolVars
        val numInt = problem.numIntVars
        val h = IndexedMaxHeap(numBool + numInt)
        // Seed each var's key with Σ factorWeights[f] over its occurrence list.
        for (v in 0 until numBool) {
            var sum = 0.0
            for (fid in problem.boolOccurrences[v]) sum += factorWeights[fid]
            h.insert(v, sum)
        }
        for (v in 0 until numInt) {
            var sum = 0.0
            for (fid in problem.intOccurrences[v]) sum += factorWeights[fid]
            h.insert(numBool + v, sum)
        }
        heap = h
        problemRef = problem
        numBoolCached = numBool
        numIntCached = numInt
        // Drain any conflict bumps that arrived before initialization.
        if (pendingBumps.size > 0) {
            for (i in 0 until pendingBumps.size) applyFactorBump(problem, pendingBumps[i])
            pendingBumps.clear()
        }
    }

    override fun pick(session: PropagationSession, rng: Random): VarRef? {
        ensureInitialized(session.problem)
        return pickByActivityWithDomDivider(
            heap = requireNotNull(heap),
            session = session,
            numBool = numBoolCached,
            skip = pickSkipBuffer,
        )
    }

    override fun onConflict(varRef: VarRef, unsat: PropagationResult.Unsat) {
        // Every factor implicated in the conflict gets +1. The reason set was assembled
        // by a backward BFS through the propagation graph (see
        // PropagationState.extractConflictFactors), so this captures all contributing
        // constraints, not just the single failing one.
        val problem = problemRef
        for (fid in unsat.conflictFactors) {
            if (fid >= factorWeights.size) continue
            factorWeights[fid] += 1.0
            if (problem != null) applyFactorBump(problem, fid) else pendingBumps.add(fid)
        }
    }

    /** Push the `+1` weight change on factor [fid] into every var in its scope's heap key. */
    private fun applyFactorBump(problem: Problem, fid: Int) {
        val h = heap ?: return
        val factor = problem.factors[fid]
        for (b in factor.boolVars) h.updateKey(b, h.keyOf(b) + 1.0)
        for (i in factor.intVars) {
            val id = numBoolCached + i
            h.updateKey(id, h.keyOf(id) + 1.0)
        }
    }
}

/**
 * Shared `argmax key(v) / dom(v)` walk used by [DomWdeg] and [ActivityBasedSearch]. The
 * [heap] is keyed on the un-divided score (wdeg or activity); we extract in descending key
 * order and stop once `key / 2.0` (the best score any remaining var could achieve with the
 * tightest possible domain = 2) falls below the current best. Pops are restored at the end
 * so the heap stays complete across calls.
 *
 * Bool ids live in `0..numBool-1`; int ids are stored at `numBool + v` in the heap.
 */
private fun pickByActivityWithDomDivider(
    heap: IndexedMaxHeap,
    session: PropagationSession,
    numBool: Int,
    skip: IntArrayList,
): VarRef? {
    skip.clear()
    var best: VarRef? = null
    var bestScore = Double.NEGATIVE_INFINITY
    while (heap.size > 0) {
        val topId = heap.peekMax()
        val activity = heap.keyOf(topId)
        // Upper bound on the score of any remaining var: activity / 2 (dom is ≥ 2 when free).
        if (activity / 2.0 <= bestScore) break
        heap.extractMax()
        skip.add(topId)
        if (topId < numBool) {
            if (session.boolValue(topId) == null) {
                val score = activity / 2.0
                if (score > bestScore) {
                    bestScore = score
                    best = VarRef.Bool(topId)
                }
            }
        } else {
            val intId = topId - numBool
            val dom = session.intDomain(intId).size
            if (dom > 1) {
                val score = activity / dom.toDouble()
                if (score > bestScore) {
                    bestScore = score
                    best = VarRef.IntVar(intId)
                }
            }
        }
    }
    for (i in 0 until skip.size) heap.restore(skip[i])
    return best
}

/**
 * Last-conflict prioritisation (Lecoutre-Saïs-Tabary-Vidal 2009). Wraps any base
 * [VariableHeuristic]: on every pick, returns the variable that triggered the most
 * recent conflict (if it's still unpinned), otherwise delegates to the base. Cleared
 * when the prioritised variable successfully commits (the next pick falls through to
 * the base) or on restart.
 *
 * Tends to fix unstable subtrees fast — when the search backtracks past a conflict
 * and the responsible variable is back in scope, branching on it again before
 * exploring other vars lets the engine confirm or rule out the cause of the prior
 * failure without wandering. Composes cleanly with [Vsids] / [DomWdeg]: use
 * `LastConflict(Vsids())` to get last-conflict priority on top of activity-driven
 * picking.
 */
class LastConflict(private val base: VariableHeuristic) : VariableHeuristic {

    private var pending: VarRef? = null

    override fun pick(session: PropagationSession, rng: Random): VarRef? {
        val candidate = pending
        if (candidate != null) {
            val stillFree = when (candidate) {
                is VarRef.Bool -> session.boolValue(candidate.varId) == null
                is VarRef.IntVar -> session.intDomain(candidate.varId).size > 1
            }
            if (stillFree) return candidate
            pending = null // assigned away (likely via propagation); drop the prioritisation
        }
        return base.pick(session, rng)
    }

    override fun onConflict(varRef: VarRef) {
        pending = varRef
        base.onConflict(varRef)
    }

    override fun onConflict(varRef: VarRef, unsat: PropagationResult.Unsat) {
        pending = varRef
        base.onConflict(varRef, unsat)
    }

    override fun onCommit(varRef: VarRef) {
        if (pending == varRef) pending = null
        base.onCommit(varRef)
    }

    override fun onPropagation(implied: PropagationResult.Implied) {
        base.onPropagation(implied)
    }

    override fun onRestart() {
        pending = null
        base.onRestart()
    }

    override fun onSolution(snapshot: Sample) {
        base.onSolution(snapshot)
    }
}

/**
 * Activity-Based Search (Michel-Van Hentenryck 2012). Choco's flagship variable selection.
 * Maintains a per-variable *activity* counter that increments every time the variable is
 * forced into a singleton by a propagation step (read off [PropagationResult.Implied]).
 * Different from VSIDS: VSIDS bumps only on conflicts; ABS bumps on *every* propagation
 * event the variable participates in — a broader, lower-variance signal of which variables
 * the constraint network is structurally hardest at.
 *
 * Picks `argmax a(v) / dom(v)` over unpinned variables. Decay is implicit via geometric
 * `increment` growth (same trick as VSIDS); per-decision rescale when `increment` nears
 * `Double` overflow. Activities persist across [onRestart] by default — that's how ABS
 * learns from one run to the next; set [resetOnRestart] to true to clear them at every
 * Luby restart for an aggressive variant.
 *
 *  - [decay] ∈ (0, 1): higher = more conservative (gives long-tail history more weight);
 *    Choco default is 0.999. Lower = more aggressive (forgets old conflicts fast).
 *  - [resetOnRestart] = false (default): preserve activities across restarts; true clears
 *    them, useful for "ABS restart" mode where each run rebuilds the activity map.
 *
 * Caveat: klause's [PropagationResult.Implied] currently reports newly-singletoned
 * variables, not every var whose domain was reduced. So our ABS activity is a narrower
 * signal than the textbook version (which counts every domain-reduction event). Still
 * captures the dominant signal on most CSPs — vars frequently forced into singletons are
 * exactly the structurally-critical ones.
 */
internal class ActivityBasedSearch(
    private val decay: Double = 0.999,
    private val resetOnRestart: Boolean = false,
    private val rescaleThreshold: Double = 1e100,
) : VariableHeuristic {

    init {
        require(decay in 0.5..0.9999) { "ABS decay must be in 0.5..0.9999, got $decay" }
    }

    private var increment: Double = 1.0

    // Combined bool+int heap keyed on raw activity; dom(v) divider applied at pick time.
    // Shares [pickByActivityWithDomDivider] with [DomWdeg].
    private var heap: IndexedMaxHeap? = null
    private var numBoolCached: Int = 0
    private var numIntCached: Int = 0
    private val pickSkipBuffer = IntArrayList(16)

    private fun ensureSized(numBool: Int, numInt: Int) {
        if (heap != null && numBoolCached == numBool && numIntCached == numInt) return
        val h = IndexedMaxHeap(numBool + numInt)
        for (i in 0 until numBool + numInt) h.insert(i, 1.0)
        heap = h
        numBoolCached = numBool
        numIntCached = numInt
    }

    override fun pick(session: PropagationSession, rng: Random): VarRef? {
        val problem = session.problem
        ensureSized(problem.numBoolVars, problem.numIntVars)
        return pickByActivityWithDomDivider(
            heap = requireNotNull(heap),
            session = session,
            numBool = numBoolCached,
            skip = pickSkipBuffer,
        )
    }

    override fun onPropagation(implied: PropagationResult.Implied) {
        val h = heap ?: return
        implied.forEachBool { id, _ ->
            if (id < numBoolCached) h.updateKey(id, h.keyOf(id) + increment)
        }
        implied.forEachInt { id, _ ->
            if (id < numIntCached) {
                val combined = numBoolCached + id
                h.updateKey(combined, h.keyOf(combined) + increment)
            }
        }
    }

    override fun onCommit(varRef: VarRef) {
        // Implicit decay: grow increment so future bumps are larger (equivalent to dividing
        // every prior activity by `decay`, without touching the keys). Same trick as VSIDS.
        increment /= decay
        if (increment > rescaleThreshold) rescaleAll()
    }

    override fun onRestart() {
        if (resetOnRestart) {
            val h = heap ?: return
            h.resetAllKeysInIdOrder(1.0)
            increment = 1.0
        }
    }

    private fun rescaleAll() {
        val h = heap ?: return
        h.scaleKeys(1.0 / rescaleThreshold)
        increment *= 1.0 / rescaleThreshold
    }
}

/**
 * Conflict-Ordering Search (Gay-Hartert-Lecoutre-Schaus 2015). Generalisation of
 * [LastConflict]: instead of pinning the *single* most recent conflict variable, COS
 * maintains a per-variable *timestamp* of its last conflict and picks the unpinned
 * variable with the most recent stamp. Variables never implicated in a conflict have
 * stamp 0 and fall through to the [base] heuristic.
 *
 * Why it's better than plain last-conflict: when the search backjumps past several
 * conflict layers, COS replays them in reverse-chronological order — the deepest unstable
 * subtree is re-explored first, then the next deepest, etc. Plain last-conflict only
 * remembers the very top conflict and forgets the rest. Empirically COS is Choco's
 * default search and routinely beats plain heuristics on structured CSPs.
 *
 *  - Stamps persist across [onRestart] (Choco's default behaviour). The conflict structure
 *    is a global property of the constraint network, not a per-run signal.
 *  - The richer [onConflict] hook (with `unsat`) stamps **every** variable in the
 *    reason set, not just the failed decision. So COS sees the full conflict-graph
 *    attribution (same as VSIDS / dom-wdeg) at no extra plumbing cost.
 *
 * Composes cleanly: `ConflictOrdering(Vsids())` gives COS-on-VSIDS — recent conflicts
 * lead, activity drives the long tail. `ConflictOrdering(DomWdeg())` is the
 * Lecoutre-recommended configuration.
 */
internal class ConflictOrdering(private val base: VariableHeuristic) : VariableHeuristic {

    private var counter: Long = 0
    private var boolStamp: LongArray = LongArray(0)
    private var intStamp: LongArray = LongArray(0)

    private fun ensureSized(numBool: Int, numInt: Int) {
        if (boolStamp.size < numBool) boolStamp = boolStamp.copyOf(numBool)
        if (intStamp.size < numInt) intStamp = intStamp.copyOf(numInt)
    }

    override fun pick(session: PropagationSession, rng: Random): VarRef? {
        val problem = session.problem
        ensureSized(problem.numBoolVars, problem.numIntVars)
        var best: VarRef? = null
        var bestStamp: Long = 0
        for (v in 0 until problem.numBoolVars) {
            if (session.boolValue(v) != null) continue
            val s = boolStamp[v]
            if (s > bestStamp) {
                bestStamp = s
                best = VarRef.Bool(v)
            }
        }
        for (v in 0 until problem.numIntVars) {
            if (session.intDomain(v).size <= 1) continue
            val s = intStamp[v]
            if (s > bestStamp) {
                bestStamp = s
                best = VarRef.IntVar(v)
            }
        }
        return best ?: base.pick(session, rng)
    }

    override fun onConflict(varRef: VarRef) {
        counter++
        stamp(varRef)
        base.onConflict(varRef)
    }

    override fun onConflict(varRef: VarRef, unsat: PropagationResult.Unsat) {
        counter++
        for (b in unsat.conflictBools) {
            growBool(b)
            boolStamp[b] = counter
        }
        for (i in unsat.conflictInts) {
            growInt(i)
            intStamp[i] = counter
        }
        stamp(varRef)
        base.onConflict(varRef, unsat)
    }

    private fun stamp(varRef: VarRef) {
        when (varRef) {
            is VarRef.Bool -> {
                growBool(varRef.varId)
                boolStamp[varRef.varId] = counter
            }

            is VarRef.IntVar -> {
                growInt(varRef.varId)
                intStamp[varRef.varId] = counter
            }
        }
    }

    private fun growBool(id: Int) {
        if (id >= boolStamp.size) boolStamp = boolStamp.copyOf((id + 1).coerceAtLeast(8))
    }
    private fun growInt(id: Int) {
        if (id >= intStamp.size) intStamp = intStamp.copyOf((id + 1).coerceAtLeast(8))
    }

    override fun onCommit(varRef: VarRef) = base.onCommit(varRef)
    override fun onPropagation(implied: PropagationResult.Implied) = base.onPropagation(implied)
    override fun onRestart() = base.onRestart()
    override fun onSolution(snapshot: Sample) = base.onSolution(snapshot)
}

/**
 * Max-regret variable selection for optimisation. The *regret* of a variable is the
 * difference between the best-case and worst-case contribution that branching choices on
 * it can make to the objective:
 *  - bool var `b` with weight `w`: regret = |w|.
 *  - int var `v` with coefficient `c` and domain `[lo..hi]`: regret = |c| · (hi - lo).
 *
 * Picks the unpinned variable with the maximum regret. Branching where the objective is
 * most sensitive lets the engine drive the upper bound down (or lower bound up) fastest —
 * a standard Choco / OR-tools default for `minimize`. When every remaining variable has
 * regret 0 (singleton or zero coefficient), delegates to [base] so the search makes
 * progress on feasibility too.
 *
 * Pair with [IndomainBest] for a complete objective-aware (var, value) strategy.
 */
internal class MaxRegret(
    private val objective: LinearObjective,
    private val base: VariableHeuristic = SmallestDomain,
) : VariableHeuristic {

    override fun pick(session: PropagationSession, rng: Random): VarRef? {
        val problem = session.problem
        var best: VarRef? = null
        var bestRegret = 0L
        for (v in 0 until problem.numBoolVars) {
            if (session.boolValue(v) != null) continue
            val w = if (v < objective.boolWeights.size) objective.boolWeights[v] else 0L
            val r = abs(w)
            if (r > bestRegret) {
                bestRegret = r
                best = VarRef.Bool(v)
            }
        }
        for (v in 0 until problem.numIntVars) {
            val d = session.intDomain(v)
            if (d.size <= 1) continue
            val c = if (v < objective.intCoefficients.size) objective.intCoefficients[v] else 0L
            val r = abs(c) * (d.max - d.min)
            if (r > bestRegret) {
                bestRegret = r
                best = VarRef.IntVar(v)
            }
        }
        return best ?: base.pick(session, rng)
    }

    override fun onConflict(varRef: VarRef) = base.onConflict(varRef)
    override fun onConflict(varRef: VarRef, unsat: PropagationResult.Unsat) = base.onConflict(varRef, unsat)
    override fun onCommit(varRef: VarRef) = base.onCommit(varRef)
    override fun onPropagation(implied: PropagationResult.Implied) = base.onPropagation(implied)
    override fun onRestart() = base.onRestart()
    override fun onSolution(snapshot: Sample) = base.onSolution(snapshot)
}

// ---- Value heuristics ------------------------------------------------------------------

/** Smallest value first (a.k.a. `indomain_min`). For bools: `false` then `true`. */
object IndomainMin : ValueHeuristic {
    override fun values(session: PropagationSession, varRef: VarRef, rng: Random): Sequence<Int> = when (varRef) {
        is VarRef.Bool -> sequenceOf(0, 1)
        is VarRef.IntVar -> domainValuesAscending(session.intDomain(varRef.varId))
    }
}

/** Largest value first (`indomain_max`). For bools: `true` then `false`. */
object IndomainMax : ValueHeuristic {
    override fun values(session: PropagationSession, varRef: VarRef, rng: Random): Sequence<Int> = when (varRef) {
        is VarRef.Bool -> sequenceOf(1, 0)
        is VarRef.IntVar -> domainValuesDescending(session.intDomain(varRef.varId))
    }
}

/**
 * Value closest to the domain midpoint first, then alternating outward (`indomain_middle`).
 * Useful when the SAT distribution clusters around the middle of the domain.
 */
object IndomainMiddle : ValueHeuristic {
    override fun values(session: PropagationSession, varRef: VarRef, rng: Random): Sequence<Int> = when (varRef) {
        is VarRef.Bool -> sequenceOf(0, 1)

        is VarRef.IntVar -> {
            val d = session.intDomain(varRef.varId)
            // Use the sparse-aware `valueAt` to land on the actual middle value
            // (skipping holes) and then walk outward, filtering with `in d` so the
            // sequence never yields a hole.
            val mid = d.valueAt(d.size / 2)
            sequence {
                yield(mid)
                var off = 1
                while (mid - off >= d.min || mid + off <= d.max) {
                    if (mid + off <= d.max && (mid + off) in d) yield(mid + off)
                    if (mid - off >= d.min && (mid - off) in d) yield(mid - off)
                    off++
                }
            }
        }
    }
}

/**
 * Domain bisection (`indomain_split`): branch `v ≤ mid` first, then `v ≥ mid+1`, with
 * `mid` the floor midpoint of the current interval. The engine's int decisions are bound
 * splits around the heuristic's first value (see `BacktrackSolver.IntNode`), so yielding
 * the midpoint produces exactly the dichotomic search the annotation asks for — log-depth
 * on wide domains where value enumeration is linear.
 */
object IndomainSplit : ValueHeuristic {
    override fun values(session: PropagationSession, varRef: VarRef, rng: Random): Sequence<Int> = when (varRef) {
        is VarRef.Bool -> sequenceOf(0, 1)

        is VarRef.IntVar -> {
            val d = session.intDomain(varRef.varId)
            val mid = d.min + (d.max - d.min) / 2
            // The midpoint may sit in a hole; the bound split doesn't care, but the
            // trailing ascending walk keeps the sequence complete for any consumer that
            // enumerates past the first value.
            sequenceOf(mid) + sequence { d.forEach { if (it != mid) yield(it) } }
        }
    }
}

/** Uniformly random shuffle of the domain (`indomain_random`). */
object IndomainRandom : ValueHeuristic {
    override fun values(session: PropagationSession, varRef: VarRef, rng: Random): Sequence<Int> = when (varRef) {
        is VarRef.Bool -> if (rng.nextBoolean()) sequenceOf(1, 0) else sequenceOf(0, 1)

        is VarRef.IntVar -> {
            val d = session.intDomain(varRef.varId)
            // Materialise the actual non-hole values via valueAt, then Fisher-Yates shuffle in
            // place. Kotlin has no primitive-array shuffle, so shuffling the raw IntArray avoids
            // boxing every value into a MutableList<Int> on each branching node.
            val arr = IntArray(d.size) { d.valueAt(it) }
            for (i in arr.size - 1 downTo 1) {
                val j = rng.nextInt(i + 1)
                val tmp = arr[i]
                arr[i] = arr[j]
                arr[j] = tmp
            }
            arr.asSequence()
        }
    }
}

/**
 * Allow-list value selection: tries only [allowedValues] (in order) intersected with the
 * current domain. Sparse-aware via the `in d` membership check.
 */
internal class IndomainSet(private val allowedValues: IntArray) : ValueHeuristic {
    override fun values(session: PropagationSession, varRef: VarRef, rng: Random): Sequence<Int> = when (varRef) {
        is VarRef.Bool -> allowedValues.asSequence().filter { it == 0 || it == 1 }

        is VarRef.IntVar -> {
            val d = session.intDomain(varRef.varId)
            allowedValues.asSequence().filter { it in d }
        }
    }
}

/**
 * Impact-based value selection (Refalo 2004). For each candidate value of [varRef], probes
 * a real propagation pin via [PropagationSession.pinBool] / [pinInt], measures the log of
 * the post-pin remaining-domain product, then reverts. Values are returned in **ascending
 * post-product order**: smaller residual search space = stronger pruning = try first.
 *
 *  - Values whose probe yields [PropagationResult.Unsat] are dropped entirely from the
 *    sequence — the engine never wastes a real pin on them. Free pre-pruning at every node.
 *  - For int domains larger than [maxProbes], a uniformly random subset is probed; the
 *    un-probed remainder is appended at the end in ascending order (so coverage is
 *    preserved if the engine backtracks past every probed value). Bool vars are always
 *    fully probed (only two values).
 *  - Composes with `LastConflict` and any variable heuristic; the cost is O(maxProbes ×
 *    propagation), amortised by the pruning power that lets the search skip whole subtrees.
 *
 * Caveat: the heuristic does work *inside* `values()` (pin + propagate + popLast). This is
 * cheap per call but isn't free — for large random/enumeration workloads where node count
 * dominates, the simpler `Indomain*` family will still win on wall-time even if each node
 * does more work. Use Impact when reasoning power per node matters, e.g. structured CSPs
 * with strong global propagators.
 */
internal class Impact(private val maxProbes: Int = 32) : ValueHeuristic {
    override fun values(session: PropagationSession, varRef: VarRef, rng: Random): Sequence<Int> =
        probeAndOrder(session, varRef, rng, maxProbes, ascending = true)
}

/**
 * Counting-based value selection — the Pesant 2005 "Maxsd" (maximum solution-density)
 * heuristic, instantiated with the cheap aFC (approximate Frequency Count, Zanarini-Pesant
 * 2009) proxy: for each candidate value, probe a real propagation pin, then score by the
 * log-product of remaining domain sizes. **Larger residual product = more solutions still
 * supported = try first** (the dual ordering of [Impact]).
 *
 * The Pesant intuition is that values which leave the constraint network *richer* are more
 * likely to be on a path to a solution; values which immediately collapse domains are more
 * likely to lead to a dead-end. Same probing machinery and infeasible-value drop as
 * [Impact], with sorting reversed. Both compose with `LastConflict` and any variable
 * heuristic.
 *
 * Notes:
 *  - The log-product is a geometric-mean proxy for the true solution-density. Exact factor
 *    counters (regular DFA, AllDifferent permanent) would be more accurate but require
 *    per-factor support that isn't in klause's current factor API.
 *  - Empirically: counting wins on structured combinatorial problems (rostering, scheduling)
 *    where the solution manifold is "fat" near correct subtrees; Impact wins on
 *    pruning-heavy first-fail problems.
 */
internal class MaxSd(private val maxProbes: Int = 32) : ValueHeuristic {
    override fun values(session: PropagationSession, varRef: VarRef, rng: Random): Sequence<Int> =
        probeAndOrder(session, varRef, rng, maxProbes, ascending = false)
}

/**
 * Shared probing core for [Impact] and [MaxSd]. For each candidate value of [varRef]:
 *   - push a real propagation pin via [PropagationSession.pinBool] / [pinInt];
 *   - score by the log of the remaining-domain product;
 *   - revert with [PropagationSession.popLast] (Unsat probes self-revert, are dropped).
 *
 * [ascending] = `true` orders smallest-residual first (Impact); `false` orders largest-
 * residual first (MaxSd). For int domains larger than [maxProbes], a random subset is
 * probed; the un-probed remainder is appended at the end so DFS coverage is preserved.
 */
private fun probeAndOrder(
    session: PropagationSession,
    varRef: VarRef,
    rng: Random,
    maxProbes: Int,
    ascending: Boolean,
): Sequence<Int> {
    val candidates: IntArray = when (varRef) {
        is VarRef.Bool -> intArrayOf(0, 1)

        is VarRef.IntVar -> {
            val d = session.intDomain(varRef.varId)
            if (d.size <= maxProbes) {
                IntArray(d.size) { d.valueAt(it) }
            } else {
                val seen = IntHashSet(maxProbes * 2)
                val sample = IntArray(maxProbes)
                var i = 0
                var guard = 0
                while (i < maxProbes && guard < maxProbes * 8) {
                    val candidate = d.valueAt(rng.nextInt(d.size))
                    if (seen.add(candidate)) {
                        sample[i] = candidate
                        i++
                    }
                    guard++
                }
                if (i < maxProbes) sample.copyOf(i) else sample
            }
        }
    }
    val scored = ArrayList<Pair<Int, Double>>(candidates.size)
    for (v in candidates) {
        val r = when (varRef) {
            is VarRef.Bool -> session.pinBool(varRef.varId, v != 0)
            is VarRef.IntVar -> session.pinInt(varRef.varId, v)
        }
        if (r is Unsat) continue
        val post = logRemainingDomainProduct(session)
        session.popLast()
        scored.add(v to post)
    }
    if (ascending) scored.sortBy { it.second } else scored.sortByDescending { it.second }
    if (varRef is VarRef.IntVar) {
        val d = session.intDomain(varRef.varId)
        if (candidates.size < d.size) {
            val probed = IntHashSet(candidates.size * 2).apply {
                for ((p, _) in scored) add(p)
                for (c in candidates) add(c)
            }
            val ordered = scored.asSequence().map { it.first }
            return ordered + sequence { d.forEach { if (it !in probed) yield(it) } }
        }
    }
    return scored.asSequence().map { it.first }
}

/** Sum of `ln(size)` over every unpinned variable (bools count as `ln 2` when free). Log-
 *  space to dodge `Double` overflow on problems with hundreds of free vars. */
private fun logRemainingDomainProduct(session: PropagationSession): Double {
    var s = 0.0
    val p = session.problem
    val ln2 = ln(2.0)
    for (v in 0 until p.numBoolVars) if (session.boolValue(v) == null) s += ln2
    for (v in 0 until p.numIntVars) {
        val sz = session.intDomain(v).size
        if (sz > 1) s += ln(sz.toDouble())
    }
    return s
}

/**
 * Objective-aware value selection — `indomain_best` / `intDomainBest`. For an int var with
 * coefficient `c` in the linear objective, returns the domain ascending (best minimum first)
 * when `c ≥ 0` and descending when `c < 0`. For a bool var with weight `w`, returns the
 * polarity minimising `w` first (false first when `w ≥ 0`, true first when `w < 0`).
 *
 * Pairs naturally with [MaxRegret] (variable side) and a B&B-style optimisation loop —
 * each successful pin moves the partial assignment as close to the global optimum as the
 * variable-level coefficients allow, so the incumbent improves fast and pruning kicks in
 * early. For a satisfiability problem, falls through to [IndomainMin] (every coefficient
 * is zero so ascending order is preserved).
 */
internal class IndomainBest(private val objective: LinearObjective) : ValueHeuristic {
    override fun values(session: PropagationSession, varRef: VarRef, rng: Random): Sequence<Int> = when (varRef) {
        is VarRef.Bool -> {
            val w = if (varRef.varId < objective.boolWeights.size) objective.boolWeights[varRef.varId] else 0L
            // false contributes 0; true contributes w. Lower-contribution-first.
            if (w >= 0L) sequenceOf(0, 1) else sequenceOf(1, 0)
        }

        is VarRef.IntVar -> {
            val c = if (varRef.varId <
                objective.intCoefficients.size
            ) {
                objective.intCoefficients[varRef.varId]
            } else {
                0L
            }
            val d = session.intDomain(varRef.varId)
            if (c >= 0L) {
                sequence { d.forEach { yield(it) } }
            } else {
                sequence { for (v in d.max downTo d.min) if (v in d) yield(v) }
            }
        }
    }
}

/**
 * Solution-guided value selection (Demoen-Garcia-de-la-Banda 2009 / Beck-Davenport). Wraps
 * a [base] value heuristic: once a SAT leaf is observed via [onSolution], the heuristic
 * snapshots the assignment, and on every subsequent pick it tries the snapshot's value for
 * the var first (falling back to [base]'s order for everything else). The snapshot is
 * refreshed on each new solution — typical use is optimisation, where successive incumbents
 * are similar and a search biased to stay near the previous incumbent finds the next one
 * faster than starting from scratch.
 *
 *  - First descent (before any solution) is purely [base] — no bias.
 *  - After a solution: saved value tried first; if the saved value is no longer in the
 *    current domain (the search has propagated it away), falls through to [base] in full.
 *  - Snapshots **persist** across [onRestart] — that's the whole point: cross-restart
 *    bias toward the last-seen incumbent. The base's `onRestart` is still forwarded so
 *    activity-based wrappers like [Vsids] still decay as expected.
 *
 * Composes naturally with [Impact] / [MaxSd] / [IndomainRandom] / phase-saving as the
 * inner choice — the engine still runs through the saved value first, but if that branch
 * proves infeasible, the inner heuristic's order takes over.
 */
class SolutionGuided(private val base: ValueHeuristic) : ValueHeuristic {

    private var bools: BooleanArray? = null
    private var ints: IntArray? = null

    override fun values(session: PropagationSession, varRef: VarRef, rng: Random): Sequence<Int> {
        val savedBools = bools
        val savedInts = ints
        if (savedBools == null || savedInts == null) return base.values(session, varRef, rng)
        val saved: Int = when (varRef) {
            is VarRef.Bool -> if (varRef.varId < savedBools.size && savedBools[varRef.varId]) 1 else 0

            is VarRef.IntVar -> {
                if (varRef.varId >= savedInts.size) return base.values(session, varRef, rng)
                savedInts[varRef.varId]
            }
        }
        val savedFeasible = when (varRef) {
            // Bool: saved is always 0 or 1 — feasible iff the var is still unpinned.
            is VarRef.Bool -> session.boolValue(varRef.varId) == null

            is VarRef.IntVar -> saved in session.intDomain(varRef.varId)
        }
        return if (savedFeasible) {
            sequenceOf(saved) + base.values(session, varRef, rng).filter { it != saved }
        } else {
            base.values(session, varRef, rng)
        }
    }

    override fun onConflict(varRef: VarRef, value: Int) = base.onConflict(varRef, value)
    override fun onCommit(varRef: VarRef, value: Int) = base.onCommit(varRef, value)
    override fun onRestart() = base.onRestart()

    override fun onSolution(snapshot: Sample) {
        bools = snapshot.bools.copyOf()
        ints = snapshot.ints.copyOf()
        base.onSolution(snapshot)
    }
}

/** Ascending sequence of all values in [d], skipping any holes. Materialises lazily so
 *  the engine can early-exit before enumerating the full domain on a backtrack. */
private fun domainValuesAscending(d: IntDomain): Sequence<Int> = sequence { d.forEach { yield(it) } }

/** Descending sequence; same skip-holes semantics. */
private fun domainValuesDescending(d: IntDomain): Sequence<Int> = sequence {
    // Backwards walk: iterate from max down to min, skip holes via membership check.
    // For sparse domains this is O((max - min) + holes); for contiguous it's O(span).
    for (v in d.max downTo d.min) if (v in d) yield(v)
}
