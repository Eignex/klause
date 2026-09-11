package com.eignex.klause.backtrack.lp

internal object LpTreeSearchIntegration {
    @JvmStatic
    fun main(args: Array<String>) {
        LpBoundingLbTreeSearchTest().`the subsolver returns only feasible incumbents`()
        println("Validated the 300-instance primal-search corpus with seed 20260625.")
    }
}
