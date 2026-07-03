package com.eignex.klause.count

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.Sample
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The native int↔bits channel that replaces bit-blasting for XOR-hash counting over integers. */
class IntBitChannelTest {

    private fun ints(domains: List<IntDomain>) = Problem(
        numBoolVars = 0,
        numIntVars = domains.size,
        intDomains = domains.toTypedArray(),
        factors = arrayOf<Factor>(),
    )

    /** Value `x` reconstructed from its channel bits (least-significant first), offset by `min`. */
    private fun decode(model: Sample, min: Int, bits: IntArray): Int {
        var v = min
        for (i in bits.indices) if (model.bools[bits[i]]) v += 1 shl i
        return v
    }

    @Test
    fun `power of two domain enumerates every value exactly once with matching bits`() {
        val base = ints(listOf(IntDomain(2, 9))) // 8 values → width 3
        val ch = IntBitChannel.channel(base, intArrayOf(0))
        assertEquals(3, ch.bitsPerVar[0].size)
        assertEquals(3, ch.allBits().size)

        val seen = HashSet<Int>()
        for (m in BacktrackSolver(ch.problem).enumerate(BacktrackParams())) {
            // The original int var keeps its id, so its value is readable directly...
            val x = m.ints[0]
            // ...and the channel bits decode to the same value (the bijection the count relies on).
            assertEquals(x, decode(m, 2, ch.bitsPerVar[0]), "bits disagree with int value")
            assertTrue(seen.add(x), "value $x enumerated twice")
        }
        assertEquals((2..9).toSet(), seen)
    }

    @Test
    fun `non power of two domain prunes out of range bit patterns`() {
        // 6 values over width-3 bits: patterns 6 and 7 land outside [0,5] and must be infeasible.
        val base = ints(listOf(IntDomain(0, 5)))
        val ch = IntBitChannel.channel(base, intArrayOf(0))
        assertEquals(3, ch.bitsPerVar[0].size)

        val values = BacktrackSolver(ch.problem).enumerate(BacktrackParams()).map { it.ints[0] }.toList()
        assertEquals((0..5).toList().sorted(), values.sorted())
        assertEquals(6, values.size)
    }

    @Test
    fun `singleton domain contributes no bits`() {
        val base = ints(listOf(IntDomain(4, 4)))
        val ch = IntBitChannel.channel(base, intArrayOf(0))
        assertTrue(ch.bitsPerVar[0].isEmpty())
        assertEquals(0, ch.allBits().size)
        val values = BacktrackSolver(ch.problem).enumerate(BacktrackParams()).map { it.ints[0] }.toList()
        assertEquals(listOf(4), values)
    }

    @Test
    fun `two variables produce independent bit groups covering the product`() {
        val base = ints(listOf(IntDomain(0, 3), IntDomain(0, 2))) // 4 x 3 = 12 combos
        val ch = IntBitChannel.channel(base, intArrayOf(0, 1))
        assertEquals(2, ch.bitsPerVar[0].size) // width 2 for 0..3
        assertEquals(2, ch.bitsPerVar[1].size) // width 2 for 0..2
        assertEquals(4, ch.allBits().size)

        val combos = HashSet<Pair<Int, Int>>()
        for (m in BacktrackSolver(ch.problem).enumerate(BacktrackParams())) {
            assertEquals(m.ints[0], decode(m, 0, ch.bitsPerVar[0]))
            assertEquals(m.ints[1], decode(m, 0, ch.bitsPerVar[1]))
            combos.add(m.ints[0] to m.ints[1])
        }
        assertEquals(12, combos.size)
    }

    @Test
    fun `multi variable channel enumerates the full product`() {
        // Regression for issue 737: enumerate must yield every channel combo (it used to drop
        // models on the channel-augmented multi-int problem). Fixed seed keeps it deterministic.
        val base = ints(List(4) { IntDomain(0, 3) }) // 4^4 = 256 combos
        val ch = IntBitChannel.channel(base, intArrayOf(0, 1, 2, 3))
        val params = BacktrackParams(maxDecisions = 10_000_000L, randomSeed = 1L)
        val combos = BacktrackSolver(ch.problem).enumerate(params)
            .map { listOf(it.ints[0], it.ints[1], it.ints[2], it.ints[3]) }.toHashSet()
        assertEquals(256, combos.size)
    }

    @Test
    fun `no requested vars returns the base problem unchanged`() {
        val base = ints(listOf(IntDomain(0, 3)))
        val ch = IntBitChannel.channel(base, intArrayOf())
        assertEquals(base, ch.problem)
        assertTrue(ch.bitsPerVar.isEmpty())
    }
}
