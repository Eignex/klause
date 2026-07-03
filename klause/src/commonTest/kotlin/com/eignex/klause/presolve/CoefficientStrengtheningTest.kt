package com.eignex.klause.presolve

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.factor.bool.PseudoBoolean
import com.eignex.klause.model.PbOp
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.solver.Assumptions
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * GCD coefficient strengthening (#319). Every test pins down the invariant that matters: the
 * rewritten constraint accepts *exactly* the same assignments as the original, checked by
 * enumerating the whole assignment space.
 */
class CoefficientStrengtheningTest {

    @Test
    fun `coefficient lifting is probing-informed - SAC-tightened bounds give a tighter lift`() {
        // The lift target 2*x0 + 5*x2 <= 8 with x0 in [0,3], x2 in [0,1]: its lift clamps coefficients
        // to d = (max activity) - bound. SAC probing tightens x0's upper bound, shrinking the activity
        // and so the clamp — i.e. lifting reads the *baked* (probed) domains, not the declared ones.
        fun strengthenedTarget(probe: Boolean): Linear {
            val p = Problem(
                numBoolVars = 0,
                numIntVars = 3,
                intDomains = arrayOf(IntDomain(0, 3), IntDomain(0, 3), IntDomain(0, 1)),
                factors = listOf(
                    Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.EQ, 0), // x0 = x1
                    Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 4), // x0 + x1 <= 4 (SAC: x0 <= 2)
                    Linear(intArrayOf(2, 5), intArrayOf(0, 2), LinearOp.LE, 8), // lift target
                ),
                probeIntBounds = probe,
            )
            val out = Presolve.strengthenCoefficients(p)
            return out.factors.filterIsInstance<Linear>().single { 0 in it.vars && 2 in it.vars }
        }
        // Probing off: root propagation leaves x0 in [0,3] ⇒ d = 2*3 + 5*1 - 8 = 3, x2's 5 clamps to 3.
        val off = strengthenedTarget(probe = false)
        assertEquals(3, off.coeffs[off.vars.indexOf(2)], "without probing, x2's coefficient clamps to 3")
        // Probing on: SAC tightens x0 to [0,2] ⇒ d = 2*2 + 5*1 - 8 = 1, both coefficients clamp to 1.
        val on = strengthenedTarget(probe = true)
        assertEquals(1, on.coeffs[on.vars.indexOf(2)], "with probing, x2's coefficient clamps to 1")
        assertEquals(1, on.coeffs[on.vars.indexOf(0)], "with probing, x0's coefficient clamps to 1")
    }

    private fun evalLinear(f: Linear, assign: IntArray): Boolean {
        var sum = 0L
        for (i in f.vars.indices) sum += f.coeffs[i].toLong() * assign[f.vars[i]]
        return when (f.op) {
            LinearOp.LE -> sum <= f.bound
            LinearOp.GE -> sum >= f.bound
            LinearOp.EQ -> sum == f.bound.toLong()
            LinearOp.NE -> sum != f.bound.toLong()
        }
    }

    private fun evalPb(f: PseudoBoolean, bools: BooleanArray): Boolean {
        var sum = 0L
        for (i in f.literals.indices) {
            if (Lit.evaluate(f.literals[i], bools[Lit.variable(f.literals[i])])) sum += f.weights[i]
        }
        return when (f.op) {
            PbOp.LE -> sum <= f.bound
            PbOp.GE -> sum >= f.bound
            PbOp.EQ -> sum == f.bound.toLong()
        }
    }

    private fun assertLinearEquivalent(numVars: Int, domain: Int, original: Linear) =
        assertLinearEquivalent(Array(numVars) { 0..domain }, original)

    /**
     * Feasible-set equivalence over arbitrary per-variable integer domains [domains]. Enumeration
     * runs over the problem's *post-construction* domains: the [Problem] constructor folds the
     * constraint's own bound deductions into the domains (#148 init tightening), so that — not the
     * declared range — is the feasible region presolve must preserve.
     */
    private fun assertLinearEquivalent(domains: Array<IntRange>, original: Linear) {
        val numVars = domains.size
        val problem =
            Problem(0, numVars, Array(numVars) { IntDomain(domains[it].first, domains[it].last) }, listOf(original))
        // The rewrite may produce one factor (lifted / gcd-reduced), none (dropped ⇒ always-true), or
        // a multi-factor contradiction (an indivisible equality ⇒ infeasible); the feasible set is the
        // conjunction of whatever rewritten factors remain.
        val rewritten = Presolve.strengthenCoefficients(problem).factors.filterIsInstance<Linear>()
        val values = Array(numVars) { v ->
            val d = problem.intDomains[v]
            IntArray(d.size) { d.valueAt(it) }
        }
        val assign = IntArray(numVars)
        enumerateMixed(values) { idx ->
            for (v in 0 until numVars) assign[v] = values[v][idx[v]]
            val origSat = evalLinear(original, assign)
            val newSat = rewritten.all { evalLinear(it, assign) }
            assertEquals(origSat, newSat, "linear disagrees at ${assign.toList()}: $original -> $rewritten")
        }
    }

    /** Enumerate the cartesian product of index ranges `0 until values[i].size`. */
    private fun enumerateMixed(values: Array<IntArray>, body: (IntArray) -> Unit) {
        val idx = IntArray(values.size)
        while (true) {
            body(idx)
            var i = 0
            while (i < values.size) {
                if (idx[i] < values[i].size - 1) {
                    idx[i]++
                    break
                }
                idx[i] = 0
                i++
            }
            if (i == values.size) return
        }
    }

    private fun assertPbEquivalent(numVars: Int, original: PseudoBoolean) {
        val problem = Problem(numVars, 0, emptyArray(), listOf(original))
        val rewritten = Presolve.strengthenCoefficients(problem).factors.getOrNull(0) as? PseudoBoolean
        val bools = BooleanArray(numVars)
        for (mask in 0 until (1 shl numVars)) {
            for (v in 0 until numVars) bools[v] = (mask shr v) and 1 == 1
            val origSat = evalPb(original, bools)
            val newSat = rewritten?.let { evalPb(it, bools) } ?: true
            assertEquals(origSat, newSat, "pb disagrees at ${bools.toList()}: $original -> $rewritten")
        }
    }

    @Test
    fun `gcd reduction preserves each linear operator`() {
        // coeffs 2,4 share gcd 2.
        assertLinearEquivalent(2, 4, Linear(intArrayOf(2, 4), intArrayOf(0, 1), LinearOp.LE, 5))
        assertLinearEquivalent(2, 4, Linear(intArrayOf(2, 4), intArrayOf(0, 1), LinearOp.GE, 5))
        assertLinearEquivalent(2, 4, Linear(intArrayOf(2, 4), intArrayOf(0, 1), LinearOp.EQ, 6)) // divisible
        assertLinearEquivalent(2, 4, Linear(intArrayOf(2, 4), intArrayOf(0, 1), LinearOp.NE, 5)) // dropped
        assertLinearEquivalent(2, 4, Linear(intArrayOf(2, 4), intArrayOf(0, 1), LinearOp.NE, 6))
    }

    private fun assertInfeasibleAfterStrengthen(problem: Problem) {
        val out = Presolve.strengthenCoefficients(problem)
        assertTrue(
            out.propagate(Assumptions.None) is PropagationResult.Unsat,
            "strengthened problem should be infeasible: ${out.factors}",
        )
    }

    @Test
    fun `an indivisible linear equality is rewritten to an infeasible problem`() {
        // 2*x0 + 4*x1 = 5: the left-hand side is always even, so it can never equal 5.
        assertInfeasibleAfterStrengthen(
            Problem(
                0,
                2,
                arrayOf(IntDomain(0, 4), IntDomain(0, 4)),
                listOf(Linear(intArrayOf(2, 4), intArrayOf(0, 1), LinearOp.EQ, 5)),
            ),
        )
    }

    @Test
    fun `an indivisible pseudo-boolean equality is rewritten to an infeasible problem`() {
        // 2*x0 + 4*x1 = 5 over booleans: even left-hand side can never equal 5.
        val lits = intArrayOf(Lit.make(0, true), Lit.make(1, true))
        assertInfeasibleAfterStrengthen(
            Problem(2, 0, emptyArray(), listOf(PseudoBoolean(intArrayOf(2, 4), lits, PbOp.EQ, 5))),
        )
    }

    @Test
    fun `a divisible equality is not flagged infeasible`() {
        // 2*x0 + 4*x1 = 6 divides through cleanly and stays satisfiable (x0=1, x1=1).
        val out = Presolve.strengthenCoefficients(
            Problem(
                0,
                2,
                arrayOf(IntDomain(0, 4), IntDomain(0, 4)),
                listOf(Linear(intArrayOf(2, 4), intArrayOf(0, 1), LinearOp.EQ, 6)),
            ),
        )
        assertTrue(out.propagate(Assumptions.None) !is PropagationResult.Unsat, "divisible equality stays feasible")
    }

    @Test
    fun `gcd reduction handles negative coefficients and bounds`() {
        assertLinearEquivalent(2, 4, Linear(intArrayOf(-3, 6), intArrayOf(0, 1), LinearOp.LE, -4))
        assertLinearEquivalent(2, 4, Linear(intArrayOf(-3, 6), intArrayOf(0, 1), LinearOp.GE, -4))
    }

    @Test
    fun `gcd reduction preserves pseudo-boolean constraints`() {
        val lits = intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true))
        assertPbEquivalent(3, PseudoBoolean(intArrayOf(2, 4, 6), lits, PbOp.LE, 7))
        assertPbEquivalent(3, PseudoBoolean(intArrayOf(2, 4, 6), lits, PbOp.GE, 7))
        assertPbEquivalent(3, PseudoBoolean(intArrayOf(2, 4, 6), lits, PbOp.EQ, 8))
    }

    @Test
    fun `knapsack lifting preserves the feasible set`() {
        val lits = intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true))
        assertPbEquivalent(3, PseudoBoolean(intArrayOf(5, 1, 1), lits, PbOp.LE, 3)) // d=4, w0=5 clamps to 4
        assertPbEquivalent(3, PseudoBoolean(intArrayOf(10, 2, 3), lits, PbOp.LE, 4))
        assertPbEquivalent(3, PseudoBoolean(intArrayOf(3, 3, 3), lits, PbOp.LE, 9)) // redundant: drops
        assertPbEquivalent(3, PseudoBoolean(intArrayOf(5, -2, 1), lits, PbOp.LE, 2)) // negative weight normalized
        val mixed = intArrayOf(Lit.make(0, true), Lit.make(1, false), Lit.make(2, true))
        assertPbEquivalent(3, PseudoBoolean(intArrayOf(6, 2, 1), mixed, PbOp.LE, 4))
    }

    @Test
    fun `random knapsack constraints preserve the feasible set`() {
        val rng = Random(0x5A7)
        repeat(500) {
            val n = 2 + rng.nextInt(3) // 2..4
            val lits = IntArray(n) { i -> Lit.make(i, rng.nextBoolean()) }
            val weights = IntArray(n) { rng.nextInt(9) - 3 } // -3..5
            assertPbEquivalent(n, PseudoBoolean(weights, lits, PbOp.LE, rng.nextInt(15) - 3))
        }
    }

    @Test
    fun `ge and eq pseudo-boolean lifting`() {
        val lits = intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true))
        // GE: complemented to <= then lifted.
        assertPbEquivalent(3, PseudoBoolean(intArrayOf(5, 1, 1), lits, PbOp.GE, 2))
        assertPbEquivalent(3, PseudoBoolean(intArrayOf(10, 2, 3), lits, PbOp.GE, 9))
        // EQ: not liftable by clamping — must stay feasible-set-equivalent (no wrong clamp).
        assertPbEquivalent(3, PseudoBoolean(intArrayOf(5, 1, 1), lits, PbOp.EQ, 3))
    }

    @Test
    fun `binary linear knapsack lifting`() {
        // 0/1 domain, positive coeffs, LE: same cover-dual clamp as pseudo-Boolean.
        assertLinearEquivalent(3, 1, Linear(intArrayOf(5, 1, 1), intArrayOf(0, 1, 2), LinearOp.LE, 3))
        assertLinearEquivalent(3, 1, Linear(intArrayOf(10, 2, 3), intArrayOf(0, 1, 2), LinearOp.LE, 4))
        // non-binary domain (handled by assertLinearEquivalent's domain arg) must NOT be lifted.
        assertLinearEquivalent(3, 4, Linear(intArrayOf(5, 1, 1), intArrayOf(0, 1, 2), LinearOp.LE, 3))
    }

    @Test
    fun `bounded integer linear lifting`() {
        // 5x ≤ 12, x ∈ [0,3]: Amax=15, d=3, coeff clamps 5→3 ⇒ 3x ≤ 6 (both ⇒ x ≤ 2).
        assertLinearEquivalent(arrayOf(0..3), Linear(intArrayOf(5), intArrayOf(0), LinearOp.LE, 12))
        // d ≤ 0 ⇒ always satisfied (max activity 10 ≤ 12), dropped.
        assertLinearEquivalent(arrayOf(0..2), Linear(intArrayOf(5), intArrayOf(0), LinearOp.LE, 12))
        // Mixed bounds, two vars: 7x + 2y ≤ 9, x ∈ [0,2], y ∈ [0,3].
        assertLinearEquivalent(arrayOf(0..2, 0..3), Linear(intArrayOf(7, 2), intArrayOf(0, 1), LinearOp.LE, 9))
        // Non-zero lower bounds.
        assertLinearEquivalent(arrayOf(1..3, 2..4), Linear(intArrayOf(6, 1), intArrayOf(0, 1), LinearOp.LE, 14))
    }

    @Test
    fun `bounded integer lifting with negative coefficients and ge`() {
        // 3x − 2y ≤ 2, x ∈ [0,2], y ∈ [1,3]: clamps to x ≤ y.
        assertLinearEquivalent(arrayOf(0..2, 1..3), Linear(intArrayOf(3, -2), intArrayOf(0, 1), LinearOp.LE, 2))
        // GE complemented to ≤ then lifted.
        assertLinearEquivalent(arrayOf(0..3), Linear(intArrayOf(-5), intArrayOf(0), LinearOp.GE, -12))
        assertLinearEquivalent(arrayOf(0..2, 1..3), Linear(intArrayOf(2, 5), intArrayOf(0, 1), LinearOp.GE, 8))
    }

    @Test
    fun `bounded integer eq and ne are not lifted`() {
        // EQ / NE pass through coefficient lifting unchanged (feasible set must still match).
        assertLinearEquivalent(arrayOf(0..3), Linear(intArrayOf(5), intArrayOf(0), LinearOp.EQ, 10))
        assertLinearEquivalent(arrayOf(0..3, 0..2), Linear(intArrayOf(5, 2), intArrayOf(0, 1), LinearOp.NE, 6))
    }

    @Test
    fun `random bounded integer constraints preserve the feasible set`() {
        val rng = Random(0x372)
        repeat(2000) {
            val n = 1 + rng.nextInt(3) // 1..3
            val domains = Array(n) {
                val lo = rng.nextInt(5) - 2 // -2..2
                lo..(lo + rng.nextInt(4)) // span 0..3
            }
            val coeffs = IntArray(n) { rng.nextInt(13) - 6 } // -6..6
            val op = LinearOp.entries[rng.nextInt(LinearOp.entries.size)]
            val bound = rng.nextInt(31) - 15
            assertLinearEquivalent(domains, Linear(coeffs, IntArray(n) { idx -> idx }, op, bound))
        }
    }

    @Test
    fun `random ge knapsack constraints preserve the feasible set`() {
        val rng = Random(0x6E)
        repeat(400) {
            val n = 2 + rng.nextInt(3)
            val lits = IntArray(n) { i -> Lit.make(i, rng.nextBoolean()) }
            val weights = IntArray(n) { 1 + rng.nextInt(6) }
            assertPbEquivalent(n, PseudoBoolean(weights, lits, PbOp.GE, rng.nextInt(15)))
        }
    }

    @Test
    fun `coprime coefficients are left untouched`() {
        // Coprime (no GCD reduction), loose enough that no coefficient exceeds the cover slack
        // d = Amax − b = 4 (so no lifting), and not so loose the bound tightens the [0,2] domains.
        val original = Linear(intArrayOf(2, 3), intArrayOf(0, 1), LinearOp.LE, 6)
        val problem = Problem(0, 2, Array(2) { IntDomain(0, 2) }, listOf(original))
        assertTrue(Presolve.strengthenCoefficients(problem).factors[0] === original, "coprime factor was rewritten")
    }

    @Test
    fun `random scaled constraints preserve the feasible set`() {
        val rng = Random(0x6CD)
        repeat(400) {
            val n = 2 + rng.nextInt(2) // 2..3
            val g = 2 + rng.nextInt(4) // 2..5
            val coeffs = IntArray(n) { (1 + rng.nextInt(4)) * g * (if (rng.nextBoolean()) 1 else -1) }
            val op = LinearOp.entries[rng.nextInt(LinearOp.entries.size)]
            val bound = rng.nextInt(41) - 20
            assertLinearEquivalent(n, 3, Linear(coeffs, IntArray(n) { idx -> idx }, op, bound))
        }
    }
}
