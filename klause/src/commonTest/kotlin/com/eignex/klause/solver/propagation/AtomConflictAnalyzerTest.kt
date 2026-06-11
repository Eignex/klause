package com.eignex.klause.solver.propagation

import com.eignex.klause.solver.Assumptions
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.backtrack.BacktrackSolver
import com.eignex.klause.solver.backtrack.InputOrder
import com.eignex.klause.solver.brute.BruteForceParams
import com.eignex.klause.solver.brute.BruteForceSolver
import com.eignex.klause.solver.factor.Clause
import com.eignex.klause.solver.factor.Linear
import com.eignex.klause.solver.factor.LinearOp
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Coverage for the **atom-var** paths of [ConflictAnalyzer] — the `levelOf` / `uipLit` /
 * `antecedentsOf` branches that dispatch on `variable >= numBoolVars`. Every case in
 * [ConflictAnalyzerTest] is pure-bool, so these branches (and the atomLevel-drift /
 * conflict-level soundness fixes #76 / #77 that lean on them) had zero direct coverage.
 *
 * The bound-consistent propagators (Linear back-clamp, AllDifferent Hall sets) eagerly
 * cap every variable the moment a related bound moves, so a genuine *multi-level* atom
 * conflict can't be reached by a sequence of [PropagationSession] decisions — the second
 * decision always conflicts at decision time (no clause-form seed) or is pre-empted at
 * bake. The realistic source of a lazy atom nogood is a *learned* atom-clause that fires
 * after a backjump. We reproduce exactly that here by hand-building the atom implication
 * graph on a [PropagationState] (same-package `internal` access) and seeding [analyze]
 * with a registered atom-clause — driving the analyzer in isolation, the way a SAT-solver
 * conflict-analysis unit test drives the 1UIP loop directly.
 */
class AtomConflictAnalyzerTest {

    /**
     * Build a state with one int var per entry of [levels] over `[0, 10]`, each tightened so
     * `v ≥ 5` holds and recorded at the given decision level (the tighten runs with
     * `currentLevel = levels[v]`, so the bound-change history — which the analyzer now reads
     * for atom levels, see #76 — places the bound at that level). Allocates the `v ≥ 5` atom
     * for each. With `numBoolVars = 0` and allocation in var order, the atom's virtual var id
     * equals its int var index — so atom `i` is var `i` and `Lit.make(i, false)` is `¬(varᵢ ≥ 5)`.
     */
    private fun atomGeState(levels: IntArray, ants: Array<IntArray?>? = null): PropagationState {
        val n = levels.size
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = n,
            intDomains = Array(n) { IntDomain(0, 10) },
            factors = arrayOf<Factor>(),
        )
        val state = PropagationState(problem, Assumptions.None)
        state.undoLogging = true
        for (v in 0 until n) {
            state.currentLevel = levels[v]
            check(state.tightenIntMin(v, 5, ants?.get(v))) { "tighten v$v failed" }
            val atomVar = state.atomVarGe(v, 5)
            check(atomVar == v) { "expected atom var id $v, got $atomVar" }
        }
        return state
    }

    /** `¬(varᵢ ≥ 5)` — the false-polarity literal of atom `i`. */
    private fun negAtom(i: Int) = Lit.make(i, false)

    private fun varsOf(literals: IntArray): Set<Int> = literals.map { Lit.variable(it) }.toSet()

    @Test
    fun `atom-var 1UIP learns an atom-literal clause with the second-level backjump`() {
        // Two leaf atoms: ax at level 1, ay at level 2 (the conflict level). The conflict
        // clause forbids both holding. 1UIP keeps ay as the asserting UIP, ax drops to the
        // learned clause as a lower-level literal → backjump to level 1.
        val state = atomGeState(intArrayOf(1, 2)) // ax @1, ay @2
        state.currentLevel = 2
        val fid = state.addLearnedClause(Clause(intArrayOf(negAtom(0), negAtom(1))), lbd = 2)

        val learned = assertIs<ConflictAnalyzer.AnalysisResult.Learned>(state.conflictAnalyzer.analyze(fid))
        assertEquals(setOf(0, 1), varsOf(learned.literals), "both atom vars stay in the clause")
        assertTrue(learned.literals.all { Lit.variable(it) >= state.problem.numBoolVars }, "all literals are atom-vars")
        assertTrue(learned.literals.all { !Lit.isPositive(it) }, "atoms hold, so they appear negated")
        assertEquals(1, learned.backjumpLevel, "second-highest decision level among the literals")
        assertEquals(2, learned.lbd)
        assertTrue(learned.asserting, "exactly one literal at the conflict level → asserting 1UIP clause")
    }

    @Test
    fun `backjump level uses bound history not a drifted atomLevel`() {
        // #76 regression: ax's bound was genuinely established at level 1 on this path (its
        // history records level 1). Atom levels are derived from the bound histories on
        // every read — there is no stored per-atom level left to drift — so the analyzer
        // necessarily sees ax at its true level 1 regardless of how the search popped.
        val state = atomGeState(intArrayOf(1, 2)) // ax established @1, ay @2
        state.currentLevel = 2
        val fid = state.addLearnedClause(Clause(intArrayOf(negAtom(0), negAtom(1))), lbd = 2)

        val learned = assertIs<ConflictAnalyzer.AnalysisResult.Learned>(state.conflictAnalyzer.analyze(fid))
        assertEquals(setOf(0, 1), varsOf(learned.literals))
        assertEquals(1, learned.backjumpLevel, "backjump must reflect ax's true level 1, not the drifted 5")
        assertEquals(2, learned.lbd, "two true levels {1, 2}, not {5, 2}")
        assertTrue(learned.asserting)
    }

    @Test
    fun `factor conflict level comes from the seed reason not the firing-factor attribution`() {
        // #77 regression: the conflict's literals are ax @1 and ay @2 (their bound histories),
        // so the conflict level is 2 and ay is the lone level-2 UIP → an asserting clause that
        // backjumps to level 1. But state.currentLevel carries the failing factor's attribution
        // (here a stale-high 5, as maxLevelForClause would read off a drifted atomLevel for an
        // atom-lit clause). The analyzer must take the conflict level from the seed reason's own
        // literals (max = 2), not that attribution: were it to trust currentLevel = 5, no literal
        // sits at level 5, the 1UIP loop finds no pivot, and the clause degenerates to a
        // non-asserting nogood (lost learning) with a mis-targeted backjump.
        val state = atomGeState(intArrayOf(1, 2)) // ax @1, ay @2
        state.currentLevel = 5 // failing-factor attribution overshoots every reason literal
        val fid = state.addLearnedClause(Clause(intArrayOf(negAtom(0), negAtom(1))), lbd = 2)

        val learned = assertIs<ConflictAnalyzer.AnalysisResult.Learned>(state.conflictAnalyzer.analyze(fid))
        assertEquals(setOf(0, 1), varsOf(learned.literals))
        assertTrue(learned.asserting, "conflict level 2 (from the seed) → ay is the lone UIP → asserting")
        assertEquals(1, learned.backjumpLevel, "backjump to level 1, not derived from the stale currentLevel 5")
        assertEquals(2, learned.lbd)
    }

    @Test
    fun `1UIP resolves through an implied atom pivot via its atom antecedents`() {
        // ax leaf @1, az leaf @2, ay implied @2 with antecedent ¬ax. Seed forbids ay ∧ az.
        // The loop resolves ay (current level, has antecedents) out through ¬ax, leaving az
        // as the lone current-level UIP. ay must NOT survive in the learned clause; ax (its
        // antecedent) takes its place at level 1. Exercises antecedentsOf's atom branch.
        // ay is the implied pivot: its bound move records ¬ax (a leaf at level 1) as its
        // reason, so the derived antecedents of [ay ≥ 5] resolve to ¬ax.
        val state = atomGeState(
            intArrayOf(1, 2, 2), // ax @1, ay @2, az @2
            arrayOf(null, intArrayOf(negAtom(0)), null),
        )
        state.currentLevel = 2
        val fid = state.addLearnedClause(Clause(intArrayOf(negAtom(1), negAtom(2))), lbd = 2)

        val learned = assertIs<ConflictAnalyzer.AnalysisResult.Learned>(state.conflictAnalyzer.analyze(fid))
        assertEquals(
            setOf(0, 2),
            varsOf(learned.literals),
            "ay (var 1) is resolved through its antecedent ¬ax; clause is {¬ax, ¬az}",
        )
        assertTrue(1 !in varsOf(learned.literals), "the resolved-through implied atom must not survive")
        assertEquals(1, learned.backjumpLevel)
        assertTrue(learned.asserting)
    }

    @Test
    fun `two same-level atom leaves yield a non-asserting clause for chronological fallback`() {
        // Both atoms sit at the conflict level with no antecedents (the int-equality-decision
        // shape: a pin contributes two same-level bound atoms 1UIP cannot collapse). The
        // learned clause keeps both at the conflict level, so it is not unit after any
        // backjump → asserting = false, signalling the engine to backtrack chronologically.
        val state = atomGeState(intArrayOf(2, 2)) // both atoms established at the conflict level
        state.currentLevel = 2
        val fid = state.addLearnedClause(Clause(intArrayOf(negAtom(0), negAtom(1))), lbd = 1)

        val learned = assertIs<ConflictAnalyzer.AnalysisResult.Learned>(state.conflictAnalyzer.analyze(fid))
        assertEquals(setOf(0, 1), varsOf(learned.literals))
        assertEquals(
            2,
            learned.literals.count { ConflictAnalyzerTestAccess.levelOf(state, Lit.variable(it)) == 2 },
            "both literals remain at the conflict level",
        )
        assertTrue(!learned.asserting, "two conflict-level literals → non-asserting → chronological fallback")
    }

    @Test
    fun `cascading backjumps prove UNSAT on an int instance requiring multiple conflicts`() {
        // x,y,z over [0,2] with three pairwise strict-order constraints forming a cycle:
        //   x < y, y < z, z < x. No assignment satisfies a 3-cycle of <, so the search must
        //   learn its way to UNSAT through several conflict/backjump rounds (the
        //   backjumpAndLearn repeat loop) and terminate at the Exhausted terminal.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(0, 2), IntDomain(0, 2), IntDomain(0, 2)),
            factors = arrayOf<Factor>(
                Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.LE, -1), // x - y <= -1  (x < y)
                Linear(intArrayOf(1, -1), intArrayOf(1, 2), LinearOp.LE, -1), // y - z <= -1  (y < z)
                Linear(intArrayOf(1, -1), intArrayOf(2, 0), LinearOp.LE, -1), // z - x <= -1  (z < x)
            ),
        )
        val r = BacktrackSolver(problem).solve(BacktrackParams(variableHeuristic = InputOrder, randomSeed = 0L))
        assertIs<SolveResult.Unsat>(r)
    }

    @Test
    fun `learned clauses survive restart and forgetting on an int search`() {
        // A satisfiable int instance driven with a tight Luby restart and a zero learned-clause
        // cap, so forgetLearnedClauses fires repeatedly across restarts while the search runs.
        // Correctness must survive: the returned model satisfies every constraint.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = Array(4) { IntDomain(0, 3) },
            factors = arrayOf<Factor>(
                Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.LE, -1), // a < b
                Linear(intArrayOf(1, -1), intArrayOf(1, 2), LinearOp.LE, -1), // b < c
                Linear(intArrayOf(1, -1), intArrayOf(2, 3), LinearOp.LE, -1), // c < d
            ),
        )
        val r = BacktrackSolver(problem).solve(
            BacktrackParams(
                lubyRestartBase = 4,
                maxLearnedClauses = 0,
                lbdGlueThreshold = 2,
                randomSeed = 9L,
            ),
        )
        val sat = assertIs<SolveResult.Sat>(r)
        val v = sat.assignment.ints
        assertTrue(v[0] < v[1] && v[1] < v[2] && v[2] < v[3], "strict chain a<b<c<d must hold; got ${v.toList()}")
    }

    @Test
    fun `BnB never over-prunes the true optimum with negative coefficients and domain holes`() {
        // linearLowerBound feeds the objective-bound prune; an unsound backjump (#76/#77) or a
        // loose lower bound would let it cut the true optimum. Validate against the brute-force
        // ground truth across instances mixing negative objective coefficients and domain holes.
        val instances = listOf(
            // a + b <= 4, holes at a=2 and b=1, minimise -a - 2b (push both high within the cap).
            Triple(
                Problem(
                    numBoolVars = 0,
                    numIntVars = 2,
                    intDomains = arrayOf(IntDomain(0, 5).excludeValue(2), IntDomain(0, 5).excludeValue(1)),
                    factors = arrayOf<Factor>(Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 4)),
                ),
                LinearObjective(intCoefficients = longArrayOf(-1L, -2L)),
                1L,
            ),
            // 2a - b >= 0 with a hole at a=1; minimise a - b (negative weight on b).
            Triple(
                Problem(
                    numBoolVars = 0,
                    numIntVars = 2,
                    intDomains = arrayOf(IntDomain(0, 4).excludeValue(1), IntDomain(0, 4)),
                    factors = arrayOf<Factor>(Linear(intArrayOf(2, -1), intArrayOf(0, 1), LinearOp.GE, 0)),
                ),
                LinearObjective(intCoefficients = longArrayOf(1L, -1L)),
                2L,
            ),
            // Three vars, equality coupling, holes, mixed-sign objective.
            Triple(
                Problem(
                    numBoolVars = 0,
                    numIntVars = 3,
                    intDomains = arrayOf(
                        IntDomain(0, 4),
                        IntDomain(0, 4).excludeValue(2),
                        IntDomain(0, 4).excludeValue(0).excludeValue(4),
                    ),
                    factors = arrayOf<Factor>(Linear(intArrayOf(1, 1, -1), intArrayOf(0, 1, 2), LinearOp.EQ, 0)),
                ),
                LinearObjective(intCoefficients = longArrayOf(-1L, 2L, -1L)),
                3L,
            ),
        )
        for ((idx, inst) in instances.withIndex()) {
            val (problem, objective, seed) = inst
            val truth = BruteForceSolver(problem).minimize(objective, BruteForceParams())
            val got = BacktrackSolver(problem).minimize(objective, BacktrackParams(randomSeed = seed))
            when (truth) {
                is MinimizeResult.Optimal -> {
                    val gotOpt = assertIs<MinimizeResult.WithSample>(got)
                    assertEquals(
                        truth.objective,
                        gotOpt.objective,
                        "instance $idx: BnB optimum ${gotOpt.objective} != brute optimum ${truth.objective}",
                    )
                }

                is MinimizeResult.Infeasible ->
                    assertIs<MinimizeResult.Infeasible>(got, "instance $idx: brute says infeasible")

                else -> error("instance $idx: brute oracle returned non-terminal $truth")
            }
        }
    }
}

/** Re-derives the analyzer's private level lookup for assertions, mirroring
 *  [ConflictAnalyzer]'s `levelOf`: bool vars via [PropagationState.boolLevel], atoms via the
 *  bound-history-derived [PropagationState.atomLevelForConflict] (#76). */
internal object ConflictAnalyzerTestAccess {
    fun levelOf(state: PropagationState, v: Int): Int = if (v < state.problem.numBoolVars) {
        state.boolLevel[v]
    } else {
        state.atomLevelForConflict(v - state.problem.numBoolVars)
    }
}
