package com.eignex.klause.ir

/** Immutable storage policy retained by domains through narrowing and restoration. */
data class DomainStorageSettings(
    /** Inclusive span threshold for bitset storage. */
    val bitsetThreshold: Int = 4096,
)

/** Engine-neutral resource policy carried through source rewrites and finite projections. */
data class ProblemSettings(
    /** Representation policy for finite integer domains. */
    val storage: DomainStorageSettings = defaultDomainStorageSettings,
    /** Span above which preparation attempts exact LP bounds before root propagation. */
    val largeSpanThreshold: Long = 1_000_000,
    /** Base relaxation-size budget for automatically admitted hulls. */
    val lpMaxTableauCells: Long = 1L shl 20,
    /** Absolute relaxation-size budget for automatic LP planning. */
    val lpCeilingTableauCells: Long = 1L shl 26,
)

internal val defaultDomainStorageSettings: DomainStorageSettings = DomainStorageSettings()
