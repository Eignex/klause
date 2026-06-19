package com.eignex.klause.solver.factor

import com.eignex.klause.solver.Assumptions
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.backtrack.BacktrackSolver
import com.eignex.klause.solver.factor.table.Element
import com.eignex.klause.solver.propagation.PropagationState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ElementTest {

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
        check(problem.factors[0].propagate(state, 0)) { "element propagate failed" }
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
