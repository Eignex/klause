package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.Problem
import com.eignex.klause.portfolio.EngineMix
import com.eignex.klause.portfolio.Kind
import com.eignex.klause.portfolio.PortfolioScenario
import com.eignex.klause.solver.ProblemClass
import com.eignex.klause.solver.ProblemProfile

internal fun privateRebuildDefaultScenario(
    scenario: PortfolioScenario,
    problem: Problem,
    profile: ProblemProfile,
    options: ExecutionOptions,
    enumerating: Boolean,
): PortfolioScenario {
    if (enumerating || options.explicitArms || scenario.explicitArms || scenario.arms != 2 ||
        scenario.cores != 1 || scenario.kind != Kind.CSP || scenario.engine != EngineMix.MIXED ||
        profile.problemClass != ProblemClass.Sat || scenario.btPool != null || scenario.lsPool != null ||
        scenario.annotationArm != null || (scenario.btEdit != null && !options.privateRebuildBudgetOnly)
    ) return scenario
    if (problem.numBoolVars !in 128..2_000 || problem.numFactors !in 512..32_768) return scenario
    val reader = options.reader()
    if (reader[ExecutionOption.privateRebuildWork] != null ||
        reader[ExecutionOption.booleanProofTasks] == true) return scenario
    val privateBytes = reader[ExecutionOption.privateRebuildBytes]
    val sharedScratch = reader[ExecutionOption.learnedMemoryScratchBytes]
    val edit = scenario.btEdit
    return scenario.copy(btEdit = { base ->
        val configured = edit?.invoke(base) ?: base
        configured.copy(privateRebuildWork = -1L,
            privateRebuildBytes = privateBytes ?: sharedScratch ?: 4_194_304L)
    })
}
