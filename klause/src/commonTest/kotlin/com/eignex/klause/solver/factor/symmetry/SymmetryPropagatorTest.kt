package com.eignex.klause.solver.factor.symmetry

import com.eignex.klause.model.PbOp
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.backtrack.BacktrackSolver
import com.eignex.klause.solver.brute.BruteForceParams
import com.eignex.klause.solver.brute.BruteForceSolver
import com.eignex.klause.solver.factor.arithmetic.Linear
import com.eignex.klause.solver.factor.arithmetic.LinearOp
import com.eignex.klause.solver.factor.arithmetic.ReifiedLinear
import com.eignex.klause.solver.factor.bool.PseudoBoolean
import com.eignex.klause.solver.factor.global.AllDifferent
import com.eignex.klause.solver.presolve.Presolve
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end soundness of dynamic symmetry handling under CDCL search. The lex-leader filtering
 * attaches inline antecedents to every pin/tighten; an unsound antecedent yields an unsound learned
 * clause that cuts valid solutions — a defect a propagation-only check (which never learns) can miss.
 * So each case runs the full [BacktrackSolver] (with clause learning) on the symmetry-broken problem
 * and compares its enumerated solutions against the brute-force solutions of the *original*: every
 * surviving solution must be a real one (no spurious), and the broken problem must stay satisfiable
 * exactly when the original is (no false UNSAT from an unsound reason).
 */
class SymmetryPropagatorTest {

    private fun key(s: Sample) = s.bools.toList() to s.ints.toList()

    private fun assertSoundUnderSearch(name: String, problem: Problem) {
        val broken = Presolve.breakSymmetries(problem)
        val original = BruteForceSolver(
            problem,
        ).enumerate(BruteForceParams(randomSeed = 0L)).map { key(it) }.toHashSet()
        val survivors = BacktrackSolver(broken).enumerate(BacktrackParams(randomSeed = 1L))
            .take(100_000).map { key(it) }.toList()
        for (s in survivors) {
            assertTrue(s in original, "$name: search produced a non-solution of the original — unsound learned clause")
        }
        assertEquals(
            original.isNotEmpty(),
            survivors.isNotEmpty(),
            "$name: breaking changed satisfiability ($original.size originals, ${survivors.size} survivors)",
        )
        assertTrue(survivors.size <= original.size, "$name: breaking added solutions")
    }

    @Test
    fun `alldifferent permutation symmetry stays sound under search`() {
        val problem = Problem(
            0,
            3,
            arrayOf(IntDomain(0, 2), IntDomain(0, 2), IntDomain(0, 2)),
            listOf(AllDifferent(intArrayOf(0, 1, 2), domainMin = 0, domainSize = 3)),
        )
        assertSoundUnderSearch("alldiff", problem)
    }

    @Test
    fun `interchangeable int matrix rows stay sound under search`() {
        val problem = Problem(
            0,
            4,
            Array(4) { IntDomain(0, 3) },
            listOf(
                Linear(intArrayOf(1, 2), intArrayOf(0, 1), LinearOp.LE, 3),
                Linear(intArrayOf(1, 2), intArrayOf(2, 3), LinearOp.LE, 3),
            ),
        )
        assertSoundUnderSearch("int-rows", problem)
    }

    @Test
    fun `interchangeable bool rows stay sound under search`() {
        val problem = Problem(
            4,
            0,
            emptyArray(),
            listOf(
                PseudoBoolean(intArrayOf(1, 2), intArrayOf(Lit.make(0, true), Lit.make(1, true)), PbOp.LE, 2),
                PseudoBoolean(intArrayOf(1, 2), intArrayOf(Lit.make(2, true), Lit.make(3, true)), PbOp.LE, 2),
            ),
        )
        assertSoundUnderSearch("bool-rows", problem)
    }

    @Test
    fun `random symmetric instances stay satisfiable under search including orbital fixing`() {
        // Orbital fixing is soundness-critical: a wrong stabiliser orbit would tighten x_k <= x_j for a
        // j the lex-leader does not actually order, cutting valid solutions (false UNSAT). Breaking only
        // *adds* constraints, so it can never invent a non-solution — the failure mode is losing the
        // last representative of an orbit. So the sharp, cheap check is that the broken problem stays
        // satisfiable exactly when the original is, over many random S_n-symmetric instances (full
        // permutation symmetry makes orbital fixing fire on the whole orbit). The full BacktrackSolver
        // (with clause learning) exercises the inline antecedents and the orbital-fixing reasons.
        val rng = Random(0x0B17A1L)
        repeat(60) { iter ->
            val n = rng.nextInt(3, 5)
            val d = rng.nextInt(1, 3)
            val xs = IntArray(n) { it }
            val factors = ArrayList<Factor>()
            when (rng.nextInt(3)) {
                0 -> factors.add(AllDifferent(xs, domainMin = 0, domainSize = d + 1))

                1 -> factors.add(Linear(IntArray(n) { 1 }, xs, LinearOp.LE, rng.nextInt(0, n * d + 1)))

                else -> {
                    factors.add(Linear(IntArray(n) { 1 }, xs, LinearOp.GE, rng.nextInt(0, n * d + 1)))
                    factors.add(Linear(IntArray(n) { 1 }, xs, LinearOp.LE, rng.nextInt(0, n * d + 1)))
                }
            }
            val problem = Problem(0, n, Array(n) { IntDomain(0, d) }, factors)
            val broken = Presolve.breakSymmetries(problem)
            val origSat = BruteForceSolver(problem).solve(BruteForceParams(randomSeed = 0L)) is SolveResult.Sat
            val brokenSat = BacktrackSolver(broken).solve(BacktrackParams(randomSeed = 1L)) is SolveResult.Sat
            assertEquals(origSat, brokenSat, "random#$iter(n=$n,d=$d): breaking changed satisfiability")
        }
    }

    @Test
    fun `composite bool-int symmetry stays sound under search`() {
        // b0 ↔ (x0 = 1), b1 ↔ (x1 = 1): interchangeable only as the joint swap (b0,x0) ↔ (b1,x1).
        val problem = Problem(
            2,
            2,
            arrayOf(IntDomain(0, 2), IntDomain(0, 2)),
            listOf(
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
        assertSoundUnderSearch("composite", problem)
    }
}
