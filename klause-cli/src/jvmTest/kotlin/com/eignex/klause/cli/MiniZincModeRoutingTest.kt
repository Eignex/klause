package com.eignex.klause.cli

import com.eignex.klause.ir.BoundOverride
import com.eignex.klause.ir.BoundsOverride
import com.eignex.klause.ir.SemanticOverrides
import com.eignex.klause.solver.result.SolveStats
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class MiniZincModeRoutingTest(private val engine: String, private val sourceText: String) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun sources(): List<Array<String>> = listOf(
            arrayOf("cp", "var int: n; var float: r; var float: s; constraint int2float(n,r); " +
                "constraint float_le(0.0,s); constraint float_le(s,1.0); " +
                "constraint float_lin_le([2.0,3.0],[r,s],4.0); " +
                "constraint float_lin_le([-3.0,-2.0],[r,s],0.0); solve satisfy;"),
            arrayOf("ls", "var 0.0..2.0: x; constraint float_le(x,1.0); solve satisfy;"),
        )
    }

    @Test
    fun `loading should retain actual routing telemetry`() {
        val source = File.createTempFile("flatzinc-routing", ".fzn").apply { writeText(sourceText) }
        try {
            val common = CommonOptions().apply {
                this.engine = this@MiniZincModeRoutingTest.engine
                semanticOverrides = SemanticOverrides(
                    undeclaredIntegers = BoundsOverride(BoundOverride.Open, BoundOverride.Open),
                )
            }

            val solvable = MiniZincMode.newSession().load(source.absolutePath, common)
            val pairs = cliInvocationStats(common, solvable.pipeline).pairs(SolveStats.EMPTY).toMap()

            assertNotNull(solvable.sourceInvocation)
            assertNotNull(pairs["frontendRoutingOutcome"])
            assertEquals(solvable.routingElapsedMs.toString(), pairs["frontendRoutingMs"])
            if (engine == "cp") assertTrue(solvable.routingLpStats.standalonePasses.sum > 0.0)
            else assertNotNull(solvable.sourceInvocation?.floatProjection)
        } finally {
            source.delete()
        }
    }
}
