package com.eignex.klause.solver.factor

import com.eignex.klause.solver.Assumptions
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.propagation.PropagationResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Propagation-tightening coverage for [Path] / [Tree] (part of #104): edge-implies-endpoints,
 * source→sink (resp. root) reachability pruning, and the infeasibility checks. Bool var ids
 * are `nodePresent` then `edgePresent`; the source/sink/root are int vars.
 */
class PathTreeTest {

    // 4 nodes, edges e0: 0→1, e1: 1→2, e2: 0→3 (node 3 is a dead end with no out-arc).
    // Bool ids: nodePresent = 0..3, edgePresent = 4..6. Int ids: source = 0, sink = 1.
    private fun pathProblem() = Problem(
        numBoolVars = 7,
        numIntVars = 2,
        intDomains = arrayOf(IntDomain(0, 3), IntDomain(0, 3)),
        factors = arrayOf<Factor>(
            Path(
                numNodes = 4,
                from = intArrayOf(0, 1, 0),
                to = intArrayOf(1, 2, 3),
                source = 0,
                sink = 1,
                nodePresent = intArrayOf(0, 1, 2, 3),
                edgePresent = intArrayOf(4, 5, 6),
            ),
        ),
    )

    @Test
    fun `path edge present forces both endpoints present`() {
        val result = pathProblem().propagate(Assumptions(bools = mapOf(4 to true)))
        val implied = assertIs<PropagationResult.Implied>(result)
        assertEquals(true, implied.boolValueOrNull(0), "edge 0→1 present ⇒ node 0 present")
        assertEquals(true, implied.boolValueOrNull(1), "edge 0→1 present ⇒ node 1 present")
    }

    @Test
    fun `path prunes a node off every source-sink path`() {
        // source = 0, sink = 2. Node 3 is reachable forward (0→3) but cannot reach the sink,
        // so it lies on no source→sink path and must be pruned absent; likewise edge 0→3.
        val result = pathProblem().propagate(Assumptions(ints = mapOf(0 to 0, 1 to 2)))
        val implied = assertIs<PropagationResult.Implied>(result)
        assertEquals(false, implied.boolValueOrNull(3), "dead-end node 3 is on no source→sink path")
        assertEquals(false, implied.boolValueOrNull(6), "edge 0→3 is on no source→sink path")
    }

    @Test
    fun `path with unreachable sink is infeasible`() {
        // source = 0, sink = 2, but edge 1→2 (the only in-arc of node 2) forced absent.
        val result = pathProblem().propagate(Assumptions(ints = mapOf(0 to 0, 1 to 2), bools = mapOf(5 to false)))
        assertIs<PropagationResult.Unsat>(result)
    }

    // 3 nodes, edges e0: 0→1, e1: 1→2. Bool ids: nodePresent = 0..2, edgePresent = 3..4. root = int 0.
    private fun treeProblem() = Problem(
        numBoolVars = 5,
        numIntVars = 1,
        intDomains = arrayOf(IntDomain(0, 2)),
        factors = arrayOf<Factor>(
            Tree(
                numNodes = 3,
                from = intArrayOf(0, 1),
                to = intArrayOf(1, 2),
                root = 0,
                nodePresent = intArrayOf(0, 1, 2),
                edgePresent = intArrayOf(3, 4),
            ),
        ),
    )

    @Test
    fun `tree prunes a node unreachable from the root`() {
        // root = 0; edge 1→2 (node 2's only in-arc from the root's reach) forced absent ⇒
        // node 2 unreachable ⇒ pruned absent.
        val result = treeProblem().propagate(Assumptions(ints = mapOf(0 to 0), bools = mapOf(4 to false)))
        val implied = assertIs<PropagationResult.Implied>(result)
        assertEquals(false, implied.boolValueOrNull(2), "node 2 unreachable from root ⇒ absent")
    }

    @Test
    fun `tree with a forced-present unreachable node is infeasible`() {
        // root = 0, node 2 forced present, but edge 1→2 forced absent ⇒ node 2 unreachable.
        val result = treeProblem().propagate(Assumptions(ints = mapOf(0 to 0), bools = mapOf(2 to true, 4 to false)))
        assertIs<PropagationResult.Unsat>(result)
    }
}
