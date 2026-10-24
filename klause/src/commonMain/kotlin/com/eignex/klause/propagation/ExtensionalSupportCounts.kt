package com.eignex.klause.propagation

internal data class ExtensionalSupportCounts(val supporting: IntArray, val total: Int)

internal interface ExtensionalSupportProvider {
    fun supportCounts(
        state: PropagationState,
        factorId: Int,
        variable: Int,
        values: LongArray,
        visit: () -> Boolean,
    ): ExtensionalSupportCounts?
}
