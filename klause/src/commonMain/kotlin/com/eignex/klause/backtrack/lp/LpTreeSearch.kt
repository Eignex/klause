package com.eignex.klause.backtrack.lp

import com.eignex.klause.lp.bounding.LpEngine
import com.eignex.klause.lp.bounding.LpFractionalBranch
import com.eignex.klause.lp.bounding.solveNode
import com.eignex.klause.lp.bounding.trailModel
import com.eignex.klause.lp.engine.Basis
import com.eignex.klause.lp.engine.CertifiedLpResult
import com.eignex.klause.lp.engine.CrashBasisAttempt
import com.eignex.klause.lp.engine.ExactLpNumber
import com.eignex.klause.lp.engine.FloatLpResult
import com.eignex.klause.lp.engine.LpCertificationObserver
import com.eignex.klause.lp.engine.LpCertifier
import com.eignex.klause.lp.engine.LpDualizationDecline
import com.eignex.klause.lp.engine.LpDualizationMetrics
import com.eignex.klause.lp.engine.LpExactState
import com.eignex.klause.lp.engine.LpModel
import com.eignex.klause.lp.engine.LpRootAdmission
import com.eignex.klause.lp.engine.LpRootDualizationAttempt
import com.eignex.klause.lp.engine.LpSolveMetrics
import com.eignex.klause.lp.engine.LpSolver
import com.eignex.klause.lp.engine.LpVerdict
import com.eignex.klause.lp.engine.acceptNullable
import com.eignex.klause.lp.engine.certifiedTightObjectiveLowerBound
import com.eignex.klause.lp.engine.certifyDualizedSource
import com.eignex.klause.lp.engine.exactPointWitness
import com.eignex.klause.lp.engine.triangularCrashBasis
import com.eignex.klause.propagation.CpBranching
import com.eignex.klause.propagation.CpSearchComponent
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.search.BooleanBranching
import com.eignex.klause.solver.search.SearchComponentSet
import com.eignex.klause.solver.search.SearchContext
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.SearchNodeDisposition
import com.eignex.klause.solver.search.SearchNodePolicy
import com.eignex.klause.solver.search.SearchRunEvent
import com.eignex.klause.solver.search.SearchSolveParams
import com.eignex.klause.util.Cancellation
import com.ionspin.kotlin.bignum.integer.BigInteger

internal fun LpEngine.lbTreeSearch(objective: LinearObjective, cancellation: Cancellation): Sample? {
    if (lpRelaxer == null) return null
    // The heuristic has an independent source root; the optimizing caller keeps its own trail.
    val token = Cancellation { cancellation() || params.cancellation() }
    val dive = forObjective(objective, token)
    return dive.use {
        val relaxer = dive.lpRelaxer ?: return@use null
        val cp = CpSearchComponent(PropagationSession(problem, token), branching = CpBranching.None)
        val native = cp.session
        var split: LpFractionalBranch? = null
        var targets = emptyMap<Int, Long>()
        var bestExact: BigFraction? = null
        var expansions = 0
        dive.cpAdapter.attach(native, feasibility = false)
        dive.cpAdapter.fractional = { split }
        dive.cpAdapter.branching = {
            val variable = (0 until problem.numIntVars).firstOrNull { native.intDomain(it).valueCount > 1 }
            if (variable == null) {
                null
            } else {
                val domain = native.intDomain(variable)
                val target = targets[variable]?.takeIf { domain.contains(it) } ?: domain.min
                buildList {
                    add(SearchDecision.IntEqual(variable, target))
                    if (target > domain.min) add(SearchDecision.IntAtMost(variable, target - 1L))
                    if (target < domain.max) add(SearchDecision.IntAtLeast(variable, target + 1L))
                }
            }
        }
        val shared = SearchComponentSet(listOf(cp, dive.propagator)).session(cancellation = token)
        val policy = object : SearchNodePolicy {
            override fun beforeBranch(context: SearchContext): SearchNodeDisposition {
                split = null
                targets = emptyMap()
                if (expansions++ >= LB_TREE_BUDGET) return SearchNodeDisposition.Indeterminate
                val relaxation = dive.nodeRelaxation(relaxer, native)
                val node = this@lbTreeSearch.solveRootNode(dive, relaxation.model, token, expansions == 1)
                val model = node.model
                val result = node.float
                val mapped = node.mapped
                val lower = mapped?.lowerBound?.plus(BigFraction.ofLong(relaxation.objectiveConstant))
                    ?: result?.let { candidate ->
                        certifiedTightObjectiveLowerBound(
                            model,
                            candidate.duals,
                            rootCertificationObserver(),
                            solveContext.certificationPolicy,
                        )?.let { BigFraction.ofDouble(it) }?.plus(BigFraction.ofLong(relaxation.objectiveConstant))
                    }
                if (lower != null && bestExact?.let { lower >= it } == true) return SearchNodeDisposition.Prune
                val witness = mapped?.witness ?: result?.let { candidate ->
                    solveContext.certificationPolicy.acceptNullable(
                        LpCertifier.EXACT_POINT,
                        exactPointWitness(model, candidate.primal, rootCertificationObserver()),
                    )
                } ?: return SearchNodeDisposition.Expand
                targets = relaxation.colVarId.indices.mapNotNull { column ->
                    val variable = relaxation.colVarId[column]
                    if (variable < 0 || relaxation.colIsBool[column]) {
                        null
                    } else {
                        ExactLpNumber.of(witness.primal[column]).legacyLong()?.let { variable to it }
                    }
                }.toMap()
                for (column in relaxation.colVarId.indices) {
                    val variable = relaxation.colVarId[column]
                    val value = witness.primal[column]
                    if (variable < 0 || value.den == BigInteger.ONE) continue
                    split = LpFractionalBranch(variable, value, relaxation.colIsBool[column], registered = false)
                    break
                }
                return SearchNodeDisposition.Expand
            }
        }
        val run = shared.openRun(
            problem.numBoolVars,
            SearchSolveParams(maxDecisions = LB_TREE_BUDGET),
            booleanBranching = BooleanBranching.SourceOrder(problem.numBoolVars),
            nodePolicy = policy,
        )
        var best: Sample? = null
        while (!token()) {
            val next = run.next()
            if (next !is SearchRunEvent.Satisfied) break
            var sample = requireNotNull(next.model.valueOf<Sample>(cp))
            val exactReals = if (problem.numRealVars > 0) {
                val leaf = dive.leafCertify(native)
                when (leaf.verdict) {
                    LpVerdict.FEASIBLE, LpVerdict.ATTAINED_OPTIMUM, LpVerdict.UNBOUNDED -> Unit
                    else -> continue
                }
                sample = Sample(sample.bools, sample.ints, leaf.reals)
                leaf.exactReals ?: continue
            } else {
                emptyList()
            }
            var value = BigFraction.ofLong(objective.constant)
            for (variable in objective.intCoefficients.indices) {
                value += BigFraction.ofLong(
                    objective.intCoefficients[variable],
                ) * BigFraction.ofLong(sample.ints[variable])
            }
            for (variable in objective.boolWeights.indices) {
                if (sample.bools[variable]) value += BigFraction.ofLong(objective.boolWeights[variable])
            }
            for (variable in objective.realCoefficients.indices) {
                value += requireNotNull(
                    BigFraction.ofDouble(objective.realCoefficients[variable]),
                ) * exactReals[variable]
            }
            if (bestExact?.let { value < it } != false) {
                bestExact = value
                best = sample
            }
        }
        best
    }
}

internal class LpRootNodeResult(
    val model: LpModel,
    val float: FloatLpResult?,
    val mapped: CertifiedLpResult?,
    val dualization: LpDualizationMetrics?,
)

@Suppress("TooGenericExceptionCaught", "ThrowingExceptionFromFinally")
internal fun LpEngine.solveRootNode(
    dive: LpEngine,
    original: LpModel,
    token: Cancellation,
    root: Boolean,
    observer: LpCertificationObserver = rootCertificationObserver(),
): LpRootNodeResult {
    val options = solveContext.rootDualization
    val dualization = if (root && options.enabled) LpRootDualizationAttempt(options) else null
    val parentWork = dive.nodeWorkBudget()
    val parentPivots = dive.nodePivotBudget()
    var model = original
    var source: LpExactState? = null
    var preparationWork = 0L
    var crashWork = 0L
    var sourceMetrics = LpSolveMetrics()
    var sourceReturned = false
    val attempt = try {
        solveRootNodeWithCrash(
            null,
            solve = {
                var basis: Basis? = null
                if (dualization != null) {
                    val estimate = (original.numVars.toLong() * 8L + original.csc.colVal.size.toLong() * 4L)
                    val decline = when {
                        token() -> LpDualizationDecline.CANCELLED
                        original.n == 0 ||
                            original.m.toLong() < options.minRowColumnRatio.toLong() * original.n ->
                            LpDualizationDecline.NOT_TALL
                        original.numVars > options.maxCoordinates ||
                            original.csc.colVal.size > options.maxEntries -> LpDualizationDecline.DIMENSION
                        parentWork > 0L && estimate > parentWork / 8L -> LpDualizationDecline.WORK
                        else -> null
                    }
                    if (decline != null) {
                        dualization.decline(decline)
                    } else {
                        preparationWork = estimate
                        source = original.exactState ?: original.trailModel()?.let(::LpExactState)
                        val projection = source?.toWorkingModel()
                        if (projection == null) {
                            dualization.decline(LpDualizationDecline.PROJECTION)
                            return@solveRootNodeWithCrash null
                        } else {
                            model = projection
                            basis = dualization.solve(
                                requireNotNull(source),
                                solveContext,
                                dive.pricingOptions,
                                token,
                                parentWork,
                                parentPivots,
                                preparationWork,
                            )
                        }
                    }
                }
                if (token()) return@solveRootNodeWithCrash null
                val auxiliaryWork = (dualization?.metrics?.totalWork ?: 0L) + preparationWork
                if (root && basis == null) {
                    val remaining = if (parentWork == 0L) 0L else parentWork - auxiliaryWork
                    if (parentWork != 0L && remaining <= 1L) return@solveRootNodeWithCrash null
                    val crash = rootCrashBasis(model, token, remaining)
                    crashWork = crash.metrics.workOps
                    basis = crash.basis
                }
                val admission = if (source != null && model.exactState === source) {
                    val work = if (parentWork == 0L) null else parentWork - auxiliaryWork - crashWork
                    val pivots = if (parentPivots ==
                        0
                    ) {
                            null
                        } else {
                            parentPivots - (dualization?.metrics?.solve?.pivots ?: 0)
                        }
                    if ((work != null && work < 2L) ||
                        (pivots != null && pivots <= 0)
                    ) {
                            return@solveRootNodeWithCrash null
                        }
                    LpRootAdmission(model, work, pivots)
                } else {
                    null
                }
                val previousMetrics = dive.propagator.lastMetrics
                var failure: Throwable? = null
                try {
                    dive.solveNode(model, basis, token, admission).also { sourceReturned = it != null }
                } catch (primary: Throwable) {
                    failure = primary
                    throw primary
                } finally {
                    try {
                        sourceMetrics = if (dive.nodeUsesTrail) {
                            dive.propagator.lastMetrics.takeUnless { it === previousMetrics } ?: LpSolveMetrics()
                        } else {
                            dive.nodeSimplex?.lastMetrics ?: LpSolveMetrics()
                        }
                    } catch (measurement: Throwable) {
                        if (failure == null) throw measurement
                        failure.addSuppressed(measurement)
                    }
                }
            },
            solveMetrics = { sourceMetrics },
            additionalMetrics = {
                (dualization?.metrics?.rootMetrics ?: LpSolveMetrics()) +
                    LpSolveMetrics(workOps = preparationWork + crashWork) +
                    if (sourceReturned) LpSolveMetrics() else sourceMetrics
            },
        )
    } finally {
        dualization?.let { observer.observeDualization(it.metrics) }
    }
    val mapped = dualization?.let { certifyDualizedSource(model, it, solveContext.certificationPolicy, token) }
    return LpRootNodeResult(model, attempt?.second, mapped, dualization?.metrics)
}

internal fun rootCrashBasis(model: LpModel, token: Cancellation, nodeWorkLimit: Long): CrashBasisAttempt =
    triangularCrashBasis(model, token, rootCrashWorkLimit(nodeWorkLimit))

internal fun solveRootNodeWithCrash(
    crash: CrashBasisAttempt?,
    solve: () -> Pair<LpSolver, FloatLpResult?>?,
    solveMetrics: (LpSolver) -> LpSolveMetrics,
    observe: (LpSolver, LpSolveMetrics) -> Unit,
    additionalMetrics: () -> LpSolveMetrics = { LpSolveMetrics() },
): Pair<LpSolver, FloatLpResult?>? {
    var charged = false
    var unchargedCrashWork = crash?.metrics?.workOps ?: 0L
    try {
        val attempt = solve() ?: return null
        val combinedMetrics = solveMetrics(
            attempt.first,
        ) + LpSolveMetrics(workOps = unchargedCrashWork) + additionalMetrics()
        unchargedCrashWork = 0L
        charged = true
        observe(attempt.first, combinedMetrics)
        return attempt
    } finally {
        if (!charged) {
            val metrics = LpSolveMetrics(workOps = unchargedCrashWork) + additionalMetrics()
            if (metrics.workOps > 0L) observe(CRASH_WORK_ONLY_SOLVER, metrics)
        }
    }
}

internal fun LpEngine.solveRootNodeWithCrash(
    crash: CrashBasisAttempt?,
    solve: () -> Pair<LpSolver, FloatLpResult?>?,
    solveMetrics: (LpSolver) -> LpSolveMetrics,
    additionalMetrics: () -> LpSolveMetrics = { LpSolveMetrics() },
): Pair<LpSolver, FloatLpResult?>? = solveRootNodeWithCrash(
    crash,
    solve,
    solveMetrics,
    this::observeRootSolve,
    additionalMetrics,
)

internal fun rootCrashWorkLimit(nodeWorkLimit: Long): Long = if (nodeWorkLimit == 0L) {
    ROOT_CRASH_WORK_LIMIT
} else {
    maxOf(1L, minOf(nodeWorkLimit / 8L, ROOT_CRASH_WORK_LIMIT))
}

private const val LB_TREE_BUDGET = 256L
private const val ROOT_CRASH_WORK_LIMIT = 100_000L

private val CRASH_WORK_ONLY_SOLVER = object : LpSolver {
    override val infeasibleRay: DoubleArray? = null
    override fun solve(warm: Basis?) = null
    override fun solvePrimal(warm: Basis?) = null
}
