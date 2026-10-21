package com.eignex.klause.bench.profile

import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class LinearLpEqualityInspectionTest {
    @Test
    fun `exact model capture should retain origins signed coefficients strict sides and premises`() {
        val prefix = "com.eignex.klause.lp.engine."
        val numberType = Class.forName(prefix + "ExactLpNumber")
        val companion = numberType.getField("Companion").get(null)
        val of = companion.javaClass.getMethod("of", Long::class.javaPrimitiveType)
        val numbers = listOf(0L, 1L, 7L, -1L).map { of.invoke(companion, it) }
        val (zero, one, seven, negativeOne) = numbers
        val premiseType = Class.forName(prefix + "ExactLpPremise")
        val premisesType = Class.forName(prefix + "ExactLpPremises")
        val boundPremise = premiseType.getConstructor(Int::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType, numberType).newInstance(1, false, seven)
        val premises = premisesType.getConstructor(List::class.java, List::class.java)
            .newInstance(listOf(boundPremise), listOf(2, 5))
        val sideType = Class.forName(prefix + "ExactLpSide")
        val side = sideType.getConstructor(numberType, Boolean::class.javaPrimitiveType, premisesType)
        val boundsType = Class.forName(prefix + "ExactLpBounds")
        val bounds = boundsType.getConstructor(sideType, sideType)
        val open = bounds.newInstance(null, null)
        val strict = bounds.newInstance(side.newInstance(zero, true, premises), null)
        val fixed = bounds.newInstance(side.newInstance(seven, false, null), side.newInstance(seven, false, null))
        val columnType = Class.forName(prefix + "ExactLpColumn")
        val column = columnType.getConstructor(boundsType, numberType, Boolean::class.javaPrimitiveType,
            Int::class.javaPrimitiveType)
        val entryType = Class.forName(prefix + "ExactLpEntry")
        val entry = entryType.getConstructor(Int::class.javaPrimitiveType, numberType)
        val rowType = Class.forName(prefix + "ExactLpRow")
        val row = rowType.getConstructor(Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
            premisesType).newInstance(true, false, premises)
        val senseType = Class.forName(prefix + "Sense")
        val sense = senseType.enumConstants.single { it.toString() == "MINIMIZE" }
        val objectiveType = Class.forName(prefix + "ExactLpObjective")
        val objective = objectiveType.getConstructor(List::class.java, numberType, numberType, numberType,
            senseType).newInstance(listOf(zero, zero, zero), zero, one, zero, sense)
        val modelType = Class.forName(prefix + "ExactLpModel")
        val model = modelType.getConstructor(List::class.java, List::class.java, List::class.java,
            List::class.java, objectiveType).newInstance(
            listOf(listOf(entry.newInstance(0, one)), listOf(entry.newInstance(0, negativeOne))),
            listOf(zero),
            listOf(column.newInstance(strict, one, true, 17), column.newInstance(open, zero, true, 18),
                column.newInstance(fixed, zero, false, -1)),
            listOf(row),
            objective,
        )

        val captured = LinearLpEqualityInspection.captureModel(model, Cancellation.Never)

        assertEquals("true", captured.getValue("complete").jsonPrimitive.content)
        val columns = captured.getValue("columns").jsonArray
        assertEquals(3, columns.size)
        assertEquals("1", columns[0].jsonObject.getValue("origin").jsonObject["rational"]?.jsonPrimitive?.content)
        val lower = columns[0].jsonObject.getValue("lower").jsonObject
        assertEquals("true", lower.getValue("strict").jsonPrimitive.content)
        assertEquals(listOf("2", "5"), lower.getValue("premises").jsonObject.getValue("literals").jsonArray
            .map { it.jsonPrimitive.content })
        val premise = lower.getValue("premises").jsonObject.getValue("bounds").jsonArray.single().jsonObject
        assertEquals("1", premise.getValue("variable").jsonPrimitive.content)
        assertEquals("false", premise.getValue("upper").jsonPrimitive.content)
        assertEquals("7", premise.getValue("threshold").jsonObject.getValue("rational").jsonPrimitive.content)
        assertEquals(JsonNull, columns[1].jsonObject["lower"])
        assertEquals("7", columns[2].jsonObject.getValue("lower").jsonObject.getValue("value")
            .jsonObject.getValue("rational").jsonPrimitive.content)
        val coefficient = captured.getValue("matrix").jsonArray[1].jsonObject.getValue("entries")
            .jsonArray.single().jsonObject.getValue("coefficient").jsonObject
        assertEquals("-1", coefficient.getValue("rational").jsonPrimitive.content)
        assertEquals(JsonNull, coefficient["ieeeBits"])
        assertEquals("false", captured.getValue("sourceVerdictClaim").jsonPrimitive.content)
    }

    @Test
    fun `a stopped capture should return an explicit decline without partial model fields`() {
        val captured = LinearLpEqualityInspection.captureModel(Any(), Cancellation { true })

        assertEquals(setOf("complete", "decline", "sourceVerdictClaim"), captured.keys)
        assertEquals("false", captured.getValue("complete").jsonPrimitive.content)
        assertEquals("capture-deadline", captured.getValue("decline").jsonPrimitive.content)
    }
}
