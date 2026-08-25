package com.eignex.klause.solver.integration

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.backtrack.selector.Vsids
import com.eignex.klause.factor.arithmetic.ArrayMinMax
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.factor.global.GlobalCardinality
import com.eignex.klause.factor.table.Element
import com.eignex.klause.factor.table.Table
import com.eignex.klause.localsearch.Invariant
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.Propagator
import com.eignex.klause.solver.*
import com.eignex.klause.solver.MixedVars
import com.eignex.klause.solver.StructuralKey
import com.eignex.klause.solver.VarList
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Multi-seed adversarial enumerate-vs-brute harness (#671). Conflict-analysis soundness on int /
 * order-literal models is search-order-sensitive: a learned clause that drops a co-premise only
 * loses solutions on the search paths that reach the bad conflict, so a single fixed seed can pass
 * for the life of a test while the analyzer is unsound on others (exactly how the [ExcludeOnFix]
 * under-explanation hid until seed 11). This harness enumerates each instance across many random
 * seeds and asserts the solution set equals brute force on **every** seed, with a hole-punching
 * co-constraint forcing interior-hole conflict analysis. It is the gate for the order-literal
 * trail rewrite, and a standing check that real factors explain their forces completely.
 */
class OrderLiteralSoundnessHarnessTest {

    /**
     * `dst != src` enforced by excluding src's value from dst once src is fixed — the same
     * interior-hole puncher the #623 bound-event tests use, but **explaining its force** (citing
     * src's singleton bounds), so it obeys the analyzer's contract instead of silently
     * under-explaining.
     */
    private class NotEqualOnFix(val a: Int, val b: Int) :
        Factor,
        Propagator {
        override val variables: VarList = MixedVars(spanInts = intArrayOf(a, b), lits = IntArray(0))

        override fun propagate(state: PropagationState, factorId: Int): Boolean {
            val da = state.intDomains[a]
            val db = state.intDomains[b]
            if (da.min == da.max && !state.excludeIntValue(
                    b,
                    da.min,
                    state.composeIntVarAtomAntecedents(intArrayOf(a)),
                )
            ) {
                return false
            }
            if (db.min == db.max && !state.excludeIntValue(
                    a,
                    db.min,
                    state.composeIntVarAtomAntecedents(intArrayOf(b)),
                )
            ) {
                return false
            }
            return true
        }

        override fun remap(boolMap: IntArray, intMap: IntArray): Factor = NotEqualOnFix(intMap[a], intMap[b])

        override fun structuralKey(): StructuralKey = error("test double has no structural key")

        override fun conflictReason(state: PropagationState, factorId: Int): IntArray? = null
        override fun asPropagator(): Propagator = this
        override fun asInvariant(): Invariant = object : Invariant {}
    }

    /** Recursive brute-force solution set: every full assignment over [domains] satisfying [ok]. */
    private fun brute(domains: Array<IntRange>, ok: (IntArray) -> Boolean): HashSet<List<Int>> {
        val out = HashSet<List<Int>>()
        val acc = IntArray(domains.size)
        fun rec(i: Int) {
            if (i == domains.size) {
                if (ok(acc)) out.add(acc.toList())
                return
            }
            for (v in domains[i]) {
                acc[i] = v
                rec(i + 1)
            }
        }
        rec(0)
        return out
    }

    /** Enumerate [problem] across seeds `1..[seeds]`; assert the solution set equals [expected] on each. */
    private fun assertEnumeratesBruteAcrossSeeds(
        label: String,
        domains: Array<IntRange>,
        factors: Array<Factor>,
        expected: HashSet<List<Int>>,
        // Kept small so each test stays under the 500ms CI budget (JIT warmup on the first test in
        // the class dominates). This is the standing regression gate; bump seeds locally for deeper
        // multi-seed validation during the rewrite.
        seeds: Int = 12,
    ) {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = domains.size,
            intDomains = Array(domains.size) { IntDomain(domains[it].first.toLong(), domains[it].last.toLong()) },
            factors = factors,
        )
        for (seed in 1L..seeds) {
            val found = BacktrackSolver(problem.bake())
                .enumerate(BacktrackParams(randomSeed = seed, variableSelector = Vsids(), maxLearnedClauses = 1_000))
                .take(200_000).map { it.ints.map { v -> v.toInt() } }.toHashSet()
            assertEquals(expected, found, "$label seed=$seed: backtrack solution set must equal brute force")
        }
    }

    @Test
    fun `var-array element with interior holes stays sound across seeds`() {
        // vars: 0=idx, 1=result, 2=v0, 3=v1, 4=c ; result = arr[idx] over [v0,v1]; c != v0, c != v1.
        val doms = arrayOf(0..1, 0..3, 0..3, 0..3, 0..3)
        assertEnumeratesBruteAcrossSeeds(
            "element",
            doms,
            arrayOf(
                Element(idx = 0, result = 1, arr = longArrayOf(2, 3), arrIsVars = true, indexOffset = 0),
                NotEqualOnFix(4, 2),
                NotEqualOnFix(4, 3),
            ),
            brute(doms) { a ->
                val sel = if (a[0] == 0) a[2] else a[3]
                a[1] == sel && a[4] != a[2] && a[4] != a[3]
            },
        )
    }

    @Test
    fun `array-max with interior holes stays sound across seeds`() {
        val doms = arrayOf(0..3, 0..3, 0..3, 0..3) // x0, x1, result, c
        assertEnumeratesBruteAcrossSeeds(
            "array-max",
            doms,
            arrayOf(
                ArrayMinMax(result = 2, xs = intArrayOf(0, 1), max = true),
                NotEqualOnFix(3, 0),
                NotEqualOnFix(3, 1),
            ),
            brute(doms) { a -> a[2] == maxOf(a[0], a[1]) && a[3] != a[0] && a[3] != a[1] },
        )
    }

    @Test
    fun `all-different with interior holes stays sound across seeds`() {
        val doms = arrayOf(0..3, 0..3, 0..3, 0..3, 0..3) // x0..x3, c
        assertEnumeratesBruteAcrossSeeds(
            "all-different",
            doms,
            arrayOf(
                AllDifferent(intArrayOf(0, 1, 2, 3), domainMin = 0, domainSize = 4),
                NotEqualOnFix(4, 0),
            ),
            brute(doms) { a ->
                val xs = listOf(a[0], a[1], a[2], a[3])
                xs.toHashSet().size == 4 && a[4] != a[0]
            },
        )
    }

    @Test
    fun `table with interior holes stays sound across seeds`() {
        // 3-col table over x0,x1,x2 ; plus c != x0.
        val tuples = intArrayOf(
            0, 1, 2,
            1, 2, 3,
            2, 3, 0,
            3, 0, 1,
            0, 2, 1,
            2, 0, 3,
        )
        val allowed = (0 until tuples.size / 3).map {
            listOf(
                tuples[it * 3],
                tuples[it * 3 + 1],
                tuples[it * 3 + 2],
            )
        }.toHashSet()
        val doms = arrayOf(0..3, 0..3, 0..3, 0..3) // x0,x1,x2,c
        assertEnumeratesBruteAcrossSeeds(
            "table",
            doms,
            arrayOf(
                Table(xs = intArrayOf(0, 1, 2), tuples = LongArray(tuples.size) { tuples[it].toLong() }),
                NotEqualOnFix(3, 0),
            ),
            brute(doms) { a -> listOf(a[0], a[1], a[2]) in allowed && a[3] != a[0] },
        )
    }

    @Test
    fun `global-cardinality with interior holes stays sound across seeds`() {
        // counts of value 0 and 1 across x0..x3 each bounded to [1,2] ; plus c != x0.
        val doms = arrayOf(0..2, 0..2, 0..2, 0..2, 0..2) // x0..x3, c
        assertEnumeratesBruteAcrossSeeds(
            "gcc",
            doms,
            arrayOf(
                GlobalCardinality(
                    xs = intArrayOf(0, 1, 2, 3),
                    cover = longArrayOf(0, 1),
                    countLow = intArrayOf(1, 1),
                    countHigh = intArrayOf(2, 2),
                ),
                NotEqualOnFix(4, 0),
            ),
            brute(doms) { a ->
                val xs = listOf(a[0], a[1], a[2], a[3])
                xs.count { it == 0 } in 1..2 && xs.count { it == 1 } in 1..2 && a[4] != a[0]
            },
        )
    }
}
