package com.eignex.klause.solver.pipeline

import com.eignex.klause.portfolio.EngineMix

/**
 * A finite-domain solve route selected by the orchestration layer.
 *
 * Frontends translate their input into one of these routes; they do not select concrete solver
 * implementations themselves.
 */
enum class FiniteEngine(
    /** Stable route id for configuration and frontend presentation. */
    val id: String,
    /** Whether solution-set-altering presolve passes should be omitted for this route. */
    val pureLocalSearch: Boolean = false,
    /** Portfolio composition for routes that execute through the portfolio. */
    val portfolioMix: EngineMix? = null,
) {
    /** A single backtrack solver following the model's search annotation. */
    FIXED("fixed"),

    /** A portfolio containing only complete backtrack solvers. */
    BACKTRACK("backtrack", portfolioMix = EngineMix.BACKTRACK),

    /** A portfolio containing only local-search solvers. */
    LOCAL_SEARCH(
        "localsearch",
        pureLocalSearch = true,
        portfolioMix = EngineMix.LOCAL_SEARCH,
    ),

    /** A portfolio combining backtrack and local-search solvers. */
    MIXED("mixed", portfolioMix = EngineMix.MIXED),

    /** A hybrid large-neighbourhood portfolio with CP repair. */
    ALNS("alns", portfolioMix = EngineMix.ALNS),
    ;

    /** Defaults shared by finite-engine route consumers. */
    companion object {
        /** The route used when a frontend provides no explicit choice. */
        val DEFAULT = MIXED
    }
}
