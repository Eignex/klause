package com.eignex.klause.solver.strategy

import com.eignex.klause.solver.localsearch.strategy.Vns

import com.eignex.klause.solver.localsearch.FixedCadenceRestart
import com.eignex.klause.solver.localsearch.LocalSearchParams
import com.eignex.klause.ast.atLeast
import com.eignex.klause.ast.atMost
import com.eignex.klause.compile.compile
import com.eignex.klause.schema.VariableSchema
import com.eignex.klause.solver.localsearch.LocalSearchSolver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VnsTest {

    @Test
    fun `VNS solves a small SAT problem`() {
        class S : VariableSchema() {
            val a by boolVar()
            val b by boolVar()
            val c by boolVar()
            val d by boolVar()
            val e by boolVar()
            val cap by constraint { atMost(3, a, b, c, d, e) }
            val req by constraint { atLeast(2, a, b, c, d, e) }
        }
        val schema = S()
        val compiled = schema.compile()
        val solver = LocalSearchSolver(
            compiled.problem,
            strategy = Vns(maxNeighborhood = 3, stagnationThreshold = 20),
            restartPolicy = FixedCadenceRestart(maxFlipsBeforeRestart = 200),
        )
        val samples = solver.enumerate(LocalSearchParams(maxFlips = 5_000, randomSeed = 17)).take(5).toList()
        assertTrue(samples.isNotEmpty(), "VNS should find at least one sample")
        for (s in samples) {
            val count = listOf(schema.a, schema.b, schema.c, schema.d, schema.e).count { compiled.decode(it, s) }
            assertTrue(count in 2..3, "count=$count violates 2..3")
        }
    }

    @Test
    fun `VNS demotes neighborhood on improvement and promotes on stagnation`() {
        // Drive the strategy directly so we can observe its neighborhood index.
        class S : VariableSchema() {
            val a by boolVar()
            val b by boolVar()
            val req by constraint { atLeast(1, a, b) }
        }
        val schema = S()
        val compiled = schema.compile()
        val state = com.eignex.klause.solver.localsearch.LocalSearchState(compiled.problem, kotlin.random.Random(0))
        state.assignment.setBool(0, false)
        state.assignment.setBool(1, false)
        state.recompute() // cost == 1 (atLeast(1) violated)

        val vns = Vns(maxNeighborhood = 3, stagnationThreshold = 3, noise = 0.0)
        assertEquals(1, vns.currentNeighborhood, "starts at N1")

        // 3 non-improving picks should promote to N2 (cost stays at 1 → stall).
        // But the engine resets cost only on apply; we drive pickMove without applying
        // so cost stays constant → each call counts as a stall.
        repeat(3) { vns.pickMove(state) }
        assertEquals(2, vns.currentNeighborhood, "promoted to N2 after 3 stalls")

        // Mock a cost decrease and verify demotion.
        state.cost = 0  // pretend search reached feasibility
        vns.pickMove(state)
        // pickMove returns null when violated is empty, but updateNeighborhood ran.
        assertEquals(1, vns.currentNeighborhood, "demoted to N1 on cost improvement")
    }

    @Test
    fun `VNS cycles back to N1 after max neighborhood`() {
        class S : VariableSchema() {
            val a by boolVar()
            val b by boolVar()
            val req by constraint { atLeast(1, a, b) }
        }
        val schema = S()
        val compiled = schema.compile()
        val state = com.eignex.klause.solver.localsearch.LocalSearchState(compiled.problem, kotlin.random.Random(0))
        state.assignment.setBool(0, false)
        state.assignment.setBool(1, false)
        state.recompute()

        val vns = Vns(maxNeighborhood = 2, stagnationThreshold = 2, noise = 0.0)
        // Drive enough stalls to cycle N1 → N2 → N1.
        repeat(2) { vns.pickMove(state) }
        assertEquals(2, vns.currentNeighborhood, "promoted to N2")
        repeat(2) { vns.pickMove(state) }
        assertEquals(1, vns.currentNeighborhood, "cycled back to N1 after exhausting max")
    }
}
