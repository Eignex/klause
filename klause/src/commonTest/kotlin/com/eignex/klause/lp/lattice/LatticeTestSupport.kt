package com.eignex.klause.lp.lattice

import com.eignex.klause.util.bigIntOf

/** The rows of a small dense matrix as [SparseIntRow]s, for the exact-integer reductions. */
@Suppress("ArrayPrimitive")
internal fun sparseRows(vararg rows: LongArray): List<SparseIntRow> =
    rows.map { r -> sparseIntRow(r.indices.associateWith { bigIntOf(r[it]) }) }
