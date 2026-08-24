package com.eignex.klause.theory.lia

import com.eignex.klause.factor.arithmetic.ComparisonClause
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.solver.ProblemPipeline
import com.eignex.klause.solver.ProblemSpec
import com.eignex.klause.solver.generalLiaWitnessBound
import com.eignex.klause.solver.pipeline
import com.eignex.klause.solver.search.ComponentCheck
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.SearchBrancher
import com.eignex.klause.solver.search.SearchContext
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.SearchModel
import com.eignex.klause.solver.search.SearchTheoryDecision
import com.eignex.klause.solver.search.TheoryComponent
import com.eignex.klause.theory.Theory
import com.eignex.klause.theory.TheoryCheck
import com.eignex.klause.theory.TheoryContext
import com.eignex.klause.theory.TheoryResult
import com.ionspin.kotlin.bignum.integer.BigInteger

/** An exact General LIA assignment, independent of CP's Long-backed Sample. */
data class GeneralLiaAssignment(
    /** Boolean values indexed by model Boolean variable id. */
    val bools: BooleanArray,
    /** Integer values indexed by model integer variable id. */
    val ints: Array<BigInteger>,
)

/** Compatibility names for General LIA's shared theory outcomes. */
object GeneralLiaResult {
    typealias Sat = TheoryResult.Sat<GeneralLiaAssignment>
    typealias Unsat = TheoryResult.Unsat
    typealias Unknown = TheoryResult.Unknown
}

/**
 * Complete finite-witness search for open General LIA.
 *
 * The model remains arbitrary-precision throughout this path: wide source rows are read directly and
 * branch intervals, split points, row sums, and witnesses are BigInteger. LP keeps its existing
 * double-based relaxation role in the finite CP path; it is deliberately not used as a certificate here.
 */
class GeneralLiaSolver(override val model: ProblemSpec) : Theory<GeneralLiaAssignment> {
    private val witnessBound = requireNotNull(model.generalLiaWitnessBound())

    init {
        require(model.pipeline() == ProblemPipeline.GENERAL_LIA) {
            "general LIA search requires an open pure-integer linear model"
        }
    }

    override fun check(bools: BooleanArray, context: TheoryContext): TheoryCheck<GeneralLiaAssignment> {
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
            BigInterval(
                context.intLowerBound(v)?.let { maxOf(lo, BigInteger.fromLong(it)) } ?: lo,
                context.intUpperBound(v)?.let { minOf(hi, BigInteger.fromLong(it)) } ?: hi,
            )
        }
        if (domains.any { it.lo > it.hi }) return TheoryCheck.Infeasible()
        val search = Search(domains, bools, BooleanArray(model.numBoolVars) { true }, context)
        return when (val outcome = search.run()) {
            is GeneralLiaSearchOutcome.Found -> TheoryCheck.Sat(GeneralLiaAssignment(bools.copyOf(), outcome.values))
            GeneralLiaSearchOutcome.Infeasible -> TheoryCheck.Infeasible()
            GeneralLiaSearchOutcome.Cancelled -> TheoryCheck.Cancelled
            GeneralLiaSearchOutcome.BudgetCapped -> TheoryCheck.Cancelled
        }
    }

    private inner class Search(
        private val domains: Array<BigInterval>,
        private val bools: BooleanArray,
        private val boolAssigned: BooleanArray,
        private val context: TheoryContext,
    ) {
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
            if (!context.consumeCheck()) {
                return GeneralLiaSearchOutcome.BudgetCapped
            }
            if (context.cancelled()) return GeneralLiaSearchOutcome.Cancelled
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

/**
 * Incremental General LIA participant.
 *
 * Its finite-witness intervals stay arbitrary precision and theory-local. Each bisection is carried
 * as an opaque [SearchDecision.Theory], so [com.eignex.klause.solver.search.SearchSession] owns the
 * resulting tree, retraction, limits, and restart lifecycle.
 */
class GeneralLiaSearchComponent(
    private val model: ProblemSpec,
    theoryIntVars: IntArray = IntArray(model.numIntVars) { it },
    private val modelContribution: ((GeneralLiaAssignment, SearchModel) -> Unit)? = null,
) : TheoryComponent,
    SearchBrancher {
    private val witnessBound = requireNotNull(model.generalLiaWitnessBound())
    private val theoryIntVars = theoryIntVars.copyOf()
    private val bools = IntArray(model.numBoolVars) { UNASSIGNED }
    private val boolLevels = IntArray(model.numBoolVars) { -1 }
    private val domainsByLevel = HashMap<Int, Array<BigInterval>>()
    private var assignment: GeneralLiaAssignment? = null
    private var outcome: ComponentCheck? = null

    init {
        require(model.generalLiaWitnessBound() != null) {
            "general LIA component requires a pure-integer linear model with a finite witness bound"
        }
        require(this.theoryIntVars.all { it in 0 until model.numIntVars }) {
            "General LIA branch variables must be source integer columns"
        }
    }

    override fun initialize(context: SearchContext): ComponentResult {
        domainsByLevel[0] = initialDomains(context)
        return if (domainsByLevel.getValue(
                0,
            ).any { it.lo > it.hi }
        ) {
            ComponentResult.Conflict()
        } else {
            ComponentResult.Consistent
        }
    }

    override fun assert(decision: SearchDecision, context: SearchContext): ComponentResult {
        when (decision) {
            is SearchDecision.Bool -> {
                val variable = decision.literal ushr 1
                bools[variable] = if (decision.literal and 1 == 0) TRUE else FALSE
                boolLevels[variable] = context.decisionLevel
            }

            is SearchDecision.Theory -> (decision.decision as? GeneralLiaDecision)?.let { branch ->
                domainsByLevel[context.decisionLevel] = branch.domains
            }

            is SearchDecision.IntAtMost, is SearchDecision.IntAtLeast, is SearchDecision.IntEqual -> Unit
        }
        assignment = null
        outcome = null
        return ComponentResult.Consistent
    }

    override fun retract(decisionLevel: Int) {
        for (variable in bools.indices) {
            if (boolLevels[variable] > decisionLevel) {
                bools[variable] = UNASSIGNED
                boolLevels[variable] = -1
            }
        }
        domainsByLevel.keys.removeAll { it > decisionLevel }
        assignment = null
        outcome = null
    }

    override fun nextBranch(context: SearchContext): List<SearchDecision>? {
        if (bools.any { it == UNASSIGNED } || outcome != null) return null
        if (context.cancelled() || !context.consumeCheck()) {
            outcome = ComponentCheck.Indeterminate
            return null
        }
        val domains = currentDomains().copyOf()
        if (!applyPublishedBounds(domains, context)) {
            outcome = ComponentCheck.Infeasible()
            return null
        }
        if (!propagateEqualities(domains) || !factorsPossible(domains)) {
            outcome = ComponentCheck.Infeasible()
            return null
        }
        val variable = widestOpenVariable(domains)
        if (variable < 0) {
            outcome = if (factorsHold(domains)) {
                assignment = GeneralLiaAssignment(
                    BooleanArray(bools.size) { bools[it] == TRUE },
                    Array(domains.size) { domains[it].lo },
                )
                ComponentCheck.Feasible
            } else {
                ComponentCheck.Infeasible()
            }
            return null
        }
        val original = domains[variable]
        val middle = original.lo + (original.hi - original.lo) / BigInteger.fromInt(2)
        return listOf(
            decision(domains.withInterval(variable, BigInterval(original.lo, middle))),
            decision(domains.withInterval(variable, BigInterval(middle + BigInteger.ONE, original.hi))),
        )
    }

    override fun check(context: SearchContext): ComponentCheck = outcome ?: ComponentCheck.Indeterminate

    override fun contributeModel(model: SearchModel, context: SearchContext) {
        assignment?.let { value ->
            model.put(this, value)
            modelContribution?.invoke(value, model)
        }
    }

    private fun initialDomains(context: SearchContext): Array<BigInterval> = Array(model.numIntVars) { variable ->
        val lo = if (model.intBounds.hasLower(variable)) {
            maxOf(-witnessBound, BigInteger.fromLong(model.intBounds.lower(variable)))
        } else {
            -witnessBound
        }
        val hi = if (model.intBounds.hasUpper(variable)) {
            minOf(witnessBound, BigInteger.fromLong(model.intBounds.upper(variable)))
        } else {
            witnessBound
        }
        BigInterval(
            context.intLowerBound(variable)?.let { maxOf(lo, BigInteger.fromLong(it)) } ?: lo,
            context.intUpperBound(variable)?.let { minOf(hi, BigInteger.fromLong(it)) } ?: hi,
        )
    }

    private fun currentDomains(): Array<BigInterval> = domainsByLevel.entries.maxByOrNull { it.key }?.value
        ?: error("General LIA component was not initialized")

    private fun decision(domains: Array<BigInterval>): SearchDecision.Theory =
        SearchDecision.Theory(GeneralLiaDecision(domains))

    private fun Array<BigInterval>.withInterval(variable: Int, interval: BigInterval): Array<BigInterval> =
        copyOf().also { it[variable] = interval }

    private fun propagateEqualities(domains: Array<BigInterval>): Boolean {
        var changed: Boolean
        do {
            changed = false
            for (factor in model.factors) {
                val row = when (factor) {
                    is Linear -> if (factor.op == LinearOp.EQ) LiaRow.of(factor) else null

                    is ReifiedLinear -> if (bools[factor.auxBoolVar] == TRUE && factor.op == LinearOp.EQ) {
                        LiaRow.of(
                            factor,
                        )
                    } else {
                        null
                    }

                    else -> null
                } ?: continue
                for (index in row.vars.indices) {
                    val coefficient = row.coeffs[index]
                    if (coefficient == BigInteger.ZERO) continue
                    val rest = rowRangeExcept(row, index, domains)
                    val lowerProduct = row.bound - rest.hi
                    val upperProduct = row.bound - rest.lo
                    val implied = if (coefficient > BigInteger.ZERO) {
                        BigInterval(ceilDiv(lowerProduct, coefficient), floorDiv(upperProduct, coefficient))
                    } else {
                        BigInterval(ceilDiv(upperProduct, coefficient), floorDiv(lowerProduct, coefficient))
                    }
                    val variable = row.vars[index]
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

    private fun rowRangeExcept(row: LiaRow, skipped: Int, domains: Array<BigInterval>): BigInterval {
        var lo = BigInteger.ZERO
        var hi = BigInteger.ZERO
        for (index in row.vars.indices) {
            if (index == skipped) continue
            val domain = domains[row.vars[index]]
            if (row.coeffs[index] >= BigInteger.ZERO) {
                lo += row.coeffs[index] * domain.lo
                hi += row.coeffs[index] * domain.hi
            } else {
                lo += row.coeffs[index] * domain.hi
                hi += row.coeffs[index] * domain.lo
            }
        }
        return BigInterval(lo, hi)
    }

    private fun applyPublishedBounds(domains: Array<BigInterval>, context: SearchContext): Boolean {
        for (variable in domains.indices) {
            val current = domains[variable]
            val lower = context.intLowerBound(variable)?.let { BigInteger.fromLong(it) } ?: current.lo
            val upper = context.intUpperBound(variable)?.let { BigInteger.fromLong(it) } ?: current.hi
            val narrowed = BigInterval(maxOf(current.lo, lower), minOf(current.hi, upper))
            if (narrowed.lo > narrowed.hi) return false
            domains[variable] = narrowed
        }
        return true
    }

    private fun widestOpenVariable(domains: Array<BigInterval>): Int {
        var result = -1
        var width = BigInteger.ZERO
        for (variable in theoryIntVars) {
            val candidate = domains[variable].hi - domains[variable].lo
            if (candidate > width) {
                width = candidate
                result = variable
            }
        }
        return result
    }

    private fun factorsPossible(domains: Array<BigInterval>): Boolean = model.factors.all { factor ->
        when (factor) {
            is Clause -> factor.literals.any { literal ->
                bools[literal ushr 1] == UNASSIGNED ||
                    bools[literal ushr 1] == truth(
                        literal,
                    )
            }

            is Linear -> relationPossible(rowRange(factor, domains), factor.op, linearBound(factor))

            is ReifiedLinear -> bools[factor.auxBoolVar] == UNASSIGNED || relationPossibleForTruth(
                rowRange(factor, domains),
                factor.op,
                reifiedBound(factor),
                bools[factor.auxBoolVar] == TRUE,
            )

            is ComparisonClause -> factor.vars.indices.any { index ->
                relationPossible(
                    domains[factor.vars[index]],
                    factor.ops[index],
                    BigInteger.fromLong(factor.consts[index]),
                )
            }

            else -> false
        }
    }

    private fun factorsHold(domains: Array<BigInterval>): Boolean = model.factors.all { factor ->
        when (factor) {
            is Clause -> factor.literals.any { literal -> bools[literal ushr 1] == truth(literal) }

            is Linear -> relationHolds(rowValue(factor, domains), factor.op, linearBound(factor))

            is ReifiedLinear -> relationHolds(
                rowValue(factor, domains),
                factor.op,
                reifiedBound(factor),
            ) == (bools[factor.auxBoolVar] == TRUE)

            is ComparisonClause -> factor.vars.indices.any { index ->
                relationHolds(
                    domains[factor.vars[index]].lo,
                    factor.ops[index],
                    BigInteger.fromLong(factor.consts[index]),
                )
            }

            else -> false
        }
    }

    private fun rowRange(factor: Linear, domains: Array<BigInterval>): BigInterval = rowRange(
        factor.vars,
        Array(factor.vars.size) { factor.wideCoeffs?.get(it) ?: BigInteger.fromLong(factor.coeff(it)) },
        domains,
    )

    private fun rowRange(factor: ReifiedLinear, domains: Array<BigInterval>): BigInterval = rowRange(
        factor.vars,
        Array(factor.vars.size) { factor.wideCoeffs?.get(it) ?: BigInteger.fromLong(factor.coeff(it)) },
        domains,
    )

    private fun rowRange(vars: IntArray, coeffs: Array<BigInteger>, domains: Array<BigInterval>): BigInterval {
        var lo = BigInteger.ZERO
        var hi = BigInteger.ZERO
        for (index in vars.indices) {
            val domain = domains[vars[index]]
            if (coeffs[index] >= BigInteger.ZERO) {
                lo += coeffs[index] * domain.lo
                hi += coeffs[index] * domain.hi
            } else {
                lo += coeffs[index] * domain.hi
                hi += coeffs[index] * domain.lo
            }
        }
        return BigInterval(lo, hi)
    }

    private fun rowValue(factor: Linear, domains: Array<BigInterval>): BigInteger = rowValue(
        factor.vars,
        Array(factor.vars.size) { factor.wideCoeffs?.get(it) ?: BigInteger.fromLong(factor.coeff(it)) },
        domains,
    )

    private fun rowValue(factor: ReifiedLinear, domains: Array<BigInterval>): BigInteger = rowValue(
        factor.vars,
        Array(factor.vars.size) { factor.wideCoeffs?.get(it) ?: BigInteger.fromLong(factor.coeff(it)) },
        domains,
    )

    private fun rowValue(vars: IntArray, coeffs: Array<BigInteger>, domains: Array<BigInterval>): BigInteger {
        var sum = BigInteger.ZERO
        for (index in vars.indices) sum += coeffs[index] * domains[vars[index]].lo
        return sum
    }

    private fun linearBound(factor: Linear): BigInteger = factor.wideBound ?: BigInteger.fromLong(factor.bound)

    private fun reifiedBound(factor: ReifiedLinear): BigInteger = factor.wideBound ?: BigInteger.fromLong(factor.bound)

    private fun relationPossible(range: BigInterval, op: LinearOp, bound: BigInteger): Boolean = when (op) {
        LinearOp.LE -> range.lo <= bound
        LinearOp.EQ -> range.lo <= bound && bound <= range.hi
        LinearOp.GE -> range.hi >= bound
        LinearOp.NE -> range.lo != range.hi || range.lo != bound
    }

    private fun relationPossibleForTruth(range: BigInterval, op: LinearOp, bound: BigInteger, truth: Boolean): Boolean =
        if (truth) {
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

    private companion object {
        const val UNASSIGNED = -1
        const val FALSE = 0
        const val TRUE = 1

        fun truth(literal: Int): Int = if (literal and 1 == 0) TRUE else FALSE
    }
}

private data class GeneralLiaDecision(val domains: Array<BigInterval>) : SearchTheoryDecision

private data class LiaRow(val vars: IntArray, val coeffs: Array<BigInteger>, val bound: BigInteger) {
    companion object {
        fun of(factor: Linear): LiaRow = LiaRow(
            factor.vars,
            Array(
                factor.vars.size,
            ) { index -> factor.wideCoeffs?.get(index) ?: BigInteger.fromLong(factor.coeff(index)) },
            factor.wideBound ?: BigInteger.fromLong(factor.bound),
        )

        fun of(factor: ReifiedLinear): LiaRow = LiaRow(
            factor.vars,
            Array(
                factor.vars.size,
            ) { index -> factor.wideCoeffs?.get(index) ?: BigInteger.fromLong(factor.coeff(index)) },
            factor.wideBound ?: BigInteger.fromLong(factor.bound),
        )
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
