package com.eignex.klause.cli

import com.eignex.klause.portfolio.EngineMix

/**
 * The `-e` engine selection — the CLI owns this model. Each value is parsed from a user token
 * (its canonical [id] or an alias, case-insensitive) via [fromId] and routed to a solve strategy
 * in [SolveCore]. The single source of truth for the engine names that appear in `--help`,
 * `-e` parsing, and the routing `when`.
 */
internal enum class Engine(
    /** Canonical id, shown in `--help` and error messages. */
    val id: String,
    /** Whether this is a pure local-search engine — drops solution-altering presolve passes. */
    val pureLs: Boolean = false,
    /** Portfolio mix for the parallel-capable engines; `null` for the single naked engines. */
    val mix: EngineMix? = null,
    /** Accepted aliases besides [id]. */
    private val aliases: List<String> = emptyList(),
) {
    /** Single naked backtrack following the model's search annotation (the MiniZinc-Challenge FD track). */
    FIXED("fixed"),

    /** Backtrack-only portfolio (free search). */
    CP("cp", mix = EngineMix.BACKTRACK, aliases = listOf("backtrack", "bt")),

    /** Local-search-only portfolio. */
    LS(
        "ls",
        pureLs = true,
        mix = EngineMix.LOCAL_SEARCH,
        aliases = listOf("localsearch", "local-search", "ls-single", "lssingle"),
    ),

    /** Mixed backtrack + local-search portfolio. */
    MIXED("mixed", mix = EngineMix.MIXED, aliases = listOf("portfolio", "pf")),

    /** Single naked free backtrack — the only engine that takes var-/val-selector `--param`s. */
    CP_SINGLE("cp-single", aliases = listOf("cpsingle")),
    ;

    companion object {
        /** Built-in default for a bare invocation (no `-e`, no `-f`, no env override). */
        val DEFAULT = MIXED

        private val byToken: Map<String, Engine> =
            entries.flatMap { e -> (listOf(e.id) + e.aliases).map { it to e } }.toMap()

        /** Parse a user token (canonical id or alias, case-insensitive); `null` if unrecognised. */
        fun fromId(token: String): Engine? = byToken[token.trim().lowercase()]

        /** Canonical ids joined for `--help` / error messages: `fixed | cp | ls | mixed | …`. */
        fun ids(): String = entries.joinToString(" | ") { it.id }
    }
}
