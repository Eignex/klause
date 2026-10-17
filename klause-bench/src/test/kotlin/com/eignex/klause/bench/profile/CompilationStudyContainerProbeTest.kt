package com.eignex.klause.bench.profile

import java.io.File
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CompilationStudyContainerProbeTest {
    @Test
    fun `an inventory request does not select a guest image`() {
        val options = CompilationStudyContainerProbe.parse(arrayOf("endpoint=unix:///owned/docker.sock"))

        assertEquals(ContainerProbeOptions("unix:///owned/docker.sock", null), options)
    }

    @ParameterizedTest
    @ValueSource(strings = ["tcp://remote:2375", "ssh://remote", "unix://remote/docker.sock", "unix:relative.sock",
        "unix:///owned/../docker.sock", "unix:///owned/docker.sock?x", "unix:///owned/docker.sock#x",
        "unix:///owned/a%20b.sock", "unix:///owned/space name.sock"])
    fun `only literal normalized local socket endpoints are accepted`(endpoint: String) {
        assertFailsWith<IllegalArgumentException> {
            CompilationStudyContainerProbe.parse(arrayOf("endpoint=$endpoint"))
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["latest", "example:25",
        "example@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        "sha256:abc", "sha256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"])
    fun `guest images require full installed content IDs`(image: String) {
        assertFailsWith<IllegalArgumentException> {
            CompilationStudyContainerProbe.parse(arrayOf("endpoint=unix:///owned/docker.sock", "image=$image"))
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["endpoint=unix:///other/docker.sock", "context=other", "image", "unknown=x"])
    fun `duplicate incomplete and unknown options fail before daemon access`(extra: String) {
        assertFailsWith<IllegalArgumentException> {
            CompilationStudyContainerProbe.parse(arrayOf("endpoint=unix:///owned/docker.sock", extra))
        }
    }

    @Test
    fun `a pinned guest gets one readonly nonce mount and cannot pull an image`() {
        val image = "sha256:" + "a".repeat(64)
        val arguments = CompilationStudyContainerProbe.guestArguments(image, File("/owned/nonce"), File("/owned/cid"))

        assertEquals("never", arguments[arguments.indexOf("--pull") + 1])
        assertEquals("none", arguments[arguments.indexOf("--network") + 1])
        assertEquals("0", arguments[arguments.indexOf("--cpuset-cpus") + 1])
        assertTrue("--read-only" in arguments)
        assertEquals(1, arguments.count { it == "--mount" })
        assertEquals("type=bind,src=/owned/nonce,dst=/probe-nonce,readonly",
            arguments[arguments.indexOf("--mount") + 1])
        assertEquals("/owned/cid", arguments[arguments.indexOf("--cidfile") + 1])
        assertTrue(image in arguments)
    }

    @ParameterizedTest
    @ValueSource(strings = ["relative", "/owned/comma,name", "/owned/space name"])
    fun `ambiguous mount and container receipt paths are rejected`(path: String) {
        assertFailsWith<IllegalArgumentException> {
            CompilationStudyContainerProbe.guestArguments("sha256:" + "a".repeat(64), File(path), File("/owned/cid"))
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `completion distinguishes inventory from a requested guest probe`(guestRequested: Boolean) {
        val result = ContainerProbeCompletion(true, guestRequested, false, true, null)

        assertEquals(!guestRequested, result.completed)
        assertFalse(result.physicalMacCpuAffinityProven)
        assertFalse(result.originalLinuxMeasurementsTransferable)
        assertFalse(result.fullCompilationStudyProtocolProven)
        assertFalse(result.acceptedSavingsCredit)
    }

    @ParameterizedTest
    @ValueSource(strings = ["inventory", "staging", "command"])
    fun `failed inventory command or owned staging cleanup prevents completion`(failure: String) {
        val result = ContainerProbeCompletion(failure != "inventory", false, false, failure != "staging",
            if (failure == "command") "failed command" else null)

        assertFalse(result.completed)
    }
}
