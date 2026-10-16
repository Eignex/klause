package com.eignex.klause.factor.global

import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.Term
import com.eignex.klause.ir.VarRemap
import com.eignex.klause.localsearch.LocalSearchModel
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.propagation.bake
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.incumbent.Verification
import com.eignex.klause.solver.validation.verifyRealCoordinates
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.parseBigInt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

class DistinctTest {
    @Test
    fun `distinct structural colors should preserve namespaces and affine roles`() {
        val first = Distinct(listOf(Distinct.Operand(intArrayOf(Term.ofIntVar(0),
            Term.ofLit(Lit.make(0, true)), Term.ofRealVar(0)),
            listOf(BigFraction.ofLong(2), BigFraction.ONE, BigFraction.ONE))), activator = 1)
        val second = Distinct(listOf(Distinct.Operand(intArrayOf(Term.ofIntVar(1),
            Term.ofLit(Lit.make(1, true)), Term.ofRealVar(1)),
            listOf(BigFraction.ofLong(2), BigFraction.ONE, BigFraction.ONE))), activator = 0)

        val firstHash = first.remapStructuralHash(VarRemap(intArrayOf(-1, 2), intArrayOf(-1, 3), intArrayOf(-1, 3)))
        val secondHash = second.remapStructuralHash(VarRemap(intArrayOf(2, -1), intArrayOf(3, -1), intArrayOf(3, -1)))

        assertEquals(firstHash, secondHash)
        assertNotEquals(first.structuralKey(), second.structuralKey())
    }

    @Test
    fun `distinct mapped keys should coalesce affine aliases before hashing`() {
        val source = Distinct(listOf(Distinct.Operand(intArrayOf(Term.ofIntVar(0), Term.ofIntVar(1)),
            listOf(BigFraction.ONE, BigFraction.MINUS_ONE))))
        val mapping = VarRemap(intArrayOf(), intArrayOf(0, 0))

        val hash = source.remapStructuralHash(mapping)

        assertEquals(source.remap(mapping).structuralKey().hashCode(), hash)
    }

    @Test
    fun `finite distinct should compare arbitrary width rational affine values exactly`() {
        val coefficient = BigFraction.of(parseBigInt("100000000000000000000000000000000000000000000000001"), BIG_ONE)
        val third = BigFraction.of(BIG_ONE, bigIntOf(3))
        val relation = Distinct(listOf(
            Distinct.Operand(intArrayOf(Term.ofIntVar(0)), listOf(coefficient), third),
            Distinct.Operand(intArrayOf(Term.ofIntVar(1)), listOf(coefficient), third),
        ))
        val source = Problem(0, 2, arrayOf(IntDomain(0, 1), IntDomain(0, 1)), arrayOf(relation))

        val points = BacktrackSolver(source.bake()).enumerate().map { it.intValue(0) to it.intValue(1) }.toSet()

        assertEquals(setOf(0L to 1L, 1L to 0L), points)
    }

    @Test
    fun `reified finite distinct should preserve both truth directions`() {
        val relation = Distinct(listOf(
            Distinct.Operand(intArrayOf(Term.ofIntVar(0)), listOf(BigFraction.ONE)),
            Distinct.Operand(intArrayOf(Term.ofIntVar(1)), listOf(BigFraction.ONE)),
        ), activator = 0)
        val source = Problem(1, 2, arrayOf(IntDomain(0, 1), IntDomain(0, 1)), arrayOf(relation))

        val points = BacktrackSolver(source.bake()).enumerate().map {
            Triple(it.intValue(0), it.intValue(1), it.boolValue(0))
        }.toSet()

        assertEquals(setOf(Triple(0L, 0L, false), Triple(0L, 1L, true),
            Triple(1L, 0L, true), Triple(1L, 1L, false)), points)
    }

    @Test
    fun `complemented Boolean operands should retain their original truth values`() {
        val relation = Distinct(listOf(
            Distinct.Operand(intArrayOf(Term.ofLit(Lit.make(0, true))), listOf(BigFraction.ONE)),
            Distinct.Operand(intArrayOf(Term.ofLit(Lit.make(1, false))), listOf(BigFraction.ONE)),
        ))
        val source = Problem(2, 0, emptyArray(), arrayOf(relation))

        val points = BacktrackSolver(source.bake()).enumerate().map { it.boolValue(0) to it.boolValue(1) }.toSet()

        assertEquals(setOf(false to false, true to true), points)
    }

    @Test
    fun `distinct operands should capture caller and getter buffers`() {
        val references = intArrayOf(Term.ofIntVar(0))
        val coefficients = mutableListOf(BigFraction.ONE)
        val operand = Distinct.Operand(references, coefficients)
        val operands = mutableListOf(operand, Distinct.Operand(intArrayOf(), emptyList(), BigFraction.ONE))
        val relation = Distinct(operands)
        val source = Problem(0, 1, arrayOf(IntDomain(0, 2)), arrayOf(relation))
        references[0] = Term.ofIntVar(99)
        coefficients[0] = BigFraction.ZERO
        operand.references[0] = Term.ofIntVar(99)
        operands.clear()

        val values = BacktrackSolver(source.bake()).enumerate().map { it.intValue(0) }.sorted().toList()

        assertEquals(listOf(0L, 2L), values)
    }

    @Test
    fun `remapping distinct operands onto one coordinate should retain the collision`() {
        val relation = Distinct(listOf(
            Distinct.Operand(intArrayOf(Term.ofIntVar(0)), listOf(BigFraction.ONE)),
            Distinct.Operand(intArrayOf(Term.ofIntVar(1)), listOf(BigFraction.ONE)),
        ))
        val image = relation.remap(VarRemap(intArrayOf(), intArrayOf(0, 0)))
        val source = Problem(0, 1, arrayOf(IntDomain(0, 1)), arrayOf(image))

        val result = BacktrackSolver(source.bake()).solve()

        assertIs<SolveResult.Unsat>(result)
    }

    @Test
    fun `affine collision score deltas should agree with fresh complete grading`() {
        val coefficient = BigFraction.of(parseBigInt("100000000000000000001"), BIG_ONE)
        val relation = Distinct((0..2).map {
            Distinct.Operand(intArrayOf(Term.ofIntVar(it)), listOf(coefficient))
        })
        val source = Problem(0, 3, Array(3) { IntDomain(0, 2) }, arrayOf(relation))
        val state = LocalSearchState(LocalSearchModel.open(source), Random(3))
        for (variable in 0..2) state.assignment.setInt(variable, 0)
        state.recompute()
        val invariant = relation.scoringView()
        val before = invariant.violationDegree(state, 0)

        val delta = invariant.deltaIfIntSet(state, 0, 0, 1)
        state.assignment.setInt(0, 1)
        state.recompute()

        assertEquals(invariant.violationDegree(state, 0) - before, delta)
        assertEquals(-1, delta)
    }

    @Test
    fun `original real witness checking should reject an exact affine collision`() {
        val coefficient = parseBigInt("100000000000000000000000000000000000000000000000001")
        val relation = Distinct(listOf(
            Distinct.Operand(intArrayOf(Term.ofRealVar(0)), listOf(BigFraction.of(coefficient, BIG_ONE))),
            Distinct.Operand(intArrayOf(), emptyList(), BigFraction.ONE),
        ))
        val source = Problem(0, 0, emptyArray(), arrayOf(relation), numRealVars = 1,
            realLower = doubleArrayOf(Double.NEGATIVE_INFINITY),
            realUpper = doubleArrayOf(Double.POSITIVE_INFINITY))
        val value = BigFraction.of(BIG_ONE, coefficient)
        val point = Sample(booleanArrayOf(), longArrayOf(), doubleArrayOf(value.toDouble()),
            exactReals = listOf(value))

        val result = verifyRealCoordinates(source, point)

        assertIs<Verification.Rejected>(result)
    }

    @Test
    fun `original integer witness checking should preserve arbitrary width affine collisions`() {
        val coefficient = parseBigInt("100000000000000000000000000000000000000000000000001")
        val relation = Distinct(listOf(
            Distinct.Operand(intArrayOf(Term.ofIntVar(0)), listOf(BigFraction.ONE)),
            Distinct.Operand(intArrayOf(), emptyList(), BigFraction.of(coefficient, BIG_ONE)),
        ))
        val source = Problem(0, 1, arrayOf(IntDomain(0, 1)), arrayOf(relation),
            openIntLo = booleanArrayOf(true), openIntHi = booleanArrayOf(true))
        val point = Sample(booleanArrayOf(), longArrayOf(), exactInts = listOf(coefficient))

        val result = verifyRealCoordinates(source, point)

        assertIs<Verification.Rejected>(result)
    }

    @Test
    fun `original distinct checking should reject missing Boolean coordinates`() {
        val relation = Distinct(listOf(
            Distinct.Operand(intArrayOf(Term.ofLit(Lit.make(0, true))), listOf(BigFraction.ONE)),
            Distinct.Operand(intArrayOf(), emptyList(), BigFraction.ONE),
        ))
        val source = Problem(1, 0, emptyArray(), arrayOf(relation))
        val point = Sample(booleanArrayOf(), longArrayOf())

        val result = verifyRealCoordinates(source, point)

        assertIs<Verification.Rejected>(result)
    }
}
