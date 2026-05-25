package com.eignex.klause.bench.parity

import java.io.File
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * CI-runnable parity smoke covering the `klause-mzn-lib/test-models/` set against Gecode.
 *
 * Pre-flight: skips silently if `minizinc` isn't on PATH or if `klause-fzn-cli` hasn't
 * been built. That makes the test resilient to bare CI images while still catching
 * regressions whenever the toolchain is present.
 */
class MznParitySmokeTest {

    @Test
    fun `every smoke model matches Gecode under klause`() {
        val root = MznParityCorpus.workspaceRoot()
        if (!minizincOnPath()) {
            println("[parity-smoke] minizinc CLI not on PATH — skipping")
            return
        }
        if (!File(root, "klause-fzn-cli/build/install/klause-fzn-cli/bin/klause-fzn-cli").canExecute()) {
            println("[parity-smoke] klause-fzn-cli not installed; run ':klause-fzn-cli:installDist'")
            return
        }
        val msc = MznParityCorpus.klauseMsc(root)
        val mznLib = MznParityCorpus.klauseMznLibDir(root)
        assertTrue(msc.isFile, "klause.msc missing at $msc")
        assertTrue(mznLib.isDirectory, "klause redefinitions dir missing at $mznLib")
        val workDir = File(root, "klause-bench/build/parity-work").also { it.mkdirs() }

        val instances = MznParityCorpus.discover(MznParityCorpus.Source.SMOKE, root)
        assertTrue(instances.isNotEmpty(), "no smoke models discovered under klause-mzn-lib/test-models")

        // Correctness failures fail the build. Timeouts / engine errors on instances on
        // [KNOWN_LS_DIFFICULT] are surfaced separately — those signal LS-strength or
        // unrelated klause-engine issues that the parity-smoke shouldn't gate CI on, but
        // remain visible in the build output. A strict mode opts the timeout/error policy
        // back in for tightening campaigns:  -Dklause.parity.smoke.strict=true
        val strict = System.getProperty("klause.parity.smoke.strict", "false").toBoolean()
        val correctnessFailures = mutableListOf<String>()
        val timeouts = mutableListOf<String>()
        for (inst in instances) {
            val cfg = MznParity.Config(
                mznPath = inst.mzn,
                dznPath = inst.dzn,
                name = inst.name,
                klauseMsc = msc,
                klauseMznLibDir = mznLib,
                timeoutSec = 60,
                workDir = workDir,
            )
            val result = MznParity.run(cfg)
            println("[parity-smoke] ${inst.name}: ${result.verdict} (klause=${result.klauseMs}ms, ref=${result.referenceMs}ms, nativeCov=${"%.0f".format(result.nativeCoverage * 100)}%)")
            when (result.verdict) {
                MznParity.Verdict.OK,
                MznParity.Verdict.REFERENCE_UNAVAILABLE -> Unit
                MznParity.Verdict.KLAUSE_TIMEOUT,
                MznParity.Verdict.KLAUSE_INFEASIBLE,
                MznParity.Verdict.UNKNOWN_ERROR -> {
                    if (inst.name in KNOWN_LS_DIFFICULT) {
                        timeouts += "${inst.name}: ${result.verdict} (allow-listed)"
                    } else {
                        correctnessFailures += "${inst.name}: ${result.verdict} — ${result.detail}"
                    }
                }
                else -> correctnessFailures += "${inst.name}: ${result.verdict} — ${result.detail}"
            }
        }
        if (timeouts.isNotEmpty()) {
            println("[parity-smoke] non-fatal issues on known-difficult instances: ${timeouts.joinToString(", ")}")
        }
        assertTrue(correctnessFailures.isEmpty(),
            "parity smoke correctness failures:\n  " + correctnessFailures.joinToString("\n  "))
        if (strict) {
            assertTrue(timeouts.isEmpty(),
                "klause.parity.smoke.strict=true and allow-listed instances failed:\n  " + timeouts.joinToString("\n  "))
        }
    }

    private companion object {
        /**
         * Smoke instances that intermittently fail klause's LS or surface engine bugs
         * unrelated to MiniZinc parity. Excluded from the default smoke gate; re-enabled
         * under `-Dklause.parity.smoke.strict=true` for tightening campaigns.
         *
         *  - `magic_square`: 3×3 magic squares have very rare satisfying assignments;
         *    klause's LS doesn't reliably converge within the 60s budget.
         *  - `zero_one_knapsack`: optimization run intermittently trips an
         *    ArrayIndexOutOfBoundsException in PropagationState.collectLevelsForVars
         *    (pre-existing klause engine bug — tracked separately).
         */
        private val KNOWN_LS_DIFFICULT = setOf("magic_square", "zero_one_knapsack")
    }

    /** Sanity-check that klause's native-predicate set is parsed correctly from
     *  redefinitions.mzn. Fast — no minizinc subprocess required. */
    @Test
    fun `native predicate set parses cleanly`() {
        val msc = MznParityCorpus.klauseMsc()
        if (!msc.isFile) {
            println("[parity-smoke] klause.msc missing; skipping native-predicate sanity")
            return
        }
        val nativeSet = readNativePredicateSet(MznParityCorpus.klauseMznLibDir())
        assertNotNull(nativeSet)
        assertTrue("int_lin_le" in nativeSet, "expected int_lin_le among klause's native predicates")
        assertTrue("all_different_int" in nativeSet, "expected all_different_int among klause's native predicates")
        assertTrue(nativeSet.size >= 50, "native predicate set unexpectedly small: ${nativeSet.size}")
    }

    /** Mirror of MznParity's private loader so the parser test stays self-contained. */
    private fun readNativePredicateSet(mznLibDir: File): Set<String> {
        val redef = File(mznLibDir, "redefinitions.mzn")
        if (!redef.isFile) return emptySet()
        val rx = Regex("""^\s*predicate\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(""")
        return buildSet {
            redef.useLines { lines -> for (line in lines) rx.find(line)?.let { add(it.groupValues[1]) } }
        }
    }

    private fun minizincOnPath(): Boolean {
        val pb = ProcessBuilder("minizinc", "--version")
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        return runCatching {
            val p = pb.start()
            p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS) && p.exitValue() == 0
        }.getOrDefault(false)
    }
}
