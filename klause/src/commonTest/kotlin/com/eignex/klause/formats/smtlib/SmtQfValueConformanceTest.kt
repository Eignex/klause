package com.eignex.klause.formats.smtlib

import com.eignex.klause.solver.Sample
import kotlin.test.Test
import kotlin.test.assertEquals

class SmtQfValueConformanceTest {
    @Test
    fun `core queries preserve boolean associativity and polymorphic equality`() {
        assertValues(listOf(
            "true" to "true",
            "false" to "false",
            "(not true)" to "false",
            "(=> false true false)" to "true",
            "(=> true true false)" to "false",
            "(and true false true)" to "false",
            "(and true true true)" to "true",
            "(or false true false)" to "true",
            "(or false false false)" to "false",
            "(xor true true true)" to "true",
            "(xor true false true)" to "false",
            "(= 7 7 7)" to "true",
            "(= 7 7 8)" to "false",
            "(distinct 1 2 3)" to "true",
            "(distinct 1 2 1)" to "false",
            "(ite false #b0 #b1)" to "#b1",
            "(ite true #b0 #b1)" to "#b0",
        ))
    }

    @Test
    fun `integer queries retain exact arithmetic and euclidean signs`() {
        assertValues(listOf(
            "18446744073709551618" to "18446744073709551618",
            "(- 7)" to "(- 7)",
            "(- 8 2 1)" to "5",
            "(+ 1 2 3)" to "6",
            "(* 2 (- 3) 4)" to "(- 24)",
            "(abs (- 7))" to "7",
            "(div (- 7) (- 3))" to "3",
            "(mod (- 7) (- 3))" to "2",
            "(div (- 7) 3)" to "(- 3)",
            "(mod (- 7) 3)" to "2",
            "(div 7 (- 3))" to "(- 2)",
            "(mod 7 (- 3))" to "1",
            "(div 25 2 3)" to "4",
            "(< (- 1) 0 1)" to "true",
            "(< 0 0 1)" to "false",
            "(<= 0 0 1)" to "true",
            "(<= 0 1 0)" to "false",
            "(> 1 0 (- 1))" to "true",
            "(> 1 0 0)" to "false",
            "(>= 1 0 0)" to "true",
            "(>= 1 0 1)" to "false",
        ))
    }

    @Test
    fun `integer exponent queries retain zero and negative exponent conventions`() {
        assertValues(listOf(
            "(** 2 5)" to "32",
            "(** 0 0)" to "1",
            "(** 0 (- 2))" to "0",
            "(** (- 2) (- 3))" to "0",
            "(** (- 1) (- 3))" to "(- 1)",
            "(** (- 1) (- 2))" to "1",
            "((_ divisible 18446744073709551618) 36893488147419103236)" to "true",
            "((_ divisible 3) 7)" to "false",
            "((as (_ |divisible| 3) Bool) 6)" to "true",
        ))
    }

    @Test
    fun `real and mixed queries retain exact rational and floor values`() {
        assertValues(listOf(
            "0.5" to "(/ 1.0 2.0)",
            "(- 0.5)" to "(- (/ 1.0 2.0))",
            "(- 2.0 0.5 0.25)" to "(/ 5.0 4.0)",
            "(+ 0.5 0.25)" to "(/ 3.0 4.0)",
            "(* 0.5 0.5)" to "(/ 1.0 4.0)",
            "(/ 3.0 2.0 3.0)" to "(/ 1.0 2.0)",
            "(to_real 7)" to "7.0",
            "(to_int (- 1.5))" to "(- 2)",
            "(is_int 2.0)" to "true",
            "(is_int 0.5)" to "false",
            "(< 0.25 0.5 1.0)" to "true",
            "(< 0.25 0.25 1.0)" to "false",
            "(<= 0.25 0.25 1.0)" to "true",
            "(<= 0.25 1.0 0.5)" to "false",
            "(> 1.0 0.5 0.25)" to "true",
            "(> 1.0 0.25 0.25)" to "false",
            "(>= 1.0 0.25 0.25)" to "true",
            "(>= 1.0 0.25 0.5)" to "false",
        ))
    }

    @Test
    fun `bitvector logical queries preserve every specified bit`() {
        assertValues(listOf(
            "#xAc" to "#b10101100",
            "(_ bv10 4)" to "#b1010",
            "(_ |bv10| 4)" to "#b1010",
            "(bvnot #b1010)" to "#b0101",
            "(bvand #b1010 #b1100 #b1111)" to "#b1000",
            "(bvor #b1010 #b1100 #b0001)" to "#b1111",
            "(bvxor #b1010 #b1100 #b0011)" to "#b0101",
            "(bvnand #b1010 #b1100)" to "#b0111",
            "(bvnor #b1010 #b1100)" to "#b0001",
            "(bvxnor #b1010 #b1100)" to "#b1001",
            "(bvcomp #b1010 #b1010)" to "#b1",
            "(bvcomp #b1010 #b1011)" to "#b0",
        ))
    }

    @Test
    fun `bitvector arithmetic queries preserve modular and signed results`() {
        assertValues(listOf(
            "(bvneg #b1010)" to "#b0110",
            "(bvadd #b1010 #b1100 #b0001)" to "#b0111",
            "(bvsub #b1010 #b1100)" to "#b1110",
            "(bvmul #b1010 #b1100 #b0011)" to "#b1000",
            "(bvudiv #b1010 #b0011)" to "#b0011",
            "(bvurem #b1010 #b0011)" to "#b0001",
            "(bvudiv #b0011 #b0000)" to "#b1111",
            "(bvurem #b1010 #b0000)" to "#b1010",
            "(bvsdiv #b1010 #b0011)" to "#b1110",
            "(bvsdiv #b1010 #b0000)" to "#b0001",
            "(bvsdiv #b0011 #b0000)" to "#b1111",
            "(bvsdiv #b1000 #b1111)" to "#b1000",
            "(bvsrem #b1010 #b0100)" to "#b1110",
            "(bvsrem #b1010 #b0000)" to "#b1010",
            "(bvsmod #b1010 #b0100)" to "#b0010",
            "(bvsmod #b1010 #b0000)" to "#b1010",
        ))
    }

    @Test
    fun `bitvector wiring queries preserve widths and arbitrary rotation indexes`() {
        assertValues(listOf(
            "(concat #b1010 #b1100)" to "#b10101100",
            "((_ extract 2 1) #b1010)" to "#b01",
            "((_ |extract| 0 0) #b11)" to "#b1",
            "((as (_ |extract| 0 0) (_ |BitVec| 1)) #b11)" to "#b1",
            "((_ zero_extend 2) #b1010)" to "#b001010",
            "((_ sign_extend 2) #b1010)" to "#b111010",
            "((_ repeat 2) #b10)" to "#b1010",
            "((_ rotate_left 1) #b1010)" to "#b0101",
            "((_ rotate_right 100000000000000000001) #b1010)" to "#b0101",
            "(bvshl #b1010 #b0001)" to "#b0100",
            "(bvshl #b1010 #b0100)" to "#b0000",
            "(bvlshr #b1010 #b0001)" to "#b0101",
            "(bvlshr #b1010 #b0100)" to "#b0000",
            "(bvashr #b1010 #b0001)" to "#b1101",
            "(bvashr #b1010 #b0100)" to "#b1111",
        ))
    }

    @Test
    fun `bitvector comparison queries distinguish signed and unsigned order`() {
        assertValues(listOf(
            "(bvult #b0011 #b1010)" to "true",
            "(bvult #b0011 #b0011)" to "false",
            "(bvule #b0011 #b0011)" to "true",
            "(bvule #b1010 #b0011)" to "false",
            "(bvugt #b1010 #b0011)" to "true",
            "(bvugt #b0011 #b0011)" to "false",
            "(bvuge #b0011 #b0011)" to "true",
            "(bvuge #b0011 #b1010)" to "false",
            "(bvslt #b1010 #b0011)" to "true",
            "(bvslt #b0011 #b1010)" to "false",
            "(bvsle #b1010 #b1010)" to "true",
            "(bvsle #b0011 #b1010)" to "false",
            "(bvsgt #b0011 #b1010)" to "true",
            "(bvsgt #b1010 #b0011)" to "false",
            "(bvsge #b1010 #b1010)" to "true",
            "(bvsge #b1010 #b0011)" to "false",
        ))
    }

    @Test
    fun `bitvector overflow queries compare mathematical results before truncation`() {
        assertValues(listOf(
            "(bvnego #b1000)" to "true",
            "(bvnego #b0000)" to "false",
            "(bvuaddo #b1111 #b0001)" to "true",
            "(bvuaddo #b0001 #b0001)" to "false",
            "(bvsaddo #b0111 #b0001)" to "true",
            "(bvsaddo #b0001 #b0001)" to "false",
            "(bvumulo #b1000 #b0010)" to "true",
            "(bvumulo #b0010 #b0011)" to "false",
            "(bvsmulo #b0100 #b0010)" to "true",
            "(bvsmulo #b0001 #b0010)" to "false",
            "(bvusubo #b0000 #b0001)" to "true",
            "(bvusubo #b0001 #b0000)" to "false",
            "(bvssubo #b1000 #b0001)" to "true",
            "(bvssubo #b0001 #b0000)" to "false",
            "(bvsdivo #b1000 #b1111)" to "true",
            "(bvsdivo #b1000 #b0001)" to "false",
        ))
    }

    @Test
    fun `bitvector conversion queries preserve signed and modular integer meanings`() {
        assertValues(listOf(
            "(ubv_to_int #b1001)" to "9",
            "(sbv_to_int #b1001)" to "(- 7)",
            "((_ int_to_bv 4) (- 7))" to "#b1001",
        ))
    }

    private fun assertValues(cases: List<Pair<String, String>>) {
        val model = SmtLib.parse("").reconstructSessionModel(Sample(booleanArrayOf(), longArrayOf()))
        for ((source, expected) in cases) {
            val term = SExprReader(source).readAll().single()

            val actual = model.evaluate(term)

            assertEquals(expected, actual, source)
        }
    }
}
