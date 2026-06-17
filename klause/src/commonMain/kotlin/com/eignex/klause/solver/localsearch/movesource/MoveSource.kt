package com.eignex.klause.solver.localsearch.movesource

import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.localsearch.MoveSink
import kotlin.jvm.JvmInline
import kotlin.random.Random

/**
 * A named, parameterised generator of candidate moves — the single place a given candidate
 * *kind* is produced. Today the same candidate (a violated-factor repair, a frontier step, a
 * structured feasibility move) is generated independently inside [com.eignex.klause.solver.localsearch.strategy.Cbls],
 * [com.eignex.klause.solver.localsearch.strategy.FocusedLs], and the minimize engine in
 * [com.eignex.klause.solver.localsearch.LocalSearchSolver]; a `MoveSource` is the unit those
 * duplicated loops collapse into (epic #710).
 *
 * Contract:
 *  - **Pure with respect to search state.** A source reads the assignment, domains, violated
 *    set, and factor degree through [MoveGenContext]; it does not mutate the state. Its only
 *    output is the moves it pushes into the supplied [MoveSink].
 *  - **Allocation-free on the hot path.** A source fills the caller-owned sink and reuses any
 *    private scratch it needs; a fill–clear cycle must touch no per-move objects (the sink's
 *    lane backing already guarantees this for primitives).
 *  - **Deterministic.** All randomness is drawn from [MoveGenContext.rng]; a source never reads
 *    a strategy-local RNG. Same state + same RNG draw sequence ⇒ same emitted multiset, which is
 *    what the move-set equivalence harness asserts when an old generator is replaced.
 *
 * A source carries no strategy back-reference: [phase] and [pool] are declarative properties the
 * driver consults to decide *when* (which feasibility phase) and *how* (noise-eligible vs
 * score-only) the source's moves participate, so those gates live once rather than being
 * re-checked inside each strategy.
 */
interface MoveSource {
    /** Stable identity, for catalog lookup and diagnostics. */
    val id: MoveSourceId

    /** Feasibility phase in which this source is meaningful. The driver consults a source only
     *  in its phase — replacing the per-generator `state.cost` re-checks with one declarative gate. */
    val phase: Phase

    /** Whether this source's moves may be taken by the random/noise draw, or only selected by
     *  score. The score-only sources (stall swaps, ejection chains, kicks) are coordinated
     *  multi-variable escapes that a noise draw turns into destructive perturbations; keeping the
     *  split a property of the source applies the rule uniformly in the driver. */
    val pool: Pool

    /** Push candidate moves for the current state into [sink]. */
    fun generate(ctx: MoveGenContext, sink: MoveSink)
}

/** Feasibility phase in which a [MoveSource] is meaningful. */
enum class Phase {
    /** Only while `state.cost > 0` (the infeasibility fight). */
    Infeasible,

    /** Only while `state.cost == 0` (objective descent over the feasible region). */
    Feasible,

    /** In either phase. */
    Any,
    ;

    /** Whether a source in this phase should be consulted at the given [cost]. */
    fun appliesAt(cost: Long): Boolean = when (this) {
        Infeasible -> cost > 0L
        Feasible -> cost == 0L
        Any -> true
    }
}

/** Whether a [MoveSource]'s moves are eligible for the random/noise draw or score-only. */
enum class Pool {
    /** May be selected by the random/noise draw as well as by score. */
    NoiseEligible,

    /** Selected by score only — never entered into the noise draw. */
    ScoreOnly,
}

/** Stable identifier for a [MoveSource]. A thin wrapper over a label so the catalog stays open
 *  (new arms — e.g. Feasibility-Jump, #698 — add their own ids without touching a closed enum)
 *  while remaining printable and comparable for diagnostics and equivalence reports. */
@JvmInline
value class MoveSourceId(
    /** Human-readable, unique catalog label (e.g. `"violated-repairs"`). */
    val label: String,
) {
    override fun toString(): String = label
}

/**
 * The read-only view a [MoveSource] generates against: the search [state] (assignment, domains,
 * violated set, factors, objective) plus the [stalled] flag the plateau-escape sources consult.
 * Bundling these means a source needs no strategy back-reference and draws all randomness from
 * one place ([rng]), which is what makes the equivalence harness's "same draws ⇒ same multiset"
 * guarantee hold.
 */
class MoveGenContext(
    val state: LocalSearchState,
    /** Whether the search is currently stalled (no strict cost drop for the strategy's stall
     *  window). Plateau-escape sources (frontier, swaps, chains, kicks) only emit when stalled. */
    val stalled: Boolean = false,
) {
    /** The threaded RNG. Every source draws here so runs are reproducible. */
    val rng: Random get() = state.rng
}

/**
 * A [MoveSource] paired with the per-strategy policy the driver applies to it: a per-pick
 * candidate [cap] (preserving the current O(arity·cap) budget) and an [enabled] gate. A strategy
 * is a list of these plus a scoring and an acceptance rule — it owns no generation loop.
 */
class ConfiguredSource(
    /** The generator this configuration wraps. */
    val source: MoveSource,
    /** Per-pick cap on candidates this source contributes; `Int.MAX_VALUE` = uncapped. */
    val cap: Int = Int.MAX_VALUE,
    /** Static enable gate (a disabled configured source is skipped without consulting [source]). */
    val enabled: Boolean = true,
) {
    init {
        require(cap >= 0) { "cap >= 0, got $cap" }
    }
}
