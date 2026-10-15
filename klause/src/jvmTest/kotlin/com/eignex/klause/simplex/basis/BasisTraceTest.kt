package com.eignex.klause.simplex.basis

import com.eignex.koblas.SparseMatrix
import org.junit.BeforeClass
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BasisTraceTest {
    companion object {
        @BeforeClass
        @JvmStatic
        fun warmReplayRuntime() {
            val resource = requireNotNull(
                BasisTraceTest::class.java.getResourceAsStream("/basis-corpus/smt-lia-wide-span.kbtrace"),
            )
            val trace = BasisTraceCodec.decode(resource.use { it.readBytes() })
            BasisTraceReplay.replay(trace, measureAllocations = false)
        }
    }

    @Test
    fun `codec roundtrip should preserve raw bits and own its arrays`() {
        val sourceBits = longArrayOf(1.0.toRawBits(), (-0.0).toRawBits(), 1.0.toRawBits(), 1.0.toRawBits())
        val matrix = BasisTraceMatrix(2, 4, intArrayOf(0, 2, 2, 3, 4), intArrayOf(0, 1, 0, 1), sourceBits)
        sourceBits[0] = 7.0.toRawBits()

        val decoded = BasisTraceCodec.decode(BasisTraceCodec.encode(trace(matrix)))

        assertEquals(1.0.toRawBits(), decoded.matrix.copyValueBits()[0])
        assertEquals((-0.0).toRawBits(), decoded.matrix.copyValueBits()[1])
        assertContentEquals(intArrayOf(0), (decoded.operations[1] as BasisTraceOperation.Solve).rhs.support())
        assertEquals(BasisTraceRoute.PRODUCTION_RELAXATION, decoded.metadata.route)
    }

    @Test
    fun `codec should reject truncated mismatched and oversized payloads`() {
        val encoded = BasisTraceCodec.encode(trace(simpleMatrix()))
        val wrongVersion = encoded.copyOf().also { it[11] = 2 }

        assertFailsWith<BasisTraceFormatException> { BasisTraceCodec.decode(encoded.copyOf(encoded.size - 1)) }
        assertFailsWith<BasisTraceFormatException> { BasisTraceCodec.decode(wrongVersion) }
        assertFailsWith<BasisTraceFormatException> { BasisTraceCodec.decode(ByteArray(BASIS_TRACE_MAX_PAYLOAD + 1)) }
    }

    @Test
    fun `replay should reject an update the captured trace accepted`() {
        val fixture = trace(simpleMatrix())
        val result = replay(fixture) { matrix ->
            val delegate = KotlinBasisSolver(matrix)
            object : BasisSolver by delegate {
                override fun update(
                    pivotRow: Int,
                    entering: Int,
                    spike: IndexedVector,
                    pivotEta: IndexedVector?,
                ): BasisUpdate = BasisUpdate.SINGULAR
            }
        }

        assertEquals(1, result.declinedUpdates)
        assertTrue(result.errors.any { "update acceptance" in it })
    }

    @Test
    fun `source residual should reject a distorted solve`() {
        val fixture = trace(simpleMatrix())
        val result = replay(fixture) { matrix ->
            val delegate = KotlinBasisSolver(matrix)
            object : BasisSolver by delegate {
                override fun ftran(x: IndexedVector, expectedDensity: Double) {
                    delegate.ftran(x, expectedDensity)
                    val values = x.toDoubleArray()
                    values[0] += 0.25
                    x.scatter(values)
                }
            }
        }

        assertTrue(result.errors.any { "source residual" in it })
    }

    @Test
    fun `persisted corpus traces should load and replay`() {
        for (fixture in listOf("smt-lia-wide-span", "mps-afiro")) {
            val resource = requireNotNull(javaClass.getResourceAsStream("/basis-corpus/$fixture.kbtrace"))
            val trace = BasisTraceCodec.decode(resource.use { it.readBytes() })

            val result = replay(trace)

            assertEquals(0, result.stateErrors, fixture)
            assertEquals(0, result.freshDeclines, "$fixture")
            assertTrue(result.acceptedUpdates > 0, fixture)
            assertTrue(result.freshRelativeResidual < 1e-9, fixture)
        }
    }

    private fun trace(matrix: BasisTraceMatrix): BasisTrace {
        val initial = listOf(BasisHeading.Unit(0), BasisHeading.Unit(1))
        val operations = listOf(
            BasisTraceOperation.Factorize(initial, true),
            BasisTraceOperation.Solve(
                0,
                false,
                BasisVectorRole.ENTERING_SPIKE,
                0.0.toRawBits(),
                BasisTraceVector(doubleArrayOf(1.0, 0.0), intArrayOf(0)),
            ),
            BasisTraceOperation.Solve(
                1,
                true,
                BasisVectorRole.PIVOT_ROW,
                0.0.toRawBits(),
                BasisTraceVector(doubleArrayOf(1.0, 0.0), intArrayOf(0)),
            ),
            BasisTraceOperation.Update(
                0,
                BasisHeading.Source(0),
                1,
                2,
                BasisTraceVector(doubleArrayOf(1.0, 0.0), intArrayOf(0)),
                BasisTraceVector(doubleArrayOf(1.0, 0.0), intArrayOf(0)),
                BasisUpdate.APPLIED,
            ),
            BasisTraceOperation.Solve(
                2,
                false,
                BasisVectorRole.GENERAL,
                0.0.toRawBits(),
                BasisTraceVector(doubleArrayOf(2.0, 3.0), intArrayOf(0, 1)),
            ),
        )
        return BasisTrace(
            metadata(),
            matrix,
            2,
            listOf(
                origin(BasisColumnKind.INTEGER, 0),
                origin(BasisColumnKind.AUXILIARY, -1),
                origin(BasisColumnKind.SYNTHESIZED_UNIT, 0),
                origin(BasisColumnKind.SYNTHESIZED_UNIT, 1),
            ),
            booleanArrayOf(false, true),
            operations,
        )
    }

    private fun replay(
        trace: BasisTrace,
        factory: (SparseMatrix) -> BasisSolver = ::KotlinBasisSolver,
    ): BasisReplayReport = BasisTraceReplay.replay(trace, factory, measureAllocations = false)

    private fun simpleMatrix() = BasisTraceMatrix(
        2,
        4,
        intArrayOf(0, 1, 2, 3, 4),
        intArrayOf(0, 1, 0, 1),
        longArrayOf(1.0.toRawBits(), 1.0.toRawBits(), 1.0.toRawBits(), 1.0.toRawBits()),
    )

    private fun origin(kind: BasisColumnKind, source: Int) = BasisColumnOrigin(
        kind,
        source,
        1,
        0.0.toRawBits(),
        1.0.toRawBits(),
        0.0.toRawBits(),
    )

    private fun metadata() = BasisTraceMetadata(
        "unit",
        BasisTraceFormat.MPS,
        BasisTraceRoute.PRODUCTION_RELAXATION,
        "unit.mps",
        "internal",
        "0".repeat(64),
        "1".repeat(40),
        "test",
        "bounded",
        "none",
        "2".repeat(64),
        0,
        "completed",
        false,
    )
}
