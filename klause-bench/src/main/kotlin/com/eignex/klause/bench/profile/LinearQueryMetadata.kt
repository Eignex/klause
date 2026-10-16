package com.eignex.klause.bench.profile

import com.eignex.klause.ir.Problem
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.magnitudeBitLength
import java.lang.reflect.Field

internal class QueryAnalysisBudget {
    private val started = System.nanoTime()
    private var remaining = 2_000_000L
    private var sincePoll = 128L

    fun step(units: Long = 1L) {
        require(units >= 0L && units <= remaining) { "analysis metadata scan cap" }
        remaining -= units
        sincePoll += units
        if (sincePoll >= 128L) {
            poll()
            sincePoll = 0L
        }
    }

    fun poll() {
        check(System.nanoTime() - started < 10_000_000_000L) { "analysis time cap" }
    }
}

internal class LinearQueryMetadata(private val budget: QueryAnalysisBudget) {
    private val fields = HashMap<Pair<Class<*>, String>, Field>()
    private var maximumBits = 0
    private var premiseScalarsKnown = true

    fun inspect(state: Any, component: Any): QueryEligibilityInput {
        maximumBits = 0
        premiseScalarsKnown = true
        val model = required(state, "model")
        val base = required(state, "baseModel")
        val matrix = list(model, "matrix")
        val columns = list(model, "columns")
        val declared = list(base, "columns")
        val metadata = list(model, "rows")
        val identities = list(required(state, "rows"), "identities")
        val rhs = list(model, "rightHandSide")
        val costs = list(required(model, "objective"), "costs")
        require(matrix.size <= MAX_DIMENSION && metadata.size <= MAX_DIMENSION)
        require(columns.size == matrix.size + metadata.size && columns.size <= MAX_DIMENSION)
        require(declared.size == columns.size && costs.size == columns.size && rhs.size == metadata.size)
        require(identities.size == metadata.size)
        val source = required(component, "model") as Problem
        var consistent = matrix.size == source.numIntVars + source.numRealVars
        val finite = required(component, "finiteIntVars") as IntArray
        require(finite.size <= MAX_DIMENSION)
        val protected = BooleanArray(matrix.size)
        for (integer in finite) {
            budget.step()
            if (integer !in 0 until source.numIntVars || source.numRealVars + integer !in protected.indices) {
                consistent = false
                protected.fill(true)
            } else {
                protected[source.numRealVars + integer] = true
            }
        }
        val definitions = definitions(component)
        val byRow = arrayOfNulls<Map<*, *>>(metadata.size)
        for ((terms, column) in definitions) {
            budget.step()
            require(column is Int && terms is Map<*, *>) { "unsupported definition mapping" }
            if (column >= columns.size) continue
            if (column !in matrix.size until columns.size || byRow[column - matrix.size] != null) {
                consistent = false
                continue
            }
            require(terms.size <= MAX_ENTRIES)
            for ((index, coefficient) in terms) {
                budget.step()
                if (index !is Int || index !in matrix.indices || coefficient !is BigFraction || coefficient.isZero) {
                    consistent = false
                }
            }
            byRow[column - matrix.size] = terms
        }
        val asserted = BooleanArray(columns.size)
        val assertions = list(state, "activeAssertions")
        require(assertions.size <= MAX_ENTRIES)
        for (assertion in assertions) {
            budget.step()
            val column = required(requireNotNull(assertion), "column") as Int
            require(column in asserted.indices)
            asserted[column] = true
        }
        val rowColumns = Array(metadata.size) { ArrayList<Int>() }
        var entries = 0
        for ((column, raw) in matrix.withIndex()) {
            require(raw is List<*> && raw.size <= MAX_ENTRIES)
            for (entry in raw) {
                budget.step()
                require(++entries <= MAX_ENTRIES)
                val item = requireNotNull(entry)
                val row = required(item, "row") as Int
                require(row in metadata.indices)
                rowColumns[row] += column
                val value = number(required(item, "number"))
                byRow[row]?.let { terms ->
                    val expected = terms[column] as? BigFraction
                    if (expected == null || value != expected.negated()) consistent = false
                }
            }
        }
        val sourceColumns = matrix.indices.map { column ->
            budget.step()
            val active = requireNotNull(columns[column])
            val original = requireNotNull(declared[column])
            QuerySourceColumn(
                free(active) && free(original) && !asserted[column],
                number(requireNotNull(costs[column])).isZero,
                number(required(active, "origin")).isZero,
                protected[column],
                sides(active),
            )
        }
        val rows = metadata.indices.map { row ->
            budget.step()
            val active = requireNotNull(columns[matrix.size + row])
            val original = requireNotNull(declared[matrix.size + row])
            val info = requireNotNull(metadata[row])
            val identity = requireNotNull(identities[row])
            val terms = byRow[row]
            val known = terms != null && terms.size == rowColumns[row].size &&
                required(identity, "id") == row.toLong()
            if (terms != null && !known) consistent = false
            val blockers = buildSet {
                if (!free(original)) add(QueryRowBlocker.DECLARED_BOUND)
                if (!free(active)) add(QueryRowBlocker.ACTIVE_BOUND)
                if (asserted[matrix.size + row]) add(QueryRowBlocker.ASSERTION)
                val integral = required(active, "integral") as Boolean ||
                    required(original, "integral") as Boolean
                if (integral) add(QueryRowBlocker.INTEGER_LOGICAL)
                if (!number(requireNotNull(costs[matrix.size + row])).isZero) add(QueryRowBlocker.NONZERO_COST)
                if (!number(required(active, "origin")).isZero) add(QueryRowBlocker.NONZERO_ORIGIN)
                if (!(required(info, "global") as Boolean)) add(QueryRowBlocker.CONDITIONAL)
                if (required(info, "strict") as Boolean) add(QueryRowBlocker.STRICT)
                if (get(info, "premises") != null) add(QueryRowBlocker.PREMISES)
                if (!number(requireNotNull(rhs[row])).isZero) add(QueryRowBlocker.NONZERO_RHS)
                if (!(required(identity, "active") as Boolean) || get(identity, "depth") != null ||
                    get(identity, "suspendedAt") != null
                ) {
                    add(QueryRowBlocker.SCOPED_OR_INACTIVE)
                }
            }
            QueryDefinitionRow(known, blockers, rowColumns[row].toIntArray(), sides(active), premiseUnits(info))
        }
        val objective = required(model, "objective")
        for (field in listOf("constant", "scale", "externalConstant")) number(required(objective, field))
        return QueryEligibilityInput(sourceColumns, rows, consistent, maximumBits, premiseScalarsKnown)
    }

    private fun definitions(component: Any): Map<*, *> {
        val delegate = required(component, "system\$delegate") as Lazy<*>
        if (!delegate.isInitialized()) return emptyMap<Any, Any>()
        val definitions = required(requireNotNull(delegate.value), "definitions") as Map<*, *>
        require(definitions.size <= MAX_DIMENSION)
        return definitions
    }

    private fun free(column: Any): Boolean = sides(column) == 0

    private fun sides(column: Any): Int {
        val bounds = required(column, "bounds")
        var count = 0
        for (name in listOf("lower", "upper")) {
            val side = get(bounds, name) ?: continue
            budget.step()
            count++
            number(required(side, "number"))
            if (get(side, "premises") != null) premiseScalarsKnown = false
        }
        return count
    }

    private fun premiseUnits(row: Any): Long {
        val premises = get(row, "premises") ?: return 0L
        premiseScalarsKnown = false
        val bounds = list(premises, "bounds")
        val literals = list(premises, "literals")
        require(bounds.size <= MAX_ENTRIES && literals.size <= MAX_ENTRIES)
        return bounds.size * 3L + literals.size
    }

    private fun number(number: Any): BigFraction {
        budget.step()
        val fraction = required(number, "value") as BigFraction
        maximumBits = maxOf(maximumBits, fraction.num.magnitudeBitLength(), fraction.den.magnitudeBitLength())
        return fraction
    }

    private fun list(owner: Any, name: String): List<*> = required(owner, name) as List<*>
    private fun required(owner: Any, name: String): Any = requireNotNull(get(owner, name)) { "missing $name" }
    private fun get(owner: Any, name: String): Any? = fields.getOrPut(owner.javaClass to name) {
        owner.javaClass.getDeclaredField(name).apply { isAccessible = true }
    }.get(owner)

    companion object {
        private const val MAX_DIMENSION = 16384
        private const val MAX_ENTRIES = 131072
    }
}
