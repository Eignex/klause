package com.eignex.klause.solver.pipeline

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.LocalSearchParams
import com.eignex.klause.localsearch.LocalSearchSolver
import com.eignex.klause.localsearch.strategy.ProbSat
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.search.SearchCandidateHints
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
internal class OpenBooleanSkeleton(
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
internal class OpenBooleanCandidate(
    /** Source Boolean ids this proposal covers, ascending. */
    val boolVars: IntArray,
    /** Proposed value of the id at the same index of [boolVars]. */
    val values: BooleanArray,
)

/** This proposal as unverified branch-order steering, the only thing a search may do with it. */
internal fun OpenBooleanCandidate.hints(): SearchCandidateHints =
    SearchCandidateHints.ofLiterals(IntArray(boolVars.size) { Lit.make(boolVars[it], values[it]) })

/**
 * A hint that draws its proposal only once [minSplits] splits have consulted it.
 *
 * The producer costs work before the traversal can use anything, and a model whose Boolean columns
 * propagation settles reaches almost no split at all — so drawing up front spends the allowance exactly
 * where no branch order could have paid for it. Deferring makes the search prove it is branching-bound
 * first: the shared branching consults a hint once per two-polarity Boolean split and only then, so the
 * consultations are the evidence. Nothing is refused for its size, and a search that never reaches the
 * threshold simply never pays.
 *
 * The first [minSplits] splits keep the default order, which is what a hint that had not been drawn yet
 * would have given them anyway.
 */
internal class DeferredCandidateHints(private val minSplits: Long, private val draw: () -> SearchCandidateHints) :
    SearchCandidateHints {

    private var splits = 0L
    private var drawn: SearchCandidateHints? = null

    override fun preferredBool(variable: Int): Boolean? {
        drawn?.let { return it.preferredBool(variable) }
        if (++splits < minSplits) return null
        val hints = draw()
        drawn = hints
        return hints.preferredBool(variable)
    }
}

/**
 * A hint that counts the splits it decided the first branch of.
 *
 * Drawing a proposal and steering a traversal are different things: propagation may fix every hinted
 * column before a split reaches it, so a hint covering half the model can still order nothing. The
 * shared branching consults a hint once per two-polarity Boolean split and only then, which is what
 * makes the consultation count the usage.
 */
internal class CountingCandidateHints(private val delegate: SearchCandidateHints) : SearchCandidateHints {

    /** Splits whose first branch this hint selected. */
    var steeredSplits: Long = 0L
        private set

    override fun preferredBool(variable: Int): Boolean? = delegate.preferredBool(variable)?.also { steeredSplits++ }
}

/**
 * What one draw from a plan's shared clauses produced, and the local-search work it spent.
 *
 * The cost is reported whether or not a proposal came out of it, since a draw that reached none is
 * overhead the traversal never gets back — exactly the part a measurement of the producer must see.
 */
internal class OpenBooleanDraw(
    /** The proposal, or null when the draw reached none. */
    val candidate: OpenBooleanCandidate?,
    /** Local-search moves the draw spent. */
    val moves: Long,
) {
    internal companion object {
        /** The draw of a plan with no shared clause to draw from, which spends nothing. */
        val NOTHING: OpenBooleanDraw = OpenBooleanDraw(null, 0)
    }
}

/** Bounded, deterministic settings for [ComponentPlan.openBooleanDraw]. */
internal data class OpenCandidateParams(
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
internal fun ComponentPlan.booleanSkeleton(
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
 * Draw one unverified Boolean proposal from this plan's shared clauses, proposing nothing when there is
 * nothing to propose — no shared clause, no assignment reached within [params], or a cancelled draw.
 *
 * The recipe is probSAT: the skeleton is a pure clause model, so a break-driven walk is the arm that fits
 * it, and a fixed seed under a fixed allowance makes the draw reproducible. Root propagation ruling the
 * skeleton out is one more way to have nothing to propose, not a refutation of [source] — the clauses are
 * only part of the model.
 */
internal fun ComponentPlan.openBooleanDraw(
    source: Problem,
    params: OpenCandidateParams = OpenCandidateParams(),
): OpenBooleanDraw {
    val skeleton = booleanSkeleton(source, params.cancellation) ?: return OpenBooleanDraw.NOTHING
    val drawn = LocalSearchSolver(skeleton.problem, strategy = ProbSat.adaptive()).solve(
        LocalSearchParams(
            maxFlips = params.maxFlips,
            randomSeed = params.randomSeed,
            cancellation = params.cancellation,
        ),
    )
    val moves = drawn.stats.ls.moves.sum.toLong()
    val sample = (drawn as? SolveResult.Sat)?.assignment ?: return OpenBooleanDraw(null, moves)
    val vars = skeleton.boolVars
    return OpenBooleanDraw(
        OpenBooleanCandidate(vars, BooleanArray(vars.size) { sample.bools[vars[it]] }),
        moves,
    )
}
