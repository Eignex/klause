package com.eignex.klause.solver.factor

import com.eignex.klause.ast.PbOp
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.Lit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Coverage of Factor.remap (#332). The generic invariant — every variable id in the rewritten
 * factor is its image under the maps, and no others — catches any variable-bearing field a remap
 * forgot to rewrite (boolVars/intVars are derived from those fields). Targeted cases then pin the
 * footguns: constants and offsets must NOT move, Lit polarity is preserved, and sentinels survive.
 */
class FactorRemapTest {

    // Injective shifts so the var-set image is unambiguous; ids used below stay < 64.
    private val intMap = IntArray(64) { it + 100 }
    private val boolMap = IntArray(64) { it + 50 }

    private fun pos(v: Int) = Lit.make(v, true)
    private fun neg(v: Int) = Lit.make(v, false)

    private fun assertVarsShifted(name: String, f: Factor) {
        val out = f.remap(boolMap, intMap)
        assertEquals(f.intVars.map { intMap[it] }.toSet(), out.intVars.toSet(), "$name int vars")
        assertEquals(f.boolVars.map { boolMap[it] }.toSet(), out.boolVars.toSet(), "$name bool vars")
    }

    @Test
    fun `every factor remaps all of its variables`() {
        val factors: List<Pair<String, Factor>> = listOf(
            "Linear" to Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 5),
            "Clause" to Clause(intArrayOf(pos(0), neg(1))),
            "Cardinality" to Cardinality(intArrayOf(pos(0), pos(1)), 1, 2),
            "Xor" to Xor(intArrayOf(pos(0), pos(1)), 1),
            "PseudoBoolean" to PseudoBoolean(intArrayOf(1, 2), intArrayOf(pos(0), pos(1)), PbOp.LE, 2),
            "ReifiedLinear" to ReifiedLinear(2, intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 3),
            "ReifiedCardinality" to ReifiedCardinality(2, intArrayOf(pos(0), pos(1)), 1, 2),
            "ReifiedPseudoBoolean" to ReifiedPseudoBoolean(2, intArrayOf(1, 1), intArrayOf(pos(0), pos(1)), PbOp.LE, 2),
            "AllDifferent" to AllDifferent(intArrayOf(0, 1, 2), 0, 3),
            "AllDifferent(opt)" to AllDifferent(intArrayOf(0, 1), 0, 2, intArrayOf(pos(3), pos(4))),
            "Element(vars)" to Element(0, 1, intArrayOf(2, 3), arrIsVars = true, indexOffset = 1),
            "Element(const)" to Element(0, 1, intArrayOf(5, 6), arrIsVars = false, indexOffset = 1),
            "Product" to Product(0, 1, 2),
            "Table" to Table(intArrayOf(0, 1), intArrayOf(0, 0, 1, 1)),
            "ArrayMinMax" to ArrayMinMax(0, intArrayOf(1, 2), max = true),
            "Sort" to Sort(intArrayOf(0, 1), intArrayOf(2, 3)),
            "LexLess" to LexLess(intArrayOf(0, 1), intArrayOf(2, 3), strict = true),
            "Inverse" to Inverse(intArrayOf(0, 1), intArrayOf(2, 3), fOffset = 0, gOffset = 0),
            "SymmetricAllDifferent" to SymmetricAllDifferent(intArrayOf(0, 1, 2)),
            "NValue" to NValue(0, intArrayOf(1, 2, 3)),
            "Circuit" to Circuit(intArrayOf(1, 2, 0)),
            "Subcircuit" to Subcircuit(intArrayOf(0, 1, 2)),
            "Cumulative" to Cumulative(intArrayOf(0, 1), intArrayOf(2, 2), intArrayOf(1, 1), 3),
            "Cumulative(vars)" to Cumulative(
                intArrayOf(0, 1),
                intArrayOf(2, 2),
                intArrayOf(1, 1),
                3,
                durationVars = intArrayOf(2, 3),
                resourceVars = intArrayOf(4, 5),
                capacityVar = 6,
            ),
            "Disjunctive" to Disjunctive(intArrayOf(0, 1), intArrayOf(2, 2)),
            "Diffn" to Diffn(intArrayOf(0, 1), intArrayOf(2, 3), intArrayOf(1, 1), intArrayOf(1, 1)),
            "Diffn(vars)" to Diffn(
                intArrayOf(0, 1),
                intArrayOf(2, 3),
                intArrayOf(1, 1),
                intArrayOf(1, 1),
                widthVars = intArrayOf(4, 5),
                heightVars = intArrayOf(6, 7),
            ),
            "GlobalCardinality(lohi)" to GlobalCardinality(
                intArrayOf(0, 1, 2),
                intArrayOf(0, 1),
                countLow = intArrayOf(0, 0),
                countHigh = intArrayOf(3, 3),
            ),
            "GlobalCardinality(counts)" to GlobalCardinality(
                intArrayOf(0, 1, 2),
                intArrayOf(0, 1),
                countVars = intArrayOf(3, 4),
            ),
            "Regular" to Regular(intArrayOf(0, 1), 2, 2, intArrayOf(1, 2, 2, 1), 1, intArrayOf(2)),
            "GaussianXor" to GaussianXor(
                listOf(Xor(intArrayOf(pos(0), pos(1)), 1), Xor(intArrayOf(pos(1), pos(2)), 0)),
            ),
        )
        for ((name, f) in factors) assertVarsShifted(name, f)
    }

    @Test
    fun `lit polarity is preserved`() {
        val out = Clause(intArrayOf(pos(0), neg(1))).remap(boolMap, intMap) as Clause
        assertEquals(listOf(Lit.make(50, true), Lit.make(51, false)).toSet(), out.literals.toSet())
    }

    @Test
    fun `constant element array is not remapped`() {
        val out = Element(0, 1, intArrayOf(5, 6), arrIsVars = false, indexOffset = 1).remap(boolMap, intMap) as Element
        assertEquals(100, out.idx)
        assertEquals(101, out.result)
        assertTrue(out.arr.contentEquals(intArrayOf(5, 6)), "constant array must be untouched: ${out.arr.toList()}")
        assertEquals(1, out.indexOffset)
    }

    @Test
    fun `inverse offsets are preserved`() {
        val out = Inverse(
            intArrayOf(0, 1),
            intArrayOf(2, 3),
            fOffset = 7,
            gOffset = 9,
        ).remap(boolMap, intMap) as Inverse
        assertEquals(7, out.fOffset)
        assertEquals(9, out.gOffset)
    }

    @Test
    fun `cumulative capacityVar sentinel survives, real one remaps`() {
        val constCap = Cumulative(intArrayOf(0), intArrayOf(2), intArrayOf(1), 3).remap(boolMap, intMap) as Cumulative
        assertEquals(-1, constCap.capacityVar)
        val varCap = Cumulative(intArrayOf(0), intArrayOf(2), intArrayOf(1), 3, capacityVar = 6)
            .remap(boolMap, intMap) as Cumulative
        assertEquals(106, varCap.capacityVar)
    }

    @Test
    fun `null diffn size-vars stay null`() {
        val out = Diffn(intArrayOf(0), intArrayOf(1), intArrayOf(1), intArrayOf(1)).remap(boolMap, intMap) as Diffn
        assertNull(out.widthVars)
        assertNull(out.heightVars)
    }
}
