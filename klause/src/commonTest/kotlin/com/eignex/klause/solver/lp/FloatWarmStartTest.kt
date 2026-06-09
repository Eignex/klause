package com.eignex.klause.solver.lp

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** #18: float fast-path — the exact solver certified from a double-precision candidate basis. */
class FloatWarmStartTest {

    @Test
    fun `exact solve certified from a float basis matches the cold optimum`() {
        // max 3x + 2y s.t. x+y<=4, x+3y<=6, x,y in [0,10] -> optimum 12 at (4,0).
        val b = LpBuilder()
        val x = b.addVar(0, 10, cost = 3)
        val y = b.addVar(0, 10, cost = 2)
        b.addRow(mapOf(x to 1L, y to 1L), Relation.LE, 4)
        b.addRow(mapOf(x to 1L, y to 3L), Relation.LE, 6)
        val model = b.build(Sense.MAXIMIZE)

        val floatBasis = FloatSimplex(model).basis()
        assertTrue(floatBasis != null, "float solve should produce a basis")
        val warm = DualSimplex(model).solve(floatBasis)
        assertEquals(LpStatus.OPTIMAL, warm.status)
        assertEquals(12.0, warm.objectiveValue, 1e-9)
    }

    @Test
    fun `float-warm exact solve equals cold exact solve on random LPs`() {
        val rng = Random(20260609)
        var matched = 0
        repeat(500) { _ ->
            val n = rng.nextInt(2, 5)
            val b = LpBuilder()
            repeat(n) { b.addVar(0, rng.nextInt(1, 8).toLong(), cost = rng.nextInt(-5, 6).toLong()) }
            repeat(rng.nextInt(1, 5)) { _ ->
                val coeffs = HashMap<Int, Long>()
                for (k in 0 until n) if (rng.nextBoolean()) coeffs[k] = rng.nextInt(-3, 4).toLong()
                if (coeffs.isEmpty()) return@repeat
                val rel = when (rng.nextInt(3)) {
                    0 -> Relation.LE
                    1 -> Relation.GE
                    else -> Relation.EQ
                }
                b.addRow(coeffs, rel, rng.nextInt(-4, 12).toLong())
            }
            val model = b.build(Sense.MINIMIZE)

            val cold = DualSimplex(model).solve()
            val warm = DualSimplex(model).solve(FloatSimplex(model).basis())
            // The exact solver is the source of truth; the float basis is only a warm start, so both
            // must agree on status and (when optimal) on the exact bound.
            assertEquals(cold.status, warm.status, "status diverged")
            if (cold.status == LpStatus.OPTIMAL) {
                assertEquals(cold.objectiveNumerator, warm.objectiveNumerator)
                assertEquals(cold.denominator, warm.denominator)
            }
            matched++
        }
        assertTrue(matched == 500)
    }
}
