package com.eignex.klause.compile

import com.eignex.klause.ast.inverse
import com.eignex.klause.ast.symmetricAllDifferent
import com.eignex.klause.schema.VariableSchema
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.backtrack.BacktrackSolver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The strong scheduling/channeling globals are reachable through the builder DSL, not only the
 * FlatZinc front-end. Enumerating the compiled problem must give exactly the constraint's
 * solution set, so the DSL → AST → compiler → factor path is wired correctly.
 */
class FactorExposureDslTest {

    @Test
    fun `symmetricAllDifferent enumerates exactly the involutions`() {
        class S : VariableSchema() {
            val x0 by intVar(min = 0, max = 2)
            val x1 by intVar(min = 0, max = 2)
            val x2 by intVar(min = 0, max = 2)
            val rows by constraint { symmetricAllDifferent(listOf(x0, x1, x2)) }
        }
        val schema = S()
        val compiled = schema.compile()
        val handles = listOf(schema.x0, schema.x1, schema.x2)
        val sols = BacktrackSolver(compiled.problem).enumerate(BacktrackParams(randomSeed = 0L))
            .map { s -> handles.map { compiled.decode(it, s) } }.toList()
        // Every solution is a self-inverse permutation of {0,1,2}.
        for (p in sols) {
            assertEquals(p.toSet().size, p.size, "not a permutation: $p")
            for (i in p.indices) assertEquals(i, p[p[i]], "not an involution at $i: $p")
        }
        // S3 has 4 involutions: identity plus the three transpositions.
        assertEquals(4, sols.toSet().size, "expected 4 involutions, got ${sols.toSet()}")
    }

    @Test
    fun `inverse enumerates exactly the permutation-inverse pairs`() {
        class S : VariableSchema() {
            val f0 by intVar(min = 0, max = 2)
            val f1 by intVar(min = 0, max = 2)
            val f2 by intVar(min = 0, max = 2)
            val g0 by intVar(min = 0, max = 2)
            val g1 by intVar(min = 0, max = 2)
            val g2 by intVar(min = 0, max = 2)
            val rows by constraint { inverse(listOf(f0, f1, f2), listOf(g0, g1, g2)) }
        }
        val schema = S()
        val compiled = schema.compile()
        val fh = listOf(schema.f0, schema.f1, schema.f2)
        val gh = listOf(schema.g0, schema.g1, schema.g2)
        val sols = BacktrackSolver(compiled.problem).enumerate(BacktrackParams(randomSeed = 0L))
            .map { s -> fh.map { compiled.decode(it, s) } to gh.map { compiled.decode(it, s) } }.toList()
        for ((f, g) in sols) {
            assertTrue(f.toSet().size == f.size, "f not a permutation: $f")
            for (i in f.indices) assertEquals(i, g[f[i]], "g is not the inverse of f at $i: f=$f g=$g")
        }
        // Each of the 6 permutations of {0,1,2} pairs with a unique inverse.
        assertEquals(6, sols.size, "expected 6 permutation/inverse pairs, got ${sols.size}")
    }
}
