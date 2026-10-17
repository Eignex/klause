package com.eignex.klause.lp.cut

import com.eignex.klause.factor.arithmetic.ExactLinear
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearIndicator
import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.factor.bool.PseudoBoolean
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.IntegralConstants
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.Term
import com.eignex.klause.lp.engine.Cut
import com.eignex.klause.lp.engine.RevisedSimplex
import com.eignex.klause.lp.relaxation.CpToLpRelaxation
import com.eignex.klause.lp.relaxation.LpRelaxation
import com.eignex.klause.model.PbOp
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.BIG_ZERO
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.parseBigInt
import com.eignex.klause.util.plus
import com.eignex.klause.util.times
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AggregationMirSeparatorTest {

    private fun separate(
        p: Problem,
        obj: LinearObjective,
        point: DoubleArray? = null,
    ): Pair<List<Cut>, LpRelaxation>? {
        // A supplied point holds the LP context fixed while only the source factor representation varies.
        val base = if (point == null) p else p.withFactors(emptyArray())
        val session = PropagationSession(base)
        val r = CpToLpRelaxation(base, obj).build(session)
        if (r.model.n == 0) return null
        val primal = if (point == null) RevisedSimplex(r.model).solve()?.primal ?: return null else {
            DoubleArray(r.model.n) { point[r.colVarId[it]] }
        }
        val cuts = AggregationMirSeparator().separate(CutContext(p, r, primal, session))
        return cuts to r
    }

    private fun satisfies(f: LinearRow, x: IntArray): Boolean {
        val row = f.constants as IntegralConstants
        var sum = BIG_ZERO
        for (i in 0 until f.size) sum += row.exactCoeff(i) * bigIntOf(x[Term.intVar(f.ref(i))])
        return when (f.relation) {
            LinearOp.LE -> if (f.strict) sum < row.exactBound else sum <= row.exactBound
            LinearOp.GE -> if (f.strict) sum > row.exactBound else sum >= row.exactBound
            LinearOp.EQ -> sum == row.exactBound
            LinearOp.NE -> sum != row.exactBound
        }
    }

    private fun cutHolds(cut: Cut, rel: LpRelaxation, x: IntArray): Boolean {
        var s = 0L
        for (k in cut.cols.indices) s += cut.coeffs[k] * x[rel.colVarId[cut.cols[k]]]
        return s <= cut.rhs
    }

    private fun assertCutsValid(p: Problem, cuts: List<Cut>, rel: LpRelaxation) {
        val lins = p.factors.map { it as LinearRow }
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
            val d = p.finiteIntDomain(v)
            for (value in d.min..d.max) {
                x[v] = value.toInt()
                recurse(v + 1)
            }
        }
        recurse(0)
    }

    @Test
    fun `early exact rows should preserve a later ordinary pair cut`() {
        for (exactRows in listOf(0, 48)) {
            val factors = List<Factor>(exactRows) {
                ExactLinear.of(intArrayOf(Term.ofIntVar(0)), listOf(BigFraction.ofLong(2)),
                    LinearOp.LE, BigFraction.ofLong(2))
            } + listOf(
                Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 1),
                Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.LE, 0),
            )
            val p = Problem(0, 2, Array(2) { IntDomain(0, 1) }, factors.toTypedArray())
            val obj = LinearObjective(intCoefficients = longArrayOf(-1, -1))

            val (cuts, rel) = separate(p, obj, doubleArrayOf(0.5, 0.5)) ?: error("LP context required")

            assertTrue(cuts.any { cut -> cut.rhs == 0L && cut.cols.size == 1 &&
                rel.colVarId[cut.cols[0]] == 0 && cut.coeffs[0] == 1L })
            assertCutsValid(p, cuts, rel)
        }
    }

    @Test
    fun `exact single row cuts should not starve an ordinary pair`() {
        val factors = List<Factor>(16) {
            ExactLinear.of(intArrayOf(Term.ofIntVar(2)), listOf(BigFraction.ofLong(2)),
                LinearOp.LE, BigFraction.ONE)
        } + listOf(
            Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 1),
            Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.LE, 0),
        )
        val p = Problem(0, 3, Array(3) { IntDomain(0, 1) }, factors.toTypedArray())
        val obj = LinearObjective(intCoefficients = longArrayOf(-1, -1, -1))

        val (cuts, rel) = separate(p, obj, doubleArrayOf(0.5, 0.5, 0.5)) ?: error("LP context required")

        assertTrue(cuts.isNotEmpty())
        assertEquals(1, cuts.first().cols.size)
        assertEquals(0, rel.colVarId[cuts.first().cols[0]])
        assertEquals(1L, cuts.first().coeffs[0])
        assertEquals(0L, cuts.first().rhs)
        assertCutsValid(p, cuts, rel)
    }

    @Test
    fun `a final ordinary equality should retain its second direction past the row threshold`() {
        val factors = List<Factor>(47) { Linear(intArrayOf(2), intArrayOf(0), LinearOp.LE, 2) } +
            Linear(intArrayOf(2, 1), intArrayOf(1, 2), LinearOp.EQ, 1)
        val p = Problem(0, 3, Array(3) { IntDomain(0, 1) }, factors.toTypedArray())
        val obj = LinearObjective(intCoefficients = longArrayOf(-1, -1, -1))

        val (cuts, rel) = separate(p, obj, doubleArrayOf(0.5, 0.5, 0.0)) ?: error("LP context required")

        assertTrue(cuts.any { cut -> cut.cols.size == 2 &&
            cut.cols.map { rel.colVarId[it] }.toSet() == setOf(1, 2) &&
            cut.coeffs.all { it == -1L } && cut.rhs == -1L })
        assertCutsValid(p, cuts, rel)
    }

    @Test
    fun `an exact equality should not add a second direction beyond remaining capacity`() {
        val factors = List<Factor>(47) { Linear(intArrayOf(2), intArrayOf(0), LinearOp.LE, 2) } +
            ExactLinear.of(intArrayOf(Term.ofIntVar(1), Term.ofIntVar(2)),
                listOf(BigFraction.ofLong(2), BigFraction.ONE), LinearOp.EQ, BigFraction.ONE)
        val p = Problem(0, 3, Array(3) { IntDomain(0, 1) }, factors.toTypedArray())
        val obj = LinearObjective(intCoefficients = longArrayOf(-1, -1, -1))

        val (cuts, rel) = separate(p, obj, doubleArrayOf(0.5, 0.5, 0.0)) ?: error("LP context required")

        assertEquals(1, cuts.size)
        assertEquals(listOf(1L), cuts.single().coeffs.toList())
        assertEquals(0L, cuts.single().rhs)
        assertCutsValid(p, cuts, rel)
    }

    @Test
    fun `a final cardinality should retain its second direction past the row threshold`() {
        val factors = List<Factor>(47) { Linear(intArrayOf(2), intArrayOf(0), LinearOp.LE, 2) } +
            Cardinality(intArrayOf(Lit.make(0, true), Lit.make(0, true), Lit.make(1, true)), 1, 1)
        val p = Problem(2, 1, arrayOf(IntDomain(0, 1)), factors.toTypedArray())
        val obj = LinearObjective(intCoefficients = longArrayOf(-1), boolWeights = longArrayOf(-1, -1))

        val (cuts, rel) = separate(p, obj, doubleArrayOf(0.5, 0.0)) ?: error("LP context required")

        assertTrue(cuts.any { cut -> cut.cols.size == 2 && cut.cols.all { rel.colIsBool[it] } &&
            cut.coeffs.all { it == -1L } && cut.rhs == -1L })
    }

    @Test
    fun `unfinished normalization and aggregation should not publish a cut`() {
        for (limit in listOf(120L, 200L)) {
            val p = Problem(0, 2, Array(2) { IntDomain(0, 1) },
                arrayOf<Factor>(Linear(intArrayOf(2, 2), intArrayOf(0, 1), LinearOp.LE, 3)))
            val session = PropagationSession(p.withFactors(emptyArray()))
            val rel = CpToLpRelaxation(session.problem,
                LinearObjective(intCoefficients = longArrayOf(-1, -1))).build(session)
            var work = 0L
            val ctx = CutContext(p, rel, doubleArrayOf(0.75, 0.75), session) { units ->
                work += units
                work <= limit
            }

            val cuts = AggregationMirSeparator().separate(ctx)

            assertTrue(cuts.isEmpty())
            assertTrue(ctx.separationStopped)
        }
    }

    @Test
    fun `interrupted exact work should retain only completed ordinary cuts`() {
        val p = Problem(0, 50, Array(50) { IntDomain(0, 1) }, arrayOf<Factor>(
            Linear(intArrayOf(2), intArrayOf(0), LinearOp.LE, 1),
            ExactLinear.of(IntArray(50) { Term.ofIntVar(it) }, List(50) { BigFraction.ONE },
                LinearOp.LE, BigFraction.ofLong(50)),
        ))
        val session = PropagationSession(p.withFactors(emptyArray()))
        val rel = CpToLpRelaxation(session.problem,
            LinearObjective(intCoefficients = LongArray(50) { -1L })).build(session)
        var work = 0L
        val ctx = CutContext(p, rel, DoubleArray(rel.model.n) { 0.5 }, session) { units ->
            work += units
            work <= 500L
        }

        val cuts = AggregationMirSeparator().separate(ctx)

        assertTrue(ctx.separationStopped)
        assertEquals(1, cuts.size)
        assertEquals(0, rel.colVarId[cuts.single().cols.single()])
        assertEquals(listOf(1L), cuts.single().coeffs.toList())
        assertEquals(0L, cuts.single().rhs)
    }

    @Test
    fun `a stopped separation should not renew its work carrier`() {
        val p = Problem(0, 1, arrayOf(IntDomain(0, 1)),
            arrayOf<Factor>(Linear(intArrayOf(2), intArrayOf(0), LinearOp.LE, 1)))
        val session = PropagationSession(p.withFactors(emptyArray()))
        val rel = CpToLpRelaxation(session.problem, LinearObjective(intCoefficients = longArrayOf(-1))).build(session)
        var calls = 0
        val ctx = CutContext(p, rel, doubleArrayOf(0.5), session) { calls++; false }

        val first = AggregationMirSeparator().separate(ctx)
        val second = AggregationMirSeparator().separate(ctx)

        assertTrue(first.isEmpty())
        assertTrue(second.isEmpty())
        assertEquals(1, calls)
        assertTrue(ctx.separationStopped)
    }

    @Test
    fun `single row half cut should separate and stay valid`() {
        // 2x + 2y <= 3: the LP reaches x + y = 1.5 while every integer point has x + y <= 1.
        val p = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 3), IntDomain(0, 3)),
            factors = arrayOf<Factor>(Linear(intArrayOf(2, 2), intArrayOf(0, 1), LinearOp.LE, 3)),
        )
        val obj = LinearObjective(intCoefficients = longArrayOf(-1L, -1L))
        val (cuts, rel) = separate(p, obj) ?: error("LP should solve")
        assertTrue(cuts.isNotEmpty())
        assertCutsValid(p, cuts, rel)
    }

    @Test
    fun `half cut should not be derived over a column open below`() {
        // The rounding shift x - lo must not lean on an invented endpoint for a global cut.
        val p = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 3), IntDomain(0, 3)),
            factors = arrayOf<Factor>(Linear(intArrayOf(2, 2), intArrayOf(0, 1), LinearOp.LE, 3)),
            openIntLo = booleanArrayOf(true, false),
        )
        val obj = LinearObjective(intCoefficients = longArrayOf(-1L, -1L))
        val (cuts, _) = separate(p, obj) ?: error("LP should solve")
        assertTrue(cuts.isEmpty())
    }

    @Test
    fun `equivalent compact rows should separate the same half cut`() {
        val refs = intArrayOf(Term.ofIntVar(0), Term.ofIntVar(1))
        val rows = listOf(
            Linear(intArrayOf(2, 2), intArrayOf(0, 1), LinearOp.LE, 3),
            ExactLinear.of(refs, List(2) { BigFraction.ofLong(2) }, LinearOp.LE, BigFraction.ofLong(3)),
            ExactLinear.of(refs, List(2) { BigFraction.of(BIG_ONE, bigIntOf(2)) }, LinearOp.LE,
                BigFraction.of(bigIntOf(3), bigIntOf(4))),
            ExactLinear.of(refs, List(2) { BigFraction.ofLong(2) }, LinearOp.LE,
                BigFraction.ofLong(4), strict = true),
        )
        for (row in rows) {
            val p = Problem(0, 2, Array(2) { IntDomain(0, 3) }, arrayOf<Factor>(row))
            val objective = LinearObjective(intCoefficients = longArrayOf(-1, -1))

            val (cuts, rel) = separate(p, objective, doubleArrayOf(0.75, 0.75)) ?: error("LP columns expected")

            assertEquals(1, cuts.size)
            assertEquals(listOf(rel.intColOf[0], rel.intColOf[1]), cuts.single().cols.toList())
            assertEquals(listOf(1L, 1L), cuts.single().coeffs.toList())
            assertEquals(1L, cuts.single().rhs)
            assertTrue(cuts.single().global)
            assertCutsValid(p, cuts, rel)
        }
    }

    @Test
    fun `mixed ordinary and exact pairs should separate the same cut`() {
        for (exactFirst in listOf(false, true)) for (exactSecond in listOf(false, true)) {
            val factors = arrayOf<Factor>(
                if (exactFirst) ExactLinear.of(intArrayOf(Term.ofIntVar(0), Term.ofIntVar(1)),
                    List(2) { BigFraction.ONE }, LinearOp.LE, BigFraction.ONE)
                else Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 1),
                if (exactSecond) ExactLinear.of(intArrayOf(Term.ofIntVar(0), Term.ofIntVar(1)),
                    listOf(BigFraction.ONE, BigFraction.ofLong(-1)), LinearOp.LE, BigFraction.ZERO)
                else Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.LE, 0),
            )
            val p = Problem(0, 2, Array(2) { IntDomain(0, 2) }, factors)
            val objective = LinearObjective(intCoefficients = longArrayOf(-1, -1))

            val (cuts, rel) = separate(p, objective, doubleArrayOf(0.5, 0.5)) ?: error("LP columns expected")

            assertEquals(1, cuts.size)
            assertEquals(listOf(rel.intColOf[0]), cuts.single().cols.toList())
            assertEquals(listOf(1L), cuts.single().coeffs.toList())
            assertEquals(0L, cuts.single().rhs)
            assertCutsValid(p, cuts, rel)
        }
    }

    @Test
    fun `greater than rows should retain their integer half cut`() {
        val rows = listOf(
            Linear(intArrayOf(2, 2), intArrayOf(0, 1), LinearOp.GE, 1),
            ExactLinear.of(intArrayOf(Term.ofIntVar(0), Term.ofIntVar(1)),
                List(2) { BigFraction.ofLong(2) }, LinearOp.GE, BigFraction.ONE),
        )
        for (row in rows) {
            val p = Problem(0, 2, Array(2) { IntDomain(0, 2) }, arrayOf<Factor>(row))
            val objective = LinearObjective(intCoefficients = longArrayOf(-1, -1))

            val (cuts, rel) = separate(p, objective, doubleArrayOf(0.25, 0.25)) ?: error("LP columns expected")

            assertEquals(1, cuts.size)
            assertEquals(listOf(-1L, -1L), cuts.single().coeffs.toList())
            assertEquals(-1L, cuts.single().rhs)
            assertCutsValid(p, cuts, rel)
        }
    }

    @Test
    fun `equalities should mine both directions without excluding integer solutions`() {
        val rows = listOf(
            Linear(intArrayOf(2, 2, 1), intArrayOf(0, 1, 2), LinearOp.EQ, 3),
            ExactLinear.of(IntArray(3) { Term.ofIntVar(it) },
                listOf(BigFraction.ofLong(2), BigFraction.ofLong(2), BigFraction.ONE),
                LinearOp.EQ, BigFraction.ofLong(3)),
        )
        for (row in rows) {
            val p = Problem(0, 3, Array(3) { IntDomain(0, 3) }, arrayOf<Factor>(row))
            val objective = LinearObjective(intCoefficients = longArrayOf(-1, -1, -1))

            val (cuts, rel) = separate(p, objective, doubleArrayOf(0.75, 0.75, 0.0))
                ?: error("LP columns expected")

            assertEquals(2, cuts.size)
            assertEquals(listOf(1L, -2L), cuts.map { it.rhs })
            assertCutsValid(p, cuts, rel)
        }
    }

    @Test
    fun `unsupported exact rows should provide no global half cut`() {
        val refs = intArrayOf(Term.ofIntVar(0), Term.ofIntVar(1))
        val coeffs = List(2) { BigFraction.ofLong(2) }
        val wide = BigFraction.of(parseBigInt("9223372036854775808"), BIG_ONE)
        val rows = listOf(
            ExactLinear.of(intArrayOf(Term.ofRealVar(0)), listOf(BigFraction.ofLong(2)),
                LinearOp.LE, BigFraction.ofLong(3)),
            ExactLinear.of(intArrayOf(Term.ofRealVar(0)), listOf(BigFraction.ofLong(2)),
                LinearOp.LE, BigFraction.ofLong(3), strict = true),
            ExactLinear.of(refs, listOf(wide, BigFraction.ONE), LinearOp.LE, BigFraction.ofLong(3)),
            ExactLinear.of(refs, coeffs, LinearOp.LE, wide),
            ExactLinear.of(refs, coeffs, LinearOp.LE, BigFraction.ofLong(3), activator = 0),
            ExactLinear.of(refs, coeffs, LinearOp.NE, BigFraction.ofLong(3)),
            LinearIndicator(1, true, ExactLinear.of(refs, coeffs, LinearOp.LE, BigFraction.ofLong(3))),
        )
        for (row in rows) {
            val p = Problem(1, 2, Array(2) { IntDomain(0, 3) }, arrayOf<Factor>(row),
                numRealVars = 1, realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(3.0))
            val objective = LinearObjective(intCoefficients = longArrayOf(-1, -1))

            val (cuts, _) = separate(p, objective, doubleArrayOf(0.75, 0.75)) ?: error("LP columns expected")

            assertTrue(cuts.isEmpty())
        }
    }

    @Test
    fun `exact half cuts should require a declared lower bound`() {
        val row = ExactLinear.of(intArrayOf(Term.ofIntVar(0), Term.ofIntVar(1)),
            List(2) { BigFraction.ofLong(2) }, LinearOp.LE, BigFraction.ofLong(3))
        val p = Problem(0, 2, Array(2) { IntDomain(0, 3) }, arrayOf<Factor>(row),
            openIntLo = booleanArrayOf(true, false))
        val objective = LinearObjective(intCoefficients = longArrayOf(-1, -1))

        val (cuts, _) = separate(p, objective, doubleArrayOf(0.75, 0.75)) ?: error("LP columns expected")

        assertTrue(cuts.isEmpty())
    }

    @Test
    fun `exact half cuts should require every source column in the relaxation`() {
        val row = ExactLinear.of(intArrayOf(Term.ofIntVar(0), Term.ofIntVar(1)),
            List(2) { BigFraction.ofLong(2) }, LinearOp.LE, BigFraction.ofLong(3))
        val p = Problem(0, 2, Array(2) { IntDomain(0, 3) }, arrayOf<Factor>(row))
        val objective = LinearObjective(intCoefficients = longArrayOf(-1, -1))
        val (_, rel) = separate(p, objective, doubleArrayOf(0.75, 0.75)) ?: error("LP columns expected")
        rel.intColOf[1] = -1
        val session = PropagationSession(p.withFactors(emptyArray()))

        val cuts = AggregationMirSeparator().separate(CutContext(p, rel, doubleArrayOf(0.75, 0.75), session))

        assertTrue(cuts.isEmpty())
    }

    @Test
    fun `overflowing shifts and aggregates should decline half cuts`() {
        val cases = listOf(
            Triple(longArrayOf(2, 2), Long.MAX_VALUE, Long.MAX_VALUE / 2 + 1),
            Triple(longArrayOf(-2, -2), Long.MAX_VALUE, 4611686018427387904L),
            Triple(longArrayOf(-1, -1), Long.MIN_VALUE, Long.MAX_VALUE - 1),
            Triple(longArrayOf(Long.MAX_VALUE, Long.MAX_VALUE), 3L, 0L),
            Triple(longArrayOf(2, 2), Long.MAX_VALUE, 0L),
        )
        for ((coeffs, bound, lower) in cases) for (exact in listOf(false, true)) {
            val row = if (exact) ExactLinear.of(IntArray(2) { Term.ofIntVar(it) },
                coeffs.map(BigFraction::ofLong), LinearOp.LE, BigFraction.ofLong(bound))
            else Linear(coeffs, intArrayOf(0, 1), LinearOp.LE, bound)
            val p = Problem(0, 2, Array(2) { IntDomain(lower, lower + 1) }, arrayOf<Factor>(row, row))
            val objective = LinearObjective(intCoefficients = longArrayOf(-1, 1))

            val (cuts, _) = separate(p, objective, doubleArrayOf(0.0, 0.0)) ?: error("LP columns expected")

            assertTrue(cuts.isEmpty())
        }
    }

    @Test
    fun `equality flips should not wrap minimum word constants`() {
        for (exact in listOf(false, true)) {
            val coeffs = longArrayOf(2, 2)
            val row = if (exact) ExactLinear.of(IntArray(2) { Term.ofIntVar(it) },
                coeffs.map(BigFraction::ofLong), LinearOp.EQ, BigFraction.ofLong(Long.MIN_VALUE))
            else Linear(coeffs, intArrayOf(0, 1), LinearOp.EQ, Long.MIN_VALUE)
            val p = Problem(0, 2, Array(2) { IntDomain(0, 1) }, arrayOf<Factor>(row))
            val objective = LinearObjective(intCoefficients = longArrayOf(-1, -1))

            val (cuts, _) = separate(p, objective, doubleArrayOf(0.0, 0.0)) ?: error("LP columns expected")

            assertEquals(1, cuts.size)
            assertEquals(listOf(1L, 1L), cuts.single().coeffs.toList())
            assertEquals(Long.MIN_VALUE / 2, cuts.single().rhs)
        }
    }

    @Test
    fun `equality coefficient flips should decline unrepresentable negation`() {
        for (exact in listOf(false, true)) {
            val coeffs = longArrayOf(Long.MIN_VALUE, 2)
            val row = if (exact) ExactLinear.of(IntArray(2) { Term.ofIntVar(it) },
                coeffs.map(BigFraction::ofLong), LinearOp.EQ, BigFraction.ofLong(3))
            else Linear(coeffs, intArrayOf(0, 1), LinearOp.EQ, 3L)
            val p = Problem(0, 2, Array(2) { IntDomain(0, 1) }, arrayOf<Factor>(row))
            val objective = LinearObjective(intCoefficients = longArrayOf(-1, -1))

            val (cuts, _) = separate(p, objective, doubleArrayOf(0.0, 0.0)) ?: error("LP columns expected")

            assertTrue(cuts.isEmpty())
        }
    }

    @Test
    fun `boolean rows should retain half cuts for both literal polarities`() {
        for (negated in listOf(false, true)) for (cardinality in listOf(false, true)) {
            val lits = intArrayOf(Lit.make(0, !negated), Lit.make(1, true))
            val row = if (cardinality) Cardinality(intArrayOf(lits[0], lits[0], lits[1], lits[1]), 0, 3)
            else PseudoBoolean(longArrayOf(2, 2), lits, PbOp.LE, 3)
            val p = Problem(2, 0, emptyArray(), arrayOf<Factor>(row))
            val objective = LinearObjective(boolWeights = longArrayOf(-1, -1))
            val point = doubleArrayOf(if (negated) 0.25 else 0.75, 0.75)

            val (cuts, rel) = separate(p, objective, point) ?: error("LP columns expected")

            assertEquals(1, cuts.size)
            assertEquals(listOf(if (negated) -1L else 1L, 1L), cuts.single().coeffs.toList())
            assertEquals(if (negated) 0L else 1L, cuts.single().rhs)
            for (x in 0..1) for (y in 0..1) {
                val first = if (negated) 1 - x else x
                if (2 * first + 2 * y > 3) continue
                val values = intArrayOf(x, y)
                val cut = cuts.single()
                val lhs = cut.cols.indices.sumOf { cut.coeffs[it] * values[rel.colVarId[cut.cols[it]]] }
                assertTrue(lhs <= cut.rhs)
            }
        }
    }
}
