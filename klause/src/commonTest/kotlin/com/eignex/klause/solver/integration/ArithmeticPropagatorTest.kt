package com.eignex.klause.solver.integration

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.backtrack.selector.Vsids
import com.eignex.klause.factor.arithmetic.ArrayMinMax
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.Product
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.localsearch.Invariant
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.propagation.IntEvent
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.Propagator
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.MixedVars
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.StructuralKey
import com.eignex.klause.solver.VarList
import com.eignex.klause.solver.VarRemap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ArithmeticPropagatorTest {

    // --- ArrayMinMaxTest ---

    @Test
    fun `array minmax returns the extreme element`() {
        // r = max(v0, v1, v2) or min(v0, v1, v2). All ∈ [0..3]. Pin v0=3, v1=1, v2=2:
        // max must be 3, min must be 1.
        for ((max, expected) in listOf(true to 3L, false to 1L)) {
            val problem = Problem(
                numBoolVars = 0,
                numIntVars = 4,
                intDomains = arrayOf(IntDomain(3, 3), IntDomain(1, 1), IntDomain(2, 2), IntDomain(0, 5)),
                factors = arrayOf<Factor>(ArrayMinMax(result = 3, xs = intArrayOf(0, 1, 2), max = max)),
            )
            val r = BacktrackSolver(problem.bake()).solve(BacktrackParams(randomSeed = 0L))
            val sat = assertIs<SolveResult.Sat>(r)
            assertEquals(expected, sat.assignment.ints[3], "max=$max")
        }
    }

    @Test
    fun `array maximum propagation tightens result against xs domains`() {
        // result ∈ [0..10], xs[i] ∈ [0..5]. propagate should tighten result.max to 5.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = arrayOf(IntDomain(0, 5), IntDomain(0, 5), IntDomain(0, 5), IntDomain(0, 10)),
            factors = arrayOf<Factor>(ArrayMinMax(result = 3, xs = intArrayOf(0, 1, 2), max = true)),
        )
        val r = BacktrackSolver(problem.bake()).solve(BacktrackParams(randomSeed = 0L))
        val sat = assertIs<SolveResult.Sat>(r)
        // Result must equal max(xs); since xs.max ≤ 5, so does result.
        val resVal = sat.assignment.ints[3]
        val xsVals = listOf(sat.assignment.ints[0], sat.assignment.ints[1], sat.assignment.ints[2])
        assertEquals(xsVals.max(), resVal)
    }

    // --- LinearBoundsEventTest ---

    /** When [src] is fixed, carve its value out of [dst] — punches interior holes into the linear's
     *  variables mid-search. Plain occurrence wakeup (no event subscription), so it always fires. */
    private class ExcludeOnFix(val src: Int, val dst: Int) :
        Factor,
        Propagator {
        override val variables: VarList = MixedVars(spanInts = intArrayOf(src, dst), boolVars = IntArray(0))

        override fun propagate(state: PropagationState, factorId: Int): Boolean {
            val d = state.intDomains[src]
            return if (d.min == d.max) state.excludeIntValue(dst, d.min) else true
        }

        override fun remap(mapping: VarRemap): Factor = ExcludeOnFix(mapping.int(src), mapping.int(dst))

        override fun structuralKey(): StructuralKey = error("test double has no structural key")

        override fun conflictReason(state: PropagationState, factorId: Int): IntArray? = null
        override fun asPropagator(): Propagator = this
        override fun asInvariant(): Invariant = object : Invariant {}
    }

    @Test
    fun `linear subscribes to only bound events on every term`() {
        val lin = Linear(intArrayOf(1, 2, -1), intArrayOf(0, 1, 2), LinearOp.LE, 5)
        val watches = lin.asPropagator().initialIntEventWatches!!
        val pairs = watches.map { IntEvent.intVarOf(it) to IntEvent.kindOf(it) }.toSet()
        assertEquals(
            setOf(
                0 to IntEvent.LB_RAISED,
                0 to IntEvent.UB_LOWERED,
                1 to IntEvent.LB_RAISED,
                1 to IntEvent.UB_LOWERED,
                2 to IntEvent.LB_RAISED,
                2 to IntEvent.UB_LOWERED,
            ),
            pairs,
        )
        assertFalse(
            watches.any { IntEvent.kindOf(it) == IntEvent.VALUE_REMOVED || IntEvent.kindOf(it) == IntEvent.FIXED },
            "linear bound propagation reads only min/max, so it must not subscribe to interior/fixed events",
        )
    }

    @Test
    fun `linear is dropped from occurrence wakeup on its vars`() {
        val lin = Linear(intArrayOf(1, 1, 1), intArrayOf(0, 1, 2), LinearOp.EQ, 6)
        val problem = Problem(0, 3, Array(3) { IntDomain(0, 4) }, listOf(lin))
        for (v in 0..2) {
            assertTrue(problem.intOccurrences[v].contains(0), "factor still mentions var $v")
            assertFalse(
                problem.nonIntEventWatcherIntOccurrences[v].contains(0),
                "subscribed linear must be off the occurrence-wakeup list for var $v",
            )
        }
    }

    private fun enumerate(problem: BakedProblem, seed: Long): HashSet<List<Int>> =
        BacktrackSolver(problem).enumerate(BacktrackParams(randomSeed = seed, variableSelector = Vsids()))
            .take(100_000).map { it.ints.map { v -> v.toInt() } }.toHashSet()

    @Test
    fun `linear with interior holes punched mid-search enumerates exactly brute force`() {
        // For each op: a linear over x0,x1,x2 (0..4) plus a co-constraint that carves x3's fixed value
        // out of x0 and x1 — punching interior holes the linear is not woken for. Enumerated set must
        // equal brute force computed directly from the relation, proving the skipped wakes are sound.
        val hi = 4
        val cases = listOf(
            Triple(LinearOp.LE, 5) { a: Int, b: Int, c: Int -> a + b + c <= 5 },
            Triple(LinearOp.EQ, 6) { a: Int, b: Int, c: Int -> a + b + c == 6 },
            Triple(LinearOp.GE, 9) { a: Int, b: Int, c: Int -> a + b + c >= 9 },
            Triple(LinearOp.NE, 6) { a: Int, b: Int, c: Int -> a + b + c != 6 },
        )
        for ((op, bound, rel) in cases) {
            val factors = listOf<Factor>(
                Linear(intArrayOf(1, 1, 1), intArrayOf(0, 1, 2), op, bound),
                ExcludeOnFix(src = 3, dst = 0),
                ExcludeOnFix(src = 3, dst = 1),
            )
            val problem = Problem(0, 4, Array(4) { IntDomain(0, hi.toLong()) }, factors).bake()
            val brute = HashSet<List<Int>>()
            val base = hi + 1
            for (m in 0 until base * base * base * base) {
                val a = m % base
                val b = (m / base) % base
                val c = (m / (base * base)) % base
                val d = m / (base * base * base)
                if (rel(a, b, c) && a != d && b != d) brute.add(listOf(a, b, c, d))
            }
            for (seed in 1L..4L) {
                assertEquals(
                    brute,
                    enumerate(problem, seed),
                    "op=$op seed=$seed: linear + interior holes must match brute",
                )
            }
        }
    }

    // --- LinearWeakestBoundTest ---

    private class Con(val coeffs: IntArray, val op: LinearOp, val bound: Int)

    private fun satisfies(con: Con, vals: IntArray, varsOf: IntArray): Boolean {
        var s = 0
        for (i in varsOf.indices) s += con.coeffs[i] * vals[varsOf[i]]
        return when (con.op) {
            LinearOp.LE -> s <= con.bound
            LinearOp.GE -> s >= con.bound
            LinearOp.EQ -> s == con.bound
            LinearOp.NE -> s != con.bound
        }
    }

    @Test
    fun `backtrack learning enumerates exactly the brute-force solution set`() {
        // Each instance: n vars over a shared [lo,hi], plus a list of linear constraints over
        // all n vars (coeffs parallel to var ids 0..n-1).
        data class Inst(val n: Int, val lo: Int, val hi: Int, val cons: List<Con>)
        val instances = listOf(
            Inst(3, 0, 3, listOf(Con(intArrayOf(2, 1, 1), LinearOp.LE, 5))),
            Inst(3, 0, 3, listOf(Con(intArrayOf(1, 1, 1), LinearOp.GE, 5))),
            Inst(3, 0, 3, listOf(Con(intArrayOf(1, 1, 2), LinearOp.EQ, 6))),
            Inst(3, 0, 2, listOf(Con(intArrayOf(1, -1, 1), LinearOp.LE, 1))),
            Inst(3, 0, 3, listOf(Con(intArrayOf(1, 1, 1), LinearOp.LE, 4), Con(intArrayOf(1, 1, 1), LinearOp.GE, 2))),
            Inst(
                4,
                0,
                2,
                listOf(Con(intArrayOf(2, 1, 1, 0), LinearOp.LE, 4), Con(intArrayOf(0, 1, 2, 1), LinearOp.GE, 3)),
            ),
            Inst(3, 0, 3, listOf(Con(intArrayOf(1, -1, 2), LinearOp.EQ, 3))),
            // Larger coefficients → bigger per-tighten relaxation room (rounding remainder up to
            // |c|-1); stresses the per-tighten weakest-bound relaxation specifically.
            Inst(3, 0, 4, listOf(Con(intArrayOf(3, 2, 1), LinearOp.LE, 9))),
            Inst(3, 0, 4, listOf(Con(intArrayOf(3, 2, 1), LinearOp.GE, 8))),
            Inst(
                4,
                0,
                3,
                listOf(Con(intArrayOf(2, 3, 1, 2), LinearOp.LE, 10), Con(intArrayOf(1, 1, 1, 1), LinearOp.GE, 3)),
            ),
            // Deep tighten chain that then conflicts (exercises stored per-tighten antecedents
            // being resolved through during conflict analysis).
            // odd RHS, even coeffs → tightenings + UNSAT
            Inst(4, 0, 3, listOf(Con(intArrayOf(2, 2, 2, 2), LinearOp.EQ, 9))),
            Inst(
                4,
                0,
                5,
                listOf(Con(intArrayOf(4, -2, 3, -1), LinearOp.LE, 6), Con(intArrayOf(1, 1, 1, 1), LinearOp.GE, 4)),
            ),
        )
        for ((idx, inst) in instances.withIndex()) {
            val n = inst.n
            val varsOf = IntArray(n) { it }
            val brute = HashSet<List<Int>>()
            val acc = IntArray(n)
            fun rec(p: Int) {
                if (p == n) {
                    if (inst.cons.all { satisfies(it, acc, varsOf) }) brute.add(acc.toList())
                    return
                }
                for (v in inst.lo..inst.hi) {
                    acc[p] = v
                    rec(p + 1)
                }
            }
            rec(0)

            val factors: Array<Factor> = inst.cons
                .map { Linear(coeffs = it.coeffs, vars = varsOf, op = it.op, bound = it.bound) as Factor }
                .toTypedArray()
            val problem = Problem(
                numBoolVars = 0,
                numIntVars = n,
                intDomains = Array(n) { IntDomain(inst.lo.toLong(), inst.hi.toLong()) },
                factors = factors,
            )
            val params = BacktrackParams(randomSeed = 1L, variableSelector = Vsids(), maxLearnedClauses = 1_000)
            val found = BacktrackSolver(problem.bake()).enumerate(params).take(200_000)
                .map { it.ints.map { v -> v.toInt() } }.toHashSet()
            assertEquals(brute, found, "instance #$idx: backtrack solution set must equal brute force")
        }
    }

    @Test
    fun `wide constraints use the shared start-bound reason and still enumerate exactly`() {
        // Arity > LINEAR_SHARED_REASON_ARITY (32) switches Linear onto the shared start-of-call
        // reason built from the contribution snapshot — the only path that materialises
        // rLo/rHi. Pad the constraint with fixed singleton vars (1..1) so the arity is wide while
        // the free space stays small enough to brute-force; the propagated tightenings on the free
        // vars then exercise the wide reason builder (and the NE case exercises the recomputed
        // contribution in the NE branch) under full CDCL learning.
        val arity = 40
        val freeCount = 3
        val varsOf = IntArray(arity) { it }
        val lo = IntArray(arity) { if (it < freeCount) 0 else 1 }
        val hi = IntArray(arity) { if (it < freeCount) 3 else 1 }
        val cons = listOf(
            Con(IntArray(arity) { 1 }, LinearOp.LE, 39), // free sum <= 2 (37 from the fixed tail)
            Con(IntArray(arity) { 1 }, LinearOp.GE, 40), // free sum >= 3
            Con(IntArray(arity) { if (it < freeCount) 2 else 1 }, LinearOp.EQ, 41), // 2*free sum == 4
            Con(IntArray(arity) { 1 }, LinearOp.NE, 38), // free sum != 1
        )
        for ((idx, con) in cons.withIndex()) {
            val brute = HashSet<List<Int>>()
            val acc = IntArray(arity) { if (it < freeCount) 0 else 1 }
            fun rec(p: Int) {
                if (p == freeCount) {
                    if (satisfies(con, acc, varsOf)) brute.add(acc.toList())
                    return
                }
                for (v in lo[p]..hi[p]) {
                    acc[p] = v
                    rec(p + 1)
                }
            }
            rec(0)

            val problem = Problem(
                numBoolVars = 0,
                numIntVars = arity,
                intDomains = Array(arity) { IntDomain(lo[it].toLong(), hi[it].toLong()) },
                factors = arrayOf<Factor>(Linear(coeffs = con.coeffs, vars = varsOf, op = con.op, bound = con.bound)),
            )
            val params = BacktrackParams(randomSeed = 1L, variableSelector = Vsids(), maxLearnedClauses = 1_000)
            val found = BacktrackSolver(problem.bake()).enumerate(params).take(200_000)
                .map { it.ints.map { v -> v.toInt() } }.toHashSet()
            assertEquals(brute, found, "wide instance #$idx (op=${con.op}): solution set must equal brute force")
        }
    }

    // --- ProductArrayMinMaxBoundsEventTest ---

    private class ExcludeOnFixWithReason(val src: Int, val dst: Int) :
        Factor,
        Propagator {
        override val variables: VarList = MixedVars(spanInts = intArrayOf(src, dst), boolVars = IntArray(0))

        override fun propagate(state: PropagationState, factorId: Int): Boolean {
            val d = state.intDomains[src]
            // Explain the exclusion: dst != src.min holds *because* src is fixed to that value.
            // Citing src's singleton bounds keeps the recorded reason complete, so conflict
            // analysis cannot drop the premise (a null reason silently under-explains).
            return if (d.min == d.max) {
                state.excludeIntValue(dst, d.min, state.composeIntVarAtomAntecedents(intArrayOf(src)))
            } else {
                true
            }
        }

        override fun remap(mapping: VarRemap): Factor = ExcludeOnFixWithReason(mapping.int(src), mapping.int(dst))

        override fun structuralKey(): StructuralKey = error("test double has no structural key")

        override fun conflictReason(state: PropagationState, factorId: Int): IntArray? = null
        override fun asPropagator(): Propagator = this
        override fun asInvariant(): Invariant = object : Invariant {}
    }

    private fun enumerateWithVsids(problem: BakedProblem, seed: Long): HashSet<List<Int>> =
        BacktrackSolver(problem).enumerate(BacktrackParams(randomSeed = seed, variableSelector = Vsids()))
            .take(100_000).map { it.ints.map { v -> v.toInt() } }.toHashSet()

    private fun assertBoundOnly(watches: IntArray?, vars: IntArray) {
        val pairs = watches!!.map { IntEvent.intVarOf(it) to IntEvent.kindOf(it) }.toSet()
        val expected = vars.toHashSet().flatMap { v ->
            listOf(
                v to IntEvent.LB_RAISED,
                v to IntEvent.UB_LOWERED,
            )
        }.toSet()
        assertEquals(expected, pairs)
        assertFalse(
            watches.any { IntEvent.kindOf(it) == IntEvent.VALUE_REMOVED || IntEvent.kindOf(it) == IntEvent.FIXED },
        )
    }

    @Test
    fun `product and array-minmax subscribe to only bound events`() {
        assertBoundOnly(Product(a = 0, b = 1, result = 2).asPropagator().initialIntEventWatches, intArrayOf(0, 1, 2))
        assertBoundOnly(
            ArrayMinMax(result = 3, xs = intArrayOf(0, 1, 2), max = true).asPropagator().initialIntEventWatches,
            intArrayOf(0, 1, 2, 3),
        )
    }

    @Test
    fun `product with interior holes punched mid-search enumerates exactly brute force`() {
        // result = a*b, plus a co-constraint carving x3's fixed value out of a and b (interior holes
        // the product is not woken for). a,b,c ∈ 0..3, result ∈ 0..9.
        val factors = listOf<Factor>(
            Product(a = 0, b = 1, result = 2),
            ExcludeOnFixWithReason(src = 3, dst = 0),
            ExcludeOnFixWithReason(src = 3, dst = 1),
        )
        val doms = arrayOf(IntDomain(0, 3), IntDomain(0, 3), IntDomain(0, 9), IntDomain(0, 3))
        val problem = Problem(0, 4, doms, factors).bake()
        val brute = HashSet<List<Int>>()
        for (a in 0..3) {
            for (b in 0..3) {
                for (r in 0..9) {
                    for (c in 0..3) {
                        if (r == a * b && a != c && b != c) brute.add(listOf(a, b, r, c))
                    }
                }
            }
        }
        for (seed in 1L..16L) {
            assertEquals(brute, enumerateWithVsids(problem, seed), "product seed=$seed must match brute force")
        }
    }

    @Test
    fun `array-max with interior holes punched mid-search enumerates exactly brute force`() {
        // result = max(x0,x1), plus a co-constraint carving x3's fixed value out of x0 and x1.
        val factors = listOf<Factor>(
            ArrayMinMax(result = 2, xs = intArrayOf(0, 1), max = true),
            ExcludeOnFixWithReason(src = 3, dst = 0),
            ExcludeOnFixWithReason(src = 3, dst = 1),
        )
        val problem = Problem(0, 4, Array(4) { IntDomain(0, 3) }, factors).bake()
        val brute = HashSet<List<Int>>()
        for (x0 in 0..3) {
            for (x1 in 0..3) {
                for (r in 0..3) {
                    for (c in 0..3) {
                        if (r == maxOf(x0, x1) && x0 != c && x1 != c) brute.add(listOf(x0, x1, r, c))
                    }
                }
            }
        }
        for (seed in 1L..16L) {
            assertEquals(brute, enumerateWithVsids(problem, seed), "array-max seed=$seed must match brute force")
        }
    }

    // --- ProductReverseTest ---

    @Test
    fun `singleton-b narrows a's domain`() {
        // a * 3 = result, with result in [6..9]. a must be in [2..3].
        val p = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(-10, 10), IntDomain(3, 3), IntDomain(6, 9)),
            factors = arrayOf<Factor>(Product(a = 0, b = 1, result = 2)),
        )
        assertIs<PropagationResult.Implied>(p.propagate())
        val daAfter = PropagationSession(p).intDomain(0)
        assertEquals(2, daAfter.min, "a.min should be ceil(6/3) = 2; got $daAfter")
        assertEquals(3, daAfter.max, "a.max should be floor(9/3) = 3; got $daAfter")
    }

    @Test
    fun `a singleton operand and a singleton result force the remaining operand`() {
        val cases = listOf(
            Triple(arrayOf(IntDomain(0, 100), IntDomain(5, 5), IntDomain(15, 15)), 0, "a * 5 = 15"),
            Triple(arrayOf(IntDomain(4, 4), IntDomain(0, 100), IntDomain(12, 12)), 1, "4 * b = 12"),
            Triple(arrayOf(IntDomain(-10, 10), IntDomain(-2, -2), IntDomain(-6, -6)), 0, "a * -2 = -6"),
        )
        for ((doms, free, label) in cases) {
            val p = Problem(
                numBoolVars = 0,
                numIntVars = 3,
                intDomains = doms,
                factors = arrayOf<Factor>(Product(a = 0, b = 1, result = 2)),
            )
            val r = assertIs<PropagationResult.Implied>(p.propagate())
            assertEquals(3, r.ints[free], "$label must force the free operand to 3")
        }
    }

    @Test
    fun `non-divisible singleton result yields Unsat`() {
        // a * 4 = 5 has no integer solution → Unsat.
        val p = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(0, 100), IntDomain(4, 4), IntDomain(5, 5)),
            factors = arrayOf<Factor>(Product(a = 0, b = 1, result = 2)),
        )
        assertIs<PropagationResult.Unsat>(p.propagate())
    }

    @Test
    fun `non-singleton positive divisor narrows target via corner division`() {
        // a * b = result over a ∈ [-100, 100], b ∈ [2, 4], result ∈ [10, 20].
        // Corner division gives a ∈ [ceil(10/4), floor(20/2)] = [3, 10].
        val p = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(-100, 100), IntDomain(2, 4), IntDomain(10, 20)),
            factors = arrayOf<Factor>(Product(a = 0, b = 1, result = 2)),
        )
        val session = PropagationSession(p)
        val daAfter = session.intDomain(0)
        assertEquals(3, daAfter.min, "a.min should be ceil(10/4) = 3; got $daAfter")
        assertEquals(10, daAfter.max, "a.max should be floor(20/2) = 10; got $daAfter")
    }

    @Test
    fun `non-singleton negative divisor flips bounds correctly`() {
        // a * b = result, b ∈ [-4, -2], result ∈ [10, 20]; corner division yields
        // a.min = min ceil(r/b) = -10 and a.max = max floor(r/b) = -3.
        val p = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(-100, 100), IntDomain(-4, -2), IntDomain(10, 20)),
            factors = arrayOf<Factor>(Product(a = 0, b = 1, result = 2)),
        )
        val session = PropagationSession(p)
        val daAfter = session.intDomain(0)
        assertEquals(-10, daAfter.min, "got $daAfter")
        assertEquals(-3, daAfter.max, "got $daAfter")
    }

    @Test
    fun `divisor straddling zero leaves target unbounded by reverse`() {
        // b ∈ [-2, 3] contains 0 — reverse propagation must skip on this divisor side
        // (a/0 is undefined). a's domain endpoints are not on 0 either (-100 / 100), so
        // the zero-exclusion endpoint check doesn't fire. Expect a's domain unchanged.
        val p = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(-100, 100), IntDomain(-2, 3), IntDomain(10, 20)),
            factors = arrayOf<Factor>(Product(a = 0, b = 1, result = 2)),
        )
        val session = PropagationSession(p)
        val daAfter = session.intDomain(0)
        assertEquals(-100, daAfter.min, "a.min should not be touched")
        assertEquals(100, daAfter.max, "a.max should not be touched")
    }

    @Test
    fun `zero-result domain excludes zero from non-singleton operands`() {
        // Contiguous-interval domains can only kick 0 out at an endpoint, so a's
        // domain is started at 0 to exercise the endpoint-exclusion path.
        val p = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(0, 5), IntDomain(1, 5), IntDomain(10, 20)),
            factors = arrayOf<Factor>(Product(a = 0, b = 1, result = 2)),
        )
        val session = PropagationSession(p)
        val daAfter = session.intDomain(0)
        assertTrue(
            daAfter.min >= 1,
            "a.min=0 should have been pushed up since 0 * b = 0 ∉ result; got $daAfter",
        )
    }

    @Test
    fun `zero-singleton operand requires zero result`() {
        // a * 0 = result. If result must be 0, fine. If result domain excludes 0, Unsat.
        val pSat = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(-5, 5), IntDomain(0, 0), IntDomain(0, 0)),
            factors = arrayOf<Factor>(Product(a = 0, b = 1, result = 2)),
        )
        assertIs<PropagationResult.Implied>(pSat.propagate())

        val pUnsat = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(-5, 5), IntDomain(0, 0), IntDomain(5, 5)),
            factors = arrayOf<Factor>(Product(a = 0, b = 1, result = 2)),
        )
        assertIs<PropagationResult.Unsat>(pUnsat.propagate())
    }
}
