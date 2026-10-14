package com.eignex.klause.theory

/** Stateless allocation identity for one receiving factor scope, retaining no controls or live theory owner. */
class EncodingNamespace {
    /** Name a private affine definition in this scope's reconstruction catalogue. */
    fun auxiliary(index: Int): EncodingAuxiliary {
        require(index >= 0)
        return EncodingAuxiliary(this, index)
    }

    /** Name a private Boolean definition in this scope's catalogue. */
    fun guard(index: Int): EncodingGuard {
        require(index >= 0)
        return EncodingGuard(this, index)
    }

    /** Attribute a coordinate allocated by the receiving theory to this factor scope. */
    fun realAuxiliary(coordinate: TheoryRealId): EncodingRealAuxiliary = EncodingRealAuxiliary(this, coordinate)

    /** Whether an affine name belongs to this receiving scope. */
    fun owns(auxiliary: EncodingAuxiliary): Boolean = auxiliary.owner === this

    /** Whether a Boolean name belongs to this receiving scope. */
    fun owns(guard: EncodingGuard): Boolean = guard.owner === this

    /** Whether a real coordinate belongs to this receiving scope. */
    fun owns(auxiliary: EncodingRealAuxiliary): Boolean = auxiliary.owner === this
}
