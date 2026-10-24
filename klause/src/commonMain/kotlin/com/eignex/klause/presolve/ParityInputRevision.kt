package com.eignex.klause.presolve

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.bool.Xor
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.ScanMeter
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.isZero
import com.eignex.klause.util.rem

internal class ParityInputRevision(problem: Problem, private val cancellation: Cancellation) {
    private val boolColumns = HashMap<Int, Int>()
    private val intColumns = HashMap<Int, Int>()
    private val units = HashMap<Int, Int>()
    private var previousBools = emptyMap<Int, Int>()
    private var previousRanges = emptyMap<Int, Pair<Long?, Long?>>()
    private var dirty = true
    private var complete = true
    private var revision = 0L

    init {
        val work = ScanMeter(cancellation)
        for (factor in problem.factors) {
            if (!record(factor, 1, work)) {
                complete = false
                break
            }
        }
        complete = complete && work.check()
    }

    fun recordDelta(
        factors: Array<Factor>,
        dropped: IntArray,
        added: List<Factor>,
        deductionsChanged: Boolean = true,
    ) {
        if (!complete) return
        val work = ScanMeter(cancellation)
        for (index in dropped) {
            if (!record(factors[index], -1, work)) {
                complete = false
                return
            }
        }
        for (factor in added) {
            if (!record(factor, 1, work)) {
                complete = false
                return
            }
        }
        // Propagation can change participating pins and ranges even when the delta has no parity row.
        dirty = dirty || deductionsChanged
        complete = work.check()
    }

    fun current(problem: Problem): Long? {
        val root = (problem as? BakedProblem)?.rootDeductions as? PropagationResult.Implied
        return current({ root?.boolValueOrNull(it) }) { v ->
            val domain = (problem as? BakedProblem)?.rootIntDomain(v) ?: problem.intDomainOrNull(v)
            if (domain != null) domain.min to domain.max else {
                val bounds = problem.intBounds
                (if (bounds.hasLower(v)) bounds.lower(v) else null) to
                    (if (bounds.hasUpper(v)) bounds.upper(v) else null)
            }
        }
    }

    fun current(boolValue: (Int) -> Boolean?, range: (Int) -> Pair<Long?, Long?>): Long? {
        if (!complete) return null
        if (!dirty) return revision
        val work = ScanMeter(cancellation)
        val bools = HashMap<Int, Int>()
        for (v in boolColumns.keys) {
            if (!work.visit(1L)) return null
            bools[v] = booleanPremise(v, boolValue(v))
        }
        val ranges = HashMap<Int, Pair<Long?, Long?>>()
        for (v in intColumns.keys) {
            if (!work.visit(1L)) return null
            ranges[v] = range(v)
        }
        if (!work.check()) return null
        if (bools != previousBools || ranges != previousRanges) revision++
        previousBools = bools
        previousRanges = ranges
        dirty = false
        return revision
    }

    private fun booleanPremise(v: Int, root: Boolean?): Int {
        val positive = units.containsKey(Lit.make(v, true))
        val negative = units.containsKey(Lit.make(v, false))
        return when {
            (positive && negative) || (positive && root == false) || (negative && root == true) -> 3
            positive || root == true -> 2
            negative || root == false -> 1
            else -> 0
        }
    }

    private fun record(factor: Factor, direction: Int, work: ScanMeter): Boolean {
        if (!work.visit(1L)) return false
        when (factor) {
            is Clause -> if (factor.literals.size == 1) {
                val lit = factor.literals[0]
                val wasPresent = units.containsKey(lit)
                update(units, lit, direction)
                if (boolColumns.containsKey(Lit.variable(lit)) && wasPresent != units.containsKey(lit)) dirty = true
            }
            is Xor -> {
                revision++
                dirty = true
                for (lit in factor.literals) {
                    if (!work.visit(1L)) return false
                    update(boolColumns, Lit.variable(lit), direction)
                }
            }
            is Linear -> {
                if (factor.op != LinearOp.EQ || factor.realVars.isNotEmpty()) return true
                val narrow = factor.integerConstants
                val wide = factor.integralConstants ?: return true
                revision++
                dirty = true
                for (k in factor.vars.indices) {
                    if (!work.visit(1L)) return false
                    val odd = narrow?.let { it.coeff(k) and 1L != 0L }
                        ?: !wide.exactCoeff(k).rem(bigIntOf(2)).isZero()
                    if (odd) update(intColumns, factor.vars[k], direction)
                }
            }
        }
        return true
    }

    private fun update(counts: MutableMap<Int, Int>, key: Int, direction: Int) {
        val count = (counts[key] ?: 0) + direction
        if (count == 0) counts.remove(key) else counts[key] = count
    }
}
