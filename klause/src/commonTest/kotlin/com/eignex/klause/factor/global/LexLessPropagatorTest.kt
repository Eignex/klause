package com.eignex.klause.factor.global

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.ConflictAnalyzer
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.addLearnedClause
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.SolveResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LexLessPropagatorTest {

    private fun lexLess(xs: List<Long>, ys: List<Long>, strict: Boolean): Boolean {
        val len = minOf(xs.size, ys.size)
        for (i in 0 until len) {
            if (xs[i] < ys[i]) return true
            if (xs[i] > ys[i]) return false
        }
        return when {
            xs.size == ys.size -> !strict
            xs.size < ys.size -> true
            else -> false
        }
    }

    @Test
    fun `strict lex less enforces strict ordering`() {
        // xs = [x0, x1], ys = [y0, y1]. All ∈ [0..2]. Strict less.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = Array(4) { IntDomain(0, 2) },
            factors = arrayOf<Factor>(LexLess(intArrayOf(0, 1), intArrayOf(2, 3), strict = true)),
        )
        BacktrackSolver(problem.bake()).enumerate(BacktrackParams(randomSeed = 0L)).take(20).forEach { sample ->
            val xs = listOf(sample.ints[0], sample.ints[1])
            val ys = listOf(sample.ints[2], sample.ints[3])
            assertTrue(lexLess(xs, ys, strict = true), "lex_less violated: xs=$xs ys=$ys")
        }
    }

    @Test
    fun `non-strict lex lesseq allows equality`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = Array(4) { IntDomain(0, 1) },
            factors = arrayOf<Factor>(LexLess(intArrayOf(0, 1), intArrayOf(2, 3), strict = false)),
        )
        val r = BacktrackSolver(problem.bake()).solve(BacktrackParams(randomSeed = 0L))
        val sat = assertIs<SolveResult.Sat>(r)
        val xs = listOf(sat.assignment.ints[0], sat.assignment.ints[1])
        val ys = listOf(sat.assignment.ints[2], sat.assignment.ints[3])
        assertTrue(lexLess(xs, ys, strict = false), "lex_lesseq violated: xs=$xs ys=$ys")
    }

    @Test
    fun `strict lex on equal pair is Unsat`() {
        // xs = [1, 1], ys = [1, 1] pinned. Strict lex < must fail.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = Array(4) { IntDomain(1, 1) },
            factors = arrayOf<Factor>(LexLess(intArrayOf(0, 1), intArrayOf(2, 3), strict = true)),
        )
        assertIs<SolveResult.Unsat>(BacktrackSolver(problem.bake()).solve(BacktrackParams(randomSeed = 0L)))
    }

    @Test
    fun `strict lex tightens the last deciding position strictly`() {
        // x0 = y0 = 0 is a fixed-equal prefix, so the relation hinges on the last position. The
        // suffix is exhausted there, so strict `lex_less` forces x1 < y1: with x1 ∈ [0,3] and
        // y1 ∈ [0,2] the β look-ahead derives x1 ≤ y1.max − 1 = 1 and y1 ≥ x1.min + 1 = 1.
        // A plain `x1 ≤ y1.max` step would only reach x1 ≤ 2. Layout: x0=0, x1=1, y0=2, y1=3.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = arrayOf(IntDomain(0, 0), IntDomain(0, 3), IntDomain(0, 0), IntDomain(0, 2)),
            factors = arrayOf<Factor>(LexLess(intArrayOf(0, 1), intArrayOf(2, 3), strict = true)),
        )
        val state = PropagationState(problem, Assumptions.None)
        state.undoLogging = true
        state.currentFactor = 0
        assertTrue(problem.propagators[0].propagate(state, 0))
        assertEquals(1, state.intDomains[1].max, "strict suffix should force x1 ≤ 1")
        assertEquals(1, state.intDomains[3].min, "strict suffix should force y1 ≥ 1")
    }

    @Test
    fun `lesseq forces a strict head step when the tail is pinned greater`() {
        // lex_lesseq([x0,x1],[y0,y1]) with the tail pinned x1 = 2 > y1 = 0. The tail can never
        // rescue equality at the head, so the β look-ahead forces x0 < y0 even though the
        // relation itself is non-strict: x0 ≤ 1 and y0 ≥ 1. Layout: x0=0, x1=1, y0=2, y1=3.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = arrayOf(IntDomain(0, 2), IntDomain(2, 2), IntDomain(0, 2), IntDomain(0, 0)),
            factors = arrayOf<Factor>(LexLess(intArrayOf(0, 1), intArrayOf(2, 3), strict = false)),
        )
        val state = PropagationState(problem, Assumptions.None)
        state.undoLogging = true
        state.currentFactor = 0
        assertTrue(problem.propagators[0].propagate(state, 0))
        assertEquals(1, state.intDomains[0].max, "forced-greater tail should force x0 ≤ 1")
        assertEquals(1, state.intDomains[2].min, "forced-greater tail should force y0 ≥ 1")
    }

    @Test
    fun `learned clause through a per-tighten lex deduction carries the prefix-equality literals`() {
        // The deciding-position tightening `xs[a].max ≤ ys[a].max` is sound
        // only because the prefix `xs[0..a-1] = ys[0..a-1]` is pinned equal — index a is the
        // deciding position precisely because of that prefix. The per-tighten antecedent is
        // recorded on the implied atom `[xs[a] ≤ ys[a].max]`; when a *later* conflict (seeded
        // by another factor) resolves through that atom in the 1UIP loop, the prefix-equality
        // literals must reappear in the learned clause. If they're omitted the clause is too
        // weak — it reads as a global fact and can excise feasible assignments on later
        // branches where the prefix is not equal.
        //
        // We drive the analyzer directly, the way AtomConflictAnalyzerTest does: pin a genuine
        // x0 = y0 prefix as decisions, tighten y1 below its max, run the lex propagator (which
        // tightens x1 ≤ 1 and records its antecedent on the atom `[x1 ≤ 1]`), then seed a
        // conflict forbidding `[x1 ≤ 1] ∧ [y1 ≤ 1]` and assert the learned clause references
        // the prefix vars x0 and y0. The free third position keeps the deciding step non-strict
        // so x1 lands at y1.max = 1 (not the strict y1.max − 1). Layout: x*=var{0,1,2},
        // y*=var{3,4,5}.
        val lex = LexLess(intArrayOf(0, 1, 2), intArrayOf(3, 4, 5), strict = true)
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 6,
            intDomains = arrayOf(
                IntDomain(0, 2),
                IntDomain(0, 3),
                IntDomain(0, 2),
                IntDomain(0, 2),
                IntDomain(0, 3),
                IntDomain(0, 2),
            ),
            factors = arrayOf<Factor>(lex),
        )
        val state = PropagationState(problem, Assumptions.None)
        state.undoLogging = true

        // Allocate the two tail atoms first so the lex tighten / y1 decision attach their
        // antecedents to them, and so `[x1 ≤ 1]` is picked as the 1UIP pivot before `[y1 ≤ 1]`.
        val atomX1Le1 = state.atomVarLe(1, 1)
        val atomY1Le1 = state.atomVarLe(4, 1)

        // Decisions: x0 = y0 = 1 at level 1 (a real, non-forced equal prefix).
        state.currentLevel = 1
        state.currentFactor = -1
        check(state.setInt(0, 1)) { "pin x0=1" }
        check(state.setInt(3, 1)) { "pin y0=1" }
        // Decision: y1 ≤ 1 at level 2 (tighten below its original max so the lex tighten of
        // x1 lands below 3 and the antecedent is non-trivial).
        state.currentLevel = 2
        check(state.tightenIntMax(4, 1)) { "tighten y1.max=1" }

        // Run the lex propagator attributed to factor 0; it walks the equal x0=y0 prefix and
        // tightens x1.max ≤ y1.max = 1, recording the prefix-aware antecedent on `[x1 ≤ 1]`.
        state.currentFactor = 0
        check(problem.propagators[0].propagate(state, 0)) { "lex propagate should narrow, not fail" }
        state.currentFactor = -1
        check(state.intDomains[1].max == 1L) { "lex should have tightened x1.max to 1" }

        // Seed a conflict that forbids the two tail atoms (both currently hold), forcing the
        // 1UIP loop to resolve `[x1 ≤ 1]` through its recorded antecedent.
        val seed = Clause(intArrayOf(Lit.make(atomX1Le1, false), Lit.make(atomY1Le1, false)))
        val fid = state.addLearnedClause(seed, lbd = 2)
        val learned = assertIs<ConflictAnalyzer.AnalysisResult.Learned>(state.conflictAnalyzer.analyze(fid))

        // Map each learned literal back to the int var its atom constrains.
        val intVarsInClause = learned.literals
            .map { Lit.variable(it) - problem.numBoolVars }
            .map { atomId -> state.atoms.intVar[atomId] }
            .toSet()
        assertTrue(
            0 in intVarsInClause && 3 in intVarsInClause,
            "learned clause must cite the prefix vars x0(0) and y0(3) — omitting them is the " +
                "#75 too-weak reason; got int vars $intVarsInClause from ${learned.literals.toList()}",
        )
    }
}
