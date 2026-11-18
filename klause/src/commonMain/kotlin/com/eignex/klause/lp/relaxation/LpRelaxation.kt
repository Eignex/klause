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

/**
 * An LP relaxation of a `Problem` at one search node, plus the metadata mapping each LP column
 * back to the CP variable it stands for. The mapping is what lets reduced-cost fixing turn
 * an LP column reduction into a domain reduction on the right `(kind, varId)`.
 *
 * The LP objective is over the column costs only; the true objective is
 * `lpObjective + objectiveConstant`. Branch-and-bound must add [objectiveConstant] before
 * comparing the LP bound to the incumbent.
 */
internal class LpRelaxation(
    val model: LpModel,
    /** Structural LP column → origin CP variable id. */
    val colVarId: IntArray,
    /** Structural LP column → true if it is a Boolean variable, false if an integer variable. */
    val colIsBool: BooleanArray,
    /** Constant term of the objective, omitted from the LP and re-added to its bound. */
    val objectiveConstant: Long,
    /** Integer variable id → its LP column, or -1 if none. For separators to write cuts. */
    val intColOf: IntArray,
    /** Boolean variable id → its LP column, or -1 if none. */
    val boolColOf: IntArray,
    /** Arc-indicator models of any Circuit factors, for the subtour-elimination separator. */
    val circuitArcs: List<CircuitArcModel> = emptyList(),
    /**
     * Whether this relaxation keeps the same layout across search nodes, allowing column bounds
     * to follow a trail over the fixed matrix. True when no row's coefficients depend on live domains
     * and every structural column can be derived from the live session: either a CP variable's domain
     * or an auxiliary column's [colReq] presence rule. These live bounds reproduce the feasible region
     * of a per-node build.
     */
    val persistentEligible: Boolean = false,
    /**
     * Per structural column, an auxiliary column's presence requirement as flat `(intVar, value)`
     * membership pairs — the column is present (upper [colPresentUpper]) only while every named value
     * stays in its variable's live domain. Null for CP-var-backed columns and for auxiliary columns
     * with no rule (which make the relaxation persistent-ineligible). Empty by default.
     */
    val colReq: Array<LongArray?> = arrayOfNulls(model.n),
    /** Per structural column, the upper bound an auxiliary column takes when present (see [colReq]);
     *  unused for CP-var columns. */
    val colPresentUpper: LongArray = LongArray(model.n),
    /** Ids of the factors that emitted at least one [Contribution.HULL] row, in factor order — the
     *  candidates the root pruner (`LpEngine.pruneIneffectiveHulls`) probes for individual removal. */
    val hullFactorIds: IntArray = emptyIntArray(),
    /** Structural LP column → the LP-only continuous (real) variable id it stands for, or -1 for an
     *  integer / Boolean / auxiliary column. Lets the leaf verdict read each continuous column's solved
     *  value back onto its real variable. */
    val colRealId: IntArray = IntArray(model.n) { -1 },
    /** Per structural column, the sign its primal contributes to its real variable's value: a
     *  lower-unbounded real variable is split `x = x⁺ − x⁻` (two columns, both lower-bounded at 0, so
     *  no huge lower-shift ever reaches the rhs doubles), and the negative part carries -1. */
    val colRealSign: IntArray = IntArray(model.n) { 1 },
    /** Gated residual rows (pure-real models only): row index per gated entry. Each reified real atom
     *  contributes its atom row AND its complement row unconditionally — with their true rhs — so the
     *  model's structure and coefficients never change across nodes and a persistent simplex's LU
     *  factorization survives; a node merely tells the solver which rows are enforced
     *  ([gatedEnforcement]), and an unenforced row's slack rides free. */
    val gatedRows: IntArray = emptyIntArray(),
    /** The aux Boolean whose pin activates gated entry k. */
    val gatedAux: IntArray = emptyIntArray(),
    /** The pin value activating gated entry k (`true` = the atom row, `false` = its complement). */
    val gatedWhenTrue: BooleanArray = BooleanArray(0),
    val sourceMap: CutSourceMap? = null,
    val colPresence: List<CutAuxiliaryDefinition?> = List(model.n) { null },
    /** The factor each row of [model] was emitted for, or -1 for a cut or a row spanning several factors. */
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

/**
 * Apply [action] to every candidate arc head of successor column `succ(i)` over an `n`-node graph: the
 * heads in `[0, n)` that [dom] admits, `i` itself only when [selfLoops].
 *
 * Walks whichever of the two sides is smaller, because neither can be the only one. A large sparse
 * routing graph carries a handful of heads per node, so sweeping the node range would cost `O(n²)` on
 * exactly the models the arc cap exists to let through; a successor declared far wider than the graph
 * makes the domain walk `O(box span)`, which past `2³¹` values cannot be enumerated at all.
 */
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

/**
 * Candidate arcs over `succ[0..n)` (see [eachCandidateArcHead]), counted no further than [cap] — above
 * it the arc model is declined, so the exact total is never read and a dense graph must not pay for one.
 */
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

/**
 * Fill [out] (length `model.m`) with per-row enforcement at [session]'s pins: every non-gated row is
 * enforced, a gated row only while its activating pin holds — at most one of an atom/complement pair.
 * The persistent simplex treats an unenforced row's slack as free, so the row never constrains.
 */
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

/**
 * The per-variable bounds the relaxation reads to size its columns: an integer variable's live domain
 * and a Boolean variable's pin (null when free). A [PropagationSession] is one source (its live search
 * state); [RootDomains] is another (the problem's root boxes, read without running the bake
 * fixpoint) so a root relaxation can be built in O(model) rather than O(domain span).
 */
internal interface RelaxationDomains {
    /** The live domain of integer variable [varId]. */
    fun intDomain(varId: Int): IntDomain

    /** The pin of Boolean variable [varId] — `true`/`false` when fixed, null when free. */
    fun boolValue(varId: Int): Boolean?

    /**
     * Whether a side `Problem.intBounds` marks as invented should be built as an
     * open LP column rather than at the finite clamp. Only the root view may: a bound in a live search
     * state is a decision the node depends on, and cannot be told apart from the clamp it sits on, so
     * relaxing it there would drop the branch's own constraint.
     */
    val honorsOpenSides: Boolean get() = false
}

/** [RelaxationDomains] over a `Problem`'s root boxes: every integer variable at its full box and every
 *  Boolean free. Reads endpoints only, so building a relaxation from it never triggers the O(domain
 *  span) bake fixpoint a [PropagationSession] runs on construction. */
internal class RootDomains(private val problem: Problem) : RelaxationDomains {
    override fun intDomain(varId: Int): IntDomain = problem.rootDomainOf(varId)
    override fun boolValue(varId: Int): Boolean? = null
    override val honorsOpenSides: Boolean get() = true
}

/** [RelaxationDomains] backed by a live [PropagationSession]'s search state. */
internal class SessionDomains(private val session: PropagationSession) : RelaxationDomains {
    override fun intDomain(varId: Int): IntDomain = session.intDomain(varId)
    override fun boolValue(varId: Int): Boolean? = session.boolValue(varId)
}

/** [RelaxationDomains] pinning every integer variable to its value in a full-assignment [sample] and
 *  every Boolean to its bit — the leaf view where the residual LP over the continuous columns is all
 *  that remains undecided (see [leafRealFeasibility]). */
private class SampleDomains(private val sample: Sample) : RelaxationDomains {
    override fun intDomain(varId: Int): IntDomain = sample.intValue(varId).let { IntDomain(it, it) }
    override fun boolValue(varId: Int): Boolean = sample.boolValue(varId)
}

/**
 * The certified feasibility verdict of the LP-only continuous relaxation at a full-assignment leaf
 * [sample], with every discrete variable pinned to its assigned value so the LP decides only the
 * continuous columns. [LpVerdict.FEASIBLE] means the reals have an exact feasible completion — the leaf is a
 * genuine solution — and [LpVerdict.ATTAINED_OPTIMUM] that the completion is also exactly optimal;
 * [LpVerdict.TOLERANCE_OPTIMUM] is a float optimum [toleranceCheck] accepted, whose reals are not exact;
 * [LpVerdict.UNBOUNDED] is an exact completion plus a ray of the reals along which [objective] descends
 * without limit, checked against the factors by [provesUnbounded]; a ray that check declines leaves the
 * completion as [LpVerdict.FEASIBLE];
 * [LpVerdict.INFEASIBLE] rejects the leaf by an exact conflict, or by the numerical ray check when the
 * source and [toleranceCheck] permit numerical deductions;
 * [LpVerdict.INDETERMINATE] means neither could be certified within the caller's allowances, so the leaf's
 * status is unknown and the terminal verdict must degrade to `unknown` rather than claim UNSAT/SAT.
 *
 * The discrete constraints already hold at a CP-consistent leaf, so their linear rows are satisfiable by
 * the fixed values and only the LP-only real rows can bind — making this exactly a residual real-LP
 * feasibility test. Called only when the problem declares continuous variables.
 */
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
    // Bound the residual-LP solve by the search deadline: on a continuous-heavy model a single leaf LP is a
    // large factorization, so an unbounded solve could outlast the whole budget. A solve cut short returns
    // INDETERMINATE, degrading the leaf to `unknown` — never an unsound SAT/UNSAT.
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

/** The factors behind the rows [certified]'s conflict or citable ray combines. */
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

/**
 * The exact completion a certified leaf LP gives [sample]'s discrete values, or INDETERMINATE without one. On
 * [LpVerdict.UNBOUNDED] it carries the ray of the reals only when [provesUnbounded] accepts the pair, and otherwise
 * stands as a [LpVerdict.FEASIBLE] completion.
 */
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

/**
 * The continuous part of an LP recession [direction], summing each split variable's `x⁺ − x⁻` columns, or null
 * when it moves a discrete column. A real variable no column carries does not move.
 */
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

/**
 * Continuous variable values of an LP [primal], summing each split variable's `x⁺ − x⁻` columns. A variable no
 * column carries appears in no emitted row and has no objective weight, so it takes [restingReal].
 */
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

/** The exact counterpart of [floatReals], over a certified exact [primal]. */
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

/**
 * The point of real variable [r]'s bounds nearest zero. Any point of them completes a leaf for a variable the
 * relaxation leaves out; zero is not one when the bounds exclude it, as for a column fixed away from it.
 */
private fun restingReal(problem: Problem, r: Int): Double = 0.0.coerceIn(problem.realLower[r], problem.realUpper[r])

/** The residual-LP verdict at a leaf plus, on [LpVerdict.FEASIBLE], the continuous variables' solved
 *  values (indexed by real var id) that complete the discrete assignment into a full solution. On
 *  [LpVerdict.UNBOUNDED], [direction] is the checked ray of the reals through that completion. */
internal class LeafRealResult(
    val verdict: LpVerdict,
    val reals: DoubleArray,
    val exactReals: List<BigFraction>? = null,
    val direction: List<BigFraction>? = null,
    /** On [LpVerdict.INFEASIBLE], the factors whose rows the exact infeasibility proof combines; empty when the proof
     *  names no rows. */
    val refutingFactors: IntArray = emptyIntArray(),
    // A private numerical proposal; only an original-source point check may give it witness authority.
    val uncertifiedReals: DoubleArray? = null,
    // The exact coordinates [uncertifiedReals] rounds, when the proposal has them; the check reads these.
    val uncertifiedExactReals: List<BigFraction>? = null,
    // The value of the real part of the objective that the leaf approaches but does not attain, when its search
    // proved that; no point of the leaf reaches it.
    val unattainedInfimum: BigFraction? = null,
    // On [LpVerdict.INFEASIBLE], whether the refutation holds with every side widened by the solve's refutation slack,
    // so no point within the source tolerance completes the leaf either.
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
