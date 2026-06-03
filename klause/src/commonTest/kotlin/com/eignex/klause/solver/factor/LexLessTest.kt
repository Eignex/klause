package com.eignex.klause.solver.factor

import com.eignex.klause.solver.Assumptions
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Move.Compound
import com.eignex.klause.solver.Move.IntSet
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.backtrack.BacktrackSolver
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.localsearch.MoveSink
import com.eignex.klause.solver.propagation.ConflictAnalyzer
import com.eignex.klause.solver.propagation.PropagationState
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LexLessTest {

    @Test
    fun `strict lex less enforces strict ordering`() {
        // xs = [x0, x1], ys = [y0, y1]. All ∈ [0..2]. Strict less.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = Array(4) { IntDomain(0, 2) },
            factors = arrayOf<Factor>(LexLess(intArrayOf(0, 1), intArrayOf(2, 3), strict = true)),
        )
        BacktrackSolver(problem).enumerate(BacktrackParams(randomSeed = 0L)).take(20).forEach { sample ->
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
        val r = BacktrackSolver(problem).solve(BacktrackParams(randomSeed = 0L))
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
        assertIs<SolveResult.Unsat>(BacktrackSolver(problem).solve(BacktrackParams(randomSeed = 0L)))
    }

    @Test
    fun `repair moves restore violated strict lex at first decided position`() {
        val factor = LexLess(intArrayOf(0, 1), intArrayOf(2, 3), strict = true)
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = Array(4) { IntDomain(0, 5) },
            factors = arrayOf<Factor>(factor),
        )
        val state = LocalSearchState(
            problem,
            Random(0),
        )
        // xs = [3, 1], ys = [2, 4]. Violation at k=0 (xs[0]=3 > ys[0]=2).
        state.assignment.setInt(0, 3)
        state.assignment.setInt(1, 1)
        state.assignment.setInt(2, 2)
        state.assignment.setInt(3, 4)
        state.recompute()
        assertTrue(factor.isViolated(state, 0))
        val sink = MoveSink()
        factor.proposeRepairMoves(state, 0, sink)
        // Expect a move lowering xs[0] to ≤ 1 (strict ≤ b-1 = 1) and/or raising ys[0] to ≥ 4.
        val intSets = sink.list.filterIsInstance<IntSet>()
        assertTrue(
            intSets.any { it.varId == 0 && it.newValue == 1 },
            "expected IntSet(xs[0]=1) in $intSets",
        )
        assertTrue(
            intSets.any { it.varId == 2 && it.newValue == 4 },
            "expected IntSet(ys[0]=4) in $intSets",
        )
    }

    @Test
    fun `repair adds swap compound when both opposites fit domains`() {
        val factor = LexLess(intArrayOf(0), intArrayOf(1), strict = true)
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = Array(2) { IntDomain(0, 5) },
            factors = arrayOf<Factor>(factor),
        )
        val state = LocalSearchState(
            problem,
            Random(0),
        )
        state.assignment.setInt(0, 4)
        state.assignment.setInt(1, 2)
        state.recompute()
        assertTrue(factor.isViolated(state, 0))
        val sink = MoveSink()
        factor.proposeRepairMoves(state, 0, sink)
        val compounds = sink.list.filterIsInstance<Compound>()
        assertTrue(
            compounds.any { c ->
                c.parts == listOf(
                    IntSet(0, 2),
                    IntSet(1, 4),
                )
            },
            "expected swap Compound(IntSet(0,2), IntSet(1,4)) in $compounds",
        )
    }

    @Test
    fun `repair on equal-length prefix-equal under strict proposes prefix break`() {
        val factor = LexLess(intArrayOf(0, 1), intArrayOf(2, 3), strict = true)
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = Array(4) { IntDomain(0, 5) },
            factors = arrayOf<Factor>(factor),
        )
        val state = LocalSearchState(
            problem,
            Random(0),
        )
        // xs == ys. Strict requires a strict break; the comparable prefix is fully equal.
        state.assignment.setInt(0, 2)
        state.assignment.setInt(1, 3)
        state.assignment.setInt(2, 2)
        state.assignment.setInt(3, 3)
        state.recompute()
        assertTrue(factor.isViolated(state, 0))
        val sink = MoveSink()
        factor.proposeRepairMoves(state, 0, sink)
        val intSets = sink.list.filterIsInstance<IntSet>()
        // Must propose lowering xs[0] or raising ys[0] (the earliest position with room).
        assertTrue(
            intSets.any { it.varId == 0 && it.newValue == 1 } ||
                intSets.any { it.varId == 2 && it.newValue == 4 },
            "expected prefix-break move at index 0 in $intSets",
        )
    }

    @Test
    fun `learned clause through a per-tighten lex deduction carries the prefix-equality literals`() {
        // Regression for #75. The index-i tightening `xs[i].max ≤ ys[i].max` is sound only
        // because the prefix `xs[0..i-1] = ys[0..i-1]` is pinned equal — index i is the
        // deciding position precisely because of that prefix. The per-tighten antecedent is
        // recorded on the implied atom `[xs[i] ≤ ys[i].max]`; when a *later* conflict (seeded
        // by another factor) resolves through that atom in the 1UIP loop, the prefix-equality
        // literals must reappear in the learned clause. If they're omitted the clause is too
        // weak — it reads as a global fact and can excise feasible assignments on later
        // branches where the prefix is not equal.
        //
        // We drive the analyzer directly, the way AtomConflictAnalyzerTest does: pin a genuine
        // x0 = y0 prefix as decisions, tighten y1 below its max, run the lex propagator (which
        // tightens x1 ≤ 1 and records its antecedent on the atom `[x1 ≤ 1]`), then seed a
        // conflict forbidding `[x1 ≤ 1] ∧ [y1 ≤ 1]` and assert the learned clause references
        // the prefix vars x0 and y0. Layout: x0=var0, x1=var1, y0=var2, y1=var3.
        val lex = LexLess(intArrayOf(0, 1), intArrayOf(2, 3), strict = true)
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = arrayOf(IntDomain(0, 2), IntDomain(0, 3), IntDomain(0, 2), IntDomain(0, 3)),
            factors = arrayOf<Factor>(lex),
        )
        val state = PropagationState(problem, Assumptions.None)
        state.undoLogging = true

        // Allocate the two tail atoms first so the lex tighten / y1 decision attach their
        // antecedents to them, and so `[x1 ≤ 1]` is picked as the 1UIP pivot before `[y1 ≤ 1]`.
        val atomX1Le1 = state.atomVarLe(1, 1)
        val atomY1Le1 = state.atomVarLe(3, 1)

        // Decisions: x0 = y0 = 1 at level 1 (a real, non-forced equal prefix).
        state.currentLevel = 1
        state.currentFactor = -1
        check(state.setInt(0, 1)) { "pin x0=1" }
        check(state.setInt(2, 1)) { "pin y0=1" }
        // Decision: y1 ≤ 1 at level 2 (tighten below its original max so the lex tighten of
        // x1 lands below 3 and the antecedent is non-trivial).
        state.currentLevel = 2
        check(state.tightenIntMax(3, 1)) { "tighten y1.max=1" }

        // Run the lex propagator attributed to factor 0; it walks the equal x0=y0 prefix and
        // tightens x1.max ≤ y1.max = 1, recording the prefix-aware antecedent on `[x1 ≤ 1]`.
        state.currentFactor = 0
        check(lex.propagate(state, 0)) { "lex propagate should narrow, not fail" }
        state.currentFactor = -1
        check(state.intDomains[1].max == 1) { "lex should have tightened x1.max to 1" }

        // Seed a conflict that forbids the two tail atoms (both currently hold), forcing the
        // 1UIP loop to resolve `[x1 ≤ 1]` through its recorded antecedent.
        val seed = Clause(intArrayOf(Lit.make(atomX1Le1, false), Lit.make(atomY1Le1, false)))
        val fid = state.addLearnedClause(seed, lbd = 2)
        val learned = assertIs<ConflictAnalyzer.AnalysisResult.Learned>(state.conflictAnalyzer.analyze(fid))

        // Map each learned literal back to the int var its atom constrains.
        val intVarsInClause = learned.literals
            .map { Lit.variable(it) - problem.numBoolVars }
            .map { atomId -> state.atomIntVar[atomId] }
            .toSet()
        assertTrue(
            0 in intVarsInClause && 2 in intVarsInClause,
            "learned clause must cite the prefix vars x0(0) and y0(2) — omitting them is the " +
                "#75 too-weak reason; got int vars $intVarsInClause from ${learned.literals.toList()}",
        )
    }

    private fun lexLess(xs: List<Int>, ys: List<Int>, strict: Boolean): Boolean {
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
}
