package com.eignex.klause.solver.search

/** Retention rule for the shared learned-clause database. */
enum class SearchLearnedDbPolicy(
    /** Identifier accepted by the open-theory CLI. */
    val id: String,
) {
    /** Protect glue and recently propagating clauses, then retain the lowest LBD. */
    LbdUse("lbd-use"),

    /** Protect binary and locked clauses, then retain the highest decaying conflict activity. */
    Activity("activity"),

    /** Add glue protection to [Activity]. */
    GlueActivity("glue-activity"),

    /** Protect glue and recently propagating clauses, then rank remaining clauses by activity. */
    UsedActivity("used-activity"),

    /** Protect glue and recently propagating clauses, ranking by LBD and then activity. */
    LbdActivity("lbd-activity"),

    /** Improve LBD during analysis; protect glue and a recently analyzed middle tier, ranking locals by activity. */
    Tiered("tiered"),
    ;

    /** Resolve an open-theory CLI identifier. */
    companion object {
        /** Policy named [id], or null for an unknown identifier. */
        fun of(id: String): SearchLearnedDbPolicy? = entries.firstOrNull { it.id == id }
    }
}
