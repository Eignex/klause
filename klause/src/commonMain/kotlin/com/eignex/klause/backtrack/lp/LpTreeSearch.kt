package com.eignex.klause.backtrack.lp

import com.eignex.klause.lp.bounding.LpEngine
import com.eignex.klause.lp.bounding.LpFractionalBranch
import com.eignex.klause.lp.bounding.LpParams
import com.eignex.klause.lp.bounding.solveNode
import com.eignex.klause.lp.engine.ExactLpNumber
import com.eignex.klause.lp.engine.LpCertifier
import com.eignex.klause.lp.engine.LpVerdict
import com.eignex.klause.lp.engine.acceptNullable
import com.eignex.klause.lp.engine.certifiedTightObjectiveLowerBound
import com.eignex.klause.lp.engine.exactPointWitness
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
    val relaxer = lpRelaxer ?: return null
    // The heuristic has an independent source root; the optimizing caller keeps its own trail.
    val token = Cancellation { cancellation() || params.cancellation() }
    val diveParams = LpParams(
        params.lpPlan,
        params.lpConfig,
        token,
        params.solveBudgetMillis,
        params.randomSeed,
        params.zeroObjectivePricing,
    )
    val dive = LpEngine(problem, objective, diveParams, sink, solveContext)
    return dive.use {
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
                val attempt = dive.solveNode(
                    relaxation.model,
                    null,
                    token,
                ) ?: return SearchNodeDisposition.Expand
                observeRootSolve(
                    attempt.first,
                    if (dive.nodeUsesTrail) dive.propagator.lastMetrics else attempt.first.lastMetrics,
                )
                val result = attempt.second ?: return SearchNodeDisposition.Expand
                val lower = certifiedTightObjectiveLowerBound(
                    relaxation.model,
                    result.duals,
                    rootCertificationObserver(),
                    solveContext.certificationPolicy,
                )?.let { BigFraction.ofDouble(it) }?.plus(BigFraction.ofLong(relaxation.objectiveConstant))
                if (lower != null && bestExact?.let { lower >= it } == true) return SearchNodeDisposition.Prune
                val witness = solveContext.certificationPolicy.acceptNullable(
                    LpCertifier.EXACT_POINT,
                    exactPointWitness(relaxation.model, result.primal, rootCertificationObserver()),
                ) ?: return SearchNodeDisposition.Expand
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

private const val LB_TREE_BUDGET = 256L
