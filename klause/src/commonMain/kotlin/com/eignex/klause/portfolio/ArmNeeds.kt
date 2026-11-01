package com.eignex.klause.portfolio

import com.eignex.klause.factor.circuit.Circuit
import com.eignex.klause.factor.symmetry.SymmetryHandling
import com.eignex.klause.factor.table.Table
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.LinearForm
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.bounding.LpAutoConfig
import com.eignex.klause.lp.bounding.LpConfig
import com.eignex.klause.lp.bounding.LpEmphasis
import com.eignex.klause.lp.bounding.LpPlan
import com.eignex.klause.meta.coreguided.CoreGuidedSearch
import com.eignex.klause.presolve.binaryChannelLiteral
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.propagation.INTEGER_LEARNING_TERMS
import com.eignex.klause.propagation.difference.DifferenceSystem
import com.eignex.klause.solver.ProblemClass
import com.eignex.klause.solver.ProblemProfile
import com.eignex.klause.solver.objective.LinearObjective

/**
 * What an arm needs from a model to do anything its siblings do not. A curated pool builds an arm only on
 * a model that offers all of them, and gives the slot to the next arm in its order.
 */
internal sealed interface ArmNeed {
    // A pure clausal model, whose propagation and explanations stay Boolean.
    data object Clausal : ArmNeed

    /** An objective: the arm steers by it, so on a satisfaction model it is a copy of its base arm. */
    data object Objective : ArmNeed

    data object BooleanObjective : ArmNeed

    data object ExtensionalSupports : ArmNeed

    /** An LP relaxation with some technique to enable at [emphasis] under the run's `--lp` ceiling; without
     *  one the arm is a copy of the conflict-driven arm it is built on. */
    data class Relaxation(val emphasis: LpEmphasis) : ArmNeed
}

/**
 * The facts about one model that decide which arms are worth building on it: its [profile], and which LP techniques
 * it gives something to do. Each relaxation fact is computed at most once, and only when some arm asks for it.
 */
internal class ProblemFacts(
    /** The model's classification. */
    val profile: ProblemProfile,
    val binaryIntegers: Boolean = false,
    val continuousObjective: Boolean = false,
    val integerLinear: Boolean = false,
    val booleanObjective: Boolean = false,
    val circuit: Boolean = false,
    val binaryPackingObjective: Boolean = false,
    val binaryEqualityObjective: Boolean = false,
    val extensionalSupports: Boolean = false,
    val integerColumnsOnly: Boolean = false,
    val compactCardinalityEquality: Boolean = false,
    val compactIntegerNeighborhood: Boolean = false,
    val integerNeighborhoodLimited: Boolean = false,
    val integerNeighborhoodWork: Int = 0,
    val construction: WorkerConstructionCost = WorkerConstructionCost(0, 0),
    private val observer: ((String, WorkerEligibility) -> Unit)? = null,
    private val relaxation: (LpEmphasis) -> Boolean,
) {
    private val relaxations = HashMap<LpEmphasis, Boolean>()

    /** Whether the model offers [need]. */
    fun offers(need: ArmNeed): Boolean = when (need) {
        ArmNeed.Clausal -> profile.problemClass == ProblemClass.Sat
        ArmNeed.Objective -> profile.optimizing
        ArmNeed.BooleanObjective -> booleanObjective
        ArmNeed.ExtensionalSupports -> extensionalSupports && !profile.optimizing &&
            profile.problemClass == ProblemClass.FiniteCp
        is ArmNeed.Relaxation -> relaxations.getOrPut(need.emphasis) { relaxation(need.emphasis) }
    }

    /** Whether the model offers every one of [needs]. */
    fun offersAll(needs: Collection<ArmNeed>): Boolean = needs.all(::offers)

    fun eligibility(label: String, needs: Set<ArmNeed>): WorkerEligibility {
        val requirements = WorkerRequirements(WorkerFragment.FINITE, WorkerObjectiveView.CANONICAL,
            WorkerProofAuthority.CHECKED_TERMINALS, construction, needs.toSet())
        return WorkerEligibility(requirements, requirements.needs.filterNot(::offers).map { "missing:" + it }).also {
            observer?.invoke(label, it)
        }
    }

    /** The two ways to learn a model's facts. */
    companion object {
        /** The facts of [problem], classified as [profile], with the arms' LP capped under [lpCeiling]. */
        fun of(
            problem: BakedProblem,
            profile: ProblemProfile,
            lpCeiling: LpConfig,
            objective: LinearObjective? = null,
            observer: ((String, WorkerEligibility) -> Unit)? = null,
        ): ProblemFacts {
            val binary = problem.binaryIntegers
            val binarySource = objective?.binaryIntegerSource
            val binaryView = binarySource != null
            val integerColumnsOnly = problem.numIntVars > 0 && problem.numBoolVars == 0 && problem.numRealVars == 0
            val compactEquality = problem.factors.any { factor ->
                val form = factor.linearForm as? LinearForm.Conjunction
                form?.rows?.any { it.isCompactCardinalityEquality() } == true
            }
            val integerLinear = problem.factors.any { it !is SymmetryHandling } && problem.factors.all {
                if (it is SymmetryHandling || (binaryView && it is DifferenceSystem)) return@all true
                if (binaryView && it.binaryChannelLiteral() != Lit.NONE) {
                    val domain = problem.rootIntDomain(it.intVars.single())
                    if (domain.min >= 0L && domain.max <= 1L) return@all true
                }
                val form = it.linearForm as? LinearForm.Conjunction ?: return@all false
                form.rows.isNotEmpty() && form.rows.all { row ->
                    row.isLongUnconditional && (row.isIntegerOnly || binaryView)
                }
            }
            val neighborhood = if (profile.optimizing && binary && integerColumnsOnly && integerLinear &&
                compactEquality) IntegerLearningNeighborhood.inspect(problem) else IntegerLearningNeighborhood.NONE
            return ProblemFacts(
                profile,
                construction = WorkerConstructionCost(problem.numBoolVars.toLong() + problem.numIntVars +
                    problem.numRealVars, problem.factors.size),
                observer = observer,
                binaryIntegers = binary,
                integerColumnsOnly = integerColumnsOnly,
                compactCardinalityEquality = compactEquality,
                compactIntegerNeighborhood = neighborhood.compatible,
                integerNeighborhoodLimited = neighborhood.limited,
                integerNeighborhoodWork = neighborhood.work,
                binaryPackingObjective = (binary && problem.hasPackingObjective(objective)) ||
                    binarySource?.let {
                        it.problem.binaryIntegers && it.problem.hasPackingObjective(it.objective)
                    } == true,
                binaryEqualityObjective = (binary && problem.hasBinaryEqualityObjective(objective)) ||
                    binarySource?.let {
                        it.problem.binaryIntegers && it.problem.hasBinaryEqualityObjective(it.objective)
                    } == true,
                continuousObjective = objective?.realCoefficientValues?.any { it != 0.0 } == true,
                integerLinear = integerLinear,
                booleanObjective = objective?.let {
                    CoreGuidedSearch.eligible(problem, it) &&
                        it.boolWeightValues.any { weight -> weight != 0L }
                } == true,
                circuit = problem.factors.any { it is Circuit },
                extensionalSupports = profile.problemClass == ProblemClass.FiniteCp && !profile.optimizing &&
                    problem.factors.any { factor ->
                        factor is Table && factor.hi == null && factor.arity in 2..32 && factor.numTuples <= 65_536 &&
                            factor.xs.all { problem.rootIntDomain(it).spanOrNull(32L) != null }
                    },
                relaxation = { emphasis ->
                    LpAutoConfig.resolve(
                        problem, LpConfig(emphasis).cappedUnder(lpCeiling), LpPlan(), objective,
                    ) != LpPlan()
                },
            )
        }

        /** Facts for composing without a model: a [ProblemClass.FiniteCp] model of [kind] that offers every
         *  relaxation, or one of [problemClass]. */
        fun assumed(kind: Kind, problemClass: ProblemClass = ProblemClass.FiniteCp): ProblemFacts =
            ProblemFacts(ProblemProfile(problemClass, kind == Kind.COP, wide = false, scheduling = false)) { true }
    }
}

/** The [Kind] a solve with this profile runs as. */
internal val ProblemProfile.kind: Kind get() = if (optimizing) Kind.COP else Kind.CSP

internal val BakedProblem.continuousMajority: Boolean
    get() = numRealVars.toLong() > numBoolVars.toLong() + numIntVars

internal val Problem.binaryIntegers: Boolean
    get() = (0 until numIntVars).all {
        intBounds.hasLower(it) && intBounds.hasUpper(it) &&
            intBounds.lower(it) >= 0L && intBounds.upper(it) <= 1L
    }

private fun Problem.hasPackingObjective(objective: LinearObjective?): Boolean {
    if (numBoolVars != 0 || numRealVars != 0 || numIntVars == 0 || objective == null) return false
    if (objective.boolWeightValues.any { it != 0L } || objective.realCoefficientValues.any { it != 0.0 } ||
        objective.intCoefficientValues.size > numIntVars ||
        objective.intCoefficientValues.none { it < 0L } || objective.intCoefficientValues.any { it > 0L }
    ) {
        return false
    }
    return factors.isNotEmpty() && factors.all { it.isBinaryPackingCapacity() }
}

private fun Factor.isBinaryPackingCapacity(): Boolean {
    val form = linearForm as? LinearForm.Conjunction ?: return false
    val row = form.rows.singleOrNull() ?: return false
    if (!row.isLongUnconditional || !row.isIntegerOnly || row.relation != LinearOp.LE || row.bound < 0L) return false
    var firstPositive = -1
    var coupled = false
    for (k in 0 until row.size) {
        val coefficient = row.coeff(k)
        if (coefficient < 0L) return false
        if (coefficient > 0L) {
            if (firstPositive < 0) firstPositive = row.ref(k) else if (row.ref(k) != firstPositive) coupled = true
        }
    }
    return coupled
}

private fun Problem.hasBinaryEqualityObjective(objective: LinearObjective?): Boolean {
    if (numBoolVars != 0 || numRealVars != 0 || numIntVars == 0 || objective == null) return false
    if (objective.boolWeightValues.any { it != 0L } || objective.realCoefficientValues.any { it != 0.0 } ||
        objective.intCoefficientValues.size > numIntVars ||
        objective.intCoefficientValues.none { it > 0L } || objective.intCoefficientValues.any { it < 0L }
    ) {
        return false
    }
    return factors.isNotEmpty() && factors.all { factor ->
        val form = factor.linearForm as? LinearForm.Conjunction ?: return@all false
        form.rows.isNotEmpty() && form.rows.all { row ->
            row.isLongUnconditional && row.isIntegerOnly && row.relation == LinearOp.EQ
        }
    }
}

private fun LinearRow.isCompactCardinalityEquality(): Boolean {
    if (size !in 3..INTEGER_LEARNING_TERMS || relation != LinearOp.EQ ||
        !isLongUnconditional || !isIntegerOnly) return false
    val first = coeff(0)
    if (first == 0L || first == Long.MIN_VALUE) return false
    val magnitude = if (first < 0L) -first else first
    var negative = 0L
    for (k in 0 until size) {
        when (coeff(k)) {
            magnitude -> Unit
            -magnitude -> negative++
            else -> return false
        }
    }
    if (bound % magnitude != 0L) return false
    return bound / magnitude in (1L - negative)..(size.toLong() - 1L - negative)
}
