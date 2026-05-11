package com.eignex.klause.solver.strategy

import com.eignex.klause.solver.FixedCadenceRestart
import com.eignex.klause.solver.LocalSearchParams
import com.eignex.klause.ast.atLeast
import com.eignex.klause.ast.atMost
import com.eignex.klause.compile.compile
import com.eignex.klause.schema.VariableSchema
import com.eignex.klause.solver.LocalSearchSolver
import kotlin.test.Test
import kotlin.test.assertTrue

class ProbSatTest {

    @Test
    fun `prob sat finds samples on small sat`() {
        // 5 booleans, mix of at-most-3 and at-least-2 factors. Both strategies should converge.
        class S : VariableSchema() {
            val a by boolVar()
            val b by boolVar()
            val c by boolVar()
            val d by boolVar()
            val e by boolVar()
            val cap by constraint { atMost(3, a, b, c, d, e) }
            val req by constraint { atLeast(2, a, b, c, d, e) }
        }
        val schema = S()
        val compiled = schema.compile()
        val solver = LocalSearchSolver(compiled.problem, strategy = ProbSat(), restartPolicy = FixedCadenceRestart(maxFlipsBeforeRestart = 200))
        val samples = solver.enumerate(LocalSearchParams(maxFlips = 5_000, randomSeed = 23)).take(10).toList()
        assertTrue(samples.isNotEmpty(), "ProbSat produced no samples on a satisfiable schema")
        for (s in samples) {
            val count = listOf(schema.a, schema.b, schema.c, schema.d, schema.e).count { compiled.decode(it, s) }
            assertTrue(count in 2..3, "count=$count violates 2..3")
        }
    }
}
