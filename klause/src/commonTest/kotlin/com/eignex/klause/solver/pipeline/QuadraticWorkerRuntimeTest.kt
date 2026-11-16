package com.eignex.klause.solver.pipeline

import com.eignex.klause.arithmetic.polynomial.AlgebraicArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialInterrupted
import com.eignex.klause.arithmetic.polynomial.PolynomialResource
import com.eignex.klause.arithmetic.polynomial.PolynomialResourceLimit
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.InstructionSlicedSearch
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.result.QuadraticIncumbent
import com.eignex.klause.solver.result.QuadraticOptimizationResult
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.ExecutionControl
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class QuadraticWorkerRuntimeTest {
    @Test
    fun `closing a pending handle should prevent another slice and clean up once`() {
        val native = QuadraticNativeScope(PolynomialTheoryParams())
        val partial = QuadraticOptimizationResult.Unknown(
            null, native.reason(), ObjectiveSense.MINIMIZE, SolveStats.EMPTY,
        )
        var closed = 0
        val runtime = QuadraticWorkerRuntime(native, ObjectiveSense.MINIMIZE, { SolveStats.EMPTY }, { partial }) {
            closed++
        }
        val handle = runtime.completeHandle({ 0L }) { _, _, _ -> }

        handle.close()
        handle.close()

        assertFailsWith<IllegalStateException> { handle.runSlice(ExecutionControl.Never, Long.MAX_VALUE, 1) {} }
        assertFalse(handle.isDone)
        assertEquals(1, closed)
    }

    @Test
    fun `terminal completion should stay readable after closing the handle`() {
        val native = QuadraticNativeScope(PolynomialTheoryParams())
        val partial = QuadraticOptimizationResult.Unknown(
            null, native.reason(), ObjectiveSense.MINIMIZE, SolveStats.EMPTY,
        )
        val runtime = QuadraticWorkerRuntime(native, ObjectiveSense.MINIMIZE, { SolveStats.EMPTY }, { partial }) {}
        val handle = runtime.completeHandle({ 7L }) { _, _, _ -> throw PolynomialInterrupted() }
        val result = handle.runSlice(ExecutionControl.Never, Long.MAX_VALUE, 1) {}

        handle.close()

        assertSame(partial, handle.completion)
        assertTrue(handle.isDone)
        assertEquals(7L, handle.work)
        assertEquals(result, handle.runSlice(ExecutionControl.Never, Long.MAX_VALUE, 1) {})
    }

    @Test
    fun `a resource decline should be visible when constructing the partial result`() {
        val native = QuadraticNativeScope(PolynomialTheoryParams())
        val partial = QuadraticOptimizationResult.Unknown(
            null, native.reason(), ObjectiveSense.MINIMIZE, SolveStats.EMPTY,
        )
        val runtime = QuadraticWorkerRuntime(native, ObjectiveSense.MINIMIZE, { SolveStats.EMPTY }, {
            assertEquals(PolynomialTheoryResource.MatrixCells, native.resource)
            partial
        }) {}
        val handle = runtime.completeHandle({ native.work }) { _, _, _ ->
            throw PolynomialResourceLimit(PolynomialResource.MATRIX_CELLS)
        }

        handle.runSlice(ExecutionControl.Never, Long.MAX_VALUE, 1) {}

        assertSame(partial, handle.completion)
    }

    @Test
    fun `cancellation should mark interruption before constructing the partial result`() {
        val native = QuadraticNativeScope(PolynomialTheoryParams())
        val partial = QuadraticOptimizationResult.Unknown(null, TerminationReason.Cancelled,
            ObjectiveSense.MINIMIZE, SolveStats.EMPTY)
        lateinit var runtime: QuadraticWorkerRuntime
        runtime = QuadraticWorkerRuntime(native, ObjectiveSense.MINIMIZE, { SolveStats.EMPTY }, {
            assertTrue(runtime.interrupted)
            partial
        }) {}
        val handle = runtime.completeHandle({ 0L }) { _, _, _ -> throw CancellationException() }

        handle.runSlice(ExecutionControl.Never, Long.MAX_VALUE, 1) {}

        assertSame(partial, handle.completion)
    }

    @Test
    fun `interrupted neighborhood preparation should retain its instruction charge`() {
        val native = QuadraticNativeScope(PolynomialTheoryParams())
        val partial = QuadraticOptimizationResult.Unknown(
            null, native.reason(), ObjectiveSense.MINIMIZE, SolveStats.EMPTY,
        )
        val runtime = QuadraticWorkerRuntime(native, ObjectiveSense.MINIMIZE, { SolveStats.EMPTY }, { partial }) {}
        val handle = runtime.neighborhoodHandle(ExecutionControl.Never, { null }) {
            native.polynomial.charge(5)
            throw PolynomialInterrupted()
        }

        handle.runSlice(ExecutionControl.Never, Long.MAX_VALUE, 1) {}

        assertEquals(quadraticNativeInstructions(5), (handle as InstructionSlicedSearch).chargedInstructions)
        assertSame(partial, handle.completion)
        assertEquals(5L, native.work)
    }

    @Test
    fun `a failed publication callback should be removed before another slice`() {
        val native = QuadraticNativeScope(PolynomialTheoryParams())
        val partial = QuadraticOptimizationResult.Unknown(
            null, native.reason(), ObjectiveSense.MINIMIZE, SolveStats.EMPTY,
        )
        val runtime = QuadraticWorkerRuntime(native, ObjectiveSense.MINIMIZE, { SolveStats.EMPTY }, { partial }) {}
        val point = QuadraticIncumbent(Sample(booleanArrayOf(), longArrayOf()),
            AlgebraicArithmetic().rational(BigFraction.ZERO))
        val handle = runtime.completeHandle({ 0L }) { _, _, _ -> runtime.publish(point) }
        val failure = IllegalArgumentException("failed publication")

        val thrown = assertFailsWith<IllegalArgumentException> {
            handle.runSlice(ExecutionControl.Never, Long.MAX_VALUE, 1) { throw failure }
        }
        runtime.publish(point)
        var published = 0
        handle.runSlice(ExecutionControl.Never, Long.MAX_VALUE, 1) { published++ }

        assertSame(failure, thrown)
        assertEquals(1, published)
        assertFalse(handle.isDone)
    }

    @Test
    fun `unexpected execution failures should release the worker without creating a terminal result`() {
        val native = QuadraticNativeScope(PolynomialTheoryParams())
        val partial = QuadraticOptimizationResult.Unknown(
            null, native.reason(), ObjectiveSense.MINIMIZE, SolveStats.EMPTY,
        )
        val failure = IllegalArgumentException("failed execution")
        var closed = 0
        val runtime = QuadraticWorkerRuntime(native, ObjectiveSense.MINIMIZE, { SolveStats.EMPTY }, { partial }) {
            closed++
        }

        val thrown = assertFailsWith<IllegalArgumentException> { runtime.execute { throw failure } }

        assertSame(failure, thrown)
        assertEquals(1, closed)
        assertNull(runtime.terminal)
    }
}
