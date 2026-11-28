package com.eignex.klause.lp.relaxation

import com.eignex.klause.factor.arithmetic.ContinuousProduct
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.values
import com.eignex.klause.lp.Contribution
import com.eignex.klause.lp.cut.CircuitArcModel
import com.eignex.klause.lp.engine.CertifiedLpResult
import com.eignex.klause.lp.engine.CutAuxiliaryDefinition
import com.eignex.klause.lp.engine.LpClaim
import com.eignex.klause.lp.engine.LpColumnId
import com.eignex.klause.lp.engine.LpLayoutRemap
import com.eignex.klause.lp.engine.LpModel
import com.eignex.klause.lp.engine.LpPricingOptions
import com.eignex.klause.lp.engine.LpSolveContext
import com.eignex.klause.lp.engine.LpVerdict
import com.eignex.klause.lp.engine.solveAndCertify
import com.eignex.klause.lp.rootDomainOf
import com.eignex.klause.propagation.CpIntId
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.PreparedFactorProjections
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.incumbent.Verification
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.LpRoute
import com.eignex.klause.solver.result.LpStatsSink
import com.eignex.klause.solver.selectedFactorProjections
import com.eignex.klause.solver.validation.floatingOptimumAcceptance
import com.eignex.klause.solver.validation.permitsToleranceOptimum
import com.eignex.klause.solver.validation.verifyRealCoordinates
import com.eignex.klause.theory.encodingSourceRealCount
import com.eignex.klause.theory.finiteLinearModel
import com.eignex.klause.theory.privateLinearCompletion
import com.eignex.klause.util.CONSTRUCTION_BATCH_SIZE
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.arrays.emptyDoubleArray
import com.eignex.util.arrays.emptyIntArray
import com.eignex.util.collections.IntArrayList
import com.eignex.util.collections.IntHashSet
import com.eignex.util.numbers.BigFraction

internal class LpAssemblyCancelled : RuntimeException()

// Maps each LP column back to its CP variable so reduced-cost fixing can turn a column reduction into a
// domain reduction. The LP objective omits objectiveConstant; add it before comparing against an incumbent.
internal class LpRelaxation(
    val model: LpModel,
    val colVarId: IntArray,
    val colIsBool: BooleanArray,
    val objectiveConstant: Long,
    // Variable id → LP column, -1 if none.
    val intColOf: IntArray,
    val boolColOf: IntArray,
    val circuitArcs: List<CircuitArcModel> = emptyList(),
    // The layout stays fixed across nodes and column bounds follow a trail: no row's coefficients depend on
    // live domains and every column derives from the session, through a CP domain or a colReq presence rule.
    val persistentEligible: Boolean = false,
    // Flat (intVar, value) membership pairs: an auxiliary column reaches colPresentUpper only while every
    // named value stays live. Null for CP-var columns and for aux columns without a rule, which make the
    // relaxation persistent-ineligible.
    val colReq: Array<LongArray?> = arrayOfNulls(model.n),
    val colPresentUpper: LongArray = LongArray(model.n),
    /** Factors that emitted a [Contribution.HULL] row, in factor order: the root pruner's candidates. */
    val hullFactorIds: IntArray = emptyIntArray(),
    // Real variable id per column, -1 for integer, Boolean and auxiliary columns.
    val colRealId: IntArray = IntArray(model.n) { -1 },
    // -1 for the negative part of a split lower-unbounded real x = x⁺ − x⁻, which keeps huge lower shifts
    // out of the rhs doubles.
    val colRealSign: IntArray = IntArray(model.n) { 1 },
    // Pure-real models only. Each reified real atom emits its row and its complement with true rhs, so the
    // structure never changes across nodes and a persistent simplex keeps its factorization; a node only
    // marks which rows it enforces.
    val gatedRows: IntArray = emptyIntArray(),
    val gatedAux: IntArray = emptyIntArray(),
    // true activates the atom row, false its complement.
    val gatedWhenTrue: BooleanArray = BooleanArray(0),
    val sourceMap: CutSourceMap? = null,
    val colPresence: List<CutAuxiliaryDefinition?> = List(model.n) { null },
    // -1 for a cut or a row spanning several factors.
    val rowFactorIds: IntArray = emptyIntArray(),
    val realUpperRows: Map<Int, Int> = emptyMap(),
    val preparationTemplates: LpPreparationTemplates? = null,
) {
    fun column(variable: CpIntId): LpColumnId? =
        intColOf[variable.index].takeIf { it >= 0 }?.let(::LpColumnId)

    fun cpInteger(column: LpColumnId): CpIntId? {
        val index = column.index
        if (colIsBool[index] || colRealId[index] >= 0) return null
        return colVarId[index].takeIf { it >= 0 }?.let(::CpIntId)
    }
}

// Walks whichever of the node range and the domain is smaller: over the node range a sparse routing graph
// costs O(n²), and a successor declared far wider than the graph cannot be enumerated past 2³¹ values.
internal inline fun eachCandidateArcHead(dom: IntDomain, i: Int, n: Int, selfLoops: Boolean, action: (Int) -> Unit) {
    if (dom.valueCount <= n) {
        val span = dom.values
        for (k in 0 until span.size) {
            val j = span.valueAt(k)
            if ((selfLoops || j != i.toLong()) && j >= 0 && j < n) action(j.toInt())
        }
    } else {
        for (j in 0 until n) {
            if ((selfLoops || j != i) && dom.contains(j.toLong())) action(j)
        }
    }
}

// Counts no further than cap: above it the arc model is declined, so a dense graph must not pay for a total.
internal fun candidateArcCount(succ: IntArray, selfLoops: Boolean, cap: Int, domainOf: (Int) -> IntDomain): Int {
    var arcs = 0
    for (i in succ.indices) {
        eachCandidateArcHead(domainOf(succ[i]), i, succ.size, selfLoops) { arcs++ }
        if (arcs > cap) return arcs
    }
    return arcs
}

internal suspend fun SequenceScope<Unit>.prepareCandidateArcCount(
    succ: IntArray,
    selfLoops: Boolean,
    cap: Int,
    domainOf: (Int) -> IntDomain,
): Int {
    var arcs = 0
    var visits = 0
    for (i in succ.indices) {
        val dom = domainOf(succ[i])
        if (dom.valueCount <= succ.size) {
            val span = dom.values
            for (k in 0 until span.size) {
                val j = span.valueAt(k)
                if ((selfLoops || j != i.toLong()) && j >= 0 && j < succ.size) arcs++
                if (++visits % CONSTRUCTION_BATCH_SIZE == 0) yield(Unit)
            }
        } else {
            for (j in succ.indices) {
                if ((selfLoops || j != i) && dom.contains(j.toLong())) arcs++
                if (++visits % CONSTRUCTION_BATCH_SIZE == 0) yield(Unit)
            }
        }
        if (arcs > cap) return arcs
        if (++visits % CONSTRUCTION_BATCH_SIZE == 0) yield(Unit)
    }
    return arcs
}

// The persistent simplex treats an unenforced row's slack as free, so the row never constrains.
internal fun LpRelaxation.gatedEnforcement(session: PropagationSession, out: BooleanArray) {
    out.fill(true)
    for (k in gatedRows.indices) {
        if (session.boolValue(gatedAux[k]) != gatedWhenTrue[k]) out[gatedRows[k]] = false
    }
}

internal fun LpRelaxation.columnBounds(session: PropagationSession): Pair<LongArray, LongArray> {
    val n = model.n
    val lo = LongArray(n)
    val hi = LongArray(n)
    for (j in 0 until n) {
        val req = colReq[j]
        when {
            req != null -> {
                var present = true
                var k = 0
                while (k < req.size) {
                    if (!session.intDomain(req[k].toInt()).contains(req[k + 1])) {
                        present = false
                        break
                    }
                    k += 2
                }
                lo[j] = 0L
                hi[j] = if (present) colPresentUpper[j] else 0L
            }

            colIsBool[j] -> {
                val pinned = session.boolValue(colVarId[j])
                lo[j] = if (pinned == true) 1L else 0L
                hi[j] = if (pinned == false) 0L else 1L
            }

            else -> {
                val d = session.intDomain(colVarId[j])
                lo[j] = d.min
                hi[j] = d.max
            }
        }
    }
    return lo to hi
}

internal fun LpRelaxation.withModel(
    reboundModel: LpModel,
    sources: CutSourceMap? = sourceMap?.withBounds(reboundModel),
    remap: LpLayoutRemap? = null,
    objectiveConstant: Long = this.objectiveConstant,
    preparationTemplates: LpPreparationTemplates? = null,
): LpRelaxation {
    require(remap == null || remap.retainedColumns.size == model.n) { "column removal requires source metadata" }
    val gates = remap?.let { gatedRows.indices.filter { index -> it.row(gatedRows[index]) >= 0 } }
    return LpRelaxation(
        model = reboundModel,
        colVarId = colVarId,
        colIsBool = colIsBool,
        objectiveConstant = objectiveConstant,
        intColOf = intColOf,
        boolColOf = boolColOf,
        circuitArcs = circuitArcs,
        persistentEligible = persistentEligible,
        colReq = colReq,
        colPresentUpper = colPresentUpper,
        colPresence = colPresence,
        hullFactorIds = hullFactorIds,
        colRealId = colRealId,
        colRealSign = colRealSign,
        gatedRows = if (gates == null) {
            gatedRows
        } else {
            gates.map { requireNotNull(remap).row(gatedRows[it]) }.toIntArray()
        },
        gatedAux = if (gates == null) gatedAux else gates.map { gatedAux[it] }.toIntArray(),
        gatedWhenTrue = if (gates == null) gatedWhenTrue else BooleanArray(gates.size) { gatedWhenTrue[gates[it]] },
        sourceMap = sources,
        rowFactorIds = if (remap != null) {
            remap.retainedRows.map { rowFactorIds.getOrElse(it) { -1 } }.toIntArray()
        } else if (reboundModel.m == model.m) rowFactorIds else {
            require(reboundModel.m >= model.m) { "row removal requires a metadata remap" }
            IntArray(reboundModel.m) { rowFactorIds.getOrElse(it) { -1 } }
        },
        realUpperRows = if (remap == null) realUpperRows else realUpperRows.mapNotNull { (row, variable) ->
            remap.row(row).takeIf { it >= 0 }?.let { it to variable }
        }.toMap(),
        preparationTemplates = preparationTemplates,
    )
}

internal interface RelaxationDomains {
    fun intDomain(varId: Int): IntDomain

    fun boolValue(varId: Int): Boolean?

    // Whether an invented side becomes an open LP column rather than the finite clamp. Only the root view
    // may: in a live search state the bound may be a branch decision indistinguishable from the clamp.
    val honorsOpenSides: Boolean get() = false
}

// Reads endpoints only, so it never triggers the O(domain span) bake fixpoint a session runs on construction.
internal class RootDomains(private val problem: Problem) : RelaxationDomains {
    override fun intDomain(varId: Int): IntDomain = problem.rootDomainOf(varId)
    override fun boolValue(varId: Int): Boolean? = null
    override val honorsOpenSides: Boolean get() = true
}

internal class SessionDomains(private val session: PropagationSession) : RelaxationDomains {
    override fun intDomain(varId: Int): IntDomain = session.intDomain(varId)
    override fun boolValue(varId: Int): Boolean? = session.boolValue(varId)
}

private class SampleDomains(private val sample: Sample) : RelaxationDomains {
    override fun intDomain(varId: Int): IntDomain = sample.intValue(varId).let { IntDomain(it, it) }
    override fun boolValue(varId: Int): Boolean = sample.boolValue(varId)
}

// Residual real-LP test at a full-assignment leaf: discrete rows already hold at the fixed values, so only
// real rows bind. FEASIBLE and ATTAINED_OPTIMUM are exact completions; TOLERANCE_OPTIMUM is a float optimum
// toleranceCheck accepted; UNBOUNDED adds a ray provesUnbounded checked, else the completion stays FEASIBLE;
// INFEASIBLE is an exact conflict or a permitted numerical ray; INDETERMINATE degrades the leaf to unknown.
internal fun leafRealFeasibility(
    problem: Problem,
    objective: LinearObjective?,
    sample: Sample,
    cancellation: ExecutionControl = ExecutionControl.Never,
    componentSplit: Boolean = true,
    sink: LpStatsSink? = null,
    context: LpSolveContext = LpSolveContext.Production,
    pricing: LpPricingOptions = LpPricingOptions(),
    toleranceCheck: ((Sample) -> Boolean)? = null,
    encoders: PreparedFactorProjections = problem.selectedFactorProjections(),
    sourceEncoding: Problem? = null,
): LeafRealResult {
    val encoded = sourceEncoding ?: encoders.finiteLinearModel(sample, cancellation)
        ?: return LeafRealResult(LpVerdict.INDETERMINATE, emptyDoubleArray())
    val relaxation = CpToLpRelaxation(problem, objective, projections = null).build(SampleDomains(sample))
    // A single leaf LP on a continuous-heavy model can outlast the budget, so the solve honors cancellation;
    // a solve cut short returns INDETERMINATE, never an unsound verdict.
    val certified = solveAndCertify(
        relaxation.model,
        cancellation = cancellation,
        componentSplit = componentSplit,
        observer = sink?.certificationObserver(LpRoute.STANDALONE),
        context = context,
        pricing = pricing,
        floatAccept = toleranceCheck.floatingOptimumAcceptance()?.let { check ->
            { result -> check(sample.copy(reals = relaxation.floatReals(result.primal, problem))) }
        },
        floatOffset = relaxation.objectiveConstant.toDouble(),
        claim = if (objective == null) LpClaim.FEASIBLE_COMPLETION else LpClaim.ATTAINED_OPTIMUM,
        acceptFloatInfeasibility = toleranceCheck.permitsToleranceOptimum(),
    )
    return relaxation.leafResult(certified, problem, objective, sample, sink)
        .checkedEncoding(encoded, sample, cancellation)
        .also { it.observeConsumption(sink) }
}

internal fun LpRelaxation.leafResult(
    certified: CertifiedLpResult,
    problem: Problem,
    objective: LinearObjective?,
    sample: Sample,
    sink: LpStatsSink?,
): LeafRealResult {
    certified.float?.let { sink?.observeComponentSplit(it.blocks) }
    if (problem.factors.any { it is ContinuousProduct && it.finiteChoices?.hasGuards != true } &&
        certified.verdict != LpVerdict.INFEASIBLE) {
        return LeafRealResult(LpVerdict.INDETERMINATE, emptyDoubleArray())
    }
    certified.floatOptimum?.let { float ->
        if (problem.factors.any { it is ContinuousProduct }) {
            return LeafRealResult(LpVerdict.INDETERMINATE, emptyDoubleArray())
        }
        return LeafRealResult(LpVerdict.TOLERANCE_OPTIMUM, floatReals(float.primal, problem))
    }
    if (certified.verdict == LpVerdict.INFEASIBLE) {
        return LeafRealResult(LpVerdict.INFEASIBLE, emptyDoubleArray(), refutingFactors = factorsOf(certified),
            refutesWithinSlack = certified.refutesWithinSlack)
    }
    val exact = exactLeafResult(certified, problem, objective, sample)
    return if (exact.verdict in setOf(LpVerdict.INDETERMINATE, LpVerdict.CERTIFIED_BOUND)) {
        LeafRealResult(exact.verdict, exact.reals, exact.exactReals,
            uncertifiedReals = certified.float?.let { floatReals(it.primal, problem) })
    } else exact
}

internal fun LpRelaxation.factorsOf(certified: CertifiedLpResult): IntArray {
    val rows = certified.infeasibleRows
        ?: (certified.farkasRay ?: certified.floatInfeasibility)
            ?.let { ray -> ray.indices.filter { ray[it] != 0L }.toIntArray() }
        ?: return emptyIntArray()
    val factors = IntArrayList()
    val seen = IntHashSet()
    for (row in rows) {
        val factor = rowFactorIds.getOrElse(row) { -1 }
        if (factor >= 0 && seen.add(factor)) factors.add(factor)
    }
    return factors.toIntArray()
}

// An UNBOUNDED certificate keeps its ray only when provesUnbounded accepts it, else stands as FEASIBLE.
internal fun LpRelaxation.exactLeafResult(
    certified: CertifiedLpResult,
    problem: Problem,
    objective: LinearObjective?,
    sample: Sample,
): LeafRealResult {
    if (problem.factors.any { it is ContinuousProduct && it.finiteChoices?.hasGuards != true }) {
        return LeafRealResult(LpVerdict.INDETERMINATE, emptyDoubleArray())
    }
    val unboundedness = certified.unboundedness
    val primal = unboundedness?.witness?.primal ?: certified.exactPrimal
        ?: return LeafRealResult(LpVerdict.INDETERMINATE, emptyDoubleArray())
    val exactReals = exactReals(primal, problem)
    val reals = DoubleArray(exactReals.size) { exactReals[it].toDouble() }
    if (unboundedness == null) return LeafRealResult(certified.verdict, reals, exactReals)
    val direction = realDirection(unboundedness.direction, problem)
    val point = sample.copy(reals = reals, exactReals = exactReals)
    return if (objective != null && direction != null && problem.provesUnbounded(objective, point, direction)) {
        LeafRealResult(LpVerdict.UNBOUNDED, reals, exactReals, direction)
    } else {
        LeafRealResult(LpVerdict.FEASIBLE, reals, exactReals)
    }
}

// Null when the direction moves a discrete column.
private fun LpRelaxation.realDirection(direction: List<BigFraction>, problem: Problem): List<BigFraction>? {
    val ray = MutableList(problem.numRealVars) { BigFraction.ZERO }
    for (col in direction.indices) {
        val r = colRealId.getOrElse(col) { -1 }
        when {
            r >= 0 -> ray[r] += BigFraction.ofLong(colRealSign[col].toLong()) * direction[col]
            col < colVarId.size && colVarId[col] >= 0 && !direction[col].isZero -> return null
        }
    }
    return ray
}

// A variable no column carries is in no emitted row and has no objective weight, so it takes restingReal.
internal fun LpRelaxation.floatReals(primal: DoubleArray, problem: Problem): DoubleArray {
    val reals = DoubleArray(problem.numRealVars)
    val carried = BooleanArray(problem.numRealVars)
    for (col in colRealId.indices) {
        val r = colRealId[col]
        if (r >= 0 && col < primal.size) {
            reals[r] += colRealSign[col] * primal[col]
            carried[r] = true
        }
    }
    for (r in reals.indices) if (!carried[r]) reals[r] = restingReal(problem, r)
    return reals
}

internal fun LpRelaxation.exactReals(primal: List<BigFraction>, problem: Problem): MutableList<BigFraction> {
    val reals = MutableList(problem.numRealVars) { BigFraction.ZERO }
    val carried = BooleanArray(problem.numRealVars)
    for (col in colRealId.indices) {
        val r = colRealId[col]
        if (r >= 0 && col < primal.size) {
            reals[r] += BigFraction.ofLong(colRealSign[col].toLong()) * primal[col]
            carried[r] = true
        }
    }
    for (r in reals.indices) if (!carried[r]) reals[r] = checkNotNull(BigFraction.ofDouble(restingReal(problem, r)))
    return reals
}

// Any point within the bounds completes a leaf for an omitted variable; zero alone may lie outside them.
private fun restingReal(problem: Problem, r: Int): Double = 0.0.coerceIn(problem.realLower[r], problem.realUpper[r])

internal class LeafRealResult(
    val verdict: LpVerdict,
    val reals: DoubleArray,
    val exactReals: List<BigFraction>? = null,
    val direction: List<BigFraction>? = null,
    // Factors whose rows an infeasibility proof combines.
    val refutingFactors: IntArray = emptyIntArray(),
    // A private numerical proposal; only an original-source point check may give it witness authority.
    val uncertifiedReals: DoubleArray? = null,
    // The exact coordinates uncertifiedReals rounds, when the proposal has them; the check reads these.
    val uncertifiedExactReals: List<BigFraction>? = null,
    // The value of the real part of the objective that the leaf approaches but does not attain, when its search
    // proved that; no point of the leaf reaches it.
    val unattainedInfimum: BigFraction? = null,
    // Whether an infeasibility refutation survives widening every side by the refutation slack, so no point
    // within the source tolerance completes the leaf either.
    val refutesWithinSlack: Boolean = false,
)

internal fun LeafRealResult.checkedEncoding(
    encoded: Problem,
    sample: Sample,
    cancellation: ExecutionControl,
): LeafRealResult {
    if (exactReals == null || verdict == LpVerdict.INFEASIBLE) return this
    val point = sample.copy(reals = reals, exactReals = exactReals)
    if (encoded.encodingSourceRealCount != encoded.numRealVars) {
        return if (privateLinearCompletion(encoded, point, cancellation) == true) this
        else LeafRealResult(LpVerdict.INDETERMINATE, emptyDoubleArray(), uncertifiedReals = reals)
    }
    val checked = verifyRealCoordinates(encoded, point)
    return if (!cancellation() && checked is Verification.Accepted) this
    else LeafRealResult(LpVerdict.INDETERMINATE, emptyDoubleArray(), uncertifiedReals = reals)
}

internal fun LeafRealResult.observeConsumption(sink: LpStatsSink?) {
    val claim = when (verdict) {
        LpVerdict.INFEASIBLE -> LpClaim.CONTRADICTION
        LpVerdict.FEASIBLE, LpVerdict.UNBOUNDED -> LpClaim.FEASIBLE_COMPLETION
        LpVerdict.ATTAINED_OPTIMUM -> LpClaim.ATTAINED_OPTIMUM
        else -> null
    }
    claim?.consumed(sink?.certificationObserver(LpRoute.STANDALONE))
}
