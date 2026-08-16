package com.eignex.klause.bench.tune

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.localsearch.strategy.LocalSearchRecipe
import kotlin.random.Random

/** A decoded engine config from [UnifiedConfigSpace] — a local-search recipe or backtrack params. The
 *  MIXED search routes evaluation by which arm it is. */
internal sealed interface EngineConfig {
    data class Ls(val recipe: LocalSearchRecipe) : EngineConfig
    data class Bt(val params: BacktrackParams) : EngineConfig
}

/**
 * The unified config space for the evidence-based MIXED search: a top-level `engine` ∈ {ls, bt}
 * over the whole [LocalSearchConfigSpace] and [BacktrackConfigSpace] cross-product. One residual-round
 * campaign over this space searches both engines together, so the mixed palette's LS/BT ratio *emerges*
 * from coverage rather than a hardcoded split — and the same run's cache projects to pure per-engine
 * orders (see [BoTuning.tuneMixed]).
 *
 * [params] lists `engine` then every LS and BT param **flat** (their names don't collide) — that's what
 * a flat-schema backend (Vizier) declares and suggests, and [decode] just reads `engine` and ignores the
 * other engine's keys. [sample] instead draws `engine` then only that sub-space's point (via the
 * sub-space's own gated `sample`), so [RandomTuner] and the exploration floor never emit cross-engine
 * noise. [coerce] over [params] types every present value for the sub-space decoders.
 */
internal object UnifiedConfigSpace : ConfigSpace(PARAMS) {

    /** Decode a coerced assignment into the engine's config, routing to the sub-space decoder. `bt`
     *  selects backtrack; anything else (default `ls`) local search. Extra other-engine keys are ignored
     *  by the chosen sub-decoder. */
    fun decode(a: Map<String, Any>): EngineConfig = when (a["engine"]) {
        "bt" -> EngineConfig.Bt(BacktrackConfigSpace.toParams(a))
        else -> EngineConfig.Ls(LocalSearchConfigSpace.toRecipe(a))
    }

    /** A random point pinned to [engine] — `engine` plus that sub-space's own (gated) draw. Used by the
     *  exploration floor to force the lagging engine. */
    fun samplePinned(engine: String, rng: Random): Map<String, Any> {
        val sub = if (engine == "bt") BacktrackConfigSpace.sample(rng) else LocalSearchConfigSpace.sample(rng)
        val a = LinkedHashMap<String, Any>()
        a["engine"] = engine
        a.putAll(sub)
        return a
    }

    override fun sample(rng: Random): Map<String, Any> = samplePinned(if (rng.nextBoolean()) "bt" else "ls", rng)

    /** This space restricted to [engines] (a non-empty subset of {ls, bt}): the `engine` categorical
     *  offers only those, so both tuner paths explore only them — [RandomTuner] draws only allowed
     *  engines and [VizierTuner] declares only them. [decode] and [samplePinned] are unchanged (they
     *  key on the `engine` value). Backs the `tune engines=` filter, e.g. a BT-only mixed sweep. */
    fun restricted(engines: Set<String>): ConfigSpace {
        require(engines.isNotEmpty() && engines.all { it == "ls" || it == "bt" }) {
            "engines must be a non-empty subset of {ls, bt}, got $engines"
        }
        val order = listOf("ls", "bt").filter { it in engines }
        val params = listOf(CategoricalParam("engine", order)) +
            LocalSearchConfigSpace.params + BacktrackConfigSpace.params
        return object : ConfigSpace(params) {
            override fun sample(rng: Random): Map<String, Any> = samplePinned(order.random(rng), rng)
        }
    }
}

/** `engine` first, then every LS and BT param flat (names are disjoint across the two sub-spaces). */
private val PARAMS: List<ConfigParam> =
    listOf(CategoricalParam("engine", listOf("ls", "bt"))) +
        LocalSearchConfigSpace.params +
        BacktrackConfigSpace.params
