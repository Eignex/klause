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

    /** Count feasible assignments over the full (contiguous-domain) space; capped for safety. */
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
    fun `row-symmetric matrix is broken soundly without column-wise overcut`() {
        // A 2x2 bool matrix whose only solutions are one symmetry orbit: ((0,1),(1,0)) and its
        // row/column swap ((1,0),(0,1)). Totally ordering each column orbit independently would cut
        // BOTH representatives and make the problem UNSAT — unsound. The break must keep ≥1 solution.
        // Layout b00=0, b01=1, b10=2, b11=3; factors b00≡b11, b01≡b10, b00≠b01, b10≠b11.
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
            "breaking turned a satisfiable symmetric orbit UNSAT (unsound column-wise cut)",
        )
    }

    @Test
    fun `generator search breaks a composite bool-int symmetry no single transposition can`() {
        // Two reified factors b0 ↔ (x0 = 1) and b1 ↔ (x1 = 1) are interchangeable only as the joint
        // swap (b0,x0) ↔ (b1,x1); swapping x0 ↔ x1 alone is not an automorphism, so transposition-only
        // detection finds nothing. The individualization–refinement generator search recovers the
        // composite permutation (individualizing x0 cascades through its factor to distinguish b0),
        // yielding the int orbit {x0,x1} and bool orbit {b0,b1}. This mixed bool+int symmetry is the
        // mechanism by which lowered set/list structure becomes breakable.
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
    fun `law-lee value precedence collapses value-symmetric solutions`() {
        // Three variables over {0,1,2} with no constraints: pure value symmetry. The symmetry classes
        // are the Bell(3)=5 set partitions, and the value_precede_chain keeps exactly one canonical
        // (restricted-growth) representative each — far stronger than pinning a single variable.
        val problem = Problem(0, 3, Array(3) { IntDomain(0, 2) }, emptyList())
        val broken = precedence(problem)
        val orig = countFeasible(problem)
        val after = countFeasible(broken)
        assertTrue(after < orig, "expected reduction: $orig -> $after")
        assertEquals(orig > 0, after > 0)
        assertEquals(5, after, "value precedence should keep one representative per symmetry class")
    }

    @Test
    fun `an ordering linear is not value-anonymous`() {
        // `x - y <= 0` is an ordering, not a disequality — relabeling values breaks it, so value
        // symmetry must stay off (regression guard for the #501 binary-relation detection).
        val problem = Problem(
            0,
            2,
            Array(2) { IntDomain(0, 2) },
            listOf(Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.LE, 0)),
        )
        assertTrue(Presolve.breakValuePrecedence(problem.bake()).isEmpty, "ordering ⇒ no value symmetry")
    }

    @Test
    fun `interchangeable matrix rows are lex-ordered`() {
        // Two rows: x0 + 2·x1 ≤ 3 and x2 + 2·x3 ≤ 3. The rows are interchangeable as blocks, but the
        // cells within a row are NOT (different coefficients) — so this is block/row symmetry, broken
        // by a lex-leader between the rows rather than per-variable ordering.
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
    fun `verified detection orders interchangeable vars in separate isomorphic factors`() {
        // x0 in (x0 <= 3) and x1 in (x1 <= 3): different factors, but swapping x0/x1 preserves the
        // factor set, so they ARE interchangeable — verified detection (remap + structural key) orders them.
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
    fun `asymmetric separate factors are not grouped`() {
        // x0 <= 3, x1 <= 4: NOT interchangeable (swapping changes the bounds). Must not reduce.
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
    fun `unequal coefficients are not grouped`() {
        val problem = Problem(
            0,
            2,
            arrayOf(IntDomain(0, 3), IntDomain(0, 3)),
            listOf(Linear(intArrayOf(1, 2), intArrayOf(0, 1), LinearOp.LE, 3)),
        )
        checkSound("unequalCoeff", problem, expectReduced = false)
    }

    @Test
    fun `bool row symmetry is handled by a propagator-only SymmetryHandling factor`() {
        // Two interchangeable 3-wide bool rows. Breaking adds a single SymmetryHandling factor that
        // enforces every generator's lex-leader dynamically (no static lex enumeration, no width cap,
        // no integer-space growth) — the mechanism that lets arbitrarily wide rows, e.g. a set
        // variable's indicator matrix, be broken. The factor is propagator-only (invisible to LS).
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
        assertEquals(problem.numIntVars, broken.numIntVars, "dynamic handling must not grow the integer space")
        val symmetry = broken.factors.filterIsInstance<SymmetryHandling>().single()
        assertSame(NoInvariant, symmetry.invariantProjection(), "symmetry handling must be propagator-only")
    }

    @Test
    fun `wl refinement splits candidate groups by structural role`() {
        // Two rows x0 + 2·x1 ≤ 3 and x2 + 2·x3 ≤ 3, all same domain. WL colour refinement splits them
        // by their role — the coeff-1 cells {x0,x2} and the coeff-2 cells {x1,x3} — into two colour classes.
        val problem = Problem(
            0,
            4,
            arrayOf(IntDomain(0, 3), IntDomain(0, 3), IntDomain(0, 3), IntDomain(0, 3)),
            listOf(
                Linear(intArrayOf(1, 2), intArrayOf(0, 1), LinearOp.LE, 3),
                Linear(intArrayOf(1, 2), intArrayOf(2, 3), LinearOp.LE, 3),
            ),
        )
        val (intColour, _) = Presolve.refineColoursForTest(problem.bake())
        assertEquals(intColour[0], intColour[2], "coeff-1 cells should share a WL colour")
        assertEquals(intColour[1], intColour[3], "coeff-2 cells should share a WL colour")
        assertTrue(intColour[0] != intColour[1], "different roles should get different WL colours")
    }

    @Test
    fun `non-relabelable factor blocks value symmetry`() {
        // A single allowed tuple (0,1) is NOT value-symmetric (swapping 0↔1 gives the row (1,0),
        // which isn't allowed), and the columns aren't variable-interchangeable either — so nothing
        // is broken.
        val problem = Problem(
            0,
            2,
            arrayOf(IntDomain(0, 1), IntDomain(0, 1)),
            listOf(Table(intArrayOf(0, 1), longArrayOf(0, 1))),
        )
        checkSound("table-asymmetric", problem, expectReduced = false)
    }

    @Test
    fun `booleans fixed apart by dropped factors are not ordered against each other`() {
        val units = listOf(Clause(intArrayOf(Lit.make(0, true))), Clause(intArrayOf(Lit.make(1, false))))
        val session = PresolveSession(Problem(2, 0, emptyArray(), units).bake())
        assertTrue(session.apply(PresolveDelta(droppedIds = intArrayOf(0, 1))))

        assertTrue(session.applyDelta(Presolve.breakSymmetries(session.passInput())))
    }

    @Test
    fun `different domains block grouping`() {
        // Same role token but different domains ⇒ not interchangeable.
        val problem = Problem(
            0,
            2,
            arrayOf(IntDomain(0, 2), IntDomain(0, 3)),
            listOf(Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 3)),
        )
        checkSound("differentDomains", problem, expectReduced = false)
    }

    @Test
    fun `an asymmetric regular has no value symmetry`() {
        // δ(1,1)=1 (stay accepting), δ(1,2)=0 (dead): only symbol 1 is ever valid, so swapping 1↔2 is
        // not a symmetry — nothing is broken. (seq var 1 stays free of the accepting constraint.)
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
    fun `a wide row hashes like its remapped structural key on every keying`() {
        val wide = parseBigInt("18446744073709551616")
        val row = ReifiedLinear(0, intArrayOf(0, 1, 2), arrayOf(wide, -wide, BIG_ONE), LinearOp.LE, wide)
        val mapping = VarRemap(intArrayOf(1, 0), intArrayOf(2, 0, 1))

        val hashes = List(3) { row.remapStructuralHash(mapping) }

        assertEquals(List(3) { row.remap(mapping).structuralKey().hashCode() }, hashes)
    }

    private fun sourceBroken(problem: Problem): Problem =
        assertNotNull(problem.withSourcePassDelta(Presolve.breakSourceSymmetries(problem)))

    /** `x + y = 4` with both columns from [yLow] / 0 upward, open above where flagged; the box covers
     *  every solution so counting inside it counts the model. */
    private fun openSum(openX: Boolean, openY: Boolean, yLow: Long = 0) = Problem(
        numBoolVars = 0,
        numIntVars = 2,
        intDomains = arrayOf(IntDomain(0, 4), IntDomain(yLow, 4)),
        factors = listOf(Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.EQ, 4)),
        openIntHi = booleanArrayOf(openX, openY),
    )

    /** `x ≠ y`, `y ≠ z` over closed `x, y ∈ 0..2` and `z` open above from [zLow]. */
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
    fun `the source form orders interchangeable open columns`() {
        val problem = openSum(openX = true, openY = true)

        val after = countFeasible(sourceBroken(problem))

        assertEquals(5, countFeasible(problem))
        assertEquals(3, after, "x <= y keeps one of each swapped pair")
    }

    @Test
    fun `the source form keeps columns with different open declarations apart`() {
        listOf(
            openSum(openX = true, openY = false),
            openSum(openX = true, openY = true, yLow = 1),
        ).forEach { problem ->
            assertTrue(Presolve.breakSourceSymmetries(problem).isEmpty)
        }
    }

    @Test
    fun `the source form posts no propagator-only break over an open column`() {
        // b0 <-> (x0 = 1), b1 <-> (x1 = 1): a joint swap only, which the finite form hands to SymmetryHandling.
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
    fun `an open column admitting part of the values splits their orbit`() {
        // 0 is admitted by x and y only, so relabeling it would move z out of its range.
        assertTrue(Presolve.breakSourceSymmetries(openColoring(zLow = 1)).isEmpty)
    }
}
