package com.eignex.klause.lp.cut

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.factor.bool.PseudoBoolean
import com.eignex.klause.lp.RevisedSimplex
import com.eignex.klause.lp.relaxation.CpToLpRelaxation
import com.eignex.klause.lp.relaxation.LpRelaxation
import com.eignex.klause.model.PbOp
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.objective.LinearObjective
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * [AggregationMirSeparator] {0,½} cuts must be valid — satisfied by every integer-feasible point — and
 * must actually separate the fractional LP point they were derived from. Validity is checked by brute
 * force against the full integer box, since an invalid cut would silently remove real solutions.
 */
class AggregationMirSeparatorTest {

    private fun separate(p: Problem, obj: LinearObjective): Pair<List<Cut>, LpRelaxation>? {
        val session = PropagationSession(p)
        val r = CpToLpRelaxation(p, obj).build(session)
        if (r.model.n == 0) return null
        val sol = RevisedSimplex(r.model).solve() ?: return null
        val cuts = AggregationMirSeparator().separate(CutContext(p, r, sol.primal, session))
        return cuts to r
    }

    /** Σ coeffs·x ⟨op⟩ bound holds for the integer assignment [x]. */
    private fun satisfies(f: Linear, x: IntArray): Boolean {
        var s = 0L
        for (i in f.vars.indices) s += f.coeffs[i] * x[f.vars[i]]
        return when (f.op) {
            LinearOp.LE -> s <= f.bound
            LinearOp.GE -> s >= f.bound
            LinearOp.EQ -> s == f.bound
            else -> true
        }
    }

    private fun cutHolds(cut: Cut, rel: LpRelaxation, x: IntArray): Boolean {
        var s = 0L
        for (k in cut.cols.indices) s += cut.coeffs[k] * x[rel.colVarId[cut.cols[k]]]
        return s <= cut.rhs // separator emits only LE cuts
    }

    /** Brute-force every integer point of the box, asserting each emitted cut holds at every point that
     *  satisfies all Linear factors. */
    private fun assertCutsValid(p: Problem, cuts: List<Cut>, rel: LpRelaxation) {
        val lins = p.factors.filterIsInstance<Linear>()
        val x = IntArray(p.numIntVars)
        fun recurse(v: Int) {
            if (v == p.numIntVars) {
                if (lins.all { satisfies(it, x) }) {
                    for (cut in cuts) {
                        assertTrue(
                            cutHolds(cut, rel, x),
                            "cut ${cut.cols.toList()}·x ≤ ${cut.rhs} cuts off feasible ${x.toList()}",
                        )
                    }
                }
                return
            }
            val d = p.requireFiniteIntDomains()[v]
            for (value in d.min..d.max) {
                x[v] = value.toInt()
                recurse(v + 1)
            }
        }
        recurse(0)
    }

    @Test
    fun `single row half cut separates and stays valid`() {
        // 2x + 2y ≤ 3 over [0,3]: the LP maximum of x+y is 1.5 (x=y=0.75), but every integer point has
        // x+y ≤ 1. The {0,½} cut ⌊2/2⌋x + ⌊2/2⌋y ≤ ⌊3/2⌋ = (x+y ≤ 1) separates it.
        val p = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 3), IntDomain(0, 3)),
            factors = arrayOf<Factor>(Linear(intArrayOf(2, 2), intArrayOf(0, 1), LinearOp.LE, 3)),
        )
        val obj = LinearObjective(intCoefficients = longArrayOf(-1L, -1L))
        val (cuts, rel) = separate(p, obj) ?: error("LP should solve")
        assertTrue(cuts.isNotEmpty(), "expected a separating {0,½} cut")
        assertCutsValid(p, cuts, rel)
    }

    @Test
    fun `random aggregation cuts are always valid`() {
        val rng = Random(20260621)
        var produced = 0
        repeat(400) {
            val n = rng.nextInt(2, 4)
            val domains = Array(n) { IntDomain(0, rng.nextInt(2, 5).toLong()) }
            val factors = ArrayList<Factor>()
            repeat(rng.nextInt(1, 4)) {
                val k = rng.nextInt(2, n + 1)
                val vars = (0 until n).shuffled(rng).take(k).toIntArray()
                val coeffs = IntArray(k) { rng.nextInt(1, 4) }
                val op = if (rng.nextBoolean()) LinearOp.LE else LinearOp.GE
                val bound = rng.nextInt(0, 3 * k + 1)
                factors.add(Linear(coeffs, vars, op, bound))
            }
            val p = Problem(0, n, domains, factors.toTypedArray())
            val obj = LinearObjective(intCoefficients = LongArray(n) { rng.nextLong(-3, 4) })
            val (cuts, rel) = separate(p, obj) ?: return@repeat
            produced += cuts.size
            assertCutsValid(p, cuts, rel)
        }
        assertTrue(produced > 0, "the separator never fired across 400 random instances")
    }

    private fun litValue(lit: Int, bools: BooleanArray): Int = if (Lit.isPositive(
            lit,
        ) == bools[Lit.variable(lit)]
    ) {
        1
    } else {
        0
    }

    private fun satisfiesPb(f: PseudoBoolean, bools: BooleanArray): Boolean {
        var s = 0L
        for (k in f.literals.indices) s += f.weights[k] * litValue(f.literals[k], bools)
        return when (f.op) {
            PbOp.LE -> s <= f.bound
            PbOp.GE -> s >= f.bound
            PbOp.EQ -> s == f.bound
        }
    }

    private fun satisfiesCard(f: Cardinality, bools: BooleanArray): Boolean {
        var c = 0
        for (lit in f.literals) c += litValue(lit, bools)
        return c in f.min..f.max
    }

    private fun cutHoldsBool(cut: Cut, rel: LpRelaxation, bools: BooleanArray): Boolean {
        var s = 0L
        for (k in cut.cols.indices) {
            val col = cut.cols[k]
            if (rel.colIsBool[col] && bools[rel.colVarId[col]]) s += cut.coeffs[k]
        }
        return s <= cut.rhs
    }

    @Test
    fun `bool aggregation cuts are always valid`() {
        val rng = Random(20260623)
        var produced = 0
        repeat(400) {
            val n = rng.nextInt(2, 7)
            val factors = ArrayList<Factor>()
            repeat(rng.nextInt(1, 4)) {
                val k = rng.nextInt(2, n + 1)
                val vars = (0 until n).shuffled(rng).take(k)
                val lits = IntArray(k) { j -> Lit.make(vars[j], rng.nextBoolean()) }
                when (rng.nextInt(3)) {
                    0 -> factors.add(
                        PseudoBoolean(
                            LongArray(k) { rng.nextInt(1, 4).toLong() },
                            lits,
                            PbOp.LE,
                            rng.nextInt(0, 2 * k).toLong(),
                        ),
                    )

                    1 -> factors.add(
                        PseudoBoolean(
                            LongArray(k) { rng.nextInt(1, 4).toLong() },
                            lits,
                            PbOp.GE,
                            rng.nextInt(0, 2 * k).toLong(),
                        ),
                    )

                    else -> {
                        val a = rng.nextInt(0, k + 1)
                        val b = rng.nextInt(0, k + 1)
                        factors.add(Cardinality(lits, min = minOf(a, b), max = maxOf(a, b)))
                    }
                }
            }
            val p = Problem(
                numBoolVars = n,
                numIntVars = 0,
                intDomains = emptyArray(),
                factors = factors.toTypedArray(),
            )
            val obj = LinearObjective(boolWeights = LongArray(n) { rng.nextLong(-3, 4) })
            val (cuts, rel) = separate(p, obj) ?: return@repeat
            produced += cuts.size
            val bools = BooleanArray(n)
            fun recurse(i: Int) {
                if (i == n) {
                    val feasible = p.factors.all { f ->
                        when (f) {
                            is PseudoBoolean -> satisfiesPb(f, bools)
                            is Cardinality -> satisfiesCard(f, bools)
                            else -> true
                        }
                    }
                    if (feasible) {
                        for (cut in cuts) {
                            assertTrue(cutHoldsBool(cut, rel, bools), "bool cut cuts off feasible ${bools.toList()}")
                        }
                    }
                    return
                }
                bools[i] = false
                recurse(i + 1)
                bools[i] = true
                recurse(i + 1)
            }
            recurse(0)
        }
        assertTrue(produced > 0, "the bool separator never fired across 400 random instances")
    }
}
