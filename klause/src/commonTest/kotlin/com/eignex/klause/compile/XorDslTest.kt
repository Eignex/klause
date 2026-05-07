package com.eignex.klause.compile

import com.eignex.klause.solver.FixedCadenceRestart
import com.eignex.klause.solver.LocalSearchParams
import com.eignex.klause.ast.implies
import com.eignex.klause.ast.not
import com.eignex.klause.ast.xor
import com.eignex.klause.schema.VariableSchema
import com.eignex.klause.solver.LocalSearchSolver
import com.eignex.klause.solver.factor.Xor
import kotlin.test.Test
import kotlin.test.assertTrue

class XorDslTest {

    @Test
    fun xorEmitsOddParityFactor() {
        class S : VariableSchema() {
            val a by boolVar()
            val b by boolVar()
            val c by boolVar()
            val parity by constraint { xor(a, b, c) }
        }
        val compiled = S().compile()
        val xf = compiled.problem.factors.single { it is Xor } as Xor
        assertTrue(xf.targetParity == 1)
    }

    @Test
    fun xorOddParityHoldsInSamples() {
        class S : VariableSchema() {
            val a by boolVar()
            val b by boolVar()
            val c by boolVar()
            val odd by constraint { xor(a, b, c) }
        }
        val schema = S()
        val compiled = schema.compile()
        val solver = LocalSearchSolver(compiled.problem, restartPolicy = FixedCadenceRestart(maxFlipsBeforeRestart = 200))
        val samples = solver.enumerate(LocalSearchParams(maxFlips = 5_000, randomSeed = 7)).take(10).toList()
        assertTrue(samples.isNotEmpty())
        for (s in samples) {
            val count = listOf(schema.a, schema.b, schema.c).count { compiled.decode(it, s) }
            assertTrue(count % 2 == 1, "count=$count")
        }
    }

    @Test
    fun negatedXorEnforcesEvenParity() {
        class S : VariableSchema() {
            val a by boolVar()
            val b by boolVar()
            val c by boolVar()
            val even by constraint { !xor(a, b, c) }
        }
        val schema = S()
        val compiled = schema.compile()
        val solver = LocalSearchSolver(compiled.problem, restartPolicy = FixedCadenceRestart(maxFlipsBeforeRestart = 200))
        val samples = solver.enumerate(LocalSearchParams(maxFlips = 5_000, randomSeed = 19)).take(10).toList()
        assertTrue(samples.isNotEmpty())
        for (s in samples) {
            val count = listOf(schema.a, schema.b, schema.c).count { compiled.decode(it, s) }
            assertTrue(count % 2 == 0, "count=$count")
        }
    }

    @Test
    fun xorReifiedUnderImplies() {
        class S : VariableSchema() {
            val flag by boolVar()
            val a by boolVar()
            val b by boolVar()
            val c by boolVar()
            val rule by constraint { flag implies xor(a, b, c) }
        }
        val schema = S()
        val compiled = schema.compile()
        val solver = LocalSearchSolver(compiled.problem, restartPolicy = FixedCadenceRestart(maxFlipsBeforeRestart = 200))
        val samples = solver.enumerate(LocalSearchParams(maxFlips = 5_000, randomSeed = 23)).take(20).toList()
        for (s in samples) {
            val flag = compiled.decode(schema.flag, s)
            if (!flag) continue
            val count = listOf(schema.a, schema.b, schema.c).count { compiled.decode(it, s) }
            assertTrue(count % 2 == 1, "flag set, count=$count")
        }
    }
}
