package com.eignex.klause.backtrack

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.incumbent.Publication
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.util.numbers.bigIntOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SourceObjectiveScoreTest {
    private val objective = LinearObjective(intCoefficients = longArrayOf(1, 1))

    private fun problem(numRealVars: Int = 0) = Problem(
        numBoolVars = 1,
        numIntVars = 2,
        intDomains = Array(2) { IntDomain(0, 5) },
        factors = arrayOf(
            Clause(intArrayOf(Lit.make(0, true))),
            Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 4),
        ),
        numRealVars = numRealVars,
        realLower = DoubleArray(numRealVars),
        realUpper = DoubleArray(numRealVars) { 1.0 },
    )

    private fun sample(b0: Boolean, x0: Long, x1: Long, reals: DoubleArray = DoubleArray(0)) =
        Sample(booleanArrayOf(b0), longArrayOf(x0, x1), reals)

    @Test
    fun `a non-finite objective should never become an incumbent`() {
        val exchange = minimizingSampleExchange(problem())
        assertIs<Publication.Rejected>(
            exchange.offer(sample(b0 = true, x0 = 1, x1 = 2), SourceObjectiveScore(Double.NaN)),
        )
        assertNull(exchange.current())
    }

    @Test
    fun `only a strict improvement should install`() {
        val exchange = minimizingSampleExchange(problem())
        assertIs<Publication.Installed<Sample, SourceObjectiveScore>>(
            exchange.offer(sample(true, 1, 2), SourceObjectiveScore(3.0)),
        )
        assertIs<Publication.NotImproving>(exchange.offer(sample(true, 0, 3), SourceObjectiveScore(3.0)))
        assertIs<Publication.NotImproving>(exchange.offer(sample(true, 2, 2), SourceObjectiveScore(4.0)))
        val better = assertIs<Publication.Installed<Sample, SourceObjectiveScore>>(
            exchange.offer(sample(true, 0, 1), SourceObjectiveScore(1.0)),
        )
        assertEquals(2L, better.incumbent.version)
    }

    @Test
    fun `exact improvement should replace an incumbent with the same displayed score`() {
        val exchange = minimizingSampleExchange(problem())
        val display = -9_007_199_254_740_992.0
        val first = SourceObjectiveScore(display, BigFraction.ofLong(-9_007_199_254_740_992L))
        val second = SourceObjectiveScore(display, BigFraction.ofLong(-9_007_199_254_740_993L))

        assertIs<Publication.Installed<Sample, SourceObjectiveScore>>(exchange.offer(sample(true, 1, 2), first))
        val replaced = assertIs<Publication.Installed<Sample, SourceObjectiveScore>>(
            exchange.offer(sample(true, 0, 3), second),
        )
        assertEquals(2L, replaced.incumbent.version)
        assertEquals(second.exact, exchange.current()?.objective?.exact)
    }

    @Test
    fun `exact incumbent bound should enclose a rational beyond one floating point step`() {
        val exact = BigFraction.of(
            bigIntOf(16_003_601_413_338_437L),
            bigIntOf(17_309_130_200_826_847L),
        )
        val score = SourceObjectiveScore(exact.toDouble(), exact)

        assertTrue(checkNotNull(BigFraction.ofDouble(score.pruningBound)) >= exact)
    }

    @Test
    fun `exact incumbent score should ignore an overflowed reported objective`() {
        val exact = BigFraction.ofLong(Long.MAX_VALUE) + BigFraction.ofLong(Long.MAX_VALUE)
        val score = SourceObjectiveScore(-2.0, exact)

        assertTrue(score.display > 0.0)
        assertTrue(checkNotNull(BigFraction.ofDouble(score.pruningBound)) >= exact)
    }

    @Test
    fun `a float incumbent should stand only when the tolerance check accepts it`() {
        for ((real, installed) in listOf(0.5 to true, 0.9 to false)) {
            val exchange = minimizingSampleExchange(problem(numRealVars = 1)) { it.reals.single() <= 0.75 }
            val float = sample(b0 = true, x0 = 1, x1 = 2, reals = doubleArrayOf(real))

            val publication = exchange.offer(float, SourceObjectiveScore(objective.evaluate(float)))

            assertEquals(installed, publication is Publication.Installed, "$real")
        }
    }
}
