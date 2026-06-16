package com.eignex.klause.solver.backtrack

/**
 * Cost tier of an LP-relaxation technique — the LP analogue of
 * [com.eignex.klause.solver.presolve.PresolveTiming]. [LpEmphasis] enables a *set* of tiers, so the
 * emphasis level is "how expensive an LP technique may be" — a cost ceiling.
 */
enum class LpTiming {
    /** Cheap, no dense tableau: the combinatorial feasibility / bound checks. */
    FAST,

    /** One per-node dual-simplex solve: the base relaxation bounding + objective propagation. */
    MEDIUM,

    /** Expensive add-ons on top of the simplex: cut separation rounds, the convex-hull column
     *  models, the time-indexed reformulation, the rounding probe / fixpoint. */
    EXHAUSTIVE,
}

/**
 * The LP-relaxation techniques the selector ranges over — the LP analogue of
 * [com.eignex.klause.solver.presolve.PresolvePass]. Each declares a serializable [id] (for the
 * `--lp` spec list and `klause.lp`) and its cost [timing]. Whether a technique actually runs is
 * `override ?: (timing in emphasis.timings)` *and* its structural applicability (resolved in
 * [LpAutoConfig]); a technique can never run above the emphasis ceiling.
 *
 * @property id serializable token for the `--lp` spec list and the `klause.lp` property.
 * @property timing the cost tier gating which emphasis levels may run this technique.
 */
enum class LpTechnique(val id: String, val timing: LpTiming) {
    /** Cumulative / Disjunctive energetic over-subscription feasibility check. */
    ENERGETIC("energetic", LpTiming.FAST),

    /** Cumulative / Disjunctive preemptive max-flow feasibility bound (#454). */
    CUMULATIVE_FLOW("cumulative-flow", LpTiming.FAST),

    /** Subgradient Lagrangian bound for an AllDifferent (#23). */
    LAGRANGIAN("lagrangian", LpTiming.FAST),

    /** The per-node dual-simplex relaxation bound + objective propagation + Farkas learning +
     *  LP↔propagation fixpoint + rounding probe (the bounding stack), and the cumulative energetic
     *  makespan row (#430), which is one row in that relaxation. */
    BOUNDING("bounding", LpTiming.MEDIUM),

    /** Structural + Gomory/MIR cut separation rounds and the persistent root pool (#22). */
    CUTS("cuts", LpTiming.EXHAUSTIVE),

    /** Circuit / subcircuit arc-model + subtour-elimination cuts (#22 / #431). */
    CIRCUIT("circuit", LpTiming.EXHAUSTIVE),

    /** Constant-array Element convex-hull columns. */
    ELEMENT("element", LpTiming.EXHAUSTIVE),

    /** Table convex-hull columns. */
    TABLE("table", LpTiming.EXHAUSTIVE),

    /** NValue one-hot value-hull columns (#435). */
    NVALUE("nvalue", LpTiming.EXHAUSTIVE),

    /** Regular layer-expanded DFA flow-hull columns (#655). */
    REGULAR("regular", LpTiming.EXHAUSTIVE),

    /** Mdd layered flow-hull columns (#655). */
    MDD("mdd", LpTiming.EXHAUSTIVE),

    /** Cumulative / Disjunctive time-indexed `x_{i,t}` reformulation over a bounded horizon (#453). */
    CUMULATIVE_TIME_INDEXED("cumulative-time-indexed", LpTiming.EXHAUSTIVE),
    ;

    /** Token lookup and the id listing for spec parsing / help. */
    companion object {
        /** The technique with serializable [id], or null if none matches. */
        fun fromId(id: String?): LpTechnique? = entries.firstOrNull { it.id == id?.trim()?.lowercase() }

        /** Canonical ids joined for spec/help: `energetic | cumulative-flow | …`. */
        fun ids(): String = entries.joinToString(" | ") { it.id }
    }
}

/**
 * The LP emphasis level — an ordered cost ceiling, the LP analogue of
 * [com.eignex.klause.solver.presolve.PresolveEmphasis]. Each level enables the set of [LpTiming]
 * tiers up to its cost, so the order `OFF < CONSERVATIVE < DEFAULT < AGGRESSIVE` is a strict ceiling
 * the portfolio caps arms against.
 */
enum class LpEmphasis(
    /** Canonical token shown in `--lp` / `--help` and error messages. */
    val id: String,
    /** The [LpTiming] cost tiers this level permits — a technique runs only if its tier is in this
     *  set (absent an override). */
    val timings: Set<LpTiming>,
    /** Accepted spellings besides [id]. */
    private val aliases: List<String> = emptyList(),
) {
    /** No LP at all. */
    OFF("off", emptySet(), aliases = listOf("none")),

    /** Cheap combinatorial bounds only (energetic / flow / Lagrangian) — no per-node simplex. */
    CONSERVATIVE("conservative", setOf(LpTiming.FAST), aliases = listOf("fast")),

    /** + the per-node simplex bounding (relaxation bound, objective propagation, the cumulative
     *  makespan row). No cut rounds or hull models. The shipped single-config default. */
    DEFAULT("default", setOf(LpTiming.FAST, LpTiming.MEDIUM), aliases = listOf("auto")),

    /** + the expensive add-ons (cuts, hulls, time-indexed, probe / fixpoint). */
    AGGRESSIVE("aggressive", setOf(LpTiming.FAST, LpTiming.MEDIUM, LpTiming.EXHAUSTIVE)),
    ;

    /** Token lookup and the id listing for spec parsing / help (single source of truth). */
    companion object {
        private val byToken: Map<String, LpEmphasis> =
            entries.flatMap { e -> (listOf(e.id) + e.aliases).map { it to e } }.toMap()

        /** `null`/blank/`default`/`auto` → [DEFAULT]; `off`/`none` → [OFF]; `conservative`/`fast` →
         *  [CONSERVATIVE]; `aggressive` → [AGGRESSIVE]; otherwise `null`. */
        fun fromId(id: String?): LpEmphasis? {
            val token = id?.trim()?.lowercase()
            return if (token.isNullOrEmpty()) DEFAULT else byToken[token]
        }

        /** Canonical ids joined for `--help` / error messages: `off | conservative | default | aggressive`. */
        fun ids(): String = entries.joinToString(" | ") { it.id }
    }
}

/**
 * An LP configuration: an [emphasis] cost ceiling plus per-technique [overrides] (force a single
 * technique on or off regardless of the level — the benchmarking toggle #429 drives). The LP analogue
 * of [com.eignex.klause.solver.presolve.PresolveConfig]; [resolved] answers whether a technique is
 * *permitted* by the config (an override wins, else the emphasis tier rule), and [LpAutoConfig.resolve]
 * combines that with structural applicability and the dense-tableau size guard.
 *
 * @property emphasis the cost ceiling — the highest [LpTiming] tier any technique may run at.
 * @property overrides per-technique force-on/off that wins over the emphasis (the benchmarking toggle).
 */
class LpConfig(val emphasis: LpEmphasis = LpEmphasis.DEFAULT, val overrides: Map<LpTechnique, Boolean> = emptyMap()) {

    /** Whether [technique] is permitted: an explicit override wins, else its tier ≤ the emphasis. */
    fun resolved(technique: LpTechnique): Boolean = overrides[technique] ?: (technique.timing in emphasis.timings)

    /** This config capped under [ceiling] for a portfolio arm: the lower emphasis, with the ceiling's
     *  per-technique overrides applied on top (so a `--lp aggressive,-cuts` ceiling forces cuts off on
     *  every arm, and a `--lp off,+energetic` ceiling forces just that one on). The ceiling's overrides
     *  win over the arm's own; an all-`AGGRESSIVE`, no-override ceiling leaves the arm unchanged. */
    fun cappedUnder(ceiling: LpConfig): LpConfig {
        val capped = if (emphasis.ordinal <= ceiling.emphasis.ordinal) emphasis else ceiling.emphasis
        return LpConfig(capped, overrides + ceiling.overrides)
    }

    /** Predefined configs and the spec-string [parse]r. */
    companion object {
        /** The shipped default — [LpEmphasis.DEFAULT], no overrides. */
        val AUTO = LpConfig(LpEmphasis.DEFAULT)

        /** No LP at all. */
        val OFF = LpConfig(LpEmphasis.OFF)

        /** Every technique permitted — the AGGRESSIVE ceiling, no overrides. */
        val AGGRESSIVE = LpConfig(LpEmphasis.AGGRESSIVE)

        /**
         * Parse a `--lp` / `klause.lp` spec into an [emphasis] + per-technique [overrides]:
         *  - an emphasis level alone (`default`/`auto`, `off`/`none`, `conservative`/`fast`,
         *    `aggressive`) — e.g. `aggressive`;
         *  - an emphasis level followed by `+<technique>` / `-<technique>` deltas that force individual
         *    techniques on/off on top of it — e.g. `aggressive,-cuts` (full LP but no cut rounds),
         *    `off,+cumulative-flow` (no LP except the flow prune). This is what enables/disables a
         *    single technique, which a bare list cannot;
         *  - `all` — every technique forced on;
         *  - a bare comma-separated technique-id list — each forced on, all others off (force-exactly).
         *
         * An unknown token throws.
         */
        fun parse(spec: String?): LpConfig {
            val s = spec?.trim()?.lowercase().orEmpty()
            if (s.isEmpty()) return AUTO
            if (s == "all") return LpConfig(LpEmphasis.AGGRESSIVE, LpTechnique.entries.associateWith { true })
            val tokens = s.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            // Emphasis-base form: first token is a level, the rest are +/- deltas.
            LpEmphasis.fromId(tokens.first())?.let { base ->
                val overrides = HashMap<LpTechnique, Boolean>()
                for (tok in tokens.drop(1)) {
                    val on = when (tok.firstOrNull()) {
                        '+' -> true
                        '-' -> false
                        else -> error("after an emphasis, `$tok` must be +<technique> or -<technique>")
                    }
                    overrides[techniqueOf(tok.substring(1))] = on
                }
                return LpConfig(base, overrides)
            }
            // Bare force-list: the named techniques on, all others off.
            val on = tokens.map { techniqueOf(it) }.toSet()
            return LpConfig(LpEmphasis.AGGRESSIVE, LpTechnique.entries.associateWith { it in on })
        }

        private fun techniqueOf(id: String): LpTechnique =
            LpTechnique.fromId(id) ?: error("unknown LP technique `$id`; expected ${LpTechnique.ids()}")
    }
}
