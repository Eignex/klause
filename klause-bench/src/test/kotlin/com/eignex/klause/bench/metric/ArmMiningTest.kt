package com.eignex.klause.bench.metric

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArmMiningTest {
    private fun rec(
        stats: Map<String, String>,
        feasible: Boolean? = true,
        attribution: List<Attribution> = emptyList(),
    ) = SolveRecord(
        problem = "fam/inst",
        solver = "klause",
        engine = "mixed",
        processors = 1,
        search = "free",
        seed = 3,
        budgetMs = 10_000,
        kind = if (attribution.isEmpty()) "satisfy" else "optimize",
        maximize = false,
        feasible = feasible,
        objective = null,
        timeToBestMs = null,
        proven = false,
        stats = stats,
        attribution = attribution,
        gitSha = null,
        timestamp = "t",
        command = "c",
    )

    private fun mined(record: SolveRecord) = checkNotNull(ArmMining.mined("s/fam/inst", "rewards", emptyMap(), record))

    @Test
    fun `an arm telemetry line parses into accounting and per-signal credit`() {
        val arm = ArmMining.parseArm(
            "domwdeg",
            "segments=4 work=1200 reward=0.25 failures=1 faults=0 FirstSolution=1.0 ClauseUses=3.5",
        )

        assertEquals(1200L, arm.work)
        assertEquals(0.25, arm.reward)
        assertEquals(1, arm.failures)
        assertEquals(mapOf("FirstSolution" to 1.0, "ClauseUses" to 3.5), arm.credit)
    }

    @Test
    fun `the best-valued attribution entry wins an optimisation case`() {
        val case = mined(
            rec(
                stats = mapOf("arm.a" to "work=10 reward=0.5", "arm.b" to "work=10 reward=0.5"),
                attribution = listOf(
                    Attribution("a", exactObjective = "5", elapsedMs = 10),
                    Attribution("b", exactObjective = "3", elapsedMs = 20),
                ),
            ),
        )

        assertEquals(setOf("b"), case.winners)
    }

    @Test
    fun `the arm credited with the first solution wins a satisfaction case`() {
        val case = mined(rec(mapOf("arm.a" to "work=10 reward=0.0", "arm.b" to "work=10 reward=1.0 FirstSolution=1.0")))

        assertEquals(setOf("b"), case.winners)
    }

    @Test
    fun `an infeasibility proof names no winner`() {
        val case = mined(rec(mapOf("arm.a" to "work=10 reward=0.0"), feasible = false))

        assertTrue(case.winners.isEmpty())
    }

    @Test
    fun `a record without arm telemetry is not mined`() {
        assertNull(ArmMining.mined("s/fam/inst", "base", emptyMap(), rec(mapOf("nodes" to "12"))))
    }

    @Test
    fun `contribution weights each case's reward by the work the arm spent there`() {
        val cases = listOf(
            mined(rec(mapOf("arm.a" to "work=300 reward=1.0 CutUses=2.0"))),
            mined(rec(mapOf("arm.a" to "work=100 reward=0.0 CutUses=1.0"))),
        )

        val a = ArmMining.contributions(cases).single()

        assertEquals(400L, a.work)
        assertEquals(0.75, a.reward)
        assertEquals(mapOf("CutUses" to 3.0), a.credit)
    }

    @Test
    fun `replicas of one arm count as that arm once per case`() {
        val stats = mapOf("arm.bt/domwdeg" to "work=100 reward=1.0", "arm.bt/domwdeg#2" to "work=100 reward=0.0")

        val arm = ArmMining.contributions(listOf(mined(rec(stats)))).single()

        assertEquals("bt/domwdeg", arm.arm)
        assertEquals(1, arm.cases)
        assertEquals(200L, arm.work)
    }

    @Test
    fun `a quarantined arm run is called out at the top of the report`() {
        val report = ArmMining.render(listOf(mined(rec(mapOf("arm.broken" to "work=10 reward=0.0 faults=1")))))

        assertTrue(report.startsWith("!!! 1 quarantined arm run"), report)
        assertTrue("broken on s/fam/inst [rewards]" in report, report)
    }

    @Test
    fun `a quarantine the run recorded is printed under its fault`() {
        val stats = mapOf(
            "arm.broken" to "work=10 reward=0.0 faults=1",
            SolverInvocation.QUARANTINED to "broken quarantined: int 3 = 7 conflicts",
        )

        val report = ArmMining.render(listOf(mined(rec(stats))))

        assertTrue("!!!     broken quarantined: int 3 = 7 conflicts" in report, report)
    }

    @Test
    fun `lab case files load only the cases whose record ran a portfolio`() {
        val file = File.createTempFile("cases", ".json").apply { deleteOnExit() }
        file.writeText(
            """
            [
              {"index": 0, "problem": {"suite": "s", "problem": "p", "family": "f"}, "arm": "base",
               "record": {"problem": "p", "solver": "klause", "engine": null, "processors": 1, "search": "free",
                 "seed": 1, "budgetMs": 1000, "kind": "satisfy", "maximize": false, "feasible": true,
                 "objective": null, "timeToBestMs": null, "proven": false,
                 "stats": {"arm.a": "work=5 reward=1.0 FirstSolution=1.0"}, "attribution": [],
                 "gitSha": null, "timestamp": "t", "command": "c"}},
              {"index": 1, "problem": {"suite": "s", "problem": "q"}, "arm": "base", "record": null}
            ]
            """.trimIndent(),
        )

        val cases = ArmMining.load(listOf(file))

        assertEquals(listOf("s/p"), cases.map { it.name })
        assertEquals("f", cases.single().slice["family"])
    }
}
