package com.eignex.klause.solver.pipeline

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.Problem
import com.eignex.klause.presolve.PresolveConfig
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.evidence.SourceClaim
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.solver.evidence.SourceInequality
import com.eignex.klause.solver.evidence.SourceObjective
import com.eignex.klause.solver.evidence.SourcePremise
import com.eignex.klause.solver.evidence.SourceProofContext
import com.eignex.klause.solver.evidence.SourceRevision
import com.eignex.klause.solver.evidence.SourceScope
import com.eignex.klause.solver.evidence.SourceValue
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.Bits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpenTheoryPipelineTest {

    @Test
    fun `ordinary real optimization certifies a free improving ray`() {
        val model = Problem(0, 0, emptyArray(), emptyArray(), numRealVars = 1,
            realLower = doubleArrayOf(Double.NEGATIVE_INFINITY),
            realUpper = doubleArrayOf(Double.POSITIVE_INFINITY))
        val request = OpenTheoryRequest(model, LinearObjective(realCoefficients = doubleArrayOf(1.0)))

        val result = assertIs<OpenTheoryExecution.Optimize>(OpenTheoryPipeline.execute(request)).result

        assertIs<OpenTheoryOptimum.Unbounded>(result)
        val ray = assertIs<SourceClaim.Ray>(assertNotNull(result.linearResult?.terminal).claim)
        assertTrue(ray.direction.values.single() < BigFraction.ZERO)
    }

    @Test
    fun `ordinary real optimization separates a strict limit from its incumbent`() {
        val model = Problem(0, 0, emptyArray(), arrayOf(Linear(intArrayOf(), doubleArrayOf(),
            intArrayOf(0), doubleArrayOf(1.0), LinearOp.GE, 0.0, strict = true)), numRealVars = 1,
            realLower = doubleArrayOf(Double.NEGATIVE_INFINITY),
            realUpper = doubleArrayOf(Double.POSITIVE_INFINITY))
        val request = OpenTheoryRequest(model, LinearObjective(realCoefficients = doubleArrayOf(1.0)))

        val result = assertIs<OpenTheoryExecution.Optimize>(OpenTheoryPipeline.execute(request)).result

        val bounded = assertIs<OpenTheoryOptimum.Bounded>(result)
        val limit = assertIs<SourceClaim.Limit>(assertNotNull(bounded.linearResult?.terminal).claim)
        assertEquals(SourceValue.Rational(BigFraction.ZERO), limit.value)
        assertTrue(assertNotNull(bounded.value) > BigFraction.ZERO)
    }

    @Test
    fun `maximized strict limits retain the original objective direction`() {
        val model = Problem(0, 0, emptyArray(), arrayOf(Linear(intArrayOf(), doubleArrayOf(),
            intArrayOf(0), doubleArrayOf(1.0), LinearOp.LE, 1.0, strict = true)), numRealVars = 1,
            realLower = doubleArrayOf(Double.NEGATIVE_INFINITY),
            realUpper = doubleArrayOf(Double.POSITIVE_INFINITY))
        val request = OpenTheoryRequest(model, LinearObjective(realCoefficients = doubleArrayOf(1.0)), maximize = true)

        val result = assertIs<OpenTheoryExecution.Optimize>(OpenTheoryPipeline.execute(request)).result

        val limit = assertIs<SourceClaim.Limit>(assertNotNull(result.linearResult?.terminal).claim)
        assertEquals(SourceValue.Rational(BigFraction.ONE), limit.value)
        assertEquals(assertNotNull(result.linearResult?.value).negated(),
            assertIs<OpenTheoryOptimum.Bounded>(result).value)
    }

    @Test
    fun `ordinary requests use the source owners exact rows and objective`() {
        val model = Problem(0, 0, emptyArray(), emptyArray(), numRealVars = 1,
            realLower = doubleArrayOf(1.0 / 3.0), realUpper = doubleArrayOf(Double.POSITIVE_INFINITY))
        val real = SourceCoordinate(SourceCoordinate.Kind.Real, 0)
        val third = BigFraction.ONE / BigFraction.ofLong(3)
        val context = SourceProofContext(SourceScope(SourceRevision("exact-source", "exact-affine"),
            objective = SourceObjective("cost", ObjectiveSense.MINIMIZE)), setOf(real),
            mapOf("lower" to SourcePremise(SourceInequality(mapOf(real to BigFraction.MINUS_ONE), third.negated()))),
            mapOf(real to third))
        val request = OpenTheoryRequest(model, LinearObjective(realCoefficients = doubleArrayOf(1.0 / 3.0)))
            .withLinearAuthority(context)

        val result = assertIs<OpenTheoryExecution.Optimize>(OpenTheoryPipeline.execute(request)).result

        assertEquals(third * third, assertIs<OpenTheoryOptimum.Optimal>(result).value)
        assertEquals(context.scope, assertNotNull(result.linearResult).scope)
    }

    @Test
    fun `ordinary mixed optimization retains moving integer coordinates in a ray`() {
        val open = Bits(1).also { it.set(0) }
        val model = Problem(numBoolVars = 0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(0), open, open),
            factors = arrayOf<Factor>(Linear(intArrayOf(0), doubleArrayOf(-1.0), intArrayOf(0),
                doubleArrayOf(1.0), LinearOp.GE, 0.0)), numRealVars = 1,
            realLower = doubleArrayOf(Double.NEGATIVE_INFINITY),
            realUpper = doubleArrayOf(Double.POSITIVE_INFINITY))
        val request = OpenTheoryRequest(model, LinearObjective(realCoefficients = doubleArrayOf(1.0)))

        val result = assertIs<OpenTheoryExecution.Optimize>(OpenTheoryPipeline.execute(request)).result

        val ray = assertIs<SourceClaim.Ray>(assertNotNull(assertIs<OpenTheoryOptimum.Unbounded>(result)
            .linearResult?.terminal).claim)
        val integer = assertNotNull(ray.direction[SourceCoordinate(SourceCoordinate.Kind.Integer, 0)])
        val real = assertNotNull(ray.direction[SourceCoordinate(SourceCoordinate.Kind.Real, 0)])
        assertTrue(integer < BigFraction.ZERO && real < BigFraction.ZERO)
        assertEquals(BigFraction.ONE.den, integer.den)
        assertTrue(real >= integer)
    }

    @Test
    fun `ordinary incomplete Boolean coverage retains an incumbent without a global claim`() {
        val model = Problem(1, 0, emptyArray(), emptyArray(), numRealVars = 1,
            realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(Double.POSITIVE_INFINITY))
        val request = OpenTheoryRequest(model, LinearObjective(boolWeights = longArrayOf(1)))

        val result = assertIs<OpenTheoryExecution.Optimize>(OpenTheoryPipeline.execute(request,
            TheoryParams(maxLeaves = 1))).result

        val authority = assertNotNull(assertIs<OpenTheoryOptimum.Bounded>(result).linearResult)
        assertNotNull(authority.incumbent)
        assertNull(authority.terminal)
        assertNull(authority.bound)
    }

    @Test
    fun `preparation reads a maximized objective in minimize sense`() {
        // `maximize x` subject to `x <= 7`. Lowering x is safe, so a source pass reading the raw
        // coefficient sees `+1 >= 0` and pins x at 0 — the one value maximization rules out. Preparation
        // has to resolve the sense the way execution does, or it reports a model the solve never sees.
        val openUpper = Bits(1).also { it.set(0) }
        val model = Problem(
            numBoolVars = 0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(0), null, openUpper),
            factors = arrayOf<Factor>(Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 7)),
        )
        val request = OpenTheoryRequest(
            model = model,
            objective = LinearObjective(intCoefficients = longArrayOf(1L)),
            maximize = true,
        )

        val preparation = OpenTheoryPipeline.prepare(request)

        assertEquals(7L, preparation.model.intBounds.upper(0), "x must keep the values maximization wants")
    }

    // x ≤ 7 over a column open below, with one Boolean beside it.
    private fun openBelow(): Problem {
        val openLower = Bits(1).also { it.set(0) }
        return Problem(
            numBoolVars = 1,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(7), openLower, null),
            factors = arrayOf<Factor>(Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 7)),
        )
    }

    @Test
    fun `the descent minimizes an integral objective`() {
        val request = OpenTheoryRequest(openBelow(), LinearObjective(intCoefficients = longArrayOf(-1L)))

        assertTrue(OpenTheoryPipeline.canMinimize(request))
    }

    @Test
    fun `a Boolean objective admits source-bound complete descent`() {
        val objective = LinearObjective(boolWeights = longArrayOf(1L), intCoefficients = longArrayOf(-1L))

        val request = OpenTheoryRequest(openBelow(), objective)

        val result = assertIs<OpenTheoryExecution.Optimize>(OpenTheoryPipeline.execute(request)).result

        assertEquals("-7", assertIs<OpenTheoryOptimum.Optimal>(result).value.toString())
    }

    @Test
    fun `preparation reports LP work used to close an open side`() {
        val openUpper = Bits(1).also { it.set(0) }
        // The `>= 2` row is what keeps dual fixing off this column: with only the `<= 7` row, lowering
        // would be safe everywhere and the pin would close the side before any LP ran. Probing would close
        // it from the `<= 7` row alone, so it stays off.
        val request = OpenTheoryRequest(
            model = Problem(
                numBoolVars = 0,
                intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(0), null, openUpper),
                factors = arrayOf<Factor>(
                    Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 7),
                    Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 2),
                ),
            ),
        ).withPresolve(PresolveConfig.parse("default,-probe-open"))

        val preparation = OpenTheoryPipeline.prepare(request)

        assertEquals(1, preparation.closedSides)
        val stats = assertNotNull(preparation.stats)
        assertTrue(
            stats.lpStats.standalonePasses.sum + stats.lpStats.componentPasses.sum > 0.0,
            "the LP solve that closed the side must be reported",
        )
    }

    @Test
    fun `selects and executes a satisfiability route`() {
        val openUpper = Bits(1).also { it.set(0) }
        val request = OpenTheoryRequest(
            model = Problem(
                numBoolVars = 0,
                intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(0), null, openUpper),
                factors = emptyArray(),
            ),
        )

        val result = OpenTheoryPipeline.execute(request)

        val satisfiability = assertIs<OpenTheoryExecution.Satisfy>(result).result
        val assignment = assertIs<OpenTheoryResult.Sat>(satisfiability).assignment
        assertEquals(0L, assertIs<OpenTheoryAssignment.Difference>(assignment).sample.ints[0])
    }
}
