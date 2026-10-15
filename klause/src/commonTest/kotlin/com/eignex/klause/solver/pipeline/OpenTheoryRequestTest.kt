package com.eignex.klause.solver.pipeline

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.Term
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
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.Bits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OpenTheoryRequestTest {

    @Test
    fun `ordinary real optimization should certify a free improving ray`() {
        val model = Problem(
            0,
            0,
            emptyArray(),
            emptyArray(),
            numRealVars = 1,
            realLower = doubleArrayOf(Double.NEGATIVE_INFINITY),
            realUpper = doubleArrayOf(Double.POSITIVE_INFINITY),
        )
        val request = OpenTheoryRequest(model, LinearObjective(realCoefficients = doubleArrayOf(1.0)))

        val result = assertIs<OpenTheoryExecution.Optimize>(OpenTheoryPipeline.execute(request)).result

        assertIs<OpenTheoryOptimum.Unbounded>(result)
        val ray = assertIs<SourceClaim.Ray>(assertNotNull(result.linearResult?.terminal).claim)
        assertTrue(ray.direction.values.single() < BigFraction.ZERO)
    }

    @Test
    fun `ordinary requests should use the source owner exact rows and objective`() {
        val model = Problem(
            0,
            0,
            emptyArray(),
            emptyArray(),
            numRealVars = 1,
            realLower = doubleArrayOf(1.0 / 3.0),
            realUpper = doubleArrayOf(Double.POSITIVE_INFINITY),
        )
        val real = SourceCoordinate(SourceCoordinate.Kind.Real, 0)
        val third = BigFraction.ofLong(3).reciprocal()
        val context = SourceProofContext(
            SourceScope(
                SourceRevision("exact-source", "exact-affine"),
                objective = SourceObjective("cost", ObjectiveSense.MINIMIZE),
            ),
            setOf(real),
            mapOf("lower" to SourcePremise(SourceInequality(mapOf(real to BigFraction.MINUS_ONE), third.negated()))),
            mapOf(real to third),
        )
        val request = OpenTheoryRequest(model, LinearObjective(realCoefficients = doubleArrayOf(1.0 / 3.0)))
            .withLinearAuthority(context)

        val result = assertIs<OpenTheoryExecution.Optimize>(OpenTheoryPipeline.execute(request)).result

        assertEquals(third * third, assertIs<OpenTheoryOptimum.Optimal>(result).value)
        assertEquals(context.scope, assertNotNull(result.linearResult).scope)
    }

    @Test
    fun `exact declarative rows should keep constant objective authority without finite propagation`() {
        val third = BigFraction.ofLong(3).reciprocal()
        val row = ExactSearchRow.of(intArrayOf(Term.ofRealVar(0)), listOf(third), LinearOp.EQ, BigFraction.ONE)
        val model = Problem(
            0,
            0,
            emptyArray(),
            arrayOf(row),
            numRealVars = 1,
            realLower = doubleArrayOf(Double.NEGATIVE_INFINITY),
            realUpper = doubleArrayOf(Double.POSITIVE_INFINITY),
        )
        val request = OpenTheoryRequest(model, LinearObjective(constant = 7))

        val result = assertIs<OpenTheoryExecution.Optimize>(OpenTheoryPipeline.execute(request)).result

        val optimum = assertIs<OpenTheoryOptimum.Optimal>(result)
        assertEquals(BigFraction.ofLong(7), optimum.value)
        assertEquals("3", optimum.assignment.realValue(0))
    }

    @Test
    fun `preparation should read a maximized objective in minimize sense`() {
        // Reading the raw +1 coefficient as minimize would let dual fixing pin x at 0.
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

        assertEquals(7L, preparation.model.intBounds.upper(0))
    }

    private fun openBelow(): Problem {
        val openLower = Bits(1).also { it.set(0) }
        return Problem(
            numBoolVars = 1,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(7), openLower, null),
            factors = arrayOf<Factor>(Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 7)),
        )
    }

    @Test
    fun `a Boolean objective should admit source bound complete descent`() {
        val objective = LinearObjective(boolWeights = longArrayOf(1L), intCoefficients = longArrayOf(-1L))

        val request = OpenTheoryRequest(openBelow(), objective)

        val result = assertIs<OpenTheoryExecution.Optimize>(OpenTheoryPipeline.execute(request)).result

        assertEquals("-7", assertIs<OpenTheoryOptimum.Optimal>(result).value.toString())
    }

    @Test
    fun `preparation should report LP work used to close an open side`() {
        val openUpper = Bits(1).also { it.set(0) }
        // The `>= 2` row keeps dual fixing from closing the side before any LP runs.
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
}
