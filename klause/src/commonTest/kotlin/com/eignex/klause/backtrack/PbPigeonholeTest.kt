package com.eignex.klause.backtrack

import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.SolveResult
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Pigeonhole is the textbook cutting-planes win: cutting-planes refutes PHPₙ in polynomially many steps,
 * while resolution (clause learning) needs exponentially many. This asserts pseudo-Boolean learning
 * (#1119 Phase 3) refutes PHP with a *polynomial* node count — a resolution-only solver cannot.
 */
class PbPigeonholeTest {

    /** [pigeons] pigeons into [holes] holes as cardinality constraints: each pigeon in ≥1 hole, each hole
     *  holds ≤1 pigeon. UNSAT whenever pigeons > holes. */
    private fun php(pigeons: Int, holes: Int): Problem {
        fun x(p: Int, h: Int) = p * holes + h
        val factors = ArrayList<Factor>()
        for (p in 0 until pigeons) {
            factors.add(Cardinality(IntArray(holes) { h -> Lit.make(x(p, h), true) }, min = 1, max = holes))
        }
        for (h in 0 until holes) {
            factors.add(Cardinality(IntArray(pigeons) { p -> Lit.make(x(p, h), true) }, min = 0, max = 1))
        }
        return Problem(pigeons * holes, 0, emptyArray(), factors.toTypedArray())
    }

    private fun nodes(r: SolveResult.Unsat): Long = r.stats.search.nodes.sum.toLong()

    @Test
    fun `pb learning refutes pigeonhole with polynomially many nodes`() {
        // PHP over 8 holes (9 pigeons). Cutting-planes refutes it in a few hundred nodes; a resolution
        // solver needs tens of thousands. The bound is far below the clause-learning node count (~10.9k
        // measured) yet comfortably above the cutting-planes count (~0.4k measured), so it captures the
        // exponential/polynomial separation without being brittle.
        val result = assertIs<SolveResult.Unsat>(
            BacktrackSolver(
                php(pigeons = 9, holes = 8).bake(),
            ).solve(BacktrackParams(randomSeed = 1L, pbLearning = true)),
        )
        assertTrue(
            nodes(result) < 3000,
            "PB cutting-planes should refute PHP-8 in polynomially many nodes, got ${nodes(result)}",
        )
    }

    @Test
    fun `pb learning uses far fewer nodes than clause learning on pigeonhole`() {
        // Small enough that the clause path is still cheap, so the separation can be asserted directly.
        val p = { php(pigeons = 7, holes = 6) }
        val pb = assertIs<SolveResult.Unsat>(
            BacktrackSolver(p().bake()).solve(BacktrackParams(randomSeed = 1L, pbLearning = true)),
        )
        val clause = assertIs<SolveResult.Unsat>(
            BacktrackSolver(p().bake()).solve(BacktrackParams(randomSeed = 1L, pbLearning = false)),
        )
        assertTrue(
            nodes(pb) * 3 < nodes(clause),
            "PB learning should explore far fewer nodes: pb=${nodes(pb)} clause=${nodes(clause)}",
        )
    }
}
