package com.eignex.klause.presolve.linear

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntegerConstants
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.presolve.PassDelta
import com.eignex.klause.presolve.equivalentLinear
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.CheckedLongOverflowException
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.MutableIntLongMap
import com.eignex.klause.util.MutableIntObjectMap
import com.eignex.klause.util.addExact
import com.eignex.klause.util.mulExact
import com.eignex.klause.util.subExact
import kotlin.math.abs

/**
 * Common linear sub-sum extraction (the contract-direction counterpart of [AffineSingletons], which
 * *expands* a variable into its definition). When an equality defines a variable as a linear sub-sum
 * `y = B + Σ_j A_j·x_j` (unit pivot on `y`, at least two partner terms), any other [Linear] row that
 * contains that whole sub-sum with a common integer multiplier `k` — every `x_j` at coefficient `k·A_j`
 * — has `k·Σ_j A_j·x_j` rewritten to the single term `k·y` (the bound absorbing `k·B`). The partner
 * terms collapse into one variable, shrinking the row's arity and the LP relaxation's density.
 *
 * Solution-set preserving and needs no reconstruction: the defining equality is retained, so
 * `Σ_j A_j·x_j = y − B` holds in every solution and the rewritten row is equivalent term-for-term.
 */
internal object LinearSubSumAggregation {

    /** Above this factor count the candidate scan is skipped — the reduction is a density optimisation,
     *  not a correctness need, so forgoing it on giant models keeps presolve bounded (sound). */
    private const val MAX_FACTORS = 200_000
    private const val MAX_SCAN_WORK = 1_000_000L
    private const val SCAN_WORK_PER_TERM = 8L
    private const val CANCEL_POLL_MASK = 63L

    private class Work(private val cancellation: Cancellation) {
        var visits = 0L
            private set
        private var limit = Long.MAX_VALUE
        private var stopped = false

        fun visit(): Boolean {
            if (stopped) return false
            if (visits >= limit || ((visits and CANCEL_POLL_MASK) == 0L && cancellation())) {
                stopped = true
                return false
            }
            visits++
            cancellation.charge(1L)
            return true
        }

        fun boundScan(terms: Long, workPerTerm: Long = SCAN_WORK_PER_TERM) {
            limit = visits + minOf(MAX_SCAN_WORK, workPerTerm * terms)
        }

        fun canScan(estimate: Long): Boolean = estimate <= limit - visits
    }

    private class Incidence {
        val rows = IntArrayList()
        var termVisits = 0L

        fun add(index: Int, width: Int) {
            rows.add(index)
            termVisits += 1L + 2L * width
        }
    }

    /** A sub-sum definition `y = B + Σ A_j·x_j` read off a unit-pivot equality: the pivot [y], the
     *  constant [b] (`B`), and the partner form `x_j ↦ A_j` in [form]. [defIndex] is the equality's own
     *  factor index, so it is never rewritten against itself. */
    private class Definition(val defIndex: Int, val y: Int, val b: Long, val form: Map<Int, Long>)

    private const val MAX_SPARSE_WIDTH = 4096
    private const val MAX_RATIOS = 8
    private const val MAX_SCALE = 4L
    private const val SPARSE_WORK_PER_TERM = 128L

    private class SparseRow(val linear: Linear, val terms: Map<Int, Long>, val magnitude: Long)

    fun sparsificationEligible(factorCount: Int): Boolean = factorCount <= MAX_FACTORS

    fun sparsifyRows(problem: Problem, cancellation: Cancellation = Cancellation.Never): PassDelta {
        if (!sparsificationEligible(problem.factors.size) || cancellation()) return PassDelta()
        val work = Work(cancellation)
        val rows = arrayOfNulls<SparseRow>(problem.factors.size)
        val equalities = IntArrayList()
        for (i in rows.indices) {
            if (!work.visit()) return PassDelta()
            val linear = problem.factors[i].equivalentLinear() ?: continue
            val constants = linear.integerConstants ?: continue
            if (linear.vars.size > MAX_SPARSE_WIDTH || constants.bound !in -OVERFLOW_GUARD..OVERFLOW_GUARD) continue
            val terms = LinkedHashMap<Int, Long>()
            var magnitude = 0L
            var safe = true
            for (j in linear.vars.indices) {
                if (!work.visit()) return PassDelta()
                val coefficient = constants.coeff(j)
                if (coefficient !in -OVERFLOW_GUARD..OVERFLOW_GUARD) safe = false
                if (coefficient != 0L) terms[linear.vars[j]] = coefficient
                if (safe) magnitude = maxOf(magnitude, abs(coefficient))
            }
            if (!safe || terms.isEmpty()) continue
            rows[i] = SparseRow(linear, terms, magnitude)
            if (linear.op == LinearOp.EQ) equalities.add(i)
        }
        if (equalities.isEmpty()) return PassDelta()

        val incidence = MutableIntObjectMap<Incidence>()
        var indexedTerms = 0L
        for (i in rows.indices) {
            if (!work.visit()) return PassDelta()
            val row = rows[i] ?: continue
            for (variable in row.terms.keys) {
                if (!work.visit()) return PassDelta()
                incidence.getOrPut(variable) { Incidence() }.add(i, row.terms.size)
                indexedTerms++
            }
        }
        work.boundScan(indexedTerms, SPARSE_WORK_PER_TERM)
        val dropped = IntArrayList()
        val added = ArrayList<Factor>()
        val rewritten = HashSet<Int>()
        val retained = HashSet<Int>()
        for (e in 0 until equalities.size) {
            if (!work.visit()) break
            val premise = equalities[e]
            if (premise in rewritten) continue
            val equality = checkNotNull(rows[premise])
            val estimate = sparseScanEstimate(equality, incidence, work) ?: break
            if (!work.canScan(estimate)) continue
            val seen = HashSet<Int>()
            for (variable in equality.terms.keys) {
                if (!work.visit()) break
                val candidates = checkNotNull(incidence[variable]).rows
                for (r in 0 until candidates.size) {
                    if (!work.visit()) break
                    val target = candidates[r]
                    if (target == premise || target in rewritten || target in retained || !seen.add(target)) continue
                    val row = checkNotNull(rows[target])
                    val replacement = sparsify(row, equality, work) ?: continue
                    dropped.add(target)
                    added.add(replacement)
                    rewritten.add(target)
                    // A premise used by any completed replacement must survive unchanged.
                    retained.add(premise)
                }
            }
        }
        return PassDelta(droppedIndices = dropped.toIntArray(), addedFactors = added)
    }

    private fun sparseScanEstimate(equality: SparseRow, incidence: MutableIntObjectMap<Incidence>, work: Work): Long? {
        var estimate = 0L
        for (variable in equality.terms.keys) {
            if (!work.visit()) return null
            val bucket = checkNotNull(incidence[variable])
            estimate += (MAX_RATIOS + 2L) *
                (bucket.termVisits + bucket.rows.size.toLong() * equality.terms.size)
        }
        return estimate
    }

    private fun sparsify(row: SparseRow, equality: SparseRow, work: Work): Linear? {
        val ratios = LinkedHashSet<Pair<Long, Long>>()
        for ((variable, coefficient) in row.terms) {
            if (!work.visit()) return null
            val pivot = equality.terms[variable] ?: continue
            var a = abs(coefficient)
            var b = abs(pivot)
            while (b != 0L) {
                val remainder = a % b
                a = b
                b = remainder
            }
            val scale = abs(pivot) / a
            if (scale > MAX_SCALE) continue
            val multiplier = (coefficient / a) * if (pivot < 0L) -1L else 1L
            ratios.add(scale to multiplier)
            if (ratios.size == MAX_RATIOS) break
        }
        for ((scale, multiplier) in ratios) {
            if (!work.visit()) return null
            subtractEquality(row, equality, scale, multiplier, work)?.let { return it }
        }
        return null
    }

    // Positive scaling preserves every comparison, including disequality. No column is eliminated.
    private fun subtractEquality(
        row: SparseRow,
        equality: SparseRow,
        scale: Long,
        multiplier: Long,
        work: Work,
    ): Linear? {
        return try {
            val terms = LinkedHashMap<Int, Long>()
            for ((variable, coefficient) in row.terms) {
                if (!work.visit()) return null
                terms[variable] = mulExact(scale, coefficient)
            }
            for ((variable, coefficient) in equality.terms) {
                if (!work.visit()) return null
                val difference = subExact(terms[variable] ?: 0L, mulExact(multiplier, coefficient))
                if (difference == 0L) terms.remove(variable) else terms[variable] = difference
            }
            if (terms.size >= row.terms.size) {
                null
            } else {
                val growthLimit = minOf(OVERFLOW_GUARD, MAX_SCALE * maxOf(row.magnitude, equality.magnitude))
                for (coefficient in terms.values) {
                    if (!work.visit()) return null
                    if (coefficient !in -growthLimit..growthLimit) return null
                }
                val bound = subExact(
                    mulExact(scale, checkNotNull(row.linear.integerConstants).bound),
                    mulExact(multiplier, checkNotNull(equality.linear.integerConstants).bound),
                )
                if (bound !in -OVERFLOW_GUARD..OVERFLOW_GUARD || !work.visit()) {
                    null
                } else {
                    // Linear retains one zero term to represent a constant comparison.
                    if (terms.isEmpty()) terms[row.linear.vars[0]] = 0L
                    Linear(terms.values.toLongArray(), terms.keys.toIntArray(), row.linear.op, bound)
                }
            }
        } catch (_: CheckedLongOverflowException) {
            null
        }
    }

    fun aggregateSubSums(problem: Problem, cancellation: Cancellation = Cancellation.Never): PassDelta {
        val factors = problem.factors
        if (factors.size > MAX_FACTORS || cancellation()) return PassDelta()
        val work = Work(cancellation)

        val rows = arrayOfNulls<Linear>(factors.size)
        for (i in factors.indices) {
            if (!work.visit()) return PassDelta()
            rows[i] = factors[i].equivalentLinear()
        }
        val definitions = collectDefinitions(rows, work)
        if (definitions.isEmpty()) return PassDelta()

        // Index Linear rows by variable, so a definition is matched only against rows that mention its
        // rarest partner rather than the whole model.
        val rowsByVar = MutableIntObjectMap<Incidence>()
        var indexedTerms = 0L
        for (i in factors.indices) {
            if (!work.visit()) return PassDelta()
            val f = rows[i]
            if (f != null && f.integerConstants != null) {
                for (v in f.vars) {
                    if (!work.visit()) return PassDelta()
                    rowsByVar.getOrPut(v) { Incidence() }.add(i, f.vars.size)
                    indexedTerms++
                }
            }
        }
        work.boundScan(indexedTerms)

        val dropped = IntArrayList()
        val added = ArrayList<Factor>()
        val rewritten = HashSet<Int>() // one substitution per row per pass; the round engine re-runs
        val retainedDefinitions = HashSet<Int>()
        for (def in definitions) {
            if (!work.visit()) break
            if (def.defIndex in rewritten) continue
            val anchor = rarestPartner(def.form, rowsByVar, work) ?: continue
            // Matching reads a candidate and its partners; a successful rewrite reads the row again.
            val estimate = anchor.termVisits + anchor.rows.size.toLong() * def.form.size
            if (!work.canScan(estimate)) continue
            for (r in 0 until anchor.rows.size) {
                if (!work.visit()) break
                val i = anchor.rows[r]
                if (i == def.defIndex || i in rewritten || i in retainedDefinitions) continue
                val row = rows[i] ?: continue
                val constants = row.integerConstants ?: continue
                val k = matchMultiplier(row, constants, def, work) ?: continue
                val replacement = rewrite(row, constants, def, k, work) ?: continue
                added.add(replacement)
                dropped.add(i)
                rewritten.add(i)
                // Every substitution keeps its defining equality as an unchanged premise.
                retainedDefinitions.add(def.defIndex)
            }
        }
        if (dropped.isEmpty()) return PassDelta()
        return PassDelta(droppedIndices = dropped.toIntArray(), addedFactors = added)
    }

    /** Every unit-pivot sub-sum definition among the equality [Linear]s: an equality `Σ c_j·v_j = b` with
     *  a variable `v_p` of coefficient `±1` and at least two other terms yields `v_p = c_p·b + Σ_{j≠p}
     *  (−c_p·c_j)·v_j`. The lowest-id unit-coefficient variable is the pivot (deterministic). */
    private fun collectDefinitions(factors: Array<Linear?>, work: Work): List<Definition> {
        val out = ArrayList<Definition>()
        for (i in factors.indices) {
            if (!work.visit()) return emptyList()
            val f = factors[i] ?: continue
            if (f.op != LinearOp.EQ || f.vars.size < 3) continue
            val c = f.integerConstants ?: continue
            val p = unitPivotIndex(f.vars, c, work) ?: continue
            val sign = c.coeff(p) // ±1
            val definition = try {
                val form = HashMap<Int, Long>(f.vars.size)
                // A zero coefficient names no partner and cannot determine a multiplier.
                for (j in f.vars.indices) {
                    if (!work.visit()) return emptyList()
                    if (j != p && c.coeff(j) != 0L) form[f.vars[j]] = mulExact(-sign, c.coeff(j))
                }
                if (form.size < 2) continue
                Definition(i, f.vars[p], mulExact(sign, c.bound), form)
            } catch (_: CheckedLongOverflowException) {
                continue
            }
            out.add(definition)
        }
        return out
    }

    /** Index of the lowest-id variable with a `±1` coefficient, or `null` if none — the deterministic
     *  pivot whose isolation keeps every folded coefficient integral. */
    private fun unitPivotIndex(vars: IntArray, constants: IntegerConstants, work: Work): Int? {
        var best = -1
        for (j in vars.indices) {
            if (!work.visit()) return null
            if (constants.coeff(j) != 1L && constants.coeff(j) != -1L) continue
            if (best < 0 || vars[j] < vars[best]) best = j
        }
        return if (best < 0) null else best
    }

    /** The partner variable of [form] contained in the fewest rows — the cheapest anchor to scan. */
    private fun rarestPartner(form: Map<Int, Long>, rowsByVar: MutableIntObjectMap<Incidence>, work: Work): Incidence? {
        var best: Incidence? = null
        for (v in form.keys) {
            if (!work.visit()) return null
            val rows = rowsByVar[v] ?: return null // a partner in no row: no match possible
            if (best == null || rows.rows.size < best.rows.size) best = rows
        }
        return best
    }

    /** The common integer multiplier `k` with which [row] contains [def]'s whole partner form — every
     *  partner `x_j` present at coefficient `k·A_j`, one nonzero `k` for all — or `null` if the form is
     *  absent, partial, or unevenly scaled (so no exact sub-sum to fold). */
    private fun matchMultiplier(row: Linear, constants: IntegerConstants, def: Definition, work: Work): Long? {
        val coeffByVar = MutableIntLongMap(row.vars.size)
        for (j in row.vars.indices) {
            if (!work.visit()) return null
            coeffByVar.put(row.vars[j], constants.coeff(j))
        }
        var k = 0L
        for ((x, a) in def.form) {
            if (!work.visit()) return null
            if (!coeffByVar.containsKey(x)) return null // partner missing → not a full sub-sum
            val c = coeffByVar.getOrDefault(x, 0L)
            if (a == 0L) return null // a zero form coefficient is no term (collectDefinitions drops these)
            if (c == Long.MIN_VALUE && a == -1L) return null
            if (c % a != 0L) return null
            val ratio = c / a
            if (ratio == 0L) return null
            if (k == 0L) {
                k = ratio
            } else if (k != ratio) {
                return null
            }
        }
        return if (k == 0L) null else k
    }

    /** Magnitude past which the `k·B` bound shift (or its addition to the row bound) is treated as
     *  overflow-risky and the fold declined — real coefficients sit far below this. */
    private const val OVERFLOW_GUARD = 1_000_000_000_000_000L

    /** [row] with `k·Σ A_j·x_j` replaced by the single term `k·y`: drop every partner term, add `k` to
     *  `y`'s coefficient, and shift the bound by `k·B` (moving `k·(y − B)` to the left leaves the same
     *  relation). Coalescing in [Linear]'s constructor folds `k·y` into any existing `y` term. */
    private fun rewrite(row: Linear, constants: IntegerConstants, def: Definition, k: Long, work: Work): Linear? {
        return try {
            val shift = mulExact(k, def.b)
            if (shift !in -OVERFLOW_GUARD..OVERFLOW_GUARD || constants.bound !in -OVERFLOW_GUARD..OVERFLOW_GUARD) {
                null
            } else {
                val vars = IntArrayList(row.vars.size)
                val coeffs = ArrayList<Long>(row.vars.size)
                for (j in row.vars.indices) {
                    if (!work.visit()) return null
                    if (def.form.containsKey(row.vars[j])) continue // absorbed into k·y
                    if (row.vars[j] == def.y) addExact(constants.coeff(j), k)
                    vars.add(row.vars[j])
                    coeffs.add(constants.coeff(j))
                }
                vars.add(def.y)
                coeffs.add(k)
                Linear(coeffs.toLongArray(), vars.toIntArray(), row.op, addExact(constants.bound, shift))
            }
        } catch (_: CheckedLongOverflowException) {
            null
        }
    }
}
