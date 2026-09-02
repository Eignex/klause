package com.eignex.klause.solver.pipeline

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.LocalSearchParams
import com.eignex.klause.localsearch.LocalSearchSolver
import com.eignex.klause.localsearch.strategy.ProbSat
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.propagation.bake
import com.eignex.klause.util.Cancellation

/** Local-search work allowance spent on one candidate draw. */
private const val DEFAULT_CANDIDATE_FLIPS = 20_000L

/**
 * Boolean-only finite projection of the shared clauses a [ComponentPlan] selects.
 *
 * Factor ownership decides what enters, not the theory route: every factor the plan marks
 * [FactorOwner.SHARED] is a clause over source Boolean ids, so the projection keeps the source
 * [Problem.numBoolVars] and carries no integer or real column at all. Theory-owned columns stay
 * symbolic — nothing here materializes a domain for one, and no arithmetic reaches the search.
 */
class OpenBooleanSkeleton internal constructor(
    /** Finite Boolean-only problem holding exactly the plan's shared clauses. */
    val problem: BakedProblem,
    /** Source Boolean ids the shared clauses constrain, ascending. */
    val boolVars: IntArray,
)

/**
 * An unverified Boolean proposal drawn from an [OpenBooleanSkeleton].
 *
 * It satisfies the shared clauses and states nothing beyond them: the theory- and CP-owned halves of the
 * model were never consulted, so this is a starting point for a later search, never a verdict about the
 * model it came from.
 */
class OpenBooleanCandidate internal constructor(
    /** Source Boolean ids this proposal covers, ascending. */
    val boolVars: IntArray,
    /** Proposed value of the id at the same index of [boolVars]. */
    val values: BooleanArray,
)

/** Bounded, deterministic settings for [ComponentPlan.openBooleanCandidate]. */
data class OpenCandidateParams(
    /** Local-search work allowance; a zero allowance produces nothing. */
    val maxFlips: Long = DEFAULT_CANDIDATE_FLIPS,
    /** Seed of the search RNG, fixed so one skeleton and allowance always draw the same proposal. */
    val randomSeed: Long = 1L,
    /** Cooperative cancellation token, observed by the projection's bake and by the search. */
    val cancellation: Cancellation = Cancellation.Never,
)

/**
 * The shared clause skeleton of [source] under this plan, or null when the plan shares no clause.
 *
 * Boolean ids are the source ids: the projection reuses the source column count and remaps nothing, so a
 * proposal drawn from it names the same variables the model does.
 */
fun ComponentPlan.booleanSkeleton(
    source: Problem,
    cancellation: Cancellation = Cancellation.Never,
): OpenBooleanSkeleton? {
    if (source.numBoolVars == 0) return null
    val shared = source.factors.indices.filter {
        factorOwner(it) == FactorOwner.SHARED && source.factors[it] is Clause
    }
    if (shared.isEmpty()) return null
    val clauses = shared.map { source.factors[it] }
    val touched = BooleanArray(source.numBoolVars)
    for (clause in clauses) for (v in clause.variables.boolVars) touched[v] = true
    val skeleton = Problem(
        numBoolVars = source.numBoolVars,
        numIntVars = 0,
        intDomains = emptyArray(),
        factors = clauses,
        impliedFactorMask = source.impliedFactorMask?.let { mask ->
            BooleanArray(shared.size) { mask[shared[it]] }
        },
    )
    return OpenBooleanSkeleton(
        skeleton.bake(cancellation),
        IntArray(touched.count { it }).also { out ->
            var next = 0
            for (v in touched.indices) if (touched[v]) out[next++] = v
        },
    )
}

/**
 * Draw one unverified Boolean proposal from this plan's shared clauses, or null when there is nothing to
 * propose — no shared clause, no assignment reached within [params], or a cancelled draw.
 *
 * The recipe is probSAT: the skeleton is a pure clause model, so a break-driven walk is the arm that fits
 * it, and a fixed seed under a fixed allowance makes the draw reproducible. Root propagation ruling the
 * skeleton out is one more way to have nothing to propose, not a refutation of [source] — the clauses are
 * only part of the model.
 */
fun ComponentPlan.openBooleanCandidate(
    source: Problem,
    params: OpenCandidateParams = OpenCandidateParams(),
): OpenBooleanCandidate? {
    val skeleton = booleanSkeleton(source, params.cancellation) ?: return null
    val sample = LocalSearchSolver(skeleton.problem, strategy = ProbSat.adaptive())
        .samples(
            LocalSearchParams(
                maxFlips = params.maxFlips,
                randomSeed = params.randomSeed,
                cancellation = params.cancellation,
            ),
        )
        .firstOrNull() ?: return null
    val vars = skeleton.boolVars
    return OpenBooleanCandidate(vars, BooleanArray(vars.size) { sample.bools[vars[it]] })
}
