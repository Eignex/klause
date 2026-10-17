package com.eignex.klause.bench.profile

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.lang.classfile.ClassFile
import java.lang.constant.ClassDesc
import java.lang.constant.ConstantDescs
import java.lang.constant.MethodTypeDesc
import java.lang.invoke.MethodHandles
import java.lang.reflect.AccessFlag
import java.lang.reflect.InvocationTargetException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class LpScopeObservationAgentTest {
    @Test
    fun `transformation should preserve boolean returns`() {
        val type = fixture("Boolean")
        val instance = type.getConstructor().newInstance()
        val method = type.getMethod("push", Boolean::class.javaPrimitiveType)

        for (value in listOf(false, true)) assertEquals(value, method.invoke(instance, value))
    }

    @Test
    fun `transformation should preserve nullable reference returns`() {
        val type = fixture("Reference")
        val instance = type.getConstructor().newInstance()
        val method = type.getMethod("solve", Any::class.java)
        val value = Any()

        assertSame(value, method.invoke(instance, value))
        assertNull(method.invoke(instance, null))
    }

    @Test
    fun `transformation should preserve void returns`() {
        val type = fixture("Void")

        assertNull(type.getMethod("publish").invoke(type.getConstructor().newInstance()))
    }

    @Test
    fun `transformation should propagate the original throwable`() {
        val type = fixture("Throw")
        val failure = IllegalStateException("original failure")

        val actual = assertFailsWith<InvocationTargetException> {
            type.getMethod("close", Throwable::class.java).invoke(type.getConstructor().newInstance(), failure)
        }

        assertSame(failure, actual.cause)
    }

    @Test
    fun `compiled targets should have complete method and field schemas`() {
        val loader = javaClass.classLoader
        val schemas = LpScopeObservationAgent.targetMethods().map { (name, selected) ->
            val bytes = checkNotNull(loader.getResourceAsStream("$name.class")).use { it.readBytes() }
            val before = LpScopeObservationAgent.schema(bytes, selected)

            val rewritten = LpScopeObservationAgent.rewrite(bytes, selected, loader)

            assertEquals(selected.size, before.size)
            assertEquals(before, LpScopeObservationAgent.schema(rewritten, selected))
            assertNotEquals(LpScopeObservationAgent.sha(bytes), LpScopeObservationAgent.sha(rewritten))
            mapOf(
                "class" to name,
                "beforeSha256" to LpScopeObservationAgent.sha(bytes),
                "afterSha256" to LpScopeObservationAgent.sha(rewritten),
                "methods" to before,
            )
        }
        val fields = LpScopeObservationAgent.fieldSchema(loader)
        assertEquals(9, fields.size)
        assertEquals(
            listOf("matrix:java.util.List", "rows:java.util.List", "columns:java.util.List"),
            fields["com.eignex.klause.lp.engine.ExactLpModel"],
        )
        println(
            "LP_SCOPE_COMPILED_SCHEMA=" + LpScopeObservationAgent.json(mapOf("classes" to schemas, "fields" to fields)),
        )
    }

    @Test
    fun `replacement transformation should preserve both private boolean returns`() {
        val type = fixture("Replace")
        val method = type.declaredMethods.single { it.name == "replace" }.apply { isAccessible = true }
        val instance = type.getConstructor().newInstance()

        for (value in listOf(false, true)) assertEquals(value, method.invoke(instance, null, null, value, null))
    }

    @ParameterizedTest
    @ValueSource(strings = ["ReplacePublic", "ReplaceDescriptor"])
    fun `replacement schema should reject unexpected visibility or descriptors`(suffix: String) {
        assertFailsWith<IllegalArgumentException> { fixture(suffix) }
    }

    private fun fixture(suffix: String): Class<*> {
        val type = ClassDesc.of("com.eignex.klause.bench.profile.ScopeFixture$suffix")
        val objectType = ConstantDescs.CD_Object
        val voidType = ConstantDescs.CD_void
        val methodFlags = AccessFlag.PUBLIC.mask()
        val bytes = ClassFile.of().build(type) { builder ->
            builder.withFlags(methodFlags or AccessFlag.SUPER.mask())
            builder.withMethodBody("<init>", MethodTypeDesc.of(voidType), methodFlags) {
                it.aload(0).invokespecial(objectType, "<init>", MethodTypeDesc.of(voidType)).return_()
            }
            if (suffix.startsWith("Replace")) {
                builder.withMethodBody(
                    "replace",
                    MethodTypeDesc.of(
                        ConstantDescs.CD_boolean,
                        if (suffix == "ReplaceDescriptor") objectType else ClassDesc.of("com.eignex.klause.lp.engine.LpBoundTrail"),
                        ClassDesc.of("com.eignex.klause.util.Cancellation"),
                        ConstantDescs.CD_boolean,
                        ClassDesc.of("com.eignex.klause.lp.engine.LpLayoutRemap"),
                    ),
                    if (suffix == "ReplacePublic") methodFlags else AccessFlag.PRIVATE.mask(),
                ) { it.iload(3).ireturn() }
            }
            builder.withMethodBody(
                "push",
                MethodTypeDesc.of(ConstantDescs.CD_boolean, ConstantDescs.CD_boolean),
                methodFlags,
            ) {
                it.iload(1).ireturn()
            }
            builder.withMethodBody("solve", MethodTypeDesc.of(objectType, objectType), methodFlags) {
                it.aload(1).areturn()
            }
            builder.withMethodBody("publish", MethodTypeDesc.of(voidType), methodFlags) { it.return_() }
            builder.withMethodBody(
                "close",
                MethodTypeDesc.of(voidType, ClassDesc.of("java.lang.Throwable")),
                methodFlags,
            ) {
                it.aload(1).athrow()
            }
        }
        val selected = if (suffix.startsWith("Replace")) {
            mapOf("replace" to LpScopeObservation.Method.REPLACE)
        } else mapOf(
            "push" to LpScopeObservation.Method.PUSH,
            "solve" to LpScopeObservation.Method.SOLVE,
            "publish" to LpScopeObservation.Method.PUBLISH,
            "close" to LpScopeObservation.Method.CLOSE,
        )
        val rewritten = LpScopeObservationAgent.rewrite(bytes, selected, javaClass.classLoader)
        return MethodHandles.lookup().defineClass(rewritten)
    }
}
