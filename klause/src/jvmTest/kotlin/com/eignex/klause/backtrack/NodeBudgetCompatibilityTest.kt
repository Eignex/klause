package com.eignex.klause.backtrack

import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NodeBudgetCompatibilityTest {
    @Test
    fun `published JVM constructor and accessor descriptors remain callable`() {
        val type = Class.forName("com.eignex.klause.backtrack.NodeBudget")
        val lookup = MethodHandles.publicLookup()
        val constructor = lookup.findConstructor(type, MethodType.methodType(Void.TYPE, Long::class.javaPrimitiveType))
        val limit = lookup.findVirtual(type, "getLimit", MethodType.methodType(Long::class.javaPrimitiveType))
        val spent = lookup.findVirtual(type, "getSpent", MethodType.methodType(Long::class.javaPrimitiveType))
        val exhausted = lookup.findVirtual(type, "exhausted", MethodType.methodType(Boolean::class.javaPrimitiveType))
        val budget = constructor.invokeWithArguments(5L) as NodeBudget

        budget.allowance.spendMoves(4)

        assertTrue(Modifier.isFinal(type.modifiers))
        assertEquals(5L, limit.invokeWithArguments(budget))
        assertEquals(5L, spent.invokeWithArguments(budget))
        assertEquals(true, exhausted.invokeWithArguments(budget))
    }
    @Test
    fun `historical JVM instruction conversion field remains available`() {
        val field = Class.forName("com.eignex.klause.backtrack.NodeBudgetKt")
            .getField("LS_INSTRUCTIONS_PER_WORK")

        assertTrue(Modifier.isStatic(field.modifiers))
        assertTrue(Modifier.isFinal(field.modifiers))
        assertEquals(0.8, field.getDouble(null))
    }

}
