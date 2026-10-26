package com.eignex.klause.bench.profile

import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class PolynomialPhaseProfileTest {
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `request encodings retain identical JSON bytes`(compressed: Boolean) {
        val json = "[{\"suite\":\"control\",\"problem\":\"constant\"}]".encodeToByteArray()
        val prefix = if (compressed) "requestz64=" else "request64="
        val bytes = if (compressed) ByteArrayOutputStream().also { out ->
            GZIPOutputStream(out).use { it.write(json) }
        }.toByteArray() else json

        val decoded = PolynomialPhaseProfile.requestBytes(prefix + Base64.getEncoder().encodeToString(bytes))

        assertContentEquals(json, decoded)
    }

    @Test
    fun `a retained source is decoded without catalog resolution`() {
        val source = "(assert false)".encodeToByteArray()
        val compressed = ByteArrayOutputStream()
        GZIPOutputStream(compressed).use { it.write(source) }

        val bytes = PolynomialPhaseProfile.capturedSource(Base64.getEncoder().encodeToString(compressed.toByteArray()))

        assertContentEquals(source, bytes)
    }

    @Test
    fun `retained source decompression respects the source byte limit`() {
        val compressed = ByteArrayOutputStream()
        GZIPOutputStream(compressed).use { it.write(ByteArray(1024 * 1024 + 1)) }

        assertFailsWith<IllegalArgumentException> {
            PolynomialPhaseProfile.capturedSource(Base64.getEncoder().encodeToString(compressed.toByteArray()))
        }
    }

    @Test
    fun `fixed integer completion retains its original coordinate map`() {
        val source = "(declare-const i Int) (declare-const x Real) (assert (= (* x x) i))"

        val trace = PolynomialPhaseProfile.PhaseAccess().measure(source, capture = true,
            fixedIntegers = mapOf("i" to "2"))

        assertEquals("Satisfiable", trace.result)
        assertEquals("0", requireNotNull(trace.sourceCoordinates)["integer.i"])
        assertEquals("2", requireNotNull(trace.failureCapture)["Scope"]?.get("condition.lower.0"))
        assertEquals(trace.nativeWork, trace.chargeSum)
    }

    @Test
    fun `pipeline dimensions distinguish declarations from product lowering coordinates`() {
        val source = "(declare-const x Real) (assert (= (* x x) 2))"

        val trace = PolynomialPhaseProfile.pipeline(source, timeoutMs = 10_000)

        val shape = requireNotNull(trace.sourceShape)
        assertEquals("POLYNOMIAL", shape.preparedKind)
        assertEquals(1, shape.declaredReals)
        assertEquals(0, shape.declaredBooleans)
        val modelReals = requireNotNull(shape.modelReals)
        assertTrue(modelReals > shape.declaredReals)
        assertEquals(0, shape.modelBooleans)
        assertTrue(requireNotNull(shape.theoryReals) >= modelReals)
        assertEquals(shape.modelBooleans, shape.theoryBooleans)
        assertEquals("Sat", trace.result)
    }

    @Test
    fun `pipeline tracing preserves a linear control without polynomial attribution`() {
        val source = "(declare-const x Real) (assert (> x 0))"

        val trace = PolynomialPhaseProfile.pipeline(source, timeoutMs = 10_000)

        val shape = requireNotNull(trace.sourceShape)
        assertEquals(1, shape.declaredReals)
        assertEquals(1, shape.modelReals)
        assertNull(shape.theoryReals)
        assertNull(shape.theoryBooleans)
        assertEquals("not a polynomial execution", trace.nativeStats)
        assertEquals("Sat", trace.result)
    }

    @Test
    fun `linear refutations retain unused declared coordinates`() {
        val source = "(declare-const x Real) (assert false)"

        val trace = PolynomialPhaseProfile.pipeline(source, timeoutMs = 10_000)

        val shape = requireNotNull(trace.sourceShape)
        assertEquals("Unsat", trace.result)
        assertEquals(1, shape.declaredReals)
        assertNull(shape.theoryReals)
        assertEquals("not a polynomial execution", trace.nativeStats)
    }

    @Test
    fun `a negated conjunction declines conjunctive guidance after normalization`() {
        val source = """
            (declare-const x Real)
            (assert (not (and (> x 0) (< (* x x) 2))))
        """.trimIndent()

        val gate = PolynomialPhaseProfile.PhaseAccess().gate(source)

        assertEquals(1, gate.realCount)
        assertEquals(2, gate.atoms)
        assertNull(gate.conjunctiveAtoms)
        assertEquals(1, gate.normalizedNodeKinds["Not"])
    }

    @Test
    fun `native work exhaustion retains its reason and exact caller charges`() {
        val source = "(declare-const x Real) (assert (> (* x x) 2))"

        val trace = PolynomialPhaseProfile.PhaseAccess().measure(source, maximumWork = 1, timeoutMs = 10_000)

        assertEquals("Unknown", trace.result)
        assertEquals("WorkLimit", trace.reason)
        assertEquals(trace.nativeWork, trace.chargeSum)
        assertNull(trace.containingDeadlineStack)
        assertTrue(trace.checkpoints.isNotEmpty())
    }

    @Test
    fun `a completed refutation retains normalization and terminal checkpoints`() {
        val trace = PolynomialPhaseProfile.PhaseAccess().measure("(assert false)")

        assertEquals("Unsatisfiable", trace.result)
        assertEquals(trace.nativeWork, trace.chargeSum)
        assertEquals("Pending", trace.checkpoints.first().result)
        assertEquals("Unsatisfiable", trace.checkpoints.last().result)
        assertTrue(trace.checkpoints.dropLast(1).all { it.result == "Pending" })
        assertFalse(requireNotNull(trace.checkpoints.last().progress).contains("firstSnapshotWork=null"))
        assertNull(trace.containingDeadlineStack)
    }
}
