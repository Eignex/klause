package com.eignex.klause.presolve

import com.eignex.klause.factor.table.Table
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.util.Cancellation

internal object TableValueSubstitution {
    private const val MAX_COLUMNS = 4096
    private const val MAX_VALUES = 64L
    private const val MAX_SCOPE = 64
    private const val MAX_CELLS = 65_536
    private const val MAX_WORK = 262_144

    private class Work(private val token: Cancellation) {
        private var left = MAX_WORK

        fun visit(): Boolean {
            if (left == 0 || token()) return false
            left--
            token.charge(1)
            return true
        }
    }

    fun reduce(problem: BakedProblem, context: PresolveContext): PassDelta {
        val objective = context.linearObjective ?: return PassDelta()
        if (context.solutionSetSensitive || problem.semantics.isApproximate || problem.numRealVars != 0 ||
            problem.numIntVars > MAX_COLUMNS || objective.realCoefficientValues.isNotEmpty() ||
            objective.intCoefficientValues.size > problem.numIntVars || context.cancellation()
        ) return PassDelta()
        val work = Work(context.cancellation)
        val readers = arrayOfNulls<MutableList<Table>>(problem.numIntVars)
        val eligible = BooleanArray(problem.numIntVars)
        for (v in eligible.indices) {
            if (!work.visit()) return PassDelta()
            val domain = problem.rootIntDomain(v)
            eligible[v] = objective.intCoefficientValues.getOrElse(v) { 0L } != 0L &&
                domain.max - domain.min in 1 until MAX_VALUES
        }
        var cells = 0
        for (factor in problem.factors) {
            if (!work.visit()) return PassDelta()
            val table = factor as? Table
            var supported = table != null && table.hi == null && table.arity <= MAX_SCOPE &&
                table.tuples.size <= MAX_CELLS
            if (supported) {
                checkNotNull(table)
                for (i in table.xs.indices) for (j in 0 until i) {
                    if (!work.visit()) return PassDelta()
                    if (table.xs[i] == table.xs[j]) supported = false
                }
            }
            for (v in factor.intVars) {
                if (!work.visit()) return PassDelta()
                if (!supported) eligible[v] = false
                if (!eligible[v]) continue
                checkNotNull(table)
                if (++cells > MAX_CELLS) return PassDelta()
                (readers[v] ?: ArrayList<Table>().also { readers[v] = it }).add(table)
            }
        }
        var domains: Array<IntDomain>? = null
        for (v in eligible.indices) {
            if (!work.visit()) return PassDelta()
            val occurrences = readers[v] ?: continue
            if (!eligible[v]) continue
            val original = problem.rootIntDomain(v)
            // Root domains can retain large historical bitsets after narrowing their endpoints.
            // Rebuild only the bounded span, charging its membership checks and fresh storage.
            var retained = IntDomain(original.min, original.max)
            for (offset in 0..(original.max - original.min).toInt()) {
                if (!work.visit()) return PassDelta()
                val value = original.min + offset
                if (value !in original) retained = retained.excludeValue(value)
            }
            val values = checkNotNull(retained.spanOrNull(MAX_VALUES))
            var changed = false
            val positive = objective.intCoefficientValues[v] > 0L
            for (i in 0 until values.size) {
                if (!work.visit()) return PassDelta()
                val a = values.valueAt(i)
                for (j in 0 until values.size) {
                    if (!work.visit()) return PassDelta()
                    val b = values.valueAt(j)
                    // Ordering compares exact costs without multiplying or subtracting Long values.
                    if ((if (positive) b >= a else b <= a) || b !in retained) continue
                    var verified = true
                    for (table in occurrences) {
                        if (!work.visit()) return PassDelta()
                        when (includes(table, v, a, b, work)) {
                            null -> return PassDelta()
                            false -> { verified = false; break }
                            true -> Unit
                        }
                    }
                    if (!verified) continue
                    for (cell in 0 until values.size) if (!work.visit()) return PassDelta()
                    retained = retained.excludeValue(a)
                    changed = true
                    break
                }
            }
            if (!changed) continue
            if (domains == null) {
                for (column in eligible.indices) if (!work.visit()) return PassDelta()
                domains = problem.rootIntDomains()
            }
            domains[v] = retained
        }
        if (!work.visit()) return PassDelta()
        // Every removed value reaches a retained cheaper value, possibly through later replacements.
        // Each step preserves every complete reader tuple with all other coordinates unchanged.
        return PassDelta(domains = domains)
    }

    private fun includes(table: Table, variable: Int, a: Long, b: Long, work: Work): Boolean? {
        var column = -1
        for (c in table.xs.indices) {
            if (!work.visit()) return null
            if (table.xs[c] == variable) column = c
        }
        check(column >= 0)
        for (row in 0 until table.numTuples) {
            if (!work.visit()) return null
            if (table.tuples[row * table.arity + column] != a) continue
            var found = false
            for (replacement in 0 until table.numTuples) {
                if (!work.visit()) return null
                if (table.tuples[replacement * table.arity + column] != b) continue
                var matches = true
                for (c in table.xs.indices) {
                    if (!work.visit()) return null
                    if (c != column && table.tuples[row * table.arity + c] !=
                        table.tuples[replacement * table.arity + c]
                    ) {
                        matches = false
                        break
                    }
                }
                if (matches) { found = true; break }
            }
            if (!found) return false
        }
        return true
    }
}
