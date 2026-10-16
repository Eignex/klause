package com.eignex.klause.bench.profile

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CompilationStudyPlanTest {
    @Test
    fun `arm order alternates by original input ordinal repetition and offset`() {
        val plan = CompilationStudyPlan("profile", listOf(0, 1), 2, 30000, listOf("cp"), true)

        assertEquals(listOf("control", "candidate"), plan.armOrder(0, 0))
        assertEquals(listOf("candidate", "control"), plan.armOrder(1, 0))
        assertEquals(listOf("candidate", "control"), plan.armOrder(0, 1))
        assertEquals(plan.armOrder(0, 1), plan.copy(orderOffset = 1).armOrder(0, 0))
    }

    @Test
    fun `distinct recording and control modes do not overwrite each other`() {
        val inputs = listOf(CompilationStudyInput("s", "p", "OPB", mapOf("model" to "a".repeat(64))))
        val profile = CompilationStudyPlan("profile", listOf(0), 1, 30000, listOf("cp", "ls"), true)

        CompilationStudyPlan.validate(inputs, listOf(profile, profile.copy(mode = "cold", profile = false)))

        assertEquals("profile-00-0-ls-control", profile.caseName(0, 0, "ls", "control"))
    }

    @Test
    fun `a suffix that aliases engine cases is rejected before capture`() {
        val inputs = listOf(CompilationStudyInput("s", "p", "OPB", mapOf("model" to "a".repeat(64))))
        val plan = CompilationStudyPlan("profile", listOf(0), 1, 30000, listOf("cp", "ls"), true,
            caseSuffix = "same")

        assertFailsWith<IllegalArgumentException> { CompilationStudyPlan.validate(inputs, listOf(plan)) }
    }

    @Test
    fun `duplicate original identities are rejected rather than reweighted`() {
        val input = CompilationStudyInput("s", "p", "OPB", mapOf("model" to "a".repeat(64)))
        val plan = CompilationStudyPlan("profile", listOf(0, 1), 1, 30000, listOf("cp"), true)

        assertFailsWith<IllegalArgumentException> { CompilationStudyPlan.validate(listOf(input, input), listOf(plan)) }
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "abc", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"])
    fun `every original input requires a complete lowercase source digest`(digest: String) {
        val inputs = listOf(CompilationStudyInput("s", "p", "OPB", mapOf("model" to digest)))
        val plan = CompilationStudyPlan("profile", listOf(0), 1, 30000, listOf("cp"), true)

        assertFailsWith<IllegalArgumentException> { CompilationStudyPlan.validate(inputs, listOf(plan)) }
    }

    @ParameterizedTest
    @ValueSource(ints = [-1, 0, 101])
    fun `repetition bounds reject omitted or unbounded measurement plans`(repeats: Int) {
        val inputs = listOf(CompilationStudyInput("s", "p", "OPB", mapOf("model" to "a".repeat(64))))
        val plan = CompilationStudyPlan("profile", listOf(0), repeats, 30000, listOf("cp"), true)

        assertFailsWith<IllegalArgumentException> { CompilationStudyPlan.validate(inputs, listOf(plan)) }
    }

    @ParameterizedTest
    @ValueSource(strings = ["source-validation=false", "source-validation=true"])
    fun `plans cannot override the fixed original source validation setting`(parameter: String) {
        val inputs = listOf(CompilationStudyInput("s", "p", "OPB", mapOf("model" to "a".repeat(64))))
        val plan = CompilationStudyPlan("profile", listOf(0), 1, 30000, listOf("cp"), true,
            params = listOf(parameter))

        assertFailsWith<IllegalArgumentException> { CompilationStudyPlan.validate(inputs, listOf(plan)) }
    }
}
