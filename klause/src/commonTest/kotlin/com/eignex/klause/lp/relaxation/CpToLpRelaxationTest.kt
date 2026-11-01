package com.eignex.klause.lp.relaxation

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedCardinality
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.arithmetic.ReifiedPseudoBoolean
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.bool.PseudoBoolean
import com.eignex.klause.factor.circuit.Circuit
import com.eignex.klause.factor.scheduling.Diffn
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.engine.FloatLpStatus
import com.eignex.klause.lp.engine.LpSolution
import com.eignex.klause.lp.engine.RevisedSimplex
import com.eignex.klause.lp.engine.solveLp
import com.eignex.klause.model.PbOp
import com.eignex.klause.propagation.CpIntId
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CpToLpRelaxationTest {
    @Test
    fun `charged assembly should report work when its debit cancels`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(0, 10)), arrayOf<Factor>(
            Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 3),
        ))
        var cancelled = false
        var work = 0L

        assertFailsWith<LpAssemblyCancelled> {
            CpToLpRelaxation(problem, null).buildWithWork(RootDomains(problem), Cancellation { cancelled }) {
                work += it
                cancelled = true
            }
        }

        assertTrue(work > 0L)
    }

    @Test
    fun `completed assembly reports no discarded work`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(0, 10)), arrayOf<Factor>(
            Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 3),
        ))
        var discardCalls = 0

        CpToLpRelaxation(problem, null).build(RootDomains(problem), emptyList(), Cancellation.Never) {
            discardCalls++
        }

        assertEquals(0, discardCalls)
    }

    @Test
    fun `cancelled assembly reports discarded construction work`() {
        val problem = Problem(0, 4, Array(4) { IntDomain(0, 10) }, arrayOf<Factor>(
            Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 5),
            Linear(intArrayOf(1, 1), intArrayOf(2, 3), LinearOp.GE, 5),
        ))
        val root = RootDomains(problem)
        var cancelled = false
        val domains = object : RelaxationDomains by root {
            override fun intDomain(varId: Int): IntDomain = root.intDomain(varId).also {
                if (varId == 2) cancelled = true
            }
        }
        var discardedWork = 0L

        assertFailsWith<LpAssemblyCancelled> {
            CpToLpRelaxation(problem, null).build(domains, emptyList(), Cancellation { cancelled }) {
                discardedWork += it
            }
        }

        assertTrue(discardedWork > 0L)
    }

    @Test
    fun `unrepresentable Boolean rows should decline through direct and delegated source views`() {
        val sources = listOf(
            PseudoBoolean(longArrayOf(Long.MAX_VALUE, 1), IntArray(2) { Lit.make(it, true) },
                PbOp.LE, Long.MAX_VALUE),
            PseudoBoolean(longArrayOf(Long.MIN_VALUE), intArrayOf(Lit.make(0, false)), PbOp.GE, Long.MIN_VALUE),
        )
        for (source in sources) for (factor in listOf<Factor>(source, object : Factor by source {})) {
            val problem = Problem(2, 0, emptyArray(), arrayOf(factor))
            val objective = LinearObjective(boolWeights = longArrayOf(1, 1))

            val relaxation = CpToLpRelaxation(problem, objective).build(RootDomains(problem))

            assertEquals(0, relaxation.model.m)
        }
    }

    @Test
    fun `typed LP handles should map back to the finite integer they represent`() {
        val problem = Problem(
            0,
            1,
            arrayOf(IntDomain(0, 10)),
            arrayOf<Factor>(Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 3)),
        )
        val relaxation = CpToLpRelaxation(problem, null).build(PropagationSession(problem))

        val column = checkNotNull(relaxation.column(CpIntId(0)))

        assertEquals(CpIntId(0), relaxation.cpInteger(column))
    }

    private val eps = 1e-9

    private fun solve(problem: Problem, objective: LinearObjective?): Pair<LpSolution, LpRelaxation> {
        val relaxation = CpToLpRelaxation(problem, objective).build(PropagationSession(problem))
        return solveLp(relaxation.model) to relaxation
    }

    private fun intCol(r: LpRelaxation, v: Int): Int {
        for (c in r.colVarId.indices) if (!r.colIsBool[c] && r.colVarId[c] == v) return c
        return -1
    }

    @Test
    fun `linear constraints should bound the objective`() {
        val p = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 10), IntDomain(0, 10)),
            factors = arrayOf<Factor>(
                Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 5),
                Linear(intArrayOf(1), intArrayOf(1), LinearOp.LE, 3),
            ),
        )
        val (sol, r) = solve(p, LinearObjective(intCoefficients = longArrayOf(1L, 0L)))

        assertEquals(FloatLpStatus.OPTIMAL, sol.status)
        assertEquals(2.0, sol.objectiveValue, eps)
        assertEquals(2.0, sol.primal(intCol(r, 0)), eps)
    }

    @Test
    fun `objective constant should be carried separately`() {
        val p = Problem(0, 1, arrayOf(IntDomain(0, 10)), arrayOf<Factor>())
        val (sol, r) = solve(p, LinearObjective(intCoefficients = longArrayOf(1L), constant = 100L))

        assertEquals(FloatLpStatus.OPTIMAL, sol.status)
        assertEquals(100L, r.objectiveConstant)
        assertEquals(0.0, sol.objectiveValue, eps)
        assertEquals(100.0, sol.objectiveValue + r.objectiveConstant, eps)
    }

    @Test
    fun `free reification should not force its linear constraint`() {
        val p = Problem(
            numBoolVars = 1,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(0, 10)),
            factors = arrayOf<Factor>(
                ReifiedLinear(
                    auxBoolVar = 0,
                    coeffs = intArrayOf(1),
                    vars = intArrayOf(0),
                    op = LinearOp.GE,
                    bound = 8,
                ),
            ),
        )
        val (sol, r) = solve(p, LinearObjective(intCoefficients = longArrayOf(1L)))

        assertEquals(FloatLpStatus.OPTIMAL, sol.status)
        assertEquals(0.0, sol.primal(intCol(r, 0)), eps)
    }

    @Test
    fun `pinned reification should enforce its linear constraint`() {
        val p = Problem(
            numBoolVars = 1,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(0, 10)),
            factors = arrayOf<Factor>(
                Clause(intArrayOf(Lit.make(0, true))),
                ReifiedLinear(
                    auxBoolVar = 0,
                    coeffs = intArrayOf(1),
                    vars = intArrayOf(0),
                    op = LinearOp.GE,
                    bound = 8,
                ),
            ),
        )
        val (sol, r) = solve(p, LinearObjective(intCoefficients = longArrayOf(1L)))

        assertEquals(FloatLpStatus.OPTIMAL, sol.status)
        assertEquals(8.0, sol.primal(intCol(r, 0)), eps)
    }

    @Test
    fun `bool to 01 channel should relax to exactly v equals aux`() {
        val p = Problem(
            numBoolVars = 1,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(0, 1)),
            factors = arrayOf<Factor>(
                ReifiedLinear(
                    auxBoolVar = 0,
                    coeffs = intArrayOf(1),
                    vars = intArrayOf(0),
                    op = LinearOp.EQ,
                    bound = 1,
                ),
            ),
        )
        for ((intC, boolW) in listOf(1L to -1L, -1L to 1L)) {
            val obj = LinearObjective(boolWeights = longArrayOf(boolW), intCoefficients = longArrayOf(intC))
            val sol = solveLp(CpToLpRelaxation(p, obj).build(PropagationSession(p)).model)
            assertEquals(FloatLpStatus.OPTIMAL, sol.status)
            assertEquals(0.0, sol.objectiveValue, eps)
        }
    }

    private fun feasibleAt(p: Problem, bits: BooleanArray): Boolean {
        val r = CpToLpRelaxation(p, null).build(PropagationSession(p))
        val m = r.model
        val x = LongArray(m.n) { col -> (if (bits[r.colVarId[col]]) 1L else 0L) - m.loShift[col] }
        val s = LongArray(m.m)
        for (j in 0 until m.n) m.forEachInColumn(j) { i, v -> s[i] += v * x[j] }
        for (i in 0 until m.m) if (s[i] > m.rhs[i]) return false
        return true
    }

    private fun excludedInconsistent(p: Problem, nBool: Int, aux: Int, cond: (BooleanArray) -> Boolean): Int {
        var excluded = 0
        for (mask in 0 until (1 shl nBool)) {
            val bits = BooleanArray(nBool) { (mask shr it) and 1 == 1 }
            val feasible = feasibleAt(p, bits)
            if (bits[aux] == cond(bits)) {
                assertTrue(feasible, "reified-consistent assignment ${bits.toList()} was cut by the relaxation")
            } else if (!feasible) {
                excluded++
            }
        }
        return excluded
    }

    private fun b(bits: BooleanArray, i: Int): Int = if (bits[i]) 1 else 0

    private fun pbProblem(op: PbOp, lits: IntArray, weights: LongArray, aux: Int) = Problem(
        numBoolVars = aux + 1,
        numIntVars = 0,
        intDomains = arrayOf(),
        factors = arrayOf<Factor>(ReifiedPseudoBoolean(aux, weights, lits, op, 3L)),
    )

    @Test
    fun `reified pseudo-boolean LE should be sound and exclude violating directions`() {
        val positive = pbProblem(
            PbOp.LE,
            intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true)),
            longArrayOf(2, 3, 1),
            aux = 3,
        )
        assertTrue(excludedInconsistent(positive, 4, 3) { 2 * b(it, 0) + 3 * b(it, 1) + b(it, 2) <= 3 } > 0)

        val negated = pbProblem(PbOp.LE, intArrayOf(Lit.make(0, true), Lit.make(1, false)), longArrayOf(2, 3), aux = 2)
        assertTrue(excludedInconsistent(negated, 3, 2) { 2 * b(it, 0) + 3 * (1 - b(it, 1)) <= 3 } > 0)
    }

    @Test
    fun `reified pseudo-boolean EQ should be sound`() {
        val p = pbProblem(
            PbOp.EQ,
            intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true)),
            longArrayOf(2, 3, 1),
            aux = 3,
        )
        excludedInconsistent(p, 4, 3) { 2 * b(it, 0) + 3 * b(it, 1) + b(it, 2) == 3 }
    }

    @Test
    fun `reified cardinality range should be sound`() {
        val p = Problem(
            numBoolVars = 4,
            numIntVars = 0,
            intDomains = arrayOf(),
            factors = arrayOf<Factor>(
                ReifiedCardinality(3, intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true)), 1, 2),
            ),
        )
        excludedInconsistent(p, 4, 3) { b(it, 0) + b(it, 1) + b(it, 2) in 1..2 }
    }

    @Test
    fun `objective cone should drop big-M rows yet still bound the critical path`() {
        val p = Problem(
            numBoolVars = 2,
            numIntVars = 4,
            intDomains = arrayOf(IntDomain(0, 20), IntDomain(0, 20), IntDomain(0, 20), IntDomain(0, 40)),
            factors = arrayOf<Factor>(
                Linear(intArrayOf(1, -1), intArrayOf(1, 0), LinearOp.GE, 3),
                Linear(intArrayOf(1, -1), intArrayOf(2, 1), LinearOp.GE, 4),
                Linear(intArrayOf(1, -1), intArrayOf(3, 2), LinearOp.GE, 5),
                ReifiedLinear(0, intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.LE, -3),
                ReifiedLinear(1, intArrayOf(1, -1), intArrayOf(1, 2), LinearOp.LE, -4),
            ),
        )
        val objective = LinearObjective(intCoefficients = longArrayOf(0L, 0L, 0L, 1L))
        val full = CpToLpRelaxation(p, objective).build(PropagationSession(p))
        val cone = CpToLpRelaxation(p, objective, objectiveCone = true).build(PropagationSession(p))

        assertEquals(3, cone.model.m)
        assertTrue(cone.model.n < full.model.n)
        assertTrue(cone.colIsBool.none { it })
        val coneSol = solveLp(cone.model)
        assertEquals(FloatLpStatus.OPTIMAL, coneSol.status)
        assertEquals(12.0, coneSol.objectiveValue, eps)
        assertTrue(coneSol.objectiveValue <= solveLp(full.model).objectiveValue + eps)
    }

    @Test
    fun `RLT relaxation bound should stay below the integer optimum and no weaker than plain`() {
        val problem = Problem(
            0,
            3,
            Array(3) { IntDomain(0, 1) },
            arrayOf(
                Linear(intArrayOf(2, 2, 2), intArrayOf(0, 1, 2), LinearOp.LE, 3),
                Linear(intArrayOf(1, 1), intArrayOf(0, 2), LinearOp.GE, 1),
            ),
        )
        val objective = LinearObjective(intCoefficients = longArrayOf(-1, -1, -1))
        fun lpOptimum(rlt: Boolean): Double {
            val relaxation = CpToLpRelaxation(problem, objective, booleanRlt = rlt).build(PropagationSession(problem))
            return assertNotNull(RevisedSimplex(relaxation.model).solve()).objective
        }

        val rlt = lpOptimum(rlt = true)

        assertTrue(rlt <= -1.0 + 1e-6)
        assertTrue(rlt >= lpOptimum(rlt = false) - 1e-6)
    }

    @Test
    fun `diffn area bound should lower-bound the strip length`() {
        val n = 3
        val lVar = 2 * n
        val domains = Array(2 * n + 1) { i ->
            when {
                i < n -> IntDomain(0, 10)
                i < 2 * n -> IntDomain(0, 2)
                else -> IntDomain(0, 20)
            }
        }
        val factors = ArrayList<Factor>()
        for (i in 0 until n) factors.add(Linear(intArrayOf(1, -1), intArrayOf(lVar, i), LinearOp.GE, 2))
        factors.add(Diffn(IntArray(n) { it }, IntArray(n) { n + it }, longArrayOf(2, 2, 2), longArrayOf(2, 2, 2)))
        val p = Problem(0, 2 * n + 1, domains, factors.toTypedArray())
        val obj = LinearObjective(intCoefficients = LongArray(p.numIntVars) { if (it == lVar) 1L else 0L })
        fun stripLengthBound(diffn: Boolean) =
            solveLp(CpToLpRelaxation(p, obj, diffn = diffn).build(PropagationSession(p)).model).objectiveValue

        assertEquals(2.0, stripLengthBound(diffn = false), 1e-6)
        assertEquals(3.0, stripLengthBound(diffn = true), 1e-6)
    }

    private fun subcircuitProblem(openSuccAbove: Boolean = false): Problem = Problem(
        numBoolVars = 0,
        numIntVars = 4,
        intDomains = Array(4) { IntDomain(0, 3) },
        factors = arrayOf<Factor>(
            Circuit(intArrayOf(0, 1, 2, 3), subcircuit = true),
            Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 1),
        ),
        openIntHi = BooleanArray(4) { openSuccAbove && it == 0 },
    )

    private val firstSuccessor = LinearObjective(intCoefficients = longArrayOf(1L, 0L, 0L, 0L))

    @Test
    fun `subcircuit arc relaxation should build arc columns without subtour elimination`() {
        val p = subcircuitProblem()
        val r = CpToLpRelaxation(p, firstSuccessor, circuitArcs = true).build(PropagationSession(p))
        assertTrue(r.model.n > 4)
        assertTrue(r.circuitArcs.isEmpty())
        assertNotNull(RevisedSimplex(r.model).solve())
    }

    @Test
    fun `subcircuit arc relaxation should skip a successor the model leaves open`() {
        val p = subcircuitProblem(openSuccAbove = true)
        val r = CpToLpRelaxation(p, firstSuccessor, circuitArcs = true).build(PropagationSession(p))
        assertTrue(r.colVarId.all { it >= 0 })
    }
}
