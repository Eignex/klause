package com.eignex.klause

import org.junit.BeforeClass

/** Initializes the shared runtime through inherited JUnit class setup. */
@Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
actual abstract class RuntimeInitializedTest protected actual constructor() {
    companion object {
        @BeforeClass
        @JvmStatic
        fun initializeSolverRuntime() = SolverTestRuntime.initialize()
    }
}
