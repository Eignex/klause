package com.eignex.klause.lp.lattice

import com.eignex.klause.util.bigIntOf

@Suppress("ArrayPrimitive")
internal fun sparseRows(vararg rows: LongArray): List<SparseIntRow> =
    rows.map { r -> sparseIntRow(r.indices.associateWith { bigIntOf(r[it]) }) }
