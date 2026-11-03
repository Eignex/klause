package com.eignex.klause.bench.profile

import com.eignex.klause.propagation.ClauseDbReasonOwnershipTest
import com.eignex.klause.propagation.GeneralIntegerReclamationTest
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

internal object RetirementFixtureProfile {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 2) { "Expected boolean-general|growing-integer and duration seconds" }
        val variant = args[0]
        val duration = args[1].toInt()
        require(duration in 1..180)
        val run: () -> Unit = when (variant) {
            "boolean-general" -> ClauseDbReasonOwnershipTest(20_000, false)::
                `Boolean retirement should progress within small grants at production sizes`
            "growing-integer" -> GeneralIntegerReclamationTest()::
                `growing integer retirements should release event ownership at an unchanged root`
            else -> error("Unknown retirement fixture: $variant")
        }
        val started = TimeSource.Monotonic.markNow()
        var ordinal = 0
        do {
            val invocation = TimeSource.Monotonic.markNow()
            run()
            val nanos = invocation.elapsedNow().inWholeNanoseconds
            println("{\"variant\":\"$variant\",\"ordinal\":$ordinal,\"elapsedNanos\":$nanos}")
            ordinal++
        } while (started.elapsedNow() < duration.seconds)
        println("{\"variant\":\"$variant\",\"completed\":true,\"invocations\":$ordinal}")
    }
}
