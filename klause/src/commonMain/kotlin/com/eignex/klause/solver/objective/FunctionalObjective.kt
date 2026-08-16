package com.eignex.klause.solver.objective

import com.eignex.klause.localsearch.Move
import com.eignex.klause.solver.Assignment
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Sample
import com.eignex.klause.util.EmptyIntArray
import com.eignex.klause.util.EmptyLongArray
import com.eignex.klause.util.IntHashSet
import com.eignex.klause.util.IntIntMap
import com.eignex.klause.util.MutableIntLongMap
import kotlin.math.abs

/**
 * An [IncrementalObjective] that evaluates a *functionally-defined* objective variable through
 * the DAG of constraints that compute it — the `:: defines_var(V)` cone MiniZinc emits for
 * decomposed objectives (e.g. `objective = Σ |dist_i − approx_i|` lowered to int_abs / int_max /
 * int_min / int_lin_eq aux vars).
 *
 * Why this exists: `LocalSearchState.shapedObjectiveDelta` gives a [LinearObjective]
 * (`minimizeInt(V)`) a non-zero gradient only on `V` itself. But `V` is *derived* — moving a
 * decision variable `x` leaves `V`'s stored value untouched (it merely breaks `V`'s defining
 * constraint), so the move scores an objective-delta of 0 and CBLS is blind to the gradient
 * that actually matters. The search then only descends opportunistically via constraint repair
 * and plateaus far from the optimum (observed: city-position stuck ~460× off the true optimum).
 *
 * This objective instead recomputes `V` from the leaf (decision) variables by evaluating the
 * cone bottom-up, so a move on `x` yields the *true* change in `V` — the gradient CBLS needs to
 * descend a decomposed objective. Built by the FlatZinc compiler from the `defines_var`
 * annotations; the builder returns `null` (falling back to [LinearObjective]) on any node shape
 * it can't evaluate exactly, so the objective is only ever used when it's an exact mirror of `V`.
 *
 * "Lower is better": for minimization the objective is `V`; for maximization it is `−V`.
 */
internal class FunctionalObjective internal constructor(
    /** Int objective terms `Σ termCoeffs[k]·terms[k]`, "lower is better": each term is a cone var
     *  (evaluated through [nodes]) or a bare int leaf. A single-variable objective is one term. */
    terms: IntArray,
    termCoeffs: LongArray,
    constant: Long,
    private val minimize: Boolean,
    /** Int defining nodes in topological order — every node's inputs are leaves or earlier nodes. */
    nodes: List<Node>,
    /** Int decision (leaf) variables the cone reads — the vars a local-search strategy should seed
     *  candidate moves on to descend this objective. Empty when the cone has no int var leaves. */
    val leafVars: IntArray,
    /** Bool objective terms `Σ boolTermCoeffs[k]·[boolTerms[k] holds]`, added to the int terms. Each
     *  is a bool cone var (evaluated through [boolNodes]) or a bare bool leaf; empty for a pure-int
     *  objective (the FlatZinc/XCSP3 case), non-empty for a bool-weighted objective over AND/OR
     *  indicators (OPB product terms, `array_bool_and`). */
    boolTerms: IntArray = EmptyIntArray,
    boolTermCoeffs: LongArray = EmptyLongArray,
    /** Bool defining nodes (AND/OR folds) in topological order. */
    boolNodes: List<BoolFold> = emptyList(),
    /** Bool decision (leaf) variables the bool cone reads. */
    val boolLeafVars: IntArray = EmptyIntArray,
) : IncrementalObjective {

    /** Single-variable objective (the FlatZinc `defines_var` cone rooted at one variable). */
    internal constructor(objectiveVar: Int, minimize: Boolean, nodes: List<Node>, leafVars: IntArray) :
        this(intArrayOf(objectiveVar), longArrayOf(1L), 0L, minimize, nodes, leafVars)

    /** A var reference (`varId ≥ 0`) or a literal constant (`varId < 0`, value in [const]). */
    class Operand internal constructor(val varId: Int, val const: Long) {
        fun value(valOf: (Int) -> Long): Long = if (varId >= 0) valOf(varId) else const
        companion object {
            fun v(id: Int) = Operand(id, 0L)
            fun c(value: Long) = Operand(-1, value)
        }
    }

    /** One functional definition `out = f(inputs)`. */
    sealed interface Node {
        val out: Int
        fun compute(valOf: (Int) -> Long): Long
    }
    class Abs(override val out: Int, val a: Operand) : Node {
        override fun compute(valOf: (Int) -> Long): Long = abs(a.value(valOf))
    }
    class Extreme(override val out: Int, val ins: Array<Operand>, val max: Boolean) : Node {
        override fun compute(valOf: (Int) -> Long): Long {
            var acc = ins[0].value(valOf)
            for (k in 1 until ins.size) {
                val v = ins[k].value(valOf)
                acc = if (max) maxOf(acc, v) else minOf(acc, v)
            }
            return acc
        }
    }
    class Times(override val out: Int, val a: Operand, val b: Operand) : Node {
        override fun compute(valOf: (Int) -> Long): Long = a.value(valOf) * b.value(valOf)
    }
    class Plus(override val out: Int, val a: Operand, val b: Operand) : Node {
        override fun compute(valOf: (Int) -> Long): Long = a.value(valOf) + b.value(valOf)
    }

    /** Linear definition `outCoeff·out + Σ coeffs[k]·ins[k] = c`  ⇒  `out = (c − Σ …)/outCoeff`.
     *  The compiler guarantees integrality of the quotient (it's a real defines_var output). */
    class Lin(override val out: Int, val outCoeff: Long, val coeffs: LongArray, val ins: Array<Operand>, val c: Long) :
        Node {
        override fun compute(valOf: (Int) -> Long): Long {
            var rhs = c
            for (k in ins.indices) rhs -= coeffs[k] * ins[k].value(valOf)
            return rhs / outCoeff
        }
    }

    /** Bool definition `out ↔ ⋀ ins` (or `⋁ ins` when `!isAnd`) over bool *literals* ([ins] are
     *  [Lit]-encoded, so a negated member is legal): the OPB product-term / `array_bool_and` shape. */
    class BoolFold(val out: Int, private val ins: IntArray, private val isAnd: Boolean) {
        fun compute(boolOf: (Int) -> Boolean): Boolean {
            var acc = isAnd
            for (lit in ins) {
                val v = if (Lit.isPositive(lit)) boolOf(Lit.variable(lit)) else !boolOf(Lit.variable(lit))
                acc = if (isAnd) acc && v else acc || v
                if (acc != isAnd) break
            }
            return acc
        }
    }

    /** Reusable dense evaluator for the defining cones; the slot indices are built once here. */
    private val coneMemo = ConeMemo(nodes, terms, termCoeffs, constant, boolNodes, boolTerms, boolTermCoeffs)

    /** Evaluate the cones over base value-getters and return the "lower is better" objective. */
    private fun objValue(intBase: (Int) -> Long, boolBase: (Int) -> Boolean): Long {
        val v = coneMemo.evaluate(intBase, boolBase)
        return if (minimize) v else -v
    }

    // Dense bottom-up evaluator for the defining cone. Each node-output variable owns a slot in a
    // flat LongArray indexed in the nodes' topological order, so evaluating the cone is array writes
    // plus O(1) slot lookups rather than a per-evaluation map of boxed values rebuilt on every move
    // — the dominant per-move allocation, since deltaIfApplied evaluates the cone twice. The slot
    // index is computed once; the value array is allocated per evaluate, so a single instance stays
    // safe to share across concurrent local-search workers (the objective is held in
    // LocalSearchParams).
    private class ConeMemo(
        private val nodes: List<Node>,
        private val terms: IntArray,
        private val termCoeffs: LongArray,
        private val constant: Long,
        private val boolNodes: List<BoolFold>,
        private val boolTerms: IntArray,
        private val boolTermCoeffs: LongArray,
    ) {
        /** Int node-output varId → its value-array slot; an id with no defining node (a leaf) maps to
         *  `-1`. [IntIntMap.build] picks a dense array backing for klause's dense aux-var ids. */
        private val slotOf: IntIntMap = IntIntMap.build(
            IntArray(nodes.size) { nodes[it].out },
            IntArray(nodes.size) { it },
            absent = -1,
        )

        /** Bool node-output varId → its value-array slot (separate id space from the int cone). */
        private val boolSlotOf: IntIntMap = IntIntMap.build(
            IntArray(boolNodes.size) { boolNodes[it].out },
            IntArray(boolNodes.size) { it },
            absent = -1,
        )

        /** Evaluate both cones bottom-up, reading leaf (decision) values from [intBase] / [boolBase],
         *  then combine the objective terms `Σ termCoeffs·terms + Σ boolTermCoeffs·[bool] + constant`. */
        fun evaluate(intBase: (Int) -> Long, boolBase: (Int) -> Boolean): Long {
            var total = constant
            if (nodes.isNotEmpty() || terms.isNotEmpty()) {
                val vals = LongArray(nodes.size)
                // A node's inputs are leaves or earlier nodes (topological order), so its slot is filled
                // before it is read; ids with no slot fall through to the leaf getter.
                val valOf: (Int) -> Long = { id ->
                    val s = slotOf[id]
                    if (s >= 0) vals[s] else intBase(id)
                }
                for (i in nodes.indices) vals[i] = nodes[i].compute(valOf)
                for (k in terms.indices) total += termCoeffs[k] * valOf(terms[k])
            }
            if (boolNodes.isNotEmpty() || boolTerms.isNotEmpty()) {
                val bvals = BooleanArray(boolNodes.size)
                val boolOf: (Int) -> Boolean = { id ->
                    val s = boolSlotOf[id]
                    if (s >= 0) bvals[s] else boolBase(id)
                }
                for (i in boolNodes.indices) bvals[i] = boolNodes[i].compute(boolOf)
                for (k in boolTerms.indices) if (boolOf(boolTerms[k])) total += boolTermCoeffs[k]
            }
            return total
        }
    }

    override fun evaluate(sample: Sample): Double =
        objValue({ id -> sample.ints[id] }, { id -> sample.bools[id] }).toDouble()

    override fun evaluate(assignment: Assignment): Double =
        objValue({ id -> assignment.intValue(id) }, { id -> assignment.boolValue(id) }).toDouble()

    override fun deltaIfApplied(assignment: Assignment, move: Move): Double {
        val moved = MutableIntLongMap()
        val flipped = IntHashSet()
        collectMoves(move, moved, flipped)
        if (moved.isEmpty() && flipped.isEmpty()) return 0.0
        val cur = objValue({ id -> assignment.intValue(id) }, { id -> assignment.boolValue(id) })
        val nxt = objValue(
            { id -> moved.getOrDefault(id, assignment.intValue(id)) },
            { id -> if (id in flipped) !assignment.boolValue(id) else assignment.boolValue(id) },
        )
        return (nxt - cur).toDouble()
    }

    /** Gather a move's int overrides into [moved] and toggle each flipped bool var in [flipped]
     *  (toggling nets out a var flipped an even number of times within a compound). */
    private fun collectMoves(move: Move, moved: MutableIntLongMap, flipped: IntHashSet) {
        when (move) {
            is Move.IntSet -> moved.put(move.varId, move.newValue)
            is Move.BoolFlip -> if (!flipped.remove(move.varId)) flipped.add(move.varId)
            is Move.Compound -> for (p in move.parts) collectMoves(p, moved, flipped)
        }
    }
}
