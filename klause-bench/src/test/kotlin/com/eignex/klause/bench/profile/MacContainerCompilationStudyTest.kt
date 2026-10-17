package com.eignex.klause.bench.profile

import java.io.File
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MacContainerCompilationStudyTest {
    private val study = listOf("selection=https://example.test/selection.json", "sha256=${"a".repeat(64)}",
        "control=${"b".repeat(40)}", "candidate=${"c".repeat(40)}", "plan=https://example.test/plan.json",
        "plan-sha256=${"d".repeat(64)}")
    private val nonce = "00000000-0000-4000-8000-000000000001"

    @Test
    fun `the wrapper preserves the complete ordered study request`() {
        val options = MacContainerCompilationStudy.parse((study + "endpoint=unix:///owned/docker.sock").toTypedArray())

        assertEquals(study, options.studyArguments)
        assertEquals("unix:///owned/docker.sock", options.endpoint)
    }

    @ParameterizedTest
    @ValueSource(strings = ["endpoint=tcp://remote:2375", "candidate=main", "image=mutable", "families=unpaired"])
    fun `duplicate unknown remote and incomplete options fail before daemon access`(extra: String) {
        val arguments = study + "endpoint=unix:///owned/docker.sock" + extra

        assertFailsWith<IllegalArgumentException> { MacContainerCompilationStudy.parse(arguments.toTypedArray()) }
    }

    @Test
    fun `the guest receives pinned source identity and readonly observer distributions`() {
        val options = MacContainerStudyOptions("unix:///owned/docker.sock", study)
        val driver = "e".repeat(40)

        val arguments = MacContainerCompilationStudy.createArguments("klause-compilation-study-$nonce", nonce,
            "sha256:" + "f".repeat(64), driver, File("/owned/work"), File("/owned/out"), File("/owned/run.sh"),
            File("/owned/cli"), File("/owned/bench"), listOf(File("/shared/collection")), options)

        assertEquals(study, arguments.takeLast(study.size))
        assertTrue(driver in arguments)
        assertEquals("never", arguments[arguments.indexOf("--pull") + 1])
        assertEquals("0,1", arguments[arguments.indexOf("--cpuset-cpus") + 1])
        assertTrue("type=bind,src=/owned/cli,dst=/observer-cli,readonly" in arguments)
        assertTrue("type=bind,src=/owned/bench,dst=/observer-bench,readonly" in arguments)
        assertTrue("org.klause.compilation-study.owner=$nonce" in arguments)
        assertTrue("type=bind,src=/shared/collection,dst=/work/corpus/collection,readonly" in arguments)
        assertFalse("--privileged" in arguments)
    }

    @ParameterizedTest
    @ValueSource(strings = ["relative", "/owned/comma,name", "/owned/space name"])
    fun `ambiguous writable mount paths cannot change guest arguments`(path: String) {
        assertFailsWith<IllegalArgumentException> {
            MacContainerCompilationStudy.createArguments("klause-compilation-study-$nonce", nonce,
                "sha256:" + "f".repeat(64), "e".repeat(40), File(path), File("/owned/out"), File("/owned/run.sh"),
                File("/owned/cli"), File("/owned/bench"), emptyList(),
                MacContainerStudyOptions("unix:///owned/docker.sock", study))
        }
    }

    @Test
    fun `a preexisting image needs no removal to complete an owned study`() {
        val result = MacContainerStudyCompletion(true, true, true, false, false, true, null, null, null)

        assertTrue(result.completed)
        assertFalse(result.acceptedSavingsCredit)
        assertFalse(result.sourceOutcomesValidated)
    }

    @ParameterizedTest
    @ValueSource(strings = ["study", "container", "image", "staging", "cleanup"])
    fun `a workload or owned resource cleanup failure cannot report completion`(failure: String) {
        val result = MacContainerStudyCompletion(true, true, failure != "container", true, failure != "image",
            failure != "staging", if (failure == "study") "failed" else null,
            if (failure == "cleanup") "failed" else null, null)

        assertFalse(result.completed)
        assertFalse(result.sourceOutcomesValidated)
        assertFalse(result.originalLinuxMeasurementsTransferable)
        assertFalse(result.physicalMacCpuAffinityProven)
        assertFalse(result.acceptedSavingsCredit)
    }
}
