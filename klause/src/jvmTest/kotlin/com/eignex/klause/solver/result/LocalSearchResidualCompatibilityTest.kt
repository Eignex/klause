package com.eignex.klause.solver.result

import kotlin.test.Test
import kotlin.test.assertEquals

class LocalSearchResidualCompatibilityTest {
    @Test
    fun `hint usage legacy constructor and default descriptors retain absent construction values`() {
        val type = OpenHintUsageStats::class.java
        val marker = Class.forName("kotlin.jvm.internal.DefaultConstructorMarker")
        assertEquals(OpenHintUsageStats(), type.getConstructor().newInstance())
        for (count in listOf(19, 22)) {
            val longs = Array(count) { Long::class.javaPrimitiveType }
            val direct = type.getConstructor(*longs).newInstance(*Array<Any>(count) { 0L })
            val defaulted = type.getConstructor(*longs, Int::class.javaPrimitiveType, marker)
                .newInstance(*Array<Any?>(count) { 7L }, (1 shl count) - 1, null)

            assertEquals(OpenHintUsageStats(), direct)
            assertEquals(OpenHintUsageStats(), defaulted)
            for (index in 1..count) assertEquals(0L, type.getMethod("component$index").invoke(direct))
            for (index in 23..30) assertEquals(null, type.getMethod("component$index").invoke(defaulted))
        }
    }

    @Test
    fun `hint usage legacy copy descriptors retain every omitted observation`() {
        val sink = OpenHintUsageSink()
        sink.pointUnavailable()
        sink.variableUncovered()
        sink.primitiveUnavailable()
        sink.sourceDimensions(9, 7, 5)
        sink.rawConstruction(3, 11, 7, 4, 19)
        val stats = sink.snapshot()
        val type = OpenHintUsageStats::class.java
        for (count in listOf(19, 22)) {
            val longs = Array(count) { Long::class.javaPrimitiveType }
            val values = Array<Any>(count) { type.getMethod("component${it + 1}").invoke(stats) }
                .also { it[1] = 2L }
            val ignored = Array<Any>(count) { 7L }.also { it[1] = 2L }

            val direct = type.getMethod("copy", *longs).invoke(stats, *values)
            val defaulted = type.getMethod("copy\$default", type, *longs,
                Int::class.javaPrimitiveType, Any::class.java)
                .invoke(null, stats, *ignored, ((1 shl count) - 1) xor 2, null)

            assertEquals(stats.copy(indexesAdmitted = 2), direct)
            assertEquals(direct, defaulted)
            assertEquals(stats.rawLedgerDebits, (defaulted as OpenHintUsageStats).rawLedgerDebits)
            assertEquals(stats.rawVariablesMax, defaulted.rawVariablesMax)
            assertEquals(stats.sourceClausesMax, defaulted.sourceClausesMax)
            assertEquals(stats.earlyPointUnavailable, defaulted.earlyPointUnavailable)
            assertEquals(stats.forwardUncovered, defaulted.forwardUncovered)
            assertEquals(stats.primitiveProviderNone, defaulted.primitiveProviderNone)
        }
    }

    @Test
    fun `legacy constructor descriptors create coordinate observations without details`() {
        val type = LocalSearchResidualFactor::class.java
        val marker = Class.forName("kotlin.jvm.internal.DefaultConstructorMarker")
        val parameters = arrayOf(Int::class.javaPrimitiveType, String::class.java, Long::class.javaPrimitiveType,
            Map::class.java, Map::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)

        val direct = type.getConstructor(*parameters).newInstance(3, "Linear", 5L, emptyMap<Int, Boolean>(),
            mapOf(4 to 2L), 1, 2)
        val defaulted = type.getConstructor(*parameters, Int::class.javaPrimitiveType, marker)
            .newInstance(3, "Linear", 5L, emptyMap<Int, Boolean>(), mapOf(4 to 2L), 0, 0, 96, null)

        assertEquals(LocalSearchResidualFactor(3, "Linear", 5L, emptyMap(), mapOf(4 to 2L), 1, 2), direct)
        assertEquals(LocalSearchResidualFactor(3, "Linear", 5L, emptyMap(), mapOf(4 to 2L)), defaulted)
    }

    @Test
    fun `legacy copy descriptors retain captured details`() {
        val factor = LocalSearchResidualFactor(3, "Linear", 5L, emptyMap(), mapOf(4 to 2L),
            details = mapOf("activity" to "-6"))
        val type = LocalSearchResidualFactor::class.java
        val parameters = arrayOf(Int::class.javaPrimitiveType, String::class.java, Long::class.javaPrimitiveType,
            Map::class.java, Map::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)

        val direct = type.getMethod("copy", *parameters)
            .invoke(factor, 4, factor.kind, factor.degree, factor.bools, factor.ints, 0, 0)
        val defaulted = type.getMethod("copy\$default", type, *parameters,
            Int::class.javaPrimitiveType, Any::class.java)
            .invoke(null, factor, 4, null, 0L, null, null, 0, 0, 126, null)

        assertEquals(factor.copy(id = 4), direct)
        assertEquals(factor.copy(id = 4), defaulted)
        assertEquals(mapOf("activity" to "-6"), (defaulted as LocalSearchResidualFactor).details)
    }
}
