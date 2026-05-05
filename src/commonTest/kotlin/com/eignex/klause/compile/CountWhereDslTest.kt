package com.eignex.klause.compile

import com.eignex.klause.ast.cardinality
import com.eignex.klause.ast.eq
import com.eignex.klause.ast.le
import com.eignex.klause.schema.VariableSchema
import com.eignex.klause.solver.Solver
import kotlin.test.Test
import kotlin.test.assertTrue

class CountWhereDslTest {

    @Test
    fun cardinalityOverArbitraryBoolExprsCounts() {
        // Two of three predicates must hold: each predicate is itself an int comparison.
        class S : VariableSchema() {
            val x by intVar(min = 0, max = 5)
            val y by intVar(min = 0, max = 5)
            val z by intVar(min = 0, max = 5)
            val twoOfThreeBig by constraint {
                cardinality(2, 3, x le 1, y le 1, z le 1)
            }
        }
        val schema = S()
        val compiled = schema.compile()
        val solver = Solver(compiled.problem, maxFlipsBeforeRestart = 500)
        val samples = solver.sample(maxFlips = 30_000, randomSeed = 17).take(10).toList()
        assertTrue(samples.isNotEmpty())
        for (s in samples) {
            val xv = compiled.decodeInt("x", s)
            val yv = compiled.decodeInt("y", s)
            val zv = compiled.decodeInt("z", s)
            val count = listOf(xv, yv, zv).count { it <= 1 }
            assertTrue(count in 2..3, "x=$xv y=$yv z=$zv count=$count")
        }
    }

    @Test
    fun cardinalityOverIntEqualities() {
        class S : VariableSchema() {
            val a by intVar(min = 0, max = 4)
            val b by intVar(min = 0, max = 4)
            val c by intVar(min = 0, max = 4)
            // Exactly one of a,b,c equals 0.
            val one by constraint { cardinality(1, 1, a eq 0, b eq 0, c eq 0) }
        }
        val schema = S()
        val compiled = schema.compile()
        val solver = Solver(compiled.problem, maxFlipsBeforeRestart = 500)
        val samples = solver.sample(maxFlips = 30_000, randomSeed = 22).take(10).toList()
        assertTrue(samples.isNotEmpty())
        for (s in samples) {
            val av = compiled.decodeInt("a", s)
            val bv = compiled.decodeInt("b", s)
            val cv = compiled.decodeInt("c", s)
            val count = listOf(av, bv, cv).count { it == 0 }
            assertTrue(count == 1, "a=$av b=$bv c=$cv count=$count")
        }
    }
}
