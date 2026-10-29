package com.eignex.klause.formats.dimacs

import com.eignex.klause.ir.BoundOverride
import com.eignex.klause.ir.BoundsOverride
import com.eignex.klause.ir.FloatSemantics
import com.eignex.klause.ir.SemanticOverrides
import com.eignex.klause.ir.SourceFormat
import com.eignex.klause.ir.SourceTolerance
import com.eignex.klause.localsearch.DefinitionalSweep
import com.eignex.klause.localsearch.LocalSearchModel
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.MoveSink
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.propagate
import com.eignex.klause.solver.Assignment
import com.eignex.klause.solver.objective.toLinearObjective
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DimacsDecoderTest {
    @Test
    fun `Boolean source lowerings should retain their declared format semantics`() {
        val models = listOf(
            SourceFormat.DIMACS to Dimacs.parse("p cnf 1 1\n1 0\n").toProblem(),
            SourceFormat.WCNF to Dimacs.parseWcnf("3 1 0\n").toProblem().problem,
        )

        for ((format, source) in models) {
            assertEquals(format, source.semantics.effective.format)
            assertEquals(source.semantics.declared, source.semantics.effective)
        }
    }

    @Test
    fun `Boolean source lowerings should reject inapplicable semantic overrides`() {
        val cnf = Dimacs.parse("p cnf 1 1\n1 0\n")
        val wcnf = Dimacs.parseWcnf("3 1 0\n")
        val overrides = listOf(
            SemanticOverrides(floats = FloatSemantics.Continuous),
            SemanticOverrides(undeclaredIntegers = BoundsOverride(lower = BoundOverride.Open)),
            SemanticOverrides(undeclaredReals = BoundsOverride(upper = BoundOverride.Open)),
            SemanticOverrides(feasibilityTolerance = SourceTolerance()),
            SemanticOverrides(pinAbsentOptionalValues = false),
        )

        for (policy in overrides) {
            assertFailsWith<IllegalArgumentException> { cnf.toProblem(semanticOverrides = policy) }
            assertFailsWith<IllegalArgumentException> { wcnf.toProblem(semanticOverrides = policy) }
        }
    }

    @Test
    fun `repeated unit penalties should repair through their source literal`() {
        for (literal in listOf(1, -1)) {
            val decoded = Dimacs.parseWcnf("3 $literal $literal 0\n7 -2 0\n").toProblem()
            val sweep = assertNotNull(DefinitionalSweep.infer(decoded.problem, boolFolds = decoded.boolFolds))
            val state = LocalSearchState(LocalSearchModel.open(decoded.problem), Random(3))
            state.assignment.setBool(0, literal < 0)
            state.invariants = sweep.network(0, decoded.problem.numBoolVars)
            sweep.sweep(state.assignment, state.rootDomains)
            state.recompute()
            val proposals = MoveSink().also { it.setInvariants(state.invariants) }

            proposals.addChannelingBoolFlip(state, decoded.boolFolds.first().out)
            state.apply(proposals.list.single())

            assertEquals(literal > 0, state.assignment.boolValue(0))
            assertEquals(0L, state.cost)
            assertEquals(0L, decoded.objective.toLinearObjective().evaluateLong(state.assignment))
        }
    }

    @Test
    fun `opposite soft literals should retain a zero tautology penalty`() {
        val decoded = Dimacs.parseWcnf("3 1 1 -1 -1 0\n7 -2 0\n").toProblem()
        val sweep = assertNotNull(DefinitionalSweep.infer(decoded.problem, boolFolds = decoded.boolFolds))
        val state = LocalSearchState(LocalSearchModel.open(decoded.problem), Random(3))
        state.invariants = sweep.network(0, decoded.problem.numBoolVars)
        for (value in listOf(false, true)) {
            state.assignment.setBool(0, value)
            sweep.sweep(state.assignment, state.rootDomains)
            state.recompute()
            val proposals = MoveSink().also { it.setInvariants(state.invariants) }

            proposals.addChannelingBoolFlip(state, decoded.boolFolds.first().out)

            assertTrue(proposals.list.isEmpty())
            assertEquals(0L, decoded.objective.toLinearObjective().evaluateLong(state.assignment))
        }
    }

    @Test
    fun `wide unit penalty definitions should retain exact feasible cost`() {
        val decoded = Dimacs.parseWcnf("9007199254740993 1 0\n7 -2 0\n").toProblem()
        val sweep = assertNotNull(DefinitionalSweep.infer(decoded.problem, boolFolds = decoded.boolFolds))
        val assignment = Assignment(decoded.problem.numBoolVars, 0)
        assignment.setBool(1, true)

        sweep.sweep(assignment, emptyArray(), decoded.problem.factors)

        assertEquals(9007199254741000L, decoded.objective.toLinearObjective().evaluateLong(assignment))
    }

    @Test
    fun `headerless maximum weight should retain its soft clause cost`() {
        val decoded = Dimacs.parseWcnf("${Long.MAX_VALUE} 1 0\n").toProblem()
        val assignment = Assignment(decoded.problem.numBoolVars, 0)

        assertEquals(Long.MAX_VALUE, decoded.objective.toLinearObjective().evaluateLong(assignment))
    }

    @Test
    fun `penalty definitions should preserve original costs across weighted and hard clauses`() {
        for (text in listOf(
            "3 1 0\n7 -2 0\n",
            "h 1 -2 0\n3 1 -2 0\n7 -1 2 0\n5 -1 0\n2 0\n",
        )) {
            val decoded = Dimacs.parseWcnf(text).toProblem()
            val sweep = assertNotNull(DefinitionalSweep.infer(decoded.problem, boolFolds = decoded.boolFolds))
            val objective = decoded.objective.toLinearObjective()
            val assignment = Assignment(decoded.problem.numBoolVars, 0)
            for (bits in 0..3) {
                val first = bits and 1 != 0
                val second = bits and 2 != 0
                assignment.setBool(0, first)
                assignment.setBool(1, second)
                sweep.sweep(assignment, emptyArray(), decoded.problem.factors)
                val expected = if (text.startsWith("h")) {
                    (if (!first && second) 3L else 0L) +
                        (if (first && !second) 7L else 0L) + (if (first) 5L else 0L) + 2L
                } else {
                    (if (!first) 3L else 0L) + (if (second) 7L else 0L)
                }

                assertEquals(expected, objective.evaluateLong(assignment))
            }
        }
    }

    @Test
    fun `unit costs should preserve both polarities and repeated clauses`() {
        val decoded = Dimacs.parseWcnf("2 1 0\n2 -1 0\n2 1 0\n2 -2 0\n4 0\n").toProblem()
        val objective = decoded.objective.toLinearObjective()
        val assignment = Assignment(decoded.problem.numBoolVars, 0)
        for ((first, second, cost) in listOf(
            Triple(false, false, 8L),
            Triple(true, false, 6L),
            Triple(false, true, 10L),
            Triple(true, true, 8L),
        )) {
            assignment.setBool(0, first)
            assignment.setBool(1, second)
            assertEquals(cost, objective.evaluateLong(assignment))
        }
    }

    @Test
    fun `non unit clauses should retain their violation cost`() {
        val decoded = Dimacs.parseWcnf("7 1 0\n7 -1 -2 0\n").toProblem()
        val objective = decoded.objective.toLinearObjective()
        val assignment = Assignment(decoded.problem.numBoolVars, 0)
        assignment.setBool(0, true)
        assignment.setBool(1, true)
        assignment.setBool(2, true)

        assertEquals(7L, objective.evaluateLong(assignment))
    }

    @Test
    fun `a satisfied soft clause should not carry a violation penalty`() {
        val decoded = Dimacs.parseWcnf("p wcnf 2 3 20\n20 1 0\n5 1 2 0\n3 -1 0\n").toProblem()
        val inflated = Assumptions(bools = mapOf(0 to true, 1 to false, 2 to true, 3 to true))

        val result = decoded.problem.propagate(inflated)

        assertIs<PropagationResult.Unsat>(result)
    }

    @Test
    fun `unit coefficient overflow should keep the relaxation encoding`() {
        val decoded = Dimacs.parseWcnf("4611686018427387904 -1 0\n4611686018427387904 -1 0\n").toProblem()
        val assignment = Assignment(decoded.problem.numBoolVars, 0)

        assertEquals(2, decoded.problem.numBoolVars)
        assertEquals(0L, decoded.objective.toLinearObjective().evaluateLong(assignment))
    }
}
