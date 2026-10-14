package com.eignex.klause.theory

import com.eignex.klause.ir.SourceBoolId
import com.eignex.klause.ir.SourceIntId
import com.eignex.klause.ir.SourceRealId
import com.eignex.klause.util.BigInt
import kotlin.jvm.JvmInline

/** A real coordinate in a private theory projection, distinct from a source declaration. */
@JvmInline
value class TheoryRealId(
    /** Position in the combined theory's real namespace. */
    val index: Int,
) {
    init { require(index >= 0) }
}

/** Immutable source correspondence retained with one installed whole-factor relation. */
class EncodingAttribution internal constructor(
    /** Original factor index in the canonical preparation. */
    val sourceFactorIndex: Int,
    booleans: List<SourceBoolId>,
    integers: List<SourceIntId>,
    reals: List<SourceRealId>,
    integerEmbeddings: Map<SourceIntId, TheoryRealId> = emptyMap(),
    realEmbeddings: Map<SourceRealId, TheoryRealId> = emptyMap(),
    auxiliaries: List<TheoryRealId> = emptyList(),
    integerConditions: Map<SourceIntId, BigInt> = emptyMap(),
    booleanConditions: Map<SourceBoolId, Boolean> = emptyMap(),
) {
    private val boolIds = booleans.toList()
    private val intIds = integers.toList()
    private val realIds = reals.toList()
    private val intMap = integerEmbeddings.toMap()
    private val realMap = realEmbeddings.toMap()
    private val auxiliaryIds = auxiliaries.toList()
    private val intPins = integerConditions.toMap()
    private val boolPins = booleanConditions.toMap()

    /** Source Boolean coordinates; linear targets retain their identity. */
    val booleans: List<SourceBoolId> get() = boolIds.toList()
    /** Source integer coordinates; linear targets retain their identity. */
    val integers: List<SourceIntId> get() = intIds.toList()
    /** Source real coordinates; linear targets retain their identity. */
    val reals: List<SourceRealId> get() = realIds.toList()
    /** Finite integer embeddings used only under their source conditions. */
    val integerEmbeddings: Map<SourceIntId, TheoryRealId> get() = intMap.toMap()
    /** Original real coordinates represented in the private theory namespace. */
    val realEmbeddings: Map<SourceRealId, TheoryRealId> get() = realMap.toMap()
    /** Private defining coordinates omitted from source witness publication. */
    val auxiliaries: List<TheoryRealId> get() = auxiliaryIds.toList()
    /** Exact source integer pins supporting a conditioned relation. */
    val integerConditions: Map<SourceIntId, BigInt> get() = intPins.toMap()
    /** Source Boolean pins supporting a conditioned relation. */
    val booleanConditions: Map<SourceBoolId, Boolean> get() = boolPins.toMap()
}
