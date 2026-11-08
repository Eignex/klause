package com.eignex.klause.bench.profile

import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FinitePortfolioLpCaptureTest {
    @Test
    fun `query capture should retain a bounded exact model without source preparation`() {
        val engine = "com.eignex.klause.lp.engine."
        val builder = Class.forName(engine + "LpBuilder").getConstructor().newInstance()
        NumericalResidualProfile.call(builder, "addVar", 0L, 3L, 1L, -1)
        val sense = Class.forName(engine + "Sense").enumConstants.single { it.toString() == "MINIMIZE" }
        val legacy = assertNotNull(NumericalResidualProfile.call(builder, "build", sense))
        val authority = Class.forName(engine + "LegacyLpModelKt").methods.single {
            it.name == "authoritativeModel" && it.parameterCount == 1
        }.invoke(null, legacy)
        val trail = Class.forName(engine + "LpBoundTrail").constructors.single {
            it.parameterTypes.single().simpleName == "ExactLpModel"
        }.newInstance(authority)
        val state = assertNotNull(NumericalResidualProfile.call(trail, "getState"))
        val meter = Class.forName(engine + "LpProjectionMeter").getConstructor().newInstance()
        val model = assertNotNull(NumericalResidualProfile.call(state, "toWorkingModel", meter))
        val query = Class.forName(engine + "LpQueryObservation").constructors.single().newInstance(
            Any(), 1L, model, false, 32L,
            Class.forName(engine + "LpClaim").enumConstants.single { it.toString() == "ATTAINED_OPTIMUM" },
            0.0, false,
            Class.forName(engine + "LpRefinementLimits").getConstructor().newInstance(),
            Class.forName(engine + "LpPricingOptions").getConstructor().newInstance(),
            false, { null },
        )

        val bytes = assertNotNull(FinitePortfolioLpCapture.capture(query, Cancellation.Never))
        NumericalResidualProfile.call(query, "release")

        val companion = Class.forName(engine + "LpExactCapture").getField("Companion").get(null)
        val decoded = assertNotNull(NumericalResidualProfile.call(companion, "decode", bytes))
        assertTrue(bytes.size in 1..1_048_576)
        assertContentEquals(bytes, NumericalResidualProfile.call(decoded, "encode") as ByteArray)
    }
}
