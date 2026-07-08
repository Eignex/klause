package com.eignex.klause.bench.tune

import com.eignex.klause.bench.catalog.Category
import com.eignex.klause.bench.catalog.Expected
import com.eignex.klause.bench.catalog.Format
import com.eignex.klause.bench.catalog.ProblemRef
import com.eignex.klause.bench.catalog.ProblemSource
import com.eignex.klause.bench.runner.ResolvedProblem
import com.eignex.klause.formats.opb.Opb
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BoTuningTest {

    /** A COP instance from an OPB `min:` objective — parsed in-process (no MiniZinc), so the ask-tell
     *  loop runs fast and hermetically, no Vizier service. */
    private fun cop(name: String, opb: String): ResolvedProblem {
        val parsed = Opb.parse(opb)
        return ResolvedProblem(
            ref = ProblemRef(
                name,
                Format.OPB,
                ProblemSource.Vendored("test/$name"),
                Category.OPTIMIZATION,
                Expected.Unknown,
            ),
            problem = parsed.problem,
            objective = requireNotNull(parsed.objective) { "OPB test instance needs a min: objective" },
        )
    }

    /** A CSP instance from a constraints-only OPB (no `min:`) — objective null, so the loop scores it by
     *  time-to-first-feasible rather than gap-to-optimum. */
    private fun csp(name: String, opb: String): ResolvedProblem {
        val parsed = Opb.parse(opb)
        return ResolvedProblem(
            ref = ProblemRef(name, Format.OPB, ProblemSource.Vendored("test/$name"), Category.CSP, Expected.Unknown),
            problem = parsed.problem,
            objective = null,
        )
    }

    /** A deterministic [Tuner] that cycles through a fixed list of config points — so the residual-round
     *  mechanics can be asserted exactly (no RNG), and, as a third [Tuner] impl, it re-proves the seam. */
    private class CyclingTuner(private val points: List<Map<String, Any>>) : Tuner {
        private var next = 0
        override fun openStudy(space: ConfigSpace, maximize: Boolean, studyId: String, noisy: Boolean) =
            object : TuningStudy {
                override fun suggest(count: Int): List<Suggestion> =
                    List(count) { Suggestion("t$next", points[next++ % points.size]) }
                override fun complete(suggestion: Suggestion, objective: Double) = Unit
                override fun observe(values: Map<String, Any>, objective: Double) = Unit
                override fun close() = Unit
            }
        override fun close() = Unit
    }

    @Test
    fun `residual rounds pick a complement, raise coverage, and see diminishing gains`() {
        // Three arms over four instances: A and B are opposite specialists, C is the all-rounder.
        val rewards = mapOf(
            "A" to doubleArrayOf(1.0, 1.0, 0.0, 0.0),
            "B" to doubleArrayOf(0.0, 0.0, 1.0, 1.0),
            "C" to doubleArrayOf(0.6, 0.6, 0.6, 0.6),
        )
        val instances = List(4) { cop("i$it", "min: 1 x1 ;\n+1 x1 >= 0 ;\n") }
        val space = ConfigSpace(listOf(CategoricalParam("arm", listOf("A", "B", "C"))))
        val tuner = CyclingTuner(listOf(mapOf("arm" to "A"), mapOf("arm" to "B"), mapOf("arm" to "C")))

        val result = BoTuning.tune(
            space = space,
            decode = { it.getValue("arm") as String },
            reward = { instance, arm -> rewards.getValue(arm)[instances.indexOf(instance)] },
            pool = UniformPool(instances),
            tuner = tuner,
            rounds = 4,
            trials = 3,
            batch = 3,
            warmStart = true,
            studyId = "test",
            sampleSize = 4, // = pool size, so every trial sees all four instances → deterministic
        )
        val palette = result.palette

        assertTrue(palette.size >= 2, "greedy rounds keep more than one arm")
        // Round 1 (frontier 0) maximizes mean reward → the all-rounder C (0.6 > A,B's 0.5).
        assertEquals("arm=C", palette[0].label, "round 1 anchors the best-on-average arm")
        // Round 2 rewards only what C misses → a specialist, not C again.
        assertTrue(palette[1].label != palette[0].label, "round 2 picks a complement, not the anchor")
        // Coverage (mean frontier) only rises; marginal gains never grow (submodular diminishing returns).
        assertTrue(
            palette.zipWithNext().all { (a, b) -> b.cumulativeCoverage >= a.cumulativeCoverage },
            "the frontier / cumulative coverage is monotone non-decreasing",
        )
        assertTrue(
            palette.zipWithNext().all { (a, b) -> b.gain <= a.gain + 1e-9 },
            "each round's marginal gain is non-increasing",
        )
    }

    @Test
    fun `the random-tuner BO loop over real COP instances yields a non-empty palette`() {
        val instances = listOf(
            cop("opb-a", "min: 1 x1 +2 x2 +3 x3 ;\n+1 x1 +1 x2 >= 1 ;\n+1 x2 +1 x3 >= 1 ;\n"),
            cop("opb-b", "min: 3 x1 +1 x2 +1 x3 ;\n+1 x1 +1 x3 >= 1 ;\n+1 x2 +1 x3 >= 2 ;\n"),
        )
        val result =
            BoTuning.tuneBt(
                UniformPool(instances),
                RandomTuner(seed = 1),
                rounds = 3,
                trials = 4,
                batch = 2,
                budgetMs = 20,
                seed = 7,
            )

        assertTrue(result.configs.isNotEmpty(), "the loop evaluated at least one config")
        assertTrue(result.palette.isNotEmpty(), "the residual-round palette is non-empty")
        assertTrue(
            result.palette.all { it.label in result.configs.keys },
            "every palette entry is one of the evaluated configs",
        )
    }

    @Test
    fun `the BO loop tunes CSP instances via the time-to-feasible reward`() {
        val instances = listOf(
            csp("csp-a", "+1 x1 +1 x2 >= 1 ;\n"),
            csp("csp-b", "+1 x1 +1 x3 >= 1 ;\n+1 x2 +1 x3 >= 1 ;\n"),
        )
        val result =
            BoTuning.tuneBt(
                UniformPool(instances),
                RandomTuner(seed = 1),
                rounds = 2,
                trials = 4,
                batch = 2,
                budgetMs = 200,
                seed = 7,
            )

        assertTrue(result.palette.isNotEmpty(), "a CSP selection yields a palette")
        // These CSP instances are satisfiable, so a solving config earns positive coverage.
        assertTrue(result.palette.first().gain > 0.0, "a config that reaches feasibility covers instances")
    }

    @Test
    fun `coerce rounds a Double integer param and clamps to the declared domain`() {
        // A tuner may hand cbls.tabu (an IntParam in [0,20]) back as a Double; the loop must round it.
        val coerced = LocalSearchConfigSpace.coerce(
            mapOf("family" to "cbls", "cbls.tabu" to 4.7, "cbls.noise" to 0.05),
        )
        assertEquals(5, coerced["cbls.tabu"], "Double 4.7 rounds to Int 5")
        assertEquals("cbls", coerced["family"], "categorical stays a String")
        assertTrue(coerced["cbls.noise"] is Double, "double param stays Double")
    }

    @Test
    fun `each trial evaluates only its mini-batch, not the whole pool`() {
        val pool = List(8) { cop("p$it", "min: 1 x1 ;\n+1 x1 >= 0 ;\n") }
        var evalCalls = 0
        BoTuning.tune(
            space = ConfigSpace(listOf(CategoricalParam("arm", listOf("A", "B", "C")))),
            decode = { it.getValue("arm") as String },
            reward = { _, _ ->
                evalCalls++
                0.5
            },
            pool = UniformPool(pool),
            tuner = RandomTuner(seed = 3),
            rounds = 2,
            trials = 3,
            batch = 1,
            warmStart = true,
            studyId = "batch",
            sampleSize = 2,
            sampleSeed = 3,
        )
        // A full-set loop would solve pool(8) × trials(3) × rounds(2) = 48; the mini-batch caps new
        // solves at rounds × trials × sampleSize regardless of pool size.
        assertTrue(evalCalls <= 2 * 3 * 2, "evals bounded by rounds×trials×sampleSize, got $evalCalls")
        assertTrue(evalCalls < pool.size * 2 * 3, "far below a full-set sweep")
    }

    @Test
    fun `mini-batch tuning tells the study its observations are noisy`() {
        var sawNoisy: Boolean? = null
        val recording = object : Tuner {
            override fun openStudy(
                space: ConfigSpace,
                maximize: Boolean,
                studyId: String,
                noisy: Boolean,
            ): TuningStudy {
                sawNoisy = noisy
                return object : TuningStudy {
                    override fun suggest(count: Int) = List(count) { Suggestion("t", mapOf("arm" to "A")) }
                    override fun complete(suggestion: Suggestion, objective: Double) = Unit
                    override fun observe(values: Map<String, Any>, objective: Double) = Unit
                    override fun close() = Unit
                }
            }
            override fun close() = Unit
        }
        BoTuning.tune(
            space = ConfigSpace(listOf(CategoricalParam("arm", listOf("A")))),
            decode = { it.getValue("arm") as String },
            reward = { _, _ -> 0.5 },
            pool = UniformPool(listOf(cop("i0", "min: 1 x1 ;\n+1 x1 >= 0 ;\n"))),
            tuner = recording,
            rounds = 1,
            trials = 1,
            batch = 1,
            warmStart = false,
            studyId = "noisy",
        )
        assertEquals(true, sawNoisy, "mini-batch evaluation opens noisy studies")
    }

    @Test
    fun `a stratum frontier keeps a rare-stratum specialist despite few instances`() {
        // 22 instances in two strata: 20 "common", 2 "rare". C is the all-rounder; S wins only the rare
        // stratum. A stratum frontier surfaces S even though rare is <10% of the pool — the signal a
        // per-instance frontier would dilute to mean-reward (rare instances rarely sampled) on a big pool.
        val common = List(20) { cop("c$it", "min: 1 x1 ;\n+1 x1 >= 0 ;\n") }
        val rare = List(2) { cop("r$it", "min: 1 x1 ;\n+1 x1 >= 0 ;\n") }
        val batch = listOf(common[0], common[1], rare[0], rare[1]) // a stratified draw always covers rare
        val pool = object : SamplingPool {
            override fun sample(size: Int, rng: Random) = batch
            override fun stratumOf(p: ResolvedProblem) = if (p in rare) "rare" else "common"
            override fun isNotEmpty() = true
        }
        val result = BoTuning.tune(
            space = ConfigSpace(listOf(CategoricalParam("arm", listOf("C", "S")))),
            decode = { it.getValue("arm") as String },
            reward = { p, arm -> if (arm == "S") (if (p in rare) 1.0 else 0.1) else 0.6 },
            pool = pool,
            tuner = CyclingTuner(listOf(mapOf("arm" to "C"), mapOf("arm" to "S"))),
            rounds = 2,
            trials = 2,
            batch = 2,
            warmStart = true,
            studyId = "strata",
            sampleSize = 4,
        )
        assertEquals("arm=C", result.palette[0].label, "round 1 anchors the all-rounder")
        assertTrue(result.palette.any { it.label == "arm=S" }, "the rare-stratum specialist still gets kept")
    }
}
