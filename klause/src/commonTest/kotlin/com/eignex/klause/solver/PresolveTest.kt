package com.eignex.klause.solver

import com.eignex.klause.ast.PbOp
import com.eignex.klause.solver.factor.Linear
import com.eignex.klause.solver.factor.LinearOp
import com.eignex.klause.solver.factor.PseudoBoolean
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * GCD coefficient strengthening (#319). Every test pins down the invariant that matters: the
 * rewritten constraint accepts *exactly* the same assignments as the original, checked by
 * enumerating the whole assignment space.
 */
class PresolveTest {

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

    private fun assertLinearEquivalent(numVars: Int, domain: Int, original: Linear) {
        val problem = Problem(0, numVars, Array(numVars) { IntDomain(0, domain) }, listOf(original))
        val rewritten = Presolve.strengthenCoefficients(problem).factors.getOrNull(0) as? Linear
        val assign = IntArray(numVars)
        enumerate(numVars, domain + 1) { mask ->
            for (v in 0 until numVars) assign[v] = mask[v]
            val origSat = evalLinear(original, assign)
            val newSat = rewritten?.let { evalLinear(it, assign) } ?: true // dropped ⇒ always-true
            assertEquals(origSat, newSat, "linear disagrees at ${assign.toList()}: $original -> $rewritten")
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

    /** Enumerate every assignment of [numVars] vars each in `0 until radix`. */
    private fun enumerate(numVars: Int, radix: Int, body: (IntArray) -> Unit) {
        val digits = IntArray(numVars)
        val total = generateSequence(1) { it * radix }.elementAt(numVars)
        repeat(total) { n ->
            var x = n
            for (i in 0 until numVars) {
                digits[i] = x % radix
                x /= radix
            }
            body(digits)
        }
    }

    @Test
    fun `gcd reduction preserves each linear operator`() {
        // coeffs 2,4 share gcd 2.
        assertLinearEquivalent(2, 4, Linear(intArrayOf(2, 4), intArrayOf(0, 1), LinearOp.LE, 5))
        assertLinearEquivalent(2, 4, Linear(intArrayOf(2, 4), intArrayOf(0, 1), LinearOp.GE, 5))
        assertLinearEquivalent(2, 4, Linear(intArrayOf(2, 4), intArrayOf(0, 1), LinearOp.EQ, 6)) // divisible
        assertLinearEquivalent(2, 4, Linear(intArrayOf(2, 4), intArrayOf(0, 1), LinearOp.EQ, 5)) // indivisible
        assertLinearEquivalent(2, 4, Linear(intArrayOf(2, 4), intArrayOf(0, 1), LinearOp.NE, 5)) // dropped
        assertLinearEquivalent(2, 4, Linear(intArrayOf(2, 4), intArrayOf(0, 1), LinearOp.NE, 6))
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
        val original = Linear(intArrayOf(2, 3), intArrayOf(0, 1), LinearOp.LE, 5)
        val problem = Problem(0, 2, Array(2) { IntDomain(0, 4) }, listOf(original))
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
