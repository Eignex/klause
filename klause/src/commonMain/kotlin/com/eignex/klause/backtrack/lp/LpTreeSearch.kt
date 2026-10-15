package com.eignex.klause.backtrack.lp

import com.eignex.klause.backtrack.ComposedSampleVerifier
import com.eignex.klause.backtrack.NodeBudget
import com.eignex.klause.backtrack.exactSourceObjective
import com.eignex.klause.lp.bounding.LpEngine
import com.eignex.klause.lp.bounding.LpFractionalBranch
import com.eignex.klause.lp.bounding.solveNode
import com.eignex.klause.lp.engine.Basis
import com.eignex.klause.lp.engine.CrashBasisAttempt
import com.eignex.klause.lp.engine.ExactLpNumber
import com.eignex.klause.lp.engine.FloatLpResult
import com.eignex.klause.lp.engine.LpCertifier
import com.eignex.klause.lp.engine.LpModel
import com.eignex.klause.lp.engine.LpSolveMetrics
import com.eignex.klause.lp.engine.LpSolver
import com.eignex.klause.lp.engine.LpVerdict
import com.eignex.klause.lp.engine.acceptNullable
import com.eignex.klause.lp.engine.ceilLong
import com.eignex.klause.lp.engine.certifiedTightObjectiveLowerBound
import com.eignex.klause.lp.engine.exactPointWitness
import com.eignex.klause.lp.engine.triangularCrashBasis
import com.eignex.klause.lp.relaxation.LeafRealResult
import com.eignex.klause.lp.relaxation.LeafRealSolver
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.CpBranching
import com.eignex.klause.propagation.CpSearchComponent
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.incumbent.Candidate
import com.eignex.klause.solver.incumbent.Verification
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.search.BooleanBranching
import com.eignex.klause.solver.search.SearchComponentSet
import com.eignex.klause.solver.search.SearchContext
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.SearchDecisionBudget
import com.eignex.klause.solver.search.SearchNodeDisposition
import com.eignex.klause.solver.search.SearchNodePolicy
import com.eignex.klause.solver.search.SearchRunEvent
import com.eignex.klause.solver.search.SearchSolveParams
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Cancellation

// Probe exhaustion supplies no source verdict, bound or recession claim.
internal class LpTreeSeed(val sample: Sample)

internal fun LpEngine.lbTreeSearch(
    objective: LinearObjective,
    cancellation: Cancellation,
    assumptions: Assumptions = Assumptions.None,
    toleranceCheck: ((Sample) -> Boolean)? = null,
    complete: ((Sample, Cancellation, Long) -> LeafRealResult)? = null,
    accept: ((Sample) -> Sample?)? = null,
    nodeBudget: NodeBudget? = null,
): LpTreeSeed? {
    if (lpRelaxer == null || cancellation() || params.cancellation()) return null
    for (variable in 0 until problem.numIntVars) {
        if (!problem.intBounds.hasLower(variable) || !problem.intBounds.hasUpper(variable)) return null
    }
    // The heuristic has an independent source root under the caller's assumptions; the optimizing caller keeps its
    // own trail.
    val stop = cancellation or params.cancellation
    var native: PropagationSession? = null
    var propagationWork = 0L
    fun accountPropagation() {
        val current = native?.work ?: 0L
        primalPropagationWork += current - propagationWork
        propagationWork = current
    }
    val token = object : Cancellation {
        override fun isCancelled(): Boolean {
            accountPropagation()
            return stop() || (params.lpPlan.rootBudgetFraction > 0.0 &&
                workSpentExceeds(params.lpPlan.rootMaxWork, sink.lp.standaloneWork)) ||
                lpWorkAllowanceSpent(sink.lp.standaloneWork)
        }
        override fun deadline() = stop.deadline()
        override fun workMeter() = stop.workMeter()
    }
    val dive = forObjective(objective, token, rootProbe = true)
    val verifier = ComposedSampleVerifier(problem, objective, token, assumptions,
        { primalPropagationWork += it }, toleranceCheck)
    try {
        return dive.use {
            val leafSolver = if (problem.numRealVars > 0 && complete == null) {
                LeafRealSolver(problem, objective, params.lpPlan.componentSplit, sink.lp, solveContext, pricingOptions)
            } else null
            leafSolver.use {
                val relaxer = dive.lpRelaxer ?: return null
                val cp = CpSearchComponent(PropagationSession(problem, token), branching = CpBranching.None)
                val session = cp.session
                native = session
                if (session.seed(assumptions) is PropagationResult.Unsat || session.isUnsatAtRoot) return null
                cp.rebase()
                var split: LpFractionalBranch? = null
                var targets = emptyMap<Int, Long>()
                var bestExact: BigFraction? = null
                var bestValue: BigFraction? = null
                var expansions = 0
                dive.cpAdapter.attach(session, feasibility = false)
                dive.cpAdapter.fractional = { split }
                dive.cpAdapter.branching = {
                    val variable = (0 until problem.numIntVars).firstOrNull { session.intDomain(it).valueCount > 1 }
                    if (variable == null) {
                        null
                    } else {
                        val domain = session.intDomain(variable)
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
                        val relaxation = dive.nodeRelaxation(relaxer, session)
                            ?: return SearchNodeDisposition.Indeterminate
                        val node = dive.solveRootNode(dive, relaxation.model, token, expansions == 1)
                        val model = node.model
                        val result = node.float
                        val lower = result?.let { candidate ->
                            certifiedTightObjectiveLowerBound(
                                model,
                                candidate.duals,
                                rootCertificationObserver(),
                                solveContext.certificationPolicy,
                            )?.let { BigFraction.ofDouble(it) }?.plus(BigFraction.ofLong(relaxation.objectiveConstant))
                        }
                        if (lower != null && bestExact?.let { lower >= it } == true) return SearchNodeDisposition.Prune
                        val witness = result?.let { candidate ->
                            solveContext.certificationPolicy.acceptNullable(
                                LpCertifier.EXACT_POINT,
                                exactPointWitness(model, candidate.primal, rootCertificationObserver()),
                            )
                        }
                        // A numerical point may order exhaustive choices without certifying a source witness.
                        val point = witness?.primal ?: if (problem.numRealVars > 0) {
                            result?.takeIf { it.optimal }?.primal
                                ?.takeIf { it.size == model.n && it.all(Double::isFinite) }
                                ?.map { requireNotNull(BigFraction.ofDouble(it)) }
                        } else null
                        if (point == null) return SearchNodeDisposition.Expand
                        dive.noteNodeOverhead(point.size.toLong())
                        targets = relaxation.colVarId.indices.mapNotNull { column ->
                            val variable = relaxation.colVarId[column]
                            if (variable < 0 || relaxation.colIsBool[column]) {
                                null
                            } else {
                                ExactLpNumber.of(point[column]).legacyLong()?.let { variable to it }
                            }
                        }.toMap()
                        for (column in relaxation.colVarId.indices) {
                            val variable = relaxation.colVarId[column]
                            val value = point[column]
                            if (variable < 0 || value.den == BIG_ONE) continue
                            if (relaxation.colIsBool[column]) {
                                if (session.boolValue(variable) != null || value <= BigFraction.ZERO ||
                                    value >= BigFraction.ONE
                                ) continue
                            } else {
                                val upper = value.ceilLong() ?: continue
                                val domain = session.intDomain(variable)
                                if (upper <= domain.min || upper > domain.max) continue
                            }
                            split = LpFractionalBranch(variable, value,
                                relaxation.colIsBool[column], registered = false)
                            break
                        }
                        return SearchNodeDisposition.Expand
                    }
                }
                val decisions = minOf(LB_TREE_BUDGET, nodeBudget?.decisionsLeft() ?: Long.MAX_VALUE)
                val run = shared.openRun(
                    problem.numBoolVars,
                    SearchSolveParams(maxDecisions = decisions),
                    decisionBudget = SearchDecisionBudget {
                        primalSearchWork++
                        nodeBudget?.spend()
                        !token() && nodeBudget?.exhausted() != true
                    },
                    booleanBranching = BooleanBranching.SourceOrder(problem.numBoolVars),
                    nodePolicy = policy,
                )
                var best: Sample? = null
                while (!token()) {
                    val next = run.next()
                    if (next !is SearchRunEvent.Satisfied) break
                    var sample = requireNotNull(next.model.valueOf<Sample>(cp))
                    if (problem.numRealVars > 0) {
                        val rootWork = rootPrimalWorkBudget(sink.lp.standaloneWork)
                        val offered = nodeQueryWorkBudget()
                        val work = when {
                            rootWork == 0L -> offered ?: 0L
                            offered == null -> rootWork
                            else -> minOf(offered, rootWork)
                        }
                        val leaf = complete?.invoke(sample, token, work)
                            ?: requireNotNull(leafSolver).solve(sample, token, toleranceCheck, work)
                        when (leaf.verdict) {
                            LpVerdict.FEASIBLE, LpVerdict.ATTAINED_OPTIMUM, LpVerdict.UNBOUNDED,
                            LpVerdict.TOLERANCE_OPTIMUM -> Unit
                            else -> continue
                        }
                        if (leaf.verdict == LpVerdict.TOLERANCE_OPTIMUM && toleranceCheck == null) continue
                        sample = sample.copy(reals = leaf.reals, exactReals = leaf.exactReals)
                    }
                    if (token()) break
                    val accepted = if (accept != null) accept(sample) else {
                        val checked = verifier.verify(Candidate(sample, objective.evaluate(sample)))
                        if (checked is Verification.Accepted) checked.candidate.assignment else null
                    } ?: continue
                    if (token()) break
                    val value = exactSourceObjective(objective, accepted, toleranceCheck) ?: continue
                    if (bestValue?.let { value < it } != false) {
                        bestValue = value
                        best = accepted
                    }
                    if (problem.numRealVars == 0 || accepted.exactReals != null) {
                        if (bestExact?.let { value < it } != false) bestExact = value
                    }
                }
                best?.let(::LpTreeSeed)
            }
        }
    } finally {
        accountPropagation()
    }
}

internal class LpRootNodeResult(val model: LpModel, val float: FloatLpResult?)

@Suppress("TooGenericExceptionCaught", "ThrowingExceptionFromFinally")
internal fun LpEngine.solveRootNode(
    dive: LpEngine,
    original: LpModel,
    token: Cancellation,
    root: Boolean,
): LpRootNodeResult {
    val parentWork = dive.nodeQueryWorkBudget() ?: dive.nodeWorkBudget()
    val model = original
    var sourceMetrics = LpSolveMetrics()
    var sourceReturned = false
    val attempt = solveRootNodeWithCrash(
        null,
        solve = {
            var basis: Basis? = null
            if (token()) return@solveRootNodeWithCrash null
            if (root) {
                if (parentWork != 0L && parentWork <= 1L) return@solveRootNodeWithCrash null
                val crash = rootCrashBasis(model, token, parentWork)
                dive.noteNodeOverhead(crash.metrics.workOps)
                basis = crash.basis
            }
            val previousMetrics = dive.propagator.lastMetrics
            var failure: Throwable? = null
            try {
                dive.solveNode(model, basis, token).also { sourceReturned = it != null }
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
            if (sourceReturned) LpSolveMetrics() else sourceMetrics
        },
    )
    return LpRootNodeResult(model, attempt?.second)
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
