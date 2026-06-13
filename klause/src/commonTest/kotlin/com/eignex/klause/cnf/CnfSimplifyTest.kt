package com.eignex.klause.cnf

import com.eignex.klause.solver.Lit
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CnfSimplifyTest {

    private fun pos(v: Int) = Lit.make(v, true)
    private fun neg(v: Int) = Lit.make(v, false)

    /** Does [model] (var → bool) satisfy every clause? */
    private fun satisfies(clauses: List<IntArray>, model: BooleanArray): Boolean = clauses.all { clause ->
        clause.isNotEmpty() && clause.any { lit -> Lit.evaluate(lit, model[Lit.variable(lit)]) }
    }

    /** Assert simplification preserves the exact model set over [numVars] variables. */
    private fun assertModelsPreserved(numVars: Int, clauses: List<IntArray>) {
        val simplified = CnfSimplify.subsumeClauses(clauses)
        val model = BooleanArray(numVars)
        for (mask in 0 until (1 shl numVars)) {
            for (v in 0 until numVars) model[v] = (mask shr v) and 1 == 1
            assertEquals(
                satisfies(clauses, model),
                satisfies(simplified, model),
                "model $mask disagrees; original=$clauses simplified=$simplified",
            )
        }
        // Simplification must never grow the database.
        val origLits = clauses.sumOf { it.size }
        val newLits = simplified.sumOf { it.size }
        assertTrue(newLits <= origLits, "literal count grew: $origLits -> $newLits")
    }

    @Test
    fun `subsumption drops the superset clause`() {
        // (a) subsumes (a ∨ b): the second is redundant.
        val clauses = listOf(intArrayOf(pos(0)), intArrayOf(pos(0), pos(1)))
        val out = CnfSimplify.subsumeClauses(clauses)
        assertEquals(1, out.size)
        assertEquals(listOf(pos(0)), out[0].toList())
        assertModelsPreserved(2, clauses)
    }

    @Test
    fun `duplicate clauses collapse to one`() {
        val clauses = listOf(
            intArrayOf(pos(0), neg(1)),
            intArrayOf(neg(1), pos(0)),
        )
        val out = CnfSimplify.subsumeClauses(clauses)
        assertEquals(1, out.size)
        assertModelsPreserved(2, clauses)
    }

    @Test
    fun `tautological clause is removed`() {
        val clauses = listOf(intArrayOf(pos(0), neg(0), pos(1)), intArrayOf(pos(1)))
        val out = CnfSimplify.subsumeClauses(clauses)
        assertTrue(out.none { it.any { lit -> Lit.variable(lit) == 0 } }, "tautology survived: $out")
        assertModelsPreserved(2, clauses)
    }

    @Test
    fun `self-subsuming resolution strengthens the clause`() {
        // (a ∨ b) ∧ (¬a ∨ b ∨ c)  ==>  (a ∨ b) ∧ (b ∨ c)
        val clauses = listOf(
            intArrayOf(pos(0), pos(1)),
            intArrayOf(neg(0), pos(1), pos(2)),
        )
        val out = CnfSimplify.subsumeClauses(clauses)
        val strengthened = out.firstOrNull { c -> c.any { Lit.variable(it) == 2 } }
        requireNotNull(strengthened) { "clause with var 2 vanished: $out" }
        assertEquals(listOf(pos(1), pos(2)), strengthened.toList())
        assertModelsPreserved(3, clauses)
    }

    @Test
    fun `unit clause strips its negation everywhere`() {
        // (a) forces a=true, so ¬a is removable from (¬a ∨ b).
        val clauses = listOf(intArrayOf(pos(0)), intArrayOf(neg(0), pos(1)))
        val out = CnfSimplify.subsumeClauses(clauses)
        // (¬a ∨ b) strengthens to (b); then nothing subsumes it. Models: a∧b only.
        assertModelsPreserved(2, clauses)
        val model = BooleanArray(2) { true }
        assertTrue(satisfies(out, model))
    }

    @Test
    fun `empty clause is preserved as unsat`() {
        val clauses = listOf(intArrayOf(), intArrayOf(pos(0)))
        val out = CnfSimplify.subsumeClauses(clauses)
        assertTrue(out.any { it.isEmpty() }, "empty clause lost: $out")
    }

    @Test
    fun `random small CNFs preserve their model set`() {
        val rng = Random(0xC0FFEE)
        repeat(400) {
            val numVars = 2 + rng.nextInt(6) // 2..7
            val numClauses = rng.nextInt(12)
            val clauses = ArrayList<IntArray>(numClauses)
            repeat(numClauses) {
                val width = 1 + rng.nextInt(3) // 1..3 literals
                val lits = IntArray(width) {
                    val v = rng.nextInt(numVars)
                    Lit.make(v, rng.nextBoolean())
                }
                clauses.add(lits)
            }
            assertModelsPreserved(numVars, clauses)
        }
    }

    @Test
    fun `subsumption composes with the bitblaster decode tables`() {
        val problem = CnfProblem(
            numVars = 2,
            clauses = listOf(intArrayOf(pos(0)), intArrayOf(pos(0), pos(1))),
            boolVarToCnfVar = intArrayOf(0, 1),
            intVarBits = arrayOf(),
            intVarMin = intArrayOf(),
        )
        val out = CnfSimplify.subsume(problem)
        assertEquals(problem.numVars, out.numVars)
        assertEquals(1, out.clauses.size)
        // Decode tables carried through unchanged.
        assertTrue(out.boolVarToCnfVar.contentEquals(problem.boolVarToCnfVar))
    }
}
