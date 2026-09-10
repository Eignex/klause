package com.eignex.klause.simplex.basis

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BasisTraceTest {
    @Test
    fun `codec roundtrip preserves raw bits and owns arrays`() {
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
    fun `codec rejects truncated mismatched and oversized payloads`() {
        val encoded = BasisTraceCodec.encode(trace(simpleMatrix()))
        val wrongVersion = encoded.copyOf().also { it[11] = 2 }

        assertFailsWith<BasisTraceFormatException> { BasisTraceCodec.decode(encoded.copyOf(encoded.size - 1)) }
        assertFailsWith<BasisTraceFormatException> { BasisTraceCodec.decode(wrongVersion) }
        assertFailsWith<BasisTraceFormatException> { BasisTraceCodec.decode(ByteArray(BASIS_TRACE_MAX_PAYLOAD + 1)) }
    }

    @Test
    fun `codec rejects an update whose preparation is not portable`() {
        val original = trace(simpleMatrix())
        val operations = original.operations.toMutableList()
        val update = operations[3] as BasisTraceOperation.Update
        operations[3] = BasisTraceOperation.Update(
            update.leavingSlot,
            update.entering,
            99,
            update.pivotOperation,
            update.spikeEvidence,
            update.pivotEvidence,
            update.outcome,
        )

        assertFailsWith<BasisTraceFormatException> {
            BasisTraceCodec.encode(
                BasisTrace(
                    original.metadata,
                    original.matrix,
                    original.sourceColumns,
                    original.origins,
                    original.rowStrict(),
                    operations,
                ),
            )
        }
    }

    @Test
    fun `replay stops both arms when an update diverges`() {
        val fixture = trace(simpleMatrix())
        val result = BasisTraceReplay.replay(
            fixture,
            customFactory = ::KotlinBasisSolver,
            referenceFactory = { matrix ->
                val delegate = KotlinBasisSolver(matrix)
                object : BasisSolver by delegate {
                    override fun update(
                        pivotRow: Int,
                        entering: Int,
                        spike: IndexedVector,
                        pivotEta: IndexedVector?,
                    ): BasisUpdate = BasisUpdate.SINGULAR
                }
            },
        )

        assertTrue(result.custom.stateErrors > 0)
        assertTrue(result.hfactor.stateErrors > 0)
    }

    @Test
    fun `independent source residual rejects a distorted solve`() {
        val fixture = trace(simpleMatrix())
        val result = BasisTraceReplay.replay(
            fixture,
            customFactory = { matrix ->
                val delegate = KotlinBasisSolver(matrix)
                object : BasisSolver by delegate {
                    override fun ftran(x: IndexedVector, expectedDensity: Double) {
                        delegate.ftran(x, expectedDensity)
                        val values = x.toDoubleArray()
                        values[0] += 0.25
                        x.scatter(values)
                    }
                }
            },
            referenceFactory = ::KotlinBasisSolver,
        )

        assertTrue(result.custom.errors.any { "source residual" in it })
        assertTrue(result.hfactor.errors.none { "source residual" in it })
        assertEquals(0, result.hfactor.ftrans)
        assertEquals(0, result.hfactor.btrans)
    }

    @Test
    fun `nonfinite solve output fails replay`() {
        val fixture = trace(simpleMatrix())
        val result = BasisTraceReplay.replay(
            fixture,
            customFactory = { matrix ->
                val delegate = KotlinBasisSolver(matrix)
                object : BasisSolver by delegate {
                    override fun ftran(x: IndexedVector, expectedDensity: Double) {
                        delegate.ftran(x, expectedDensity)
                        val values = x.toDoubleArray()
                        values[0] = Double.NaN
                        x.scatter(values)
                    }
                }
            },
            referenceFactory = ::KotlinBasisSolver,
        )

        assertTrue(result.custom.errors.any { "nonfinite" in it })
        assertTrue(result.custom.absoluteResidual.isFinite())
    }

    @Test
    fun `persisted real corpus trace loads and replays`() {
        val resource = requireNotNull(javaClass.getResourceAsStream("/basis-corpus/smt-lia-wide-span.kbtrace"))
        val trace = BasisTraceCodec.decode(resource.use { it.readBytes() })

        val result = BasisTraceReplay.replay(trace, referenceFactory = ::KotlinBasisSolver)

        assertEquals(BasisTraceFormat.SMTLIB, trace.metadata.format)
        assertEquals(0, result.custom.stateErrors)
        assertEquals(0, result.hfactor.stateErrors)
    }

    @Test
    fun `persisted production trace retains source shifts`() {
        val resource = requireNotNull(javaClass.getResourceAsStream("/basis-corpus/mzn-graph-coloring.kbtrace"))
        val trace = BasisTraceCodec.decode(resource.use { it.readBytes() })

        assertEquals(1.0, Double.fromBits(requireNotNull(trace.origins.first().lowerBits)))
        assertEquals(6.0, Double.fromBits(requireNotNull(trace.origins.first().upperBits)))
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
