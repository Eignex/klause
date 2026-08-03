package com.eignex.klause.presolve

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.presolve.PresolveShared.withPassDelta
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.solver.Assumptions
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Binary implication graph ([Presolve.reduceImplicationGraph]). Asserts the observable reduction —
 * merged equivalent variables, dropped redundant binaries, protected objective variables, the no-op
 * identity — and that a reconstructed solution of every reduced problem is feasible in the original
 * (the soundness round-trip). The cap is large enough that every fixture is fully harvested.
 */
class ImplicationGraphTest {

    private val cap = 1024

    private fun isFeasible(problem: Problem, sample: Sample): Boolean {
        var a = Assumptions.None
        for (v in 0 until problem.numBoolVars) a = a.withBool(v, sample.bools[v])
        for (v in 0 until problem.numIntVars) a = a.withInt(v, sample.ints[v])
        return problem.propagate(a) !is PropagationResult.Unsat
    }

    private fun binaryCount(problem: Problem): Int =
        problem.factors.filterIsInstance<Clause>().count { it.literals.size == 2 }

    /** The problem [Presolve.reduceImplicationGraph] reduces [problem] to, materialized from its delta. */
    private fun reduced(problem: Problem, objectiveBoolVars: Set<Int> = emptySet()): Problem {
        val delta = Presolve.reduceImplicationGraph(problem, cap, objectiveBoolVars = objectiveBoolVars)
        return problem.withPassDelta(delta, BakeConfig.NONE)
    }

    /** Solve the reduced problem, reconstruct via the [delta], and assert the lifted sample is feasible. */
    private fun assertRoundTrip(original: Problem, delta: PassDelta) {
        val solved = BacktrackSolver(original.withPassDelta(delta, BakeConfig.NONE).bake()).solve(BacktrackParams())
        check(solved is SolveResult.Sat) { "reduced problem should be satisfiable" }
        val full = (delta.reconstruct ?: { it })(solved.assignment)
        assertTrue(isFeasible(original, full), "reconstructed sample infeasible in the original")
    }

    @Test
    fun two_variables_in_a_mutual_implication_cycle_collapse_to_one() {
        // (!b0 | b1) and (!b1 | b0) make b0 <-> b1: pinning b0 propagates b1 and vice versa, a cycle.
        // The pass merges b1 into b0, so b1 stops appearing in the substituted factors.
        val problem = Problem(
            numBoolVars = 2,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = listOf(
                Clause(intArrayOf(Lit.make(0, false), Lit.make(1, true))),
                Clause(intArrayOf(Lit.make(1, false), Lit.make(0, true))),
            ),
        )
        assertTrue(reduced(problem).factors.none { 1 in it.boolVars }, "b1 should be substituted away")
        assertRoundTrip(problem, Presolve.reduceImplicationGraph(problem, cap))
    }

    @Test
    fun an_equivalence_chain_collapses_to_a_single_representative() {
        // b0 <-> b1 <-> b2 via three mutual-implication pairs: all three share one SCC, so b1 and b2
        // both merge into the smallest id b0.
        val problem = Problem(
            numBoolVars = 3,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = listOf(
                Clause(intArrayOf(Lit.make(0, false), Lit.make(1, true))),
                Clause(intArrayOf(Lit.make(1, false), Lit.make(0, true))),
                Clause(intArrayOf(Lit.make(1, false), Lit.make(2, true))),
                Clause(intArrayOf(Lit.make(2, false), Lit.make(1, true))),
            ),
        )
        assertTrue(reduced(problem).factors.none { 1 in it.boolVars || 2 in it.boolVars }, "b1, b2 merged into b0")
        assertRoundTrip(problem, Presolve.reduceImplicationGraph(problem, cap))
    }

    @Test
    fun anti_equivalent_variables_are_not_merged() {
        // (!b0 | !b1) and (b0 | b1) make b0 <-> !b1. Substitution preserves polarity and cannot express
        // the flip, so neither variable is merged and both still appear in the factors.
        val problem = Problem(
            numBoolVars = 2,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = listOf(
                Clause(intArrayOf(Lit.make(0, false), Lit.make(1, false))),
                Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
            ),
        )
        assertTrue(reduced(problem).factors.any { 1 in it.boolVars }, "anti-equivalent b1 must not be merged away")
    }

    @Test
    fun a_transitively_redundant_binary_is_dropped() {
        // a -> b, b -> c, a -> c as three binary clauses. The direct a -> c is entailed by the chain
        // a -> b -> c, so the pass drops it; the two chain clauses survive.
        val problem = Problem(
            numBoolVars = 3,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = listOf(
                Clause(intArrayOf(Lit.make(0, false), Lit.make(1, true))), // a -> b
                Clause(intArrayOf(Lit.make(1, false), Lit.make(2, true))), // b -> c
                Clause(intArrayOf(Lit.make(0, false), Lit.make(2, true))), // a -> c (redundant)
            ),
        )
        assertEquals(2, binaryCount(reduced(problem)), "the redundant a -> c binary is dropped")
        assertRoundTrip(problem, Presolve.reduceImplicationGraph(problem, cap))
    }

    @Test
    fun a_non_redundant_binary_is_kept() {
        // a -> b and b -> c with no a -> c edge: every binary carries information the others don't, so
        // none is redundant and all survive.
        val problem = Problem(
            numBoolVars = 3,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = listOf(
                Clause(intArrayOf(Lit.make(0, false), Lit.make(1, true))),
                Clause(intArrayOf(Lit.make(1, false), Lit.make(2, true))),
            ),
        )
        assertEquals(2, binaryCount(reduced(problem)), "no binary is redundant")
        assertTrue(Presolve.reduceImplicationGraph(problem, cap).isEmpty, "nothing to reduce is the no-op signal")
    }

    @Test
    fun an_objective_variable_is_never_merged() {
        // b0 <-> b1, but b1 is read by the objective: it must keep a constrained variable, so the pass
        // leaves both untouched even though they are equivalent.
        val problem = Problem(
            numBoolVars = 2,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = listOf(
                Clause(intArrayOf(Lit.make(0, false), Lit.make(1, true))),
                Clause(intArrayOf(Lit.make(1, false), Lit.make(0, true))),
            ),
        )
        assertTrue(
            reduced(problem, objectiveBoolVars = setOf(1)).factors.any { 1 in it.boolVars },
            "an objective variable must not be merged",
        )
    }

    @Test
    fun a_problem_with_nothing_to_derive_is_returned_unchanged() {
        // Two independent free booleans with one disjunction: no mutual implication, no redundant
        // binary, so the pass returns its input unchanged.
        val problem = Problem(
            numBoolVars = 2,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = listOf(Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true)))),
        )
        assertTrue(Presolve.reduceImplicationGraph(problem, cap).isEmpty, "no reduction is the no-op signal")
    }

    @Test
    fun the_builder_records_the_forward_implication_edge_of_a_binary_clause() {
        // (!b0 | b1) is the implication b0 -> b1: pinning b0 = true propagates b1 = true, so the
        // literal-indexed adjacency carries the edge from b0+ to b1+.
        val problem = Problem(
            numBoolVars = 2,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = listOf(Clause(intArrayOf(Lit.make(0, false), Lit.make(1, true)))),
        )
        val graph = Presolve.implicationGraph(problem, cap)
        assertTrue(Lit.make(1, true) in graph[Lit.make(0, true)].toList(), "b0 = true must force b1 = true")
    }

    @Test
    fun the_builder_records_the_contrapositive_implication_edge_of_a_binary_clause() {
        // The same (!b0 | b1) is also b1 -> b0 contrapositively: pinning b1 = false propagates
        // b0 = false, so the adjacency carries the edge from b1- to b0-.
        val problem = Problem(
            numBoolVars = 2,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = listOf(Clause(intArrayOf(Lit.make(0, false), Lit.make(1, true)))),
        )
        val graph = Presolve.implicationGraph(problem, cap)
        assertTrue(Lit.make(0, false) in graph[Lit.make(1, false)].toList(), "b1 = false must force b0 = false")
    }

    @Test
    fun the_builder_yields_all_empty_lists_when_no_binary_implication_exists() {
        // A single ternary disjunction: pinning either polarity of any variable leaves a non-unit
        // clause, so propagation forces nothing and every adjacency list is empty over the
        // 2*numBoolVars nodes.
        val problem = Problem(
            numBoolVars = 3,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = listOf(Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true)))),
        )
        val graph = Presolve.implicationGraph(problem, cap)
        assertEquals(2 * problem.numBoolVars, graph.size, "one node per literal")
        assertTrue(graph.all { it.isEmpty() }, "no implication means every adjacency list is empty")
    }
}
