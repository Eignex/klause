package com.eignex.klause

import org.junit.BeforeClass

@Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
actual abstract class RuntimeInitializedTest actual constructor() {
    companion object {
        @BeforeClass
        @JvmStatic
        fun initializeSolverRuntime() = SolverTestRuntime.initialize()
    }
}
