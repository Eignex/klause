package com.eignex.klause.compile

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.circuit.Circuit
import com.eignex.klause.factor.scheduling.Cumulative
import com.eignex.klause.factor.scheduling.Disjunctive
import com.eignex.klause.schema.VariableSchema
import com.eignex.klause.schema.circuit
import com.eignex.klause.schema.cumulative
import com.eignex.klause.schema.disjunctive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CircuitDslTest {

    @Test
    fun `circuit DSL emits a Circuit factor`() {
        class S : VariableSchema() {
            val s0 by intVar(min = 0, max = 3)
            val s1 by intVar(min = 0, max = 3)
            val s2 by intVar(min = 0, max = 3)
            val s3 by intVar(min = 0, max = 3)
            val cyc by constraint { circuit(s0, s1, s2, s3) }
        }
        val compiled = S().compile()
        assertTrue(compiled.problem.factors.any { it is Circuit })
        val solver = BacktrackSolver(compiled.problem)
        val sample = solver.sample(BacktrackParams()).assignment
        assertNotNull(sample)
        // Verify it's a Hamiltonian cycle of length 4.
        val visited = BooleanArray(4)
        var node = 0
        for (step in 0 until 4) {
            assertTrue(!visited[node], "revisit at step $step")
            visited[node] = true
            node = sample.ints[node]
        }
        assertEquals(0, node, "must close the cycle")
    }

    @Test
    fun `circuit DSL with 1-indexed offset channels through aux vars`() {
        // Successors live in [1, 4] (MiniZinc-style). Compiler should add channeling.
        class S : VariableSchema() {
            val s0 by intVar(min = 1, max = 4)
            val s1 by intVar(min = 1, max = 4)
            val s2 by intVar(min = 1, max = 4)
            val s3 by intVar(min = 1, max = 4)
            val cyc by constraint { circuit(listOf(s0, s1, s2, s3), valueOffset = 1) }
        }
        val compiled = S().compile()
        assertTrue(compiled.problem.factors.any { it is Circuit })
        val solver = BacktrackSolver(compiled.problem)
        val sample = solver.sample(BacktrackParams()).assignment
        assertNotNull(sample)
        // The user-facing values are 1..4 (1-indexed). Verify Hamiltonian cycle in that space.
        val visited = BooleanArray(4)
        var node = 0 // 0-indexed entry point
        for (step in 0 until 4) {
            assertTrue(!visited[node], "revisit at step $step in ${sample.ints.toList()}")
            visited[node] = true
            // Successor value (1-indexed) → 0-indexed node.
            node = sample.ints[node] - 1
        }
        assertEquals(0, node, "must close the cycle (1-indexed inputs decoded)")
    }

    @Test
    fun `cumulative DSL emits a Cumulative factor`() {
        class S : VariableSchema() {
            val t0 by intVar(min = 0, max = 4)
            val t1 by intVar(min = 0, max = 4)
            val t2 by intVar(min = 0, max = 4)
            val cap by constraint {
                cumulative(
                    starts = listOf(t0, t1, t2),
                    durations = listOf(2, 2, 2),
                    resources = listOf(1, 1, 1),
                    capacity = 1,
                )
            }
        }
        val compiled = S().compile()
        assertTrue(compiled.problem.factors.any { it is Cumulative })
        val solver = BacktrackSolver(compiled.problem)
        val sample = solver.sample(BacktrackParams()).assignment
        assertNotNull(sample)
        // Verify non-overlapping schedule under capacity 1.
        val occ = IntArray(8)
        for (i in 0 until 3) {
            val s = sample.ints[i]
            for (t in s until s + 2) if (t in occ.indices) occ[t]++
        }
        for (t in occ.indices) assertTrue(occ[t] <= 1, "capacity broken at t=$t: ${sample.ints.toList()}")
    }

    @Test
    fun `disjunctive DSL emits a Disjunctive factor`() {
        class S : VariableSchema() {
            val t0 by intVar(min = 0, max = 2)
            val t1 by intVar(min = 0, max = 2)
            val t2 by intVar(min = 0, max = 2)
            val nonOverlap by constraint {
                disjunctive(listOf(t0, t1, t2), listOf(1, 1, 1))
            }
        }
        val compiled = S().compile()
        assertTrue(compiled.problem.factors.any { it is Disjunctive })
        val solver = BacktrackSolver(compiled.problem)
        val samples = solver.enumerate(BacktrackParams()).toList()
        assertEquals(6, samples.size, "expected 3! disjunctive schedules; got $samples")
    }
}
