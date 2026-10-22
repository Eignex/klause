package com.eignex.klause.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CliDebugStatsTest {
    @Test
    fun `ordinary statistics should not initialize runtime instrumentation`() {
        val common = CommonOptions().apply { statistics = true }

        common.startDebugStatistics(false) { error("debug collector initialized") }

        assertNull(common.debugStats)
    }

    @Test
    fun `debug flag should enable statistics and initialize the collector`() {
        val common = CommonOptions()
        parseArgs(arrayOf("--debug"), commonFlagSpecs(common)) {}
        val collector = CliDebugStats { null }

        common.startDebugStatistics(false) { collector }

        assertTrue(common.statistics)
        assertSame(collector, common.debugStats)
    }

    @Test
    fun `environment debug setting should enable instrumentation without a flag`() {
        val common = CommonOptions()
        val collector = CliDebugStats { null }

        common.startDebugStatistics(true) { collector }

        assertTrue(common.statistics)
        assertSame(collector, common.debugStats)
    }

    @Test
    fun `compilation statistics should separate startup from the measured invocation`() {
        var compilationMs = 24L
        val collector = CliDebugStats { compilationMs }
        compilationMs = 310L

        val pairs = collector.pairs().toMap()

        assertEquals("true", pairs["jvmCompilationSupported"])
        assertEquals("24", pairs["jvmCompilationStartupMs"])
        assertEquals("286", pairs["jvmCompilationMs"])
    }

    @Test
    fun `unsupported compilation monitoring should omit numeric counters`() {
        val collector = CliDebugStats { null }

        val pairs = collector.pairs().toMap()

        assertEquals("true", pairs["runtimeDebug"])
        assertEquals("false", pairs["jvmCompilationSupported"])
        assertFalse("jvmCompilationStartupMs" in pairs)
        assertFalse("jvmCompilationMs" in pairs)
    }
}
