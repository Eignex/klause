package com.eignex.klause.solver

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
 * and plateaus far from the optimum (observed: city-position stuck ~460× off OR-Tools' optimum).
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
    private val objectiveVar: Int,
    private val minimize: Boolean,
    /** Defining nodes in topological order — every node's inputs are leaves or earlier nodes. */
    private val nodes: List<Node>,
    /** Decision (leaf) variables the cone reads — the vars a local-search strategy should seed
     *  candidate moves on to descend this objective. Empty when the cone has no var leaves. */
    val leafVars: IntArray,
) : IncrementalObjective {

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

    /** Evaluate the cone over a base value-getter and return the "lower is better" objective. */
    private fun objValue(base: (Int) -> Long): Long {
        val computed = HashMap<Int, Long>(nodes.size * 2)
        val valOf: (Int) -> Long = { id -> computed[id] ?: base(id) }
        for (n in nodes) computed[n.out] = n.compute(valOf)
        val v = computed[objectiveVar] ?: base(objectiveVar)
        return if (minimize) v else -v
    }

    override fun evaluate(sample: Sample): Double = objValue { id -> sample.ints[id].toLong() }.toDouble()

    override fun deltaIfApplied(assignment: Assignment, move: Move): Double {
        val moved = HashMap<Int, Long>()
        collectIntMoves(move, moved)
        if (moved.isEmpty()) return 0.0
        val cur = objValue { id -> assignment.intValue(id).toLong() }
        val nxt = objValue { id -> moved[id] ?: assignment.intValue(id).toLong() }
        return (nxt - cur).toDouble()
    }

    private fun collectIntMoves(move: Move, into: HashMap<Int, Long>) {
        when (move) {
            is Move.IntSet -> into[move.varId] = move.newValue.toLong()

            is Move.BoolFlip -> {}

            // bool moves don't change int-cone leaf values
            is Move.Compound -> for (p in move.parts) collectIntMoves(p, into)
        }
    }
}
