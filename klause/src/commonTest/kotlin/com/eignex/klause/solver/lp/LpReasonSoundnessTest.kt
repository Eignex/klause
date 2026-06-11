package com.eignex.klause.solver.lp

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.backtrack.BacktrackSolver
import com.eignex.klause.solver.factor.AllDifferent
import com.eignex.klause.solver.factor.Clause
import com.eignex.klause.solver.factor.Linear
import com.eignex.klause.solver.factor.LinearOp
import com.eignex.klause.solver.factor.ReifiedLinear
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.propagation.PropagationResult
import com.eignex.klause.solver.propagation.PropagationSession
import com.eignex.klause.solver.result.MinimizeResult
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Witness validation of the LP-derived learned artifacts over *real* relaxations — pinned Boolean
 * columns, live-big-M [ReifiedLinear] rows and locally separated cuts — the structure the
 * [LpBuilder]-level harnesses ([FarkasExplanationTest], [ObjectiveBoundReasonTest]) cannot reach
 * because there every row is globally valid by construction. A learned clause or recorded reason
 * must never exclude an assignment that satisfies the problem's factors.
 */
class LpReasonSoundnessTest {

    /** True when [point] (ints) and [bools] satisfy every factor of [p]. */
    private fun feasible(p: Problem, point: IntArray, bools: BooleanArray): Boolean {
        for (f in p.factors) {
            val ok = when (f) {
                is Linear -> {
                    var s = 0L
                    for (k in f.vars.indices) s += f.coeffs[k].toLong() * point[f.vars[k]]
                    holds(s, f.op, f.bound)
                }

                is ReifiedLinear -> {
                    var s = 0L
                    for (k in f.vars.indices) s += f.coeffs[k].toLong() * point[f.vars[k]]
                    bools[f.auxBoolVar] == holds(s, f.op, f.bound)
                }

                is Clause -> f.literals.any { lit -> bools[Lit.variable(lit)] == Lit.isPositive(lit) }

                is AllDifferent -> {
                    var distinct = true
                    for (i in f.vars.indices) {
                        for (j in i + 1 until f.vars.size) {
                            if (point[f.vars[i]] == point[f.vars[j]]) distinct = false
                        }
                    }
                    distinct
                }

                else -> error("unhandled factor $f")
            }
            if (!ok) return false
        }
        return true
    }

    private fun holds(sum: Long, op: LinearOp, bound: Int): Boolean = when (op) {
        LinearOp.LE -> sum <= bound
        LinearOp.GE -> sum >= bound
        LinearOp.EQ -> sum == bound.toLong()
        LinearOp.NE -> sum != bound.toLong()
    }

    /** Enumerate every assignment over the declared boxes, calling [action] on factor-feasible ones. */
    private fun forEachSolution(p: Problem, action: (ints: IntArray, bools: BooleanArray) -> Unit) {
        val ints = IntArray(p.numIntVars)
        val bools = BooleanArray(p.numBoolVars)
        fun recBool(b: Int) {
            if (b == p.numBoolVars) {
                if (feasible(p, ints, bools)) action(ints, bools)
                return
            }
            bools[b] = false
            recBool(b + 1)
            bools[b] = true
            recBool(b + 1)
        }
        fun recInt(i: Int) {
            if (i == p.numIntVars) {
                recBool(0)
                return
            }
            for (v in p.intDomains[i].min..p.intDomains[i].max) {
                ints[i] = v
                recInt(i + 1)
            }
        }
        recInt(0)
    }

    /**
     * The Farkas nogood's literals must all be *false* at the dead node — that is the documented
     * contract of both the 1UIP seed (`analyzeConflictClause`) and the restart-flushed nogood. A
     * Boolean column pinned **true** by the branch is seated at its (collapsed) lower bound `1`, so
     * its premise is `b` and the clause must carry `¬b` — not the seat-side name `b`.
     */
    @Test
    fun `farkas clause literals are all false at the dead node even for pinned bools`() {
        // b <-> (x + y >= 6) with b pinned true, plus y + w >= 6, x + w >= 6, x + y + w <= 8.
        // Summing the three lower bounds forces x + y + w >= 9 > 8: LP-infeasible, while per-factor
        // bounds propagation is quiet (every single row is bounds-consistent on [0, 8]^3).
        val p = Problem(
            numBoolVars = 1,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(0, 10), IntDomain(0, 10), IntDomain(0, 10)),
            factors = arrayOf<Factor>(
                ReifiedLinear(0, intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 6),
                Linear(intArrayOf(1, 1), intArrayOf(1, 2), LinearOp.GE, 6),
                Linear(intArrayOf(1, 1), intArrayOf(0, 2), LinearOp.GE, 6),
                Linear(intArrayOf(1, 1, 1), intArrayOf(0, 1, 2), LinearOp.LE, 8),
            ),
        )
        val session = PropagationSession(p)
        assertTrue(session.pinBool(0, true) !is PropagationResult.Unsat)
        val objective = LinearObjective(intCoefficients = longArrayOf(1L, 0L, 0L))
        val relaxation = CpToLpRelaxation(p, objective).build(session)
        val solution = DualSimplex(relaxation.model).solve()
        assertEquals(LpStatus.INFEASIBLE, solution.status)

        // Semantic check on the raw certificate: no factor-feasible point sits in the premise box
        // (each cited column pinned to the *value side* it was seated at).
        forEachSolution(p) { ints, bools ->
            var inBox = true
            for (k in solution.certCols.indices) {
                val col = solution.certCols[k]
                val varId = relaxation.colVarId[col]
                val lo = relaxation.model.loShift[col]
                val hi = lo + relaxation.model.upper[col]
                val ok = if (relaxation.colIsBool[col]) {
                    val seated = (lo + if (solution.certBoundIsUpper[k]) relaxation.model.upper[col] else 0L) == 1L
                    bools[varId] == seated
                } else if (solution.certBoundIsUpper[k]) {
                    ints[varId] <= hi
                } else {
                    ints[varId] >= lo
                }
                if (!ok) inBox = false
            }
            assertTrue(!inBox, "feasible point ${ints.toList()}/${bools.toList()} inside the Farkas premise box")
        }

        val clause = LpExplanation.infeasibilityClause(relaxation, solution, session)
        assertNotNull(clause, "expected a learnable Farkas nogood")
        for (lit in clause) {
            assertEquals(
                false,
                session.litTruth(lit),
                "certificate literal must be false at the dead node (clause=${clause.toList()})",
            )
        }
    }

    /**
     * A Farkas ray that leans on a live-big-M reified row must cite the bounds behind the M instead
     * of being withheld. Three pairwise covers force `x + y + w ≥ 9` in the LP, while the reified
     * `¬(x + y + w ≥ 9)` face — built with the live big-M from the decision-tightened uppers — forces
     * `≤ 8`. The live box alone only gives `≤ 12`, so no certificate over column seats exists: the
     * ray *must* combine the non-global row, and the clause is expressible exactly because that row
     * recorded its premises (`x ≤ 4`, `y ≤ 4`, `w ≤ 4`).
     */
    @Test
    fun `farkas clause cites the live bounds behind a big-M row`() {
        val p = Problem(
            numBoolVars = 1,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(0, 10), IntDomain(0, 10), IntDomain(0, 10)),
            factors = arrayOf<Factor>(
                ReifiedLinear(0, intArrayOf(1, 1, 1), intArrayOf(0, 1, 2), LinearOp.GE, 9),
                Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 6),
                Linear(intArrayOf(1, 1), intArrayOf(1, 2), LinearOp.GE, 6),
                Linear(intArrayOf(1, 1), intArrayOf(0, 2), LinearOp.GE, 6),
            ),
        )
        val session = PropagationSession(p)
        assertTrue(session.pinIntAtMost(0, 4) !is PropagationResult.Unsat)
        assertTrue(session.pinIntAtMost(1, 4) !is PropagationResult.Unsat)
        assertTrue(session.pinIntAtMost(2, 4) !is PropagationResult.Unsat)
        assertTrue(session.pinBool(0, false) !is PropagationResult.Unsat)
        val objective = LinearObjective(intCoefficients = longArrayOf(1L, 0L, 0L))
        val relaxation = CpToLpRelaxation(p, objective).build(session)
        val solution = DualSimplex(relaxation.model).solve()
        assertEquals(LpStatus.INFEASIBLE, solution.status)
        // The ray genuinely leans on a non-global row (the big-M face is stronger than the box).
        assertTrue(
            solution.certRows.any { !relaxation.model.rowGlobal[it] },
            "the certificate must combine the live-big-M row",
        )

        val clause = LpExplanation.infeasibilityClause(relaxation, solution, session)
        assertNotNull(clause, "a big-M row with recorded premises keeps the certificate expressible")
        for (lit in clause) {
            assertEquals(false, session.litTruth(lit), "clause must be all-false (clause=${clause.toList()})")
        }

        // Brute validity: no factor-feasible point may satisfy every cited premise — the column
        // seats plus each non-global ray row's recorded live bounds.
        forEachSolution(p) { ints, bools ->
            var inBox = true
            for (k in solution.certCols.indices) {
                val col = solution.certCols[k]
                val varId = relaxation.colVarId[col]
                val lo = relaxation.model.loShift[col]
                val hi = lo + relaxation.model.upper[col]
                val ok = if (relaxation.colIsBool[col]) {
                    val seated = (lo + if (solution.certBoundIsUpper[k]) relaxation.model.upper[col] else 0L) == 1L
                    bools[varId] == seated
                } else if (solution.certBoundIsUpper[k]) {
                    ints[varId] <= hi
                } else {
                    ints[varId] >= lo
                }
                if (!ok) inBox = false
            }
            for (r in solution.certRows) {
                if (relaxation.model.rowGlobal[r]) continue
                val prem = assertNotNull(relaxation.model.rowPremises[r])
                for (k in prem.vars.indices) {
                    val ok = if (prem.isUpper[k]) {
                        ints[prem.vars[k]] <= prem.thresholds[k]
                    } else {
                        ints[prem.vars[k]] >= prem.thresholds[k]
                    }
                    if (!ok) inBox = false
                }
            }
            assertTrue(!inBox, "feasible point ${ints.toList()}/${bools.toList()} inside the premise box")
        }
    }

    /**
     * A locally separated cut is valid only under the node's tightened bounds. Here the Hall-sum cut
     * `x1 + x2 >= 9` (from live domains `[4, 10]`) is the binding row of the LP floor `z >= 1`, and
     * both `x1` and `x2` end up basic with zero reduced cost at *every* optimal basis — so the
     * support cites no bound that justifies the cut. A support-only reason would claim `z >= 1`
     * globally, excluding e.g. `(x1, x2, z) = (0, 1, 0)`. The reason must be withheld.
     */
    @Test
    fun `objective bound reason is withheld when a node-local cut carries dual weight`() {
        val p = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(0, 10), IntDomain(0, 10), IntDomain(0, 20)),
            factors = arrayOf<Factor>(
                AllDifferent(intArrayOf(0, 1), domainMin = 0, domainSize = 11),
                Linear(intArrayOf(1, -1, -1), intArrayOf(2, 0, 1), LinearOp.GE, -8),
            ),
        )
        val session = PropagationSession(p)
        assertTrue(session.pinIntAtLeast(0, 4) !is PropagationResult.Unsat)
        assertTrue(session.pinIntAtLeast(1, 4) !is PropagationResult.Unsat)
        val objective = LinearObjective(intCoefficients = longArrayOf(0L, 0L, 1L))
        val relaxer = CpToLpRelaxation(p, objective, generateCuts = true)
        var relaxation = relaxer.build(session)
        var solution = DualSimplex(relaxation.model).solve()
        assertEquals(LpStatus.OPTIMAL, solution.status)

        val cuts = AllDifferentSeparator().separate(CutContext(p, relaxation, solution, session))
        assertTrue(cuts.isNotEmpty(), "expected a violated Hall-sum cut at the fractional point")
        relaxation = relaxer.build(session, cuts)
        solution = DualSimplex(relaxation.model).solve()
        assertEquals(LpStatus.OPTIMAL, solution.status)
        assertEquals(1L, solution.objectiveLowerBoundCeil() + relaxation.objectiveConstant)

        val reason = LpExplanation.objectiveBoundReason(relaxation, solution, session)
        assertNull(reason, "a floor held up by a node-local cut row admits no bound-atom reason")
    }

    /**
     * End-to-end net: with every LP learning/propagation feature on, branch-and-bound must reach
     * the same verdict and optimum as brute-force enumeration. Random instances mix Linear,
     * live-big-M ReifiedLinear, Clause and AllDifferent over small boxes so the LP-learned clauses
     * (Farkas nogoods, objective-bound reasons, reduced-cost reasons) actually fire during search.
     */
    @Test
    fun `lp learning preserves the optimum on random reified problems`() {
        val rng = Random(20260610)
        var optimal = 0
        var infeasible = 0
        repeat(300) {
            val numInts = rng.nextInt(2, 4)
            val numBools = rng.nextInt(1, 3)
            val hi = rng.nextInt(2, 5)
            val domains = Array(numInts) { IntDomain(0, hi) }
            val factors = ArrayList<Factor>()
            repeat(rng.nextInt(1, 3)) {
                val arity = rng.nextInt(1, numInts + 1)
                val vars = (0 until numInts).shuffled(rng).take(arity).toIntArray()
                val coeffs = IntArray(arity) { rng.nextInt(-3, 4) }
                if (coeffs.all { c -> c == 0 }) return@repeat
                val op = if (rng.nextBoolean()) LinearOp.LE else LinearOp.GE
                factors.add(Linear(coeffs, vars, op, rng.nextInt(-4, 3 * hi)))
            }
            repeat(rng.nextInt(1, 3)) {
                val arity = rng.nextInt(1, numInts + 1)
                val vars = (0 until numInts).shuffled(rng).take(arity).toIntArray()
                val coeffs = IntArray(arity) { rng.nextInt(-2, 3) }
                if (coeffs.all { c -> c == 0 }) return@repeat
                val op = when (rng.nextInt(3)) {
                    0 -> LinearOp.LE
                    1 -> LinearOp.GE
                    else -> LinearOp.EQ
                }
                factors.add(
                    ReifiedLinear(rng.nextInt(numBools), coeffs, vars, op, rng.nextInt(-2, 2 * hi)),
                )
            }
            if (numBools >= 2) {
                val lits = intArrayOf(Lit.make(0, rng.nextBoolean()), Lit.make(1, rng.nextBoolean()))
                factors.add(Clause(lits))
            }
            if (numInts >= 2 && rng.nextBoolean()) {
                factors.add(AllDifferent(intArrayOf(0, 1), domainMin = 0, domainSize = hi + 1))
            }
            val p = Problem(numBools, numInts, domains, factors.toTypedArray())
            val objCoef = LongArray(numInts)
            objCoef[0] = 1L // single ascending objective: minimise var 0
            val objective = LinearObjective(intCoefficients = objCoef)

            var bruteBest = Long.MAX_VALUE
            forEachSolution(p) { ints, _ -> if (ints[0] < bruteBest) bruteBest = ints[0].toLong() }

            val params = BacktrackParams(
                randomSeed = 7L,
                lubyRestartBase = 8L,
                lpBounding = true,
                lpCuts = true,
                lpCutPool = true,
                lpLearn = true,
                lpObjectiveBound = true,
                lpFixpoint = true,
                lpProbe = true,
                lpBoundEvery = 1,
            )
            when (val res = BacktrackSolver(p).minimize(objective, params)) {
                is MinimizeResult.Optimal -> {
                    optimal++
                    assertTrue(bruteBest != Long.MAX_VALUE, "solver Optimal on brute-infeasible instance #$it")
                    assertEquals(
                        bruteBest.toDouble(),
                        res.objective,
                        1e-9,
                        "wrong optimum on instance #$it",
                    )
                }

                is MinimizeResult.Infeasible -> {
                    infeasible++
                    assertEquals(Long.MAX_VALUE, bruteBest, "solver Infeasible on brute-feasible instance #$it")
                }

                else -> error("unexpected non-terminal result $res on instance #$it")
            }
            // The one-flag auto path (the LP-focused portfolio arm's configuration) must agree too.
            val autoParams = BacktrackParams(randomSeed = 7L, lubyRestartBase = 8L, lpAuto = true)
            when (val res = BacktrackSolver(p).minimize(objective, autoParams)) {
                is MinimizeResult.Optimal -> assertEquals(
                    bruteBest.toDouble(),
                    res.objective,
                    1e-9,
                    "lpAuto wrong optimum on instance #$it",
                )

                is MinimizeResult.Infeasible ->
                    assertEquals(Long.MAX_VALUE, bruteBest, "lpAuto Infeasible on brute-feasible instance #$it")

                else -> error("unexpected non-terminal lpAuto result $res on instance #$it")
            }
        }
        assertTrue(optimal > 60, "covered only $optimal optimal instances")
        assertTrue(infeasible > 20, "covered only $infeasible infeasible instances")
    }
}
