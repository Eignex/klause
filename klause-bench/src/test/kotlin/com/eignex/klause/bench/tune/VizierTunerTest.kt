package com.eignex.klause.bench.tune

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Live ask-tell round-trip against the OSS Vizier service (`klause-bench/vizier/run.sh`), driven
 * entirely through the backend-agnostic [Tuner] / [TuningStudy] seam — so this also proves nothing
 * Vizier-specific is needed to use it. The service is optional infrastructure, so the test
 * short-circuits (passes) when localhost:6789 is unreachable; the permanent guarantee is that
 * [VizierTuner] compiles against the generated stubs.
 */
class VizierTunerTest {
    @Test
    fun `ask-tell round-trip suggests, completes, and re-suggests through the Tuner seam`() {
        if (!VizierTuner.reachable()) {
            println("[skip] vizier service not reachable at localhost:6789")
            return
        }
        val space = ConfigSpace(
            listOf(
                CategoricalParam("family", listOf("cbls", "probsat", "walksat")),
                DoubleParam("noise", 0.0, 1.0),
            ),
        )
        val tuner: Tuner = VizierTuner()
        tuner.use {
            it.openStudy(space, maximize = true, studyId = "klause-smoke-test").use { study ->
                val first = study.suggest(3)
                assertTrue(first.isNotEmpty(), "expected an initial batch of suggestions")
                assertTrue(
                    first.all { s -> "family" in s.values && "noise" in s.values },
                    "every suggestion carries both params",
                )
                first.forEach { s -> study.complete(s, objective = 1.0 - abs(s.values["noise"] as Double - 0.5)) }

                val second = study.suggest(3)
                assertTrue(second.isNotEmpty(), "the bandit must keep suggesting after tell")
            }
        }
    }

    @Test
    fun `observe injects a pre-evaluated prior without the requested-trial rejection`() {
        if (!VizierTuner.reachable()) {
            println("[skip] vizier service not reachable at localhost:6789")
            return
        }
        val space = ConfigSpace(
            listOf(
                CategoricalParam("family", listOf("cbls", "probsat", "walksat")),
                DoubleParam("noise", 0.0, 1.0),
            ),
        )
        val tuner: Tuner = VizierTuner()
        tuner.use {
            it.openStudy(space, maximize = true, studyId = "klause-warmstart-test").use { study ->
                // The warm-start path: a known result goes in as a SUCCEEDED trial via CreateTrial, not
                // through suggest→complete (which rejects a non-ACTIVE trial). Must not throw.
                study.observe(mapOf("family" to "cbls", "noise" to 0.3), objective = 0.9)
                study.observe(mapOf("family" to "walksat", "noise" to 0.7), objective = 0.4)
                val suggested = study.suggest(3)
                assertTrue(suggested.isNotEmpty(), "the bandit keeps suggesting after priors are injected")
            }
        }
    }
}
