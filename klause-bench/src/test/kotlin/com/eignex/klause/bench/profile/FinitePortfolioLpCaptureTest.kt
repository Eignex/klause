package com.eignex.klause.bench.profile

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FinitePortfolioLpCaptureTest {
    @Test
    fun `query capture should retain a bounded exact model without source preparation`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(0, 2)),
            listOf(Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 1)))
        val relaxation = MpsLpAdmission.rootRelaxation(problem,
            LinearObjective(intCoefficients = longArrayOf(1)), false, Cancellation.Never)
        val model = relaxation.javaClass.getMethod("getModel").invoke(relaxation)
        val engine = "com.eignex.klause.lp.engine."
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
