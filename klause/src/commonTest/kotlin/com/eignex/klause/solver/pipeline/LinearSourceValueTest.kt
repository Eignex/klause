package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.solver.evidence.SourceObjective
import com.eignex.klause.solver.evidence.SourceProofContext
import com.eignex.klause.solver.evidence.SourceRevision
import com.eignex.klause.solver.evidence.SourceScope
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.parseBigInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LinearSourceValueTest {
    private val scope = SourceScope(SourceRevision("value", "exact-affine"),
        objective = SourceObjective("cost", ObjectiveSense.MINIMIZE))

    @Test
    fun `source values retain non dyadic coefficients and coordinate authority`() {
        val real = SourceCoordinate(SourceCoordinate.Kind.Real, 0)
        val third = BigFraction.ofLong(3).reciprocal()
        val source = SourceProofContext(scope, setOf(real), emptyMap(), mapOf(real to third))
        val sample = Sample(booleanArrayOf(), longArrayOf(), doubleArrayOf(1.0 / 3.0), exactReals = listOf(third))

        val value = source.linearValue(sample, Cancellation.Never)

        assertEquals(BigFraction.ofLong(9).reciprocal(), value)
    }

    @Test
    fun `wide integer source values do not project through long or double`() {
        val integer = SourceCoordinate(SourceCoordinate.Kind.Integer, 0)
        val source = SourceProofContext(scope, setOf(integer), emptyMap(), mapOf(integer to BigFraction.ofLong(7)),
            BigFraction.MINUS_ONE)
        val sample = Sample(booleanArrayOf(), longArrayOf(), exactInts = listOf(parseBigInt("18446744073709551616")))

        val value = source.linearValue(sample, Cancellation.Never)

        assertEquals(BigFraction.of(parseBigInt("129127208515966861311"), BIG_ONE), value)
    }

    @Test
    fun `approximate coordinates cannot manufacture an exact source value`() {
        val real = SourceCoordinate(SourceCoordinate.Kind.Real, 0)
        val source = SourceProofContext(scope, setOf(real), emptyMap(), mapOf(real to BigFraction.ONE))
        val sample = Sample(booleanArrayOf(), longArrayOf(), doubleArrayOf(1.0 / 3.0))

        val value = source.linearValue(sample, Cancellation.Never)

        assertNull(value)
    }

    @Test
    fun `missing affine objective capture cannot become a zero value`() {
        val source = SourceProofContext(scope, emptySet(), emptyMap())
        val sample = Sample(booleanArrayOf(), longArrayOf())

        val value = source.linearValue(sample, Cancellation.Never)

        assertNull(value)
    }

    @Test
    fun `cancelled evaluation cannot publish a constant objective value`() {
        val source = SourceProofContext(scope, emptySet(), emptyMap(), emptyMap())
        val sample = Sample(booleanArrayOf(), longArrayOf())

        val value = source.linearValue(sample, Cancellation { true })

        assertNull(value)
    }
}
