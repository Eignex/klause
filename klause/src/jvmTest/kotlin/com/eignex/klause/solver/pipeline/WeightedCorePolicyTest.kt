package com.eignex.klause.solver.pipeline

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.factor.arithmetic.ReifiedCardinality
import com.eignex.klause.factor.arithmetic.ReifiedPseudoBoolean
import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.bool.PseudoBoolean
import com.eignex.klause.factor.bool.Xor
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.meta.coreguided.CoreRefinementMode
import com.eignex.klause.model.PbOp
import com.eignex.klause.portfolio.BacktrackCatalog
import com.eignex.klause.portfolio.EngineMix
import com.eignex.klause.portfolio.Kind
import com.eignex.klause.portfolio.LocalSearchCatalog
import com.eignex.klause.portfolio.PortfolioScenario
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.parseBigInt
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

@RunWith(Parameterized::class)
class WeightedCorePolicyTest(private val variant: String, private val automatic: Boolean) {
    @Test
    fun `automatic weighted core selection should preserve explicit policy and containing resources`() {
        val ordinary = PortfolioScenario.sequential(Kind.COP, seed = 17L)
        val scenario = when (variant) {
            "satisfaction" -> ordinary.copy(kind = Kind.CSP)
            "parallel" -> ordinary.copy(cores = 2)
            "local-search" -> ordinary.copy(engine = EngineMix.LOCAL_SEARCH)
            "complete-only" -> ordinary.copy(engine = EngineMix.BACKTRACK)
            "explicit-count" -> ordinary.copy(explicitArms = true)
            "custom-count" -> ordinary.copy(arms = 4)
            "custom-backtrack" -> ordinary.copy(btPool = listOf { BacktrackCatalog.byLabel("satOptimized") })
            "custom-local" -> ordinary.copy(lsPool = listOf { LocalSearchCatalog.byLabel("cbls-plateau/ils-basin") })
            "annotation" -> ordinary.copy(annotationArm = BacktrackParams())
            "backtrack-edit" -> ordinary.copy(btEdit = { it })
            "work-driven" -> ordinary.copy(diagnosticWorkLimit = 3_000L)
            "resolved-early" -> ordinary.copy(earlyCoreAdmission = true)
            "resolved-refinement" -> ordinary.copy(coreRefinement = CoreRefinementMode.DELETION)
            else -> ordinary
        }
        val options = when (variant) {
            "early-opt-out" -> ExecutionOptions(ExecutionOption.coreEarly(false))
            "refinement-opt-out" -> ExecutionOptions(ExecutionOption.coreRefinement(CoreRefinementMode.NONE))
            "explicit-option-count" -> ExecutionOptions(ExecutionOption.arms(6))
            else -> ExecutionOptions()
        }
        val booleans = when (variant) {
            "boundary" -> 16_384
            "column-cap" -> 16_385
            "entry-cap" -> 128
            else -> 3
        }
        val factorCount = when (variant) {
            "boundary", "entry-cap" -> 16_384
            "factor-cap" -> 16_385
            else -> 1
        }
        val integers = if (variant == "integer") 1 else 0
        val reals = if (variant == "real") 1 else 0
        val literals = intArrayOf(Lit.make(1, true), Lit.make(2, true))
        val factor: Factor = when (variant) {
            "entry-cap" -> Clause(IntArray(128) { Lit.make(it, true) })
            "unsupported-row" -> Xor(literals, targetParity = 1)
            "cardinality" -> Cardinality(literals, min = 1, max = 2)
            "reified-cardinality" -> ReifiedCardinality(0, literals, min = 1, max = 2)
            "pseudo-boolean" -> PseudoBoolean(longArrayOf(1, 1), literals, PbOp.GE, 1L)
            "reified-pseudo-boolean" -> ReifiedPseudoBoolean(0, longArrayOf(1, 1), literals, PbOp.GE, 1L)
            "wide-row" -> PseudoBoolean(arrayOf(parseBigInt("18446744073709551616"), bigIntOf(1)),
                literals, PbOp.GE, bigIntOf(1))
            "wide-reified-row" -> ReifiedPseudoBoolean(0,
                arrayOf(parseBigInt("18446744073709551616"), bigIntOf(1)), literals, PbOp.GE, bigIntOf(1))
            else -> Clause(literals)
        }
        val problem = Problem(booleans, integers, Array(integers) { IntDomain(0, 1) },
            Array<Factor>(factorCount) { factor }, numRealVars = reals,
            realLower = DoubleArray(reals), realUpper = DoubleArray(reals) { 1.0 })
        val weights = when (variant) {
            "uniform" -> longArrayOf(7, 7, 7)
            "single-term" -> longArrayOf(0, 7, 0)
            "zero-objective" -> longArrayOf(0, 0, 0)
            "signed" -> longArrayOf(-1, 7, -7)
            "wide-cancellation" -> longArrayOf(Long.MIN_VALUE, Long.MAX_VALUE, 1)
            else -> longArrayOf(1, 7, 7)
        }
        val constant = when (variant) {
            "unsafe-scalar" -> 9_007_199_254_740_991L
            "wide-constant" -> Long.MIN_VALUE
            "shifted" -> 19L
            else -> 0L
        }
        val objective = if (variant == "no-objective") null else LinearObjective(weights,
            intCoefficients = LongArray(integers) { 1L },
            constant = constant)
        var charged = 0L
        val stop = if (variant == "stopped-scan") object : Cancellation {
            override fun isCancelled(): Boolean = charged >= 2L
            override fun workMeter(): WorkMeter = WorkMeter { charged += it }
        } else Cancellation.Never

        val selected = weightedCoreDefaultScenario(scenario, problem, objective, options, stop)

        if (automatic) {
            assertEquals(scenario.copy(earlyCoreAdmission = true, coreRefinement = CoreRefinementMode.REASONS),
                selected)
        } else {
            assertSame(scenario, selected)
        }
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}, automatic={1}")
        fun cases(): List<Array<Any>> = listOf("weighted", "signed", "boundary", "uniform", "single-term",
            "zero-objective", "integer", "real", "column-cap", "factor-cap", "unsafe-scalar", "wide-cancellation",
            "satisfaction", "parallel", "local-search", "complete-only", "explicit-count", "custom-count",
            "custom-backtrack", "custom-local", "annotation", "backtrack-edit", "work-driven", "resolved-early",
            "resolved-refinement", "early-opt-out", "refinement-opt-out", "explicit-option-count", "stopped-scan",
            "no-objective", "entry-cap", "unsupported-row", "cardinality", "reified-cardinality", "pseudo-boolean",
            "reified-pseudo-boolean", "wide-row", "wide-reified-row", "shifted", "wide-constant").map {
            arrayOf<Any>(it, it in setOf("weighted", "signed", "boundary", "cardinality", "reified-cardinality",
                "pseudo-boolean", "reified-pseudo-boolean", "shifted"))
        }
    }
}
