package com.eignex.klause.theory.qflra

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.arithmetic.ReifiedRealLinear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.lp.LpBuilder
import com.eignex.klause.lp.Relation
import com.eignex.klause.lp.Sense
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.simplex.exact.BigRationalTableauRow
import com.eignex.klause.simplex.exact.RationalFeasibility
import com.eignex.klause.simplex.exact.bigRationalOutcome
import com.eignex.klause.solver.Cancellation
import com.eignex.klause.solver.ProblemSpec
import com.eignex.klause.solver.search.ComponentCheck
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.SearchBrancher
import com.eignex.klause.solver.search.SearchContext
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.SearchModel
import com.eignex.klause.solver.search.SearchTheoryDecision
import com.eignex.klause.solver.search.TheoryComponent
import com.eignex.klause.solver.supportsExactLira
import com.eignex.klause.theory.Theory
import com.eignex.klause.theory.TheoryCheck
import com.eignex.klause.theory.TheoryContext
import com.eignex.klause.theory.TheoryResult
import com.ionspin.kotlin.bignum.integer.BigInteger

/** An exact mixed integer/rational witness for an open QF_LIRA model. */
data class ExactLiraAssignment(
    /** Boolean values indexed by model Boolean variable id. */
    val bools: BooleanArray,
    /** Arbitrary-precision integer values indexed by model integer variable id. */
    val ints: Array<BigInteger>,
    /** Rational real values indexed by model real variable id. */
    val reals: List<BigFraction>,
)

/** Compatibility names for exact QF_LIRA's shared theory outcomes. */
object ExactLiraResult {
    typealias Sat = TheoryResult.Sat<ExactLiraAssignment>
    typealias Unsat = TheoryResult.Unsat
    typealias Unknown = TheoryResult.Unknown
}

/**
 * Exact feasibility for the supported open QF_LIRA fragment.
 *
 * The Boolean skeleton is fixed first. At a Boolean leaf the rational simplex sees both integer and
 * real columns. A fractional integer witness is split at its exact [BigInteger] floor, so the child
 * boxes are disjoint and cover every integer value. This deliberately lives beside QF_LRA rather than
 * entering finite CP: the only branching here is theory-local integrality branching.
 */
class ExactLiraSolver(override val model: ProblemSpec) : Theory<ExactLiraAssignment> {
    private val witnessBound = requireNotNull(model.liraWitnessBound())

    init {
        require(model.supportsExactLira()) { "exact LIRA search requires a supported open mixed linear model" }
    }

    override fun check(bools: BooleanArray, context: TheoryContext): TheoryCheck<ExactLiraAssignment> {
        val cancellation = Cancellation(context::cancelled)
        return when (
            val result = IntegerSearch(
                bools,
                cancellation,
                context::consumeCheck,
                context::intLowerBound,
                context::intUpperBound,
            ).run()
        ) {
            is IntegerSearchResult.Found -> TheoryCheck.Sat(result.assignment)
            IntegerSearchResult.Infeasible -> TheoryCheck.Infeasible
            IntegerSearchResult.Cancelled, IntegerSearchResult.Budget -> TheoryCheck.Cancelled
        }
    }

    private inner class IntegerSearch(
        private val bools: BooleanArray,
        private val cancellation: Cancellation,
        private val consumeLeaf: () -> Boolean,
        private val lowerBound: (Int) -> Long?,
        private val upperBound: (Int) -> Long?,
    ) {
        fun run(): IntegerSearchResult {
            val stack = ArrayDeque<SearchNode>()
            stack.addLast(
                SearchNode(
                    branches = List(model.numIntVars) { integer ->
                        IntegerBranch(integer, lower = -witnessBound, upper = witnessBound)
                    },
                ).withPublishedBounds(model.numIntVars, lowerBound, upperBound),
            )
            while (stack.isNotEmpty()) {
                if (!consumeLeaf()) return IntegerSearchResult.Budget
                if (cancellation()) return IntegerSearchResult.Cancelled
                val node = stack.removeLast()
                val disequality = node.nextDisequality(model, bools)
                if (disequality >= 0) {
                    stack.addLast(node.withDirection(disequality, LinearOp.GE))
                    stack.addLast(node.withDirection(disequality, LinearOp.LE))
                    continue
                }
                val leaf = QfLiraSystem(model).build(bools, node) ?: return IntegerSearchResult.Budget
                val outcome = bigRationalOutcome(leaf.model, cancellation, Int.MAX_VALUE)
                when (outcome.feasibility) {
                    RationalFeasibility.INFEASIBLE -> Unit

                    RationalFeasibility.UNKNOWN -> return IntegerSearchResult.Cancelled

                    RationalFeasibility.FEASIBLE -> {
                        val values = checkNotNull(outcome.witness)
                        leaf.gmiCut(outcome.tableau)?.takeUnless { candidate ->
                            node.cuts.any { existing -> existing.sameAs(candidate) }
                        }?.let { cut ->
                            stack.addLast(node.withCut(cut))
                            continue
                        }
                        val split = leaf.integerPositive.indices.firstOrNull { integer ->
                            !leaf.value(
                                values,
                                leaf.integerPositive[integer],
                                leaf.integerNegative[integer],
                            ).isInteger()
                        }
                        if (split == null) {
                            return IntegerSearchResult.Found(
                                ExactLiraAssignment(
                                    bools.copyOf(),
                                    Array(model.numIntVars) { integer ->
                                        leaf.value(
                                            values,
                                            leaf.integerPositive[integer],
                                            leaf.integerNegative[integer],
                                        ).num
                                    },
                                    List(model.numRealVars) { real ->
                                        leaf.value(values, leaf.realPositive[real], leaf.realNegative[real])
                                    },
                                ),
                            )
                        }
                        val value = leaf.value(values, leaf.integerPositive[split], leaf.integerNegative[split])
                        val floor = value.floor()
                        stack.addLast(node.withBranch(IntegerBranch(split, lower = floor + BigInteger.ONE)))
                        stack.addLast(node.withBranch(IntegerBranch(split, upper = floor)))
                    }
                }
            }
            return IntegerSearchResult.Infeasible
        }
    }
}

/**
 * Incremental QF_LIRA participant whose exact simplex probes emit their residual integer splits to the
 * shared search engine. The component owns only its branch description and exact tableau assembly;
 * [com.eignex.klause.solver.search.SearchSession] owns the tree, decision levels, and retraction.
 */
class ExactLiraSearchComponent(
    private val model: ProblemSpec,
    private val modelContribution: ((ExactLiraAssignment, SearchModel) -> Unit)? = null,
) : TheoryComponent,
    SearchBrancher {
    private val witnessBound = requireNotNull(model.liraWitnessBound())
    private val bools = IntArray(model.numBoolVars) { UNASSIGNED }
    private val boolLevels = IntArray(model.numBoolVars) { -1 }
    private val root = SearchNode(
        branches = List(model.numIntVars) { integer ->
            IntegerBranch(integer, lower = -witnessBound, upper = witnessBound)
        },
    )
    private val nodesByLevel = HashMap<Int, SearchNode>()
    private var node = root
    private var assignment: ExactLiraAssignment? = null
    private var outcome: ComponentCheck? = null

    init {
        require(model.supportsExactLira()) { "exact LIRA component requires a supported mixed linear model" }
        nodesByLevel[0] = root
    }

    override fun assert(decision: SearchDecision, context: SearchContext): ComponentResult {
        when (decision) {
            is SearchDecision.Bool -> {
                val variable = decision.literal ushr 1
                bools[variable] = if (decision.literal and 1 == 0) TRUE else FALSE
                boolLevels[variable] = context.decisionLevel
            }

            is SearchDecision.Theory -> (decision.decision as? ExactLiraDecision)?.let { branch ->
                node = branch.node
                nodesByLevel[context.decisionLevel] = node
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
        nodesByLevel.keys.removeAll { it > decisionLevel }
        node = nodesByLevel.entries.maxByOrNull { it.key }?.value ?: root
        assignment = null
        outcome = null
    }

    override fun nextBranch(context: SearchContext): List<SearchDecision>? {
        if (bools.any { it == UNASSIGNED } || outcome != null) return null
        if (context.cancelled() || !context.consumeCheck()) {
            outcome = ComponentCheck.Indeterminate
            return null
        }
        val values = BooleanArray(bools.size) { bools[it] == TRUE }
        val current = node.withPublishedBounds(
            model.numIntVars,
            context::intLowerBound,
            context::intUpperBound,
        )
        val disequality = current.nextDisequality(model, values)
        if (disequality >= 0) {
            return listOf(
                decision(current.withDirection(disequality, LinearOp.GE)),
                decision(current.withDirection(disequality, LinearOp.LE)),
            )
        }
        val leaf = QfLiraSystem(model).build(values, current)
        if (leaf == null) {
            outcome = ComponentCheck.Indeterminate
            return null
        }
        val simplex = bigRationalOutcome(leaf.model, Cancellation(context::cancelled), Int.MAX_VALUE)
        when (simplex.feasibility) {
            RationalFeasibility.INFEASIBLE -> {
                outcome = ComponentCheck.Infeasible()
                return null
            }

            RationalFeasibility.UNKNOWN -> {
                outcome = ComponentCheck.Indeterminate
                return null
            }

            RationalFeasibility.FEASIBLE -> Unit
        }
        val witness = checkNotNull(simplex.witness)
        leaf.gmiCut(
            simplex.tableau,
        )?.takeUnless { candidate -> current.cuts.any { it.sameAs(candidate) } }?.let { cut ->
            return listOf(decision(current.withCut(cut)))
        }
        val split = leaf.integerPositive.indices.firstOrNull { integer ->
            !leaf.value(witness, leaf.integerPositive[integer], leaf.integerNegative[integer]).isInteger()
        }
        if (split != null) {
            val value = leaf.value(witness, leaf.integerPositive[split], leaf.integerNegative[split])
            val floor = value.floor()
            return listOf(
                decision(current.withBranch(IntegerBranch(split, lower = floor + BigInteger.ONE))),
                decision(current.withBranch(IntegerBranch(split, upper = floor))),
            )
        }
        assignment = ExactLiraAssignment(
            values,
            Array(model.numIntVars) { integer ->
                leaf.value(witness, leaf.integerPositive[integer], leaf.integerNegative[integer]).num
            },
            List(model.numRealVars) { real -> leaf.value(witness, leaf.realPositive[real], leaf.realNegative[real]) },
        )
        outcome = ComponentCheck.Feasible
        return null
    }

    override fun check(context: SearchContext): ComponentCheck = outcome ?: ComponentCheck.Indeterminate

    override fun contributeModel(model: SearchModel, context: SearchContext) {
        assignment?.let { value ->
            model.put(this, value)
            modelContribution?.invoke(value, model)
        }
    }

    private fun decision(node: SearchNode): SearchDecision = SearchDecision.Theory(ExactLiraDecision(node))

    private companion object {
        const val UNASSIGNED = -1
        const val FALSE = 0
        const val TRUE = 1
    }
}

private sealed interface IntegerSearchResult {
    data class Found(val assignment: ExactLiraAssignment) : IntegerSearchResult
    data object Infeasible : IntegerSearchResult
    data object Cancelled : IntegerSearchResult
    data object Budget : IntegerSearchResult
}

private data class IntegerBranch(val variable: Int, val lower: BigInteger? = null, val upper: BigInteger? = null)

/** One exact integer branch plus the choices which decompose integer disequalities. */
private data class SearchNode(
    val branches: List<IntegerBranch> = emptyList(),
    val disequalityDirections: Map<Int, LinearOp> = emptyMap(),
    val cuts: List<ExactGmiCut> = emptyList(),
) {
    fun withBranch(branch: IntegerBranch): SearchNode = copy(branches = branches + branch)

    fun withDirection(factor: Int, direction: LinearOp): SearchNode =
        copy(disequalityDirections = disequalityDirections + (factor to direction))

    fun withCut(cut: ExactGmiCut): SearchNode = copy(cuts = cuts + cut)

    fun nextDisequality(model: ProblemSpec, bools: BooleanArray): Int {
        for (index in model.factors.indices) {
            if (index in disequalityDirections) continue
            val factor = model.factors[index]
            val op = when (factor) {
                is Linear -> factor.op
                is ReifiedLinear -> if (bools[factor.auxBoolVar]) factor.op else factor.op.complement()
                is ReifiedRealLinear -> if (bools[factor.aux]) factor.op else factor.op.complement()
                else -> continue
            }
            if (op == LinearOp.NE) return index
        }
        return -1
    }

    private fun LinearOp.complement(): LinearOp = when (this) {
        LinearOp.LE -> LinearOp.GE
        LinearOp.GE -> LinearOp.LE
        LinearOp.EQ -> LinearOp.NE
        LinearOp.NE -> LinearOp.EQ
    }
}

private fun SearchNode.withPublishedBounds(
    numIntVars: Int,
    lowerBound: (Int) -> Long?,
    upperBound: (Int) -> Long?,
): SearchNode {
    var bounded = this
    for (integer in 0 until numIntVars) {
        val lower = lowerBound(integer)?.let(BigInteger::fromLong)
        val upper = upperBound(integer)?.let(BigInteger::fromLong)
        if (lower != null || upper != null) {
            bounded = bounded.withBranch(IntegerBranch(integer, lower, upper))
        }
    }
    return bounded
}

/** QF_LIRA branch payload opaque to the shared session. */
private data class ExactLiraDecision(val node: SearchNode) : SearchTheoryDecision

/** Direct exact-simplex assembler for one Boolean/integer branch of a mixed source model. */
private class QfLiraSystem(private val model: ProblemSpec) {
    fun build(bools: BooleanArray, node: SearchNode): QfLiraLeaf? {
        val builder = LpBuilder()
        // Split every source column as p - n. Both halves use true open-above columns rather than
        // the LP probe box: an exact infeasibility proof must never rest on an invented frontier.
        val intsPositive = IntArray(model.numIntVars) { builder.addOpenAboveVar(0L) }
        val intsNegative = IntArray(model.numIntVars) { builder.addOpenAboveVar(0L) }
        val realsPositive = IntArray(model.numRealVars) { builder.addOpenAboveVar(0L) }
        val realsNegative = IntArray(model.numRealVars) { builder.addOpenAboveVar(0L) }
        val constants = BigConstantEncoder(builder)
        for (integer in 0 until model.numIntVars) {
            model.intBounds.lowerOrNull(integer)?.let(BigInteger::fromLong)?.let { lower ->
                constants.addBound(intsPositive[integer], intsNegative[integer], Relation.GE, lower)
            }
            model.intBounds.upperOrNull(integer)?.let(BigInteger::fromLong)?.let { upper ->
                constants.addBound(intsPositive[integer], intsNegative[integer], Relation.LE, upper)
            }
        }
        for (real in 0 until model.numRealVars) {
            model.realLower[real].takeIf(Double::isFinite)?.let { lower ->
                builder.addRealRow(
                    intArrayOf(realsPositive[real], realsNegative[real]),
                    doubleArrayOf(1.0, -1.0),
                    Relation.GE,
                    lower,
                )
            }
            model.realUpper[real].takeIf(Double::isFinite)?.let { upper ->
                builder.addRealRow(
                    intArrayOf(realsPositive[real], realsNegative[real]),
                    doubleArrayOf(1.0, -1.0),
                    Relation.LE,
                    upper,
                )
            }
        }
        for (branch in node.branches) {
            if (branch.lower != null) {
                constants.addBound(
                    intsPositive[branch.variable],
                    intsNegative[branch.variable],
                    Relation.GE,
                    branch.lower,
                )
            }
            if (branch.upper != null) {
                constants.addBound(
                    intsPositive[branch.variable],
                    intsNegative[branch.variable],
                    Relation.LE,
                    branch.upper,
                )
            }
        }
        for ((index, factor) in model.factors.withIndex()) {
            when (factor) {
                is Linear -> if (factor.wide) {
                    addWide(
                        builder, intsPositive, intsNegative, constants, factor.vars,
                        checkNotNull(factor.wideCoeffs), factor.op, checkNotNull(factor.wideBound),
                        node.disequalityDirections[index],
                    )
                } else {
                    addLinear(
                        builder,
                        intsPositive,
                        intsNegative,
                        realsPositive,
                        realsNegative,
                        factor.vars,
                        factor.coefficients(),
                        factor.realVars,
                        factor.realCoeffs,
                        factor.op,
                        factor.boundForTheory(),
                        factor.strictReal,
                        node.disequalityDirections[index],
                        factor.hasReals,
                    )
                }

                is ReifiedLinear -> if (factor.wide) {
                    val op = if (bools[factor.auxBoolVar]) factor.op else factor.op.complement()
                    addWide(
                        builder, intsPositive, intsNegative, constants, factor.vars,
                        checkNotNull(factor.wideCoeffs), op, checkNotNull(factor.wideBound),
                        node.disequalityDirections[index],
                    )
                } else {
                    addReified(
                        builder = builder,
                        intsPositive = intsPositive,
                        intsNegative = intsNegative,
                        realsPositive = realsPositive,
                        realsNegative = realsNegative,
                        intVars = factor.vars,
                        intCoeffs = factor.coefficients(),
                        realVars = IntArray(0),
                        realCoeffs = DoubleArray(0),
                        op = factor.op,
                        bound = factor.bound.toDouble(),
                        strict = false,
                        truth = bools[factor.auxBoolVar],
                        disequalityDirection = node.disequalityDirections[index],
                        hasReals = false,
                    )
                }

                is ReifiedRealLinear -> addReified(
                    builder = builder,
                    intsPositive = intsPositive,
                    intsNegative = intsNegative,
                    realsPositive = realsPositive,
                    realsNegative = realsNegative,
                    intVars = factor.vars,
                    intCoeffs = factor.intCoeffs,
                    realVars = factor.realVars,
                    realCoeffs = factor.realCoeffs,
                    op = factor.op,
                    bound = factor.bound,
                    strict = factor.strict,
                    truth = bools[factor.aux],
                    disequalityDirection = node.disequalityDirections[index],
                    hasReals = true,
                )

                else -> Unit
            }
        }
        for (cut in node.cuts) {
            builder.addRealRow(cut.columns, cut.coefficients, Relation.GE, cut.rhs)
        }
        return QfLiraLeaf(builder.build(Sense.MINIMIZE), intsPositive, intsNegative, realsPositive, realsNegative)
    }

    private fun addLinear(
        builder: LpBuilder,
        intsPositive: IntArray,
        intsNegative: IntArray,
        realsPositive: IntArray,
        realsNegative: IntArray,
        intVars: IntArray,
        intCoeffs: DoubleArray,
        realVars: IntArray,
        realCoeffs: DoubleArray,
        op: LinearOp,
        bound: Double,
        strict: Boolean,
        disequalityDirection: LinearOp?,
        hasReals: Boolean,
    ) {
        val (actualOp, actualBound, actualStrict) = lowerDisequality(
            op,
            bound,
            strict,
            disequalityDirection,
            hasReals,
        )
        add(
            builder,
            intsPositive,
            intsNegative,
            realsPositive,
            realsNegative,
            intVars,
            intCoeffs,
            realVars,
            realCoeffs,
            actualOp,
            actualBound,
            actualStrict,
        )
    }

    private fun addReified(
        builder: LpBuilder,
        intsPositive: IntArray,
        intsNegative: IntArray,
        realsPositive: IntArray,
        realsNegative: IntArray,
        intVars: IntArray,
        intCoeffs: DoubleArray,
        realVars: IntArray,
        realCoeffs: DoubleArray,
        op: LinearOp,
        bound: Double,
        strict: Boolean,
        truth: Boolean,
        disequalityDirection: LinearOp?,
        hasReals: Boolean,
    ) {
        val actualOp = if (truth) {
            op
        } else {
            when (op) {
                LinearOp.LE -> LinearOp.GE
                LinearOp.GE -> LinearOp.LE
                LinearOp.EQ -> LinearOp.NE
                LinearOp.NE -> LinearOp.EQ
            }
        }
        val (rel, rhs, actualStrict) = lowerDisequality(
            actualOp,
            bound,
            if (truth) strict else !strict,
            disequalityDirection,
            hasReals,
        )
        add(
            builder,
            intsPositive,
            intsNegative,
            realsPositive,
            realsNegative,
            intVars,
            intCoeffs,
            realVars,
            realCoeffs,
            rel,
            rhs,
            actualStrict,
        )
    }

    private fun addWide(
        builder: LpBuilder,
        positive: IntArray,
        negative: IntArray,
        constants: BigConstantEncoder,
        variables: IntArray,
        coefficients: Array<BigInteger>,
        op: LinearOp,
        bound: BigInteger,
        direction: LinearOp?,
    ) {
        val (relation, rhs) = lowerWideDisequality(op, bound, direction)
        val columns = ArrayList<Int>()
        val values = ArrayList<Double>()
        for (index in variables.indices) {
            constants.appendProduct(coefficients[index], positive[variables[index]], columns, values, 1.0)
            constants.appendProduct(coefficients[index], negative[variables[index]], columns, values, -1.0)
        }
        constants.appendConstant(-rhs, columns, values)
        builder.addRealRow(columns.toIntArray(), values.toDoubleArray(), relation(relation), 0.0)
    }

    private fun add(
        builder: LpBuilder,
        intsPositive: IntArray,
        intsNegative: IntArray,
        realsPositive: IntArray,
        realsNegative: IntArray,
        intVars: IntArray,
        intCoeffs: DoubleArray,
        realVars: IntArray,
        realCoeffs: DoubleArray,
        op: LinearOp,
        bound: Double,
        strict: Boolean,
    ) {
        require(op != LinearOp.NE) { "QF_LIRA disequality must be lowered before exact feasibility" }
        val columns = IntArray(2 * (intVars.size + realVars.size))
        val values = DoubleArray(columns.size)
        for (i in intVars.indices) {
            columns[2 * i] = intsPositive[intVars[i]]
            values[2 * i] = intCoeffs[i]
            columns[2 * i + 1] = intsNegative[intVars[i]]
            values[2 * i + 1] = -intCoeffs[i]
        }
        for (i in realVars.indices) {
            val offset = 2 * (intVars.size + i)
            columns[offset] = realsPositive[realVars[i]]
            values[offset] = realCoeffs[i]
            columns[offset + 1] = realsNegative[realVars[i]]
            values[offset + 1] = -realCoeffs[i]
        }
        builder.addRealRow(columns, values, relation(op), bound, strict)
    }

    private fun Linear.coefficients(): DoubleArray = if (hasReals) {
        realIntCoeffs
    } else {
        DoubleArray(
            vars.size,
        ) { coeff(it).toDouble() }
    }

    private fun Linear.boundForTheory(): Double = if (hasReals) realBound else bound.toDouble()

    private fun lowerDisequality(
        op: LinearOp,
        bound: Double,
        strict: Boolean,
        direction: LinearOp?,
        hasReals: Boolean,
    ): Triple<LinearOp, Double, Boolean> = if (op == LinearOp.NE) {
        when (requireNotNull(direction) { "integer disequality direction is missing" }) {
            LinearOp.LE -> if (hasReals) {
                Triple(LinearOp.LE, bound, true)
            } else {
                Triple(LinearOp.LE, bound - 1.0, false)
            }

            LinearOp.GE -> if (hasReals) {
                Triple(LinearOp.GE, bound, true)
            } else {
                Triple(LinearOp.GE, bound + 1.0, false)
            }

            else -> error("integer disequality direction must be an inequality")
        }
    } else {
        Triple(op, bound, strict)
    }

    private fun ReifiedLinear.coefficients(): DoubleArray = DoubleArray(vars.size) { coeff(it).toDouble() }

    private fun relation(op: LinearOp): Relation = when (op) {
        LinearOp.LE -> Relation.LE
        LinearOp.GE -> Relation.GE
        LinearOp.EQ -> Relation.EQ
        LinearOp.NE -> error("checked by add")
    }
}

private fun LinearOp.complement(): LinearOp = when (this) {
    LinearOp.LE -> LinearOp.GE
    LinearOp.GE -> LinearOp.LE
    LinearOp.EQ -> LinearOp.NE
    LinearOp.NE -> LinearOp.EQ
}

private fun lowerWideDisequality(op: LinearOp, bound: BigInteger, direction: LinearOp?): Pair<LinearOp, BigInteger> =
    if (op == LinearOp.NE) {
        when (requireNotNull(direction) { "wide integer disequality direction is missing" }) {
            LinearOp.LE -> LinearOp.LE to (bound - BigInteger.ONE)
            LinearOp.GE -> LinearOp.GE to (bound + BigInteger.ONE)
            else -> error("wide integer disequality direction must be an inequality")
        }
    } else {
        op to bound
    }

private class QfLiraLeaf(
    val model: com.eignex.klause.lp.LpModel,
    val integerPositive: IntArray,
    val integerNegative: IntArray,
    val realPositive: IntArray,
    val realNegative: IntArray,
) {
    fun value(witness: List<BigFraction>, positive: Int, negative: Int): BigFraction =
        witness[positive] - witness[negative]

    /**
     * Separates one exact GMI cut from a fractional extended-integer tableau row.  Source integer
     * `x` is represented as `p - n`; requiring both nonnegative halves to be integral preserves
     * every source-integer solution, so this is a valid extended formulation.  A cut is retained
     * only if every coefficient survives the existing double LP view exactly.
     */
    fun gmiCut(tableau: List<BigRationalTableauRow>?): ExactGmiCut? {
        if (model.rowStrict.any { it }) return null
        val integral = BooleanArray(model.n)
        for (column in integerPositive) integral[column] = true
        for (column in integerNegative) integral[column] = true
        for (row in tableau.orEmpty()) {
            if (row.basic !in 0 until model.n || !integral[row.basic]) continue
            val fraction = row.rhs.fractionalPart()
            if (fraction.isZero) continue
            val coefficients = ArrayList<BigFraction>()
            val columns = ArrayList<Int>()
            for (index in row.columns.indices) {
                val column = row.columns[index]
                val coefficient = if (column < model.n && integral[column]) {
                    row.coefficients[index].fractionalPart()
                } else {
                    row.coefficients[index].mixedIntegerCoefficient(fraction)
                }
                if (!coefficient.isZero) {
                    columns.add(column)
                    coefficients.add(coefficient)
                }
            }
            val projected = projectSlacks(columns, coefficients, fraction) ?: continue
            ExactGmiCut.from(projected)?.let { return it }
        }
        return null
    }

    /** Eliminate tableau slack columns with `s = rhs - A*x` before adding the cut to the source LP. */
    private fun projectSlacks(columns: List<Int>, coefficients: List<BigFraction>, rhs: BigFraction): ExactCutRow? {
        val terms = HashMap<Int, BigFraction>()
        var cutRhs = rhs
        for (index in columns.indices) {
            val column = columns[index]
            val coefficient = coefficients[index]
            if (column < model.n) {
                terms.add(column, coefficient)
                continue
            }
            val row = column - model.n
            if (model.hasUpper[column] && model.upper[column] == 0L) continue
            cutRhs -= coefficient * requireNotNull(BigFraction.ofDouble(model.rhsD(row)))
            for (sourceColumn in 0 until model.n) {
                model.forEachInColumnD(sourceColumn) { sourceRow, value ->
                    if (sourceRow == row) {
                        terms.add(sourceColumn, coefficient * requireNotNull(BigFraction.ofDouble(value)).negated())
                    }
                }
            }
        }
        val ordered = terms.entries.filter { !it.value.isZero }.sortedBy { it.key }
        return ExactCutRow(
            ordered.map { it.key }.toIntArray(),
            ordered.map { it.value },
            cutRhs,
        )
    }
}

/** A GMI inequality in the structural columns, held as doubles only after an exact round-trip check. */
private class ExactGmiCut(val columns: IntArray, val coefficients: DoubleArray, val rhs: Double) {
    fun sameAs(other: ExactGmiCut): Boolean =
        columns.contentEquals(other.columns) && coefficients.contentEquals(other.coefficients) && rhs == other.rhs

    companion object {
        fun from(row: ExactCutRow): ExactGmiCut? {
            val rhs = row.rhs.exactDoubleOrNull() ?: return null
            val coefficients = DoubleArray(row.coefficients.size)
            for (index in coefficients.indices) {
                coefficients[index] = row.coefficients[index].exactDoubleOrNull() ?: return null
            }
            return ExactGmiCut(row.columns, coefficients, rhs)
        }
    }
}

private class ExactCutRow(val columns: IntArray, val coefficients: List<BigFraction>, val rhs: BigFraction)

private fun MutableMap<Int, BigFraction>.add(column: Int, value: BigFraction) {
    val sum = (this[column] ?: BigFraction.ZERO) + value
    if (sum.isZero) remove(column) else this[column] = sum
}

private fun BigFraction.fractionalPart(): BigFraction = this - BigFraction.of(floor(), BigInteger.ONE)

private fun BigFraction.mixedIntegerCoefficient(fraction: BigFraction): BigFraction = if (signum() >= 0) {
    this
} else {
    BigFraction.of(fraction.num, fraction.den - fraction.num) * negated()
}

private fun BigFraction.exactDoubleOrNull(): Double? {
    val value = toDouble()
    return value.takeIf { it.isFinite() && BigFraction.ofDouble(it) == this }
}

/** Exact base-2^40 encoding for branch bounds too large for one IEEE coefficient. */
private class BigConstantEncoder(private val builder: LpBuilder) {
    private val base = BigInteger.ONE shl 40
    private val columns = ArrayList<Int>()
    private val scaledColumns = HashMap<Int, ArrayList<Int>>()

    fun addBound(positive: Int, negative: Int, relation: Relation, bound: BigInteger) {
        val rowColumns = arrayListOf(positive, negative)
        val rowValues = arrayListOf(1.0, -1.0)
        appendConstant(-bound, rowColumns, rowValues)
        builder.addRealRow(rowColumns.toIntArray(), rowValues.toDoubleArray(), relation, 0.0)
    }

    /** Appends `multiplier * coefficient * column` using exact base-2^40 scale columns. */
    fun appendProduct(
        coefficient: BigInteger,
        column: Int,
        targetColumns: MutableList<Int>,
        targetValues: MutableList<Double>,
        multiplier: Double,
    ) {
        for ((digit, value) in digits(coefficient).withIndex()) {
            if (value.isZero()) continue
            targetColumns.add(scaled(column, digit))
            targetValues.add(multiplier * value.longValue().toDouble())
        }
    }

    /** Appends `value` times the exact constant one. */
    fun appendConstant(value: BigInteger, targetColumns: MutableList<Int>, targetValues: MutableList<Double>) {
        ensureDigits(value)
        for ((digit, part) in digits(value).withIndex()) {
            if (part.isZero()) continue
            targetColumns.add(columns[digit])
            targetValues.add(part.longValue().toDouble())
        }
    }

    private fun ensureDigits(value: BigInteger) {
        val needed = digits(value).size
        while (columns.size < needed) {
            val column = builder.addOpenAboveVar(0L)
            if (columns.isEmpty()) {
                builder.addRealRow(intArrayOf(column), doubleArrayOf(1.0), Relation.EQ, 1.0)
            } else {
                builder.addRealRow(
                    intArrayOf(column, columns.last()),
                    doubleArrayOf(1.0, -(1L shl 40).toDouble()),
                    Relation.EQ,
                    0.0,
                )
            }
            columns.add(column)
        }
    }

    private fun digits(value: BigInteger): List<BigInteger> {
        var remaining = value.abs()
        val sign = if (value < BigInteger.ZERO) -BigInteger.ONE else BigInteger.ONE
        val result = ArrayList<BigInteger>()
        do {
            result.add((remaining % base) * sign)
            remaining /= base
        } while (remaining != BigInteger.ZERO)
        return result
    }

    private fun scaled(column: Int, exponent: Int): Int {
        val chain = scaledColumns.getOrPut(column) { arrayListOf(column) }
        while (chain.size <= exponent) {
            val next = builder.addOpenAboveVar(0L)
            builder.addRealRow(
                intArrayOf(next, chain.last()),
                doubleArrayOf(1.0, -(1L shl 40).toDouble()),
                Relation.EQ,
                0.0,
            )
            chain.add(next)
        }
        return chain[exponent]
    }
}

private fun BigFraction.isInteger(): Boolean = den == BigInteger.ONE

private fun BigFraction.floor(): BigInteger {
    val quotient = num / den
    return if (num < BigInteger.ZERO && num % den != BigInteger.ZERO) quotient - BigInteger.ONE else quotient
}

private fun com.eignex.klause.solver.IntBounds.lowerOrNull(variable: Int): Long? = if (hasLower(
        variable,
    )
) {
    lower(variable)
} else {
    null
}

private fun com.eignex.klause.solver.IntBounds.upperOrNull(variable: Int): Long? = if (hasUpper(
        variable,
    )
) {
    upper(variable)
} else {
    null
}

/**
 * A finite witness box for the mixed rational rows, derived by clearing each row's denominators and
 * applying the mixed-integer small-solution bound.  This is theory-local: it preserves existence of
 * a mixed witness and is never materialized as a CP domain or exposed through a frontend clamp.
 */
private fun ProblemSpec.liraWitnessBound(): BigInteger? {
    val variables = numIntVars + numRealVars
    if (variables == 0) return BigInteger.ONE
    var largest = BigInteger.ONE

    fun observe(value: BigInteger) {
        val magnitude = if (value < BigInteger.ZERO) -value else value
        if (magnitude > largest) largest = magnitude
    }

    fun row(values: List<BigFraction>) {
        var denominator = BigInteger.ONE
        for (value in values) denominator = (denominator * value.den) / denominator.gcd(value.den)
        for (value in values) observe((value.num * (denominator / value.den)))
    }

    fun rational(value: Double): BigFraction? = BigFraction.ofDouble(value)

    fun integerRow(coefficients: DoubleArray, reals: DoubleArray, bound: Double, disequality: Boolean) {
        val values = ArrayList<BigFraction>(coefficients.size + reals.size + if (disequality) 3 else 1)
        for (coefficient in coefficients) values.add(rational(coefficient) ?: return)
        for (coefficient in reals) values.add(rational(coefficient) ?: return)
        val rhs = rational(bound) ?: return
        values.add(rhs)
        if (disequality) {
            values.add(rhs + BigFraction.ONE)
            values.add(rhs - BigFraction.ONE)
        }
        row(values)
    }

    fun wideRow(coefficients: Array<BigInteger>, bound: BigInteger, disequality: Boolean) {
        val values = ArrayList<BigFraction>(coefficients.size + if (disequality) 3 else 1)
        for (coefficient in coefficients) values.add(BigFraction.of(coefficient, BigInteger.ONE))
        val rhs = BigFraction.of(bound, BigInteger.ONE)
        values.add(rhs)
        if (disequality) {
            values.add(rhs + BigFraction.ONE)
            values.add(rhs - BigFraction.ONE)
        }
        row(values)
    }

    for (factor in factors) {
        when (factor) {
            is Clause -> Unit

            is Linear -> {
                if (factor.wide) {
                    wideRow(checkNotNull(factor.wideCoeffs), checkNotNull(factor.wideBound), factor.op == LinearOp.NE)
                } else if (factor.hasReals) {
                    integerRow(factor.realIntCoeffs, factor.realCoeffs, factor.realBound, factor.op == LinearOp.NE)
                } else {
                    integerRow(
                        DoubleArray(factor.vars.size) { factor.coeff(it).toDouble() },
                        DoubleArray(0),
                        factor.bound.toDouble(),
                        factor.op == LinearOp.NE,
                    )
                }
            }

            is ReifiedLinear -> if (factor.wide) {
                wideRow(checkNotNull(factor.wideCoeffs), checkNotNull(factor.wideBound), factor.op == LinearOp.NE)
            } else {
                integerRow(
                    DoubleArray(factor.vars.size) { factor.coeff(it).toDouble() },
                    DoubleArray(0),
                    factor.bound.toDouble(),
                    factor.op == LinearOp.NE,
                )
            }

            is ReifiedRealLinear -> integerRow(
                factor.intCoeffs,
                factor.realCoeffs,
                factor.bound,
                factor.op == LinearOp.NE,
            )

            else -> return null
        }
    }
    for (integer in 0 until numIntVars) {
        intBounds.lowerOrNull(integer)?.let { row(listOf(BigFraction.ONE, BigFraction.ofLong(it))) }
        intBounds.upperOrNull(integer)?.let { row(listOf(BigFraction.ONE, BigFraction.ofLong(it))) }
    }
    for (real in 0 until numRealVars) {
        realLower[real].takeIf(Double::isFinite)?.let { bound ->
            row(listOf(BigFraction.ONE, rational(bound) ?: return null))
        }
        realUpper[real].takeIf(Double::isFinite)?.let { bound ->
            row(listOf(BigFraction.ONE, rational(bound) ?: return null))
        }
    }
    val dimensions = BigInteger.fromInt(variables)
    // Hadamard gives `sqrt(n^n) * beta^n`; `n^n * beta^n` is an integral over-approximation.
    return BigInteger.fromInt(variables + 1) * dimensions.pow(variables) * largest.pow(variables)
}
