package com.eignex.klause.bench.profile

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CliLpScopeCaptureTest {
    @Test
    fun `requests should require an original hash and a bounded explicit budget`() {
        val valid = arrayOf("suite=smtlib-qflia", "problem=original", "sha256=${"a".repeat(64)}", "timeout=10000", "seed=1")
        val invalid = listOf(valid.dropLast(1).toTypedArray(), valid + "seed=2", valid + "engine=cp",
            valid.map { if (it.startsWith("sha256=")) "sha256=wrong" else it }.toTypedArray(),
            valid.map { if (it.startsWith("timeout=")) "timeout=0" else it }.toTypedArray(),
            valid.map { if (it.startsWith("timeout=")) "timeout=60001" else it }.toTypedArray())

        for (request in invalid) assertFailsWith<IllegalArgumentException> { CliLpScopeCapture.parse(request) }
        assertEquals(10000L, CliLpScopeCapture.parse(valid).timeout)
    }

    @Test
    fun `inherited JVM overrides should not reach the child`() {
        val inherited = mapOf("JAVA_TOOL_OPTIONS" to "-javaagent:other", "JDK_JAVA_OPTIONS" to "-Xmx1g",
            "_JAVA_OPTIONS" to "-Xmx1g", "JAVA_OPTS" to "-Xmx1g", "CLASSPATH" to "other.jar",
            "KLAUSE_CLI_OPTS" to "-XX:ActiveProcessorCount=8")

        val actual = CliLpScopeCapture.childEnvironment(inherited, File("/jdk"), File("/agent.jar"), File("/agent.properties"))

        for (key in listOf("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "CLASSPATH")) {
            assertFalse(key in actual)
        }
        assertEquals("", actual["JAVA_OPTS"])
        assertEquals("/jdk", actual["JAVA_HOME"])
        assertEquals("-Xmx3g -XX:+UseSerialGC -XX:ActiveProcessorCount=1 -javaagent:/agent.jar=/agent.properties",
            actual["KLAUSE_CLI_OPTS"])
    }

    @Test
    fun `the child command should preserve the original default solve settings`() {
        val request = CliLpScopeCapture.Request("smtlib-qflia", "original", "a".repeat(64), 10000L, 1L)

        val command = CliLpScopeCapture.command(request, File("/installed/klause-cli"), File("/out/input.smt2"))

        assertEquals(listOf("/installed/klause-cli", "/out/input.smt2", "-t", "10000", "-r", "1", "-s"), command)
        assertTrue(command.none { it == "-e" || it == "--param" || it == "--exact" })
    }
}
