package com.eignex.klause.theory

import com.eignex.klause.ir.SourceBoolId
import com.eignex.klause.ir.SourceIntId

/** Immutable diagnosis of a declined encoding scope; it carries no refutation authority. */
class EncoderFailure internal constructor(
    /** Consumer language that declined the relation. */
    val target: EncodingTarget,
    /** Original preparation or installation outcome. */
    val cause: EncoderDecline,
    /** Canonical source factor, absent for a declaration-wide failure or containing stop. */
    val factorIndex: Int? = null,
    missingIntegers: List<SourceIntId> = emptyList(),
    missingBooleans: Map<SourceBoolId, Boolean> = emptyMap(),
) {
    private val integers = missingIntegers.toList()
    private val booleans = missingBooleans.toMap()

    /** Required source integers without an admitted fixed value or complete finite cover. */
    val missingIntegers: List<SourceIntId> get() = integers.toList()

    /** Required Boolean pins absent from, or contradicted by, the captured invocation. */
    val missingBooleans: Map<SourceBoolId, Boolean> get() = booleans.toMap()
}
