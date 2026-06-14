package com.eignex.klause.solver.presolve

import com.eignex.klause.model.PbOp
import com.eignex.klause.solver.Assumptions
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.factor.AllDifferent
import com.eignex.klause.solver.factor.Cardinality
import com.eignex.klause.solver.factor.Circuit
import com.eignex.klause.solver.factor.Cumulative
import com.eignex.klause.solver.factor.Diffn
import com.eignex.klause.solver.factor.Disjunctive
import com.eignex.klause.solver.factor.Element
import com.eignex.klause.solver.factor.GlobalCardinality
import com.eignex.klause.solver.factor.Inverse
import com.eignex.klause.solver.factor.Linear
import com.eignex.klause.solver.factor.LinearOp
import com.eignex.klause.solver.factor.Mdd
import com.eignex.klause.solver.factor.NValue
import com.eignex.klause.solver.factor.PseudoBoolean
import com.eignex.klause.solver.factor.Regular
import com.eignex.klause.solver.factor.ReifiedCardinality
import com.eignex.klause.solver.factor.ReifiedLinear
import com.eignex.klause.solver.factor.ReifiedPseudoBoolean
import com.eignex.klause.solver.factor.Sort
import com.eignex.klause.solver.factor.Subcircuit
import com.eignex.klause.solver.factor.Table
import com.eignex.klause.solver.propagation.PropagationResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Symmetry breaking (#317). The non-negotiable property is **soundness**: breaking must never
 * turn a satisfiable problem unsatisfiable. Every test enumerates the whole assignment space and
 * compares feasible-solution counts before and after; the broken problem must keep ≥1 solution per
 * orbit (so counts agree on satisfiability) and only ever remove solutions.
 */
class SymmetryBreakingTest {

    private fun isFeasible(problem: Problem, bools: BooleanArray, ints: IntArray): Boolean {
        var a = Assumptions.None
        for (v in 0 until problem.numBoolVars) a = a.withBool(v, bools[v])
        for (v in 0 until problem.numIntVars) a = a.withInt(v, ints[v])
        return problem.propagate(a) !is PropagationResult.Unsat
    }

    /** Count feasible assignments over the full (contiguous-domain) space; capped for safety. */
    private fun countFeasible(problem: Problem): Int {
        val b = problem.numBoolVars
        val n = problem.numIntVars
        val ints = IntArray(n) { problem.intDomains[it].min }
        var count = 0
        while (true) {
            for (mask in 0 until (1 shl b).coerceAtLeast(1)) {
                val bools = BooleanArray(b) { (mask shr it) and 1 == 1 }
                if (isFeasible(problem, bools, ints.copyOf())) count++
            }
            var i = 0
            while (i < n) {
                ints[i]++
                if (ints[i] <= problem.intDomains[i].max) break
                ints[i] = problem.intDomains[i].min
                i++
            }
            if (i == n) break
        }
        return count
    }

    private fun checkSound(name: String, problem: Problem, expectReduced: Boolean) {
        val broken = Presolve.breakSymmetries(problem)
        val orig = countFeasible(problem)
        val after = countFeasible(broken)
        assertTrue(after <= orig, "$name: breaking ADDED solutions ($orig -> $after)")
        assertEquals(orig > 0, after > 0, "$name: breaking changed satisfiability ($orig -> $after)")
        if (expectReduced) {
            assertTrue(after < orig, "$name: expected fewer solutions but $orig -> $after")
        } else {
            assertSame(problem, broken, "$name: expected no symmetry detected")
        }
    }

    private fun pos(v: Int) = Lit.make(v, true)

    @Test
    fun `law-lee value precedence collapses value-symmetric solutions`() {
        // Three variables over {0,1,2} with no constraints: pure value symmetry. The symmetry classes
        // are the Bell(3)=5 set partitions, and the value_precede_chain keeps exactly one canonical
        // (restricted-growth) representative each — far stronger than pinning a single variable.
        val problem = Problem(0, 3, Array(3) { IntDomain(0, 2) }, emptyList())
        val broken = Presolve.breakValuePrecedence(problem)
        val orig = countFeasible(problem)
        val after = countFeasible(broken)
        assertTrue(after < orig, "expected reduction: $orig -> $after")
        assertEquals(orig > 0, after > 0)
        assertEquals(5, after, "value precedence should keep one representative per symmetry class")
    }

    @Test
    fun `law-lee precedence on alldifferent keeps the single canonical permutation`() {
        // AllDifferent over {0,1,2} is value-anonymous; precedence forces the identity assignment
        // 0,1,2 (the one canonical labeling), collapsing all 6 permutations.
        val problem = Problem(
            0,
            3,
            Array(3) { IntDomain(0, 2) },
            listOf(AllDifferent(intArrayOf(0, 1, 2), domainMin = 0, domainSize = 3)),
        )
        val broken = Presolve.breakValuePrecedence(problem)
        assertEquals(6, countFeasible(problem))
        assertEquals(1, countFeasible(broken), "precedence + alldifferent should leave one solution")
    }

    @Test
    fun `law-lee precedence is a no-op when no factor is value-anonymous`() {
        // A Linear is value-meaningful, so value symmetry does not apply — nothing is posted.
        val problem = Problem(
            0,
            2,
            Array(2) { IntDomain(0, 2) },
            listOf(Linear(intArrayOf(1, 2), intArrayOf(0, 1), LinearOp.LE, 3)),
        )
        assertSame(problem, Presolve.breakValuePrecedence(problem), "no value symmetry ⇒ no-op")
    }

    @Test
    fun `value precedence fires on graph coloring with binary disequalities`() {
        // `col[a] != col[b]` compiles to a binary Linear with op NE — a pure disequality, which is
        // value-anonymous (#501): the colors are interchangeable. A triangle over 3 colors has 3!=6
        // proper colorings (all distinct); precedence keeps the single canonical labeling 0,1,2.
        val edges = listOf(0 to 1, 0 to 2, 1 to 2)
        val problem = Problem(
            0,
            3,
            Array(3) { IntDomain(0, 2) },
            edges.map { (a, b) -> Linear(intArrayOf(1, -1), intArrayOf(a, b), LinearOp.NE, 0) },
        )
        assertEquals(6, countFeasible(problem))
        assertEquals(
            1,
            countFeasible(Presolve.breakValuePrecedence(problem)),
            "coloring value symmetry should collapse to one labeling",
        )
    }

    @Test
    fun `value symmetry breaking is sound on a partial graph coloring`() {
        // Path 0-1-2 over 3 colors: 12 proper colorings, colors still interchangeable. The single-var
        // value pin (breakSymmetries) must only remove solutions, never change satisfiability.
        val problem = Problem(
            0,
            3,
            Array(3) { IntDomain(0, 2) },
            listOf(
                Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.NE, 0),
                Linear(intArrayOf(1, -1), intArrayOf(1, 2), LinearOp.NE, 0),
            ),
        )
        checkSound("path-coloring", problem, expectReduced = true)
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
        assertSame(problem, Presolve.breakValuePrecedence(problem), "ordering ⇒ no value symmetry")
    }

    @Test
    fun `law-lee precedence fires over a verified non-anonymous orbit`() {
        // A global_cardinality with equal per-value bounds is NOT value-anonymous, but its cover values
        // are interchangeable — verified via remapValues (#442). Precedence over the fully-internal
        // vars then collapses the value-symmetric solutions, where the old anonymity gate gave up.
        val problem = Problem(
            0,
            3,
            Array(3) { IntDomain(0, 2) },
            listOf(
                GlobalCardinality(
                    xs = intArrayOf(0, 1, 2),
                    cover = intArrayOf(0, 1, 2),
                    countLow = intArrayOf(0, 0, 0),
                    countHigh = intArrayOf(3, 3, 3),
                ),
            ),
        )
        val broken = Presolve.breakValuePrecedence(problem)
        val orig = countFeasible(problem)
        val after = countFeasible(broken)
        assertTrue(after < orig, "expected reduction: $orig -> $after")
        assertEquals(orig > 0, after > 0)
    }

    @Test
    fun `value symmetry pins an interchangeable-value variable`() {
        // x0,x1: AllDifferent over {0,1,2}. x2 ∈ {3,4} appears in no factor, so its two values are an
        // interchangeable-value orbit (value-anonymous problem) — pinned to the orbit minimum (3).
        val problem = Problem(
            0,
            3,
            arrayOf(IntDomain(0, 2), IntDomain(0, 2), IntDomain(3, 4)),
            listOf(AllDifferent(intArrayOf(0, 1), domainMin = 0, domainSize = 3)),
        )
        checkSound("value-sym", problem, expectReduced = true)
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
        // factor set, so they ARE interchangeable. The same-factor-set heuristic misses this;
        // verified detection (remap + structural key) catches it and orders them.
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
    fun `interchangeable alldifferent variables are ordered`() {
        val problem = Problem(
            0,
            3,
            arrayOf(IntDomain(0, 2), IntDomain(0, 2), IntDomain(0, 2)),
            listOf(AllDifferent(intArrayOf(0, 1, 2), domainMin = 0, domainSize = 3)),
        )
        checkSound("alldiff", problem, expectReduced = true)
        // 3! permutations collapse to the single sorted one.
        assertEquals(1, countFeasible(Presolve.breakSymmetries(problem)))
    }

    @Test
    fun `equal-coefficient sum variables are interchangeable`() {
        val problem = Problem(
            0,
            3,
            arrayOf(IntDomain(0, 2), IntDomain(0, 2), IntDomain(0, 2)),
            listOf(Linear(intArrayOf(1, 1, 1), intArrayOf(0, 1, 2), LinearOp.LE, 2)),
        )
        checkSound("equalCoeffSum", problem, expectReduced = true)
    }

    @Test
    fun `interchangeable booleans in a cardinality are ordered`() {
        val problem = Problem(
            3,
            0,
            emptyArray(),
            listOf(Cardinality(intArrayOf(pos(0), pos(1), pos(2)), min = 2, max = 3)),
        )
        checkSound("cardBools", problem, expectReduced = true)
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
    fun `same role in different factors is not grouped`() {
        // x0 in (x0 <= 1); x1 in (x1 <= 2). Same role token but different factors and bounds —
        // grouping them would be unsound, so the factorId in the role key must keep them apart.
        val problem = Problem(
            0,
            2,
            arrayOf(IntDomain(0, 3), IntDomain(0, 3)),
            listOf(
                Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 1),
                Linear(intArrayOf(1), intArrayOf(1), LinearOp.LE, 2),
            ),
        )
        checkSound("differentFactors", problem, expectReduced = false)
    }

    @Test
    fun `interchangeable bool rows are lex-ordered`() {
        // Two bool rows a0+2·a1 ≤ 2 and b0+2·b1 ≤ 2. The rows are interchangeable as blocks, but the
        // cells within a row are NOT (different weights), so this is bool block/row symmetry — broken
        // by a binary-number lex-leader (#373) rather than per-variable ordering.
        val problem = Problem(
            4,
            0,
            emptyArray(),
            listOf(
                PseudoBoolean(intArrayOf(1, 2), intArrayOf(pos(0), pos(1)), PbOp.LE, 2),
                PseudoBoolean(intArrayOf(1, 2), intArrayOf(pos(2), pos(3)), PbOp.LE, 2),
            ),
        )
        checkSound("bool-rows", problem, expectReduced = true)
    }

    @Test
    fun `isomorphic element factors are block-ordered`() {
        // Two element constraints v1 = arr(v0), v3 = arr(v2) over the same constant table are
        // interchangeable as blocks. element used to be unkeyed (structuralKey == null), forcing the
        // conservative heuristic which excludes element-touched vars and breaks nothing; with a key,
        // verified block detection orders the rows.
        val problem = Problem(
            0,
            4,
            arrayOf(IntDomain(1, 2), IntDomain(7, 8), IntDomain(1, 2), IntDomain(7, 8)),
            listOf(
                Element(idx = 0, result = 1, arr = intArrayOf(7, 8), arrIsVars = false, indexOffset = 1),
                Element(idx = 2, result = 3, arr = intArrayOf(7, 8), arrIsVars = false, indexOffset = 1),
            ),
        )
        checkSound("element-blocks", problem, expectReduced = true)
    }

    @Test
    fun `isomorphic inverse factors are block-ordered`() {
        // Two inverse constraints over disjoint var blocks are interchangeable as blocks; the new
        // inverse structuralKey enables verified detection (and the brute gate guards the key's
        // soundness — a too-coarse key would let a false swap through and ADD solutions).
        val problem = Problem(
            0,
            8,
            Array(8) { IntDomain(0, 1) },
            listOf(
                Inverse(f = intArrayOf(0, 1), g = intArrayOf(2, 3)),
                Inverse(f = intArrayOf(4, 5), g = intArrayOf(6, 7)),
            ),
        )
        checkSound("inverse-blocks", problem, expectReduced = true)
    }

    @Test
    fun `wl refinement splits candidate groups by structural role`() {
        // Two rows x0 + 2·x1 ≤ 3 and x2 + 2·x3 ≤ 3, all same domain. The old grouping put all four
        // same-domain vars in one candidate group; WL colour refinement splits them by their role —
        // the coeff-1 cells {x0,x2} and the coeff-2 cells {x1,x3} — into two colour classes.
        val problem = Problem(
            0,
            4,
            arrayOf(IntDomain(0, 3), IntDomain(0, 3), IntDomain(0, 3), IntDomain(0, 3)),
            listOf(
                Linear(intArrayOf(1, 2), intArrayOf(0, 1), LinearOp.LE, 3),
                Linear(intArrayOf(1, 2), intArrayOf(2, 3), LinearOp.LE, 3),
            ),
        )
        val (intColour, _) = Presolve.refineColoursForTest(problem)
        assertEquals(intColour[0], intColour[2], "coeff-1 cells should share a WL colour")
        assertEquals(intColour[1], intColour[3], "coeff-2 cells should share a WL colour")
        assertTrue(intColour[0] != intColour[1], "different roles should get different WL colours")
    }

    @Test
    fun `interchangeable values in a global cardinality are pinned`() {
        // GCC over x0,x1 ∈ {0,1}: each of values 0,1 may occur 0..2 times. The two values are
        // interchangeable (same bounds, same domain-incidence). GCC is not value-anonymous, so the
        // old gate switched value symmetry off; remapValues verification (#374) catches the swap and
        // pins a fully-internal variable to the orbit minimum.
        val problem = Problem(
            0,
            2,
            arrayOf(IntDomain(0, 1), IntDomain(0, 1)),
            listOf(
                GlobalCardinality(
                    xs = intArrayOf(0, 1),
                    cover = intArrayOf(0, 1),
                    countLow = intArrayOf(0, 0),
                    countHigh = intArrayOf(2, 2),
                ),
            ),
        )
        checkSound("gcc-value", problem, expectReduced = true)
    }

    @Test
    fun `interchangeable values in a table are pinned`() {
        // Table allowing (0,1) and (1,0): swapping values 0↔1 maps the row set to itself, so the two
        // values are an interchangeable orbit (verified via Table.remapValues). A fully-internal var
        // is pinned to the orbit minimum.
        val problem = Problem(
            0,
            2,
            arrayOf(IntDomain(0, 1), IntDomain(0, 1)),
            listOf(Table(intArrayOf(0, 1), intArrayOf(0, 1, 1, 0))),
        )
        checkSound("table-value", problem, expectReduced = true)
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
            listOf(Table(intArrayOf(0, 1), intArrayOf(0, 1))),
        )
        checkSound("table-asymmetric", problem, expectReduced = false)
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
    fun `newly-keyed factors have collision-free structural keys`() {
        // The soundness property a structuralKey must hold (#443): two factors that differ in any
        // value-distinguishing constant get different keys, while a faithful copy keeps the same key.
        // A too-coarse key (a dropped constant) would let symmetry detection see a false automorphism.
        fun distinct(a: Factor, b: Factor, why: String) = assertNotEquals(a.structuralKey(), b.structuralKey(), why)

        // ReifiedLinear: aux bool, op, bound, and the (var, coeff) terms all matter.
        assertEquals(
            ReifiedLinear(0, intArrayOf(1, 2), intArrayOf(0, 1), LinearOp.LE, 3).structuralKey(),
            ReifiedLinear(0, intArrayOf(2, 1), intArrayOf(1, 0), LinearOp.LE, 3).structuralKey(),
            "term order must not change the key",
        )
        distinct(
            ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.LE, 1),
            ReifiedLinear(1, intArrayOf(1), intArrayOf(0), LinearOp.LE, 1),
            "aux bool",
        )
        distinct(
            ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.LE, 1),
            ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.LE, 2),
            "bound",
        )
        distinct(
            ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.LE, 1),
            ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.GE, 1),
            "op",
        )
        distinct(
            ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.LE, 1),
            Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 1),
            "reified vs asserted linear",
        )
        // ReifiedCardinality / ReifiedPseudoBoolean.
        distinct(
            ReifiedCardinality(0, intArrayOf(pos(1), pos(2)), 1, 2),
            ReifiedCardinality(0, intArrayOf(pos(1), pos(2)), 1, 1),
            "cardinality max",
        )
        distinct(
            ReifiedPseudoBoolean(0, intArrayOf(2, 3), intArrayOf(pos(1), pos(2)), PbOp.LE, 4),
            ReifiedPseudoBoolean(0, intArrayOf(2, 3), intArrayOf(pos(1), pos(2)), PbOp.LE, 5),
            "pb bound",
        )
        // Circuit / Subcircuit: position-faithful, and distinct from each other.
        distinct(Circuit(intArrayOf(1, 0)), Circuit(intArrayOf(0, 1)), "succ order")
        distinct(Circuit(intArrayOf(1, 0)), Subcircuit(intArrayOf(1, 0)), "circuit vs subcircuit")
        // NValue: mode, count var, and the counted vars.
        distinct(
            NValue(2, intArrayOf(0, 1), NValue.Mode.Eq),
            NValue(2, intArrayOf(0, 1), NValue.Mode.AtMost),
            "nvalue mode",
        )
        distinct(NValue(2, intArrayOf(0, 1)), NValue(3, intArrayOf(0, 1)), "nvalue count var")
        // Sort: ys is position-faithful, xs order-insensitive.
        assertEquals(
            Sort(intArrayOf(0, 1), intArrayOf(2, 3)).structuralKey(),
            Sort(intArrayOf(1, 0), intArrayOf(2, 3)).structuralKey(),
            "xs order must not change the key",
        )
        distinct(Sort(intArrayOf(0, 1), intArrayOf(2, 3)), Sort(intArrayOf(0, 1), intArrayOf(3, 2)), "ys order")
    }

    /** Soundness without an outcome expectation: breaking may or may not find a breakable orbit, but
     *  it must never add solutions or flip satisfiability. Guards that a new structuralKey can't make
     *  verified detection unsound (a too-coarse key would). */
    private fun checkBreakingSound(name: String, problem: Problem) {
        val broken = Presolve.breakSymmetries(problem)
        val orig = countFeasible(problem)
        val after = countFeasible(broken)
        assertTrue(after <= orig, "$name: breaking ADDED solutions ($orig -> $after)")
        assertEquals(orig > 0, after > 0, "$name: breaking changed satisfiability ($orig -> $after)")
    }

    @Test
    fun `isomorphic nvalue factors are block-ordered`() {
        // Two nvalue(count, xs) blocks over disjoint, equal-domain variables are interchangeable; the
        // new nvalue structuralKey lets verified block detection order them. The count vars get domain
        // [1,2] (the distinct count of two binary vars) so the blocks aren't degenerate. The brute gate
        // guards the key's soundness — a too-coarse key would let a false swap through and add solutions.
        val problem = Problem(
            0,
            6,
            arrayOf(
                IntDomain(1, 2),
                IntDomain(0, 1),
                IntDomain(0, 1),
                IntDomain(1, 2),
                IntDomain(0, 1),
                IntDomain(0, 1),
            ),
            listOf(
                NValue(n = 0, xs = intArrayOf(1, 2)),
                NValue(n = 3, xs = intArrayOf(4, 5)),
            ),
        )
        checkSound("nvalue-blocks", problem, expectReduced = true)
    }

    @Test
    fun `breaking stays sound with reified rows present`() {
        // Two reified rows b0 <-> (x0 <= 1), b1 <-> (x1 <= 1) over disjoint, equal-domain vars carry a
        // block symmetry. The new ReifiedLinear key takes the problem off the conservative fallback;
        // the current breaker posts no ordering across this mixed bool+int orbit, so the key's job here
        // is purely that detection runs without ever becoming unsound.
        val problem = Problem(
            2,
            2,
            arrayOf(IntDomain(0, 2), IntDomain(0, 2)),
            listOf(
                ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.LE, 1),
                ReifiedLinear(1, intArrayOf(1), intArrayOf(1), LinearOp.LE, 1),
            ),
        )
        checkBreakingSound("rlin-blocks", problem)
    }

    @Test
    fun `global factors have collision-free structural keys`() {
        // #531: keys for the scheduling / automaton globals must distinguish every constant and the
        // variable order, or symmetry verification could accept a false automorphism.
        fun distinct(a: Factor, b: Factor, why: String) = assertNotEquals(a.structuralKey(), b.structuralKey(), why)

        // Cumulative: capacity, the constant duration/resource arrays, the var/const split, and order.
        val cum = Cumulative(intArrayOf(0, 1), intArrayOf(1, 1), intArrayOf(1, 1), 2)
        distinct(cum, Cumulative(intArrayOf(0, 1), intArrayOf(1, 1), intArrayOf(1, 1), 3), "capacity")
        distinct(cum, Cumulative(intArrayOf(0, 1), intArrayOf(1, 2), intArrayOf(1, 1), 2), "duration")
        distinct(cum, Cumulative(intArrayOf(0, 1), intArrayOf(1, 1), intArrayOf(2, 1), 2), "resource")
        distinct(cum, Cumulative(intArrayOf(1, 0), intArrayOf(1, 1), intArrayOf(1, 1), 2), "start order")
        distinct(
            cum,
            Cumulative(intArrayOf(0, 1), intArrayOf(1, 1), intArrayOf(1, 1), 2, capacityVar = 5),
            "capacityVar",
        )

        // Disjunctive.
        val dis = Disjunctive(intArrayOf(0, 1), intArrayOf(1, 1))
        distinct(dis, Disjunctive(intArrayOf(0, 1), intArrayOf(1, 2)), "duration")
        distinct(dis, Disjunctive(intArrayOf(1, 0), intArrayOf(1, 1)), "start order")
        distinct(dis, cum, "disjunctive vs cumulative")

        // Diffn: the nonStrict flag, constant sizes, and coordinate order.
        val diffn = Diffn(intArrayOf(0, 1), intArrayOf(2, 3), intArrayOf(1, 1), intArrayOf(1, 1))
        distinct(
            diffn,
            Diffn(intArrayOf(0, 1), intArrayOf(2, 3), intArrayOf(1, 1), intArrayOf(1, 1), nonStrict = true),
            "nonStrict",
        )
        distinct(diffn, Diffn(intArrayOf(0, 1), intArrayOf(2, 3), intArrayOf(2, 1), intArrayOf(1, 1)), "width")
        distinct(diffn, Diffn(intArrayOf(1, 0), intArrayOf(2, 3), intArrayOf(1, 1), intArrayOf(1, 1)), "x order")

        // Regular: automaton size, transition table, q0, accepting, sequence order.
        val reg = Regular(intArrayOf(0, 1), 2, 2, intArrayOf(1, 2, 2, 1), 1, intArrayOf(2))
        distinct(reg, Regular(intArrayOf(0, 1), 2, 2, intArrayOf(1, 1, 2, 1), 1, intArrayOf(2)), "transition")
        distinct(reg, Regular(intArrayOf(0, 1), 2, 2, intArrayOf(1, 2, 2, 1), 2, intArrayOf(2)), "q0")
        distinct(reg, Regular(intArrayOf(0, 1), 2, 2, intArrayOf(1, 2, 2, 1), 1, intArrayOf(1)), "accepting")
        distinct(reg, Regular(intArrayOf(1, 0), 2, 2, intArrayOf(1, 2, 2, 1), 1, intArrayOf(2)), "seq order")

        // Mdd: initial, transition records, cost var, sequence order.
        val mdd = Mdd(intArrayOf(0), intArrayOf(1, 1), intArrayOf(0, 1), intArrayOf(0, 0, 1), 0, intArrayOf(0), 3)
        distinct(
            mdd,
            Mdd(intArrayOf(0), intArrayOf(1, 1), intArrayOf(0, 1), intArrayOf(0, 1, 1), 0, intArrayOf(0), 3),
            "transition",
        )
        distinct(
            mdd,
            Mdd(
                intArrayOf(0),
                intArrayOf(1, 1),
                intArrayOf(0, 1),
                intArrayOf(0, 0, 1, 0),
                0,
                intArrayOf(0),
                4,
                cost = 7,
            ),
            "cost stride",
        )
    }

    @Test
    fun `breaking stays sound with disjunctive blocks present`() {
        // Two disjoint identical disjunctive blocks over equal-domain start vars carry a block symmetry;
        // the new key takes the problem off the conservative fallback. Soundness is the guarantee.
        val problem = Problem(
            0,
            4,
            arrayOf(IntDomain(0, 2), IntDomain(0, 2), IntDomain(0, 2), IntDomain(0, 2)),
            listOf(
                Disjunctive(intArrayOf(0, 1), intArrayOf(1, 1)),
                Disjunctive(intArrayOf(2, 3), intArrayOf(1, 1)),
            ),
        )
        checkBreakingSound("disjunctive-blocks", problem)
    }
}
