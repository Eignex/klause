package com.eignex.klause.propagation

import kotlin.jvm.JvmInline

/** Integer location in one finite CP projection; convert through its source mapping. */
@JvmInline
value class CpIntId(val index: Int) {
    init {
        require(index >= 0) { "CP integer id must not be negative" }
    }
}
