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
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PortfolioCompositionTest {
    @Test
    fun `default table satisfaction pools should schedule one support aware worker within the containing allowance`() {
        val budget = NodeBudget(limit = 100)
        val facts = ProblemFacts(ProblemFacts.assumed(Kind.CSP).profile, extensionalSupports = true) { false }
        for (engine in listOf(EngineMix.BACKTRACK, EngineMix.MIXED)) {
            val scenario = PortfolioScenario.sequential(Kind.CSP, engine = engine).copy(nodeBudget = budget)

            val plan = PortfolioComposition.plan(scenario, facts)
            val complete = plan.arms.filterIsInstance<BacktrackWorkerConfig>()

            assertEquals(scenario.arms, plan.arms.size)
            assertEquals(listOf("satOptimized", "conflictDriven", "support-aware"), complete.take(3).map { it.label })
            assertEquals("propagation-guided", complete[3].label)
            val support = complete.single { it.label == "support-aware" }
            assertSame(budget, support.recipe.build(3L, null).nodeBudget)
            assertEquals(plan.arms.size, plan.firstSolutionCount)
        }
    }

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
    fun `finite satisfaction should retain classical branching in the existing complete slots`() {
        val facts = ProblemFacts(ProblemFacts.assumed(Kind.CSP).profile) { false }
        for (count in listOf(6, 12)) {
            val scenario = PortfolioScenario.sequential(Kind.CSP, arms = count)
            val baseline = PortfolioComposition.compose(scenario.copy(btEdit = { it }), facts)

            val plan = PortfolioComposition.plan(scenario, facts)
            val complete = plan.arms.filterIsInstance<BacktrackWorkerConfig>()

            assertEquals(
                listOf("satOptimized", "conflictDriven", "domwdeg", "first-fail"),
                complete.take(4).map { it.label },
            )
            assertEquals(1, complete.count { it.label == "domwdeg" })
            assertEquals(1, complete.count { it.label == "first-fail" })
            assertEquals(baseline.size, plan.arms.size)
            assertEquals(
                baseline.filterIsInstance<LocalSearchWorkerConfig>().map { it.label },
                plan.arms.filterIsInstance<LocalSearchWorkerConfig>().map { it.label },
            )
            assertEquals(plan.arms.size, plan.firstSolutionCount)
        }
    }

    @Test
    fun `finite satisfaction diversity should preserve explicit parallel and small pools`() {
        val scenario = PortfolioScenario.sequential(Kind.CSP)
        val facts = ProblemFacts(ProblemFacts.assumed(Kind.CSP).profile) { false }
        val other = ProblemFacts(ProblemFacts.assumed(Kind.CSP, ProblemClass.MixedInteger).profile) { false }
        for (control in listOf(
            scenario.copy(cores = 2),
            scenario.copy(arms = 5),
            scenario.copy(engine = EngineMix.BACKTRACK),
            scenario.copy(btEdit = { it }),
            scenario.copy(btPool = listOf { BacktrackCatalog.byLabel("free") }),
            scenario.copy(lsPool = listOf { LocalSearchCatalog.byLabel("cbls/fixed") }),
            scenario.copy(annotationArm = BacktrackParams()),
        )) {
            val expected = PortfolioComposition.compose(control, other).filterIsInstance<BacktrackWorkerConfig>()

            val selected = PortfolioComposition.compose(control, facts).filterIsInstance<BacktrackWorkerConfig>()

            assertEquals(expected.map { it.label }, selected.map { it.label }, control.toString())
        }
    }

    @Test
    fun `satisfaction diversity should preserve LP recipes and other model classes`() {
        val scenario = PortfolioScenario.sequential(Kind.CSP)
        val cases = ProblemClass.entries.flatMap { problemClass ->
            listOf(false, true).map { problemClass to it }
        }.filter { (problemClass, relaxation) -> problemClass != ProblemClass.FiniteCp || relaxation }
        for ((problemClass, relaxation) in cases) {
            val facts = ProblemFacts(ProblemFacts.assumed(Kind.CSP, problemClass).profile) { relaxation }

            val selected = PortfolioComposition.compose(scenario, facts).filterIsInstance<BacktrackWorkerConfig>()

            assertEquals(BacktrackCatalog.ranked(Kind.CSP, facts).take(4).map { it.label }, selected.map { it.label })
        }
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
    fun `annotated finite optimization should admit unrestricted first fail beside source search`() {
        val annotation = SearchHints(
            listOf(
                SearchGroup(intArrayOf(), intArrayOf(0, 1)),
                SearchGroup(intArrayOf(), intArrayOf(2), VariableSelection.InputOrder),
            ),
        ).toBacktrackParams(0, 3)
        val scenario = PortfolioScenario.sequential(Kind.COP).copy(annotationArm = annotation)
        val facts = ProblemFacts(ProblemFacts.assumed(Kind.COP).profile, circuit = true) { false }

        val plan = PortfolioComposition.plan(scenario, facts)

        assertEquals(listOf("satOptimized", "annotation"), plan.arms.take(2).map { it.label })
        assertEquals("first-fail", plan.arms.last().label)
        assertEquals(plan.arms.size, plan.firstSolutionCount)
        val source = (plan.arms[1] as BacktrackWorkerConfig).recipe.build(1L, null)
        assertSame(annotation.variableSelector, source.variableSelector)
        assertSame(annotation.valueSelector, source.valueSelector)
        val generic = (plan.arms.last() as BacktrackWorkerConfig).recipe.build(1L, null)
        assertSame(SmallestDomain, generic.variableSelector)
        assertSame(IndomainMin, generic.valueSelector)
    }

    @Test
    fun `tiered first fail should retain unrestricted search diversity`() {
        val annotation = SearchHints(listOf(SearchGroup(intArrayOf(), intArrayOf(1, 0))))
            .toBacktrackParams(0, 3)
        val scenario = PortfolioScenario.sequential(Kind.COP).copy(annotationArm = annotation)

        val facts = ProblemFacts(ProblemFacts.assumed(Kind.COP).profile, circuit = true) { true }
        val composed = PortfolioComposition.compose(scenario, facts)

        assertEquals(1, composed.count { it.label == "first-fail" })
        assertEquals(1, composed.count { it.label == "annotation" })
    }

    @Test
    fun `annotation diversity should preserve other primary branching preferences`() {
        val scenario = PortfolioScenario.sequential(Kind.COP)
        val facts = ProblemFacts(ProblemFacts.assumed(Kind.COP).profile, circuit = true) { false }
        val primaryTiers = listOf(
            SearchGroup(intArrayOf(0), intArrayOf(), VariableSelection.InputOrder),
            SearchGroup(intArrayOf(0), intArrayOf()),
            SearchGroup(intArrayOf(), intArrayOf(0), VariableSelection.InputOrder),
            SearchGroup(intArrayOf(), intArrayOf(0), valueSelector = ValueSelection.Maximum),
        )
        for (primary in primaryTiers) {
            val annotation = SearchHints(listOf(primary, SearchGroup(intArrayOf(), intArrayOf(1, 2))))
                .toBacktrackParams(1, 3)

            val composed = PortfolioComposition.compose(scenario.copy(annotationArm = annotation), facts)

            assertEquals(0, composed.count { it.label == "first-fail" })
            assertEquals(1, composed.count { it.label == "annotation" })
        }
    }

    @Test
    fun `annotation diversity should preserve later source value preferences`() {
        val annotation = SearchHints(
            listOf(
                SearchGroup(intArrayOf(), intArrayOf(0, 1)),
                SearchGroup(intArrayOf(), intArrayOf(2), VariableSelection.InputOrder, ValueSelection.Maximum),
            ),
        ).toBacktrackParams(0, 3)
        val scenario = PortfolioScenario.sequential(Kind.COP).copy(annotationArm = annotation)
        val facts = ProblemFacts(ProblemFacts.assumed(Kind.COP).profile, circuit = true) { false }

        val composed = PortfolioComposition.compose(scenario, facts)

        assertEquals(0, composed.count { it.label == "first-fail" })
        assertEquals(1, composed.count { it.label == "annotation" })
    }

    @Test
    fun `empty source tiers should not hide the leading integer first fail preference`() {
        val annotation = SearchHints(
            listOf(
                SearchGroup(intArrayOf(), intArrayOf(), VariableSelection.InputOrder),
                SearchGroup(intArrayOf(), intArrayOf(0, 1)),
            ),
        ).toBacktrackParams(0, 3)
        val scenario = PortfolioScenario.sequential(Kind.COP).copy(annotationArm = annotation)
        val facts = ProblemFacts(ProblemFacts.assumed(Kind.COP).profile, circuit = true) { false }

        val composed = PortfolioComposition.compose(scenario, facts)

        assertEquals(1, composed.count { it.label == "first-fail" })
    }

    @Test
    fun `unrestricted annotation should already supply first fail branching`() {
        val scenario = PortfolioScenario.sequential(Kind.COP).copy(
            annotationArm = BacktrackParams(variableSelector = SmallestDomain, valueSelector = IndomainMin),
        )

        val facts = ProblemFacts(ProblemFacts.assumed(Kind.COP).profile, circuit = true) { true }
        val composed = PortfolioComposition.compose(scenario, facts)

        assertEquals(0, composed.count { it.label == "first-fail" })
        assertEquals(1, composed.count { it.label == "annotation" })
    }

    @Test
    fun `annotation diversity should preserve explicit pools small counts and parallel composition`() {
        val scenario = PortfolioScenario.sequential(Kind.COP).copy(
            annotationArm = SearchHints(listOf(SearchGroup(intArrayOf(), intArrayOf(0, 1))))
                .toBacktrackParams(0, 3),
        )
        val controls = listOf(
            scenario.copy(arms = 1),
            scenario.copy(cores = 2),
            scenario.copy(kind = Kind.CSP),
            scenario.copy(engine = EngineMix.BACKTRACK),
            scenario.copy(annotationArm = null),
            scenario.copy(btPool = listOf { BacktrackCatalog.byLabel("conflictDriven") }),
            scenario.copy(lsPool = listOf { LocalSearchCatalog.byLabel("cbls/fixed") }),
        )

        for (control in controls) {
            val facts = ProblemFacts(ProblemFacts.assumed(Kind.COP).profile, circuit = true) { true }
            val composed = PortfolioComposition.compose(control, facts)

            assertEquals(0, composed.count { it.label == "first-fail" }, control.toString())
        }
    }

    @Test
    fun `annotation diversity should require nonbinary finite circuit domains`() {
        val scenario = PortfolioScenario.sequential(Kind.COP).copy(
            annotationArm = SearchHints(listOf(SearchGroup(intArrayOf(), intArrayOf(0, 1))))
                .toBacktrackParams(0, 3),
        )
        val profile = ProblemFacts.assumed(Kind.COP).profile
        val controls = ProblemClass.entries.filter { it != ProblemClass.FiniteCp }
            .map { ProblemFacts(profile.copy(problemClass = it), circuit = true) { true } } +
            listOf(
                ProblemFacts(profile, binaryIntegers = true, circuit = true) { true },
                ProblemFacts(profile) { true },
            )

        for (facts in controls) {
            val composed = PortfolioComposition.compose(scenario, facts)

            assertEquals(0, composed.count { it.label == "first-fail" })
        }
    }

    @Test
    fun `expanded annotated pools should admit first fail before any incumbent without duplicating it`() {
        val scenario = PortfolioScenario.sequential(Kind.COP).copy(
            annotationArm = SearchHints(listOf(SearchGroup(intArrayOf(), intArrayOf(0, 1))))
                .toBacktrackParams(0, 3),
        )
        val facts = ProblemFacts(ProblemFacts.assumed(Kind.COP).profile, circuit = true) { false }
        val small = PortfolioComposition.plan(scenario, facts)

        for (count in listOf(9, 30)) {
            val expanded = PortfolioComposition.plan(scenario.copy(arms = count), facts)

            assertEquals(small.arms.map { it.label }, expanded.arms.take(small.arms.size).map { it.label })
            assertEquals(1, expanded.arms.count { it.label == "first-fail" })
            assertEquals(small.firstSolutionCount, expanded.firstSolutionCount)
        }
    }

    @Test
    fun `annotation diversity should spend the containing node allowance`() {
        val budget = NodeBudget(limit = 100)
        val scenario = PortfolioScenario.sequential(Kind.COP).copy(
            annotationArm = SearchHints(listOf(SearchGroup(intArrayOf(), intArrayOf(0, 1))))
                .toBacktrackParams(0, 3),
            nodeBudget = budget,
            lpCeiling = LpConfig.OFF,
        )

        val facts = ProblemFacts(ProblemFacts.assumed(Kind.COP).profile, circuit = true) { true }
        val composed = PortfolioComposition.compose(scenario, facts)

        val generic = composed.filterIsInstance<BacktrackWorkerConfig>().single { it.label == "first-fail" }
        assertSame(budget, generic.recipe.build(1L, null).nodeBudget)
    }

    @Test
    fun `integer linear companion should preserve existing worker positions`() {
        val scenario = PortfolioScenario.sequential(Kind.COP)
        val profile = ProblemFacts.assumed(Kind.COP).profile
        val baseline = PortfolioComposition.compose(scenario, ProblemFacts(profile, binaryIntegers = true) { true })

        val composed = PortfolioComposition.compose(
            scenario,
            ProblemFacts(profile, binaryIntegers = true, integerLinear = true) { true },
        )

        assertEquals(baseline.map { it.label } + "lp-aggressive", composed.map { it.label })
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
    fun `explicit and parallel integer linear pools should retain their composition`() {
        val scenario = PortfolioScenario.sequential(Kind.COP)
        val profile = ProblemFacts.assumed(Kind.COP).profile
        val baseline = ProblemFacts(profile) { true }
        val integer = ProblemFacts(profile, integerLinear = true) { true }
        for (control in listOf(
            scenario.copy(cores = 2),
            scenario.copy(engine = EngineMix.BACKTRACK),
            scenario.copy(kind = Kind.CSP),
            scenario.copy(arms = 5),
            scenario.copy(btEdit = { it }),
            scenario.copy(btPool = listOf { BacktrackCatalog.byLabel("conflictDriven") }),
            scenario.copy(lsPool = listOf { LocalSearchCatalog.byLabel("cbls/fixed") }),
            scenario.copy(annotationArm = BacktrackParams()),
        )) {
            assertEquals(
                PortfolioComposition.compose(control, baseline).map { it.label },
                PortfolioComposition.compose(control, integer).map { it.label },
            )
        }
    }

    @Test
    fun `Boolean objective pools should retain a core guided proof worker`() {
        val scenario = PortfolioScenario.sequential(Kind.COP)
        val ordinary = ProblemFacts.assumed(Kind.COP)
        val facts = ProblemFacts(ordinary.profile, booleanObjective = true) { true }

        val selected = PortfolioComposition.compose(scenario, facts)

        assertIs<CoreGuidedWorkerConfig>(selected.last())
        assertEquals(
            PortfolioComposition.compose(scenario, ordinary).map { it.label },
            selected.dropLast(1).map { it.label },
        )
    }

    @Test
    fun `finite mixed optimization should admit selector diversity without a relaxation`() {
        val scenario = PortfolioScenario.sequential(Kind.COP)
        val profile = ProblemFacts.assumed(Kind.COP).profile
        val facts = ProblemFacts(profile) { false }

        val plan = PortfolioComposition.plan(scenario, facts)

        assertEquals(
            listOf("satOptimized", "selector-switch"),
            plan.arms.filterIsInstance<BacktrackWorkerConfig>().map { it.label },
        )
        assertEquals(4, plan.arms.count { it is LocalSearchWorkerConfig })
    }

    @Test
    fun `explicit and continuous pools should keep selector switch out of the leading recipes`() {
        val scenario = PortfolioScenario.sequential(Kind.COP)
        val profile = ProblemFacts.assumed(Kind.COP).profile
        val finite = ProblemFacts(profile) { false }
        val controls = listOf(
            scenario.copy(cores = 2),
            scenario.copy(engine = EngineMix.BACKTRACK),
            scenario.copy(btPool = listOf { BacktrackCatalog.byLabel("conflictDriven") }),
            scenario.copy(annotationArm = BacktrackParams()),
        )
        val composed = controls.map { PortfolioComposition.compose(it, finite) } +
            listOf(ProblemClass.MixedInteger, ProblemClass.Sat).map { problemClass ->
                val facts = ProblemFacts(profile.copy(problemClass = problemClass)) { false }
                PortfolioComposition.compose(scenario, facts)
            }

        for (arms in composed) {
            assertTrue(arms.filterIsInstance<BacktrackWorkerConfig>().take(2).none { it.label == "selector-switch" })
        }
    }

    @Test
    fun `a pool with a relaxation should lead with the LP default recipe`() {
        val scenario = PortfolioScenario.sequential(Kind.COP)
        val relaxation = ProblemFacts(ProblemFacts.assumed(Kind.COP).profile) { true }

        val labels = PortfolioComposition.compose(scenario, relaxation).take(2).map { it.label }

        assertEquals(listOf("satOptimized", "lp-default"), labels)
    }

    @Test
    fun `expanded finite pools should keep the admitted selector position without duplicating it`() {
        val scenario = PortfolioScenario.sequential(Kind.COP)
        val facts = ProblemFacts(ProblemFacts.assumed(Kind.COP).profile) { false }
        val small = PortfolioComposition.plan(scenario, facts)

        val expanded = PortfolioComposition.plan(scenario.copy(arms = 18), facts)

        assertEquals(small.arms.map { it.label }, expanded.arms.take(small.arms.size).map { it.label })
        assertEquals(1, expanded.arms.count { it.label == "selector-switch" })
        assertEquals(1, expanded.arms.count { it.label == "conflictDriven" })
    }

    @Test
    fun `expanded pools should retain the initial selector beside later relaxation recipes`() {
        val scenario = PortfolioScenario.sequential(Kind.COP)
        val facts = ProblemFacts(ProblemFacts.assumed(Kind.COP).profile) { it == LpEmphasis.CONSERVATIVE }
        val small = PortfolioComposition.plan(scenario, facts)

        val expanded = PortfolioComposition.plan(scenario.copy(arms = 12), facts)

        assertEquals(small.arms.map { it.label }, expanded.arms.take(small.arms.size).map { it.label })
        assertTrue(expanded.arms.any { it.label == "lp-conservative" })
    }

    @Test
    fun `scheduled mixed optimization should pair primary and full plateau neighborhoods`() {
        val scenario = PortfolioScenario.sequential(Kind.COP)
        val ordinary = ProblemFacts.assumed(Kind.COP)
        val scheduling = ProblemFacts(ordinary.profile.copy(scheduling = true)) { true }
        val baseline = PortfolioComposition.compose(scenario, ordinary).filterIsInstance<LocalSearchWorkerConfig>()

        val paired = PortfolioComposition.compose(scenario, scheduling).filterIsInstance<LocalSearchWorkerConfig>()

        assertEquals("cbls-primary-plateau/ils-basin", paired.first().label)
        assertEquals("cbls-plateau/ils-basin", paired[1].label)
        assertEquals(baseline.drop(1).map { it.label }, paired.drop(1).map { it.label })
        assertEquals(baseline.size, paired.size)
    }

    @Test
    fun `explicit parallel and single engine scheduling pools should retain their recipes`() {
        val scenarios = listOf(
            PortfolioScenario.sequential(Kind.CSP),
            PortfolioScenario.parallel(4, Kind.COP),
            PortfolioScenario.sequential(Kind.COP, engine = EngineMix.LOCAL_SEARCH),
            PortfolioScenario.sequential(Kind.COP).copy(lsPool = listOf { LocalSearchCatalog.byLabel("cbls/fixed") }),
        )
        for (scenario in scenarios) {
            val ordinary = ProblemFacts.assumed(scenario.kind)
            val scheduling = ProblemFacts(ordinary.profile.copy(scheduling = true)) { true }

            val selected = PortfolioComposition.compose(scenario, scheduling)

            assertEquals(PortfolioComposition.compose(scenario, ordinary).map { it.label }, selected.map { it.label })
        }
    }

    @Test
    fun `curated pseudo Boolean satisfaction should enable bounded root maintenance`() {
        val budget = NodeBudget(limit = 100)
        val facts = ProblemFacts.assumed(Kind.CSP, ProblemClass.PseudoBoolean)
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
                assertEquals(4096L, arm.inprocessingWork)
                assertEquals(256L, arm.inprocessingRootWork)
                assertEquals(3072L, arm.inprocessingProbeWork)
                assertEquals(32L, arm.inprocessingInterval)
                assertEquals(8, arm.inprocessingGrowth)
                assertEquals(32L, arm.inprocessingCooldown)
                assertEquals(0L, arm.inprocessingWarmupWork)
                assertSame(budget, arm.nodeBudget)
            }
        }
    }

    @Test
    fun `disabling automatic root maintenance should retain stock root parameters`() {
        val scenario = PortfolioScenario.sequential(Kind.CSP, engine = EngineMix.BACKTRACK)
            .copy(rootMaintenance = false)
        val facts = ProblemFacts.assumed(Kind.CSP, ProblemClass.PseudoBoolean)
        val stock = BacktrackParams()

        val params = PortfolioComposition.compose(scenario, facts).filterIsInstance<BacktrackWorkerConfig>()
            .map { it.recipe.build(1L, null) }

        assertTrue(params.isNotEmpty())
        for (arm in params) {
            assertEquals(stock.rootSimplification, arm.rootSimplification)
            assertEquals(stock.rootSimplifyBatch, arm.rootSimplifyBatch)
            assertEquals(stock.rootProbeBatch, arm.rootProbeBatch)
            assertEquals(stock.rootProbeWork, arm.rootProbeWork)
            assertEquals(stock.inprocessingWork, arm.inprocessingWork)
            assertEquals(stock.inprocessingRootWork, arm.inprocessingRootWork)
            assertEquals(stock.inprocessingProbeWork, arm.inprocessingProbeWork)
            assertEquals(stock.inprocessingInterval, arm.inprocessingInterval)
            assertEquals(stock.inprocessingGrowth, arm.inprocessingGrowth)
            assertEquals(stock.inprocessingCooldown, arm.inprocessingCooldown)
            assertEquals(stock.inprocessingWarmupWork, arm.inprocessingWarmupWork)
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
        for (problemClass in ProblemClass.entries.filter { it != ProblemClass.PseudoBoolean }) {
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
            val facts = ProblemFacts.assumed(scenario.kind, ProblemClass.PseudoBoolean)

            val params = PortfolioComposition.compose(scenario, facts).filterIsInstance<BacktrackWorkerConfig>()
                .map { it.recipe.build(1L, null) }

            assertTrue(params.all { !it.rootSimplification && it.rootProbeBatch == 0 })
        }
    }

    @Test
    fun `explicit edits should override automatic root maintenance`() {
        val scenario = PortfolioScenario.sequential(Kind.CSP, engine = EngineMix.BACKTRACK).copy(
            btEdit = { it.copy(rootSimplification = false, rootProbeBatch = 0, inprocessingWork = 17L) },
        )
        val facts = ProblemFacts.assumed(Kind.CSP, ProblemClass.PseudoBoolean)

        val params = PortfolioComposition.compose(scenario, facts).filterIsInstance<BacktrackWorkerConfig>()
            .map { it.recipe.build(1L, null) }

        assertTrue(params.isNotEmpty())
        assertTrue(params.all { !it.rootSimplification && it.rootProbeBatch == 0 && it.inprocessingWork == 17L })
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
    fun `expanding a sequential optimization pool should keep the small pool positions`() {
        val small = PortfolioScenario.sequential(Kind.COP)
        for (problemClass in listOf(ProblemClass.FiniteCp, ProblemClass.MixedInteger)) {
            val facts = ProblemFacts.assumed(Kind.COP, problemClass)

            val baseline = PortfolioComposition.plan(small, facts)
            val expanded = PortfolioComposition.plan(small.copy(arms = 12), facts)

            assertEquals(baseline.arms.map { it.label }, expanded.arms.take(baseline.arms.size).map { it.label })
            assertEquals(baseline.firstSolutionCount, expanded.firstSolutionCount)
            assertEquals(13, expanded.arms.size)
        }
    }

    @Test
    fun `retained LP should join first solution search for finite and continuous objectives`() {
        val scenario = PortfolioScenario.sequential(Kind.COP)
        val profile = ProblemFacts.assumed(Kind.COP, ProblemClass.MixedInteger).profile

        for (continuousObjective in listOf(false, true)) {
            val facts = ProblemFacts(profile, continuousObjective = continuousObjective) { true }

            val plan = PortfolioComposition.plan(scenario, facts)

            assertEquals("lp-default", plan.arms.last().label)
            assertEquals(plan.arms.size, plan.firstSolutionCount)
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
    fun `binary packing should use one default LP in the existing backtrack slot`() {
        val scenario = PortfolioScenario.sequential(Kind.COP)
        val profile = ProblemFacts.assumed(Kind.COP).profile
        val baseline = PortfolioComposition.compose(scenario, ProblemFacts(profile, binaryIntegers = true) { true })
        for (ordinary in listOf(false, true)) {
            val facts = ProblemFacts(
                profile, binaryIntegers = true, integerLinear = ordinary, binaryPackingObjective = true,
            ) { true }

            val composed = PortfolioComposition.compose(scenario, facts)

            assertEquals(listOf("satOptimized", "lp-default"), composed.take(2).map { it.label })
            assertEquals(baseline.drop(2).map { it.label }, composed.drop(2).map { it.label })
        }
    }

    @Test
    fun `expanded binary packing pools should reuse the default LP recipe`() {
        val scenario = PortfolioScenario.sequential(Kind.COP).copy(arms = 24)
        val profile = ProblemFacts.assumed(Kind.COP).profile
        val baseline = ProblemFacts(profile, binaryIntegers = true) { true }
        val packing = ProblemFacts(profile, binaryIntegers = true, binaryPackingObjective = true) { true }

        val composed = PortfolioComposition.compose(scenario, packing)

        assertEquals(listOf("satOptimized", "lp-default"), composed.take(2).map { it.label })
        assertEquals(1, composed.count { it.label == "lp-default" })
        assertEquals(PortfolioComposition.compose(scenario, baseline).size, composed.size)
    }

    @Test
    fun `binary packing should preserve explicit parallel and disabled LP composition`() {
        val scenario = PortfolioScenario.sequential(Kind.COP)
        val profile = ProblemFacts.assumed(Kind.COP).profile
        val baseline = ProblemFacts(profile, binaryIntegers = true) { true }
        val packing = ProblemFacts(profile, binaryIntegers = true, binaryPackingObjective = true) { true }
        for (control in listOf(
            scenario.copy(cores = 2),
            scenario.copy(engine = EngineMix.BACKTRACK),
            scenario.copy(kind = Kind.CSP),
            scenario.copy(arms = 5),
            scenario.copy(lpCeiling = LpConfig.OFF),
            scenario.copy(btEdit = { it }),
            scenario.copy(btPool = listOf { BacktrackCatalog.byLabel("conflictDriven") }),
            scenario.copy(lsPool = listOf { LocalSearchCatalog.byLabel("cbls/fixed") }),
            scenario.copy(annotationArm = BacktrackParams()),
        )) {
            assertEquals(
                PortfolioComposition.compose(control, baseline).map { it.label },
                PortfolioComposition.compose(control, packing).map { it.label },
            )
        }
    }

    @Test
    fun `binary packing LP should spend the containing node allowance`() {
        val budget = NodeBudget(limit = 100)
        val scenario = PortfolioScenario.sequential(Kind.COP).copy(nodeBudget = budget)
        val facts = ProblemFacts(
            ProblemFacts.assumed(Kind.COP).profile, binaryIntegers = true, binaryPackingObjective = true,
        ) { true }

        val composed = PortfolioComposition.compose(scenario, facts)
        val lp = composed.filterIsInstance<BacktrackWorkerConfig>().single { it.label == "lp-default" }

        assertSame(budget, lp.recipe.build(1L, null).nodeBudget)
    }

    @Test
    fun `binary equality objectives should preserve existing worker positions with an LP companion`() {
        val scenario = PortfolioScenario.sequential(Kind.COP)
        val profile = ProblemFacts.assumed(Kind.COP).profile
        val baseline = PortfolioComposition.compose(scenario, ProblemFacts(profile, binaryIntegers = true) { true })
        val facts = ProblemFacts(profile, binaryIntegers = true, binaryEqualityObjective = true) { true }

        val composed = PortfolioComposition.compose(scenario, facts)

        assertEquals(baseline.map { it.label } + "lp-aggressive", composed.map { it.label })
    }

    @Test
    fun `binary equality objectives should preserve explicit parallel and disabled LP composition`() {
        val scenario = PortfolioScenario.sequential(Kind.COP)
        val profile = ProblemFacts.assumed(Kind.COP).profile
        for (control in listOf(
            scenario.copy(cores = 2),
            scenario.copy(engine = EngineMix.BACKTRACK),
            scenario.copy(kind = Kind.CSP),
            scenario.copy(arms = 5),
            scenario.copy(lpCeiling = LpConfig.OFF),
            scenario.copy(btEdit = { it }),
            scenario.copy(btPool = listOf { BacktrackCatalog.byLabel("conflictDriven") }),
            scenario.copy(lsPool = listOf { LocalSearchCatalog.byLabel("cbls/fixed") }),
            scenario.copy(annotationArm = BacktrackParams()),
        )) {
            val available = control.lpCeiling != LpConfig.OFF
            val baseline = ProblemFacts(profile, binaryIntegers = true) { available }
            val equality = ProblemFacts(profile, binaryIntegers = true, binaryEqualityObjective = true) { available }

            assertEquals(
                PortfolioComposition.compose(control, baseline).map { it.label },
                PortfolioComposition.compose(control, equality).map { it.label },
            )
        }
    }

    @Test
    fun `binary equality LP should spend the containing node allowance`() {
        val budget = NodeBudget(limit = 100)
        val scenario = PortfolioScenario.sequential(Kind.COP).copy(nodeBudget = budget)
        val facts = ProblemFacts(
            ProblemFacts.assumed(Kind.COP).profile, binaryIntegers = true, binaryEqualityObjective = true,
        ) { true }

        val composed = PortfolioComposition.compose(scenario, facts)
        val lp = composed.filterIsInstance<BacktrackWorkerConfig>().single { it.label == "lp-aggressive" }

        assertSame(budget, lp.recipe.build(1L, null).nodeBudget)
    }
}
