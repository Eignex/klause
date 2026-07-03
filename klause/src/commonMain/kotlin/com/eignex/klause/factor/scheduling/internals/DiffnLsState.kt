package com.eignex.klause.factor.scheduling.internals

/** LS-side payload for Diffn: count of overlapping rectangle pairs. */
class DiffnLsState(
    /** Number of rectangle pairs that currently overlap in at least one dimension. */
    var overlappingPairs: Int,
)
