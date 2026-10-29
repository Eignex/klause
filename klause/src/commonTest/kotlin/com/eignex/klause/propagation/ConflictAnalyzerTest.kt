package com.eignex.klause.propagation

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.table.Table
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConflictAnalyzerTest {
    @Test
    fun `historical table replay should improve the minimized asserting backjump`() {
        val tuples = (0 until 8).flatMap { listOf(0L, (it / 4).toLong(), (it / 2 % 2).toLong(), (it % 2).toLong()) }
            .toLongArray() + longArrayOf(1, 0, 0, 1, 1, 1, 1, 0)
        val problem = Problem(1, 4, Array(4) { IntDomain(0, 1) },
            arrayOf<Factor>(Table(intArrayOf(0, 1, 2, 3), tuples)))
        for (enabled in listOf(false, true)) {
            val state = PropagationState(problem, Assumptions.None)
            state.undoLogging = true
            state.historicalTableReasons = enabled
            state.factorAttribution = FactorAttribution()
            check(state.setIntMinAsDecision(2, 1))
            check(state.setIntMinAsDecision(1, 1))
            check(state.pinBoolAsDecision(0, true))
            check(state.tightenIntMin(3, 1, intArrayOf(Lit.make(0, false))))
            val x = state.atomVarGe(0, 1)
            val b = state.atomVarGe(2, 1)
            state.currentFactor = 0
            check(state.factorAt(0).propagate(state, 0))
            val seed = intArrayOf(Lit.make(x, true), Lit.make(b, false), Lit.make(0, false))

            val learned = assertIs<ConflictAnalyzer.AnalysisResult.Learned>(
                state.conflictAnalyzer.analyzeConflictClause(seed))

            assertTrue(learned.asserting)
            assertEquals(if (enabled) 1 else 2, learned.backjumpLevel)
            assertEquals(if (enabled) 2 else 3, learned.literals.size)
            assertTrue(state.factorAttribution!!.snapshot().getValue("factorTableUipReasons") > 0)
        }
    }

    @Test
    fun `historical table replay should preserve the ordinary marker memo`() {
        val tuples = (0 until 8).flatMap { listOf(0L, (it / 4).toLong(), (it / 2 % 2).toLong(), (it % 2).toLong()) }
            .toLongArray() + longArrayOf(1, 0, 0, 1, 1, 1, 1, 0)
        val problem = Problem(1, 4, Array(4) { IntDomain(0, 1) },
            arrayOf<Factor>(Table(intArrayOf(0, 1, 2, 3), tuples)))
        val state = PropagationState(problem, Assumptions.None)
        state.undoLogging = true
        state.historicalTableReasons = true
        check(state.setIntMinAsDecision(2, 1))
        check(state.setIntMinAsDecision(1, 1))
        check(state.pinBoolAsDecision(0, true))
        check(state.tightenIntMin(3, 1, intArrayOf(Lit.make(0, false))))
        val x = state.atomVarGe(0, 1)
        val b = state.atomVarGe(2, 1)
        state.currentFactor = 0
        check(state.factorAt(0).propagate(state, 0))
        val marker = state.intMaxAntecedents[0]
        val ordinary = state.reasonOf(marker)!!.toList()

        state.conflictAnalyzer.analyzeConflictClause(
            intArrayOf(Lit.make(x, true), Lit.make(b, false), Lit.make(0, false)))

        assertEquals(ordinary, state.reasonOf(marker)!!.toList())
    }

    @Test
    fun `cancelled historical table analysis should discard unfinished learning`() {
        val tuples = (0 until 8).flatMap { listOf(0L, (it / 4).toLong(), (it / 2 % 2).toLong(), (it % 2).toLong()) }
            .toLongArray() + longArrayOf(1, 0, 0, 1, 1, 1, 1, 0)
        val problem = Problem(1, 4, Array(4) { IntDomain(0, 1) },
            arrayOf<Factor>(Table(intArrayOf(0, 1, 2, 3), tuples)))
        for (stop in 1..8) {
            val state = PropagationState(problem, Assumptions.None)
            state.undoLogging = true
            state.historicalTableReasons = true
            state.factorAttribution = FactorAttribution()
            check(state.setIntMinAsDecision(2, 1))
            check(state.setIntMinAsDecision(1, 1))
            check(state.pinBoolAsDecision(0, true))
            check(state.tightenIntMin(3, 1, intArrayOf(Lit.make(0, false))))
            val x = state.atomVarGe(0, 1)
            val b = state.atomVarGe(2, 1)
            state.currentFactor = 0
            check(state.factorAt(0).propagate(state, 0))
            var polls = 0
            state.finiteWork.cancellation = Cancellation { ++polls >= stop }

            val result = state.conflictAnalyzer.analyzeConflictClause(
                intArrayOf(Lit.make(x, true), Lit.make(b, false), Lit.make(0, false)))

            if (state.runCancelled) assertIs<ConflictAnalyzer.AnalysisResult.NotApplicable>(result)
            else assertIs<ConflictAnalyzer.AnalysisResult.Learned>(result)
            assertNull(state.historicalTableReason)
        }
    }

    @Test
    fun `historical table selection should leave extended reasons and scope premises intact`() {
        val problem = Problem(1, 2, Array(2) { IntDomain(0, 1) }, arrayOf<Factor>(
            Table(intArrayOf(0, 1), longArrayOf(0, 1, 1, 0))))
        val state = PropagationState(problem, Assumptions.None)
        state.undoLogging = true
        check(state.pinBoolAsDecision(0, true))
        check(state.tightenIntMin(1, 1))
        state.currentFactor = 0
        val marker = state.lazyReason(intArrayOf(0, 2, 0, 1))
        val scope = Lit.make(0, false)
        val choice = HistoricalTableReason(state)
        state.historicalTableReason = choice

        val reason = state.reasonOf(extendReason(marker, intArrayOf(scope)))!!

        assertTrue(scope in reason)
        assertTrue(!choice.prepare(reason))
        state.historicalTableReason = null
    }

    @Test
    fun `a single decision conflict should learn the negated decision and backjump to the root`() {
        val session = PropagationSession(
            clauses(
                3,
                intArrayOf(Lit.make(0, false), Lit.make(1, true)),
                intArrayOf(Lit.make(1, false), Lit.make(2, true)),
                intArrayOf(Lit.make(0, false), Lit.make(1, false), Lit.make(2, false)),
            ),
        )

        val unsat = assertIs<PropagationResult.Unsat>(session.pinBool(0, true))

        val learned = assertIs<ConflictAnalyzer.AnalysisResult.Learned>(unsat.learnedClause)
        assertEquals(setOf(Lit.make(0, false)), learned.literals.toSet())
        assertEquals(0, learned.backjumpLevel)
        assertEquals(1, learned.lbd)
    }

    @Test
    fun `a two decision conflict should learn both decisions and backjump to the earlier level`() {
        val session = PropagationSession(
            clauses(
                3,
                intArrayOf(Lit.make(0, false), Lit.make(1, false), Lit.make(2, true)),
                intArrayOf(Lit.make(0, false), Lit.make(1, false), Lit.make(2, false)),
            ),
        )
        assertIs<PropagationResult.Implied>(session.pinBool(0, true))

        val unsat = assertIs<PropagationResult.Unsat>(session.pinBool(1, true))

        val learned = assertIs<ConflictAnalyzer.AnalysisResult.Learned>(unsat.learnedClause)
        assertEquals(setOf(Lit.make(0, false), Lit.make(1, false)), learned.literals.toSet())
        assertEquals(1, learned.backjumpLevel)
        assertEquals(2, learned.lbd)
    }

    private fun clauses(numBoolVars: Int, vararg literals: IntArray) = Problem(
        numBoolVars = numBoolVars,
        numIntVars = 0,
        intDomains = emptyArray(),
        factors = Array<Factor>(literals.size) { Clause(literals[it]) },
    )

    @Test
    fun `a conflict should take its level from its own path and not the previous analysis`() {
        // x1 >= 1 and x2 >= 1 force x3 >= 3, breaking x0 + x1 + x3 <= 6 with x0 >= 3; the second
        // conflict must resolve x3 >= 3 back to x1's decision for the clause to assert.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = Array(4) { IntDomain(0, 5) },
            factors = arrayOf<Factor>(
                Linear(intArrayOf(1, 1, 1), intArrayOf(0, 1, 3), LinearOp.LE, 6),
                Linear(intArrayOf(1, -1, -1), intArrayOf(3, 1, 2), LinearOp.GE, 1),
            ),
        )
        val state = PropagationState(problem, Assumptions.None)
        state.undoLogging = true
        val root = state.mark()
        fun propagateToConflict() {
            state.currentFactor = 1
            check(state.factorAt(1).propagate(state, 1))
            state.currentFactor = 0
            check(!state.factorAt(0).propagate(state, 0))
        }
        check(state.setIntMinAsDecision(2, 1) && state.tightenIntMin(0, 3) && state.setIntMinAsDecision(1, 1))
        propagateToConflict()
        state.conflictAnalyzer.analyze(0)
        state.undoTo(root)
        check(state.setIntMinAsDecision(2, 1) && state.setIntMinAsDecision(0, 3) && state.setIntMinAsDecision(1, 1))
        propagateToConflict()

        val learned = assertIs<ConflictAnalyzer.AnalysisResult.Learned>(state.conflictAnalyzer.analyze(0))

        assertTrue(learned.asserting)
        assertEquals(2, learned.backjumpLevel)
    }

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

    private fun levelOf(state: PropagationState, v: Int): Int = if (v < state.problem.numBoolVars) {
        state.boolLevel[v]
    } else {
        state.atomLevelForConflict(v - state.problem.numBoolVars)
    }

    private fun negAtom(i: Int) = Lit.make(i, false)

    private fun varsOf(literals: IntArray): Set<Int> = literals.map { Lit.variable(it) }.toSet()

    @Test
    fun `1UIP analysis should learn an asserting atom clause backjumping to the lower level`() {
        val state = atomGeState(intArrayOf(1, 2))
        state.currentLevel = 2
        val fid = state.addLearnedClause(Clause(intArrayOf(negAtom(0), negAtom(1))), lbd = 2)

        val learned = assertIs<ConflictAnalyzer.AnalysisResult.Learned>(state.conflictAnalyzer.analyze(fid))
        assertEquals(setOf(0, 1), varsOf(learned.literals))
        assertTrue(learned.literals.all { Lit.variable(it) >= state.problem.numBoolVars })
        assertTrue(learned.literals.all { !Lit.isPositive(it) })
        assertEquals(1, learned.backjumpLevel)
        assertEquals(2, learned.lbd)
        assertTrue(learned.asserting)
    }

    @Test
    fun `conflict level should come from the seed reason not the stale current level`() {
        val state = atomGeState(intArrayOf(1, 2))
        state.currentLevel = 5
        val fid = state.addLearnedClause(Clause(intArrayOf(negAtom(0), negAtom(1))), lbd = 2)

        val learned = assertIs<ConflictAnalyzer.AnalysisResult.Learned>(state.conflictAnalyzer.analyze(fid))
        assertEquals(setOf(0, 1), varsOf(learned.literals))
        assertTrue(learned.asserting)
        assertEquals(1, learned.backjumpLevel)
        assertEquals(2, learned.lbd)
    }

    @Test
    fun `1UIP analysis should resolve an implied atom through its antecedents`() {
        val state = atomGeState(
            intArrayOf(1, 2, 2),
            arrayOf(null, intArrayOf(negAtom(0)), null),
        )
        state.currentLevel = 2
        val fid = state.addLearnedClause(Clause(intArrayOf(negAtom(1), negAtom(2))), lbd = 2)

        val learned = assertIs<ConflictAnalyzer.AnalysisResult.Learned>(state.conflictAnalyzer.analyze(fid))
        assertEquals(setOf(0, 2), varsOf(learned.literals))
        assertEquals(1, learned.backjumpLevel)
        assertTrue(learned.asserting)
    }

    @Test
    fun `two conflict-level atom leaves should yield a non-asserting clause`() {
        val state = atomGeState(intArrayOf(2, 2))
        state.currentLevel = 2
        val fid = state.addLearnedClause(Clause(intArrayOf(negAtom(0), negAtom(1))), lbd = 1)

        val learned = assertIs<ConflictAnalyzer.AnalysisResult.Learned>(state.conflictAnalyzer.analyze(fid))
        assertEquals(setOf(0, 1), varsOf(learned.literals))
        assertEquals(
            2,
            learned.literals.count { levelOf(state, Lit.variable(it)) == 2 },
        )
        assertTrue(!learned.asserting)
    }
}
