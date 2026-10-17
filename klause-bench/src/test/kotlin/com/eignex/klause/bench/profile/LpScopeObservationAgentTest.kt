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
import kotlin.test.assertNotNull
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

    @ParameterizedTest
    @ValueSource(strings = [
        "com/eignex/klause/lp/engine/LpScopedSolver",
        "com/eignex/klause/lp/bounding/LpPropagator",
        "com/eignex/klause/lp/engine/ExactPointFeasibleKt",
    ])
    fun `compiled targets should have complete method and field schemas`(name: String) {
        val loader = javaClass.classLoader
        val selected = checkNotNull(LpScopeObservationAgent.targetMethods()[name])
        val bytes = checkNotNull(loader.getResourceAsStream("$name.class")).use { it.readBytes() }
        val before = LpScopeObservationAgent.schema(bytes, selected)

        val rewritten = LpScopeObservationAgent.rewrite(bytes, selected, loader)

        assertEquals(selected.size, before.size)
        assertEquals(before, LpScopeObservationAgent.schema(rewritten, selected))
        assertNotEquals(LpScopeObservationAgent.sha(bytes), LpScopeObservationAgent.sha(rewritten))
        val schemas = listOf(mapOf(
            "class" to name,
            "beforeSha256" to LpScopeObservationAgent.sha(bytes),
            "afterSha256" to LpScopeObservationAgent.sha(rewritten),
            "methods" to before,
        ))
        val fields = LpScopeObservationAgent.fieldSchema(loader)
        assertEquals(14, fields.size)
        assertEquals(
            listOf("matrix:java.util.List", "rows:java.util.List", "columns:java.util.List"),
            fields["com.eignex.klause.lp.engine.ExactLpModel"],
        )
        assertEquals(listOf("optimal:boolean"), fields["com.eignex.klause.lp.engine.FloatLpResult"])
        for (type in listOf(
            "ReconstructionMetrics", "ExactPointRecovery", "LpRefinementMetrics", "ExactBasisMetrics",
        )) {
            val metadata = checkNotNull(fields["com.eignex.klause.lp.engine.$type"])
            assertEquals(1, metadata.count { it.startsWith("decline:") })
            assertEquals(false, metadata.any { it.startsWith("operations:") || it.startsWith("elapsed:") })
        }
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

    @ParameterizedTest
    @ValueSource(ints = [0, 1, 2])
    fun `static transformation should preserve nullable reference returns`(mode: Int) {
        val type = fixture("StaticNullable$mode")
        val method = type.declaredMethods.single { it.name == "scanPoint" }.apply { isAccessible = true }
        val input = when (mode) {
            0 -> null
            1 -> emptyList<Any>()
            else -> listOf(Any())
        }

        val result = method.invoke(null, null, input, null)

        if (mode < 2) assertNull(result) else assertNotNull(result)
    }

    @Test
    fun `static transformation should propagate the original throwable`() {
        val type = fixture("StaticThrow")
        val method = type.declaredMethods.single { it.name == "scanPoint" }.apply { isAccessible = true }
        val failure = IllegalStateException("original static failure")

        val actual = assertFailsWith<InvocationTargetException> {
            method.invoke(null, null, listOf(failure), null)
        }

        assertSame(failure, actual.cause)
    }

    @ParameterizedTest
    @ValueSource(strings = ["StaticPublic", "StaticDescriptor", "StaticLine"])
    fun `static schema should reject changed visibility descriptors and return mappings`(suffix: String) {
        assertFailsWith<IllegalArgumentException> { fixture(suffix) }
    }

    @Test
    fun `static schema should reject an instance helper`() {
        assertFailsWith<NoSuchElementException> { fixture("StaticInstance") }
    }

    private fun fixture(suffix: String): Class<*> {
        val packageName = if (suffix.startsWith("Static")) "com.eignex.klause.lp.engine" else
            "com.eignex.klause.bench.profile"
        val type = ClassDesc.of("$packageName.ScopeFixture$suffix")
        val objectType = ConstantDescs.CD_Object
        val voidType = ConstantDescs.CD_void
        val methodFlags = AccessFlag.PUBLIC.mask()
        val bytes = ClassFile.of().build(type) { builder ->
            builder.withFlags(methodFlags or AccessFlag.SUPER.mask())
            builder.withMethodBody("<init>", MethodTypeDesc.of(voidType), methodFlags) {
                it.aload(0).invokespecial(objectType, "<init>", MethodTypeDesc.of(voidType)).return_()
            }
            if (suffix.startsWith("Static")) {
                val privateFlags = if (suffix == "StaticPublic") AccessFlag.PUBLIC.mask() else AccessFlag.PRIVATE.mask()
                val flags = privateFlags or if (suffix == "StaticInstance") 0 else AccessFlag.STATIC.mask()
                val pointType = ClassDesc.of("com.eignex.klause.lp.engine.PointScan")
                val listType = ClassDesc.of("java.util.List")
                val arrayList = ClassDesc.of("java.util.ArrayList")
                builder.withMethodBody(
                    "scanPoint",
                    MethodTypeDesc.of(
                        pointType,
                        if (suffix == "StaticDescriptor") objectType else
                            ClassDesc.of("com.eignex.klause.lp.engine.LpModel"),
                        listType,
                        ClassDesc.of("com.eignex.klause.lp.engine.RefinementMeter"),
                    ),
                    flags,
                ) { code ->
                    val input = code.parameterSlot(1)
                    val present = code.newLabel()
                    val populated = code.newLabel()
                    code.aload(input).ifnonnull(present)
                    code.lineNumber(if (suffix == "StaticLine") 154 else 153).aconst_null().areturn()
                    code.labelBinding(present).aload(input)
                        .invokeinterface(listType, "isEmpty", MethodTypeDesc.of(ConstantDescs.CD_boolean))
                        .ifeq(populated)
                    code.lineNumber(186).aconst_null().areturn()
                    code.labelBinding(populated)
                    if (suffix == "StaticThrow") {
                        val success = code.newLabel()
                        val slot = 3
                        val throwable = ClassDesc.of("java.lang.Throwable")
                        code.aload(input).loadConstant(0)
                            .invokeinterface(listType, "get", MethodTypeDesc.of(objectType, ConstantDescs.CD_int))
                            .astore(slot).aload(slot).ifnull(success)
                        code.aload(slot).checkcast(throwable).athrow()
                        code.labelBinding(success)
                    }
                    code.lineNumber(191).new_(pointType).dup()
                    repeat(3) {
                        code.new_(arrayList).dup().invokespecial(arrayList, "<init>", MethodTypeDesc.of(voidType))
                    }
                    code.invokespecial(pointType, "<init>", MethodTypeDesc.of(voidType, listType, listType, listType))
                        .areturn()
                }
            }
            if (suffix.startsWith("Replace")) {
                builder.withMethodBody(
                    "replace",
                    MethodTypeDesc.of(
                        ConstantDescs.CD_boolean,
                        if (suffix == "ReplaceDescriptor") objectType else
                            ClassDesc.of("com.eignex.klause.lp.engine.LpBoundTrail"),
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
        val selected = if (suffix.startsWith("Static")) {
            mapOf("scanPoint" to LpScopeObservation.Method.SCAN_POINT)
        } else if (suffix.startsWith("Replace")) {
            mapOf("replace" to LpScopeObservation.Method.REPLACE)
        } else mapOf(
            "push" to LpScopeObservation.Method.PUSH,
            "solve" to LpScopeObservation.Method.SOLVE,
            "publish" to LpScopeObservation.Method.PUBLISH,
            "close" to LpScopeObservation.Method.CLOSE,
        )
        val rewritten = LpScopeObservationAgent.rewrite(bytes, selected, javaClass.classLoader)
        val lookup = if (suffix.startsWith("Static")) {
            MethodHandles.privateLookupIn(
                Class.forName("com.eignex.klause.lp.engine.LpScopedSolver", false, javaClass.classLoader),
                MethodHandles.lookup(),
            )
        } else MethodHandles.lookup()
        return lookup.defineClass(rewritten)
    }
}
