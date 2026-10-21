package com.eignex.klause.solver.pipeline

import com.eignex.klause.backtrack.TieredVariableSelector
import com.eignex.klause.backtrack.selector.SolutionGuided
import com.eignex.klause.backtrack.toBacktrackParams
import com.eignex.klause.factor.circuit.Circuit
import com.eignex.klause.formats.flatzinc.linearObjective
import com.eignex.klause.formats.flatzinc.parseFlatZinc
import com.eignex.klause.formats.flatzinc.toSearchHints
import com.eignex.klause.ir.VariableSelection
import com.eignex.klause.lp.bounding.LpConfig
import com.eignex.klause.portfolio.Kind
import com.eignex.klause.portfolio.PortfolioBuilder
import com.eignex.klause.portfolio.PortfolioScenario
import com.eignex.klause.presolve.PresolveConfig
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.incumbent.Candidate
import com.eignex.klause.solver.incumbent.Verification
import com.eignex.klause.solver.validation.finiteWitnessVerifier
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SourceSearchAdmissionTest {
    @Test
    fun `translated circuit search should publish reconstructed source witnesses and objectives`() {
        val program = parseFlatZinc(
            "var 1..3: a; var 1..3: b; var 1..3: c; " +
                "constraint circuit([a,b,c]); " +
                "solve :: int_search([a,b,c], first_fail, indomain_min, complete) minimize a;",
        )
        val objective = assertNotNull(program.linearObjective())
        val invocation = SourceSetup.prepare(SourceSetupRequest(
            SourceAttachment(program.problem, program.definitionHints,
                assertNotNull(program.searchHints).toSearchHints()),
            ExecutionOperation.OPTIMIZE, FiniteEngine.MIXED, objective,
        ))
        val points = ArrayList<Sample>()

        val result = assertIs<SourceExecutionResult.Finite>(SourceSetup.execute(
            SourceExecutionRequest(invocation, presolveConfig = PresolveConfig.parse("affine")),
            SourceExecutionCallbacks(FiniteSolveCallbacks(points::add, { points += it.sample })),
        ))

        val outcome = assertIs<FiniteSolveOutcome.Completed>(result.result.outcome)
        val best = assertNotNull(outcome.bestSample)
        assertEquals(2L, objective.evaluateLong(best))
        assertEquals(listOf(2L, 3L, 1L), listOf("a", "b", "c").map {
            best.intValue(program.intVarsByName.getValue(it))
        })
        assertTrue(points.isNotEmpty())
        val verifier = finiteWitnessVerifier(program.problem.bake(), objective)
        for (point in points + best) {
            assertIs<Verification.Accepted<*, *>>(verifier.verify(Candidate(point, objective.evaluate(point))))
        }
    }

    @Test
    fun `source circuit search should retain first fail admission after affine successor normalization`() {
        for (predicate in listOf("circuit", "subcircuit")) {
            val program = parseFlatZinc(
                "array [1..3] of var 1..3: p; var 0..2: cost; " +
                    "constraint $predicate(p); " +
                    "solve :: seq_search([int_search(p, first_fail, indomain_min, complete), " +
                    "int_search([cost], input_order, indomain_min, complete)]) minimize cost;",
            )
            val invocation = SourceSetup.prepare(SourceSetupRequest(
                SourceAttachment(program.problem, program.definitionHints,
                    assertNotNull(program.searchHints).toSearchHints()),
                ExecutionOperation.OPTIMIZE, FiniteEngine.MIXED, assertNotNull(program.linearObjective()),
            ))
            val shape = invocation.finiteShape()
            val preparation = FinitePipeline.prepare(FinitePipelineRequest(
                shape.problem, FiniteEngine.MIXED, shape.linearObjective, PresolveConfig.parse("affine"),
                explicitPresolveConfig = true, preparedPlan = shape.preparedPlan,
            ))
            val hints = assertNotNull(assertNotNull(shape.searchHints)
                .remapped(assertNotNull(preparation.sourceMapping), Cancellation.Never))
            val annotation = hints.toBacktrackParams(preparation.problem.numBoolVars, preparation.problem.numIntVars)

            val workers = PortfolioBuilder.build(preparation.executableProblem(),
                PortfolioScenario.sequential(Kind.COP).copy(annotationArm = annotation, lpCeiling = LpConfig.OFF),
                preparation.objective)

            try {
                val successors = preparation.problem.factors.filterIsInstance<Circuit>().single().succ.toList()
                assertEquals(successors, hints.tiers.first().intVars.toList())
                assertEquals(VariableSelection.InputOrder, hints.tiers.last().varSelector)
                assertIs<TieredVariableSelector>(annotation.variableSelector)
                assertIs<SolutionGuided>(annotation.valueSelector)
                val generic = workers.single { it.label == "bt/first-fail" }
                assertFalse(generic.improvementOnly)
                assertTrue(generic.permitsSourceProofs)
            } finally {
                workers.forEach { it.close() }
            }
        }
    }
}
