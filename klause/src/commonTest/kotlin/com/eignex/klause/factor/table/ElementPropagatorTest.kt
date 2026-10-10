package com.eignex.klause.factor.table

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.backtrack.selector.Vsids
import com.eignex.klause.factor.PropagationReasonOracle
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.AtomKind
import com.eignex.klause.propagation.IntEvent
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.bake
import com.eignex.klause.propagation.factorAt
import com.eignex.klause.propagation.holeReasonFor
import com.eignex.klause.propagation.propagate
import com.eignex.klause.propagation.propagatorProjection
import com.eignex.klause.propagation.reasonOf
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ElementPropagatorTest {

    @Test
    fun `var-array conflict reason is a sound nonempty witness`() {
        // idx in [0,1] selects arr=[v2, v3]; result must equal arr[idx]. A level-1 decision forces
        // result ≥ 10 but squeezes both elements ≤ 5 — no position can supply result, so idx is
        // wiped and propagate returns false.
        val factor = Element(idx = 0, result = 1, arr = longArrayOf(2, 3), arrIsVars = true, indexOffset = 0)
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

    private fun enumerate(problem: Problem, seed: Long): HashSet<List<Int>> = BacktrackSolver(problem.bake())
        .enumerate(BacktrackParams(randomSeed = seed, variableSelector = Vsids()))
        .take(100_000)
        .map { it.ints.map { v -> v.toInt() } }
        .toHashSet()

    @Test
    fun `const-array element narrowing wakes a bounds-subscribed linear summing its results`() {
        // r1 = arr(idx1), r2 = arr(idx2) over constant arrays, with `r1 + r2 <= 60` capping their sum.
        // The sum cap can't fold into either result's domain (each result alone is well under 60), so
        // it stays an active Linear over the two results. During search, fixing an idx narrows its
        // result to a single value via the batched GAC exclusion; that exclusion must raise the bound
        // events so the Linear — which subscribes to LB_RAISED/UB_LOWERED and is dropped from each
        // result's occurrence-list wakeup — fires and rejects a sum over 60. A batched exclusion that
        // marks the var dirty without the event kind under-sets the wake, letting backtrack reach
        // leaves with r1 + r2 = 100 and emit them as solutions.
        val arr = longArrayOf(50, 10)
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = arrayOf(IntDomain(1, 2), IntDomain(1, 2), IntDomain(0, 100), IntDomain(0, 100)),
            factors = arrayOf<Factor>(
                Element(idx = 0, result = 2, arr = arr, arrIsVars = false, indexOffset = 1),
                Element(idx = 1, result = 3, arr = arr, arrIsVars = false, indexOffset = 1),
                Linear(intArrayOf(1, 1), intArrayOf(2, 3), LinearOp.LE, 60),
            ),
        )
        val brute = HashSet<List<Int>>()
        for (i1 in 1..2) {
            for (i2 in 1..2) {
                val r1 = arr[i1 - 1].toInt()
                val r2 = arr[i2 - 1].toInt()
                if (r1 + r2 <= 60) brute.add(listOf(i1, i2, r1, r2))
            }
        }
        for (seed in 1L..5L) {
            assertEquals(brute, enumerate(problem, seed), "seed=$seed: must not emit r1+r2 > 60 leaves")
        }
    }

    @Test
    fun `variable-array element subscribes to all kinds and consumes the delta`() {
        val varArr = Element(idx = 0, result = 1, arr = longArrayOf(2, 3), arrIsVars = true, indexOffset = 0)
        val varArrProp = varArr.propagatorProjection() as ElementPropagator
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
                Element(idx = 0, result = 1, arr = longArrayOf(2), arrIsVars = true, indexOffset = 0),
            ),
        )
        val state = PropagationState(problem, Assumptions.None)
        state.undoLogging = true
        state.currentLevel = 1
        check(state.tightenIntMin(1, 1)) { "tighten result min failed" }
        check(problem.propagators[0].propagate(state, 0)) { "element propagate failed" }
        check(state.intDomains[2].min == 1L) { "channel must lift the element's min to 1" }

        val ant = state.intMinAntecedents[2]
        assertNotNull(ant, "the channeled bound is search-derived; its reason must not be a leaf")
        val citesResult = ant.any { lit ->
            val v = Lit.variable(lit)
            v >= problem.numBoolVars && state.atoms.intVar[v - problem.numBoolVars] == 1
        }
        assertTrue(citesResult, "reason must cite the result var's bound; got ${ant.toList()}")
    }

    @Test
    fun `constant-array element deductions are implied by their reasons under carved holes`() {
        val rng = Random(0xE1E0)
        repeat(300) { iter ->
            val problem = Problem(
                numBoolVars = 0,
                numIntVars = 2,
                intDomains = arrayOf(IntDomain(0, 3), IntDomain(0, 4)),
                factors = arrayOf<Factor>(
                    Element(
                        idx = 0,
                        result = 1,
                        arr = LongArray(4) { rng.nextInt(5).toLong() },
                        arrIsVars = false,
                        indexOffset = 0,
                    ),
                ),
            )
            PropagationReasonOracle.assertReasonsImply(problem, "element-const#$iter") { state ->
                (0 until 3).all {
                    val v = rng.nextInt(2)
                    when (rng.nextInt(3)) {
                        0 -> state.excludeIntValue(v, rng.nextInt(5).toLong())
                        1 -> state.tightenIntMin(v, rng.nextInt(3).toLong())
                        else -> state.tightenIntMax(v, 1L + rng.nextInt(4))
                    }
                }
            }
        }
    }

    @Test
    fun `a constant-array element prunes a result value citing only the positions that held it`() {
        // arr = [6, 5, 6, 5, 6, 6]: with positions 1, 3 and 4 carved out, 5 has lost both holders; position 4
        // held a 6 and plays no part.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 5), IntDomain(5, 6)),
            factors = arrayOf<Factor>(
                Element(idx = 0, result = 1, arr = longArrayOf(6, 5, 6, 5, 6, 6), arrIsVars = false, indexOffset = 0),
            ),
        )
        val state = PropagationState(problem, Assumptions.None)
        state.undoLogging = true
        state.currentLevel = 1
        check(state.excludeIntValue(0, 1L) && state.excludeIntValue(0, 3L) && state.excludeIntValue(0, 4L))
        state.currentFactor = 0

        check(state.factorAt(0).propagate(state, 0))

        val cited = state.reasonOf(state.intMinAntecedents[1])!!.map { lit ->
            val atom = Lit.variable(lit) - problem.numBoolVars
            Triple(state.atoms.intVar[atom], state.atoms.kind[atom], state.atoms.threshold[atom])
        }
        assertEquals(setOf(Triple(0, AtomKind.EQ, 1L), Triple(0, AtomKind.EQ, 3L)), cited.toSet())
    }

    @Test
    fun `a constant-array element raises the result bound citing the positions below it not the holes it crossed`() {
        // arr = [1, 2, 3, 4]: result value 2 is carved and index position 0 dropped, so the result's minimum
        // climbs past the hole at 2 to 3. Every constant below 3 lost its positions to the index bound alone.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 3), IntDomain(1, 4)),
            factors = arrayOf<Factor>(
                Element(idx = 0, result = 1, arr = longArrayOf(1, 2, 3, 4), arrIsVars = false, indexOffset = 0),
            ),
        )
        val state = PropagationState(problem, Assumptions.None)
        state.undoLogging = true
        state.currentLevel = 1
        check(state.excludeIntValue(1, 2L) && state.tightenIntMin(0, 1L))
        state.currentFactor = 0

        check(state.factorAt(0).propagate(state, 0))

        assertEquals(3L, state.intDomains[1].min)
        assertEquals(
            listOf(Lit.make(state.atomVarGe(0, 2), false)),
            state.reasonOf(state.intMinAntecedents[1])?.toList(),
        )
    }

    @Test
    fun `a variable-array element drops a position citing only how its cell misses the result`() {
        // The result is at most 3 and cell 1 at least 5, so position 1 goes; cell 2's hole and cell 0's bound play
        // no part.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 5,
            intDomains = arrayOf(IntDomain(0, 2), IntDomain(0, 9), IntDomain(0, 9), IntDomain(0, 9), IntDomain(0, 9)),
            factors = arrayOf<Factor>(
                Element(idx = 0, result = 1, arr = longArrayOf(2, 3, 4), arrIsVars = true, indexOffset = 0),
            ),
        )
        val state = PropagationState(problem, Assumptions.None)
        state.undoLogging = true
        state.currentLevel = 1
        check(state.tightenIntMax(1, 3) && state.tightenIntMin(3, 5) && state.excludeIntValue(4, 7))
        check(state.tightenIntMin(2, 1))
        state.currentFactor = 0

        check(state.factorAt(0).propagate(state, 0))

        val cited = state.reasonOf(state.holeReasonFor(0, 1L))!!.map { lit ->
            val atom = Lit.variable(lit) - problem.numBoolVars
            Triple(state.atoms.intVar[atom], state.atoms.kind[atom], state.atoms.threshold[atom])
        }
        assertEquals(setOf(Triple(1, AtomKind.LE, 3L), Triple(3, AtomKind.GE, 5L)), cited.toSet())
    }

    @Test
    fun `variable-array element deductions are implied by their reasons under carved holes`() {
        val rng = Random(0xE1E1)
        repeat(300) { iter ->
            val problem = Problem(
                numBoolVars = 0,
                numIntVars = 5,
                intDomains = Array(5) { if (it == 0) IntDomain(0, 2) else IntDomain(0, 3) },
                factors = arrayOf<Factor>(
                    Element(idx = 0, result = 1, arr = longArrayOf(2, 3, 4), arrIsVars = true, indexOffset = 0),
                ),
            )
            PropagationReasonOracle.assertReasonsImply(problem, "element-var#$iter") { state ->
                (0 until 5).all {
                    val v = rng.nextInt(5)
                    when (rng.nextInt(3)) {
                        0 -> state.excludeIntValue(v, rng.nextInt(4).toLong())
                        1 -> state.tightenIntMin(v, rng.nextInt(2).toLong())
                        else -> state.tightenIntMax(v, 1L + rng.nextInt(3))
                    }
                }
            }
        }
    }
}
