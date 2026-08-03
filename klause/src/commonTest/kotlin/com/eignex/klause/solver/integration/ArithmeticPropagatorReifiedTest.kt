package com.eignex.klause.solver.integration

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.backtrack.selector.Vsids
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.factor.arithmetic.ReifiedCardinality
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.arithmetic.ReifiedPseudoBoolean
import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.model.PbOp
import com.eignex.klause.propagation.AtomKind
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.pinBoolAsDecision
import com.eignex.klause.solver.Assumptions
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ArithmeticPropagatorReifiedTest {

    // --- ReifiedAtomCycleTest ---

    @Test
    fun `backtrack enumeration over reified equalities matches brute force`() {
        // Three vars over {0,1,2}; for each var v and value k a channel aux (aux ↔ v == k).
        // Clauses tie channels across variables so propagation forces equalities both ways,
        // exercising the cyclic atom resolution. Enumerate full assignments and compare to brute.
        val n = 3
        val dvals = intArrayOf(0, 1, 2)
        val numBool = n * dvals.size
        fun chan(v: Int, kIdx: Int) = v * dvals.size + kIdx
        val factors = ArrayList<Factor>()
        for (v in 0 until n) {
            for (kIdx in dvals.indices) {
                factors.add(
                    ReifiedLinear(
                        auxBoolVar = chan(v, kIdx),
                        coeffs = intArrayOf(1),
                        vars = intArrayOf(v),
                        op = LinearOp.EQ,
                        bound = dvals[kIdx],
                    ),
                )
            }
        }
        // Linking clauses: (v0==0) → (v1==1), (v1==1) → (v2==2), and not all three equal to 0.
        factors.add(Clause(intArrayOf(Lit.make(chan(0, 0), false), Lit.make(chan(1, 1), true))))
        factors.add(Clause(intArrayOf(Lit.make(chan(1, 1), false), Lit.make(chan(2, 2), true))))
        factors.add(
            Clause(intArrayOf(Lit.make(chan(0, 0), false), Lit.make(chan(1, 0), false), Lit.make(chan(2, 0), false))),
        )

        val p = Problem(
            numBoolVars = numBool,
            numIntVars = n,
            intDomains = Array(n) { IntDomain(0, 2) },
            factors = factors.toTypedArray(),
        )

        val brute = HashSet<List<Int>>()
        fun ok(a: IntArray): Boolean {
            if (a[0] == 0 && a[1] != 1) return false
            if (a[1] == 1 && a[2] != 2) return false
            if (a[0] == 0 && a[1] == 0 && a[2] == 0) return false
            return true
        }
        for (x0 in 0..2) {
            for (x1 in 0..2) {
                for (x2 in 0..2) {
                    val a = intArrayOf(x0, x1, x2)
                    if (ok(a)) brute.add(a.toList())
                }
            }
        }

        val params = BacktrackParams(randomSeed = 1L, variableSelector = Vsids(), maxLearnedClauses = 1_000)
        val found = BacktrackSolver(
            p.bake(),
        ).enumerate(params).take(100_000).map { it.ints.map { v -> v.toInt() } }.toHashSet()
        assertEquals(brute, found, "backtrack enumeration must equal the brute-force feasible set")
    }

    // --- ReifiedCardinalityPropTest ---

    @Test
    fun `aux false with up-only escape forces all unassigned true`() {
        // ReifiedCardinality(aux, lits, min=1, max=2). 4 literals over distinct bool
        // vars. With aux pinned false and v0=true forced, trueCount = 1 = min. The
        // "down" branch (count < 1) is infeasible (we'd need 0 trues but already have 1);
        // only "count > max = 2" is feasible. Required additional trues: max-trueCount+1
        // = 2 = unassigned. So every unassigned literal must be forced true.
        val problem = Problem(
            numBoolVars = 4,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(
                Clause(intArrayOf(Lit.make(0, true))), // force v0 = true
                ReifiedCardinality(
                    auxBoolVar = 3,
                    literals = intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true)),
                    min = 1,
                    max = 2,
                ),
            ),
        )
        val session = PropagationSession(problem)
        assertIs<PropagationResult.Implied>(session.pinBool(3, false))
        // v1 and v2 forced true (so count = 3 > max = 2).
        assertEquals(true, session.boolValue(1), "v1 should be forced true")
        assertEquals(true, session.boolValue(2), "v2 should be forced true")
    }

    @Test
    fun `aux false with down-only escape forces all unassigned false`() {
        // Force "down-only" escape: trueCount = min - 1 (so cap == 0, must avoid any
        // more trues), and trueCount + unassigned ≤ max (up-branch infeasible). Easiest
        // satisfiable shape:
        //   min=1, max=2 (so up-branch needs count > 2, i.e., ≥ 3 trues), 2 literals,
        //   trueCount=0 (so cap = min - 0 - 1 = 0). Up-branch needs 3 trues > 2
        //   unassigned → infeasible. cap == 0 → both literals forced false.
        val problem = Problem(
            numBoolVars = 3,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(
                ReifiedCardinality(
                    auxBoolVar = 2,
                    literals = intArrayOf(Lit.make(0, true), Lit.make(1, true)),
                    min = 1,
                    max = 2,
                ),
            ),
        )
        val session = PropagationSession(problem)
        assertIs<PropagationResult.Implied>(session.pinBool(2, false))
        // Expect v0 and v1 forced false.
        assertEquals(false, session.boolValue(0), "v0 should be forced false")
        assertEquals(false, session.boolValue(1), "v1 should be forced false")
    }

    @Test
    fun `aux false conflicts with body-must-hold via definitelyIn`() {
        // Body must hold under current pins (count ∈ [min, max] forced) AND aux is
        // pinned false. ReifiedCardinality's `definitelyIn` check pins aux=true, which
        // conflicts with the prior aux=false pin → Unsat surfaced via revertAndUnsat.
        val problem = Problem(
            numBoolVars = 3,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(
                Clause(intArrayOf(Lit.make(0, true))),
                Clause(intArrayOf(Lit.make(1, true))),
                ReifiedCardinality(
                    auxBoolVar = 2,
                    literals = intArrayOf(Lit.make(0, true), Lit.make(1, true)),
                    min = 1,
                    max = 2,
                ),
            ),
        )
        val session = PropagationSession(problem)
        val r = session.pinBool(2, false)
        assertIs<PropagationResult.Unsat>(r)
    }

    @Test
    fun `aux true unchanged - boundary forcing still fires`() {
        // Sanity: the aux=true case still pins all unassigned to !pos when trueCount == max.
        val problem = Problem(
            numBoolVars = 4,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(
                Clause(intArrayOf(Lit.make(0, true))), // force v0 = true
                ReifiedCardinality(
                    auxBoolVar = 3,
                    literals = intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true)),
                    min = 0,
                    max = 1,
                ),
            ),
        )
        val session = PropagationSession(problem)
        val r = session.pinBool(3, true)
        assertIs<PropagationResult.Implied>(r)
        // After: trueCount = 1 = max. Remaining unassigned (v1, v2) must be false.
        assertEquals(false, session.boolValue(1), "v1 should be forced false")
        assertEquals(false, session.boolValue(2), "v2 should be forced false")
    }

    // --- ReifiedEqNegationTest ---

    @Test
    fun `ReifiedLinear aux=false on EQ tightens single-free-term boundary`() {
        // ReifiedLinear: aux ↔ (x + y = 5). aux=false → x + y ≠ 5.
        // Pin y=2, the body becomes "x ≠ 3". If x's domain has 3 at an endpoint, it gets shaved.
        val p = Problem(
            numBoolVars = 1,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(3, 6), IntDomain(0, 5)),
            factors = arrayOf<Factor>(
                ReifiedLinear(
                    auxBoolVar = 0,
                    coeffs = intArrayOf(1, 1),
                    vars = intArrayOf(0, 1),
                    op = LinearOp.EQ,
                    bound = 5,
                ),
            ),
        )
        val session = PropagationSession(p)
        assertIs<PropagationResult.Implied>(
            session.seed(Assumptions(bools = mapOf(0 to false), ints = mapOf(1 to 2))),
        )
        // x=3 forbidden at the min boundary of [3..6] → x.min tightens to 4.
        val dxAfter = session.intDomain(0)
        assertEquals(4, dxAfter.min, "x=3 should be shaved off the min boundary; got $dxAfter")
        assertEquals(6, dxAfter.max, "x.max untouched; got $dxAfter")
    }

    @Test
    fun `ReifiedLinear aux=false on EQ detects Unsat when body must equal bound`() {
        // x + y = 5, x in {3..3}, y in {2..2}. Both pinned → x+y=5 forced.
        // aux=false → x+y ≠ 5 must hold → Unsat.
        val p = Problem(
            numBoolVars = 1,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(3, 3), IntDomain(2, 2)),
            factors = arrayOf<Factor>(
                ReifiedLinear(
                    auxBoolVar = 0,
                    coeffs = intArrayOf(1, 1),
                    vars = intArrayOf(0, 1),
                    op = LinearOp.EQ,
                    bound = 5,
                ),
            ),
        )
        // Without aux pinning: bake-time propagation forces aux=true (since sum is exactly 5).
        val bake = p.propagate(Assumptions.None)
        val impl = assertIs<PropagationResult.Implied>(bake)
        assertEquals(true, impl.bools[0])
        // Now pin aux=false explicitly: should be Unsat.
        val r = p.propagate(Assumptions(bools = mapOf(0 to false)))
        assertIs<PropagationResult.Unsat>(r)
    }

    @Test
    fun `ReifiedPseudoBoolean aux=false on EQ rejects forced-sum-equals-bound`() {
        // sum(weights·lits) with weights {2, 3}, lits {0, 1}, both pinned true → sum = 5.
        // aux ↔ (sum = 5). With both lits true, sum=5 forced → aux must be true.
        // Pinning aux=false should yield Unsat.
        val p = Problem(
            numBoolVars = 3,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(
                ReifiedPseudoBoolean(
                    auxBoolVar = 2,
                    weights = longArrayOf(2, 3),
                    literals = intArrayOf(Lit.make(0, true), Lit.make(1, true)),
                    op = PbOp.EQ,
                    bound = 5L,
                ),
            ),
        )
        val r = p.propagate(Assumptions(bools = mapOf(0 to true, 1 to true, 2 to false)))
        assertIs<PropagationResult.Unsat>(r)
    }

    @Test
    fun `ReifiedPseudoBoolean aux=false on EQ allows feasible non-equal sum`() {
        // weights {2, 3}, lits {0, 1}, aux=false → sum ≠ 5.
        // Pin lit0=true, lit1=false → sum = 2. aux=false is consistent.
        val p = Problem(
            numBoolVars = 3,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(
                ReifiedPseudoBoolean(
                    auxBoolVar = 2,
                    weights = longArrayOf(2, 3),
                    literals = intArrayOf(Lit.make(0, true), Lit.make(1, true)),
                    op = PbOp.EQ,
                    bound = 5L,
                ),
            ),
        )
        val r = p.propagate(Assumptions(bools = mapOf(0 to true, 1 to false, 2 to false)))
        assertIs<PropagationResult.Implied>(r)
    }

    @Test
    fun `ReifiedPseudoBoolean aux=false on EQ prunes single-free literal at unique-sum boundary`() {
        // weights {2, 3}, lits {0, 1}. With lit0=true (contribution 2) and aux=false,
        // we need 2 + 3*lit1 ≠ 5. So 3*lit1 ≠ 3 → lit1 ≠ true → lit1 = false.
        val p = Problem(
            numBoolVars = 3,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(
                ReifiedPseudoBoolean(
                    auxBoolVar = 2,
                    weights = longArrayOf(2, 3),
                    literals = intArrayOf(Lit.make(0, true), Lit.make(1, true)),
                    op = PbOp.EQ,
                    bound = 5L,
                ),
            ),
        )
        val r = p.propagate(Assumptions(bools = mapOf(0 to true, 2 to false)))
        val impl = assertIs<PropagationResult.Implied>(r)
        assertEquals(false, impl.bools[1], "lit1=true would force sum=5; must be false")
    }

    // --- ReifiedHoleChainTest ---

    @Test
    fun `proven optimum over equality channels with carved holes matches brute force`() {
        val lo = 1
        val hi = 5
        val span = hi - lo + 1
        for (seed in 0 until 12) {
            val rnd = Random(seed)
            val numDrivers = 2

            // bool layout: drivers d0,d1 then channels cx_v, cy_v.
            fun cx(v: Int) = numDrivers + (v - lo)
            fun cy(v: Int) = numDrivers + span + (v - lo)
            val numBool = numDrivers + 2 * span
            val factors = ArrayList<Factor>()
            for (v in lo..hi) {
                factors.add(
                    ReifiedLinear(
                        auxBoolVar = cx(v),
                        coeffs = intArrayOf(1),
                        vars = intArrayOf(0),
                        op = LinearOp.EQ,
                        bound = v,
                    ),
                )
                factors.add(
                    ReifiedLinear(
                        auxBoolVar = cy(v),
                        coeffs = intArrayOf(1),
                        vars = intArrayOf(1),
                        op = LinearOp.EQ,
                        bound = v,
                    ),
                )
            }
            val clauses = ArrayList<IntArray>()
            repeat(10) {
                val lits = IntArray(3) {
                    val b = rnd.nextInt(numBool)
                    Lit.make(b, rnd.nextBoolean())
                }
                clauses.add(lits)
                factors.add(Clause(lits))
            }
            val p = Problem(
                numBoolVars = numBool,
                numIntVars = 2,
                intDomains = arrayOf(IntDomain(lo.toLong(), hi.toLong()), IntDomain(lo.toLong(), hi.toLong())),
                factors = factors.toTypedArray(),
            )

            // Brute-force optimum of x + 2y over the feasible set (channels are functions of
            // x and y; the two drivers are free).
            var bruteBest: Int? = null
            for (x in lo..hi) {
                for (y in lo..hi) {
                    for (mask in 0 until (1 shl numDrivers)) {
                        val bools = BooleanArray(numBool)
                        for (d in 0 until numDrivers) bools[d] = (mask shr d) and 1 == 1
                        for (v in lo..hi) {
                            bools[cx(v)] = x == v
                            bools[cy(v)] = y == v
                        }
                        val ok = clauses.all { cl ->
                            cl.any { lit -> bools[Lit.variable(lit)] == Lit.isPositive(lit) }
                        }
                        if (ok) {
                            val obj = x + 2 * y
                            val best = bruteBest
                            if (best == null || obj < best) bruteBest = obj
                        }
                    }
                }
            }

            // Defensive decision cap: a capped run returns BestFound and makes no optimality
            // claim, which the check below then skips. The guarded property is the soundness
            // one: whenever the engine *claims* a proven optimum or infeasibility, brute force
            // must agree.
            val params = BacktrackParams(
                randomSeed = seed.toLong(),
                variableSelector = Vsids(),
                maxLearnedClauses = 1_000,
                maxDecisions = 200_000,
            )
            val objective = LinearObjective(intCoefficients = longArrayOf(1L, 2L))
            val result = BacktrackSolver(p.bake()).minimize(objective, params)
            val best = bruteBest
            when (result) {
                is MinimizeResult.Optimal ->
                    assertEquals(
                        best?.toDouble(),
                        result.objective,
                        "seed $seed: proven optimum must match brute force",
                    )

                is MinimizeResult.Infeasible ->
                    assertEquals(null, best, "seed $seed: claimed infeasible but brute found $best")

                else -> {} // budget-capped: no optimality claim to check
            }
        }
    }

    // --- ReifiedLinearConflictReasonTest ---

    @Test
    fun `body conflict reason is a sound witness containing the indicator literal`() {
        // aux ↔ (v0 ≥ 5). Decide aux=true at level 1, then squeeze v0 ≤ 4 → the body must hold
        // (GE 5) but cannot, so body propagation wipes v0's domain and propagate returns false.
        val factor = ReifiedLinear(
            auxBoolVar = 0,
            coeffs = intArrayOf(1),
            vars = intArrayOf(0),
            op = LinearOp.GE,
            bound = 5,
        )
        val problem = Problem(
            numBoolVars = 1,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(0, 9)),
            factors = arrayOf<Factor>(factor),
        )
        val state = PropagationState(problem, Assumptions.None)
        state.undoLogging = true
        state.currentLevel = 1
        state.pinBoolAsDecision(0, true)
        assertTrue(state.tightenIntMax(0, 4), "squeeze v0 ≤ 4")
        assertFalse(
            problem.propagators[0].propagate(state, 0),
            "body GE 5 must be infeasible under v0 ≤ 4 with aux true",
        )

        val reason = problem.propagators[0].conflictReason(state, 0)
        assertTrue(reason != null && reason.isNotEmpty(), "must yield a non-empty clause-form reason")
        for (lit in reason) {
            assertTrue(state.litFalse(lit), "every reason literal must be false at conflict time, lit=$lit")
        }
        assertTrue(
            Lit.make(0, false) in reason.toSet(),
            "reason must thread the indicator literal ¬[aux=true], got ${reason.toList()}",
        )
    }

    private fun enumerateBoolInt(problem: Problem, seed: Long): HashSet<List<Int>> = BacktrackSolver(problem.bake())
        .enumerate(BacktrackParams(randomSeed = seed, variableSelector = Vsids()))
        .take(100_000)
        .map { it.bools.map { b -> if (b) 1 else 0 } + it.ints.map { v -> v.toInt() } }
        .toHashSet()

    @Test
    fun `enumerate matches brute force for reified LE`() {
        // aux ↔ (v0 + v1 ≤ 2), both in [0, 3]. aux is free, so enumeration spans both polarities;
        // each conflicting branch learns off the new indicator-aware nogood.
        for (seed in 1L..5L) {
            val problem = Problem(
                numBoolVars = 1,
                numIntVars = 2,
                intDomains = arrayOf(IntDomain(0, 3), IntDomain(0, 3)),
                factors = arrayOf<Factor>(
                    ReifiedLinear(
                        auxBoolVar = 0,
                        coeffs = intArrayOf(1, 1),
                        vars = intArrayOf(0, 1),
                        op = LinearOp.LE,
                        bound = 2,
                    ),
                ),
            )
            val brute = HashSet<List<Int>>()
            for (a in 0..1) {
                for (v0 in 0..3) {
                    for (v1 in 0..3) {
                        if ((a == 1) == (v0 + v1 <= 2)) brute.add(listOf(a, v0, v1))
                    }
                }
            }
            assertEquals(brute, enumerateBoolInt(problem, seed), "seed=$seed: reified LE must match brute force")
        }
    }

    @Test
    fun `enumerate matches brute force for reified EQ`() {
        // aux ↔ (v0 + v1 = 3), both in [0, 3] — a tight equality that drives many body conflicts.
        for (seed in 1L..5L) {
            val problem = Problem(
                numBoolVars = 1,
                numIntVars = 2,
                intDomains = arrayOf(IntDomain(0, 3), IntDomain(0, 3)),
                factors = arrayOf<Factor>(
                    ReifiedLinear(
                        auxBoolVar = 0,
                        coeffs = intArrayOf(1, 1),
                        vars = intArrayOf(0, 1),
                        op = LinearOp.EQ,
                        bound = 3,
                    ),
                ),
            )
            val brute = HashSet<List<Int>>()
            for (a in 0..1) {
                for (v0 in 0..3) {
                    for (v1 in 0..3) {
                        if ((a == 1) == (v0 + v1 == 3)) brute.add(listOf(a, v0, v1))
                    }
                }
            }
            assertEquals(brute, enumerateBoolInt(problem, seed), "seed=$seed: reified EQ must match brute force")
        }
    }

    @Test
    fun `enumerate matches brute force for single-term EQ with unreachable target`() {
        // aux ↔ (2·v0 = 3), v0 in [0, 3]. 3 is not divisible by 2, so the body can never hold —
        // aux is forced false via the eqTargetUnreachable path (hole-aware reason).
        for (seed in 1L..5L) {
            val problem = Problem(
                numBoolVars = 1,
                numIntVars = 1,
                intDomains = arrayOf(IntDomain(0, 3)),
                factors = arrayOf<Factor>(
                    ReifiedLinear(
                        auxBoolVar = 0,
                        coeffs = intArrayOf(2),
                        vars = intArrayOf(0),
                        op = LinearOp.EQ,
                        bound = 3,
                    ),
                ),
            )
            val brute = HashSet<List<Int>>()
            for (v0 in 0..3) brute.add(listOf(0, v0)) // aux always false
            assertEquals(brute, enumerateBoolInt(problem, seed), "seed=$seed: unreachable EQ target forces aux false")
        }
    }

    @Test
    fun `enumerate should cover all projected models for int-bit channels with reified eq`() {
        // x in [0,3], bits c0/c1 in {0,1}, booleans b0/b1 with
        // b0 <-> (c0 == 1), b1 <-> (c1 == 1), and x - c0 - 2*c1 == 0.
        val nv = 3
        for (seed in 1L..5L) {
            var nextBool = 0
            var nextInt = nv
            val doms = ArrayList<IntDomain>().apply { repeat(nv) { add(IntDomain(0, 3)) } }
            val factors = ArrayList<Factor>()
            repeat(nv) { x ->
                val c0 = nextInt++
                val c1 = nextInt++
                doms.add(IntDomain(0, 1))
                doms.add(IntDomain(0, 1))
                val b0 = nextBool++
                val b1 = nextBool++
                factors.add(ReifiedLinear(b0, intArrayOf(1), intArrayOf(c0), LinearOp.EQ, 1))
                factors.add(ReifiedLinear(b1, intArrayOf(1), intArrayOf(c1), LinearOp.EQ, 1))
                factors.add(Linear(intArrayOf(1, -1, -2), intArrayOf(x, c0, c1), LinearOp.EQ, 0))
            }
            val problem = Problem(
                numBoolVars = nextBool,
                numIntVars = nextInt,
                intDomains = doms.toTypedArray(),
                factors = factors.toTypedArray(),
            )
            val projected = BacktrackSolver(problem.bake()).enumerate(
                BacktrackParams(
                    maxDecisions = 50_000_000L,
                    randomSeed = seed,
                    variableSelector = Vsids(),
                ),
            ).map { sample -> (0 until nv).map { sample.ints[it].toInt() } }.toHashSet()
            val expected = HashSet<List<Int>>()
            for (x0 in 0..3) for (x1 in 0..3) for (x2 in 0..3) expected.add(listOf(x0, x1, x2))
            assertEquals(expected, projected, "seed=$seed: enumerate must cover all 64 projected models")
        }
    }

    // --- ReifiedLinearHoleTest ---

    @Test
    fun `eq reifications over a hole domain enumerate exactly the brute set`() {
        // domain {0, 2, 3}: value 1 is an interior hole.
        var dom = IntDomain(0, 3)
        dom = dom.excludeValue(1)
        val values = intArrayOf(0, 1, 2, 3)
        val factors = ArrayList<Factor>()
        for (k in values.indices) {
            factors.add(
                ReifiedLinear(
                    auxBoolVar = k,
                    coeffs = intArrayOf(1),
                    vars = intArrayOf(0),
                    op = LinearOp.EQ,
                    bound = values[k],
                ),
            )
        }
        val p = Problem(
            numBoolVars = values.size,
            numIntVars = 1,
            intDomains = arrayOf(dom),
            factors = factors.toTypedArray(),
        )
        val brute = HashSet<Pair<List<Boolean>, Int>>()
        for (x in intArrayOf(0, 2, 3)) {
            brute.add(values.map { it == x } to x)
        }
        val params = BacktrackParams(randomSeed = 1L, variableSelector = Vsids(), maxLearnedClauses = 1_000)
        val found = BacktrackSolver(p.bake()).enumerate(params).take(100_000)
            .map { it.bools.toList() to it.ints[0].toInt() }.toHashSet()
        assertEquals(brute, found, "reified eq over a hole domain must match brute enumeration")
    }

    @Test
    fun `accumulated forced-false hole reifications stay satisfiable`() {
        val nVars = 6
        val perVar = 31 // values 1..31; 1..30 are holes, 31 is live
        val doms = Array(nVars) {
            var d = IntDomain(0, 34)
            for (v in 1..30) d = d.excludeValue(v.toLong())
            d
        }
        val factors = ArrayList<Factor>()
        for (i in 0 until nVars) {
            val chans = IntArray(perVar) { i * perVar + it }
            for (k in 0 until perVar) {
                factors.add(
                    ReifiedLinear(
                        auxBoolVar = chans[k],
                        coeffs = intArrayOf(1),
                        vars = intArrayOf(i),
                        op = LinearOp.EQ,
                        bound = 1 + k,
                    ),
                )
            }
            factors.add(Cardinality(IntArray(perVar) { Lit.make(chans[it], true) }, min = 1, max = perVar))
        }
        val p = Problem(nVars * perVar, nVars, doms, factors.toTypedArray())
        assertIs<SolveResult.Sat>(BacktrackSolver(p.bake()).solve(BacktrackParams(randomSeed = 1L)))
    }

    @Test
    fun `eq reification conflict reason over a search-time hole is sound`() {
        val problem = Problem(
            numBoolVars = 1,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(0, 3)),
            factors = arrayOf(),
        )
        val state = PropagationState(problem, Assumptions.None)
        state.undoLogging = true
        // aux = true at level 1, then carve 2 out of x at level 2 — a search-time interior
        // hole with x's bounds still [0, 3]: the eqTargetUnreachable conflict state.
        state.currentLevel = 1
        check(state.pinBool(0, true)) { "pin aux failed" }
        state.currentLevel = 2
        check(state.excludeIntValue(0, 2, null)) { "carve hole failed" }
        val reif = ReifiedLinear(
            auxBoolVar = 0,
            coeffs = intArrayOf(1),
            vars = intArrayOf(0),
            op = LinearOp.EQ,
            bound = 2,
        )
        val reason = reif.asPropagator().conflictReason(state, 0) ?: error("expected a conflict reason")
        // The feasible witness aux = true, x = 2 must satisfy the reason clause (≥ 1 literal true).
        val nbv = problem.numBoolVars
        fun satUnderWitness(lit: Int): Boolean {
            val v = Lit.variable(lit)
            val holds = if (v < nbv) {
                true // aux = true
            } else {
                val a = v - nbv
                when (state.atoms.kind[a]) {
                    AtomKind.GE -> 2 >= state.atoms.threshold[a]
                    AtomKind.LE -> 2 <= state.atoms.threshold[a]
                    AtomKind.EQ -> 2L == state.atoms.threshold[a]
                }
            }
            return holds == Lit.isPositive(lit)
        }
        assertTrue(
            reason.any { satUnderWitness(it) },
            "conflict reason must be satisfied by the feasible x=2 assignment, not a bare unit: ${reason.toList()}",
        )
    }

    @Test
    fun `all-hole reifications are correctly unsat`() {
        var dom = IntDomain(0, 10)
        for (v in 3..7) dom = dom.excludeValue(v.toLong())
        val values = intArrayOf(3, 4, 5, 6, 7) // every candidate is a hole
        val factors = ArrayList<Factor>()
        for (k in values.indices) {
            factors.add(
                ReifiedLinear(
                    auxBoolVar = k,
                    coeffs = intArrayOf(1),
                    vars = intArrayOf(0),
                    op = LinearOp.EQ,
                    bound = values[k],
                ),
            )
        }
        factors.add(Cardinality(IntArray(values.size) { Lit.make(it, true) }, min = 1, max = values.size))
        val p = Problem(values.size, 1, arrayOf(dom), factors.toTypedArray())
        assertIs<SolveResult.Unsat>(BacktrackSolver(p.bake()).solve(BacktrackParams(randomSeed = 1L)))
    }
}
