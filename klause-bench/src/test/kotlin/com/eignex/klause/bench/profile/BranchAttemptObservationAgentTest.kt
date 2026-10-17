package com.eignex.klause.bench.profile

import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.solver.search.BooleanBranching
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.SearchCandidateHints
import com.eignex.klause.solver.search.SearchComponent
import com.eignex.klause.solver.search.SearchContext
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.SearchSession
import com.eignex.klause.theory.qflra.ExactLiraSearchComponent
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.lang.classfile.ClassFile
import java.lang.constant.ClassDesc
import java.lang.constant.ConstantDescs
import java.lang.constant.MethodTypeDesc
import java.lang.reflect.AccessFlag
import java.lang.reflect.InvocationTargetException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class BranchAttemptObservationAgentTest {
    @Test
    fun `the compiled target should have one private method and one unchanged push site`() {
        val target = BranchAttemptObservationAgent.targetMethods().entries.single()
        val bytes = requireNotNull(javaClass.classLoader.getResourceAsStream(target.key + ".class")).use {
            it.readBytes()
        }
        val schema = BranchAttemptObservationAgent.schema(bytes, target.value)

        val rewritten = BranchAttemptObservationAgent.rewrite(bytes, target.value, javaClass.classLoader)

        assertEquals(1, schema.size)
        assertEquals(schema, BranchAttemptObservationAgent.schema(rewritten, target.value))
        assertNotEquals(BranchAttemptObservationAgent.sha(bytes), BranchAttemptObservationAgent.sha(rewritten))
        val fields = BranchAttemptObservationAgent.fieldSchema(javaClass.classLoader)
        assertEquals(6, fields.size)
        assertEquals(9, fields.values.sumOf { it.size })
        println("BRANCH_ATTEMPT_COMPILED_SCHEMA=" + BranchAttemptObservationAgent.json(mapOf(
            "class" to target.key, "methods" to schema, "beforeSha256" to BranchAttemptObservationAgent.sha(bytes),
            "afterSha256" to BranchAttemptObservationAgent.sha(rewritten), "fields" to fields)))
    }

    @Test
    fun `the injected observer should preserve the original push argument and return`() {
        val decisions = ArrayList<SearchDecision>()
        val component = object : SearchComponent {
            override fun assert(decision: SearchDecision, context: SearchContext): ComponentResult {
                decisions += decision
                return ComponentResult.Consistent
            }
        }
        Bridge.session = SearchSession(listOf(component))
        assertIs<ComponentResult.Consistent>(Bridge.session!!.initialize())
        val decision = SearchDecision.Bool(0)
        Bridge.decision = decision
        val type = fixture()
        val method = type.getDeclaredMethod("advanceFrame").apply { isAccessible = true }
        val recorder = BranchAttemptObservation()
        val observation = BranchAttemptObservationAgent.javaClass.getDeclaredField("observation")
            .apply { isAccessible = true }
        observation.set(null, recorder)

        val returned = try {
            method.invoke(type.getConstructor().newInstance())
        } finally {
            observation.set(null, null)
        }

        assertNull(returned)
        assertSame(decision, decisions.single())
        assertEquals("INCONCLUSIVE", recorder.snapshot()["status"])
    }

    @Test
    fun `the injected observer should preserve an original push exception`() {
        val failure = IllegalStateException("original push")
        val component = object : SearchComponent {
            override fun assert(decision: SearchDecision, context: SearchContext): ComponentResult = throw failure
        }
        Bridge.session = SearchSession(listOf(component))
        assertIs<ComponentResult.Consistent>(Bridge.session!!.initialize())
        Bridge.decision = SearchDecision.Bool(0)
        val type = fixture()
        val method = type.getDeclaredMethod("advanceFrame").apply { isAccessible = true }
        val recorder = BranchAttemptObservation()
        val observation = BranchAttemptObservationAgent.javaClass.getDeclaredField("observation")
            .apply { isAccessible = true }
        observation.set(null, recorder)

        val actual = try {
            assertFailsWith<InvocationTargetException> { method.invoke(type.getConstructor().newInstance()) }
        } finally {
            observation.set(null, null)
        }

        assertSame(failure, actual.cause)
        assertEquals("INCONCLUSIVE", recorder.snapshot()["status"])
    }

    @ParameterizedTest
    @ValueSource(strings = ["missing", "duplicate", "public", "static", "descriptor", "class"])
    fun `unexpected target shape should refuse transformation`(shape: String) {
        assertFailsWith<IllegalArgumentException> { fixture(shape) }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `raw source order and its hint wrapper should classify selected dependency roles`(hinted: Boolean) {
        val source = Problem(numBoolVars = 2,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(1), null, null),
            factors = arrayOf(ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.LE, 0)))
        ExactLiraSearchComponent(source).use { component ->
            val session = SearchSession(listOf(component))
            assertIs<ComponentResult.Consistent>(session.initialize())
            val run = session.openRun(2,
                candidateHints = if (hinted) SearchCandidateHints { true } else SearchCandidateHints.None)
            val recorder = BranchAttemptObservation()

            BranchAttemptObservationAgent.record(recorder, session, SearchDecision.Bool(0), run)
            BranchAttemptObservationAgent.record(recorder, session, SearchDecision.Bool(3), run)

            assertEquals("COMPLETE", recorder.snapshot()["status"])
            val counts = recorder.snapshot()["counts"] as Map<*, *>
            assertEquals(1L, counts["ARITHMETIC_DEPENDENCY"])
            assertEquals(1L, counts["NON_ARITHMETIC"])
        }
    }

    @Test
    fun `a missing arithmetic owner should withhold branch role qualification`() {
        val session = SearchSession(emptyList())
        val recorder = BranchAttemptObservation()

        BranchAttemptObservationAgent.record(recorder, session, SearchDecision.Bool(0), session.openRun(1))

        assertEquals("INCONCLUSIVE", recorder.snapshot()["status"])
    }

    @ParameterizedTest
    @ValueSource(strings = ["ambiguous", "context", "assigned", "mode", "width"])
    fun `incompatible live metadata should withhold branch role qualification`(shape: String) {
        val source = Problem(numBoolVars = 2,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(1), null, null),
            factors = arrayOf(ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.LE, 0)))
        ExactLiraSearchComponent(source).use { component ->
            val initialized = SearchSession(listOf(component))
            assertIs<ComponentResult.Consistent>(initialized.initialize())
            val session = when (shape) {
                "ambiguous" -> SearchSession(listOf(component, component))
                "context" -> SearchSession(listOf(component))
                else -> initialized
            }
            if (shape == "assigned") {
                assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(0)))
            }
            val width = if (shape == "width") 3 else 2
            val branching = if (shape == "mode") BooleanBranching.None else BooleanBranching.SourceOrder(width)
            val run = session.openRun(width, booleanBranching = branching)
            val recorder = BranchAttemptObservation()

            BranchAttemptObservationAgent.record(recorder, session, SearchDecision.Bool(0), run)

            assertEquals("INCONCLUSIVE", recorder.snapshot()["status"])
        }
    }

    @Test
    fun `a helper schema failure should be contained within inconclusive metadata`() {
        val recorder = BranchAttemptObservation()

        BranchAttemptObservationAgent.record(recorder, Any(), Any(), Any())

        assertEquals("INCONCLUSIVE", recorder.snapshot()["status"])
        assertTrue(recorder.disabled)
    }

    @Test
    fun `an arbitrary set override should be refused without executing membership`() {
        var called = false
        val set = object : HashSet<Int>() {
            override fun contains(element: Int): Boolean {
                called = true
                error("arbitrary membership")
            }
        }

        assertFalse(BranchAttemptObservationAgent.trustedSet(set))
        assertFalse(called)
        assertTrue(BranchAttemptObservationAgent.trustedSet(linkedSetOf(1, 2)))
        assertTrue(BranchAttemptObservationAgent.trustedSet(setOf(1)))
    }

    private fun fixture(shape: String = "valid"): Class<*> {
        val name = "com.eignex.klause.solver.search.SearchRun"
        val type = ClassDesc.of(if (shape == "class") name + "Other" else name)
        val session = ClassDesc.of(SearchSession::class.java.name)
        val decision = ClassDesc.of(SearchDecision::class.java.name)
        val bridge = ClassDesc.of(Bridge::class.java.name)
        val result = ClassDesc.of("com.eignex.klause.solver.search.ComponentResult")
        val advance = ClassDesc.of("com.eignex.klause.solver.search.SearchRun\$Advance")
        val flags = (if (shape == "public") AccessFlag.PUBLIC else AccessFlag.PRIVATE).mask() or
            (if (shape == "static") AccessFlag.STATIC.mask() else 0)
        val bytes = ClassFile.of().build(type) { builder ->
            builder.withFlags(AccessFlag.PUBLIC.mask() or AccessFlag.SUPER.mask())
            builder.withMethodBody("<init>", MethodTypeDesc.of(ConstantDescs.CD_void), AccessFlag.PUBLIC.mask()) {
                it.aload(0).invokespecial(ConstantDescs.CD_Object, "<init>",
                    MethodTypeDesc.of(ConstantDescs.CD_void)).return_()
            }
            builder.withMethodBody("advanceFrame", MethodTypeDesc.of(
                if (shape == "descriptor") ConstantDescs.CD_Object else advance), flags) { code ->
                repeat(if (shape == "missing") 0 else if (shape == "duplicate") 2 else 1) {
                    code.getstatic(bridge, "session", session).getstatic(bridge, "decision", decision)
                        .invokevirtual(session, "push", MethodTypeDesc.of(result, decision)).pop()
                }
                code.aconst_null().areturn()
            }
        }
        val selected = BranchAttemptObservationAgent.targetMethods().values.single()
        val rewritten = BranchAttemptObservationAgent.rewrite(bytes, selected, javaClass.classLoader)
        return object : ClassLoader(javaClass.classLoader) {
            fun define(): Class<*> = defineClass(name, rewritten, 0, rewritten.size)
        }.define()
    }

    object Bridge {
        @JvmField var session: SearchSession? = null
        @JvmField var decision: SearchDecision? = null
    }
}
