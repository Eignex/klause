package com.eignex.klause.backtrack.selector

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.PropagationResult.Unsat
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.search.VarRef
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class VsidsTest {
    @Test
    fun `an attributed failed decision should receive one activity bump`() {
        val session = PropagationSession(Problem(3, 0, emptyArray(), emptyArray()))
        val vsids = Vsids()
        val rng = Random(0)
        vsids.onUnassign(assertNotNull(vsids.pick(session, rng)))
        vsids.onConflict(VarRef.Bool(2), Unsat())

        vsids.onConflict(VarRef.Bool(0), Unsat(conflictBools = intArrayOf(0, 2)))

        assertEquals(VarRef.Bool(2), vsids.pick(session, rng))
    }

    @Test
    fun `finite search should prioritize a conflict implication after its backjump`() {
        for (nativeSat in listOf(false, true)) {
            val base = Vsids()
            val picks = ArrayList<VarRef>()
            val selector = object : VariableSelector by base {
                override fun fresh(): VariableSelector = this

                override fun pick(session: PropagationSession, rng: Random): VarRef? {
                    val selected = if (picks.size == 1) VarRef.Bool(1) else base.pick(session, rng)
                    if (picks.isEmpty()) base.onConflict(VarRef.Bool(2), Unsat())
                    return selected?.also(picks::add)
                }
            }
            val problem = Problem(
                5,
                0,
                emptyArray(),
                arrayOf(
                    Clause(intArrayOf(Lit.make(0, false), Lit.make(3, true))),
                    Clause(intArrayOf(Lit.make(1, false), Lit.make(4, true))),
                    Clause(intArrayOf(Lit.make(3, false), Lit.make(4, false), Lit.make(1, false))),
                ),
            )

            val result = BacktrackSolver(problem.bake()).solve(
                BacktrackParams(variableSelector = selector, valueSelector = IndomainMax, nativeSat = nativeSat),
            )

            assertIs<SolveResult.Sat>(result)
            assertEquals(listOf(VarRef.Bool(0), VarRef.Bool(1), VarRef.Bool(4)), picks.take(3))
        }
    }

    @Test
    fun `vsids should resize activity arrays across problems`() {
        val vsids = Vsids()
        val p1 = Problem(
            numBoolVars = 3,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(Clause(intArrayOf(Lit.make(0, true)))),
        )
        val r1 = BacktrackSolver(p1.bake()).solve(BacktrackParams(variableSelector = vsids))
        assertIs<SolveResult.Sat>(r1)

        val p2 = Problem(
            numBoolVars = 7,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(Clause(intArrayOf(Lit.make(6, true)))),
        )
        val r2 = BacktrackSolver(p2.bake()).solve(BacktrackParams(variableSelector = vsids))
        assertIs<SolveResult.Sat>(r2)
        assertEquals(true, r2.assignment.bools[6])
    }

    @Test
    fun `pick should re-offer an open variable stranded out of the heap`() {
        // A missed onUnassign re-add strands an open variable out of the heap; a null pick would let the
        // engine emit an incomplete assignment as a solution.
        val problem = Problem(0, 1, arrayOf(IntDomain(0, 3)), emptyArray())
        val session = PropagationSession(problem)
        session.seed(Assumptions.None)
        val vsids = Vsids()
        val rng = Random(0)
        session.pinInt(0, 2) // x = 2 (singleton): x surfaces assigned and is dropped from the heap
        assertNull(vsids.pick(session, rng))
        session.popLast() // x widens back to [0, 3]; no unassign listener is wired, so the re-add is missed
        assertEquals(VarRef.IntVar(0), vsids.pick(session, rng))
    }
}
