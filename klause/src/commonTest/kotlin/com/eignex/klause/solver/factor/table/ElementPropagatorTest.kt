package com.eignex.klause.solver.factor.table

import com.eignex.klause.solver.Assumptions
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Invariant
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.Propagator
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.backtrack.BacktrackSolver
import com.eignex.klause.solver.backtrack.selector.Vsids
import com.eignex.klause.solver.factor.global.AllDifferent
import com.eignex.klause.solver.factor.table.Element
import com.eignex.klause.solver.propagation.IntEvent
import com.eignex.klause.solver.propagation.PropagationState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * #651: [Element] now overrides [Factor.conflictReason] with the hole-aware Hall-style nogood of
 * every read int var ([collectHoleAndBoundAntecedents] over [Element.intVars]), like [AllDifferent].
 * The variable-array path cites `idx`, `result`, and the position vars; the constant-array path
 * cites only `idx`/`result`. Previously the failure fell through to the coarse default bool-pins
 * reason, suppressed once an int decision is on the trail. Tests: (1) the reason is a sound
 * non-empty witness — every literal false at conflict time; (2) full enumeration under CDCL learning
 * matches brute force for both the variable and constant array paths.
 */
class ElementPropagatorTest {

    @Test
    fun `var-array conflict reason is a sound nonempty witness`() {
        // idx in [0,1] selects arr=[v2, v3]; result must equal arr[idx]. A level-1 decision forces
        // result ≥ 10 but squeezes both elements ≤ 5 — no position can supply result, so idx is
        // wiped and propagate returns false.
        val factor = Element(idx = 0, result = 1, arr = intArrayOf(2, 3), arrIsVars = true, indexOffset = 0)
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = arrayOf(IntDomain(0, 1), IntDomain(0, 10), IntDomain(0, 10), IntDomain(0, 10)),
            factors = arrayOf<Factor>(factor),
        )
        val state = PropagationState(problem, Assumptions.None)
        state.undoLogging = true
        state.currentLevel = 1
        assertTrue(state.tightenIntMin(1, 10), "result ≥ 10")
        assertTrue(state.tightenIntMax(2, 5) && state.tightenIntMax(3, 5), "both elements ≤ 5")
        assertFalse(problem.propagators[0].propagate(state, 0), "no position can supply result=10 → infeasible")

        val reason = problem.propagators[0].conflictReason(state, 0)
        assertTrue(reason != null && reason.isNotEmpty(), "must yield a non-empty clause-form reason")
        for (lit in reason) {
            assertTrue(state.litFalse(lit), "every reason literal must be false at conflict time, lit=$lit")
        }
    }

    private fun enumerate(problem: Problem, seed: Long): HashSet<List<Int>> = BacktrackSolver(problem)
        .enumerate(BacktrackParams(randomSeed = seed, variableSelector = Vsids()))
        .take(100_000)
        .map { it.ints.toList() }
        .toHashSet()

    @Test
    fun `enumerate matches brute force for variable array`() {
        // result == arr[idx], arr=[v2, v3]. ints = [idx, result, v2, v3], all over small ranges.
        for (seed in 1L..5L) {
            val problem = Problem(
                numBoolVars = 0,
                numIntVars = 4,
                intDomains = arrayOf(IntDomain(0, 1), IntDomain(0, 3), IntDomain(0, 3), IntDomain(0, 3)),
                factors = arrayOf<Factor>(
                    Element(idx = 0, result = 1, arr = intArrayOf(2, 3), arrIsVars = true, indexOffset = 0),
                ),
            )
            val brute = HashSet<List<Int>>()
            for (idx in 0..1) {
                for (res in 0..3) {
                    for (v2 in 0..3) {
                        for (v3 in 0..3) {
                            val selected = if (idx == 0) v2 else v3
                            if (res == selected) brute.add(listOf(idx, res, v2, v3))
                        }
                    }
                }
            }
            assertEquals(brute, enumerate(problem, seed), "seed=$seed: var-array element must match brute force")
        }
    }

    @Test
    fun `enumerate matches brute force for constant array`() {
        // result == arr[idx] with constant arr=[5, 7, 5]. ints = [idx, result].
        val arr = intArrayOf(5, 7, 5)
        for (seed in 1L..5L) {
            val problem = Problem(
                numBoolVars = 0,
                numIntVars = 2,
                intDomains = arrayOf(IntDomain(0, 2), IntDomain(0, 9)),
                factors = arrayOf<Factor>(
                    Element(idx = 0, result = 1, arr = arr, arrIsVars = false, indexOffset = 0),
                ),
            )
            val brute = HashSet<List<Int>>()
            for (idx in 0..2) {
                for (res in 0..9) {
                    if (res == arr[idx]) brute.add(listOf(idx, res))
                }
            }
            assertEquals(brute, enumerate(problem, seed), "seed=$seed: const-array element must match brute force")
        }
    }

    private class ExcludeOnFix(val src: Int, val dst: Int) :
        Factor,
        Propagator {
        override val boolVars: IntArray = IntArray(0)
        override val intVars: IntArray = intArrayOf(src, dst)

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

        override fun remap(boolMap: IntArray, intMap: IntArray): Factor = ExcludeOnFix(intMap[src], intMap[dst])

        override fun conflictReason(state: PropagationState, factorId: Int): IntArray? = null
        override fun asPropagator(): Propagator = this
        override fun asInvariant(): Invariant = object : Invariant {
            override val boolVars get() = this@ExcludeOnFix.boolVars
            override val intVars get() = this@ExcludeOnFix.intVars
        }
    }

    @Test
    fun `variable-array element subscribes to all kinds and consumes the delta`() {
        val varArr = Element(idx = 0, result = 1, arr = intArrayOf(2, 3), arrIsVars = true, indexOffset = 0)
        val varArrProp = varArr.asPropagator() as ElementPropagator
        assertTrue(varArrProp.consumesIntEventDelta, "var-array element must consume the dirty-var delta")
        val watches = varArrProp.initialIntEventWatches
        assertTrue(watches != null)
        // every distinct variable subscribed to all four kinds
        val byVar = watches.groupBy { IntEvent.intVarOf(it) }
        for ((_, packs) in byVar) {
            assertEquals(
                setOf(IntEvent.LB_RAISED, IntEvent.UB_LOWERED, IntEvent.VALUE_REMOVED, IntEvent.FIXED),
                packs.map { IntEvent.kindOf(it) }.toSet(),
            )
        }
        assertEquals(setOf(0, 1, 2, 3), byVar.keys, "all of idx/result/array vars subscribed")

        // The constant-array path keeps occurrence wakeup (its own reversible domRef fast path).
        val constArr = Element(idx = 0, result = 1, arr = intArrayOf(5, 6, 7), arrIsVars = false, indexOffset = 0)
        val constArrProp = constArr.asPropagator() as ElementPropagator
        assertNull(constArrProp.initialIntEventWatches)
        assertFalse(constArrProp.consumesIntEventDelta)
    }

    @Test
    fun `delta-gated variable-array element stays sound with interior holes punched mid-search`() {
        // result = arr[idx] over arr=[v2,v3], plus a co-constraint carving var4's fixed value out of
        // v2/v3 — punching interior holes the element's gate must still react to. vars: 0=idx, 1=result,
        // 2=v2, 3=v3, 4=c.
        for (seed in 1L..6L) {
            val factors = listOf<Factor>(
                Element(idx = 0, result = 1, arr = intArrayOf(2, 3), arrIsVars = true, indexOffset = 0),
                ExcludeOnFix(src = 4, dst = 2),
                ExcludeOnFix(src = 4, dst = 3),
            )
            val doms = arrayOf(IntDomain(0, 1), IntDomain(0, 3), IntDomain(0, 3), IntDomain(0, 3), IntDomain(0, 3))
            val problem = Problem(0, 5, doms, factors)
            val brute = HashSet<List<Int>>()
            for (idx in 0..1) {
                for (res in 0..3) {
                    for (v2 in 0..3) {
                        for (v3 in 0..3) {
                            for (c in 0..3) {
                                val selected = if (idx == 0) v2 else v3
                                if (res == selected && v2 != c && v3 != c) brute.add(listOf(idx, res, v2, v3, c))
                            }
                        }
                    }
                }
            }
            val found = BacktrackSolver(problem)
                .enumerate(BacktrackParams(randomSeed = seed, variableSelector = Vsids()))
                .take(100_000).map { it.ints.toList() }.toHashSet()
            assertEquals(brute, found, "seed=$seed: delta-gated element + interior holes must match brute")
        }
    }

    /**
     * Pinned-index channel over a var array: `result = arr[idx]` with `idx` pinned copies
     * bounds both ways between `result` and the selected element. The copied bound is the
     * other var's search-derived state, so the recorded reason must cite that var — a
     * reason carrying only the index pin records `idx = pos → bound` as if it held for
     * every value of the other var, and conflict analysis resolving through it learns a
     * clause that prunes feasible assignments (surfaced as a false UNSAT on
     * project-planning). The element→result direction is also covered by the union bound,
     * which cites the array; the result→element direction below is served by the channel
     * alone.
     */
    @Test
    fun `pinned index channel cites the result bound when lifting the element`() {
        // ints: idx(0) root-pinned to 0, result(1), element(2); result = [element][idx].
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(0, 0), IntDomain(0, 2), IntDomain(0, 2)),
            factors = arrayOf<Factor>(
                Element(idx = 0, result = 1, arr = intArrayOf(2), arrIsVars = true, indexOffset = 0),
            ),
        )
        val state = PropagationState(problem, Assumptions.None)
        state.undoLogging = true
        state.currentLevel = 1
        check(state.tightenIntMin(1, 1)) { "tighten result min failed" }
        check(problem.propagators[0].propagate(state, 0)) { "element propagate failed" }
        check(state.intDomains[2].min == 1) { "channel must lift the element's min to 1" }

        val ant = state.intMinAntecedents[2]
        assertNotNull(ant, "the channeled bound is search-derived; its reason must not be a leaf")
        val citesResult = ant.any { lit ->
            val v = Lit.variable(lit)
            v >= problem.numBoolVars && state.atomIntVar[v - problem.numBoolVars] == 1
        }
        assertTrue(citesResult, "reason must cite the result var's bound; got ${ant.toList()}")
    }

    @Test
    fun `backtrack enumeration over Element equals brute force for const and var arrays`() {
        // Soundness gate for the unchanged-domains fast path: enumerating fires propagate repeatedly
        // on one PropagationState (fast-path hits on no-op re-fires; misses when a decision shrinks a
        // var), across push/pop. An unsound skip would drop or admit an assignment, so the enumerated
        // set must equal brute force. Covers both the const-array and the heavier var-array path.

        // Const array: result(0) = arr[idx(1) - 1], arr = [5,7,9], both vars over [0,10].
        run {
            val arr = intArrayOf(5, 7, 9)
            val problem = Problem(
                numBoolVars = 0,
                numIntVars = 2,
                intDomains = arrayOf(IntDomain(0, 10), IntDomain(0, 10)),
                factors = arrayOf<Factor>(Element(idx = 1, result = 0, arr = arr, arrIsVars = false, indexOffset = 1)),
            )
            val brute = HashSet<List<Int>>()
            for (res in 0..10) {
                for (idxV in 0..10) {
                    val pos = idxV - 1
                    if (pos in arr.indices && res == arr[pos]) brute.add(listOf(res, idxV))
                }
            }
            val found = BacktrackSolver(problem).enumerate(BacktrackParams(randomSeed = 1L)).take(100_000)
                .map { it.ints.toList() }.toHashSet()
            assertEquals(brute, found, "const-array Element: enumerated set must equal brute force")
        }

        // Var array: result(0) = [arr0(2), arr1(3)][idx(1) - 1]; idx in [1,2], others in [0,3].
        run {
            val problem = Problem(
                numBoolVars = 0,
                numIntVars = 4,
                intDomains = arrayOf(IntDomain(0, 3), IntDomain(1, 2), IntDomain(0, 3), IntDomain(0, 3)),
                factors = arrayOf<Factor>(
                    Element(idx = 1, result = 0, arr = intArrayOf(2, 3), arrIsVars = true, indexOffset = 1),
                ),
            )
            val brute = HashSet<List<Int>>()
            for (res in 0..3) {
                for (idxV in 1..2) {
                    for (a0 in 0..3) {
                        for (a1 in 0..3) {
                            val sel = if (idxV == 1) a0 else a1
                            if (res == sel) brute.add(listOf(res, idxV, a0, a1))
                        }
                    }
                }
            }
            val found = BacktrackSolver(problem).enumerate(BacktrackParams(randomSeed = 1L)).take(100_000)
                .map { it.ints.toList() }.toHashSet()
            assertEquals(brute, found, "var-array Element: enumerated set must equal brute force")
        }
    }

    @Test
    fun `incremental const-array Element with duplicate constants equals brute force`() {
        // Stresses the reversible support-count path: a constant value held by several positions has
        // support > 1, so removing one supporting idx position must NOT unsupport the result value
        // until the last one goes. Wide-ish domains + branching exercise rebuild / delta / cascade
        // and the trail rollback of the counts across deep backtracking.
        val arr = intArrayOf(5, 7, 5, 9, 7, 5) // 5×3, 7×2, 9×1
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 12), IntDomain(1, 6)),
            factors = arrayOf<Factor>(Element(idx = 1, result = 0, arr = arr, arrIsVars = false, indexOffset = 1)),
        )
        val brute = HashSet<List<Int>>()
        for (res in 0..12) {
            for (idxV in 1..6) {
                if (res == arr[idxV - 1]) brute.add(listOf(res, idxV))
            }
        }
        val found = BacktrackSolver(problem).enumerate(BacktrackParams(randomSeed = 3L)).take(100_000)
            .map { it.ints.toList() }.toHashSet()
        assertEquals(brute, found, "duplicate-constant const Element: enumerated set must equal brute force")
    }

    @Test
    fun `two coupled const-array Elements share a result equals brute force`() {
        // Two const Elements sharing the result var: each fire's prune feeds the other (cross-factor
        // cascade), and the incremental state of each must stay sound under interleaved push/pop.
        // result(0) = arrA[idxA(1)] and result(0) = arrB[idxB(2)], overlapping constant sets.
        val arrA = intArrayOf(2, 4, 6, 4) // values {2,4,6}
        val arrB = intArrayOf(4, 6, 6, 8) // values {4,6,8}; overlap {4,6}
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(0, 9), IntDomain(1, 4), IntDomain(1, 4)),
            factors = arrayOf<Factor>(
                Element(idx = 1, result = 0, arr = arrA, arrIsVars = false, indexOffset = 1),
                Element(idx = 2, result = 0, arr = arrB, arrIsVars = false, indexOffset = 1),
            ),
        )
        val brute = HashSet<List<Int>>()
        for (res in 0..9) {
            for (ia in 1..4) {
                for (ib in 1..4) {
                    if (res == arrA[ia - 1] && res == arrB[ib - 1]) brute.add(listOf(res, ia, ib))
                }
            }
        }
        val found = BacktrackSolver(problem).enumerate(BacktrackParams(randomSeed = 5L)).take(100_000)
            .map { it.ints.toList() }.toHashSet()
        assertEquals(brute, found, "coupled const Elements: enumerated set must equal brute force")
    }
}
