package com.eignex.klause.lp.engine

internal class LpPointAcceptanceResult(
    val accepted: Boolean,
    val work: Long = 0L,
    val stop: LpFloatingBoundStop? = null,
)

internal interface BudgetedLpPointAcceptance : (FloatLpResult) -> Boolean {
    fun accept(result: FloatLpResult, workLimit: Long): LpPointAcceptanceResult

    override fun invoke(result: FloatLpResult): Boolean = accept(result, 0L).accepted
}
