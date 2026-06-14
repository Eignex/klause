package com.eignex.klause.util

import java.math.BigInteger
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** [BigInt] validated against `java.math.BigInteger` as an oracle (JVM-only). */
class BigIntOracleTest {

    private fun BigInt.oracle() = BigInteger(toString())

    @Test
    fun `of and toString round-trip across the Long range`() {
        val edge = longArrayOf(
            0, 1, -1, 2, -2, Long.MAX_VALUE, Long.MIN_VALUE, Long.MIN_VALUE + 1, 1234567890123L, -987654321L,
        )
        for (v in edge) assertEquals(v.toString(), BigInt.of(v).toString(), "of($v)")
        val rng = Random(1)
        repeat(2000) {
            val v = rng.nextLong()
            assertEquals(v.toString(), BigInt.of(v).toString())
            assertEquals(v, BigInt.of(v).toLongOrNull(), "toLong($v)")
        }
    }

    @Test
    fun `arithmetic matches the oracle on accumulated large values`() {
        val rng = Random(7)
        repeat(400) {
            var a = BigInt.of(rng.nextLong(-1_000_000, 1_000_000))
            var oa = a.oracle()
            // Grow well past 64 bits through a random op chain.
            repeat(rng.nextInt(1, 12)) {
                val k = BigInt.of(rng.nextLong(-1_000_000_000, 1_000_000_000))
                val ok = k.oracle()
                when (rng.nextInt(3)) {
                    0 -> {
                        a += k
                        oa += ok
                    }

                    1 -> {
                        a -= k
                        oa -= ok
                    }

                    else -> {
                        a *= k
                        oa *= ok
                    }
                }
                assertEquals(oa.toString(), a.toString(), "op chain")
            }
            // Compare ordering and gcd against a second large value.
            var b = BigInt.of(rng.nextLong())
            var ob = b.oracle()
            repeat(rng.nextInt(0, 6)) {
                val k = BigInt.of(rng.nextLong(-1_000_000_000, 1_000_000_000))
                b *= k
                ob *= k.oracle()
            }
            assertEquals(oa.compareTo(ob).coerceIn(-1, 1), a.compareTo(b).coerceIn(-1, 1), "compare")
            assertEquals(oa.gcd(ob).toString(), a.gcd(b).toString(), "gcd")
        }
    }

    @Test
    fun `divide and remainder match the oracle including signs`() {
        val rng = Random(13)
        repeat(3000) {
            // Build dividend and divisor of varied magnitude (divisor non-zero).
            var a = BigInt.of(rng.nextLong())
            var oa = a.oracle()
            repeat(
                rng.nextInt(0, 4),
            ) {
                val k = rng.nextLong(-1_000_000, 1_000_000)
                a *= BigInt.of(k)
                oa *= BigInteger.valueOf(k)
            }
            var dv = rng.nextLong()
            if (dv == 0L) dv = 1L
            var d = BigInt.of(dv)
            var od = BigInteger.valueOf(dv)
            repeat(
                rng.nextInt(0, 3),
            ) {
                val k = rng.nextLong(1, 1_000_000)
                d *= BigInt.of(k)
                od *= BigInteger.valueOf(k)
            }
            if (d.isZero) return@repeat
            val (q, r) = a.divideAndRemainder(d)
            val (oq, orr) = oa.divideAndRemainder(od)
            assertEquals(oq.toString(), q.toString(), "quotient $oa / $od")
            assertEquals(orr.toString(), r.toString(), "remainder $oa % $od")
            // Reconstruct: q*d + r == a.
            assertEquals(a.toString(), (q * d + r).toString(), "reconstruct")
        }
    }

    @Test
    fun `divmod with a single-limb divisor whose high bit is set`() {
        // Regression: a one-limb divisor in [2^31, 2^32) overflowed the signed-Long `rem*2^32+limb`.
        val rng = Random(31)
        repeat(3000) {
            var a = BigInt.of(rng.nextLong())
            var oa = a.oracle()
            repeat(rng.nextInt(0, 5)) {
                val k = rng.nextLong()
                a *= BigInt.of(k)
                oa *= BigInteger.valueOf(k)
            }
            val dv = rng.nextLong(1L shl 31, 1L shl 32) // single 32-bit limb, high bit set
            val d = BigInt.of(dv)
            val (q, r) = a.divideAndRemainder(d)
            val (oq, orr) = oa.divideAndRemainder(BigInteger.valueOf(dv))
            assertEquals(oq.toString(), q.toString(), "q $oa / $dv")
            assertEquals(orr.toString(), r.toString(), "r $oa % $dv")
        }
    }

    @Test
    fun `divExact returns the exact quotient`() {
        val rng = Random(29)
        repeat(1000) {
            val q = BigInt.of(rng.nextLong(-1_000_000, 1_000_000))
            var d = BigInt.of(rng.nextLong(1, 1_000_000))
            repeat(rng.nextInt(0, 3)) { d *= BigInt.of(rng.nextLong(1, 1000)) }
            if (d.isZero) return@repeat
            val product = q * d
            assertEquals(q.toString(), product.divExact(d).toString())
        }
        assertTrue(BigInt.of(6).divExact(BigInt.of(3)) == BigInt.of(2))
    }
}
