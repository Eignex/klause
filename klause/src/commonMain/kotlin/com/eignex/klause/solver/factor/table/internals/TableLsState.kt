package com.eignex.klause.solver.factor.table.internals

/** Per-worker LS state for the Table constraint: per-tuple Hamming distances and their minimum. */
internal class TableLsState(val dist: IntArray, var minDist: Int)
