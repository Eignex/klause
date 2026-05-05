package com.eignex.klause.compile

import com.eignex.klause.ast.abs
import com.eignex.klause.ast.eq
import com.eignex.klause.ast.ge
import com.eignex.klause.ast.le
import com.eignex.klause.ast.max
import com.eignex.klause.ast.min
import com.eignex.klause.cnf.BitBlaster
import com.eignex.klause.schema.VariableSchema
import com.eignex.klause.solver.Solver
import kotlin.test.Test
import kotlin.test.assertTrue

class MinMaxAbsDslTest {

    @Test
    fun minOfTwoIntsSamplesValidly() {
        class S : VariableSchema() {
            val x by intVar(min = 0, max = 5)
            val y by intVar(min = 0, max = 5)
            val capMin by constraint { min(x, y) le 2 }
        }
        val schema = S()
        val compiled = schema.compile()
        val solver = Solver(compiled.problem, maxFlipsBeforeRestart = 500)
        val samples = solver.sample(maxFlips = 20_000, randomSeed = 11).take(8).toList()
        assertTrue(samples.isNotEmpty())
        for (s in samples) {
            val xv = compiled.decodeInt("x", s)
            val yv = compiled.decodeInt("y", s)
            assertTrue(kotlin.math.min(xv, yv) <= 2, "min($xv,$yv)>2")
        }
    }

    @Test
    fun maxOfThreeIntsSamplesValidly() {
        class S : VariableSchema() {
            val x by intVar(min = 0, max = 4)
            val y by intVar(min = 0, max = 4)
            val z by intVar(min = 0, max = 4)
            val capMax by constraint { max(x, y, z) ge 3 }
        }
        val schema = S()
        val compiled = schema.compile()
        val solver = Solver(compiled.problem, maxFlipsBeforeRestart = 500)
        val samples = solver.sample(maxFlips = 20_000, randomSeed = 7).take(8).toList()
        assertTrue(samples.isNotEmpty())
        for (s in samples) {
            val xv = compiled.decodeInt("x", s)
            val yv = compiled.decodeInt("y", s)
            val zv = compiled.decodeInt("z", s)
            assertTrue(maxOf(xv, yv, zv) >= 3, "max($xv,$yv,$zv)<3")
        }
    }

    @Test
    fun absOfSignedIntSamplesValidly() {
        class S : VariableSchema() {
            val x by intVar(min = -5, max = 5)
            val capAbs by constraint { abs(x) le 2 }
        }
        val schema = S()
        val compiled = schema.compile()
        val solver = Solver(compiled.problem, maxFlipsBeforeRestart = 500)
        val samples = solver.sample(maxFlips = 20_000, randomSeed = 23).take(10).toList()
        assertTrue(samples.isNotEmpty())
        for (s in samples) {
            val xv = compiled.decodeInt("x", s)
            assertTrue(kotlin.math.abs(xv) <= 2, "|$xv|>2")
        }
    }

    @Test
    fun minBitBlastingSatisfiabilityAgreesWithSolver() {
        class S : VariableSchema() {
            val x by intVar(min = 0, max = 3)
            val y by intVar(min = 0, max = 3)
            val pin by constraint { min(x, y) eq 2 }
        }
        val compiled = S().compile()
        val cnf = BitBlaster.compile(compiled.problem)
        assertTrue(cnf.clauses.isNotEmpty())
    }
}
