package com.eignex.klause.lp.lattice

import com.ionspin.kotlin.bignum.integer.BigInteger

/** The rows of a small dense matrix as [SparseIntRow]s, for the exact-integer reductions. */
@Suppress("ArrayPrimitive")
internal fun sparseRows(vararg rows: LongArray): List<SparseIntRow> =
    rows.map { r -> sparseIntRow(r.indices.associateWith { BigInteger.fromLong(r[it]) }) }
