package com.eignex.klause.portfolio

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.NodeBudget
import com.eignex.klause.backtrack.selector.IndomainMin
import com.eignex.klause.backtrack.selector.SmallestDomain
import com.eignex.klause.backtrack.toBacktrackParams
import com.eignex.klause.factor.arithmetic.ExactLinear
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.SearchGroup
import com.eignex.klause.ir.SearchHints
import com.eignex.klause.ir.Term
import com.eignex.klause.ir.ValueSelection
import com.eignex.klause.ir.VariableSelection
import com.eignex.klause.lp.bounding.LpConfig
import com.eignex.klause.lp.bounding.LpEmphasis
import com.eignex.klause.propagation.bake
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.ProblemClass
import com.eignex.klause.solver.ProblemProfile
import com.eignex.klause.util.bigIntOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PortfolioCompositionTest {

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
    fun `exact integer equivalents should preserve ordinary row composition under caller controls`() {
        val ordinary = Problem(0, 2, Array(2) { IntDomain(0, 5) }, arrayOf<Factor>(
            Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 3),
        )).bake()
        val refs = intArrayOf(Term.ofIntVar(0), Term.ofIntVar(1))
        val half = BigFraction.of(bigIntOf(1), bigIntOf(2))
        val rows = listOf(
            ExactLinear.of(refs, List(2) { BigFraction.ONE }, LinearOp.GE, BigFraction.ofLong(3)),
            ExactLinear.of(refs, List(2) { half }, LinearOp.GE, BigFraction.of(bigIntOf(3), bigIntOf(2))),
            ExactLinear.of(refs, List(2) { BigFraction.ONE }, LinearOp.GE, BigFraction.ofLong(2), strict = true),
        )
        val scenario = PortfolioScenario.sequential(Kind.COP)
        val controls = listOf(
            scenario,
            scenario.copy(lpCeiling = LpConfig.OFF),
            scenario.copy(lpCeiling = LpConfig.parse("aggressive,-cuts")),
            scenario.copy(cores = 2),
            scenario.copy(engine = EngineMix.BACKTRACK),
            scenario.copy(kind = Kind.CSP),
            scenario.copy(arms = 5),
            scenario.copy(btEdit = { it }),
            scenario.copy(btPool = listOf { BacktrackCatalog.byLabel("conflictDriven") }),
            scenario.copy(lsPool = listOf { LocalSearchCatalog.byLabel("cbls/fixed") }),
            scenario.copy(annotationArm = BacktrackParams()),
        )
        for (row in rows) {
            val exact = Problem(0, 2, Array(2) { IntDomain(0, 5) }, arrayOf<Factor>(row)).bake()
            for (control in controls) {
                val ordinaryFacts = ProblemFacts.of(ordinary, ProblemProfile.of(ordinary, true), control.lpCeiling)
                val exactFacts = ProblemFacts.of(exact, ProblemProfile.of(exact, true), control.lpCeiling)
                val expected = PortfolioComposition.compose(control, ordinaryFacts).map { it.label }

                val composed = PortfolioComposition.compose(control, exactFacts)

                assertEquals(expected, composed.map { it.label })
            }
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
    fun `auxiliary LP should join finite objectives after the incumbent and continuous objectives before it`() {
        val scenario = PortfolioScenario.sequential(Kind.COP)
        val profile = ProblemFacts.assumed(Kind.COP, ProblemClass.MixedInteger).profile

        for (continuousObjective in listOf(false, true)) {
            val facts = ProblemFacts(profile, continuousObjective = continuousObjective) { true }

            val plan = PortfolioComposition.plan(scenario, facts)

            assertEquals("lp-default", plan.arms.last().label)
            assertEquals(plan.arms.size - if (continuousObjective) 0 else 1, plan.firstSolutionCount)
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
}
