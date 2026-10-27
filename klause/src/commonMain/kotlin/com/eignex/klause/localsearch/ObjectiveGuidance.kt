package com.eignex.klause.localsearch

/** Objective scoring and proposal policy for a local-search minimization. */
enum class ObjectiveGuidance {
    /** Use the prepared functional gradient when per-move invariants are available. */
    Functional,

    /** Score and propose through the canonical linear objective. */
    Canonical,

    /** Score canonically and propose bounded geometric steps through maintained objective leaves. */
    Geometric,
}
