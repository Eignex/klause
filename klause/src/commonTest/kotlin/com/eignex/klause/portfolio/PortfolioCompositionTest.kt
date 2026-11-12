package com.eignex.klause.portfolio

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.NodeBudget
import com.eignex.klause.backtrack.selector.IndomainMin
import com.eignex.klause.backtrack.selector.SmallestDomain
import com.eignex.klause.backtrack.toBacktrackParams
import com.eignex.klause.ir.SearchGroup
import com.eignex.klause.ir.SearchHints
import com.eignex.klause.ir.ValueSelection
import com.eignex.klause.ir.VariableSelection
import com.eignex.klause.lp.bounding.LpConfig
import com.eignex.klause.lp.bounding.LpEmphasis
import com.eignex.klause.solver.ProblemClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PortfolioCompositionTest {
    @Test
    fun `an explicit table satisfaction pool should keep its selected recipes`() {
        val facts = ProblemFacts(ProblemFacts.assumed(Kind.CSP).profile, extensionalSupports = true) { false }
        val scenario = PortfolioScenario.sequential(Kind.CSP).copy(
            btPool = listOf { BacktrackCatalog.byLabel("conflictDriven") },
        )

        val arms = PortfolioComposition.compose(scenario, facts).filterIsInstance<BacktrackWorkerConfig>()

        assertTrue(arms.all { it.label == "conflictDriven" })
    }


    @Test
    fun `finite satisfaction diversity should spend the containing node allowance`() {
        val budget = NodeBudget(limit = 100)
        val scenario = PortfolioScenario.sequential(Kind.CSP).copy(nodeBudget = budget)
        val facts = ProblemFacts(ProblemFacts.assumed(Kind.CSP).profile) { false }

        val selected = PortfolioComposition.compose(scenario, facts).filterIsInstance<BacktrackWorkerConfig>()

        for (label in listOf("domwdeg", "first-fail")) {
            val arm = selected.single { it.label == label }
            assertSame(budget, arm.recipe.build(1L, null).nodeBudget)
        }
    }

    @Test
    fun `integer linear companion should remain available with cuts disabled`() {
        val scenario = PortfolioScenario.sequential(Kind.COP)
        val facts = ProblemFacts(ProblemFacts.assumed(Kind.COP).profile, integerLinear = true) { true }
        val baseline = PortfolioComposition.compose(scenario, facts)

        val composed = PortfolioComposition.compose(
            scenario.copy(lpCeiling = LpConfig.parse("aggressive,-cuts")),
            facts,
        )

        assertEquals(baseline.map { it.label }, composed.map { it.label })
    }

    @Test
    fun `curated Boolean satisfaction should enable bounded root maintenance`() {
        val budget = NodeBudget(limit = 100)
        for (problemClass in listOf(ProblemClass.Sat, ProblemClass.PseudoBoolean)) {
            val facts = ProblemFacts.assumed(Kind.CSP, problemClass)
            for (cores in listOf(1, 2)) {
                val scenario = PortfolioScenario.sequential(Kind.CSP, engine = EngineMix.BACKTRACK)
                    .copy(cores = cores, nodeBudget = budget)

                val params = PortfolioComposition.compose(scenario, facts).filterIsInstance<BacktrackWorkerConfig>()
                    .map { it.recipe.build(1L, null) }

                assertTrue(params.isNotEmpty())
                for (arm in params) {
                    assertTrue(arm.rootSimplification)
                    assertEquals(256, arm.rootSimplifyBatch)
                    assertEquals(2, arm.rootProbeBatch)
                    assertEquals(8192L, arm.rootProbeWork)
                    assertFalse(arm.rootProbeImplications)
                    assertEquals(problemClass == ProblemClass.Sat, arm.rootProbeBinaryGate)
                    assertEquals(problemClass == ProblemClass.PseudoBoolean, arm.rootProbeGeneralGate)
                    assertEquals(if (problemClass == ProblemClass.Sat) 4096L else 16384L, arm.inprocessingWork)
                    assertEquals(256L, arm.inprocessingRootWork)
                    assertEquals(if (problemClass == ProblemClass.Sat) 3072L else 12288L, arm.inprocessingProbeWork)
                    assertEquals(if (problemClass == ProblemClass.Sat) 0L else Long.MAX_VALUE,
                        arm.inprocessingSubsumeWork)
                    assertEquals(if (problemClass == ProblemClass.Sat) 0L else Long.MAX_VALUE,
                        arm.inprocessingVivifyWork)
                    assertEquals(32768L, arm.inprocessingProbeInterval)
                    assertEquals(32L, arm.inprocessingInterval)
                    assertEquals(8, arm.inprocessingGrowth)
                    assertEquals(32L, arm.inprocessingCooldown)
                    assertEquals(
                        if (problemClass == ProblemClass.Sat) 4096L else 0L,
                        arm.inprocessingWarmupWork,
                    )
                    assertSame(budget, arm.nodeBudget)
                }
            }
        }
    }

    @Test
    fun `disabling automatic root maintenance should retain stock root parameters`() {
        val scenario = PortfolioScenario.sequential(Kind.CSP, engine = EngineMix.BACKTRACK)
            .copy(rootMaintenance = false)
        val stock = BacktrackParams()
        for (problemClass in listOf(ProblemClass.Sat, ProblemClass.PseudoBoolean)) {
            val facts = ProblemFacts.assumed(Kind.CSP, problemClass)

            val params = PortfolioComposition.compose(scenario, facts).filterIsInstance<BacktrackWorkerConfig>()
                .map { it.recipe.build(1L, null) }

            assertTrue(params.isNotEmpty())
            for (arm in params) {
                assertEquals(stock.rootSimplification, arm.rootSimplification)
                assertEquals(stock.rootSimplifyBatch, arm.rootSimplifyBatch)
                assertEquals(stock.rootProbeBatch, arm.rootProbeBatch)
                assertEquals(stock.rootProbeWork, arm.rootProbeWork)
                assertEquals(stock.rootProbeImplications, arm.rootProbeImplications)
                assertEquals(stock.rootProbeBinaryGate, arm.rootProbeBinaryGate)
                assertEquals(stock.rootProbeGeneralGate, arm.rootProbeGeneralGate)
                assertEquals(stock.inprocessingWork, arm.inprocessingWork)
                assertEquals(stock.inprocessingRootWork, arm.inprocessingRootWork)
                assertEquals(stock.inprocessingProbeWork, arm.inprocessingProbeWork)
                assertEquals(stock.inprocessingProbeInterval, arm.inprocessingProbeInterval)
                assertEquals(stock.inprocessingInterval, arm.inprocessingInterval)
                assertEquals(stock.inprocessingGrowth, arm.inprocessingGrowth)
                assertEquals(stock.inprocessingCooldown, arm.inprocessingCooldown)
                assertEquals(stock.inprocessingWarmupWork, arm.inprocessingWarmupWork)
            }
        }
    }

    @Test
    fun `manual root settings should survive automatic policy opt out`() {
        val scenario = PortfolioScenario.sequential(Kind.CSP, engine = EngineMix.BACKTRACK).copy(
            rootMaintenance = false,
            btEdit = { it.copy(rootSimplification = true, rootProbeBatch = 7, inprocessingWork = 99L) },
        )
        val facts = ProblemFacts.assumed(Kind.CSP, ProblemClass.PseudoBoolean)

        val params = PortfolioComposition.compose(scenario, facts).filterIsInstance<BacktrackWorkerConfig>()
            .map { it.recipe.build(1L, null) }

        assertTrue(params.isNotEmpty())
        assertTrue(params.all { it.rootSimplification && it.rootProbeBatch == 7 && it.inprocessingWork == 99L })
    }

    @Test
    fun `other model classes should retain their root maintenance defaults`() {
        val scenario = PortfolioScenario.sequential(Kind.CSP, engine = EngineMix.BACKTRACK)
        val otherClasses = ProblemClass.entries.filter {
            it != ProblemClass.Sat && it != ProblemClass.PseudoBoolean
        }
        for (problemClass in otherClasses) {
            val facts = ProblemFacts.assumed(Kind.CSP, problemClass)

            val params = PortfolioComposition.compose(scenario, facts).filterIsInstance<BacktrackWorkerConfig>()
                .map { it.recipe.build(1L, null) }

            assertTrue(params.all { !it.rootSimplification && it.rootProbeBatch == 0 })
        }
    }

    @Test
    fun `mixed and optimization pools should retain their root maintenance defaults`() {
        val scenarios = listOf(
            PortfolioScenario.sequential(Kind.CSP, engine = EngineMix.MIXED),
            PortfolioScenario.sequential(Kind.COP, engine = EngineMix.BACKTRACK),
        )
        for (scenario in scenarios) {
            for (problemClass in listOf(ProblemClass.Sat, ProblemClass.PseudoBoolean)) {
                val facts = ProblemFacts.assumed(scenario.kind, problemClass)

                val params = PortfolioComposition.compose(scenario, facts).filterIsInstance<BacktrackWorkerConfig>()
                    .map { it.recipe.build(1L, null) }

                assertTrue(params.all { !it.rootSimplification && it.rootProbeBatch == 0 })
            }
        }
    }

    @Test
    fun `explicit edits should override automatic root maintenance`() {
        val scenario = PortfolioScenario.sequential(Kind.CSP, engine = EngineMix.BACKTRACK).copy(
            btEdit = {
                it.copy(rootSimplification = false, rootProbeBatch = 0,
                    rootProbeImplications = true, rootProbeGeneralGate = false, rootProbeWork = 23L,
                    inprocessingWork = 17L, inprocessingSubsumeWork = 13L, inprocessingVivifyWork = 19L)
            },
        )
        val facts = ProblemFacts.assumed(Kind.CSP, ProblemClass.PseudoBoolean)

        val params = PortfolioComposition.compose(scenario, facts).filterIsInstance<BacktrackWorkerConfig>()
            .map { it.recipe.build(1L, null) }

        assertTrue(params.isNotEmpty())
        assertTrue(params.all {
            !it.rootSimplification && it.rootProbeBatch == 0 &&
                it.rootProbeImplications && !it.rootProbeGeneralGate && it.rootProbeWork == 23L &&
                it.inprocessingWork == 17L && it.inprocessingSubsumeWork == 13L && it.inprocessingVivifyWork == 19L
        })
    }

    @Test
    fun `explicit pools should preserve their root maintenance parameters`() {
        val requested = BacktrackParams(rootProbeBatch = 5, inprocessingWork = 17L)
        val scenario = PortfolioScenario.sequential(Kind.CSP, engine = EngineMix.BACKTRACK).copy(
            btPool = listOf { BacktrackWorkerConfig.ofParams("custom", requested) },
        )
        val facts = ProblemFacts.assumed(Kind.CSP, ProblemClass.PseudoBoolean)

        val params = PortfolioComposition.compose(scenario, facts).filterIsInstance<BacktrackWorkerConfig>()
            .map { it.recipe.build(1L, null) }

        assertTrue(params.isNotEmpty())
        assertTrue(params.all { !it.rootSimplification && it.rootProbeBatch == 5 && it.inprocessingWork == 17L })
    }

    @Test
    fun `source annotations should preserve their root maintenance policy`() {
        val requested = BacktrackParams(rootProbeBatch = 5, inprocessingWork = 17L)
        val scenario = PortfolioScenario.sequential(Kind.CSP, engine = EngineMix.BACKTRACK)
            .copy(annotationArm = requested)
        val facts = ProblemFacts.assumed(Kind.CSP, ProblemClass.PseudoBoolean)

        val params = PortfolioComposition.compose(scenario, facts).filterIsInstance<BacktrackWorkerConfig>()
            .associate { it.label to it.recipe.build(1L, null) }

        val annotation = params.getValue("annotation")
        assertEquals(5, annotation.rootProbeBatch)
        assertEquals(17L, annotation.inprocessingWork)
        assertTrue(params.values.all { !it.rootSimplification })
        assertTrue(params.filterKeys { it != "annotation" }.values.all { it.rootProbeBatch == 0 })
    }

    @Test
    fun `curated satisfaction root cuts should require an explicit override`() {
        for (requested in listOf(null, false, true)) {
            val edit = requested?.let { enabled ->
                { params: BacktrackParams -> params.copy(lpPlan = params.lpPlan.copy(rootCutHarvest = enabled)) }
            }
            val scenario = PortfolioScenario.sequential(Kind.CSP, engine = EngineMix.BACKTRACK, arms = 4)
                .copy(btEdit = edit)

            val params = PortfolioComposition.compose(scenario).filterIsInstance<BacktrackWorkerConfig>()
                .map { it.recipe.build(1L, null) }.filter { it.lpConfig != null }

            assertEquals(2, params.size)
            assertEquals(List(2) { requested == true }, params.map { it.lpPlan.rootCutHarvest })
        }
    }

    @Test
    fun `optimization and explicit satisfaction pools should retain their root cut plans`() {
        val scenarios = listOf(
            PortfolioScenario.sequential(Kind.COP, engine = EngineMix.BACKTRACK, arms = 4),
            PortfolioScenario.sequential(Kind.CSP, engine = EngineMix.BACKTRACK, arms = 1)
                .copy(btPool = listOf { BacktrackCatalog.byLabel("lp-aggressive") }),
        )

        for (scenario in scenarios) {
            val params = PortfolioComposition.compose(scenario).filterIsInstance<BacktrackWorkerConfig>()
                .map { it.recipe.build(1L, null) }.filter { it.lpConfig != null }

            assertTrue(params.isNotEmpty())
            assertTrue(params.all { it.lpPlan.rootCutHarvest })
        }
    }

    @Test
    fun `explicit and parallel pools should use every arm for a first solution`() {
        val scenarios = listOf(
            PortfolioScenario.sequential(Kind.CSP, arms = 12),
            PortfolioScenario.sequential(Kind.COP, engine = EngineMix.BACKTRACK, arms = 12),
            PortfolioScenario.sequential(Kind.COP, engine = EngineMix.LOCAL_SEARCH, arms = 12),
            PortfolioScenario.parallel(4, Kind.COP, arms = 12),
            PortfolioScenario.sequential(Kind.COP, arms = 12)
                .copy(btPool = listOf { BacktrackCatalog.byLabel("conflictDriven") }),
            PortfolioScenario.sequential(Kind.COP, arms = 12)
                .copy(lsPool = listOf { LocalSearchCatalog.byLabel("cbls/fixed") }),
        )
        for (scenario in scenarios) {
            val plan = PortfolioComposition.plan(scenario, ProblemFacts.assumed(scenario.kind))

            assertEquals(plan.arms.size, plan.firstSolutionCount, "$scenario")
        }
    }

    @Test
    fun `mixed optimization should schedule a complete arm before local search`() {
        val arms = PortfolioComposition.compose(PortfolioScenario.sequential(Kind.COP))

        assertIs<BacktrackWorkerConfig>(arms.first())
    }

    @Test
    fun `an injected backtrack pool should be built as asked whatever the model offers`() {
        val scenario = PortfolioScenario.sequential(Kind.COP, engine = EngineMix.BACKTRACK, arms = 2)
            .copy(btPool = listOf { BacktrackCatalog.byLabel("lp-default") })
        val facts = ProblemFacts(ProblemFacts.assumed(Kind.COP).profile, relaxation = { false })

        val labels = PortfolioComposition.compose(scenario, facts).map { it.label }

        assertEquals(listOf("lp-default", "lp-default"), labels)
    }

    @Test
    fun `the node allowance should reach every arm that runs a backtrack engine`() {
        val budget = NodeBudget(limit = 100)
        val arms = PortfolioComposition.compose(
            PortfolioScenario.sequential(Kind.COP, engine = EngineMix.MIXED, arms = 6).copy(nodeBudget = budget),
        )

        for (arm in arms) {
            when (arm) {
                is BacktrackWorkerConfig ->
                    assertSame(budget, arm.recipe.build(1L, null).nodeBudget, "backtrack arm ${arm.label}")

                is AlnsWorkerConfig -> assertSame(budget, arm.nodeBudget, "hybrid-ALNS arm ${arm.label}")

                else -> Unit
            }
        }
        assertTrue(arms.any { it is AlnsWorkerConfig })
    }

    @Test
    fun `the model's annotation arm should spend the node allowance`() {
        val budget = NodeBudget(limit = 100)
        val arms = PortfolioComposition.compose(
            PortfolioScenario.sequential(Kind.CSP, engine = EngineMix.BACKTRACK, arms = 3)
                .copy(annotationArm = BacktrackParams(), nodeBudget = budget),
        )

        val annotation = arms.filterIsInstance<BacktrackWorkerConfig>().single { it.label == "annotation" }
        assertSame(budget, annotation.recipe.build(1L, null).nodeBudget)
    }


    @Test
    fun `an optimization roster should seat one arm per needed role before filling by rank`() {
        val scenario = PortfolioScenario.sequential(Kind.COP)
        val facts = ProblemFacts(ProblemFacts.assumed(Kind.COP).profile) { true }

        val plan = PortfolioComposition.plan(scenario, facts)

        assertEquals(
            listOf(
                "satOptimized", "cbls/fixed", "lp-default", "alns-balanced", "cbls-plateau/ils-basin",
                "conflictDriven",
            ),
            plan.arms.map { it.label },
        )
        assertEquals(
            listOf(
                ArmRole.LearningProof, ArmRole.FirstSolution, ArmRole.LpBounding, ArmRole.NeighbourhoodImprovement,
                ArmRole.NeighbourhoodImprovement, ArmRole.LearningProof,
            ),
            plan.roles,
        )
    }

    @Test
    fun `a satisfaction roster should seat local search before its proof arm`() {
        val scenario = PortfolioScenario.sequential(Kind.CSP)
        val facts = ProblemFacts(ProblemFacts.assumed(Kind.CSP).profile) { true }

        val labels = PortfolioComposition.compose(scenario, facts).map { it.label }

        assertEquals(
            listOf(
                "cbls/fixed", "satOptimized", "lp-default", "conflictDriven", "cbls-plateau/ils-basin",
                "lp-aggressive",
            ),
            labels,
        )
    }

    @Test
    fun `a finite model without a relaxation should seat a selector arm before filling`() {
        val cases = listOf(
            Kind.COP to listOf(
                "satOptimized", "cbls/fixed", "alns-balanced", "selector-switch", "cbls-plateau/ils-basin",
                "conflictDriven",
            ),
            Kind.CSP to listOf(
                "cbls/fixed", "satOptimized", "domwdeg", "conflictDriven", "cbls-plateau/ils-basin", "first-fail",
            ),
        )
        for ((kind, expected) in cases) {
            val facts = ProblemFacts(ProblemFacts.assumed(kind).profile) { false }

            val labels = PortfolioComposition.compose(PortfolioScenario.sequential(kind), facts).map { it.label }

            assertEquals(expected, labels)
        }
    }

    @Test
    fun `selector diversity should be a finite model need`() {
        val scenario = PortfolioScenario.sequential(Kind.COP)
        val profile = ProblemFacts.assumed(Kind.COP).profile
        for (problemClass in ProblemClass.entries.filter { it != ProblemClass.FiniteCp }) {
            val facts = ProblemFacts(profile.copy(problemClass = problemClass)) { false }

            val plan = PortfolioComposition.plan(scenario, facts)

            assertFalse(ArmRole.SelectorDiversity in plan.roles, "$problemClass")
        }
    }

    @Test
    fun `table satisfaction should seat support ordering for selector diversity`() {
        val scenario = PortfolioScenario.sequential(Kind.CSP)
        val facts = ProblemFacts(ProblemFacts.assumed(Kind.CSP).profile, extensionalSupports = true) { false }

        val labels = PortfolioComposition.compose(scenario, facts).map { it.label }

        assertEquals(
            listOf(
                "cbls/fixed", "satOptimized", "support-aware", "conflictDriven", "cbls-plateau/ils-basin",
                "propagation-guided",
            ),
            labels,
        )
    }

    @Test
    fun `a Boolean objective should seat the core worker`() {
        val scenario = PortfolioScenario.sequential(Kind.COP)
        val facts = ProblemFacts(ProblemFacts.assumed(Kind.COP).profile, booleanObjective = true) { true }

        val plan = PortfolioComposition.plan(scenario, facts)

        val core = plan.arms.indexOfFirst { it is CoreGuidedWorkerConfig }
        assertEquals(1, plan.arms.count { it is CoreGuidedWorkerConfig })
        assertEquals(ArmRole.ObjectiveCores, plan.roles[core])
        assertEquals(scenario.arms, plan.arms.size)
    }

    @Test
    fun `pure integer rows should seat a second LP emphasis and binary ones a selector arm`() {
        val scenario = PortfolioScenario.sequential(Kind.COP)
        val profile = ProblemFacts.assumed(Kind.COP).profile
        val cases = listOf(
            ProblemFacts(profile, integerLinear = true) { true } to "cbls-plateau/ils-basin",
            ProblemFacts(profile, binaryIntegers = true, binaryEqualityObjective = true) { true } to
                "selector-switch",
        )
        for ((facts, last) in cases) {
            val labels = PortfolioComposition.compose(scenario, facts).map { it.label }

            assertEquals(
                listOf("satOptimized", "cbls/fixed", "lp-default", "alns-balanced", "lp-aggressive", last),
                labels,
            )
        }
    }

    @Test
    fun `a continuous objective should keep one LP emphasis`() {
        val scenario = PortfolioScenario.sequential(Kind.COP)
        val profile = ProblemFacts.assumed(Kind.COP, ProblemClass.MixedInteger).profile
        val facts = ProblemFacts(profile, continuousObjective = true, integerLinear = true) { true }

        val plan = PortfolioComposition.plan(scenario, facts)

        assertEquals(listOf("lp-default"), plan.arms.map { it.label }.filter { it.startsWith("lp-") })
        assertEquals(plan.arms.size, plan.firstSolutionCount)
    }

    @Test
    fun `an annotated model should seat its source order for selector diversity`() {
        val annotation = BacktrackParams(variableSelector = SmallestDomain, valueSelector = IndomainMin)
        val budget = NodeBudget(limit = 100)
        for (kind in Kind.entries) {
            val scenario = PortfolioScenario.sequential(kind).copy(annotationArm = annotation, nodeBudget = budget)
            val facts = ProblemFacts(ProblemFacts.assumed(kind).profile) { true }

            val plan = PortfolioComposition.plan(scenario, facts)

            val source = plan.arms.indexOfFirst { it.label == "annotation" }
            assertEquals(1, plan.arms.count { it.label == "annotation" })
            assertEquals(ArmRole.SelectorDiversity, plan.roles[source])
            val params = (plan.arms[source] as BacktrackWorkerConfig).recipe.build(1L, null)
            assertSame(SmallestDomain, params.variableSelector)
            assertSame(budget, params.nodeBudget)
        }
    }

    @Test
    fun `a scheduling optimization should lead local search with the primary and full plateau pair`() {
        val scenario = PortfolioScenario.sequential(Kind.COP)
        val scheduling = ProblemFacts(ProblemFacts.assumed(Kind.COP).profile.copy(scheduling = true)) { true }

        val local = PortfolioComposition.compose(scenario, scheduling).filterIsInstance<LocalSearchWorkerConfig>()

        assertEquals(listOf("cbls-primary-plateau/ils-basin", "cbls-plateau/ils-basin"), local.map { it.label })
    }

    @Test
    fun `a smaller roster should be a prefix of a larger one at every core count`() {
        val cases = listOf(
            Kind.COP to ProblemFacts.assumed(Kind.COP),
            Kind.COP to ProblemFacts(ProblemFacts.assumed(Kind.COP).profile, booleanObjective = true) { false },
            Kind.COP to ProblemFacts.assumed(Kind.COP, ProblemClass.MixedInteger),
            Kind.CSP to ProblemFacts.assumed(Kind.CSP),
            Kind.CSP to ProblemFacts.assumed(Kind.CSP, ProblemClass.Sat),
        )
        for ((kind, facts) in cases) {
            val small = PortfolioComposition.compose(PortfolioScenario.sequential(kind), facts).map { it.label }
            for ((cores, arms) in listOf(1 to 12, 4 to 8, 1 to 80)) {
                val scenario = PortfolioScenario.parallel(cores, kind, arms = arms)

                val large = PortfolioComposition.compose(scenario, facts).map { it.label }

                assertEquals(arms, large.size)
                assertEquals(small, large.take(small.size), "$kind cores=$cores arms=$arms")
            }
        }
    }

    @Test
    fun `an expanded sequential optimization pool should search first with the default roster`() {
        val scenario = PortfolioScenario.sequential(Kind.COP, arms = 12)

        val plan = PortfolioComposition.plan(scenario, ProblemFacts.assumed(Kind.COP))

        assertEquals(12, plan.arms.size)
        assertEquals(PortfolioScenario.DEFAULT_ARMS, plan.firstSolutionCount)
    }

    @Test
    fun `an injected backtrack recipe should repeat beside the curated local search`() {
        val scenario = PortfolioScenario.sequential(Kind.COP)
            .copy(btPool = listOf { BacktrackCatalog.byLabel("conflictDriven") })

        val labels = PortfolioComposition.compose(scenario).map { it.label }

        assertEquals(
            listOf(
                "conflictDriven", "cbls/fixed", "alns-balanced", "cbls-plateau/ils-basin", "conflictDriven",
                "cbls-smooth/ils-basin",
            ),
            labels,
        )
    }

    @Test
    fun `a pool with a core per arm should keep its cores from the neighbourhood arm`() {
        val scenario = PortfolioScenario.parallel(4, Kind.COP)

        val arms = PortfolioComposition.compose(scenario)

        assertEquals(4, arms.size)
        assertFalse(arms.any { it is AlnsWorkerConfig })
    }
}
