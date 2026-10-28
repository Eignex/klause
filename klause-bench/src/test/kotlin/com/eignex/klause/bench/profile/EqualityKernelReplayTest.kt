package com.eignex.klause.bench.profile

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.VarRemap
import com.eignex.klause.localsearch.LocalSearchModel
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.Cancellation
import java.io.File
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class EqualityKernelReplayTest {
    @TempDir
    lateinit var directory: File

    @Test
    fun `a complete kernel move is applied and the checked center is restored`() {
        val problem = Problem(0, 2, Array(2) { IntDomain(0, 2) }, listOf(
            Linear(longArrayOf(1, -1), intArrayOf(0, 1), LinearOp.EQ, 0)))
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(0))
        val objective = LinearObjective(intCoefficients = longArrayOf(-1, 0))
        val view = TrajectoryView("kernel", state, VarRemap(intArrayOf(), intArrayOf(0, 1)), objective)
        val center = Sample(booleanArrayOf(), longArrayOf(0, 0))
        assertEquals(null, view.load(center))
        val source = problem.bake()
        val file = directory.resolve("kernel.json")

        EqualityKernelReplay.capture(view, file, objective::evaluateExact) { sample, stop ->
            FiniteConflictSelection.checkSource(source, sample, stop)
        }

        val records = Json.parseToJsonElement(file.readText()).jsonObject.getValue("records").jsonArray
        for (record in records) {
            val point = record.jsonObject.getValue("consumed").jsonObject
            assertEquals("-2", point.getValue("sourceObjective").jsonPrimitive.content)
            assertEquals("true", point.getValue("nativeSourceCheck").jsonPrimitive.content)
        }
        assertEquals(2, records.size)
        assertEquals(center, state.assignment.snapshot())
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 1])
    fun `a pin rejects the complete direction`(pinned: Int) {
        val problem = Problem(0, 2, Array(2) { IntDomain(0, 2) }, listOf(
            Linear(longArrayOf(1, -1), intArrayOf(0, 1), LinearOp.EQ, 0)))
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(0),
            Assumptions(ints = mapOf(pinned to 0L)))
        val objective = LinearObjective(intCoefficients = longArrayOf(-1, 0))
        val view = TrajectoryView("pinned", state, VarRemap(intArrayOf(), intArrayOf(0, 1)), objective)
        assertEquals(null, view.load(Sample(booleanArrayOf(), longArrayOf(0, 0))))
        val file = directory.resolve("pinned.json")

        EqualityKernelReplay.capture(view, file, objective::evaluateExact) { _, _ -> true }

        val records = Json.parseToJsonElement(file.readText()).jsonObject.getValue("records").jsonArray
        assertTrue(records.all { it.jsonObject.getValue("consumed") == JsonNull })
        assertTrue(records.flatMap { it.jsonObject.getValue("directions").jsonArray }.all {
            it.jsonObject.getValue("candidates").jsonArray.isEmpty()
        })
    }

    @Test
    fun `a foreign owner cannot leave a sink trimmed partial direction`() {
        val problem = Problem(0, 2, Array(2) { IntDomain(0, 2) }, listOf(
            Linear(longArrayOf(1, -1), intArrayOf(0, 1), LinearOp.EQ, 0)))
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(0))
        val objective = LinearObjective(intCoefficients = longArrayOf(-1, 0))
        val view = TrajectoryView("owned", state, VarRemap(intArrayOf(), intArrayOf(0, 1)), objective,
            owners = intArrayOf(0, -1))
        assertEquals(null, view.load(Sample(booleanArrayOf(), longArrayOf(0, 0))))
        val file = directory.resolve("owned.json")

        EqualityKernelReplay.capture(view, file, objective::evaluateExact) { _, _ -> true }

        val records = Json.parseToJsonElement(file.readText()).jsonObject.getValue("records").jsonArray
        assertTrue(records.all { it.jsonObject.getValue("consumed") == JsonNull })
        assertTrue(records.flatMap { it.jsonObject.getValue("directions").jsonArray }.all {
            it.jsonObject.getValue("candidates").jsonArray.isEmpty()
        })
    }

    @Test
    fun `domain holes exclude the unit step while admitting its complete endpoint`() {
        val problem = Problem(0, 2, Array(2) { IntDomain(0, 2).excludeValue(1) }, listOf(
            Linear(longArrayOf(1, -1), intArrayOf(0, 1), LinearOp.EQ, 0)))
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(0))
        val objective = LinearObjective(intCoefficients = longArrayOf(-1, 0))
        val view = TrajectoryView("holes", state, VarRemap(intArrayOf(), intArrayOf(0, 1)), objective)
        assertEquals(null, view.load(Sample(booleanArrayOf(), longArrayOf(0, 0))))
        val source = problem.bake()
        val file = directory.resolve("holes.json")

        EqualityKernelReplay.capture(view, file, objective::evaluateExact) { sample, stop ->
            FiniteConflictSelection.checkSource(source, sample, stop)
        }

        val records = Json.parseToJsonElement(file.readText()).jsonObject.getValue("records").jsonArray
        assertTrue(records.flatMap { it.jsonObject.getValue("directions").jsonArray }.flatMap {
            it.jsonObject.getValue("candidates").jsonArray
        }.all { it.jsonObject.getValue("step").jsonPrimitive.content.removePrefix("-") == "2" })
        assertTrue(records.any { it.jsonObject.getValue("consumed") != JsonNull })
    }

    @Test
    fun `an equality preserving move must still satisfy every source factor`() {
        val problem = Problem(0, 2, Array(2) { IntDomain(0, 2) }, listOf(
            Linear(longArrayOf(1, -1), intArrayOf(0, 1), LinearOp.EQ, 0),
            Linear(longArrayOf(1), intArrayOf(0), LinearOp.LE, 0)))
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(0))
        val objective = LinearObjective(intCoefficients = longArrayOf(-1, 0))
        val view = TrajectoryView("retained", state, VarRemap(intArrayOf(), intArrayOf(0, 1)), objective)
        assertEquals(null, view.load(Sample(booleanArrayOf(), longArrayOf(0, 0))))
        val source = problem.bake()
        val file = directory.resolve("retained.json")

        EqualityKernelReplay.capture(view, file, objective::evaluateExact) { sample, stop ->
            FiniteConflictSelection.checkSource(source, sample, stop)
        }

        val records = Json.parseToJsonElement(file.readText()).jsonObject.getValue("records").jsonArray
        assertTrue(records.all { it.jsonObject.getValue("consumed") == JsonNull })
    }

    @Test
    fun `step arithmetic can span more than a signed word without wrapping targets`() {
        val problem = Problem(0, 2, Array(2) { IntDomain(Long.MIN_VALUE + 1, Long.MAX_VALUE) }, listOf(
            Linear(longArrayOf(1, 1), intArrayOf(0, 1), LinearOp.EQ, 0)))
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(0))
        val objective = LinearObjective(intCoefficients = longArrayOf(-1, 0))
        val view = TrajectoryView("wide", state, VarRemap(intArrayOf(), intArrayOf(0, 1)), objective)
        assertEquals(null, view.load(Sample(booleanArrayOf(), longArrayOf(Long.MIN_VALUE + 1, Long.MAX_VALUE))))
        val source = problem.bake()
        val file = directory.resolve("wide.json")

        EqualityKernelReplay.capture(view, file, objective::evaluateExact) { sample, stop ->
            FiniteConflictSelection.checkSource(source, sample, stop)
        }

        val records = Json.parseToJsonElement(file.readText()).jsonObject.getValue("records").jsonArray
        assertTrue(records.flatMap { it.jsonObject.getValue("directions").jsonArray }.flatMap {
            it.jsonObject.getValue("candidates").jsonArray
        }.any { it.jsonObject.getValue("step").jsonPrimitive.content.removePrefix("-") == "18446744073709551614" })
        assertTrue(records.any { it.jsonObject.getValue("consumed") != JsonNull })
    }

    @Test
    fun `interrupted extraction publishes no direction or consumed point`() {
        val problem = Problem(0, 2, Array(2) { IntDomain(0, 2) }, listOf(
            Linear(longArrayOf(1, -1), intArrayOf(0, 1), LinearOp.EQ, 0)))
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(0))
        val objective = LinearObjective(intCoefficients = longArrayOf(-1, 0))
        val view = TrajectoryView("cancelled", state, VarRemap(intArrayOf(), intArrayOf(0, 1)), objective)
        assertEquals(null, view.load(Sample(booleanArrayOf(), longArrayOf(0, 0))))
        val file = directory.resolve("cancelled.json")

        EqualityKernelReplay.capture(view, file, objective::evaluateExact, Cancellation { true }) { _, _ -> true }

        val report = Json.parseToJsonElement(file.readText()).jsonObject
        assertTrue(report.getValue("records").jsonArray.isEmpty())
        assertEquals("true", report.getValue("interrupted").jsonPrimitive.content)
    }
}
