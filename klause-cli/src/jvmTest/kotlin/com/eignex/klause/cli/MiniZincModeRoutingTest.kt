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
            arrayOf("cp", "var int: n; var int: m; var float: r; constraint int2float(n,r); " +
                "constraint int_lin_le([2,3],[n,m],4); " +
                "constraint int_lin_le([-3,-2],[n,m],0); " +
                "constraint int_lin_le([1,-1],[n,m],1); solve satisfy;"),
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
            val pairs = cliInvocationStats(common, solvable.rendering).pairs(SolveStats.EMPTY).toMap()

            assertNotNull(solvable.sourceInvocation)
            assertNotNull(pairs["frontendRoutingOutcome"])
            assertEquals(solvable.routingElapsedMs.toString(), pairs["frontendRoutingMs"])
            if (engine == "cp") {
                val stats = solvable.routingLpStats
                assertTrue(
                    stats.standalonePasses.sum + stats.componentPasses.sum > 0.0,
                    "No routing LP work: standalone=${stats.standalonePasses.sum}, " +
                        "component=${stats.componentPasses.sum}",
                )
            } else assertNotNull(solvable.sourceInvocation.floatProjection)
        } finally {
            source.delete()
        }
    }
}
