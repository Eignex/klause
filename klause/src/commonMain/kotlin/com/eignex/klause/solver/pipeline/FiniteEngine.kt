package com.eignex.klause.solver.pipeline

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
) {
    /** A single backtrack solver following the model's search annotation. */
    FIXED("fixed"),

    /** A portfolio containing only complete backtrack solvers. */
    BACKTRACK("backtrack"),

    /** A portfolio containing only local-search solvers. */
    LOCAL_SEARCH(
        "localsearch",
        pureLocalSearch = true,
    ),

    /** A portfolio combining backtrack and local-search solvers. */
    MIXED("mixed"),

    /**
     * A hybrid adaptive large-neighbourhood portfolio with CP repair (Ropke and Pisinger, Transportation
     * Science 40(4), 2006, https://doi.org/10.1287/trsc.1050.0135).
     */
    ALNS("alns"),
    ;

    /** Defaults shared by finite-engine route consumers. */
    companion object {
        /** The route used when a frontend provides no explicit choice. */
        val DEFAULT = MIXED
    }
}
