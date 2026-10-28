package com.eignex.klause.propagation

import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.bool.PseudoBooleanPropagator
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.solver.search.SearchAtomRegistry
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.SearchResult
import com.eignex.klause.solver.search.SearchRunObserver
import com.eignex.klause.solver.search.SearchSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PbConflictResolventTest {
    @Test
    fun `the same counting conflict should store and consume PB through shared traversal`() {
        for (mode in 0..2) {
            val holes = 4
            val factors = buildList<Factor> {
                for (pigeon in 0..holes) {
                    add(Cardinality.atLeastOne(IntArray(holes) { Lit.make(pigeon * holes + it, true) }))
                }
                for (hole in 0 until holes) {
                    add(Cardinality.atMostOne(IntArray(holes + 1) { Lit.make(it * holes + hole, true) }))
                }
                if (mode == 2) add(ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.LE, 5))
            }
            val native = PropagationSession(
                Problem(20, if (mode == 0) 0 else 1,
                    if (mode == 0) emptyArray() else arrayOf(IntDomain(0, 10)), factors.toTypedArray()),
                pbLearning = true,
            )
            val cp = CpSearchComponent(native)
            val shared = SearchSession(listOf(cp), atoms = SearchAtomRegistry(20))
            var consumed = false
            val observer = object : SearchRunObserver {
                override fun onCommit(decision: SearchDecision, decisionLevel: Int) {
                    val state = native.explanationState
                    for (variable in 0 until 20) {
                        val fid = state.boolReason[variable]
                        if (fid >= state.baseFactorCount && fid < state.totalFactorCount &&
                            state.factorAt(fid) is PseudoBooleanPropagator
                        ) consumed = true
                    }
                }
            }

            assertIs<SearchResult.Exhausted>(shared.solve(20, observer = observer))

            assertTrue(native.explanationState.learned.store.any { it is PseudoBooleanPropagator }, "mode=$mode")
            assertTrue(consumed, "mode=$mode")
        }
    }

    @Test
    fun `an atom introduced only by a pivot reason should retain clause fallback`() {
        for (late in listOf(false, true)) {
            val state = PropagationState(Problem(3, 1, arrayOf(IntDomain(0, 10)), emptyArray()),
                Assumptions.None, pbLearning = true)
            state.undoLogging = true
            val atom = if (late) -1 else state.atomVarGe(0, 5)
            assertTrue(state.setIntMinAsDecision(0, 5))
            val bound = if (late) state.atomVarGe(0, 5) else atom
            val premise = Lit.make(bound, false)
            state.currentLevel = 2
            assertTrue(state.pinBool(0, true))
            assertTrue(state.pinBool(1, true, intArrayOf(premise)))
            assertTrue(state.pinBool(2, true, intArrayOf(Lit.make(0, false))))

            val learned = state.conflictAnalyzer.analyzeConflictClause(intArrayOf(Lit.make(1, false), Lit.make(2, false)))

            assertIs<ConflictAnalyzer.AnalysisResult.Learned>(learned)
            assertTrue(learned.literals.contains(premise))
        }
    }

    @Test
    fun `a mixed feasible witness should satisfy the connected source relation`() {
        val native = PropagationSession(Problem(2, 1, arrayOf(IntDomain(0, 10)), arrayOf(
            Cardinality.exactlyOne(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
            ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.LE, 5),
        )), pbLearning = true)
        val shared = SearchSession(listOf(CpSearchComponent(native)), atoms = SearchAtomRegistry(2))

        assertIs<SearchResult.Satisfied>(shared.solve(2))

        assertEquals(native.boolValue(0), native.intDomain(0).min <= 5)
        assertEquals(native.intDomain(0).min, native.intDomain(0).max)
    }

    @Test
    fun `a seed atom should not enter Boolean assertion indexing`() {
        val native = PropagationSession(Problem(1, 1, arrayOf(IntDomain(0, 10)), arrayOf(
            ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.LE, 5),
        )), pbLearning = true)
        assertIs<PropagationResult.Implied>(native.pinIntAtLeast(0, 6))

        val conflict = assertIs<PropagationResult.Unsat>(native.pinBool(0, true))

        assertIs<ConflictAnalyzer.AnalysisResult.Learned>(conflict.learnedClause)
    }
}
