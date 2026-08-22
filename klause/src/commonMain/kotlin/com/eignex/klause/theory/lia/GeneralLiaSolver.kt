package com.eignex.klause.theory.lia

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.factor.arithmetic.ComparisonClause
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.solver.ProblemPipeline
import com.eignex.klause.solver.ProblemSpec
import com.eignex.klause.solver.generalLiaWitnessBound
import com.eignex.klause.solver.pipeline
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.solver.result.TerminationReason
import com.ionspin.kotlin.bignum.integer.BigInteger

/** An exact General LIA assignment, independent of CP's Long-backed Sample. */
data class GeneralLiaAssignment(
    /** Boolean values indexed by model Boolean variable id. */
    val bools: BooleanArray,
    /** Integer values indexed by model integer variable id. */
    val ints: Array<BigInteger>,
)

/** Result of an exact General LIA satisfiability search. */
sealed interface GeneralLiaResult {
    /** Search telemetry collected by this solver. */
    val stats: SolveStats

    /** Satisfiable, carrying an exact assignment. */
    data class Sat(
        /** The satisfying Boolean/integer assignment. */
        val assignment: GeneralLiaAssignment,
        override val stats: SolveStats = SolveStats.EMPTY,
    ) : GeneralLiaResult

    /** Proven infeasible. */
    data class Unsat(override val stats: SolveStats = SolveStats.EMPTY) : GeneralLiaResult

    /** Indeterminate because an explicit search budget or cancellation interrupted the search. */
    data class Unknown(
        /** The interruption reason. */
        val reason: TerminationReason,
        override val stats: SolveStats = SolveStats.EMPTY,
    ) : GeneralLiaResult
}

/**
 * Complete finite-witness search for open General LIA.
 *
 * The model remains arbitrary-precision throughout this path: wide source rows are read directly and
 * branch intervals, split points, row sums, and witnesses are BigInteger. LP keeps its existing
 * double-based relaxation role in the finite CP path; it is deliberately not used as a certificate here.
 */
class GeneralLiaSolver(private val model: ProblemSpec) {
    private val witnessBound = requireNotNull(model.generalLiaWitnessBound())

    init {
        require(model.pipeline() == ProblemPipeline.GENERAL_LIA) {
            "general LIA search requires an open pure-integer linear model"
        }
    }

    /** Decide satisfiability without converting an integer bound, coefficient, or witness to Long. */
    fun solve(params: BacktrackParams = BacktrackParams()): GeneralLiaResult {
        val domains = Array(model.numIntVars) { v ->
            val lo = if (model.intBounds.hasLower(v)) {
                maxOf(-witnessBound, BigInteger.fromLong(model.intBounds.lower(v)))
            } else {
                -witnessBound
            }
            val hi = if (model.intBounds.hasUpper(v)) {
                minOf(witnessBound, BigInteger.fromLong(model.intBounds.upper(v)))
            } else {
                witnessBound
            }
            BigInterval(lo, hi)
        }
        if (domains.any { it.lo > it.hi }) return GeneralLiaResult.Unsat()
        val bools = BooleanArray(model.numBoolVars)
        val boolAssigned = BooleanArray(model.numBoolVars)
        val search = Search(domains, bools, boolAssigned, params)
        return when (val outcome = search.run()) {
            is GeneralLiaSearchOutcome.Found -> GeneralLiaResult.Sat(
                GeneralLiaAssignment(bools.copyOf(), outcome.values),
            )

            GeneralLiaSearchOutcome.Infeasible -> GeneralLiaResult.Unsat()

            GeneralLiaSearchOutcome.Cancelled ->
                GeneralLiaResult.Unknown(TerminationReason.Cancelled)

            GeneralLiaSearchOutcome.BudgetCapped ->
                GeneralLiaResult.Unknown(TerminationReason.BudgetExhausted)
        }
    }

    private inner class Search(
        private val domains: Array<BigInterval>,
        private val bools: BooleanArray,
        private val boolAssigned: BooleanArray,
        private val params: BacktrackParams,
    ) {
        private var instructionsLeft = minOf(params.maxDecisions, params.maxInstructions ?: Long.MAX_VALUE)

        fun run(): GeneralLiaSearchOutcome {
            val stack = ArrayDeque<Frame>()
            stack.addLast(Frame(domains.copyOf()))
            var completed: GeneralLiaSearchOutcome? = null
            while (stack.isNotEmpty()) {
                if (completed != null) {
                    val frame = stack.removeLast()
                    val outcome = completed
                    if (outcome !is GeneralLiaSearchOutcome.Infeasible) {
                        finish(frame)
                        completed = outcome
                        continue
                    }
                    when {
                        frame.bool >= 0 && frame.first -> {
                            frame.first = false
                            bools[frame.bool] = true
                            stack.addLast(frame)
                            stack.addLast(Frame(domains.copyOf()))
                            completed = null
                        }

                        frame.variable >= 0 && frame.first -> {
                            frame.first = false
                            domains[frame.variable] = BigInterval(frame.middle + BigInteger.ONE, frame.original.hi)
                            stack.addLast(frame)
                            stack.addLast(Frame(domains.copyOf()))
                            completed = null
                        }

                        else -> {
                            finish(frame)
                            completed = GeneralLiaSearchOutcome.Infeasible
                        }
                    }
                    continue
                }
                val frame = stack.last()
                completed = visit(frame, stack)
            }
            return requireNotNull(completed)
        }

        private fun visit(frame: Frame, stack: ArrayDeque<Frame>): GeneralLiaSearchOutcome? {
            if (instructionsLeft == 0L || params.nodeBudget?.exhausted() == true) {
                return GeneralLiaSearchOutcome.BudgetCapped
            }
            if (params.cancellation()) return GeneralLiaSearchOutcome.Cancelled
            instructionsLeft--
            params.nodeBudget?.spend()
            if (!propagateEqualities()) return GeneralLiaSearchOutcome.Infeasible
            if (!factorsPossible()) return GeneralLiaSearchOutcome.Infeasible
            val bool = boolAssigned.indexOfFirst { !it }
            if (bool >= 0) {
                boolAssigned[bool] = true
                bools[bool] = false
                frame.bool = bool
                stack.addLast(Frame(domains.copyOf()))
                return null
            }
            val variable = widestOpenVariable()
            if (variable < 0) {
                return if (factorsHold()) {
                    GeneralLiaSearchOutcome.Found(Array(domains.size) { domains[it].lo })
                } else {
                    GeneralLiaSearchOutcome.Infeasible
                }
            }
            val original = domains[variable]
            val middle = original.lo + (original.hi - original.lo) / BigInteger.fromInt(2)
            domains[variable] = BigInterval(original.lo, middle)
            frame.variable = variable
            frame.original = original
            frame.middle = middle
            stack.addLast(Frame(domains.copyOf()))
            return null
        }

        private fun finish(frame: Frame) {
            if (frame.bool >= 0) boolAssigned[frame.bool] = false
            for (v in domains.indices) domains[v] = frame.saved[v]
        }

        /**
         * Exact interval transfer for equality rows. The finite witness theorem makes every endpoint
         * finite; carrying an equality through those endpoints before splitting avoids turning a simple
         * wide affine chain into a Cartesian product of its variable ranges.
         */
        private fun propagateEqualities(): Boolean {
            var changed: Boolean
            do {
                changed = false
                for (factor in model.factors) {
                    val row = when (factor) {
                        is Linear -> if (factor.op == LinearOp.EQ) BigRow.of(factor) else null

                        is ReifiedLinear -> if (boolAssigned[factor.auxBoolVar] && bools[factor.auxBoolVar] &&
                            factor.op == LinearOp.EQ
                        ) {
                            BigRow.of(factor)
                        } else {
                            null
                        }

                        else -> null
                    } ?: continue
                    for (i in row.vars.indices) {
                        val coefficient = row.coeffs[i]
                        if (coefficient == BigInteger.ZERO) continue
                        val rest = rowRangeExcept(row, i)
                        val lowerProduct = row.bound - rest.hi
                        val upperProduct = row.bound - rest.lo
                        val implied = if (coefficient > BigInteger.ZERO) {
                            BigInterval(ceilDiv(lowerProduct, coefficient), floorDiv(upperProduct, coefficient))
                        } else {
                            BigInterval(ceilDiv(upperProduct, coefficient), floorDiv(lowerProduct, coefficient))
                        }
                        val variable = row.vars[i]
                        val current = domains[variable]
                        val narrowed = BigInterval(maxOf(current.lo, implied.lo), minOf(current.hi, implied.hi))
                        if (narrowed.lo > narrowed.hi) return false
                        if (narrowed != current) {
                            domains[variable] = narrowed
                            changed = true
                        }
                    }
                }
            } while (changed)
            return true
        }

        private fun rowRangeExcept(row: BigRow, skipped: Int): BigInterval {
            var lo = BigInteger.ZERO
            var hi = BigInteger.ZERO
            for (i in row.vars.indices) {
                if (i == skipped) continue
                val domain = domains[row.vars[i]]
                if (row.coeffs[i] >= BigInteger.ZERO) {
                    lo += row.coeffs[i] * domain.lo
                    hi += row.coeffs[i] * domain.hi
                } else {
                    lo += row.coeffs[i] * domain.hi
                    hi += row.coeffs[i] * domain.lo
                }
            }
            return BigInterval(lo, hi)
        }

        private fun widestOpenVariable(): Int {
            var result = -1
            var width = BigInteger.ZERO
            for (v in domains.indices) {
                val candidate = domains[v].hi - domains[v].lo
                if (candidate > width) {
                    width = candidate
                    result = v
                }
            }
            return result
        }

        private fun factorsPossible(): Boolean = model.factors.all { factor ->
            when (factor) {
                is Clause -> factor.literals.any(::literalPossible)

                is Linear -> relationPossible(rowRange(factor), factor.op, linearBound(factor))

                is ReifiedLinear -> {
                    if (!boolAssigned[factor.auxBoolVar]) {
                        true
                    } else {
                        relationPossibleForTruth(
                            rowRange(factor),
                            factor.op,
                            reifiedBound(factor),
                            bools[factor.auxBoolVar],
                        )
                    }
                }

                is ComparisonClause -> factor.vars.indices.any { i ->
                    relationPossible(
                        domains[factor.vars[i]],
                        factor.ops[i],
                        BigInteger.fromLong(factor.consts[i]),
                    )
                }

                else -> false
            }
        }

        private fun factorsHold(): Boolean = model.factors.all { factor ->
            when (factor) {
                is Clause -> factor.literals.any(::literalHolds)

                is Linear -> relationHolds(rowValue(factor), factor.op, linearBound(factor))

                is ReifiedLinear ->
                    relationHolds(rowValue(factor), factor.op, reifiedBound(factor)) == bools[factor.auxBoolVar]

                is ComparisonClause -> factor.vars.indices.any { i ->
                    relationHolds(domains[factor.vars[i]].lo, factor.ops[i], BigInteger.fromLong(factor.consts[i]))
                }

                else -> false
            }
        }

        private fun literalPossible(literal: Int): Boolean {
            val variable = literal ushr 1
            return !boolAssigned[variable] || bools[variable] == (literal and 1 == 0)
        }

        private fun literalHolds(literal: Int): Boolean {
            val variable = literal ushr 1
            return bools[variable] == (literal and 1 == 0)
        }

        private fun rowRange(factor: Linear): BigInterval = rowRange(
            factor.vars,
            Array(factor.vars.size) { i -> factor.wideCoeffs?.get(i) ?: BigInteger.fromLong(factor.coeff(i)) },
        )

        private fun rowRange(factor: ReifiedLinear): BigInterval = rowRange(
            factor.vars,
            Array(factor.vars.size) { i -> factor.wideCoeffs?.get(i) ?: BigInteger.fromLong(factor.coeff(i)) },
        )

        private fun rowRange(vars: IntArray, coeffs: Array<BigInteger>): BigInterval {
            var lo = BigInteger.ZERO
            var hi = BigInteger.ZERO
            for (i in vars.indices) {
                val domain = domains[vars[i]]
                if (coeffs[i] >= BigInteger.ZERO) {
                    lo += coeffs[i] * domain.lo
                    hi += coeffs[i] * domain.hi
                } else {
                    lo += coeffs[i] * domain.hi
                    hi += coeffs[i] * domain.lo
                }
            }
            return BigInterval(lo, hi)
        }

        private fun rowValue(factor: Linear): BigInteger = rowValue(
            factor.vars,
            Array(factor.vars.size) { i -> factor.wideCoeffs?.get(i) ?: BigInteger.fromLong(factor.coeff(i)) },
        )

        private fun rowValue(factor: ReifiedLinear): BigInteger = rowValue(
            factor.vars,
            Array(factor.vars.size) { i -> factor.wideCoeffs?.get(i) ?: BigInteger.fromLong(factor.coeff(i)) },
        )

        private fun rowValue(vars: IntArray, coeffs: Array<BigInteger>): BigInteger {
            var sum = BigInteger.ZERO
            for (i in vars.indices) sum += coeffs[i] * domains[vars[i]].lo
            return sum
        }
    }

    private class Frame(val saved: Array<BigInterval>) {
        var bool = -1
        var variable = -1
        lateinit var original: BigInterval
        lateinit var middle: BigInteger
        var first = true
    }

    private fun linearBound(factor: Linear): BigInteger = factor.wideBound ?: BigInteger.fromLong(factor.bound)

    private fun reifiedBound(factor: ReifiedLinear): BigInteger = factor.wideBound ?: BigInteger.fromLong(factor.bound)

    private fun relationPossible(range: BigInterval, op: LinearOp, bound: BigInteger): Boolean = when (op) {
        LinearOp.LE -> range.lo <= bound
        LinearOp.EQ -> range.lo <= bound && bound <= range.hi
        LinearOp.GE -> range.hi >= bound
        LinearOp.NE -> range.lo != range.hi || range.lo != bound
    }

    private fun relationPossibleForTruth(
        range: BigInterval,
        op: LinearOp,
        bound: BigInteger,
        truth: Boolean,
    ): Boolean = if (truth) {
        relationPossible(range, op, bound)
    } else {
        when (op) {
            LinearOp.LE -> range.hi > bound
            LinearOp.EQ -> range.lo != range.hi || range.lo != bound
            LinearOp.GE -> range.lo < bound
            LinearOp.NE -> range.lo <= bound && bound <= range.hi
        }
    }

    private fun relationHolds(value: BigInteger, op: LinearOp, bound: BigInteger): Boolean = when (op) {
        LinearOp.LE -> value <= bound
        LinearOp.EQ -> value == bound
        LinearOp.GE -> value >= bound
        LinearOp.NE -> value != bound
    }
}

private data class BigInterval(val lo: BigInteger, val hi: BigInteger)

private data class BigRow(val vars: IntArray, val coeffs: Array<BigInteger>, val bound: BigInteger) {
    companion object {
        fun of(factor: Linear): BigRow = BigRow(
            factor.vars,
            Array(factor.vars.size) { i -> factor.wideCoeffs?.get(i) ?: BigInteger.fromLong(factor.coeff(i)) },
            factor.wideBound ?: BigInteger.fromLong(factor.bound),
        )

        fun of(factor: ReifiedLinear): BigRow = BigRow(
            factor.vars,
            Array(factor.vars.size) { i -> factor.wideCoeffs?.get(i) ?: BigInteger.fromLong(factor.coeff(i)) },
            factor.wideBound ?: BigInteger.fromLong(factor.bound),
        )
    }
}

private fun floorDiv(a: BigInteger, b: BigInteger): BigInteger {
    val quotient = a / b
    return if (a % b != BigInteger.ZERO && (a < BigInteger.ZERO) != (b < BigInteger.ZERO)) {
        quotient - BigInteger.ONE
    } else {
        quotient
    }
}

private fun ceilDiv(a: BigInteger, b: BigInteger): BigInteger {
    val quotient = a / b
    return if (a % b != BigInteger.ZERO && (a < BigInteger.ZERO) == (b < BigInteger.ZERO)) {
        quotient + BigInteger.ONE
    } else {
        quotient
    }
}

private sealed interface GeneralLiaSearchOutcome {
    data class Found(val values: Array<BigInteger>) : GeneralLiaSearchOutcome
    data object Infeasible : GeneralLiaSearchOutcome
    data object Cancelled : GeneralLiaSearchOutcome
    data object BudgetCapped : GeneralLiaSearchOutcome
}
