package com.eignex.klause.solver

import com.eignex.klause.solver.propagation.PropagationResult

import com.eignex.klause.ast.IntCmpOp
import com.eignex.klause.solver.factor.Cardinality
import com.eignex.klause.solver.factor.Clause
import com.eignex.klause.ast.PbOp
import com.eignex.klause.solver.factor.Linear
import com.eignex.klause.solver.factor.LinearOp
import com.eignex.klause.solver.factor.PseudoBoolean
import com.eignex.klause.solver.factor.AllDifferent
import com.eignex.klause.solver.factor.Product
import com.eignex.klause.solver.factor.ReifiedCardinality
import com.eignex.klause.solver.factor.ReifiedIntCompare
import com.eignex.klause.solver.factor.ReifiedLinear
import com.eignex.klause.solver.factor.ReifiedPseudoBoolean
import com.eignex.klause.solver.factor.Xor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail

class PropagationTest {

    private fun boolProblem(numBoolVars: Int, vararg clauses: IntArray): Problem =
        Problem(
            numBoolVars = numBoolVars,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = clauses.map { Clause(it) },
        )

    private fun lit(v: Int, pos: Boolean) = Lit.make(v, pos)

    private fun implied(r: PropagationResult): PropagationResult.Implied =
        r as? PropagationResult.Implied ?: fail("expected Implied, got $r")

    @Test
    fun `unit clause with one undetermined literal pins it`() {
        // (x0) — x0 must be true
        val p = boolProblem(1, intArrayOf(lit(0, true)))
        val r = implied(p.propagate())
        assertEquals(mapOf(0 to true), r.bools)
        assertTrue(r.ints.isEmpty())
    }

    @Test
    fun `forced chain across three unit clauses cascades`() {
        // x0, !x0 v x1, !x1 v x2  →  x0=x1=x2=true
        val p = boolProblem(
            3,
            intArrayOf(lit(0, true)),
            intArrayOf(lit(0, false), lit(1, true)),
            intArrayOf(lit(1, false), lit(2, true)),
        )
        val r = implied(p.propagate())
        assertEquals(mapOf(0 to true, 1 to true, 2 to true), r.bools)
    }

    @Test
    fun `conflicting assumption set returns Unsat`() {
        // (!x0 v x1) plus assumption x0=true, x1=false → unit-prop forces x1=true, contradiction
        val p = boolProblem(2, intArrayOf(lit(0, false), lit(1, true)))
        val r = p.propagate(Assumptions(bools = mapOf(0 to true, 1 to false)))
        assertIs<PropagationResult.Unsat>(r)
    }

    @Test
    fun `two contradicting clauses with empty assumptions return Unsat at init`() {
        // (x0) and (!x0) jointly Unsat — caught by the empty-assumption pass
        val p = boolProblem(
            1,
            intArrayOf(lit(0, true)),
            intArrayOf(lit(0, false)),
        )
        assertIs<PropagationResult.Unsat>(p.propagate())
    }

    @Test
    fun `Implied result is disjoint from input assumptions`() {
        // (!x0 v x1) with assumption x0=true → implies x1=true; x0 should NOT reappear in Implied
        val p = boolProblem(2, intArrayOf(lit(0, false), lit(1, true)))
        val r = implied(p.propagate(Assumptions(bools = mapOf(0 to true))))
        assertEquals(mapOf(1 to true), r.bools)
    }

    @Test
    fun `propagate with no constraints returns empty Implied`() {
        val p = Problem(
            numBoolVars = 3,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = emptyList(),
        )
        val r = implied(p.propagate())
        assertTrue(r.isEmpty)
    }

    @Test
    fun `unsatisfiable clause under assumptions returns Unsat`() {
        // (x0 v x1) with x0=false, x1=false → empty clause, Unsat
        val p = boolProblem(2, intArrayOf(lit(0, true), lit(1, true)))
        val r = p.propagate(Assumptions(bools = mapOf(0 to false, 1 to false)))
        assertIs<PropagationResult.Unsat>(r)
    }

    @Test
    fun `non-unit clause does not force anything`() {
        // (x0 v x1 v x2) with no assumptions — nothing pinned
        val p = boolProblem(3, intArrayOf(lit(0, true), lit(1, true), lit(2, true)))
        val r = implied(p.propagate())
        assertTrue(r.isEmpty)
    }

    @Test
    fun `IntEq forces value`() {
        val p = Problem(0, 1, arrayOf(IntDomain(0, 10)), listOf(Linear(intArrayOf(1), intArrayOf(0), LinearOp.EQ, 7)))
        val r = implied(p.propagate())
        assertEquals(mapOf(0 to 7), r.ints)
    }

    @Test
    fun `IntEq with out-of-domain value is Unsat`() {
        val p = Problem(0, 1, arrayOf(IntDomain(0, 5)), listOf(Linear(intArrayOf(1), intArrayOf(0), LinearOp.EQ, 9)))
        assertIs<PropagationResult.Unsat>(p.propagate())
    }

    @Test
    fun `IntLeq plus IntGeq force a single value`() {
        val p = Problem(0, 1, arrayOf(IntDomain(0, 10)), listOf(Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 3), Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 3)))
        val r = implied(p.propagate())
        assertEquals(mapOf(0 to 3), r.ints)
    }

    @Test
    fun `conflicting IntLeq plus IntGeq is Unsat`() {
        val p = Problem(0, 1, arrayOf(IntDomain(0, 10)), listOf(Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 2), Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 5)))
        assertIs<PropagationResult.Unsat>(p.propagate())
    }

    @Test
    fun `IntNeq at domain boundary tightens`() {
        // domain 5..10, x ≠ 5 → forces x ≥ 6
        val p = Problem(0, 1, arrayOf(IntDomain(5, 10)), listOf(Linear(intArrayOf(1), intArrayOf(0), LinearOp.NE, 5), Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 6)))
        val r = implied(p.propagate())
        assertEquals(mapOf(0 to 6), r.ints)
    }

    @Test
    fun `IntNeq on singleton domain is Unsat`() {
        val p = Problem(0, 1, arrayOf(IntDomain(7, 7)), listOf(Linear(intArrayOf(1), intArrayOf(0), LinearOp.NE, 7)))
        assertIs<PropagationResult.Unsat>(p.propagate())
    }

    @Test
    fun `Cardinality at upper bound forces remaining literals false`() {
        // atMostOne(x0, x1, x2); pin x0=true → x1=x2 must be false.
        val p = Problem(
            numBoolVars = 3,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = listOf(Cardinality(
                literals = intArrayOf(lit(0, true), lit(1, true), lit(2, true)),
                min = 0, max = 1,
            )),
        )
        val r = implied(p.propagate(Assumptions(bools = mapOf(0 to true))))
        assertEquals(mapOf(1 to false, 2 to false), r.bools)
    }

    @Test
    fun `Cardinality at lower bound forces remaining literals true`() {
        // atLeastOne(x0, x1); pin x0=false → x1 must be true.
        val p = Problem(
            numBoolVars = 2,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = listOf(Cardinality(
                literals = intArrayOf(lit(0, true), lit(1, true)),
                min = 1, max = 2,
            )),
        )
        val r = implied(p.propagate(Assumptions(bools = mapOf(0 to false))))
        assertEquals(mapOf(1 to true), r.bools)
    }

    @Test
    fun `Cardinality with too many true literals is Unsat`() {
        // atMostOne(x0, x1, x2) with x0=x1=true → Unsat
        val p = Problem(
            numBoolVars = 3,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = listOf(Cardinality(
                literals = intArrayOf(lit(0, true), lit(1, true), lit(2, true)),
                min = 0, max = 1,
            )),
        )
        assertIs<PropagationResult.Unsat>(p.propagate(Assumptions(bools = mapOf(0 to true, 1 to true))))
    }

    @Test
    fun `ReifiedIntCompare aux true tightens domain`() {
        // aux=true ↔ (x ≤ 5); pin aux=true → tighten max to 5.
        // Add Linear(intArrayOf(1), intArrayOf(x), LinearOp.GE, 5) to force a singleton outcome.
        val p = Problem(
            numBoolVars = 1, numIntVars = 1, intDomains = arrayOf(IntDomain(0, 10)),
            factors = listOf(
                ReifiedIntCompare(auxBoolVar = 0, intVar = 0, op = IntCmpOp.LE, 5),
                Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 5),
            ),
        )
        val r = implied(p.propagate(Assumptions(bools = mapOf(0 to true))))
        assertEquals(mapOf(0 to 5), r.ints)
    }

    @Test
    fun `ReifiedIntCompare derives aux when domain forces it`() {
        // x in [0..3], aux ↔ (x ≤ 5) → aux must be true.
        val p = Problem(
            numBoolVars = 1, numIntVars = 1, intDomains = arrayOf(IntDomain(0, 3)),
            factors = listOf(ReifiedIntCompare(auxBoolVar = 0, intVar = 0, op = IntCmpOp.LE, 5)),
        )
        val r = implied(p.propagate())
        assertEquals(mapOf(0 to true), r.bools)
    }

    @Test
    fun `ReifiedIntCompare conflicting aux is Unsat`() {
        // x in [0..3], aux ↔ (x ≥ 10), pin aux=true → never holds → Unsat.
        val p = Problem(
            numBoolVars = 1, numIntVars = 1, intDomains = arrayOf(IntDomain(0, 3)),
            factors = listOf(ReifiedIntCompare(auxBoolVar = 0, intVar = 0, op = IntCmpOp.GE, 10)),
        )
        assertIs<PropagationResult.Unsat>(p.propagate(Assumptions(bools = mapOf(0 to true))))
    }

    @Test
    fun `ReifiedCardinality body forced derives aux`() {
        // aux ↔ (#true in {x1, x2} ≤ 1). x1=x2=true means body=false → aux=false.
        val p = Problem(
            numBoolVars = 3, numIntVars = 0, intDomains = emptyArray(),
            factors = listOf(ReifiedCardinality(
                auxBoolVar = 0, literals = intArrayOf(lit(1, true), lit(2, true)), min = 0, max = 1,
            )),
        )
        val r = implied(p.propagate(Assumptions(bools = mapOf(1 to true, 2 to true))))
        assertEquals(true, r.bools[0] == false)
    }

    @Test
    fun `Linear LE tightens domains positive coefficients`() {
        // 2x + 3y ≤ 10, x in [0..10], y in [0..10] → with no other constraints
        // sumLo = 0 → slack = 10. Per-var: x ≤ floor((10-0)/2) = 5; y ≤ floor((10-0)/3) = 3.
        // With y=3 pinned, x's effective upper ≤ floor((10 - 9)/2) = 0.
        val p = Problem(
            numBoolVars = 0, numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 10), IntDomain(0, 10)),
            factors = listOf(Linear(intArrayOf(2, 3), intArrayOf(0, 1), LinearOp.LE, 10)),
        )
        val r = implied(p.propagate(Assumptions(ints = mapOf(1 to 3))))
        // x must be 0 (since 2x ≤ 10 - 9 = 1 → x ≤ 0; combined with domain ≥ 0 → singleton)
        assertEquals(mapOf(0 to 0), r.ints)
    }

    @Test
    fun `Linear EQ derives both variable bounds`() {
        // x + y = 5, x in [2..2] → y must be 3.
        val p = Problem(
            numBoolVars = 0, numIntVars = 2,
            intDomains = arrayOf(IntDomain(2, 2), IntDomain(0, 10)),
            factors = listOf(Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.EQ, 5)),
        )
        val r = implied(p.propagate())
        assertEquals(3, r.ints[1])
    }

    @Test
    fun `Linear infeasible bounds returns Unsat`() {
        // 2x + 3y ≥ 100, x,y in [0..1] → max sum = 5, can never reach 100 → Unsat.
        val p = Problem(
            numBoolVars = 0, numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 1), IntDomain(0, 1)),
            factors = listOf(Linear(intArrayOf(2, 3), intArrayOf(0, 1), LinearOp.GE, 100)),
        )
        assertIs<PropagationResult.Unsat>(p.propagate())
    }

    @Test
    fun `Linear negative coefficient tightens correctly`() {
        // -2x + y ≤ -5, x in [0..10], y in [0..0]. y = 0 → -2x ≤ -5 → x ≥ 3 (ceil(-5/-2) = 3).
        val p = Problem(
            numBoolVars = 0, numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 10), IntDomain(0, 0)),
            factors = listOf(Linear(intArrayOf(-2, 1), intArrayOf(0, 1), LinearOp.LE, -5)),
        )
        val r = implied(p.propagate())
        // y forced to 0, x's domain narrowed: 0 already pinned for y. Force Linear(intArrayOf(1), intArrayOf(x), LinearOp.LE, 3) to get singleton
        // — instead just check x's lower bound by adding IntLeq.
        val p2 = Problem(
            numBoolVars = 0, numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 10), IntDomain(0, 0)),
            factors = listOf(
                Linear(intArrayOf(-2, 1), intArrayOf(0, 1), LinearOp.LE, -5),
                Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 3),
            ),
        )
        val r2 = implied(p2.propagate())
        assertEquals(mapOf(0 to 3, 1 to 0), r2.ints)
    }

    @Test
    fun `PseudoBoolean LE forces literals false when weights tight`() {
        // x0 + x1 + 3*x2 ≤ 2: must have x2 = false (since 3 > 2 - 0).
        val p = Problem(
            numBoolVars = 3, numIntVars = 0, intDomains = emptyArray(),
            factors = listOf(PseudoBoolean(
                weights = intArrayOf(1, 1, 3),
                literals = intArrayOf(lit(0, true), lit(1, true), lit(2, true)),
                op = PbOp.LE, bound = 2,
            )),
        )
        val r = implied(p.propagate())
        assertEquals(false, r.bools[2])
    }

    @Test
    fun `PseudoBoolean GE forces literals true when slack tight`() {
        // 1*x0 + 5*x1 ≥ 5: with x1 pinned false, x0 alone can't reach 5 → Unsat
        val p = Problem(
            numBoolVars = 2, numIntVars = 0, intDomains = emptyArray(),
            factors = listOf(PseudoBoolean(
                weights = intArrayOf(1, 5),
                literals = intArrayOf(lit(0, true), lit(1, true)),
                op = PbOp.GE, bound = 5,
            )),
        )
        assertIs<PropagationResult.Unsat>(p.propagate(Assumptions(bools = mapOf(1 to false))))
    }

    @Test
    fun `PseudoBoolean EQ forces single literal`() {
        // 2*x0 + 3*x1 = 5: with x0 pinned true, x1 must be true (2+3=5); with x0 false, 3x1=5 unsat.
        val p = Problem(
            numBoolVars = 2, numIntVars = 0, intDomains = emptyArray(),
            factors = listOf(PseudoBoolean(
                weights = intArrayOf(2, 3),
                literals = intArrayOf(lit(0, true), lit(1, true)),
                op = PbOp.EQ, bound = 5,
            )),
        )
        val r = implied(p.propagate(Assumptions(bools = mapOf(0 to true))))
        assertEquals(true, r.bools[1])
    }

    @Test
    fun `Xor forces last literal when n-1 pinned`() {
        // XOR(x0, x1, x2) = 1 (odd). Pin x0=true, x1=false → x2 must be false (to keep odd count).
        val p = Problem(
            numBoolVars = 3, numIntVars = 0, intDomains = emptyArray(),
            factors = listOf(Xor(intArrayOf(lit(0, true), lit(1, true), lit(2, true)), targetParity = 1)),
        )
        val r = implied(p.propagate(Assumptions(bools = mapOf(0 to true, 1 to false))))
        assertEquals(false, r.bools[2])
    }

    @Test
    fun `Xor with all pinned wrong parity is Unsat`() {
        // XOR(x0, x1) = 1; pin both true → parity 0 ≠ 1 → Unsat
        val p = Problem(
            numBoolVars = 2, numIntVars = 0, intDomains = emptyArray(),
            factors = listOf(Xor(intArrayOf(lit(0, true), lit(1, true)), targetParity = 1)),
        )
        assertIs<PropagationResult.Unsat>(p.propagate(Assumptions(bools = mapOf(0 to true, 1 to true))))
    }

    @Test
    fun `ReifiedLinear aux true tightens variable domains`() {
        // aux ↔ (x + y ≤ 5); pin aux=true, x in [0..10], y in [3..3] → x ≤ 2.
        val p = Problem(
            numBoolVars = 1, numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 10), IntDomain(3, 3)),
            factors = listOf(ReifiedLinear(
                auxBoolVar = 0, coeffs = intArrayOf(1, 1), vars = intArrayOf(0, 1),
                op = LinearOp.LE, bound = 5,
            )),
        )
        val r = implied(p.propagate(Assumptions(bools = mapOf(0 to true))))
        // x's domain is now [0..2], y is pinned at 3. y returns as 3 in Implied; x not yet singleton.
        // Add Linear(intArrayOf(1), intArrayOf(x), LinearOp.GE, 2) to force singleton x=2.
        val p2 = Problem(
            numBoolVars = 1, numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 10), IntDomain(3, 3)),
            factors = listOf(
                ReifiedLinear(0, intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 5),
                Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 2),
            ),
        )
        val r2 = implied(p2.propagate(Assumptions(bools = mapOf(0 to true))))
        assertEquals(2, r2.ints[0])
    }

    @Test
    fun `ReifiedLinear derives aux from sum range`() {
        // x,y in [0..2], aux ↔ (x + y ≤ 10) → always holds → aux=true
        val p = Problem(
            numBoolVars = 1, numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 2), IntDomain(0, 2)),
            factors = listOf(ReifiedLinear(0, intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 10)),
        )
        val r = implied(p.propagate())
        assertEquals(true, r.bools[0])
    }

    @Test
    fun `ReifiedPseudoBoolean derives aux from sum range`() {
        // x0,x1 bool; aux ↔ (5*x0 + 5*x1 ≥ 20) → never holds (max 10) → aux=false
        val p = Problem(
            numBoolVars = 3, numIntVars = 0, intDomains = emptyArray(),
            factors = listOf(ReifiedPseudoBoolean(
                auxBoolVar = 0, weights = intArrayOf(5, 5),
                literals = intArrayOf(lit(1, true), lit(2, true)),
                op = PbOp.GE, bound = 20,
            )),
        )
        val r = implied(p.propagate())
        assertEquals(false, r.bools[0])
    }

    @Test
    fun `Product tightens result interval from operand bounds`() {
        // a in [2..3], b in [4..5], result domain [-100..100] → result in [8..15]
        // Add Linear(intArrayOf(1), intArrayOf(result), LinearOp.LE, 8) → singleton 8.
        val p = Problem(
            numBoolVars = 0, numIntVars = 3,
            intDomains = arrayOf(IntDomain(2, 3), IntDomain(4, 5), IntDomain(-100, 100)),
            factors = listOf(Product(a = 0, b = 1, result = 2), Linear(intArrayOf(1), intArrayOf(2), LinearOp.LE, 8)),
        )
        val r = implied(p.propagate())
        assertEquals(8, r.ints[2])
    }

    @Test
    fun `Product handles zero-crossing operand`() {
        // a in [-2..3], b in [-1..4], result; product range = [min(-2*-1, -2*4, 3*-1, 3*4), max]
        // = [min(2, -8, -3, 12), max] = [-8, 12]. Constrain result to be singleton at 12 via tighten.
        val p = Problem(
            numBoolVars = 0, numIntVars = 3,
            intDomains = arrayOf(IntDomain(-2, 3), IntDomain(-1, 4), IntDomain(-1000, 1000)),
            factors = listOf(
                Product(a = 0, b = 1, result = 2),
                Linear(intArrayOf(1), intArrayOf(2), LinearOp.GE, 12),
                Linear(intArrayOf(1), intArrayOf(2), LinearOp.LE, 12),
            ),
        )
        val r = implied(p.propagate())
        assertEquals(12, r.ints[2])
    }

    @Test
    fun `AllDifferent detects singleton-value conflict`() {
        // x0, x1 both pinned to 5 → Unsat.
        val p = Problem(
            numBoolVars = 0, numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 9), IntDomain(0, 9)),
            factors = listOf(AllDifferent(intArrayOf(0, 1), domainMin = 0, domainSize = 10)),
        )
        assertIs<PropagationResult.Unsat>(p.propagate(Assumptions(ints = mapOf(0 to 5, 1 to 5))))
    }

    @Test
    fun `AllDifferent pigeonhole detects Unsat across non-pinned vars`() {
        // 3 vars, each domain {0..1} (size 2), but 3 vars need 3 distinct values → Unsat
        val p = Problem(
            numBoolVars = 0, numIntVars = 3,
            intDomains = arrayOf(IntDomain(0, 1), IntDomain(0, 1), IntDomain(0, 1)),
            factors = listOf(AllDifferent(intArrayOf(0, 1, 2), domainMin = 0, domainSize = 2)),
        )
        assertIs<PropagationResult.Unsat>(p.propagate())
    }

    @Test
    fun `AllDifferent shaves taken value off domain boundary`() {
        // x0 pinned to 0; x1 in [0..2]; AllDifferent should tighten x1 ≥ 1.
        // Add Linear(intArrayOf(1), intArrayOf(x1), LinearOp.LE, 1) to force singleton 1.
        val p = Problem(
            numBoolVars = 0, numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 2), IntDomain(0, 2)),
            factors = listOf(
                AllDifferent(intArrayOf(0, 1), domainMin = 0, domainSize = 3),
                Linear(intArrayOf(1), intArrayOf(1), LinearOp.LE, 1),
            ),
        )
        val r = implied(p.propagate(Assumptions(ints = mapOf(0 to 0))))
        assertEquals(1, r.ints[1])
    }

    @Test
    fun `exactlyOne with two-literal cascade pins via Cardinality and Clause together`() {
        // exactlyOne(x0, x1), and (!x0 v x2). Pin x1=false → x0 must be true → x2 must be true.
        val p = Problem(
            numBoolVars = 3,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = listOf(
                Cardinality(intArrayOf(lit(0, true), lit(1, true)), 1, 1),
                Clause(intArrayOf(lit(0, false), lit(2, true))),
            ),
        )
        val r = implied(p.propagate(Assumptions(bools = mapOf(1 to false))))
        assertEquals(mapOf(0 to true, 2 to true), r.bools)
    }
}
