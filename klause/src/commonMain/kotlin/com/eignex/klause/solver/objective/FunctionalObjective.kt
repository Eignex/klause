package com.eignex.klause.solver.objective

import com.eignex.klause.factor.arithmetic.saturatedIntegerPower
import com.eignex.klause.ir.Lit
import com.eignex.klause.localsearch.DefinitionalSweep
import com.eignex.klause.localsearch.Move
import com.eignex.klause.solver.Assignment
import com.eignex.klause.solver.Sample
import com.eignex.klause.util.BigInt
import com.eignex.klause.util.CheckedLongOverflowException
import com.eignex.klause.util.EmptyIntArray
import com.eignex.klause.util.EmptyLongArray
import com.eignex.klause.util.IntHashSet
import com.eignex.klause.util.IntIntMap
import com.eignex.klause.util.MutableIntLongMap
import com.eignex.klause.util.addExact
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.minus
import com.eignex.klause.util.mulExact
import com.eignex.klause.util.plus
import com.eignex.klause.util.times
import com.eignex.klause.util.toDouble
import com.eignex.klause.util.unaryMinus
import kotlin.math.abs

// Integer and Boolean definitions share a topological order and retain separate coordinate spaces.
internal class FunctionalObjective internal constructor(
    /** Int objective terms `Σ termCoeffs[k]·terms[k]`, "lower is better": each term is a cone var
     *  (evaluated through [nodes]) or a bare int leaf. A single-variable objective is one term. */
    private val terms: IntArray,
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
    private val boolTerms: IntArray = EmptyIntArray,
    private val boolTermCoeffs: LongArray = EmptyLongArray,
    /** Bool defining nodes (AND/OR folds) in topological order. */
    boolNodes: List<BoolFold> = emptyList(),
    /** Bool decision (leaf) variables the bool cone reads. */
    val boolLeafVars: IntArray = EmptyIntArray,
    definitions: List<Definition> = emptyList(),
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
    class Power(override val out: Int, val base: Operand, val exponent: Int) : Node {
        override fun compute(valOf: (Int) -> Long): Long = saturatedIntegerPower(base.value(valOf), exponent)
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

    internal class Definition(
        val out: Int,
        val outIsBool: Boolean,
        /** The sweep node this definition evaluates, when it was read from a sweep. */
        val origin: DefinitionalSweep.SweepNode? = null,
        val compute: ((Int) -> Long, (Int) -> Boolean) -> Long,
    )

    private val coneMemo = ConeMemo(
        nodes, terms, termCoeffs, constant, boolNodes, boolTerms, boolTermCoeffs, definitions,
    )

    internal fun prepareBooleanDelta(numBooleans: Int): Sequence<PreparedBooleanObjectiveDelta?> =
        if (terms.isNotEmpty()) sequenceOf(PreparedBooleanObjectiveDelta(null)) else
            BooleanObjectiveDelta.preparation(coneMemo.ordered, boolTerms, boolTermCoeffs, minimize, numBooleans)

    /** The objective as a "lower is better" linear form over its terms, or null when a combined
     *  coefficient leaves the `Long` range. It agrees with the cones wherever every cone variable
     *  holds its definition's value. */
    internal val termForm: LinearObjective? = termForm(terms, termCoeffs, constant, boolTerms, boolTermCoeffs)

    /** True iff every cone definition was read from a sweep node that [maintains] accepts. */
    internal fun conesMaintained(maintains: (DefinitionalSweep.SweepNode) -> Boolean): Boolean =
        coneMemo.ordered.all { definition -> definition.origin?.let(maintains) == true }

    internal fun coneMaintenanceSteps(
        maintains: (DefinitionalSweep.SweepNode) -> Boolean,
    ): Sequence<Boolean?> = sequence {
        for ((index, definition) in coneMemo.ordered.withIndex()) {
            if (definition.origin?.let(maintains) != true) {
                yield(false)
                return@sequence
            }
            if ((index + 1) % 256 == 0) yield(null)
        }
        yield(true)
    }

    private fun termForm(
        terms: IntArray,
        termCoeffs: LongArray,
        constant: Long,
        boolTerms: IntArray,
        boolTermCoeffs: LongArray,
    ): LinearObjective? {
        val sign = if (minimize) 1L else -1L
        return try {
            val ints = LongArray((terms.maxOrNull() ?: -1) + 1)
            for (k in terms.indices) ints[terms[k]] = addExact(ints[terms[k]], mulExact(sign, termCoeffs[k]))
            val bools = LongArray((boolTerms.maxOrNull() ?: -1) + 1)
            for (k in boolTerms.indices) {
                bools[boolTerms[k]] = addExact(bools[boolTerms[k]], mulExact(sign, boolTermCoeffs[k]))
            }
            LinearObjective(bools, ints, mulExact(sign, constant))
        } catch (_: CheckedLongOverflowException) {
            null
        }
    }

    /** Evaluate the cones over base value-getters and return the "lower is better" objective. */
    private fun objValue(intBase: (Int) -> Long, boolBase: (Int) -> Boolean): BigInt {
        val v = coneMemo.evaluate(intBase, boolBase)
        return if (minimize) v else -v
    }

    // Dense bottom-up evaluator: each node output owns a slot in a flat LongArray in topological order,
    // avoiding a boxed per-evaluation map on the per-move path (deltaIfApplied evaluates the cone twice).
    // The value array is allocated per evaluate, so one instance is safe to share across concurrent
    // local-search workers.
    private class ConeMemo(
        nodes: List<Node>,
        private val terms: IntArray,
        private val termCoeffs: LongArray,
        private val constant: Long,
        boolNodes: List<BoolFold>,
        private val boolTerms: IntArray,
        private val boolTermCoeffs: LongArray,
        definitions: List<Definition>,
    ) {
        val ordered = if (definitions.isNotEmpty()) definitions else
            nodes.map { node -> Definition(node.out, false) { intOf, _ -> node.compute(intOf) } } +
                boolNodes.map { node ->
                    Definition(node.out, true) { _, boolOf -> if (node.compute(boolOf)) 1L else 0L }
                }
        private val intSlots = slots(false)
        private val boolSlots = slots(true)

        private fun slots(isBool: Boolean): IntIntMap {
            val indices = ordered.indices.filter { ordered[it].outIsBool == isBool }
            return IntIntMap.build(
                IntArray(indices.size) { ordered[indices[it]].out }, indices.toIntArray(), absent = -1,
            )
        }

        fun evaluate(intBase: (Int) -> Long, boolBase: (Int) -> Boolean): BigInt {
            val values = LongArray(ordered.size)
            val intOf: (Int) -> Long = { id ->
                val slot = intSlots[id]
                if (slot >= 0) values[slot] else intBase(id)
            }
            val boolOf: (Int) -> Boolean = { id ->
                val slot = boolSlots[id]
                if (slot >= 0) values[slot] != 0L else boolBase(id)
            }
            for (i in ordered.indices) values[i] = ordered[i].compute(intOf, boolOf)
            try {
                var total = constant
                for (k in terms.indices) total = addExact(total, mulExact(termCoeffs[k], intOf(terms[k])))
                for (k in boolTerms.indices) if (boolOf(boolTerms[k])) total = addExact(total, boolTermCoeffs[k])
                return bigIntOf(total)
            } catch (_: CheckedLongOverflowException) {
                var total = bigIntOf(constant)
                for (k in terms.indices) total += bigIntOf(termCoeffs[k]) * bigIntOf(intOf(terms[k]))
                for (k in boolTerms.indices) if (boolOf(boolTerms[k])) total += bigIntOf(boolTermCoeffs[k])
                return total
            }
        }
    }

    override fun evaluate(sample: Sample): Double =
        objValue({ id -> sample.intValue(id) }, { id -> sample.boolValue(id) }).toDouble()

    override fun evaluate(assignment: Assignment): Double =
        objValue({ id -> assignment.intValue(id) }, { id -> assignment.boolValue(id) }).toDouble()

    internal fun evaluateExact(assignment: Assignment): BigInt =
        objValue({ id -> assignment.intValue(id) }, { id -> assignment.boolValue(id) })

    internal fun evaluateExact(sample: Sample): BigInt =
        objValue({ id -> sample.intValue(id) }, { id -> sample.boolValue(id) })

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
            is Move.RealSet -> {}
            is Move.Compound -> for (p in move.parts) collectMoves(p, moved, flipped)
        }
    }
}
