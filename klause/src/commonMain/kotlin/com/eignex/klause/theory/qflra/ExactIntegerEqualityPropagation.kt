package com.eignex.klause.theory.qflra

import com.eignex.klause.arithmetic.difference.checkedDifferenceSubtract
import com.eignex.klause.arithmetic.difference.checkedDifferenceSum
import com.eignex.klause.ir.IntegerConstants
import com.eignex.klause.ir.LinearForm
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.Term
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.SearchContext
import com.eignex.klause.solver.search.SearchExplanation
import com.eignex.klause.util.Cancellation

internal class ExactIntegerEqualityPropagation private constructor(
    private val rows: List<Row>,
    private val zero: Int,
    private val observe: ((String) -> Unit)?,
) {
    fun propagate(
        context: SearchContext,
        stop: Cancellation,
        orderEligible: ((Int, Int, Boolean) -> Boolean)? = null,
        receiveOrder: ((Order) -> ComponentResult?)? = null,
        receive: (Int) -> ComponentResult = { ComponentResult.Consistent },
    ): ComponentResult? {
        stop.charge((zero + 1) * 5L)
        if (stop()) return ComponentResult.Indeterminate
        val scratch = Scratch(zero + 1, stop)
        val exclusions = ArrayList<Fact>()
        val orders = ArrayList<Fact>()
        for (row in rows) {
            if (!scratch.visit()) return if (stop()) ComponentResult.Indeterminate else null
            val truth = (if (row.guard == LinearRow.ALWAYS) true else context.boolValue(row.guard)) ?: continue
            val equality = when (row.relation) {
                LinearOp.EQ -> truth
                LinearOp.NE -> !truth
                else -> false
            }
            val premise = if (row.guard == LinearRow.ALWAYS) null else Lit.make(row.guard, !truth)
            if (!equality) {
                if (receiveOrder == null || orderEligible == null) continue
                stop.charge(3L)
                if (stop()) return ComponentResult.Indeterminate
                val fact = Fact(row, premise, truth)
                if (row.relation == LinearOp.EQ || row.relation == LinearOp.NE) exclusions += fact
                else orders += fact
                continue
            }
            scratch.join(row, premise)
            if (scratch.declined) return if (stop()) ComponentResult.Indeterminate else null
        }
        var published = false
        fun finish(): ComponentResult? = when {
            stop() -> ComponentResult.Indeterminate
            published -> ComponentResult.Consistent
            else -> null
        }
        if (receiveOrder != null && orderEligible != null) {
            val tightened = scratch.tighten(exclusions, orders, orderEligible, observe)
            if (scratch.declined || stop()) return finish()
            for (known in tightened) {
                if (!scratch.visit()) return finish()
                val proof = scratch.orderProof(known) ?: continue
                if (proof.any { context.boolValue(Lit.variable(it)) != !Lit.isPositive(it) }) continue
                stop.charge(proof.size + 1L)
                if (stop()) return ComponentResult.Indeterminate
                val row = known.exclusion.row
                val terms = buildList {
                    add(row.first to row.firstCoefficient)
                    if (row.second != zero) add(row.second to row.secondCoefficient)
                }
                observe?.invoke("ALIASED_ORDER_ATOM_MATCH")
                val result = receiveOrder(Order(row.factor, row.index, known.exclusion.truth,
                    terms, known.bound, known.upper, proof)) ?: continue
                if (result !is ComponentResult.Consistent) return result
                published = true
            }
        }
        for (row in rows) {
            if (!scratch.visit()) return finish()
            if (row.guard == LinearRow.ALWAYS || context.boolValue(row.guard) != null) continue
            val first = scratch.find(row.first) ?: continue
            val second = scratch.find(row.second) ?: continue
            if (first.root != second.root || (first.root != zero &&
                    row.firstCoefficient * first.sign + row.secondCoefficient * second.sign != 0L)
            ) continue
            val firstOffset = signed(first.offset, row.firstCoefficient) ?: continue
            val secondOffset = signed(second.offset, row.secondCoefficient) ?: continue
            val value = checkedDifferenceSum(firstOffset, secondOffset) ?: continue
            val truth = when (row.relation) {
                LinearOp.EQ -> value == row.bound
                LinearOp.NE -> value != row.bound
                LinearOp.LE -> value <= row.bound
                LinearOp.GE -> value >= row.bound
            }
            val literal = Lit.make(row.guard, truth)
            val reason = scratch.reason(first, second, null) ?: continue
            if (reason.any { context.boolValue(Lit.variable(it)) != !Lit.isPositive(it) }) continue
            stop.charge(reason.size + 1L)
            if (stop()) return ComponentResult.Indeterminate
            val result = context.imply(literal, SearchExplanation(reason + literal))
            if (result !is ComponentResult.Consistent) return result
            val received = receive(literal)
            if (received !is ComponentResult.Consistent) return received
            published = true
        }
        return finish()
    }

    class Order(
        val factor: Int,
        val row: Int,
        val sourceTruth: Boolean,
        val terms: List<Pair<Int, Long>>,
        val bound: Long,
        val upper: Boolean,
        val premises: IntArray,
    )

    private class Scratch(size: Int, private val stop: Cancellation) {
        private val parent = IntArray(size) { it }
        private val rank = IntArray(size)
        private val sign = LongArray(size) { 1L }
        private val offset = LongArray(size)
        private val reasons = arrayOfNulls<IntArray>(size)
        private var visits = 0
        private var retainedPremises = 0
        var declined = false
            private set

        fun visit(): Boolean {
            stop.charge(1)
            if (++visits > MAX_VISITS || stop()) declined = true
            return !declined
        }

        fun find(variable: Int): Path? {
            var root = variable
            var multiplier = 1L
            var translation = 0L
            val edges = ArrayList<Int>()
            while (parent[root] != root) {
                if (!visit()) return null
                translation = checkedDifferenceSum(translation, signed(offset[root], multiplier) ?: return null)
                    ?: return null
                multiplier *= sign[root]
                edges += root
                root = parent[root]
            }
            return Path(root, multiplier, translation, edges)
        }

        fun reason(first: Path, second: Path, premise: Int?): IntArray? =
            reason(listOf(first, second), listOfNotNull(premise))

        private fun reason(paths: List<Path>, premises: List<Int>): IntArray? {
            val result = LinkedHashSet<Int>()
            result += premises
            for (path in paths) {
                for (edge in path.edges) {
                    for (literal in checkNotNull(reasons[edge])) {
                        if (!visit()) return null
                        result += literal
                    }
                }
            }
            stop.charge(result.size + 1L)
            if (stop()) return null
            return result.toIntArray()
        }

        private fun normalize(row: Row, first: Path, second: Path): Predicate? {
            if (!visit() || first.root == second.root) return null
            val firstOffset = signed(first.offset, row.firstCoefficient) ?: return null
            val secondOffset = signed(second.offset, row.secondCoefficient) ?: return null
            val constant = checkedDifferenceSum(firstOffset, secondOffset) ?: return null
            val bound = checkedDifferenceSubtract(row.bound, constant) ?: return null
            val reverse = first.root > second.root
            val firstRoot = if (reverse) second.root else first.root
            val secondRoot = if (reverse) first.root else second.root
            val firstSign = if (reverse) row.secondCoefficient * second.sign else row.firstCoefficient * first.sign
            val secondSign = if (reverse) row.firstCoefficient * first.sign else row.secondCoefficient * second.sign
            val rhs = signed(bound, firstSign) ?: return null
            stop.charge(6L)
            if (stop()) return null
            val expression = Expression(firstRoot, secondRoot,
                if (secondRoot == parent.lastIndex) -1L else firstSign * secondSign)
            return Predicate(expression, rhs, firstSign < 0L)
        }

        fun tighten(
            exclusions: List<Fact>,
            orders: List<Fact>,
            eligible: (Int, Int, Boolean) -> Boolean,
            observe: ((String) -> Unit)?,
        ): List<Tightened> {
            if (exclusions.isEmpty() || orders.isEmpty()) return emptyList()
            val weak = HashMap<Endpoint, Fact>()
            for (fact in orders) {
                if (!visit()) break
                val first = find(fact.row.first) ?: continue
                val second = find(fact.row.second) ?: continue
                val predicate = normalize(fact.row, first, second) ?: continue
                val sourceUpper = (fact.row.relation == LinearOp.LE) != predicate.negated
                val upper = sourceUpper == fact.truth
                val bound = if (fact.truth) predicate.bound else {
                    checkedDifferenceSum(predicate.bound, if (upper) -1L else 1L) ?: continue
                }
                val key = Endpoint(Side(predicate.expression, upper), bound)
                if (key !in weak) {
                    stop.charge(2L)
                    if (stop()) break
                    weak[key] = fact
                    observe?.invoke("ALIASED_ORDER_ATOM_WEAK_BOUND")
                }
            }
            val tightened = ArrayList<Tightened>()
            for (fact in exclusions) {
                if (!visit()) break
                if (!eligible(fact.row.factor, fact.row.index, fact.truth)) continue
                val first = find(fact.row.first) ?: continue
                val second = find(fact.row.second) ?: continue
                val predicate = normalize(fact.row, first, second) ?: continue
                for (upper in listOf(true, false)) {
                    if (!visit()) break
                    val side = Side(predicate.expression, upper)
                    val order = weak[Endpoint(side, predicate.bound)] ?: continue
                    if (rawExpression(fact.row) == rawExpression(order.row)) continue
                    checkedDifferenceSum(predicate.bound, if (upper) -1L else 1L) ?: continue
                    val sourceUpper = upper != predicate.negated
                    val bound = checkedDifferenceSum(fact.row.bound, if (sourceUpper) -1L else 1L) ?: continue
                    stop.charge(3L)
                    if (stop()) break
                    tightened += Tightened(bound, sourceUpper, fact, order)
                    observe?.invoke("ALIASED_ORDER_ATOM_TIGHTENED")
                }
            }
            return tightened
        }

        private fun rawExpression(row: Row): Expression {
            stop.charge(4L)
            val reverse = row.first > row.second
            val first = if (reverse) row.second else row.first
            val second = if (reverse) row.first else row.second
            return Expression(first, second,
                if (second == parent.lastIndex) -1L else row.firstCoefficient * row.secondCoefficient)
        }

        fun orderProof(known: Tightened): IntArray? {
            val paths = ArrayList<Path>(4)
            for (fact in listOf(known.exclusion, known.order)) {
                paths += find(fact.row.first) ?: return null
                paths += find(fact.row.second) ?: return null
            }
            val premises = listOfNotNull(known.exclusion.premise, known.order.premise)
            val proof = reason(paths, premises) ?: return null
            return proof.takeIf { it.size <= MAX_PREMISES }
        }

        fun join(row: Row, premise: Int?) {
            val first = find(row.first) ?: return
            val second = find(row.second) ?: return
            if (first.root == second.root) return
            val relationSign = -row.firstCoefficient * row.secondCoefficient
            val rhs = signed(row.bound, row.firstCoefficient) ?: return
            val moved = checkedDifferenceSum(signed(second.offset, relationSign) ?: return, rhs) ?: return
            val delta = checkedDifferenceSum(moved, signed(first.offset, -1L) ?: return) ?: return
            val rootSign = first.sign * relationSign * second.sign
            val rootOffset = signed(delta, first.sign) ?: return
            val proof = reason(first, second, premise) ?: return
            if (retainedPremises + proof.size > MAX_PREMISES) {
                declined = true
                return
            }
            val zero = parent.lastIndex
            // The fixed zero coordinate must remain a root even when another tree has higher rank.
            val reverse = first.root == zero || (second.root != zero && rank[first.root] > rank[second.root])
            val child = if (reverse) second.root else first.root
            val ancestor = if (reverse) first.root else second.root
            val translation = if (reverse) signed(rootOffset, -rootSign) ?: return else rootOffset
            parent[child] = ancestor
            sign[child] = rootSign
            offset[child] = translation
            reasons[child] = proof
            retainedPremises += proof.size
            if (rank[first.root] == rank[second.root]) rank[ancestor]++
        }
    }

    private data class Path(val root: Int, val sign: Long, val offset: Long, val edges: List<Int>)
    private data class Expression(val first: Int, val second: Int, val secondSign: Long)
    private data class Predicate(val expression: Expression, val bound: Long, val negated: Boolean)
    private data class Side(val expression: Expression, val upper: Boolean)
    private data class Endpoint(val side: Side, val bound: Long)
    private class Fact(val row: Row, val premise: Int?, val truth: Boolean)
    private class Tightened(val bound: Long, val upper: Boolean, val exclusion: Fact, val order: Fact)

    private data class Row(
        val factor: Int,
        val index: Int,
        val first: Int,
        val second: Int,
        val firstCoefficient: Long,
        val secondCoefficient: Long,
        val bound: Long,
        val relation: LinearOp,
        val guard: Int,
    )

    companion object {
        private const val MAX_ROWS = 65_536
        private const val MAX_PREMISES = 65_536
        private const val MAX_VISITS = 1_048_576

        fun prepare(
            model: Problem,
            stop: Cancellation,
            observe: ((String) -> Unit)? = null,
        ): ExactIntegerEqualityPropagation? {
            if (model.numIntVars == 0 || model.numIntVars >= MAX_ROWS) return null
            val rows = ArrayList<Row>()
            var visited = 0
            var hasEquality = false
            for ((factorIndex, factor) in model.factors.withIndex()) {
                stop.charge(1)
                if (++visited > MAX_VISITS || stop()) return null
                val form = factor.linearForm as? LinearForm.Conjunction ?: continue
                for ((rowIndex, row) in form.rows.withIndex()) {
                    stop.charge(1)
                    if (++visited > MAX_VISITS || stop()) return null
                    if (row.constants !is IntegerConstants || row.strict || row.size !in 1..2) continue
                    if (!row.isIntegerOnly || (0 until row.size).any { row.coeff(it) !in listOf(-1L, 1L) }) continue
                    val first = Term.intVar(row.ref(0))
                    val second = if (row.size == 1) model.numIntVars else Term.intVar(row.ref(1))
                    if (first !in 0 until model.numIntVars ||
                        (row.size == 2 && second !in 0 until model.numIntVars) ||
                        (row.activator != LinearRow.ALWAYS && row.activator !in 0 until model.numBoolVars)
                    ) return null
                    if (rows.size == MAX_ROWS) return null
                    stop.charge(9)
                    if (stop()) return null
                    rows += Row(
                        factorIndex, rowIndex, first, second, row.coeff(0),
                        if (row.size == 1) -row.coeff(0) else row.coeff(1),
                        row.bound, row.relation, row.activator,
                    )
                    hasEquality = hasEquality || row.relation == LinearOp.EQ || row.relation == LinearOp.NE
                }
            }
            return if (hasEquality) ExactIntegerEqualityPropagation(rows, model.numIntVars, observe) else null
        }

        private fun signed(value: Long, sign: Long): Long? = when {
            sign == 1L -> value
            value == Long.MIN_VALUE -> null
            else -> -value
        }
    }
}
