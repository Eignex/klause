package com.eignex.klause.formats.smtlib

internal fun smtArraySelect(array: SmtValue.Array, index: SmtValue, values: SmtModelValues): SmtValue? {
    for ((key, value) in array.entries.asReversed()) {
        values.checkpoint()
        when (values.equal(index, key)) {
            true -> return value
            false -> Unit
            null -> return null
        }
    }
    return array.default
}

internal fun smtArraysEqual(left: SmtValue.Array, right: SmtValue.Array, values: SmtModelValues): Boolean? {
    values.checkpoint()
    if (left.sort != right.sort) return false
    val indexes = ArrayList<SmtValue>()
    var unavailable = false
    for ((index, _) in left.entries + right.entries) {
        values.checkpoint()
        val a = smtArraySelect(left, index, values)
        val b = smtArraySelect(right, index, values)
        when (if (a == null || b == null) null else values.equal(a, b)) {
            false -> return false
            null -> unavailable = true
            true -> Unit
        }
        var present = false
        for (previous in indexes) {
            values.checkpoint()
            when (values.equal(previous, index)) {
                true -> {
                    present = true
                    break
                }
                false -> Unit
                null -> unavailable = true
            }
        }
        if (!present) indexes += index
    }
    val covered = arrayIndexCardinality(left.sort.index, indexes.size + 1, values)?.let { it <= indexes.size } == true
    if (covered) return if (unavailable) null else true
    return when (values.equal(left.default, right.default)) {
        true -> if (unavailable) null else true
        false -> if (unavailable || arrayIndexCardinality(left.sort.index, indexes.size + 1, values) == null) null
        else false
        null -> null
    }
}

private fun arrayIndexCardinality(sort: SmtSort, cap: Int, values: SmtModelValues): Int? {
    values.checkpoint()
    return when (sort) {
        SmtSort.Bool -> minOf(2, cap)
        SmtSort.Int, SmtSort.Real -> cap
        is SmtSort.BitVector -> if (sort.width >= 31) cap else minOf(1 shl sort.width, cap)
        is SmtSort.Uninterpreted -> null
        is SmtSort.Array -> {
            val element = arrayIndexCardinality(sort.element, cap, values) ?: return null
            if (element == 1) return 1
            val count = arrayIndexCardinality(sort.index, cap, values) ?: return null
            var result = 1L
            repeat(count) {
                values.checkpoint()
                result *= element
                if (result >= cap) return cap
            }
            result.toInt()
        }
    }
}
