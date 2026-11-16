package com.eignex.klause.lowering

import kotlin.test.Test
import kotlin.test.assertEquals

class BitVectorCircuitTest {
    private val circuit = BitVectorCircuit(object : BitVectorGates<Boolean> {
        override val zero = false
        override val one = true
        override fun not(value: Boolean): Boolean = !value
        override fun and(left: Boolean, right: Boolean): Boolean = left && right
        override fun or(left: Boolean, right: Boolean): Boolean = left || right
        override fun xor(left: Boolean, right: Boolean): Boolean = left != right
        override fun ite(condition: Boolean, yes: Boolean, no: Boolean): Boolean = if (condition) yes else no
    })

    @Test
    fun `bitwise circuits should preserve every small word bit`() {
        for (width in 1..4) {
            val mask = (1 shl width) - 1
            for (a in 0..mask) {
                assertEquals(a xor mask, value(circuit.not(bits(a, width))))
                assertEquals((-a) and mask, value(circuit.negate(bits(a, width))))
                for (b in 0..mask) {
                    assertEquals(a and b, value(circuit.and(bits(a, width), bits(b, width))))
                    assertEquals(a or b, value(circuit.or(bits(a, width), bits(b, width))))
                    assertEquals(a xor b, value(circuit.xor(bits(a, width), bits(b, width))))
                    assertEquals(a, value(circuit.ite(true, bits(a, width), bits(b, width))))
                    assertEquals(b, value(circuit.ite(false, bits(a, width), bits(b, width))))
                }
            }
        }
    }

    @Test
    fun `indexed wiring should agree with integer slices extensions and rotations`() {
        for (width in 1..4) for (a in 0 until (1 shl width)) {
            val word = bits(a, width)
            for (low in 0 until width) for (high in low until width) {
                assertEquals((a ushr low) and ((1 shl (high - low + 1)) - 1), value(circuit.extract(word, high, low)))
            }
            for (count in 0..3) {
                assertEquals(a, value(circuit.extend(word, count, false)))
                assertEquals(signed(a, width) and ((1 shl (width + count)) - 1),
                    value(circuit.extend(word, count, true)))
            }
            for (count in 1..3) {
                assertEquals((0 until count).sumOf { a shl (it * width) }, value(circuit.repeat(word, count)))
            }
            for (amount in 0..2 * width) {
                val distance = amount % width
                val mask = (1 shl width) - 1
                assertEquals(((a shl distance) or (a ushr (width - distance))) and mask,
                    value(circuit.rotate(word, amount, false)))
                assertEquals(((a ushr distance) or (a shl (width - distance))) and mask,
                    value(circuit.rotate(word, amount, true)))
            }
        }
    }

    @Test
    fun `concatenation should retain unequal operand widths`() {
        for (highWidth in 1..4) for (lowWidth in 1..4) {
            for (high in 0 until (1 shl highWidth)) for (low in 0 until (1 shl lowWidth)) {
                assertEquals((high shl lowWidth) + low,
                    value(circuit.concat(bits(high, highWidth), bits(low, lowWidth))))
            }
        }
    }

    @Test
    fun `small unsigned arithmetic should agree with modular integers`() {
        for (width in 1..4) {
            val modulus = 1 shl width
            for (a in 0 until modulus) for (b in 0 until modulus) {
                val left = bits(a, width)
                val right = bits(b, width)
                assertEquals((a + b) % modulus, value(circuit.add(left, right)))
                assertEquals((a - b + modulus) % modulus, value(circuit.subtract(left, right)))
                assertEquals((a * b) % modulus, value(circuit.multiply(left, right)))
            }
        }
    }

    @Test
    fun `unsigned division should include the specified zero divisor values`() {
        for (width in 1..4) {
            val modulus = 1 shl width
            for (a in 0 until modulus) for (b in 0 until modulus) {
                val (quotient, remainder) = circuit.unsignedDivRem(bits(a, width), bits(b, width))
                assertEquals(if (b == 0) modulus - 1 else a / b, value(quotient))
                assertEquals(if (b == 0) a else a % b, value(remainder))
            }
        }
    }

    @Test
    fun `signed division should truncate toward zero and wrap minimum divided by minus one`() {
        for (width in 1..4) {
            val modulus = 1 shl width
            for (a in 0 until modulus) for (b in 0 until modulus) {
                val x = signed(a, width)
                val y = signed(b, width)
                val quotient = if (y == 0) if (x < 0) 1 else -1 else x / y
                assertEquals(quotient and (modulus - 1), value(circuit.signedDivide(bits(a, width), bits(b, width))))
            }
        }
    }

    @Test
    fun `signed remainder should follow the dividend while modulo follows the divisor`() {
        for (width in 1..4) {
            val modulus = 1 shl width
            for (a in 0 until modulus) for (b in 0 until modulus) {
                val x = signed(a, width)
                val y = signed(b, width)
                val remainder = if (y == 0) x else x % y
                val modulo = if (remainder != 0 && (x < 0) != (y < 0)) remainder + y else remainder
                assertEquals(remainder and (modulus - 1),
                    value(circuit.signedRemainder(bits(a, width), bits(b, width))))
                assertEquals(modulo and (modulus - 1), value(circuit.signedModulo(bits(a, width), bits(b, width))))
            }
        }
    }

    @Test
    fun `comparisons should distinguish signed and unsigned order`() {
        for (width in 1..4) for (a in 0 until (1 shl width)) for (b in 0 until (1 shl width)) {
            assertEquals(a == b, circuit.equal(bits(a, width), bits(b, width)))
            assertEquals(a < b, circuit.unsignedLess(bits(a, width), bits(b, width)))
            assertEquals(signed(a, width) < signed(b, width), circuit.signedLess(bits(a, width), bits(b, width)))
        }
    }

    @Test
    fun `shifts should fill oversized distances without machine shift masking`() {
        for (width in 1..4) {
            val mask = (1 shl width) - 1
            for (a in 0..mask) for (b in 0..mask) {
                assertEquals((a shl b) and mask, value(circuit.shiftLeft(bits(a, width), bits(b, width))))
                assertEquals(a ushr b, value(circuit.logicalShiftRight(bits(a, width), bits(b, width))))
                assertEquals((signed(a, width) shr b) and mask,
                    value(circuit.arithmeticShiftRight(bits(a, width), bits(b, width))))
            }
        }
    }

    @Test
    fun `overflow predicates should check full mathematical results`() {
        for (width in 1..4) {
            val modulus = 1 shl width
            val minimum = -modulus / 2
            val maximum = modulus / 2 - 1
            for (a in 0 until modulus) {
                assertEquals(signed(a, width) == minimum, circuit.negateOverflow(bits(a, width)))
                for (b in 0 until modulus) {
                    val sum = signed(a, width) + signed(b, width)
                    val product = signed(a, width) * signed(b, width)
                    val difference = signed(a, width) - signed(b, width)
                    assertEquals(a + b >= modulus, circuit.unsignedAddOverflow(bits(a, width), bits(b, width)))
                    assertEquals(sum !in minimum..maximum, circuit.signedAddOverflow(bits(a, width), bits(b, width)))
                    assertEquals(a * b >= modulus, circuit.unsignedMultiplyOverflow(bits(a, width), bits(b, width)))
                    assertEquals(product !in minimum..maximum,
                        circuit.signedMultiplyOverflow(bits(a, width), bits(b, width)))
                    assertEquals(a < b, circuit.unsignedSubtractOverflow(bits(a, width), bits(b, width)))
                    assertEquals(difference !in minimum..maximum,
                        circuit.signedSubtractOverflow(bits(a, width), bits(b, width)))
                    assertEquals(signed(a, width) == minimum && signed(b, width) == -1,
                        circuit.signedDivideOverflow(bits(a, width), bits(b, width)))
                }
            }
        }
    }

    @Test
    fun `wiring operations should retain every bit beyond machine width`() {
        val wide = List(129) { it == 128 || it == 63 || it == 0 }

        assertEquals(List(129) { it == 128 || it == 63 || it == 0 },
            circuit.extract(circuit.concat(wide, wide), 257, 129))
        assertEquals(wide + List(129) { true }, circuit.extend(wide, 129, true))
        assertEquals(wide + List(129) { false }, circuit.extend(wide, 129, false))
        assertEquals(wide + wide + wide, circuit.repeat(wide, 3))
        assertEquals(wide, circuit.rotate(circuit.rotate(wide, Int.MAX_VALUE, true), Int.MAX_VALUE, false))
        assertEquals(List(129) { false }, circuit.shiftLeft(wide, List(129) { it == 128 }))
    }

    private fun bits(value: Int, width: Int): List<Boolean> = List(width) { value and (1 shl it) != 0 }

    private fun value(bits: List<Boolean>): Int = bits.indices.sumOf { if (bits[it]) 1 shl it else 0 }

    private fun signed(value: Int, width: Int): Int =
        if (value and (1 shl (width - 1)) == 0) value else value - (1 shl width)
}
