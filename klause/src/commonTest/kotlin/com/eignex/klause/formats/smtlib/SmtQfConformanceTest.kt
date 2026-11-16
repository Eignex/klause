package com.eignex.klause.formats.smtlib

import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.solver.pipeline.OpenTheoryResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SmtQfConformanceTest {
    @Test
    fun `function bitvector results address reconstructed arrays`() {
        val parsed = SmtLib.parse("""
            (declare-sort U 0)
            (declare-const u U)
            (declare-fun address (U) (_ BitVec 2))
            (declare-const a (Array (_ BitVec 2) Bool))
            (assert (= (address u) #b10))
            (assert (select a (address u)))
        """.trimIndent())

        val assignment = assertIs<OpenTheoryResult.Sat>(BacktrackSolver.source(parsed.source).solve()).assignment
        val values = parsed.reconstructModel(assignment).values.associate { it.first.name to it.second }

        val address = assertIs<SmtValue.Function>(values.getValue("address"))
        val index = address.entries.lastOrNull { it.first == listOf(values.getValue("u")) }?.second ?: address.default
        assertEquals(SmtValue.BitVector("10"), index)
        val array = assertIs<SmtValue.Array>(values.getValue("a"))
        assertEquals(SmtValue.Boolean(true), array.entries.lastOrNull { it.first == index }?.second ?: array.default)
        assertEquals(setOf("u", "address", "a"), values.keys)
        assertTrue(parsed.intVarNames.isEmpty())
    }

    @Test
    fun `array stores use interpreted bitvector equality for function indexes`() {
        val parsed = SmtLib.parse("""
            (declare-sort U 0)
            (declare-const u U)
            (declare-fun address (U) (_ BitVec 2))
            (declare-const a (Array (_ BitVec 2) Bool))
            (assert (= (address u) (bvadd #b01 #b01)))
            (assert (not (select (store a #b10 true) (address u))))
        """.trimIndent())

        val result = BacktrackSolver.source(parsed.source).solve()

        assertIs<OpenTheoryResult.Unsat>(result)
    }

    @Test
    fun `datatype function results retain bitvector and exact real array fields`() {
        val parsed = SmtLib.parse("""
            (declare-sort U 0)
            (declare-datatype Packet ((packet (bits (_ BitVec 2)) (weights (Array Bool Real)))))
            (declare-const u U)
            (declare-fun payload (U) Packet)
            (assert (= (bits (payload u)) #b11))
            (assert (= (select (weights (payload u)) true) (/ 1.0 3.0)))
        """.trimIndent())

        val assignment = assertIs<OpenTheoryResult.Sat>(BacktrackSolver.source(parsed.source).solve()).assignment
        val values = parsed.reconstructModel(assignment).values.associate { it.first.name to it.second }

        val payload = assertIs<SmtValue.Function>(values.getValue("payload"))
        val packet = assertIs<SmtValue.Application>(payload.entries.lastOrNull {
            it.first == listOf(values.getValue("u"))
        }?.second ?: payload.default)
        assertEquals(SmtValue.BitVector("11"), packet.arguments[0])
        val weights = assertIs<SmtValue.Array>(packet.arguments[1])
        val weight = weights.entries.lastOrNull { it.first == SmtValue.Boolean(true) }?.second ?: weights.default
        assertEquals("(/ 1 3)", assertIs<SmtValue.Real>(weight).term)
        assertEquals(setOf("u", "payload"), values.keys)
    }

    @Test
    fun `datatype array indexes preserve function congruence`() {
        val parsed = SmtLib.parse("""
            (declare-datatype Key ((first) (second)))
            (declare-fun key (Bool) Key)
            (declare-const a (Array Key (_ BitVec 2)))
            (assert (= (key true) second))
            (assert (distinct (select (store a second #b11) (key true)) #b11))
        """.trimIndent())

        val result = BacktrackSolver.source(parsed.source).solve()

        assertIs<OpenTheoryResult.Unsat>(result)
    }

    @Test
    fun `nested array models contain datatype values at bitvector indexes`() {
        val parsed = SmtLib.parse("""
            (declare-datatype Color ((red) (green)))
            (declare-const a (Array (_ BitVec 1) (Array Bool Color)))
            (assert (= (select (select a #b1) true) green))
        """.trimIndent())

        val assignment = assertIs<OpenTheoryResult.Sat>(BacktrackSolver.source(parsed.source).solve()).assignment
        val outer = assertIs<SmtValue.Array>(parsed.reconstructModel(assignment).values.single().second)

        val inner = assertIs<SmtValue.Array>(outer.entries.lastOrNull {
            it.first == SmtValue.BitVector("1")
        }?.second ?: outer.default)
        val color = inner.entries.lastOrNull { it.first == SmtValue.Boolean(true) }?.second ?: inner.default
        assertEquals("green", assertIs<SmtValue.Application>(color).constructor)
    }

    @Test
    fun `finite datatype cardinality includes bitvector fields`() {
        val parsed = SmtLib.parse("""
            (declare-datatype Box ((box (bit (_ BitVec 1)))))
            (declare-fun value (Int) Box)
            (assert (distinct (value 0) (value 1) (value 2)))
        """.trimIndent())

        val result = BacktrackSolver.source(parsed.source).solve()

        assertIs<OpenTheoryResult.Unsat>(result)
    }

    @Test
    fun `variable euclidean quotient converts into an array bitvector index`() {
        val parsed = SmtLib.parse("""
            (declare-fun numerator (Bool) Int)
            (declare-const divisor Int)
            (declare-const index (_ BitVec 4))
            (declare-const a (Array (_ BitVec 4) Bool))
            (assert (= (numerator true) (- 7)))
            (assert (= divisor 3))
            (assert (= index ((_ int_to_bv 4) (div (numerator true) divisor))))
            (assert (select (store a #b1101 true) index))
        """.trimIndent())

        val assignment = assertIs<OpenTheoryResult.Sat>(BacktrackSolver.source(parsed.source).solve()).assignment
        val values = parsed.reconstructModel(assignment).values.associate { it.first.name to it.second }

        assertEquals(SmtValue.BitVector("1101"), values.getValue("index"))
        assertEquals(SmtValue.Integer("3"), values.getValue("divisor"))
    }

    @Test
    fun `exact rational array indexes agree through uninterpreted functions`() {
        val parsed = SmtLib.parse("""
            (declare-fun address (Real) Real)
            (declare-const a (Array Real Bool))
            (assert (select a (address (/ 1.0 3.0))))
            (assert (not (select a (address (/ 2.0 6.0)))))
        """.trimIndent())

        val result = BacktrackSolver.source(parsed.source).solve()

        assertIs<OpenTheoryResult.Unsat>(result)
    }

    @Test
    fun `polymorphic distinct constants respect the boolean instance`() {
        val parsed = SmtLib.parse("""
            (declare-sort-parameter A)
            (declare-const a A)
            (declare-const b A)
            (declare-const c A)
            (assert (distinct a b c))
        """.trimIndent())

        val result = BacktrackSolver.source(parsed.source).solve()

        assertIs<OpenTheoryResult.Unsat>(result)
    }

    @Test
    fun `quoted source names survive composite model reconstruction`() {
        val parsed = SmtLib.parse("""
            (declare-sort |Source sort| 0)
            (declare-const |source value| |Source sort|)
            (declare-fun |source function| (|Source sort|) (_ BitVec 1))
            (assert (= (|source function| |source value|) #b1))
        """.trimIndent())

        val assignment = assertIs<OpenTheoryResult.Sat>(BacktrackSolver.source(parsed.source).solve()).assignment
        val model = parsed.reconstructModel(assignment)

        assertEquals(listOf("|source value|", "|source function|"), model.values.map { it.first.name })
        val function = assertIs<SmtValue.Function>(model.values[1].second)
        val argument = model.values[0].second
        val result = function.entries.lastOrNull { it.first == listOf(argument) }?.second ?: function.default
        assertEquals(SmtValue.BitVector("1"), result)
    }

    @Test
    fun `indexed divisibility composes with datatype bitvector array values`() {
        val parsed = SmtLib.parse("""
            (declare-datatype Tag ((tag (bit (_ BitVec 1)))))
            (declare-const a (Array Bool Tag))
            (assert (= (select a true) (tag #b1)))
            (assert ((_ divisible 18446744073709551618) 36893488147419103236))
        """.trimIndent())

        val assignment = assertIs<OpenTheoryResult.Sat>(BacktrackSolver.source(parsed.source).solve()).assignment
        val array = assertIs<SmtValue.Array>(parsed.reconstructModel(assignment).values.single().second)

        val tag = assertIs<SmtValue.Application>(array.entries.lastOrNull {
            it.first == SmtValue.Boolean(true)
        }?.second ?: array.default)
        assertEquals(listOf(SmtValue.BitVector("1")), tag.arguments)
    }

    @Test
    fun `mixed numeric conditionals retain real array index sorts`() {
        val parsed = SmtLib.parse("""
            (declare-datatype Unit ((unit)))
            (declare-const a (Array Real Bool))
            (declare-const c Bool)
            (declare-const bit (_ BitVec 1))
            (assert (not c))
            (assert (= bit #b1))
            (assert (select (store a 0.5 true) (ite c 0 0.5)))
        """.trimIndent())

        val result = BacktrackSolver.source(parsed.source).solve()

        assertIs<OpenTheoryResult.Sat>(result)
    }

    @Test
    fun `qualified indexed operators retain their declared result sorts`() {
        val parsed = SmtLib.parse("""
            (declare-datatype Tag ((tag (bit (_ BitVec 1)))))
            (assert ((as (_ is tag) Bool) (tag #b1)))
            (assert (= ((as (_ extract 0 0) (_ BitVec 1)) #b11) #b1))
            (assert ((as (_ divisible 2) Bool) 4))
        """.trimIndent())

        val result = BacktrackSolver.source(parsed.source).solve()

        assertIs<OpenTheoryResult.Sat>(result)
    }
}
