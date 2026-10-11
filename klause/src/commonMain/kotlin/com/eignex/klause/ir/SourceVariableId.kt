package com.eignex.klause.ir

import kotlin.jvm.JvmInline

/** Boolean location in a source model; pair it with that model's ownership scope. */
@JvmInline
value class SourceBoolId(
    /** Position in the source Boolean namespace. */
    val index: Int,
) {
    init {
        require(index >= 0) { "source Boolean id must not be negative" }
    }
}

/** Integer location in a source model, distinct from a compact finite CP id. */
@JvmInline
value class SourceIntId(
    /** Position in the source integer namespace. */
    val index: Int,
) {
    init {
        require(index >= 0) { "source integer id must not be negative" }
    }
}

/** Continuous location in a source model, distinct from an LP column handle. */
@JvmInline
value class SourceRealId(
    /** Position in the source continuous namespace. */
    val index: Int,
) {
    init {
        require(index >= 0) { "source real id must not be negative" }
    }
}
