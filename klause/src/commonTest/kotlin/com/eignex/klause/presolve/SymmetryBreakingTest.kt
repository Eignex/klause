package com.eignex.klause.presolve

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.bool.PseudoBoolean
import com.eignex.klause.factor.symmetry.SymmetryHandling
import com.eignex.klause.factor.table.Regular
import com.eignex.klause.factor.table.Table
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.VarRemap
import com.eignex.klause.localsearch.NoInvariant
import com.eignex.klause.localsearch.invariantProjection
import com.eignex.klause.model.PbOp
import com.eignex.klause.presolve.PresolveShared.withPassDelta
import com.eignex.klause.presolve.PresolveShared.withSourcePassDelta
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.bake
import com.eignex.klause.propagation.propagate
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.parseBigInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SymmetryBreakingTest {
    private fun isFeasible(problem: Problem, bools: BooleanArray, ints: LongArray): Boolean {
        var a = Assumptions.None
        for (v in 0 until problem.numBoolVars) a = a.withBool(v, bools[v])
        for (v in 0 until problem.numIntVars) a = a.withInt(v, ints[v])
        return problem.propagate(a) !is PropagationResult.Unsat
    }

    private fun countFeasible(problem: Problem): Int {
        val b = problem.numBoolVars
        val n = problem.numIntVars
        val ints = LongArray(n) { problem.finiteIntDomain(it).min }
        var count = 0
        while (true) {
            for (mask in 0 until (1 shl b).coerceAtLeast(1)) {
                val bools = BooleanArray(b) { (mask shr it) and 1 == 1 }
                if (isFeasible(problem, bools, ints.copyOf())) count++
            }
            var i = 0
            while (i < n) {
                ints[i]++
                if (ints[i] <= problem.finiteIntDomain(i).max) break
                ints[i] = problem.finiteIntDomain(i).min
                i++
            }
            if (i == n) break
        }
        return count
    }

    private fun broken(problem: Problem): Problem =
        problem.bake().let { it.withPassDelta(Presolve.breakSymmetries(it), BakeConfig.NONE) }

    private fun precedence(problem: Problem): Problem =
        problem.bake().let { it.withPassDelta(Presolve.breakValuePrecedence(it), BakeConfig.NONE) }

    private fun checkSound(name: String, problem: Problem, expectReduced: Boolean) {
        val broken = broken(problem)
        val orig = countFeasible(problem)
        val after = countFeasible(broken)
        assertTrue(after <= orig, "$name: breaking ADDED solutions ($orig -> $after)")
        assertEquals(orig > 0, after > 0, "$name: breaking changed satisfiability ($orig -> $after)")
        if (expectReduced) {
            assertTrue(after < orig, "$name: expected fewer solutions but $orig -> $after")
        } else {
            assertTrue(Presolve.breakSymmetries(problem.bake()).isEmpty, "$name: expected no symmetry detected")
        }
    }

    private fun pos(v: Int) = Lit.make(v, true)

    @Test
    fun `symmetry breaking should keep a row-symmetric matrix satisfiable`() {
        // Ordering each column orbit independently would cut both solutions of this single orbit.
        fun eq(a: Int, b: Int) = listOf(
            Clause(intArrayOf(Lit.make(a, false), Lit.make(b, true))),
            Clause(intArrayOf(Lit.make(a, true), Lit.make(b, false))),
        )
        fun neq(a: Int, b: Int) = listOf(
            Clause(intArrayOf(Lit.make(a, true), Lit.make(b, true))),
            Clause(intArrayOf(Lit.make(a, false), Lit.make(b, false))),
        )
        val problem = Problem(4, 0, emptyArray(), eq(0, 3) + eq(1, 2) + neq(0, 1) + neq(2, 3))
        val broken = broken(problem)
        assertEquals(
            countFeasible(problem) > 0,
            countFeasible(broken) > 0,
            "breaking made a satisfiable orbit UNSAT",
        )
    }

    @Test
    fun `generator search should break a composite bool-int symmetry`() {
        val problem = Problem(
            numBoolVars = 2,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 2), IntDomain(0, 2)),
            factors = listOf(
                ReifiedLinear(
                    auxBoolVar = 0,
                    coeffs = intArrayOf(1),
                    vars = intArrayOf(0),
                    op = LinearOp.EQ,
                    bound = 1,
                ),
                ReifiedLinear(
                    auxBoolVar = 1,
                    coeffs = intArrayOf(1),
                    vars = intArrayOf(1),
                    op = LinearOp.EQ,
                    bound = 1,
                ),
            ),
        )
        checkSound("composite bool-int", problem, expectReduced = true)
    }

    @Test
    fun `value precedence should keep one solution per value-symmetry class`() {
        // Bell(3) = 5 set partitions of three variables.
        val problem = Problem(0, 3, Array(3) { IntDomain(0, 2) }, emptyList())
        val broken = precedence(problem)
        val orig = countFeasible(problem)
        val after = countFeasible(broken)
        assertTrue(after < orig, "expected reduction: $orig -> $after")
        assertEquals(orig > 0, after > 0)
        assertEquals(5, after)
    }

    @Test
    fun `an ordering linear should block value precedence`() {
        val problem = Problem(
            0,
            2,
            Array(2) { IntDomain(0, 2) },
            listOf(Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.LE, 0)),
        )
        assertTrue(Presolve.breakValuePrecedence(problem.bake()).isEmpty)
    }

    @Test
    fun `interchangeable matrix rows should be lex-ordered`() {
        val problem = Problem(
            0,
            4,
            arrayOf(IntDomain(0, 3), IntDomain(0, 3), IntDomain(0, 3), IntDomain(0, 3)),
            listOf(
                Linear(intArrayOf(1, 2), intArrayOf(0, 1), LinearOp.LE, 3),
                Linear(intArrayOf(1, 2), intArrayOf(2, 3), LinearOp.LE, 3),
            ),
        )
        checkSound("matrix-rows", problem, expectReduced = true)
    }

    @Test
    fun `interchangeable vars in separate isomorphic factors should be ordered`() {
        val problem = Problem(
            0,
            2,
            arrayOf(IntDomain(0, 5), IntDomain(0, 5)),
            listOf(
                Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 3),
                Linear(intArrayOf(1), intArrayOf(1), LinearOp.LE, 3),
            ),
        )
        checkSound("cross-factor", problem, expectReduced = true)
    }

    @Test
    fun `vars in asymmetric separate factors should not be grouped`() {
        val problem = Problem(
            0,
            2,
            arrayOf(IntDomain(0, 5), IntDomain(0, 5)),
            listOf(
                Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 3),
                Linear(intArrayOf(1), intArrayOf(1), LinearOp.LE, 4),
            ),
        )
        checkSound("asymmetric", problem, expectReduced = false)
    }

    @Test
    fun `vars with unequal coefficients should not be grouped`() {
        val problem = Problem(
            0,
            2,
            arrayOf(IntDomain(0, 3), IntDomain(0, 3)),
            listOf(Linear(intArrayOf(1, 2), intArrayOf(0, 1), LinearOp.LE, 3)),
        )
        checkSound("unequalCoeff", problem, expectReduced = false)
    }

    @Test
    fun `bool row symmetry should be broken by a propagator-only SymmetryHandling factor`() {
        val problem = Problem(
            6,
            0,
            emptyArray(),
            listOf(
                PseudoBoolean(longArrayOf(1, 2, 4), intArrayOf(pos(0), pos(1), pos(2)), PbOp.LE, 5L),
                PseudoBoolean(longArrayOf(1, 2, 4), intArrayOf(pos(3), pos(4), pos(5)), PbOp.LE, 5L),
            ),
        )
        val broken = broken(problem)
        assertEquals(problem.numIntVars, broken.numIntVars)
        val symmetry = broken.factors.filterIsInstance<SymmetryHandling>().single()
        assertSame(NoInvariant, symmetry.invariantProjection())
    }

    @Test
    fun `a non-relabelable table should block symmetry breaking`() {
        val problem = Problem(
            0,
            2,
            arrayOf(IntDomain(0, 1), IntDomain(0, 1)),
            listOf(Table(intArrayOf(0, 1), longArrayOf(0, 1))),
        )
        checkSound("table-asymmetric", problem, expectReduced = false)
    }

    @Test
    fun `booleans fixed apart by dropped factors should not be ordered against each other`() {
        val units = listOf(Clause(intArrayOf(Lit.make(0, true))), Clause(intArrayOf(Lit.make(1, false))))
        val session = PresolveSession(Problem(2, 0, emptyArray(), units).bake())
        assertTrue(session.apply(PresolveDelta(droppedIds = intArrayOf(0, 1))))

        assertTrue(session.applyDelta(Presolve.breakSymmetries(session.passInput())))
    }

    @Test
    fun `vars with different domains should not be grouped`() {
        val problem = Problem(
            0,
            2,
            arrayOf(IntDomain(0, 2), IntDomain(0, 3)),
            listOf(Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 3)),
        )
        checkSound("differentDomains", problem, expectReduced = false)
    }

    @Test
    fun `an asymmetric regular should have no value symmetry`() {
        val problem = Problem(
            0,
            1,
            arrayOf(IntDomain(1, 2)),
            listOf(
                Regular(
                    intArrayOf(0),
                    numStates = 1,
                    alphabetSize = 2,
                    transitions = longArrayOf(1, 0),
                    q0 = 1,
                    accepting = intArrayOf(1),
                ),
            ),
        )
        checkSound("regular-asymmetric", problem, expectReduced = false)
    }

    @Test
    fun `a wide row remap hash should match its remapped structural key`() {
        val wide = parseBigInt("18446744073709551616")
        val row = ReifiedLinear(0, intArrayOf(0, 1, 2), arrayOf(wide, -wide, BIG_ONE), LinearOp.LE, wide)
        val mapping = VarRemap(intArrayOf(1, 0), intArrayOf(2, 0, 1))

        val hashes = List(3) { row.remapStructuralHash(mapping) }

        assertEquals(List(3) { row.remap(mapping).structuralKey().hashCode() }, hashes)
    }

    private fun sourceBroken(problem: Problem): Problem =
        assertNotNull(problem.withSourcePassDelta(Presolve.breakSourceSymmetries(problem)))

    private fun openSum(openX: Boolean, openY: Boolean, yLow: Long = 0) = Problem(
        numBoolVars = 0,
        numIntVars = 2,
        intDomains = arrayOf(IntDomain(0, 4), IntDomain(yLow, 4)),
        factors = listOf(Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.EQ, 4)),
        openIntHi = booleanArrayOf(openX, openY),
    )

    private fun openColoring(zLow: Long) = Problem(
        numBoolVars = 0,
        numIntVars = 3,
        intDomains = arrayOf(IntDomain(0, 2), IntDomain(0, 2), IntDomain(zLow, 3)),
        factors = listOf(
            Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.NE, 0),
            Linear(intArrayOf(1, -1), intArrayOf(1, 2), LinearOp.NE, 0),
        ),
        openIntHi = booleanArrayOf(false, false, true),
    )

    @Test
    fun `source symmetry breaking should order interchangeable open columns`() {
        val problem = openSum(openX = true, openY = true)

        val after = countFeasible(sourceBroken(problem))

        assertEquals(5, countFeasible(problem))
        assertEquals(3, after, "x <= y keeps one of each swapped pair")
    }

    @Test
    fun `source symmetry breaking should keep columns with different open declarations apart`() {
        listOf(
            openSum(openX = true, openY = false),
            openSum(openX = true, openY = true, yLow = 1),
        ).forEach { problem ->
            assertTrue(Presolve.breakSourceSymmetries(problem).isEmpty)
        }
    }

    @Test
    fun `source symmetry breaking should post no propagator-only break over an open column`() {
        val problem = Problem(
            numBoolVars = 2,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 2), IntDomain(0, 2)),
            factors = listOf(
                ReifiedLinear(auxBoolVar = 0, coeffs = intArrayOf(1), vars = intArrayOf(0), LinearOp.EQ, bound = 1),
                ReifiedLinear(auxBoolVar = 1, coeffs = intArrayOf(1), vars = intArrayOf(1), LinearOp.EQ, bound = 1),
            ),
            openIntHi = booleanArrayOf(true, true),
        )

        val delta = Presolve.breakSourceSymmetries(problem)

        assertTrue(delta.addedFactors.none { it is SymmetryHandling })
    }

    @Test
    fun `an open column admitting part of the values should split their orbit`() {
        assertTrue(Presolve.breakSourceSymmetries(openColoring(zLow = 1)).isEmpty)
    }
}
