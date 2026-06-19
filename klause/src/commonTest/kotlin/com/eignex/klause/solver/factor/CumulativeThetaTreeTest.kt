package com.eignex.klause.solver.factor

import com.eignex.klause.solver.factor.scheduling.internals.CumulativeThetaTree
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CumulativeThetaTreeTest {

    @Test fun `empty tree returns no envelope`() {
        val t = CumulativeThetaTree(n = 4, capacity = 3)
        assertEquals(CumulativeThetaTree.NO_ENV, t.envOfTheta())
        assertEquals(0L, t.energyOfTheta())
        assertFalse(t.isActive(0))
    }

    @Test fun `single task envelope matches the formula`() {
        val t = CumulativeThetaTree(n = 1, capacity = 2)
        t.activate(id = 0, est = 5, taskEnergy = 6L)
        assertTrue(t.isActive(0))
        assertEquals(2L * 5 + 6, t.envOfTheta())
        assertEquals(6L, t.energyOfTheta())
    }

    @Test fun `deactivate restores the empty envelope`() {
        val t = CumulativeThetaTree(n = 2, capacity = 2)
        t.activate(0, est = 0, taskEnergy = 4L)
        t.activate(1, est = 3, taskEnergy = 6L)
        t.deactivate(0)
        t.deactivate(1)
        assertEquals(CumulativeThetaTree.NO_ENV, t.envOfTheta())
        assertEquals(0L, t.energyOfTheta())
    }

    @Test fun `two tasks left anchor wins the envelope`() {
        // Tasks: a est=0 e=10, b est=5 e=2, capacity=1.
        // env(a) = 0 + 10 = 10
        // env(b) = 5 + 2 = 7
        // env(theta) = max(env(a) + e(b), env(b)) = max(12, 7) = 12
        val t = CumulativeThetaTree(n = 2, capacity = 1)
        t.setLeafOrder(intArrayOf(0, 1))
        t.activate(0, est = 0, taskEnergy = 10L)
        t.activate(1, est = 5, taskEnergy = 2L)
        assertEquals(12L, t.envOfTheta())
        assertEquals(12L, t.energyOfTheta())
    }

    @Test fun `two tasks right anchor wins the envelope`() {
        // Tasks: a est=0 e=1, b est=100 e=5, capacity=10.
        // env(a) = 0 + 1 = 1
        // env(b) = 1000 + 5 = 1005
        // env(theta) = max(1 + 5, 1005) = 1005
        val t = CumulativeThetaTree(n = 2, capacity = 10)
        t.setLeafOrder(intArrayOf(0, 1))
        t.activate(0, est = 0, taskEnergy = 1L)
        t.activate(1, est = 100, taskEnergy = 5L)
        assertEquals(1005L, t.envOfTheta())
    }

    @Test fun `deactivate matches never-activated state`() {
        // Build a tree of three tasks, then deactivate one. Result should match a tree
        // that was built with only the other two from the start.
        val full = CumulativeThetaTree(n = 3, capacity = 4)
        full.setLeafOrder(intArrayOf(0, 1, 2))
        full.activate(0, est = 1, taskEnergy = 5L)
        full.activate(1, est = 4, taskEnergy = 3L)
        full.activate(2, est = 8, taskEnergy = 2L)
        full.deactivate(1)

        val twoOnly = CumulativeThetaTree(n = 3, capacity = 4)
        twoOnly.setLeafOrder(intArrayOf(0, 1, 2))
        twoOnly.activate(0, est = 1, taskEnergy = 5L)
        twoOnly.activate(2, est = 8, taskEnergy = 2L)

        assertEquals(twoOnly.envOfTheta(), full.envOfTheta())
        assertEquals(twoOnly.energyOfTheta(), full.energyOfTheta())
    }

    @Test fun `leaf ordering matters for the envelope`() {
        // Same task set, different leaf orderings: the recurrence anchors at the
        // leftmost EST in the subtree, so EST-ascending leaf order is the one that
        // gives the correct envelope.
        val aEst = 0
        val aE = 10L
        val bEst = 5
        val bE = 4L
        val capacity = 1

        val ordered = CumulativeThetaTree(n = 2, capacity = capacity)
        ordered.setLeafOrder(intArrayOf(0, 1))
        ordered.activate(0, aEst, aE)
        ordered.activate(1, bEst, bE)
        // env = max(env(a) + e(b), env(b)) = max((0+10)+4, 5+4) = max(14, 9) = 14
        assertEquals(14L, ordered.envOfTheta())

        val swapped = CumulativeThetaTree(n = 2, capacity = capacity)
        swapped.setLeafOrder(intArrayOf(1, 0))
        swapped.activate(0, aEst, aE)
        swapped.activate(1, bEst, bE)
        // Now L holds b (est=5, e=4), R holds a (est=0, e=10).
        // env = max((1*5+4) + 10, (1*0+10)) = max(19, 10) = 19
        // Different — and wrong as a cumulative envelope. Documenting the contract:
        // setLeafOrder is the caller's responsibility.
        assertEquals(19L, swapped.envOfTheta())
    }

    @Test fun `non-power-of-two task count works`() {
        // n=5 → leafBase=8, three padding leaves should stay inert.
        val t = CumulativeThetaTree(n = 5, capacity = 2)
        t.setLeafOrder(intArrayOf(0, 1, 2, 3, 4))
        t.activate(0, est = 0, taskEnergy = 1L)
        t.activate(1, est = 1, taskEnergy = 1L)
        t.activate(2, est = 2, taskEnergy = 1L)
        t.activate(3, est = 3, taskEnergy = 1L)
        t.activate(4, est = 4, taskEnergy = 1L)
        // For five unit-energy tasks at est 0..4, left-anchored at est=0 with all energies:
        // env = max chain → 0*2 + 1 + 1 + 1 + 1 + 1 = 5
        // also candidate: anchor at est=4 → 4*2 + 1 = 9
        // and anchor at est=3 with last two → 3*2 + 2 = 8
        // and anchor at est=2 with last three → 2*2 + 3 = 7
        // anchor at est=1 with last four → 1*2 + 4 = 6
        // anchor at est=0 with all five → 0 + 5 = 5
        // → max is 9.
        assertEquals(9L, t.envOfTheta())
        assertEquals(5L, t.energyOfTheta())
    }

    @Test fun `reactivation overwrites the prior contribution`() {
        val t = CumulativeThetaTree(n = 2, capacity = 1)
        t.setLeafOrder(intArrayOf(0, 1))
        t.activate(0, est = 0, taskEnergy = 100L)
        t.activate(0, est = 10, taskEnergy = 1L) // overwrite — was the same id
        t.activate(1, est = 20, taskEnergy = 1L)
        // Now: task 0 est=10 e=1, task 1 est=20 e=1.
        // env(0) = 10 + 1 = 11; env(1) = 20 + 1 = 21; env(theta) = max(11 + 1, 21) = 21.
        assertEquals(21L, t.envOfTheta())
        assertEquals(2L, t.energyOfTheta())
    }

    @Test fun `envIfActivated matches activate then deactivate`() {
        val t = CumulativeThetaTree(n = 4, capacity = 3)
        t.setLeafOrder(intArrayOf(0, 1, 2, 3))
        t.activate(0, est = 1, taskEnergy = 4L)
        t.activate(2, est = 6, taskEnergy = 5L)

        val predicted = t.envIfActivated(id = 3, est = 4, taskEnergy = 2L)
        t.activate(3, est = 4, taskEnergy = 2L)
        val actual = t.envOfTheta()
        t.deactivate(3)

        assertEquals(predicted, actual)
    }

    /** Brute-force reference: scan every non-empty subset of active tasks. O(2^n) so
     *  small inputs only. */
    private fun bruteEnv(capacity: Int, ests: IntArray, energies: LongArray, active: BooleanArray): Long {
        val n = ests.size
        var best = CumulativeThetaTree.NO_ENV
        val activeIdx = (0 until n).filter { active[it] }
        val m = activeIdx.size
        if (m == 0) return best
        for (mask in 1 until (1 shl m)) {
            var est = Int.MAX_VALUE
            var e = 0L
            for (b in 0 until m) {
                if (mask and (1 shl b) != 0) {
                    val id = activeIdx[b]
                    if (ests[id] < est) est = ests[id]
                    e += energies[id]
                }
            }
            val env = capacity.toLong() * est + e
            if (env > best) best = env
        }
        return best
    }

    @Test fun `randomized envelopes match brute force`() {
        val rng = Random(0x7C0FEE)
        repeat(200) {
            val n = 1 + rng.nextInt(7) // up to 7 tasks: 2^7 = 128 subsets
            val capacity = 1 + rng.nextInt(5)
            val ests = IntArray(n) { rng.nextInt(20) }
            val energies = LongArray(n) { rng.nextLong(1, 10) }
            val active = BooleanArray(n) { rng.nextBoolean() }

            // Build leaf positions = argsort of ests (ascending; ties broken by id).
            val order = (0 until n).sortedWith(compareBy({ id -> ests[id] }, { id -> id }))
            val leafPos = IntArray(n)
            for ((leafIdx, id) in order.withIndex()) leafPos[id] = leafIdx

            val tree = CumulativeThetaTree(n = n, capacity = capacity)
            tree.setLeafOrder(leafPos)
            for (id in 0 until n) if (active[id]) tree.activate(id, ests[id], energies[id])

            val expected = bruteEnv(capacity, ests, energies, active)
            val got = tree.envOfTheta()
            assertEquals(
                expected,
                got,
                "mismatch: n=$n cap=$capacity ests=${ests.toList()} e=${energies.toList()} active=${active.toList()}",
            )

            val expectedE = (0 until n).filter { id -> active[id] }.sumOf { id -> energies[id] }
            assertEquals(expectedE, tree.energyOfTheta())
        }
    }
}
