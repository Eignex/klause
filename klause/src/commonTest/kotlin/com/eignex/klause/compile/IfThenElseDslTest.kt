package com.eignex.klause.compile

import com.eignex.klause.ast.eq
import com.eignex.klause.ast.ifThenElse
import com.eignex.klause.ast.le
import com.eignex.klause.schema.VariableSchema
import com.eignex.klause.solver.localsearch.FixedCadenceRestart
import com.eignex.klause.solver.localsearch.LocalSearchParams
import com.eignex.klause.solver.localsearch.LocalSearchSolver
import kotlin.test.Test
import kotlin.test.assertTrue

class IfThenElseDslTest {

    @Test
    fun `if then else dispatches by condition`() {
        class S : VariableSchema() {
            val flag by boolVar()
            val x by intVar(min = 0, max = 9)
            val y by intVar(min = 0, max = 9)
            val pin by constraint { ifThenElse(flag, x, y) eq 5 }
        }
        val schema = S()
        val compiled = schema.compile()
        val solver = LocalSearchSolver(
            compiled.problem,
            restartPolicy = FixedCadenceRestart(maxFlipsBeforeRestart = 500),
        )
        val samples = solver.enumerate(LocalSearchParams(maxFlips = 20_000, randomSeed = 42)).take(10).toList()
        assertTrue(samples.isNotEmpty())
        for (s in samples) {
            val flag = compiled.decode(schema.flag, s)
            val xv = compiled.decode(schema.x, s)
            val yv = compiled.decode(schema.y, s)
            val picked = if (flag) xv else yv
            assertTrue(picked == 5, "flag=$flag x=$xv y=$yv selected=$picked, expected 5")
        }
    }

    @Test
    fun `if then else inside arithmetic`() {
        class S : VariableSchema() {
            val flag by boolVar()
            val x by intVar(min = 0, max = 4)
            val y by intVar(min = 0, max = 4)

            val cap by constraint { ifThenElse(flag, x, y) le 2 }
        }
        val schema = S()
        val compiled = schema.compile()
        val solver = LocalSearchSolver(
            compiled.problem,
            restartPolicy = FixedCadenceRestart(maxFlipsBeforeRestart = 500),
        )
        val samples = solver.enumerate(LocalSearchParams(maxFlips = 20_000, randomSeed = 99)).take(10).toList()
        assertTrue(samples.isNotEmpty())
        for (s in samples) {
            val flag = compiled.decode(schema.flag, s)
            val xv = compiled.decode(schema.x, s)
            val yv = compiled.decode(schema.y, s)
            val picked = if (flag) xv else yv
            assertTrue(picked <= 2, "selected=$picked > 2")
        }
    }
}
