package com.eignex.klause.bench.profile

import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.solver.evidence.SourceProofContext
import com.eignex.klause.solver.evidence.SourceRevision
import com.eignex.klause.solver.evidence.SourceScope
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.pipeline.TheoryParams
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.numbers.BigFraction
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class JointRepairLpCaptureTest {
    companion object {
        private lateinit var solveContext: Any
        private lateinit var model: Problem
        private lateinit var context: SourceProofContext
        private lateinit var objective: LinearObjective

        @BeforeAll
        @JvmStatic
        fun initializeContext() {
            solveContext = Class.forName("com.eignex.klause.lp.engine.LpSolveContext").getConstructor().newInstance()
            NumericalResidualProfile.call(solveContext, "getNumericalDeductions")
            model = Problem.of(0, 1, arrayOf(IntDomain(0, 1)), emptyList())
            val coordinate = SourceCoordinate(SourceCoordinate.Kind.Integer, 0)
            context = SourceProofContext(SourceScope(SourceRevision("capture", "exact")),
                setOf(coordinate), emptyMap(), mapOf(coordinate to BigFraction.ONE))
            objective = LinearObjective(intCoefficients = longArrayOf(1))
            JointRepairLpCapture.openOwner(model, context, objective,
                TheoryParams(sourceIncumbentCompletion = true), ExecutionControl.Never, null).close()
        }
    }

    @Test
    fun `standalone capture should open an ordinary joint owner`() {
        val checker: (Sample, BigFraction, ExecutionControl) -> Boolean = { _, _, _ -> true }

        val owner = JointRepairLpCapture.openOwner(model, context, objective,
            TheoryParams(sourceIncumbentCompletion = true), ExecutionControl.Never, checker)

        owner.use {
            val type = it.javaClass
            assertEquals(true, type.getDeclaredField("observeExternalIncumbents")
                .apply { isAccessible = true }.get(it))
            assertEquals(false, type.getDeclaredField("incumbentProximity")
                .apply { isAccessible = true }.get(it))
            assertSame(checker, type.getDeclaredField("incumbentCompletionCheck")
                .apply { isAccessible = true }.get(it))
        }
    }

    @Suppress("SpreadOperator")
    @Test
    fun `observing queries should retain the original numerical deduction policy`() {
        val defaults = solveContext
        val copy = defaults.javaClass.methods.single { it.name == "copy" }
        val arguments = Array(copy.parameterCount) { index ->
            NumericalResidualProfile.call(defaults, "component${index + 1}")
        }
        arguments[7] = true
        val original = copy.invoke(defaults, *arguments)

        val observing = JointRepairLpCapture.observingContext(original) { }

        assertEquals(true, NumericalResidualProfile.call(observing, "getNumericalDeductions"))
    }

    @Test
    fun `ray encoding should preserve exact finite bits and signed zero`() {
        val ray = doubleArrayOf(0.0, -0.0, 1.5, Double.MIN_VALUE, Double.MAX_VALUE)

        val encoded = JointRepairLpCapture.rayBits(ray, ray.size)

        assertEquals(listOf("0000000000000000", "8000000000000000", "3ff8000000000000",
            "0000000000000001", "7fefffffffffffff"), encoded)
    }

    @ParameterizedTest
    @ValueSource(strings = ["NaN", "Infinity", "-Infinity"])
    fun `nonfinite ray values should remain unavailable`(value: String) {
        val ray = doubleArrayOf(value.toDouble())

        val encoded = JointRepairLpCapture.rayBits(ray, 1)

        assertNull(encoded)
    }

    @ParameterizedTest
    @CsvSource("0,0", "2,1", "4097,4097")
    fun `unsupported ray dimensions should remain unavailable`(size: Int, rows: Int) {
        val ray = DoubleArray(size)

        val encoded = JointRepairLpCapture.rayBits(ray, rows)

        assertNull(encoded)
    }

    @Test
    fun `missing rays should remain unavailable`() {
        val encoded = JointRepairLpCapture.rayBits(null, 1)

        assertNull(encoded)
    }

    @Test
    fun `root queries and repeated conditions should preserve conditional capacity`() {
        val window = JointRepairLpCapture.ConditionWindow(2)
        val first = Any()
        val second = Any()
        val captured = ArrayList<Int>()

        window.observe(null, capture = captured::add)
        window.observe(first, capture = captured::add)
        window.observe(first, capture = captured::add)
        window.observe(second, capture = captured::add)
        window.observe(Any(), capture = captured::add)

        assertEquals(listOf(0, 1), captured)
        assertEquals(2, window.size)
    }

    @Test
    fun `equal conditions with separate identities should retain separate attempts`() {
        val window = JointRepairLpCapture.ConditionWindow(2)
        val captured = ArrayList<Int>()
        val first = String(charArrayOf('x'))
        val second = String(charArrayOf('x'))

        window.observe(first, capture = captured::add)
        window.observe(second, capture = captured::add)

        assertEquals(listOf(0, 1), captured)
    }

    @Test
    fun `changed model versions should retain a fresh snapshot within the capture cap`() {
        val window = JointRepairLpCapture.ConditionWindow(2)
        val condition = Any()
        val captured = ArrayList<Int>()

        window.observe(condition, "first", capture = captured::add)
        window.observe(condition, "first", capture = captured::add)
        window.observe(condition, "second", capture = captured::add)
        window.observe(condition, "third", capture = captured::add)

        assertEquals(listOf(0, 1), captured)
        assertEquals(2, window.size)
    }
}
