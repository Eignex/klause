package com.eignex.klause.theory.lia

import com.eignex.klause.factor.arithmetic.ComparisonClause
import com.eignex.klause.factor.arithmetic.IntegralConstants
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.ProblemSpec
import com.eignex.klause.solver.search.ComponentCheck
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.SearchBrancher
import com.eignex.klause.solver.search.SearchContext
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.SearchModel
import com.eignex.klause.solver.search.SearchSession
import com.eignex.klause.solver.search.SearchTheoryDecision
import com.eignex.klause.solver.search.TheoryComponent
import com.eignex.klause.theory.Theory
import com.eignex.klause.theory.TheoryCheck
import com.eignex.klause.theory.TheoryContext
import com.eignex.klause.util.MutableIntObjectMap
import com.ionspin.kotlin.bignum.integer.BigInteger

/** An exact General LIA assignment, independent of CP's Long-backed Sample. */
data class GeneralLiaAssignment(
    /** Boolean values indexed by model Boolean variable id. */
    val bools: BooleanArray,
    /** Integer values indexed by model integer variable id. */
    val ints: Array<BigInteger>,
)

/**
 * Factors walked between budget polls, chosen from how wide the witness box is.
 *
 * A fixed stride cannot serve both ends of this route. The work per factor is `BigInteger` arithmetic over
 * the domain endpoints, so on a box of a few hundred thousand bits one factor already costs milliseconds
 * and a stride of 256 overshoots a budget many times over; on a narrow box the same stride is what keeps
 * the check from showing against the arithmetic it guards. Scaling the stride by the bit width tracks the
 * cost it is amortising.
 */
internal fun pollStrideFor(witnessBits: Int): Int = (POLL_BIT_BUDGET / witnessBits.coerceAtLeast(1))
    .coerceIn(1, MAX_POLL_FACTORS)

/** Bits of endpoint arithmetic a single poll interval is allowed to cover. */
private const val POLL_BIT_BUDGET = 65_536

/** Stride ceiling, used where the endpoints are narrow enough that the poll itself is the cost. */
private const val MAX_POLL_FACTORS = 256

/**
 * Widest operand equality propagation will multiply or divide.
 *
 * Propagation multiplies box endpoints by row coefficients before dividing, and past this width a single
 * `BigInteger` division outlasts a solve deadline — which no polling schedule can repair, since the
 * operation itself cannot be interrupted.
 *
 * The answer is to skip the row, not the model. Narrowing is optional work: declining it leaves the
 * domains sound and merely less tight, so the search still runs and can still decide. Refusing the model
 * outright would trade every answer on it for the ones propagation could not afford.
 */
internal const val MAX_LIA_PROPAGATION_BITS = 16_384

/**
 * Whether narrowing a row over [vars] with [coeffs] would multiply or divide past
 * [MAX_LIA_PROPAGATION_BITS].
 *
 * Measured once per row over its widest coefficient and widest live endpoint, so the guard costs
 * `O(vars)` rather than the `O(vars²)` the narrowing itself would.
 */
private fun rowExceedsArithmetic(vars: IntArray, coeffs: Array<BigInteger>, domains: Array<BigInterval>): Boolean {
    var widest = 0
    for (index in vars.indices) {
        val domain = domains[vars[index]]
        val product = coeffs[index].bitLength() + maxOf(domain.lo.bitLength(), domain.hi.bitLength())
        if (product > widest) widest = product
    }
    return widest > MAX_LIA_PROPAGATION_BITS
}

/**
 * Complete finite-witness search for open General LIA.
 *
 * The model remains arbitrary-precision throughout this path: wide source rows are read directly and
 * branch intervals, split points, row sums, and witnesses are BigInteger. LP keeps its existing
 * double-based relaxation role in the finite CP path; it is deliberately not used as a certificate here.
 */

class GeneralLiaSolver(override val model: ProblemSpec) : Theory<GeneralLiaAssignment> {
    private val witnessBound = model.generalLiaWitnessBound()
    private val pollStride = witnessBound?.let { pollStrideFor(it.bitLength()) } ?: MAX_POLL_FACTORS

    init {
        require(model.admitsGeneralLia()) {
            "general LIA search requires an open pure-integer linear model"
        }
    }

    override fun check(bools: BooleanArray, context: TheoryContext): TheoryCheck<GeneralLiaAssignment> {
        val witnessBound = witnessBound ?: return TheoryCheck.Cancelled
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
            val narrowed = propagateEqualities() ?: return GeneralLiaSearchOutcome.Cancelled
            if (!narrowed) return GeneralLiaSearchOutcome.Infeasible
            val possible = factorsPossible() ?: return GeneralLiaSearchOutcome.Cancelled
            if (!possible) return GeneralLiaSearchOutcome.Infeasible
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
         * Narrow the domains through the equality rows until nothing moves, or null when the budget was
         * spent first.
         *
         * The fixpoint is unbounded in principle — each pass may narrow an interval and license another —
         * and every pass walks every factor in [BigInteger], so on a large model one call to this is long
         * enough that a poll at the node above it never lands. Null is a third answer on purpose: `false`
         * here means infeasible, and a stopped sweep must not claim that.
         */

        // Looks at the budget once per pollStride units of work, so a sweep over a large model notices
        // it. A unit is one term of one row, not one factor: even with [rowRange] tracked as a running
        // total, a row with many terms is real work on its own, and a factor-boundary poll would let one
        // very wide row run unchecked.
        private var untilPoll = pollStride

        private fun budgetSpent(): Boolean {
            if (--untilPoll > 0) return false
            untilPoll = pollStride
            return context.cancelled()
        }

        private fun propagateEqualities(): Boolean? {
            var changed: Boolean
            do {
                if (context.cancelled()) return null
                changed = false
                for (factor in model.factors) {
                    if (budgetSpent()) return null
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
                    // Too wide to narrow within a deadline: leave this row's domains as they are. The
                    // sweep stays sound, only less tight, and the search keeps its shot at the model.
                    if (rowExceedsArithmetic(row.vars, row.coeffs, domains)) continue
                    var rowRange = rowRange(row)
                    for (i in row.vars.indices) {
                        if (budgetSpent()) return null
                        val coefficient = row.coeffs[i]
                        if (coefficient == BigInteger.ZERO) continue
                        val current = domains[row.vars[i]]
                        val contribution = termRange(coefficient, current)
                        val rest = BigInterval(
                            rowRange.lo - contribution.lo,
                            rowRange.hi - contribution.hi,
                        )
                        val lowerProduct = row.bound - rest.hi
                        val upperProduct = row.bound - rest.lo
                        val implied = if (coefficient > BigInteger.ZERO) {
                            BigInterval(ceilDiv(lowerProduct, coefficient), floorDiv(upperProduct, coefficient))
                        } else {
                            BigInterval(ceilDiv(upperProduct, coefficient), floorDiv(lowerProduct, coefficient))
                        }
                        val variable = row.vars[i]
                        val narrowed = BigInterval(maxOf(current.lo, implied.lo), minOf(current.hi, implied.hi))
                        if (narrowed.lo > narrowed.hi) return false
                        if (narrowed != current) {
                            domains[variable] = narrowed
                            val replacement = termRange(coefficient, narrowed)
                            rowRange = BigInterval(rest.lo + replacement.lo, rest.hi + replacement.hi)
                            changed = true
                        }
                    }
                }
            } while (changed)
            return true
        }

        private fun rowRange(row: BigRow): BigInterval = exactRowRange(
            row.vars,
            row.coeffs,
            domains,
            checkNotNull(witnessBound),
        )

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

        private fun factorsPossible(): Boolean? = model.factors.all { factor ->
            if (budgetSpent()) return null
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

        private fun rowRange(factor: Linear): BigInterval = rowRange(factor.vars, exactConstantsOf(factor))

        private fun rowRange(factor: ReifiedLinear): BigInterval = rowRange(factor.vars, factor.constants)

        private fun rowRange(vars: IntArray, coeffs: IntegralConstants): BigInterval = exactRowRange(
            vars,
            coeffs,
            domains,
            checkNotNull(witnessBound),
        )

        private fun rowValue(factor: Linear): BigInteger = rowValue(factor.vars, exactConstantsOf(factor))

        private fun rowValue(factor: ReifiedLinear): BigInteger = rowValue(factor.vars, factor.constants)

        private fun rowValue(vars: IntArray, coeffs: IntegralConstants): BigInteger {
            var sum = BigInteger.ZERO
            for (i in vars.indices) sum += coeffs.exactCoeff(i) * domains[vars[i]].lo
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

    private fun linearBound(factor: Linear): BigInteger = exactConstantsOf(factor).exactBound

    private fun reifiedBound(factor: ReifiedLinear): BigInteger = factor.constants.exactBound

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

/** Incremental General LIA search component. */
class GeneralLiaSearchComponent(
    private val model: ProblemSpec,
    theoryIntVars: IntArray = IntArray(model.numIntVars) { it },
    private val modelContribution: ((GeneralLiaAssignment, SearchModel) -> Unit)? = null,
) : TheoryComponent,
    SearchBrancher {
    private var witnessBound: BigInteger? = null
    private var pollStride = MAX_POLL_FACTORS
    private val theoryIntVars = theoryIntVars.copyOf()
    private val bools = IntArray(model.numBoolVars) { UNASSIGNED }
    private val boolLevels = IntArray(model.numBoolVars) { -1 }
    private val domainsByLevel = MutableIntObjectMap<Array<BigInterval>>()
    private var assignment: GeneralLiaAssignment? = null
    private var outcome: ComponentCheck? = null
    private var possibleDomains: Array<BigInterval>? = null
    private var possibleBools: IntArray? = null
    private var factorRanges: Array<BigInterval?>? = null

    init {
        require(model.admitsGeneralLia()) {
            "general LIA component requires a pure-integer linear model with a finite witness bound"
        }
        require(this.theoryIntVars.all { it in 0 until model.numIntVars }) {
            "General LIA branch variables must be source integer columns"
        }
    }

    override fun initialize(context: SearchContext): ComponentResult {
        val bound = model.generalLiaWitnessBound(
            context::pollGeneralLiaCancellation,
        ) ?: run {
            return ComponentResult.Indeterminate
        }
        witnessBound = bound
        pollStride = pollStrideFor(bound.bitLength())
        clearFactorCache()
        val initialDomains = initialDomains(context) ?: return ComponentResult.Indeterminate
        domainsByLevel.put(0, initialDomains)
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
                domainsByLevel.put(context.decisionLevel, branch.domains)
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
        domainsByLevel.removeKeysAbove(decisionLevel)
        assignment = null
        outcome = null
    }

    /**
     * A restart returns to the root without calling [initialize], so the per-factor cache would
     * otherwise survive across what is logically an unrelated search state. The cache is a pure
     * function of [bools] and [domainsByLevel], so a stale entry cannot itself be wrong — but
     * clearing it here removes the need for that argument to keep holding across future changes.
     */
    override fun onRestart(context: SearchContext) {
        clearFactorCache()
    }

    private fun clearFactorCache() {
        possibleDomains = null
        possibleBools = null
        factorRanges = null
    }

    override fun nextBranch(context: SearchContext): List<SearchDecision>? {
        if (bools.any { it == UNASSIGNED } || outcome != null) return null
        if (context.pollGeneralLiaCancellation() || !context.consumeCheck()) {
            outcome = ComponentCheck.Indeterminate
            return null
        }
        val domains = currentDomains().copyOf()
        val imported = applyPublishedBounds(domains, context)
        if (imported == null) {
            outcome = ComponentCheck.Indeterminate
            return null
        }
        if (!imported) {
            outcome = ComponentCheck.Infeasible()
            return null
        }
        val narrowed = propagateEqualities(domains, context)
        if (narrowed == null) {
            outcome = ComponentCheck.Indeterminate
            return null
        }
        val possible = factorsPossible(domains, context)
        if (possible == null) {
            outcome = ComponentCheck.Indeterminate
            return null
        }
        if (!narrowed || !possible) {
            outcome = ComponentCheck.Infeasible()
            return null
        }
        val variable = widestOpenVariable(domains)
        if (variable < 0) {
            val holds = factorsHold(domains, context)
            outcome = if (holds == null) {
                ComponentCheck.Indeterminate
            } else if (holds) {
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

    private fun initialDomains(context: SearchContext): Array<BigInterval>? {
        val witnessBound = checkNotNull(witnessBound)
        return Array(model.numIntVars) { variable ->
            if (!context.consumeGeneralLiaWork()) return null
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
    }

    private fun currentDomains(): Array<BigInterval> = domainsByLevel.valueAtMaxKey()
        ?: error("General LIA component was not initialized")

    private fun decision(domains: Array<BigInterval>): SearchDecision.Theory =
        SearchDecision.Theory(GeneralLiaDecision(domains))

    private fun Array<BigInterval>.withInterval(variable: Int, interval: BigInterval): Array<BigInterval> =
        copyOf().also { it[variable] = interval }

    /**
     * Narrow [domains] through the equality rows until nothing moves, or null when the budget was spent.
     *
     * Null is a third answer on purpose: `false` means the domains emptied, which licenses infeasible,
     * and a sweep that stopped early must not claim that. The walk is over every factor in [BigInteger],
     * so on a large model a single pass runs long enough that a poll at the branch above never lands.
     */
    private fun propagateEqualities(domains: Array<BigInterval>, context: SearchContext): Boolean? {
        var changed: Boolean
        var untilPoll = pollStride
        do {
            if (context.pollGeneralLiaCancellation()) return null
            changed = false
            for (factor in model.factors) {
                if (!context.consumeGeneralLiaWork()) return null
                if (--untilPoll <= 0) {
                    untilPoll = pollStride
                    if (context.pollGeneralLiaCancellation()) return null
                }
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
                // Too wide to narrow within a deadline: leave this row's domains as they are. The sweep
                // stays sound, only less tight, and the search keeps its shot at the model.
                if (rowExceedsArithmetic(row.vars, row.coeffs, domains)) continue
                var rowRange = rowRange(row, domains)
                for (index in row.vars.indices) {
                    if (--untilPoll <= 0) {
                        untilPoll = pollStride
                        if (context.pollGeneralLiaCancellation()) return null
                    }
                    val coefficient = row.coeffs[index]
                    if (coefficient == BigInteger.ZERO) continue
                    val current = domains[row.vars[index]]
                    val contribution = termRange(coefficient, current)
                    val rest = BigInterval(
                        rowRange.lo - contribution.lo,
                        rowRange.hi - contribution.hi,
                    )
                    val lowerProduct = row.bound - rest.hi
                    val upperProduct = row.bound - rest.lo
                    val implied = if (coefficient > BigInteger.ZERO) {
                        BigInterval(ceilDiv(lowerProduct, coefficient), floorDiv(upperProduct, coefficient))
                    } else {
                        BigInterval(ceilDiv(upperProduct, coefficient), floorDiv(lowerProduct, coefficient))
                    }
                    val variable = row.vars[index]
                    val narrowed = BigInterval(maxOf(current.lo, implied.lo), minOf(current.hi, implied.hi))
                    if (narrowed.lo > narrowed.hi) return false
                    if (narrowed != current) {
                        domains[variable] = narrowed
                        val replacement = termRange(coefficient, narrowed)
                        rowRange = BigInterval(rest.lo + replacement.lo, rest.hi + replacement.hi)
                        changed = true
                    }
                }
            }
        } while (changed)
        return true
    }

    private fun rowRange(row: LiaRow, domains: Array<BigInterval>): BigInterval = exactRowRange(
        row.vars,
        row.coeffs,
        domains,
        checkNotNull(witnessBound),
    )

    private fun applyPublishedBounds(domains: Array<BigInterval>, context: SearchContext): Boolean? {
        for (variable in domains.indices) {
            if (!context.consumeGeneralLiaWork()) return null
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

    /** Whether every factor can still hold over [domains], or null when the budget was spent mid-walk. */
    private fun factorsPossible(domains: Array<BigInterval>, context: SearchContext): Boolean? {
        var untilPoll = pollStride
        val cachedDomains = possibleDomains
        val cachedBools = possibleBools
        val cachedRanges = factorRanges
        val ranges = arrayOfNulls<BigInterval>(model.factors.size)
        for (factorIndex in model.factors.indices) {
            if (!context.consumeGeneralLiaWork()) return null
            if (--untilPoll <= 0) {
                untilPoll = pollStride
                if (context.pollGeneralLiaCancellation()) return null
            }
            val factor = model.factors[factorIndex]
            val possible = if (cachedDomains != null && cachedBools != null &&
                factorInputsUnchanged(factor, domains, cachedDomains, cachedBools)
            ) {
                ranges[factorIndex] = cachedRanges?.get(factorIndex)
                true
            } else {
                when (factor) {
                    is Clause -> factor.literals.any { literal ->
                        bools[literal ushr 1] == UNASSIGNED ||
                            bools[literal ushr 1] == truth(
                                literal,
                            )
                    }

                    is Linear -> {
                        val range = incrementalRange(
                            factor.vars,
                            exactConstantsOf(factor),
                            factorIndex,
                            cachedDomains,
                            cachedRanges,
                            domains,
                        )
                        ranges[factorIndex] = range
                        relationPossible(range, factor.op, linearBound(factor))
                    }

                    is ReifiedLinear -> if (bools[factor.auxBoolVar] == UNASSIGNED) {
                        true
                    } else {
                        val range = incrementalRange(
                            factor.vars,
                            factor.constants,
                            factorIndex,
                            cachedDomains,
                            cachedRanges,
                            domains,
                        )
                        ranges[factorIndex] = range
                        reifiedPossible(factor, range)
                    }

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
            if (!possible) return false
        }
        possibleDomains = domains.copyOf()
        possibleBools = bools.copyOf()
        factorRanges = ranges
        return true
    }

    /**
     * Whether every variable [factor] reads — Boolean or integer — carries the same value it did
     * when [cachedDomains]/[cachedBools] were captured, so a cached possibility verdict for it can
     * be reused verbatim.
     *
     * Driven by [Factor.intVars]/[Factor.boolVars] rather than a per-factor-type list: any factor
     * kind this component ever comes to admit is covered without a matching edit here.
     */
    private fun factorInputsUnchanged(
        factor: Factor,
        domains: Array<BigInterval>,
        cachedDomains: Array<BigInterval>,
        cachedBools: IntArray,
    ): Boolean = factor.intVars.all { domains[it] == cachedDomains[it] } &&
        factor.boolVars.all { bools[it] == cachedBools[it] }

    /**
     * The row range for [vars]/[coeffs] over [domains], reusing [cachedRanges]'s entry at
     * [factorIndex] via [updateRowRange] when [cachedDomains] is available, or computing it fresh
     * via [exactRowRange] otherwise.
     *
     * Sound regardless of any Boolean variable: the row sum itself never reads `bools`.
     */
    private fun incrementalRange(
        vars: IntArray,
        coeffs: IntegralConstants,
        factorIndex: Int,
        cachedDomains: Array<BigInterval>?,
        cachedRanges: Array<BigInterval?>?,
        domains: Array<BigInterval>,
    ): BigInterval {
        val updated = cachedDomains?.let { old ->
            cachedRanges?.get(factorIndex)?.let { updateRowRange(it, vars, coeffs, old, domains) }
        }
        return updated ?: exactRowRange(vars, coeffs, domains, checkNotNull(witnessBound))
    }

    private fun factorsHold(domains: Array<BigInterval>, context: SearchContext): Boolean? {
        var untilPoll = pollStride
        for (factor in model.factors) {
            if (!context.consumeGeneralLiaWork()) return null
            if (--untilPoll <= 0) {
                untilPoll = pollStride
                if (context.pollGeneralLiaCancellation()) return null
            }
            val holds = when (factor) {
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
            if (!holds) return false
        }
        return true
    }

    private fun rowValue(factor: Linear, domains: Array<BigInterval>): BigInteger =
        rowValue(factor.vars, exactConstantsOf(factor), domains)

    private fun rowValue(factor: ReifiedLinear, domains: Array<BigInterval>): BigInteger =
        rowValue(factor.vars, factor.constants, domains)

    private fun rowValue(vars: IntArray, coeffs: IntegralConstants, domains: Array<BigInterval>): BigInteger {
        var sum = BigInteger.ZERO
        for (index in vars.indices) sum += coeffs.exactCoeff(index) * domains[vars[index]].lo
        return sum
    }

    private fun linearBound(factor: Linear): BigInteger = exactConstantsOf(factor).exactBound

    private fun reifiedBound(factor: ReifiedLinear): BigInteger = factor.constants.exactBound

    /** Feasibility of an asserted reified row after rounding its integer lattice. */
    private fun reifiedPossible(factor: ReifiedLinear, range: BigInterval): Boolean {
        val truth = bools[factor.auxBoolVar] == TRUE
        val gcd = factor.vars.indices.fold(BigInteger.ZERO) { current, index ->
            bigGcd(current, factor.constants.exactCoeff(index))
        }
        if (gcd <= BigInteger.ONE) {
            return relationPossibleForTruth(range, factor.op, reifiedBound(factor), truth)
        }
        val bound = reifiedBound(factor)
        return when (factor.op) {
            LinearOp.LE -> if (truth) {
                range.lo <= floorDiv(bound, gcd) * gcd
            } else {
                range.hi >= ceilDiv(bound + BigInteger.ONE, gcd) * gcd
            }

            LinearOp.EQ -> if (bound % gcd != BigInteger.ZERO) {
                !truth
            } else {
                relationPossibleForTruth(range, factor.op, bound, truth)
            }

            LinearOp.GE -> if (truth) {
                range.hi >= ceilDiv(bound, gcd) * gcd
            } else {
                range.lo <= floorDiv(bound - BigInteger.ONE, gcd) * gcd
            }

            LinearOp.NE -> if (bound % gcd != BigInteger.ZERO) {
                truth
            } else {
                relationPossibleForTruth(range, factor.op, bound, truth)
            }
        }
    }

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

private fun exactConstantsOf(factor: Linear): IntegralConstants =
    checkNotNull(factor.integralConstants) { "open LIA row carries continuous constants" }

private data class GeneralLiaDecision(val domains: Array<BigInterval>) : SearchTheoryDecision

private data class LiaRow(val vars: IntArray, val coeffs: Array<BigInteger>, val bound: BigInteger) {
    companion object {
        fun of(factor: Linear): LiaRow = of(factor.vars, exactConstantsOf(factor))

        fun of(factor: ReifiedLinear): LiaRow = of(factor.vars, factor.constants)

        private fun of(vars: IntArray, constants: IntegralConstants): LiaRow =
            LiaRow(vars, Array(vars.size) { constants.exactCoeff(it) }, constants.exactBound)
    }
}

private data class BigInterval(val lo: BigInteger, val hi: BigInteger)

private fun termRange(coefficient: BigInteger, domain: BigInterval): BigInterval = if (coefficient >= BigInteger.ZERO) {
    BigInterval(coefficient * domain.lo, coefficient * domain.hi)
} else {
    BigInterval(coefficient * domain.hi, coefficient * domain.lo)
}

private fun exactRowRange(
    vars: IntArray,
    coeffs: Array<BigInteger>,
    domains: Array<BigInterval>,
    witnessBound: BigInteger,
): BigInterval = exactRowRange(vars, domains, witnessBound) { coeffs[it] }

private fun exactRowRange(
    vars: IntArray,
    coeffs: IntegralConstants,
    domains: Array<BigInterval>,
    witnessBound: BigInteger,
): BigInterval = exactRowRange(vars, domains, witnessBound, coeffs::exactCoeff)

private inline fun exactRowRange(
    vars: IntArray,
    domains: Array<BigInterval>,
    witnessBound: BigInteger,
    coefficientAt: (Int) -> BigInteger,
): BigInterval {
    var lo = BigInteger.ZERO
    var hi = BigInteger.ZERO
    var loWitness = BigInteger.ZERO
    var hiWitness = BigInteger.ZERO
    val negativeWitnessBound = -witnessBound
    for (index in vars.indices) {
        val coefficient = coefficientAt(index)
        val domain = domains[vars[index]]
        val loEndpoint = if (coefficient >= BigInteger.ZERO) domain.lo else domain.hi
        val hiEndpoint = if (coefficient >= BigInteger.ZERO) domain.hi else domain.lo
        when (loEndpoint) {
            witnessBound -> loWitness += coefficient
            negativeWitnessBound -> loWitness -= coefficient
            else -> lo += coefficient * loEndpoint
        }
        when (hiEndpoint) {
            witnessBound -> hiWitness += coefficient
            negativeWitnessBound -> hiWitness -= coefficient
            else -> hi += coefficient * hiEndpoint
        }
    }
    lo += loWitness * witnessBound
    hi += hiWitness * witnessBound
    return BigInterval(lo, hi)
}

private fun updateRowRange(
    range: BigInterval,
    vars: IntArray,
    coeffs: IntegralConstants,
    oldDomains: Array<BigInterval>,
    newDomains: Array<BigInterval>,
): BigInterval {
    var lo = range.lo
    var hi = range.hi
    for (index in vars.indices) {
        val variable = vars[index]
        val oldDomain = oldDomains[variable]
        val newDomain = newDomains[variable]
        if (oldDomain == newDomain) continue
        val coefficient = coeffs.exactCoeff(index)
        val oldContribution = termRange(coefficient, oldDomain)
        val newContribution = termRange(coefficient, newDomain)
        lo += newContribution.lo - oldContribution.lo
        hi += newContribution.hi - oldContribution.hi
    }
    return BigInterval(lo, hi)
}

private data class BigRow(val vars: IntArray, val coeffs: Array<BigInteger>, val bound: BigInteger) {
    companion object {
        fun of(factor: Linear): BigRow = of(factor.vars, exactConstantsOf(factor))

        fun of(factor: ReifiedLinear): BigRow = of(factor.vars, factor.constants)

        private fun of(vars: IntArray, constants: IntegralConstants): BigRow =
            BigRow(vars, Array(vars.size) { constants.exactCoeff(it) }, constants.exactBound)
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

private fun bigGcd(first: BigInteger, second: BigInteger): BigInteger {
    var a = if (first < BigInteger.ZERO) -first else first
    var b = if (second < BigInteger.ZERO) -second else second
    while (b != BigInteger.ZERO) {
        val remainder = a % b
        a = b
        b = remainder
    }
    return a
}

private sealed interface GeneralLiaSearchOutcome {
    data class Found(val values: Array<BigInteger>) : GeneralLiaSearchOutcome
    data object Infeasible : GeneralLiaSearchOutcome
    data object Cancelled : GeneralLiaSearchOutcome
    data object BudgetCapped : GeneralLiaSearchOutcome
}

private fun SearchContext.consumeGeneralLiaWork(): Boolean = (this as? SearchSession)?.consumeLiaRowVisit() != false

internal fun SearchContext.pollGeneralLiaCancellation(): Boolean {
    (this as? SearchSession)?.recordCancellationPoll()
    return cancelled()
}
