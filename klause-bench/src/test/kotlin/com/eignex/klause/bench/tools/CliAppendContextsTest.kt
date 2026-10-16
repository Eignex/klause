package com.eignex.klause.bench.tools

import kotlin.test.Test
import kotlin.test.assertEquals

class CliAppendContextsTest {
    @Test
    fun `nested disequality assertions should retain their captured ancestor`() {
        val system = "com.eignex.klause.theory.qflra.LiveQfLraSystem."
        val component = "com.eignex.klause.theory.qflra.ExactLiraSearchComponent."
        val stack = listOf(
            "com.eignex.klause.lp.bounding.LpPropagator.append",
            "${system}definitionColumn", "${system}assertTerms", "${system}assertComparison",
            "${component}assertDisequalities", "${component}assertSource",
        )

        val path = CliAppendContexts.classify(stack)

        assertEquals("comparison append: observed assertDisequalities", path)
    }

    @Test
    fun `a captured source assertion should leave missing ancestors unknown`() {
        val system = "com.eignex.klause.theory.qflra.LiveQfLraSystem."
        val stack = listOf(
            "com.eignex.klause.lp.bounding.LpPropagator.append",
            "${system}definitionColumn", "${system}assertTerms", "${system}assertComparison",
            "com.eignex.klause.theory.qflra.ExactLiraSearchComponent.assertSource",
        )

        val path = CliAppendContexts.classify(stack)

        assertEquals("comparison append: observed assertSource; other ancestors unknown", path)
    }

    @Test
    fun `missing and lookalike source assertion ancestors should remain unresolved`() {
        val system = "com.eignex.klause.theory.qflra.LiveQfLraSystem."
        val prefix = listOf(
            "com.eignex.klause.lp.bounding.LpPropagator.append",
            "${system}definitionColumn", "${system}assertTerms", "${system}assertComparison",
        )
        for (suffix in listOf(emptyList(), listOf(
            "com.eignex.klause.theory.qflra.ExactLiraSearchComponent.assertSourceExtra",
            "com.eignex.klause.theory.qflra.ExactLiraSearchComponent.assertDisequalitiesExtra",
        ))) {
            val path = CliAppendContexts.classify(prefix + suffix)

            assertEquals("comparison append: source assertion ancestor not captured", path)
        }
    }

    @Test
    fun `incomplete and reordered comparison paths should retain an unresolved definition label`() {
        val system = "com.eignex.klause.theory.qflra.LiveQfLraSystem."
        for (suffix in listOf(
            listOf("${system}assertComparison"),
            listOf("${system}assertComparison", "${system}assertTerms"),
        )) {
            val stack = listOf("com.eignex.klause.lp.bounding.LpPropagator.append", "${system}definitionColumn")

            val path = CliAppendContexts.classify(stack + suffix)

            assertEquals("definition append: comparison path not captured", path)
        }
    }

    @Test
    fun `stacks without a captured append should retain their own label`() {
        for (stack in listOf(emptyList(), listOf(
            "com.eignex.klause.lp.bounding.LpPropagator.appendExtra",
            "com.eignex.klause.theory.qflra.LiveQfLraSystem.definitionColumn",
        ))) {
            val path = CliAppendContexts.classify(stack)

            assertEquals("no captured append", path)
        }
    }

    @Test
    fun `other append paths should preserve the missing definition ancestor`() {
        val path = CliAppendContexts.classify(listOf("com.eignex.klause.lp.bounding.LpPropagator.append"))

        assertEquals("append: no captured definitionColumn ancestor", path)
    }

    @Test
    fun `a stack at the frame limit should remain observable`() {
        val stack = List(256) { "java.lang.Thread.run" }
        val contexts = CliAppendContexts { 0L }

        contexts.record(stack.size) { stack }

        assertEquals(mapOf(
            "no captured append | unknown: no captured context marker" to 1L,
        ), contexts.tables[0].second)
    }

    @Test
    fun `oversized stacks should retain CPU and allocation totals without inspection`() {
        val contexts = CliAppendContexts { 0L }

        contexts.record(257) { error("oversized stack inspected") }
        contexts.record(257, 123L) { error("oversized stack inspected") }

        assertEquals(mapOf("unknown: append stack exceeds 256 frames" to 1L), contexts.tables[0].second)
        assertEquals(mapOf("unknown: append stack exceeds 256 frames" to 123L), contexts.tables[1].second)
    }

    @Test
    fun `expired analysis should retain CPU and allocation totals without inspection`() {
        var time = 0L
        val contexts = CliAppendContexts { time }
        time = 10_000_000_000L

        contexts.record(0) { error("expired stack inspected") }
        contexts.record(0, 456L) { error("expired stack inspected") }

        assertEquals(mapOf("unknown: append analysis limit" to 1L), contexts.tables[0].second)
        assertEquals(mapOf("unknown: append analysis limit" to 456L), contexts.tables[1].second)
    }

    @Test
    fun `append ancestry should preserve overlapping captured contexts`() {
        val stack = listOf(
            "com.eignex.klause.solver.pipeline.RetainedLinearQueries.check",
            "com.eignex.klause.solver.pipeline.ExactNeighborhood.advance",
        )
        val contexts = CliAppendContexts { 0L }

        contexts.record(stack.size) { stack }

        assertEquals(mapOf(
            "no captured append | multiple observed: retained-query entry + neighborhood entry" to 1L,
        ), contexts.tables[0].second)
    }
}
