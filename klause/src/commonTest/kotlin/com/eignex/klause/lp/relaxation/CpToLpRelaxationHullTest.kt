package com.eignex.klause.lp.relaxation

import com.eignex.klause.factor.global.GlobalCardinality
import com.eignex.klause.factor.global.NValue
import com.eignex.klause.factor.table.Element
import com.eignex.klause.factor.table.Mdd
import com.eignex.klause.factor.table.Regular
import com.eignex.klause.factor.table.Table
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.engine.FloatLpStatus
import com.eignex.klause.lp.engine.LpSolution
import com.eignex.klause.lp.engine.solveLp
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.objective.LinearObjective
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CpToLpRelaxationHullTest {
    private val eps = 1e-7

    private class HullCase(
        val name: String,
        val problem: (openIntHi: BooleanArray?) -> Problem,
        val openIntHi: BooleanArray,
        val objective: LinearObjective,
        val relax: (Problem, LinearObjective, Boolean) -> CpToLpRelaxation,
    )

    private fun intCol(r: LpRelaxation, v: Int): Int {
        for (c in r.colVarId.indices) if (!r.colIsBool[c] && r.colVarId[c] == v) return c
        return -1
    }

    private fun tableProblem(d1: IntDomain = IntDomain(0, 5), openIntHi: BooleanArray? = null) = Problem(
        numBoolVars = 0,
        numIntVars = 2,
        intDomains = arrayOf(IntDomain(0, 4), d1),
        factors = arrayOf<Factor>(Table(xs = intArrayOf(0, 1), tuples = longArrayOf(0, 5, 2, 2, 4, 0))),
        openIntHi = openIntHi,
    )

    private fun elementProblem(idx: IntDomain = IntDomain(0, 3), offset: Int = 0, openIntHi: BooleanArray? = null) =
        Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(idx, IntDomain(0, 20)),
            factors = arrayOf<Factor>(
                Element(idx = 0, result = 1, arr = longArrayOf(7, 3, 9, 5), arrIsVars = false, indexOffset = offset),
            ),
            openIntHi = openIntHi,
        )

    private fun varElementProblem(
        idx: IntDomain = IntDomain(0, 1),
        a0: IntDomain = IntDomain(4, 10),
        a1: IntDomain = IntDomain(2, 8),
        openIntHi: BooleanArray? = null,
    ) = Problem(
        numBoolVars = 0,
        numIntVars = 4,
        intDomains = arrayOf(idx, IntDomain(0, 50), a0, a1),
        factors = arrayOf<Factor>(
            Element(idx = 0, result = 1, arr = longArrayOf(2, 3), arrIsVars = true, indexOffset = 0),
        ),
        openIntHi = openIntHi,
    )

    private fun nValueProblem(openIntHi: BooleanArray? = null) = Problem(
        numBoolVars = 0,
        numIntVars = 4,
        intDomains = arrayOf(IntDomain(0, 1), IntDomain(1, 2), IntDomain(0, 2).excludeValue(1), IntDomain(0, 3)),
        factors = arrayOf<Factor>(NValue(n = 3, xs = intArrayOf(0, 1, 2), mode = NValue.Mode.Eq)),
        openIntHi = openIntHi,
    )

    private fun gccProblem(openIntHi: BooleanArray? = null) = Problem(
        numBoolVars = 0,
        numIntVars = 4,
        intDomains = arrayOf(IntDomain(1, 2), IntDomain(1, 2), IntDomain(0, 2), IntDomain(0, 2)),
        factors = arrayOf<Factor>(
            GlobalCardinality(xs = intArrayOf(0, 1), cover = longArrayOf(1, 2), countVars = intArrayOf(2, 3)),
        ),
        openIntHi = openIntHi,
    )

    private fun regularProblem(openIntHi: BooleanArray? = null) = Problem(
        numBoolVars = 0,
        numIntVars = 3,
        intDomains = Array(3) { IntDomain(1, 2) },
        factors = arrayOf<Factor>(
            Regular(intArrayOf(0, 1, 2), 2, 2, longArrayOf(1, 2, 2, 1), q0 = 1, accepting = intArrayOf(1)),
        ),
        openIntHi = openIntHi,
    )

    private fun mddProblem(openIntHi: BooleanArray? = null) = Problem(
        numBoolVars = 0,
        numIntVars = 3,
        intDomains = arrayOf(IntDomain(1, 2), IntDomain(1, 2), IntDomain(0, 100)),
        factors = arrayOf<Factor>(
            Mdd(
                seq = intArrayOf(0, 1),
                numStatesPerLayer = intArrayOf(1, 1, 1),
                layerStarts = intArrayOf(0, 8, 16),
                transitions = longArrayOf(0, 1, 0, 5, 0, 2, 0, 1, 0, 1, 0, 5, 0, 2, 0, 1),
                initial = 0,
                accepting = intArrayOf(0),
                recordStride = 4,
                cost = 2,
            ),
        ),
        openIntHi = openIntHi,
    )

    private val minimizeN = LinearObjective(intCoefficients = longArrayOf(0L, 0L, 0L, 1L))
    private val maximizeTotalCount = LinearObjective(intCoefficients = longArrayOf(0, 0, -1, -1))

    private val hullCases = listOf(
        HullCase(
            "table",
            { tableProblem(openIntHi = it) },
            booleanArrayOf(false, true),
            LinearObjective(intCoefficients = longArrayOf(1L, 1L)),
        ) { p, o, on -> CpToLpRelaxation(p, o, tableHull = on) },
        HullCase(
            "element",
            { elementProblem(openIntHi = it) },
            booleanArrayOf(true, false),
            LinearObjective(intCoefficients = longArrayOf(0L, 1L)),
        ) { p, o, on -> CpToLpRelaxation(p, o, elementHull = on) },
        HullCase("nvalue", { nValueProblem(it) }, booleanArrayOf(false, true, false, false), minimizeN) { p, o, on ->
            CpToLpRelaxation(p, o, nValueHull = on)
        },
        HullCase("gcc", { gccProblem(it) }, booleanArrayOf(true, false, false, false), maximizeTotalCount) { p, o, on ->
            CpToLpRelaxation(p, o, gccCountHull = on)
        },
        HullCase(
            "regular",
            { regularProblem(it) },
            booleanArrayOf(false, true, false),
            LinearObjective(intCoefficients = longArrayOf(1, 1, 1)),
        ) { p, o, on -> CpToLpRelaxation(p, o, regularHull = on) },
        HullCase(
            "mdd",
            { mddProblem(it) },
            booleanArrayOf(false, true, false),
            LinearObjective(intCoefficients = longArrayOf(0, 0, 1)),
        ) { p, o, on -> CpToLpRelaxation(p, o, mddHull = on) },
    )

    @Test
    fun `hulls should skip a variable the model leaves open`() {
        // Selectors or flow rows over an invented endpoint would refute assignments the model admits.
        for (case in hullCases) {
            val p = case.problem(case.openIntHi)
            val hull = case.relax(p, case.objective, true).build(PropagationSession(p))
            val bare = case.relax(p, case.objective, false).build(PropagationSession(p))
            assertEquals(bare.model.m, hull.model.m, case.name)
            assertEquals(bare.model.n, hull.model.n, case.name)
        }
    }

    @Test
    fun `table hull should minimize a linear objective over the allowed tuples`() {
        val p = tableProblem()
        val r = CpToLpRelaxation(p, LinearObjective(intCoefficients = longArrayOf(1L, 1L)), tableHull = true)
            .build(PropagationSession(p))
        val sol = solveLp(r.model)

        assertEquals(FloatLpStatus.OPTIMAL, sol.status)
        assertEquals(4.0, sol.objectiveValue, eps)
        // The optimum is a tie on the (2,2)-(4,0) face, so assert the face rather than a vertex.
        assertEquals(4.0, sol.primal(intCol(r, 0)) + sol.primal(intCol(r, 1)), eps)
    }

    @Test
    fun `table hull should drop tuples removed by a shrunk domain`() {
        val p = tableProblem(d1 = IntDomain(0, 1))
        val r = CpToLpRelaxation(p, LinearObjective(intCoefficients = longArrayOf(1L, 1L)), tableHull = true)
            .build(PropagationSession(p))
        val sol = solveLp(r.model)

        assertEquals(FloatLpStatus.OPTIMAL, sol.status)
        assertEquals(4.0, sol.primal(intCol(r, 0)), eps)
        assertEquals(0.0, sol.primal(intCol(r, 1)), eps)
    }

    private fun solveElement(p: Problem, obj: LinearObjective): Pair<LpSolution, LpRelaxation> {
        val r = CpToLpRelaxation(p, obj, elementHull = true).build(PropagationSession(p))
        return solveLp(r.model) to r
    }

    @Test
    fun `constant array element hull should bound the result by the min selectable entry`() {
        val (sol, r) = solveElement(elementProblem(), LinearObjective(intCoefficients = longArrayOf(0L, 1L)))

        assertEquals(FloatLpStatus.OPTIMAL, sol.status)
        assertEquals(3.0, sol.objectiveValue, eps)
        assertEquals(3.0, sol.primal(intCol(r, 1)), eps)
    }

    @Test
    fun `element hull index channel should tie idx to the selected position`() {
        val p = elementProblem(idx = IntDomain(1, 4), offset = 1)
        val (sol, r) = solveElement(p, LinearObjective(intCoefficients = longArrayOf(0L, 1L)))

        assertEquals(3.0, sol.objectiveValue, eps)
        assertEquals(2.0, sol.primal(intCol(r, 0)), eps)
    }

    private val minimizeResult = LinearObjective(intCoefficients = longArrayOf(0L, 1L, 0L, 0L))

    @Test
    fun `var array element hull should be exact when the index is fixed`() {
        listOf(0 to 4.0, 1 to 2.0).forEach { (position, expected) ->
            val p = varElementProblem(idx = IntDomain(position.toLong(), position.toLong()))
            val (sol, _) = solveElement(p, minimizeResult)
            assertEquals(FloatLpStatus.OPTIMAL, sol.status, "idx=$position")
            assertEquals(expected, sol.objectiveValue, eps, "idx=$position")
        }
    }

    @Test
    fun `var array element hull should drop only the big-M rows of an open coordinate`() {
        val closed = varElementProblem()
        val closedRows = CpToLpRelaxation(closed, minimizeResult, elementHull = true)
            .build(PropagationSession(closed)).model.m
        for ((openVar, droppedRows) in listOf(3 to 2, 1 to 4)) {
            val open = varElementProblem(openIntHi = BooleanArray(4) { it == openVar })
            val openRows = CpToLpRelaxation(open, minimizeResult, elementHull = true)
                .build(PropagationSession(open)).model.m
            assertEquals(closedRows - droppedRows, openRows, "open x$openVar")
        }
    }

    @Test
    fun `nvalue hull should lower-bound distinct values beyond the greedy propagator bound`() {
        val p = nValueProblem()
        val session = PropagationSession(p)
        val bare = solveLp(CpToLpRelaxation(p, minimizeN, nValueHull = false).build(session).model)
        val hull = solveLp(CpToLpRelaxation(p, minimizeN, nValueHull = true).build(session).model)
        assertEquals(FloatLpStatus.OPTIMAL, hull.status)
        assertEquals(1.5, hull.objectiveValue, eps)
        assertTrue(hull.objectiveValue > bare.objectiveValue + eps)
    }

    @Test
    fun `nvalue hull should omit excepted values from the count while retaining their selectors`() {
        for ((except, expected) in listOf(longArrayOf(0) to 1.0, longArrayOf(0, 2) to 0.0)) {
            val problem = Problem(0, 3, arrayOf(IntDomain(0, 0), IntDomain(2, 2), IntDomain(0, 2)),
                arrayOf(NValue(2, intArrayOf(0, 1), exceptSet = except)))
            val objective = LinearObjective(intCoefficients = longArrayOf(0, 0, 1))

            val hull = solveLp(CpToLpRelaxation(problem, objective, nValueHull = true)
                .build(PropagationSession(problem)).model)

            assertEquals(FloatLpStatus.OPTIMAL, hull.status)
            assertEquals(expected, hull.objectiveValue, eps)
        }
    }

    @Test
    fun `gcc count hull should capture the joint count sum the per-count propagator misses`() {
        val p = gccProblem()
        val session = PropagationSession(p)
        val bare = solveLp(CpToLpRelaxation(p, maximizeTotalCount, gccCountHull = false).build(session).model)
        val hull = solveLp(CpToLpRelaxation(p, maximizeTotalCount, gccCountHull = true).build(session).model)
        assertEquals(FloatLpStatus.OPTIMAL, hull.status)
        assertEquals(-2.0, hull.objectiveValue, eps)
        assertTrue(hull.objectiveValue > bare.objectiveValue + eps)
    }

    @Test
    fun `regular flow hull should give the exact optimum on an even-count DFA`() {
        val p = regularProblem()
        val r = CpToLpRelaxation(p, LinearObjective(intCoefficients = longArrayOf(1, 1, 1)), regularHull = true)
            .build(PropagationSession(p))
        val sol = solveLp(r.model)
        assertEquals(FloatLpStatus.OPTIMAL, sol.status)
        assertEquals(3.0, sol.objectiveValue, eps)
    }

    @Test
    fun `regular flow hull should admit alphabet symbols from a wide domain`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(-1000000000000L, 1000000000000L)),
            factors = arrayOf<Factor>(
                Regular(intArrayOf(0), 1, 2, longArrayOf(1, 1), q0 = 1, accepting = intArrayOf(1)),
            ),
        )

        val relaxation = CpToLpRelaxation(
            problem,
            LinearObjective(intCoefficients = longArrayOf(1)),
            regularHull = true,
        ).build(PropagationSession(problem))

        assertEquals(3, relaxation.model.n)
        assertEquals(3, relaxation.model.m)
    }

    @Test
    fun `cost mdd flow hull should give the exact minimum path cost`() {
        val p = mddProblem()
        val r = CpToLpRelaxation(p, LinearObjective(intCoefficients = longArrayOf(0, 0, 1)), mddHull = true)
            .build(PropagationSession(p))
        val sol = solveLp(r.model)
        assertEquals(FloatLpStatus.OPTIMAL, sol.status)
        assertEquals(2.0, sol.objectiveValue, eps)
    }
}
