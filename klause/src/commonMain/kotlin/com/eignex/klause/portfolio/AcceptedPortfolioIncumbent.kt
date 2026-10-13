package com.eignex.klause.portfolio

import com.eignex.klause.solver.Sample

internal data class AcceptedPortfolioIncumbent<V>(
    val version: Long,
    val assignment: Sample,
    val objective: V,
)
