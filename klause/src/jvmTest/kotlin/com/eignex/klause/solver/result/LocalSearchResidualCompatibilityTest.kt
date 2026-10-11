package com.eignex.klause.solver.result

import kotlin.test.Test
import kotlin.test.assertEquals

class LocalSearchResidualCompatibilityTest {
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
