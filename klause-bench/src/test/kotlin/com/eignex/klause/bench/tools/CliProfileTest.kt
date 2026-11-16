package com.eignex.klause.bench.tools

import kotlin.test.Test
import kotlin.test.assertEquals

class CliProfileTest {
    @Test
    fun `polynomial checkpoint callers should retain the operation beneath cancellation adapters`() {
        val callers = CliProfile.cliPolynomialCheckpointCallers(listOf(
            "com.eignex.klause.util.OrCancellation.isCancelled",
            "com.eignex.klause.solver.pipeline.QuadraticNativeScope.getStopped",
            "com.eignex.klause.theory.qfnra.QfNraWork.isCancelled",
            "com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic.step",
            "com.eignex.klause.theory.qfnra.PolynomialSignPropagationKt.possibleSigns",
            "com.eignex.klause.theory.qfnra.PolynomialBooleanSigns.prepare",
        ))

        assertEquals(
            "com.eignex.klause.theory.qfnra.PolynomialSignPropagationKt.possibleSigns <- " +
                "com.eignex.klause.theory.qfnra.PolynomialBooleanSigns.prepare",
            callers,
        )
    }

    @Test
    fun `polynomial checkpoint callers should retain arithmetic frames and bounded ancestry`() {
        val stack = listOf(
            "arithmetic.polynomial.PolynomialArithmetic.step",
            "arithmetic.polynomial.RationalPolynomialArithmetic.divide",
            "arithmetic.polynomial.RationalPolynomialArithmetic.sturm",
            "arithmetic.polynomial.RationalPolynomialArithmetic.refine",
            "arithmetic.polynomial.AlgebraicArithmetic.compare",
            "theory.qfnra.QfNraSolverKt.liftFrame",
        ).map { "com.eignex.klause.$it" }

        val callers = CliProfile.cliPolynomialCheckpointCallers(stack)

        assertEquals(stack.drop(1).take(4).joinToString(" <- "), callers)
    }

    @Test
    fun `missing polynomial checkpoints should leave operation attribution unknown`() {
        for (stack in listOf(emptyList(), listOf(
            "com.eignex.klause.util.OrCancellation.isCancelled",
            "com.eignex.klause.theory.qfnra.PolynomialBooleanSigns.prepare",
        ), listOf("com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic.stepExtra"))) {
            val callers = CliProfile.cliPolynomialCheckpointCallers(stack)

            assertEquals("unknown: no captured polynomial checkpoint", callers)
        }
    }

    @Test
    fun `truncated polynomial checkpoint ancestors should remain unknown`() {
        val callers = CliProfile.cliPolynomialCheckpointCallers(listOf(
            "com.eignex.klause.theory.qfnra.QfNraWork.isCancelled",
            "com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic.step",
        ))

        assertEquals("unknown: no captured caller below polynomial checkpoint", callers)
    }

    @Test
    fun `context markers should recognize concrete entry frames`() {
        for ((frame, expected) in listOf(
            "solver.pipeline.ResumableOpenTheory.runSlice" to "open-theory entry",
            "solver.pipeline.OpenTheoryEngine\$OpenTheoryRun.advance" to "open-theory entry",
            "solver.pipeline.RetainedLinearQueries.check" to "retained-query entry",
            "solver.pipeline.RetainedLinearQueries.decide" to "retained-query entry",
            "solver.pipeline.RetainedLinearQueries.prepared" to "retained-query entry",
            "solver.pipeline.RetainedLinearQueries.prepareBooleanCenter" to "retained-query entry",
            "solver.pipeline.ExactNeighborhood.advance" to "neighborhood entry",
            "solver.pipeline.OpenExactNeighborhood.check" to "neighborhood entry",
            "lp.LpProbeSolver.probe" to "presolve probe entry",
            "theory.qflra.QfLiraSolverKt.sharedExactLiraSource" to "source preparation entry",
            "solver.pipeline.PreparedSearchComponents.prepareSource" to "source preparation entry",
        )) {
            val markers = CliProfile.cliContextMarkers(listOf("com.eignex.klause.$frame"))

            assertEquals("observed: $expected", markers, frame)
        }
    }

    @Test
    fun `missing or truncated context ancestors should remain unknown`() {
        for (stack in listOf(emptyList(), listOf("java.util.HashMap.getNode"), listOf(
            "com.eignex.klause.lp.LpPropagator.solve",
            "com.eignex.klause.solver.search.ExactLiraSearchComponent.relax",
        ))) {
            val markers = CliProfile.cliContextMarkers(stack)

            assertEquals("unknown: no captured context marker", markers)
        }
    }

    @Test
    fun `context markers should preserve nested method frames`() {
        for (frame in listOf(
            "com.eignex.klause.solver.pipeline.ExactNeighborhood\$advance\$1.invokeSuspend",
            "com.eignex.klause.solver.pipeline.ExactNeighborhood.advance\$default",
        )) {
            val markers = CliProfile.cliContextMarkers(listOf(frame))

            assertEquals("observed: neighborhood entry", markers, frame)
        }
    }

    @Test
    fun `repeated context frames should contribute one marker`() {
        val markers = CliProfile.cliContextMarkers(listOf(
            "com.eignex.klause.solver.pipeline.RetainedLinearQueries.check",
            "com.eignex.klause.solver.pipeline.RetainedLinearQueries.decide",
            "com.eignex.klause.solver.pipeline.RetainedLinearQueries.check",
        ))

        assertEquals("observed: retained-query entry", markers)
    }

    @Test
    fun `common engine ancestors should preserve multiple captured contexts`() {
        val stack = listOf(
            "com.eignex.klause.solver.pipeline.ExactNeighborhood.advance",
            "com.eignex.klause.solver.pipeline.RetainedLinearQueries.decide",
            "com.eignex.klause.solver.pipeline.ResumableOpenTheory.runSlice",
        )

        for (frames in listOf(stack, stack.reversed())) {
            val markers = CliProfile.cliContextMarkers(frames)

            assertEquals("multiple observed: open-theory entry + retained-query entry + neighborhood entry", markers)
        }
    }

    @Test
    fun `context matching should reject similar package class and method prefixes`() {
        for (frame in listOf(
            "other.com.eignex.klause.solver.pipeline.ExactNeighborhood.advance",
            "com.eignex.klause.solver.pipeline.ExactNeighborhoodExtra.advance",
            "com.eignex.klause.solver.pipeline.ExactNeighborhood.advanceExtra",
            "com.eignex.klause.solver.pipeline.OtherExactNeighborhood.advance",
            "com.eignex.klause.solver.pipeline.OpenTheoryEngine\$OpenTheoryRun.advanceExtra",
            "com.eignex.klause.lp.LpProbeSolver.probeExtra",
        )) {
            val markers = CliProfile.cliContextMarkers(listOf(frame))

            assertEquals("unknown: no captured context marker", markers, frame)
        }
    }

    @Test
    fun `model caller paths should retain source ancestors beyond mechanism packages`() {
        val callers = CliProfile.cliModelCallers(listOf(
            "java.util.HashMap.getNode",
            "com.eignex.klause.util.OrCancellation.isCancelled",
            "com.eignex.klause.bench.tools.CliProfile.summarizeCli",
            "com.eignex.klause.lp.engine.LpExactState.<init>",
            "com.eignex.klause.lp.lattice.IntegerEchelon.reduce",
            "com.eignex.klause.simplex.exact.BigFraction.plus",
            "com.eignex.klause.arithmetic.difference.DifferenceGraph.potentials",
            "com.eignex.klause.theory.qflra.LiveQfLraSystem.assertComparison",
            "com.eignex.klause.solver.search.SearchSession.propagate",
        ))

        assertEquals(
            "com.eignex.klause.theory.qflra.LiveQfLraSystem.assertComparison <- " +
                "com.eignex.klause.solver.search.SearchSession.propagate",
            callers,
        )
    }

    @Test
    fun `model caller paths should retain at most four captured frames`() {
        val stack = listOf("First", "Second", "Third", "Fourth", "Fifth")
            .map { "com.eignex.klause.lp.$it.run" }

        val callers = CliProfile.cliModelCallers(stack)

        assertEquals(
            "com.eignex.klause.lp.First.run <- com.eignex.klause.lp.Second.run <- " +
                "com.eignex.klause.lp.Third.run <- com.eignex.klause.lp.Fourth.run",
            callers,
        )
    }

    @Test
    fun `package filters should preserve model-facing arithmetic and LP owners`() {
        for (frame in listOf(
            "com.eignex.klause.factor.arithmetic.Linear.computeViolation",
            "com.eignex.klause.lp.relaxation.CpToLpRelaxation.prepare",
            "com.eignex.klause.bound.KnapsackLagrangianBound.compute",
        )) {
            val callers = CliProfile.cliModelCallers(listOf(
                "com.eignex.klause.simplex.exact.BigFraction.plus",
                frame,
            ))

            assertEquals(frame, callers)
        }
    }

    @Test
    fun `stacks without model callers should retain an explicit unknown label`() {
        for (stack in listOf(emptyList(), listOf("java.util.HashMap.getNode"), listOf(
            "com.eignex.klause.util.OrCancellation.isCancelled",
            "com.eignex.klause.lp.engine.LpExactState.<init>",
            "com.eignex.klause.lp.lattice.IntegerEchelon.reduce",
            "com.eignex.klause.simplex.exact.BigFraction.plus",
            "com.eignex.klause.arithmetic.difference.DifferenceGraph.potentials",
        ))) {
            val callers = CliProfile.cliModelCallers(stack)

            assertEquals("no captured model-facing caller", callers)
        }
    }

    @Test
    fun `operation caller paths should preserve captured order beyond utilities`() {
        val callers = CliProfile.cliOperationCallers(listOf(
            "java.util.HashMap.getNode",
            "com.eignex.klause.util.OrCancellation.isCancelled",
            "com.eignex.klause.theory.qflra.LiveQfLraSystem.install",
            "com.eignex.klause.solver.search.SearchSession.initialize",
        ))

        assertEquals(
            "com.eignex.klause.theory.qflra.LiveQfLraSystem.install <- " +
                "com.eignex.klause.solver.search.SearchSession.initialize",
            callers,
        )
    }

    @Test
    fun `operation caller paths should retain at most three frames`() {
        val stack = listOf("First", "Second", "Third", "Fourth").map { "com.eignex.klause.solver.$it.run" }

        val callers = CliProfile.cliOperationCallers(stack)

        assertEquals(
            "com.eignex.klause.solver.First.run <- com.eignex.klause.solver.Second.run <- " +
                "com.eignex.klause.solver.Third.run",
            callers,
        )
    }

    @Test
    fun `stacks without operation callers should keep an explicit unknown label`() {
        for (stack in listOf(emptyList(), listOf("java.util.HashMap.getNode"), listOf(
            "com.eignex.klause.util.OrCancellation.isCancelled",
            "com.eignex.klause.bench.tools.CliProfile.summarizeCli",
        ))) {
            val callers = CliProfile.cliOperationCallers(stack)

            assertEquals("no captured Klause operation", callers)
        }
    }

    @Test
    fun `resumed initialization and restart frames should retain their phases`() {
        for ((method, expected) in listOf(
            "newMinimizeState" to "local-search seeding",
            "newSatisfyState" to "local-search seeding",
            "recomputeInitial" to "local-search seeding",
            "restartAndRepair" to "local-search restart seeding",
        )) {
            for (frame in listOf("LocalSearchEngine.$method", "LocalSearchEngine\$$method\$1.invokeSuspend")) {
                val phase = CliProfile.cliPhase(listOf("LocalSearchState.apply", frame))

                assertEquals(expected, phase, frame)
            }
        }
    }

    @Test
    fun `standalone greedy seeding should take precedence over move application`() {
        for (frame in listOf(
            "com.eignex.klause.localsearch.movesource.GreedyInit\$Pass.advance",
            "LocalSearchEngine\$greedyRepairPass\$1.invokeSuspend",
        )) {
            val phase = CliProfile.cliPhase(listOf("LocalSearchState.apply", frame))

            assertEquals("local-search seeding", phase, frame)
        }
    }

    @Test
    fun `greedy seeding should retain visible ALNS bootstrap attribution`() {
        val phase = CliProfile.cliPhase(listOf(
            "LocalSearchState.apply",
            "LocalSearchEngine\$greedyRepairPass\$1.invokeSuspend",
            "com.eignex.klause.meta.alns.Alns.bootstrapIncumbent",
        ))

        assertEquals("ALNS bootstrap: local-search seeding", phase)
    }

    @Test
    fun `shared search stacks should retain the operation phase`() {
        for ((frame, expected) in listOf(
            "PreparedSearchComponents.<init>" to "shared source preparation",
            "PreparedSearchComponents.prepareSource" to "shared source preparation",
            "QfLiraSolverKt.sharedExactLiraSource" to "shared source preparation",
            "ComponentPlanKt.componentPlan" to "shared source preparation",
            "PreparedSourcePlanKt.prepareExecutionPlan" to "shared source preparation",
            "ExactLiraSearchComponent.installSource" to "shared theory source installation",
            "ExactLiraSearchComponent.assertSource" to "shared theory source installation",
            "SearchSession.prepareInitialization" to "shared root initialization",
            "SearchSession.propagate" to "shared propagation",
            "SearchSession.check" to "shared model check",
            "PreparedSearchRegistrations.open" to "shared session opening",
            "SearchRun.next" to "shared search",
            "SearchRun.advanceFrame" to "shared search",
            "SearchRun.backtrack" to "shared search",
        )) {
            val phase = CliProfile.cliPhase(listOf("java.util.HashMap.getNode", frame))

            assertEquals(expected, phase, frame)
        }
    }

    @Test
    fun `shared root initialization should retain nested propagation cost`() {
        val phase = CliProfile.cliPhase(listOf(
            "com.eignex.klause.propagation.MutatorsKt.excludeIntValueImpl",
            "SearchSession.propagate",
            "SearchSession.initialize",
            "SearchRun.next",
        ))

        assertEquals("shared root initialization", phase)
    }

    @Test
    fun `presolve and backtrack callers should retain their phase ownership`() {
        for ((frame, expected) in listOf(
            "com.eignex.klause.presolve.RootBaker.prepare" to "presolve",
            "com.eignex.klause.backtrack.BacktrackSolver.solve" to "backtrack search",
        )) {
            val phase = CliProfile.cliPhase(listOf("SearchSession.initialize", frame))

            assertEquals(expected, phase, frame)
        }
    }

    @Test
    fun `utility frames without a shared caller should remain unclassified`() {
        val phase = CliProfile.cliPhase(listOf(
            "java.util.HashMap.getNode",
            "OrCancellation.isCancelled",
            "SearchSession.checkBudgetExhausted",
        ))

        assertEquals("other or unclassified", phase)
    }
}
