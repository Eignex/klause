package com.eignex.klause.solver.pipeline

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.portfolio.BacktrackCatalog
import com.eignex.klause.portfolio.EngineMix
import com.eignex.klause.portfolio.Kind
import com.eignex.klause.portfolio.LocalSearchCatalog
import com.eignex.klause.portfolio.PortfolioScenario
import com.eignex.klause.solver.ProblemClass
import com.eignex.klause.solver.ProblemProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame

class PrivateRebuildPolicyTest {
    @Test
    fun `ordinary private rebuilding should own its byte grant and retain canonical scheduling`() {
        val problem = Problem(130, 0, emptyArray(), Array(512) { Clause(intArrayOf(Lit.make(0, true))) })
        val profile = ProblemProfile.of(problem, false)
        val scenario = PortfolioScenario.sequential(Kind.CSP).copy(arms = 2)

        val selected = privateRebuildDefaultScenario(scenario, problem, profile, ExecutionOptions(), false)
        val params = assertNotNull(selected.btEdit).invoke(BacktrackParams())

        assertEquals(-1L, params.privateRebuildWork)
        assertEquals(4_194_304L, params.privateRebuildBytes)
        assertEquals(1_048_576L, params.learnedMemoryScratchBytes)
        assertEquals(2, selected.arms)
        assertEquals(listOf(0.0, 0.98), clausalProofShares(selected, profile,
            listOf("ls/probsat/plain", "bt/clausal-balanced"), privateRebuildBudgetOnly = true).toList())
    }

    @Test
    fun `private defaults should honor typed and parsed caller byte limits`() {
        val problem = Problem(130, 0, emptyArray(), Array(512) { Clause(intArrayOf(Lit.make(0, true))) })
        val profile = ProblemProfile.of(problem, false)
        for ((options, expectedPrivate, expectedShared) in listOf(
            Triple(ExecutionOptions(ExecutionOption.learnedMemoryScratchBytes(0L)), 0L, 0L),
            Triple(ExecutionOptions.parse(listOf("learned-memory-scratch-bytes=0")), 0L, 0L),
            Triple(ExecutionOptions(ExecutionOption.learnedMemoryScratchBytes(1_048_576L)), 1_048_576L, 1_048_576L),
            Triple(ExecutionOptions.parse(listOf("learned-memory-scratch-bytes=1048576")), 1_048_576L, 1_048_576L),
            Triple(ExecutionOptions(ExecutionOption.privateRebuildBytes(2_000_000L)), 2_000_000L, 1_048_576L),
            Triple(ExecutionOptions.parse(listOf("private-rebuild-bytes=2000000")), 2_000_000L, 1_048_576L),
        )) {
            val configured = assertNotNull(backtrackOverride(options.reader(), allowSelectors = true))
            val scenario = PortfolioScenario.sequential(Kind.CSP).copy(arms = 2, btEdit = configured)

            val selected = privateRebuildDefaultScenario(scenario, problem, profile, options, false)
            val params = assertNotNull(selected.btEdit).invoke(BacktrackParams())

            assertEquals(-1L, params.privateRebuildWork)
            assertEquals(expectedPrivate, params.privateRebuildBytes)
            assertEquals(expectedShared, params.learnedMemoryScratchBytes)
        }
    }

    @Test
    fun `explicit work settings should retain their private policy`() {
        val problem = Problem(130, 0, emptyArray(), Array(512) { Clause(intArrayOf(Lit.make(0, true))) })
        val profile = ProblemProfile.of(problem, false)
        val scenario = PortfolioScenario.sequential(Kind.CSP).copy(arms = 2)
        for (work in listOf(0L, -1L, 65_536L)) {
            for (options in listOf(ExecutionOptions(ExecutionOption.privateRebuildWork(work)),
                ExecutionOptions.parse(listOf("private-rebuild-work=$work")))) {
                assertSame(scenario, privateRebuildDefaultScenario(scenario, problem, profile, options, false))
            }
        }
    }

    @Test
    fun `private defaults should preserve unsupported and explicitly composed routes`() {
        val problem = Problem(130, 0, emptyArray(), Array(512) { Clause(intArrayOf(Lit.make(0, true))) })
        val profile = ProblemProfile.of(problem, false)
        val scenario = PortfolioScenario.sequential(Kind.CSP).copy(arms = 2)
        for (control in listOf(scenario.copy(cores = 2), scenario.copy(kind = Kind.COP),
            scenario.copy(engine = EngineMix.BACKTRACK), scenario.copy(explicitArms = true),
            scenario.copy(arms = 6), scenario.copy(btEdit = { it }),
            scenario.copy(btPool = listOf { BacktrackCatalog.byLabel("clausal-balanced") }),
            scenario.copy(lsPool = listOf { LocalSearchCatalog.byLabel("probsat/plain") }),
            scenario.copy(annotationArm = BacktrackParams()))) {
            assertSame(control, privateRebuildDefaultScenario(control, problem, profile, ExecutionOptions(), false))
        }
        assertSame(scenario, privateRebuildDefaultScenario(scenario, problem, profile, ExecutionOptions(), true))
        assertSame(scenario, privateRebuildDefaultScenario(scenario, problem,
            profile.copy(problemClass = ProblemClass.PseudoBoolean), ExecutionOptions(), false))
        for (columns in listOf(127, 2_001)) {
            val outside = Problem(columns, 0, emptyArray(), problem.factors)
            assertSame(scenario, privateRebuildDefaultScenario(scenario, outside, profile, ExecutionOptions(), false))
        }
        for (count in listOf(511, 32_769)) {
            val outside = Problem(130, 0, emptyArray(), Array(count) { problem.factors[0] })
            assertSame(scenario, privateRebuildDefaultScenario(scenario, outside, profile, ExecutionOptions(), false))
        }
    }
}
