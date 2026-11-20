package com.eignex.klause.lp.optimization

import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.lp.SourceLpBudget
import com.eignex.klause.lp.SourceLpFunding
import com.eignex.klause.lp.engine.CutFamily
import com.eignex.klause.lp.optimization.AggregateCutTestSource.left
import com.eignex.klause.lp.optimization.AggregateCutTestSource.real
import com.eignex.klause.lp.optimization.AggregateCutTestSource.right
import com.eignex.klause.lp.optimization.AggregateCutTestSource.scope
import com.eignex.klause.lp.optimization.AggregateCutTestSource.x
import com.eignex.klause.lp.optimization.AggregateCutTestSource.y
import com.eignex.klause.solver.evidence.SourceClaim
import com.eignex.klause.solver.evidence.SourceCondition
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.solver.evidence.SourceEvidence
import com.eignex.klause.solver.evidence.SourceEvidenceCheck
import com.eignex.klause.solver.evidence.SourceEvidenceVerifier
import com.eignex.klause.solver.evidence.SourceInequality
import com.eignex.klause.solver.evidence.SourceObjective
import com.eignex.klause.solver.evidence.SourcePremise
import com.eignex.klause.solver.evidence.SourceProofContext
import com.eignex.klause.solver.evidence.SourceRevision
import com.eignex.klause.solver.evidence.SourceScope
import com.eignex.klause.solver.evidence.SourceValue
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.numbers.BigFraction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinearAuthorityAggregateCutsTest {
    @Test
    fun `conditional projections should survive ordinary selection for a later receiving query`() {
        val integers = (0 until 5).map { SourceCoordinate(SourceCoordinate.Kind.Integer, it) }
        val flows = (0 until 5).map { SourceCoordinate(SourceCoordinate.Kind.Real, it) }
        val premises = linkedMapOf<String, SourcePremise>()
        for (id in integers.indices) {
            premises["capacity $id"] = SourcePremise(SourceInequality(mapOf(flows[id] to BigFraction.ONE,
                integers[id] to BigFraction.ofLong(-2)), BigFraction.ZERO))
            premises["edge $id"] = SourcePremise(SourceInequality(mapOf(integers[id] to BigFraction.ONE,
                integers[(id + 1) % integers.size] to BigFraction.ONE), BigFraction.ONE))
            premises["lower $id"] = SourcePremise(SourceInequality(mapOf(integers[id] to BigFraction.MINUS_ONE),
                BigFraction.ZERO))
        }
        premises["demand"] = SourcePremise(SourceInequality(flows.associateWith { BigFraction.MINUS_ONE },
            BigFraction.ofLong(-7) * BigFraction.ofLong(2).reciprocal()))
        val source = SourceProofContext(scope.copy(objective = SourceObjective("sum", ObjectiveSense.MAXIMIZE)),
            (integers + flows).toSet(), premises, integers.associateWith { BigFraction.ONE })
        val child = source.atScope(source.scope.copy(condition = SourceCondition(
            upper = integers.associateWith { BigFraction.ZERO })))
        val capture = LinearAuthorityCapture(8, 100_000L, 4_000_000L, ExecutionControl.Never)
        val budget = SourceLpBudget()
        LinearAuthority(budget, retainLp = true, aggregateCuts = true).use { authority ->
            authority.capture = capture
            val base = authority.advanceWithPointCuts(source, ExecutionControl.Never)
            val operations = budget.operations

            val result = authority.advanceWithPointCuts(child, ExecutionControl.Never)

            assertNotNull(base.bound)
            assertTrue((authority.cutObservations()["numericalCutSelectionConditionalRetained"] ?: 0L) > 0L)
            assertTrue(capture.records.last().cuts.any { it.conditionalCandidate && it.checked && !it.installed })
            assertEquals(operations, budget.operations)
            assertEquals(setOf(CutFamily.SOURCE_AGGREGATION), result.cutConflictFamilies)
            assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(child.withLinearAuthority())
                .verify(assertNotNull(result.evidence)))
        }
    }

    @Test
    fun `automatic source pairs should strengthen a checked bound across an unbounded continuous coordinate`() {
        val residual = SourceCoordinate(SourceCoordinate.Kind.Real, 1)
        val mixed = SourceInequality(left.coefficients + (residual to BigFraction.MINUS_ONE), left.rhs)
        val bound = SourceInequality(mapOf(residual to BigFraction.MINUS_ONE), BigFraction.ZERO)
        val source = SourceProofContext(scope.copy(objective = SourceObjective("sum", ObjectiveSense.MINIMIZE)),
            setOf(x, y, real, residual), mapOf("left" to SourcePremise(mixed),
                "right" to SourcePremise(right), "bound" to SourcePremise(bound)),
            mapOf(x to BigFraction.MINUS_ONE, y to BigFraction.MINUS_ONE, residual to BigFraction.ONE))

        for ((aggregate, selection, expected) in listOf(
            Triple(false, false, BigFraction.ofLong(-3) * BigFraction.ofLong(2).reciprocal()),
            Triple(true, false, BigFraction.MINUS_ONE), Triple(true, true, BigFraction.MINUS_ONE),
        )) {
            LinearAuthority(SourceLpBudget(), retainLp = true, aggregateCuts = aggregate).use { authority ->
                val result = if (selection) authority.advanceWithPointCuts(source, ExecutionControl.Never)
                else authority.advance(source, ExecutionControl.Never, cuts = true)

                assertEquals(SourceValue.Rational(expected),
                    assertIs<SourceClaim.Bound>(assertNotNull(result.bound).claim).value)
                assertEquals(SourceEvidenceCheck.Checked,
                    SourceEvidenceVerifier(source.withLinearAuthority()).verify(assertNotNull(result.bound)))
                if (aggregate) assertTrue(CutFamily.SOURCE_AGGREGATION in result.cutBoundFamilies)
            }
        }
    }

    @Test
    fun `point selection should query the current recipe before preparing aggregate pairs`() {
        val source = SourceProofContext(scope.copy(objective = SourceObjective("sum", ObjectiveSense.MINIMIZE)),
            setOf(x, y, real), mapOf("left" to SourcePremise(left), "right" to SourcePremise(right)),
            mapOf(x to BigFraction.MINUS_ONE, y to BigFraction.MINUS_ONE))
        val capture = LinearAuthorityCapture(4, 10_000L, 1_000_000L, ExecutionControl.Never)

        val result = LinearAuthority(SourceLpBudget(), retainLp = true, aggregateCuts = true).use { authority ->
            authority.capture = capture
            authority.advanceWithPointCuts(source, ExecutionControl.Never)
        }

        assertTrue(capture.records.first().cuts.isEmpty())
        assertEquals(SourceValue.Rational(BigFraction.MINUS_ONE),
            assertIs<SourceClaim.Bound>(assertNotNull(result.bound).claim).value)
        assertEquals(SourceEvidenceCheck.Checked,
            SourceEvidenceVerifier(source.withLinearAuthority()).verify(assertNotNull(result.bound)))
    }

    @Test
    fun `a checked current optimum should bypass optional pair discovery`() {
        val source = SourceProofContext(scope.copy(objective = SourceObjective("x", ObjectiveSense.MINIMIZE)),
            setOf(x, y, real), mapOf("left" to SourcePremise(left), "right" to SourcePremise(right),
                "upper" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ONE), BigFraction.ONE))),
            mapOf(x to BigFraction.MINUS_ONE))
        LinearAuthority(SourceLpBudget(), retainLp = true, aggregateCuts = true).use { authority ->
            val result = authority.advanceWithPointCuts(source, ExecutionControl.Never)

            assertEquals(null, authority.cutObservations()["numericalCutAggregatePairAttempts"])
            assertIs<SourceClaim.Optimal>(assertNotNull(result.evidence).claim)
            assertEquals(SourceEvidenceCheck.Checked,
                SourceEvidenceVerifier(source.withLinearAuthority()).verify(assertNotNull(result.evidence)))
        }
    }

    @Test
    fun `root guidance should retain the paid base point beside a stronger checked bound`() {
        val residual = SourceCoordinate(SourceCoordinate.Kind.Real, 1)
        val triangle = (2..4).map { SourceCoordinate(SourceCoordinate.Kind.Integer, it) }
        val premises = linkedMapOf(
            "left" to SourcePremise(SourceInequality(left.coefficients + (residual to BigFraction.ONE), left.rhs)),
            "right" to SourcePremise(right),
            "lower" to SourcePremise(SourceInequality(mapOf(residual to BigFraction.MINUS_ONE), BigFraction.ZERO)),
            "upper" to SourcePremise(SourceInequality(mapOf(residual to BigFraction.ONE), BigFraction.ONE)),
        )
        for ((index, coordinate) in triangle.withIndex()) {
            premises["nonnegative $index"] = SourcePremise(SourceInequality(
                mapOf(coordinate to BigFraction.MINUS_ONE), BigFraction.ZERO))
            premises["edge $index"] = SourcePremise(SourceInequality(
                mapOf(coordinate to BigFraction.ONE, triangle[(index + 1) % 3] to BigFraction.ONE), BigFraction.ONE))
        }
        val source = SourceProofContext(scope.copy(objective = SourceObjective("sum", ObjectiveSense.MAXIMIZE)),
            setOf(x, y, real, residual) + triangle, premises,
            (triangle + x + y).associateWith { BigFraction.ONE })
        for (pause in listOf(false, true)) {
            val funding = SourceLpFunding(4_000_000L, 8_000_000L).apply { grant(4_000_000L) }
            var blocked = false
            var operations = 0L
            val budget = SourceLpBudget(funding = funding, onWork = {
                operations += it.operations
                if (pause && !blocked && operations == 2L) {
                    blocked = true
                    funding.grant(0L)
                }
            })

            val result = LinearAuthority(budget, retainLp = true, aggregateCuts = true).use { authority ->
                val initial = authority.advanceWithPointCuts(source, ExecutionControl.Never, retainBasePoint = true)
                if (pause) {
                    assertTrue(initial.paused)
                    assertNull(initial.advisoryPoint)
                    val attempts = authority.cutObservations()["numericalCutAggregatePairAttempts"]
                    funding.grant(4_000_000L)
                    authority.advanceWithPointCuts(source, ExecutionControl.Never, retainBasePoint = true).also {
                        assertEquals(attempts, authority.cutObservations()["numericalCutAggregatePairAttempts"])
                    }
                } else initial
            }

            assertFalse(result.paused)
            assertNull(result.incumbent)
            assertNull(result.evidence)
            val guidance = assertNotNull(result.advisoryPoint)
            assertEquals(1.5, guidance.getValue(x) + guidance.getValue(y))
            assertEquals(1.0, result.relaxedPoint.getValue(x) + result.relaxedPoint.getValue(y))
            assertEquals(SourceValue.Rational(BigFraction.ofLong(5) * BigFraction.ofLong(2).reciprocal()),
                assertIs<SourceClaim.Bound>(assertNotNull(result.bound).claim).value)
            assertEquals(SourceEvidenceCheck.Checked,
                SourceEvidenceVerifier(source.withLinearAuthority()).verify(assertNotNull(result.bound)))
            assertEquals(2, budget.operations)
        }
    }

    @Test
    fun `a selected checked optimum should retain its selected root guidance`() {
        val source = SourceProofContext(scope.copy(objective = SourceObjective("sum", ObjectiveSense.MINIMIZE)),
            setOf(x, y, real), mapOf("left" to SourcePremise(left), "right" to SourcePremise(right)),
            mapOf(x to BigFraction.MINUS_ONE, y to BigFraction.MINUS_ONE))

        val result = LinearAuthority(SourceLpBudget(), retainLp = true, aggregateCuts = true).use { authority ->
            authority.advanceWithPointCuts(source, ExecutionControl.Never, retainBasePoint = true)
        }

        assertNull(result.advisoryPoint)
        assertEquals(1.0, result.relaxedPoint.getValue(x) + result.relaxedPoint.getValue(y))
        assertIs<SourceClaim.Optimal>(assertNotNull(result.evidence).claim)
        assertEquals(SourceEvidenceCheck.Checked,
            SourceEvidenceVerifier(source.withLinearAuthority()).verify(assertNotNull(result.evidence)))
    }

    @Test
    fun `cancelled selection should preserve the paid bound without publishing new guidance`() {
        val source = SourceProofContext(scope.copy(objective = SourceObjective("sum", ObjectiveSense.MINIMIZE)),
            setOf(x, y, real), mapOf("left" to SourcePremise(left), "right" to SourcePremise(right)),
            mapOf(x to BigFraction.MINUS_ONE, y to BigFraction.MINUS_ONE))
        var cancelled = false
        var operations = 0L
        val budget = SourceLpBudget(onWork = {
            operations += it.operations
            if (operations == 2L) cancelled = true
        })

        val result = LinearAuthority(budget, retainLp = true, aggregateCuts = true).use { authority ->
            authority.advanceWithPointCuts(source, ExecutionControl { cancelled }, retainBasePoint = true)
        }

        assertTrue(cancelled)
        assertNull(result.advisoryPoint)
        assertNull(result.incumbent)
        assertNull(result.evidence)
        assertEquals(SourceValue.Rational(BigFraction.ofLong(-3) * BigFraction.ofLong(2).reciprocal()),
            assertIs<SourceClaim.Bound>(assertNotNull(result.bound).claim).value)
        assertEquals(SourceEvidenceCheck.Checked,
            SourceEvidenceVerifier(source.withLinearAuthority()).verify(assertNotNull(result.bound)))
    }

    @Test
    fun `retiring a fully selected aggregate family should preserve its numerical owner`() {
        val residual = SourceCoordinate(SourceCoordinate.Kind.Real, 1)
        val triangle = (2..4).map { SourceCoordinate(SourceCoordinate.Kind.Integer, it) }
        val premises = linkedMapOf(
            "left" to SourcePremise(SourceInequality(left.coefficients + (residual to BigFraction.ONE), left.rhs)),
            "right" to SourcePremise(right),
            "lower" to SourcePremise(SourceInequality(mapOf(residual to BigFraction.MINUS_ONE), BigFraction.ZERO)),
            "upper" to SourcePremise(SourceInequality(mapOf(residual to BigFraction.ONE), BigFraction.ONE)),
        )
        for ((index, coordinate) in triangle.withIndex()) {
            premises["nonnegative $index"] = SourcePremise(SourceInequality(
                mapOf(coordinate to BigFraction.MINUS_ONE), BigFraction.ZERO))
            premises["edge $index"] = SourcePremise(SourceInequality(
                mapOf(coordinate to BigFraction.ONE, triangle[(index + 1) % 3] to BigFraction.ONE), BigFraction.ONE))
        }
        val source = SourceProofContext(scope.copy(objective = SourceObjective("sum", ObjectiveSense.MAXIMIZE)),
            setOf(x, y, real, residual) + triangle, premises,
            (triangle + x + y).associateWith { BigFraction.ONE })
        val child = source.atScope(source.scope.copy(condition = SourceCondition(
            upper = mapOf(x to BigFraction.ZERO, y to BigFraction.ZERO))))
        val capture = LinearAuthorityCapture(8, 10_000L, 1_000_000L, ExecutionControl.Never)
        LinearAuthority(SourceLpBudget(), retainLp = true, aggregateCuts = true).use { authority ->
            authority.capture = capture
            authority.advanceWithPointCuts(source, ExecutionControl.Never)
            val preparations = authority.preparations
            repeat(3) { authority.advanceWithPointCuts(child, ExecutionControl.Never) }
            assertNull(authority.cutObservations()["numericalCutSelectionRetiredInstalled"])

            val result = authority.advanceWithPointCuts(child, ExecutionControl.Never)

            assertEquals(1L, authority.cutObservations()["numericalCutSelectionRetiredInstalled"])
            assertEquals(preparations, authority.preparations)
            assertEquals(false, capture.records.last().cuts.single().installed)
            assertEquals(SourceValue.Rational(BigFraction.ofLong(3) * BigFraction.ofLong(2).reciprocal()),
                assertIs<SourceClaim.Bound>(assertNotNull(result.bound).claim).value)
            assertEquals(SourceEvidenceCheck.Checked,
                SourceEvidenceVerifier(child.withLinearAuthority()).verify(assertNotNull(result.bound)))
        }
    }

    @Test
    fun `retiring candidates should retain their base bound across repeated pauses`() {
        val residual = SourceCoordinate(SourceCoordinate.Kind.Real, 1)
        val triangle = (2..4).map { SourceCoordinate(SourceCoordinate.Kind.Integer, it) }
        val premises = linkedMapOf(
            "left" to SourcePremise(SourceInequality(left.coefficients + (residual to BigFraction.ONE), left.rhs)),
            "right" to SourcePremise(right),
            "lower" to SourcePremise(SourceInequality(mapOf(residual to BigFraction.MINUS_ONE), BigFraction.ZERO)),
            "upper" to SourcePremise(SourceInequality(mapOf(residual to BigFraction.ONE), BigFraction.ONE)),
        )
        for ((index, coordinate) in triangle.withIndex()) {
            premises["nonnegative $index"] = SourcePremise(SourceInequality(
                mapOf(coordinate to BigFraction.MINUS_ONE), BigFraction.ZERO))
            premises["edge $index"] = SourcePremise(SourceInequality(
                mapOf(coordinate to BigFraction.ONE, triangle[(index + 1) % 3] to BigFraction.ONE), BigFraction.ONE))
        }
        val source = SourceProofContext(scope.copy(objective = SourceObjective("sum", ObjectiveSense.MAXIMIZE)),
            setOf(x, y, real, residual) + triangle, premises,
            (triangle + x + y).associateWith { BigFraction.ONE })
        val child = source.atScope(source.scope.copy(condition = SourceCondition(
            upper = mapOf(x to BigFraction.ZERO, y to BigFraction.ZERO))))
        val funding = SourceLpFunding(4_000_000L, 8_000_000L).apply { grant(4_000_000L) }
        var pause = false
        var blocked = false
        val budget = SourceLpBudget(funding = funding, onWork = {
            if (pause && !blocked && it.operations > 0L) {
                blocked = true
                funding.grant(0L)
            }
        })
        LinearAuthority(budget, retainLp = true, aggregateCuts = true).use { authority ->
            authority.advanceWithPointCuts(source, ExecutionControl.Never)
            repeat(3) { authority.advanceWithPointCuts(child, ExecutionControl.Never) }
            val operations = budget.operations
            pause = true

            repeat(2) {
                val paused = authority.advanceWithPointCuts(child, ExecutionControl.Never)

                assertTrue(paused.paused)
                assertEquals(1L, authority.cutObservations()["numericalCutSelectionRetiredInstalled"])
                assertEquals(SourceValue.Rational(BigFraction.ofLong(3) * BigFraction.ofLong(2).reciprocal()),
                    assertIs<SourceClaim.Bound>(assertNotNull(paused.bound).claim).value)
                assertEquals(SourceEvidenceCheck.Checked,
                    SourceEvidenceVerifier(child.withLinearAuthority()).verify(assertNotNull(paused.bound)))
            }

            funding.grant(4_000_000L)
            val completed = authority.advanceWithPointCuts(child, ExecutionControl.Never)

            assertFalse(completed.paused)
            assertEquals(operations + 1, budget.operations)
            assertEquals(SourceValue.Rational(BigFraction.ofLong(3) * BigFraction.ofLong(2).reciprocal()),
                assertIs<SourceClaim.Bound>(assertNotNull(completed.bound).claim).value)
            assertEquals(SourceEvidenceCheck.Checked,
                SourceEvidenceVerifier(child.withLinearAuthority()).verify(assertNotNull(completed.bound)))
        }
    }

    @Test
    fun `an ordinary recipe should replace a retained point selected layout`() {
        val residual = SourceCoordinate(SourceCoordinate.Kind.Real, 1)
        val triangle = (2..4).map { SourceCoordinate(SourceCoordinate.Kind.Integer, it) }
        val premises = linkedMapOf(
            "left" to SourcePremise(SourceInequality(left.coefficients + (residual to BigFraction.ONE), left.rhs)),
            "right" to SourcePremise(right),
            "lower" to SourcePremise(SourceInequality(mapOf(residual to BigFraction.MINUS_ONE), BigFraction.ZERO)),
            "upper" to SourcePremise(SourceInequality(mapOf(residual to BigFraction.ONE), BigFraction.ONE)),
        )
        for ((index, coordinate) in triangle.withIndex()) {
            premises["nonnegative $index"] = SourcePremise(SourceInequality(
                mapOf(coordinate to BigFraction.MINUS_ONE), BigFraction.ZERO))
            premises["edge $index"] = SourcePremise(SourceInequality(
                mapOf(coordinate to BigFraction.ONE, triangle[(index + 1) % 3] to BigFraction.ONE), BigFraction.ONE))
        }
        val source = SourceProofContext(scope.copy(objective = SourceObjective("sum", ObjectiveSense.MAXIMIZE)),
            setOf(x, y, real, residual) + triangle, premises,
            (triangle + x + y).associateWith { BigFraction.ONE })
        val child = source.atScope(source.scope.copy(condition = SourceCondition(
            upper = mapOf(x to BigFraction.ZERO, y to BigFraction.ZERO))))
        LinearAuthority(SourceLpBudget(), retainLp = true, aggregateCuts = true).use { authority ->
            authority.advanceWithPointCuts(source, ExecutionControl.Never)
            authority.advanceWithPointCuts(child, ExecutionControl.Never)

            val result = authority.advance(source, ExecutionControl.Never, cuts = true)

            assertEquals(SourceValue.Rational(BigFraction.ofLong(3)),
                assertIs<SourceClaim.Bound>(assertNotNull(result.bound).claim).value)
            assertEquals(SourceEvidenceCheck.Checked,
                SourceEvidenceVerifier(source.withLinearAuthority()).verify(assertNotNull(result.bound)))
        }
    }

    @Test
    fun `installed aggregate cuts should retain their checked numerical recipe across scoped queries`() {
        val residual = SourceCoordinate(SourceCoordinate.Kind.Real, 1)
        val triangle = (2..4).map { SourceCoordinate(SourceCoordinate.Kind.Integer, it) }
        val mixed = SourceInequality(left.coefficients + (residual to BigFraction.MINUS_ONE), left.rhs)
        val premises = linkedMapOf("left" to SourcePremise(mixed), "right" to SourcePremise(right),
            "bound" to SourcePremise(SourceInequality(mapOf(residual to BigFraction.MINUS_ONE), BigFraction.ZERO)))
        for ((index, coordinate) in triangle.withIndex()) {
            premises["nonnegative $index"] = SourcePremise(SourceInequality(
                mapOf(coordinate to BigFraction.MINUS_ONE), BigFraction.ZERO))
            premises["edge $index"] = SourcePremise(SourceInequality(
                mapOf(coordinate to BigFraction.ONE, triangle[(index + 1) % 3] to BigFraction.ONE),
                BigFraction.ONE))
        }
        val source = SourceProofContext(scope.copy(objective = SourceObjective("sum", ObjectiveSense.MINIMIZE)),
            setOf(x, y, real, residual) + triangle, premises,
            (triangle + x + y).associateWith { BigFraction.MINUS_ONE } + (residual to BigFraction.ONE))
        val child = source.atScope(source.scope.copy(condition = SourceCondition(upper = mapOf(x to BigFraction.ZERO))))
        val capture = LinearAuthorityCapture(4, 10_000L, 1_000_000L, ExecutionControl.Never)
        LinearAuthority(SourceLpBudget(), retainLp = true, aggregateCuts = true).use { authority ->
            authority.advance(source, ExecutionControl.Never, cuts = true)
            authority.capture = capture
            authority.advanceWithPointCuts(source, ExecutionControl.Never)
            val attempts = assertNotNull(authority.cutObservations()["numericalCutAggregatePairAttempts"])
            assertEquals(1L, authority.cutObservations()["numericalCutSelectionAdoptedInstalled"])
            assertEquals(1, capture.records.size)

            val result = authority.advanceWithPointCuts(child, ExecutionControl.Never)

            assertEquals(attempts, authority.cutObservations()["numericalCutAggregatePairAttempts"])
            assertEquals(2, capture.records.size)
            assertEquals(SourceValue.Rational(BigFraction.ofLong(-5) * BigFraction.ofLong(2).reciprocal()),
                assertIs<SourceClaim.Bound>(assertNotNull(result.bound).claim).value)
            assertTrue(CutFamily.SOURCE_AGGREGATION in result.cutBoundFamilies)
            assertEquals(SourceEvidenceCheck.Checked,
                SourceEvidenceVerifier(child.withLinearAuthority()).verify(assertNotNull(result.bound)))
        }
    }

    @Test
    fun `repeated selected queries should reuse their completed pair generation`() {
        val residual = SourceCoordinate(SourceCoordinate.Kind.Real, 1)
        val mixed = SourceInequality(left.coefficients + (residual to BigFraction.MINUS_ONE), left.rhs)
        val bound = SourceInequality(mapOf(residual to BigFraction.MINUS_ONE), BigFraction.ZERO)
        val source = SourceProofContext(scope.copy(objective = SourceObjective("sum", ObjectiveSense.MINIMIZE)),
            setOf(x, y, real, residual), mapOf("left" to SourcePremise(mixed),
                "right" to SourcePremise(right), "bound" to SourcePremise(bound)),
            mapOf(x to BigFraction.MINUS_ONE, y to BigFraction.MINUS_ONE, residual to BigFraction.ONE))
        LinearAuthority(SourceLpBudget(), retainLp = true, aggregateCuts = true).use { authority ->
            authority.advanceWithPointCuts(source, ExecutionControl.Never)
            val attempts = assertNotNull(authority.cutObservations()["numericalCutAggregatePairAttempts"])

            val result = authority.advanceWithPointCuts(source, ExecutionControl.Never)

            assertEquals(attempts, authority.cutObservations()["numericalCutAggregatePairAttempts"])
            assertEquals(SourceValue.Rational(BigFraction.MINUS_ONE),
                assertIs<SourceClaim.Bound>(assertNotNull(result.bound).claim).value)
            assertEquals(SourceEvidenceCheck.Checked,
                SourceEvidenceVerifier(source.withLinearAuthority()).verify(assertNotNull(result.bound)))
        }
    }

    @Test
    fun `bounded point appends should keep completed proposals and their checked authority`() {
        val premises = (0 until 64).associateTo(linkedMapOf()) { index ->
            "row $index" to SourcePremise(SourceInequality(
                mapOf(x to BigFraction.ofLong(2), real to BigFraction.MINUS_ONE),
                BigFraction.ofLong(3L + 2L * index)))
        }
        val upper = SourceInequality(mapOf(x to BigFraction.ONE, real to BigFraction.ONE), BigFraction.ofLong(5))
        premises["upper"] = SourcePremise(upper)
        premises["lower"] = SourcePremise(SourceInequality(mapOf(real to BigFraction.MINUS_ONE), BigFraction.ZERO))
        val source = SourceProofContext(scope, setOf(x, real), premises)
        val ordinary = assertNotNull(LinearAuthorityTemplate.prepare(source, ExecutionControl.Never, cuts = true))
        val directed = assertNotNull(ordinary.atPoint(mapOf(x to 8.0 / 3.0, real to 7.0 / 3.0),
            ExecutionControl.Never, {}, { _, _ -> }))
        val aggregate = assertNotNull(LinearAuthorityAggregateCuts.derive(listOf(
            LinearAuthorityAggregateTerm(0, "row 0", premises.getValue("row 0").inequality),
            LinearAuthorityAggregateTerm(64, "upper", upper)), ExecutionControl.Never))
        assertEquals(64, assertNotNull(directed.cuts).rows.size)

        for ((count, limit, expected) in listOf(
            Triple(1, 64, 64), Triple(64, 64, 64),
            Triple(1, Int.MAX_VALUE, 65), Triple(64, Int.MAX_VALUE, 128),
        )) {
            val template = if (limit == Int.MAX_VALUE) ordinary else directed
            val appended = assertNotNull(template.withAdditional(List(count) { aggregate }, ExecutionControl.Never,
                maximumCandidates = limit))

            val checked = assertNotNull(LinearAuthorityQuery.prepare(source, appended, ExecutionControl.Never))

            assertEquals(expected, checked.checkedCuts.size)
            assertEquals(expected, checked.activeCuts)
            assertTrue(checked.unconditionalRows)
            assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(source).verify(
                SourceEvidence(scope, SourceClaim.Consequence(aggregate.inequality), aggregate.derivation)))
        }
    }

    @Test
    fun `a dense ordinary mixed recipe should retain its checked query without point expansion`() {
        val premises = (0 until 64).associateTo(linkedMapOf()) { index ->
            "row $index" to SourcePremise(SourceInequality(
                mapOf(x to BigFraction.ofLong(2), real to BigFraction.MINUS_ONE),
                BigFraction.ofLong(3L + 2L * index)))
        }
        premises["upper"] = SourcePremise(SourceInequality(
            mapOf(x to BigFraction.ONE, real to BigFraction.ONE), BigFraction.ofLong(5)))
        premises["lower"] = SourcePremise(SourceInequality(mapOf(real to BigFraction.MINUS_ONE), BigFraction.ZERO))
        val source = SourceProofContext(scope.copy(objective = SourceObjective("x", ObjectiveSense.MAXIMIZE)),
            setOf(x, real), premises, mapOf(x to BigFraction.ONE))
        val capture = LinearAuthorityCapture(4, 100_000L, 10_000_000L, ExecutionControl.Never)
        LinearAuthority(SourceLpBudget(), retainLp = true, aggregateCuts = true).use { authority ->
            authority.capture = capture

            val result = authority.advanceWithPointCuts(source, ExecutionControl.Never)

            assertEquals(64L, authority.cutObservations()["numericalMaxRetainedCuts"])
            assertEquals(1L, authority.cutObservations()["numericalCutSelectionDeclinedCutCount"])
            assertEquals(64, capture.records.last().cuts.size)
            assertTrue(capture.records.last().cuts.all { it.installed })
            assertEquals(SourceValue.Rational(BigFraction.ofLong(8) * BigFraction.ofLong(3).reciprocal()),
                assertIs<SourceClaim.Bound>(assertNotNull(result.bound).claim).value)
            assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(source.withLinearAuthority())
                .verify(assertNotNull(result.bound)))

            val preparations = authority.preparations
            authority.advanceWithPointCuts(source, ExecutionControl.Never)

            assertEquals(preparations, authority.preparations)
            assertEquals(1L, authority.cutObservations()["numericalCutSelectionDeclinedCutCount"])
            assertEquals(1L, authority.cutObservations()["numericalCutSelectionCurrentFallbackQueries"])
        }
    }

    @Test
    fun `point separation should retain a wide checked cut within its allocation grant`() {
        val coordinates = (0 until 48).map { SourceCoordinate(SourceCoordinate.Kind.Integer, it) }
        val first = SourceInequality(coordinates.take(24).associateWith { BigFraction.ofLong(2) } +
            (real to BigFraction.ONE), BigFraction.ofLong(24))
        val second = SourceInequality(coordinates.drop(24).associateWith { BigFraction.ofLong(2) } +
            (real to BigFraction.MINUS_ONE), BigFraction.ofLong(23))
        val source = SourceProofContext(scope, (coordinates + real).toSet(),
            linkedMapOf("left" to SourcePremise(first), "right" to SourcePremise(second)))
        val limits = LinearAuthorityAggregateCuts.pointSourcePairLimits.copy(allocation = 45_500L)
        var allocation = 0L

        val cut = LinearAuthorityAggregateCuts.prepareSourcePairs(source, ExecutionControl.Never,
            { name, count -> if (name == "AggregateAllocation") allocation += count }, maximumCandidates = 1,
            limits = limits, point = coordinates.associateWith { 47.0 / 96.0 } + (real to 0.5)).single()

        assertEquals(SourceInequality(coordinates.associateWith { BigFraction.ONE }, BigFraction.ofLong(23)),
            cut.inequality)
        assertTrue(allocation <= limits.allocation)
        assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(source).verify(
            SourceEvidence(scope, SourceClaim.Consequence(cut.inequality), cut.derivation)))
    }

    @Test
    fun `point separation should withhold a cut when a coordinate is unavailable`() {
        val source = SourceProofContext(scope, setOf(x, y, real),
            linkedMapOf("left" to SourcePremise(left), "right" to SourcePremise(right)))
        val events = mutableMapOf<String, Long>()

        val cuts = LinearAuthorityAggregateCuts.prepareSourcePairs(source, ExecutionControl.Never,
            { name, count -> events[name] = (events[name] ?: 0L) + count },
            limits = LinearAuthorityAggregateCuts.pointSourcePairLimits, point = mapOf(x to 0.75, real to 1.5))

        assertTrue(cuts.isEmpty())
        assertEquals(1L, events["AggregatePairPointUnavailable"])
        assertEquals(1L, events["AggregatePairIncomplete"])
    }

    @Test
    fun `point ordering should reach a tight pair before spending a second pair attempt`() {
        val a = SourceCoordinate(SourceCoordinate.Kind.Integer, 2)
        val b = SourceCoordinate(SourceCoordinate.Kind.Integer, 3)
        val continuous = SourceCoordinate(SourceCoordinate.Kind.Real, 1)
        val source = SourceProofContext(scope, setOf(x, y, real, a, b, continuous), linkedMapOf(
            "loose left" to SourcePremise(SourceInequality(
                mapOf(a to BigFraction.ofLong(2), continuous to BigFraction.ONE), BigFraction.ofLong(3))),
            "loose right" to SourcePremise(SourceInequality(
                mapOf(b to BigFraction.ofLong(2), continuous to BigFraction.MINUS_ONE), BigFraction.ZERO)),
            "left" to SourcePremise(left), "right" to SourcePremise(right)))
        val point = mapOf(a to 0.0, b to 0.0, continuous to 0.0, x to 0.75, y to 0.75, real to 1.5)
        var attempts = 0L
        var stopped = false

        val cut = LinearAuthorityAggregateCuts.prepareSourcePairs(source, ExecutionControl { stopped },
            observe = { name, count ->
                if (name == "AggregatePairAttempts") {
                    attempts += count
                    if (attempts == 2L) stopped = true
                }
            }, maximumCandidates = 1, point = point).single()

        assertEquals(setOf(2, 3), cut.owners)
        assertEquals(mapOf(x to BigFraction.ONE, y to BigFraction.ONE), cut.inequality.coefficients)
        assertEquals(1L, attempts)
        assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(source).verify(
            SourceEvidence(scope, SourceClaim.Consequence(cut.inequality), cut.derivation)))
    }

    @Test
    fun `small incidence buckets should leave a tight grant for the first useful pair`() {
        val continuous = (1..40).map { SourceCoordinate(SourceCoordinate.Kind.Real, it) }
        val premises = linkedMapOf<String, SourcePremise>()
        continuous.forEachIndexed { group, id ->
            repeat(5) { index ->
                premises["loose $group $index"] = SourcePremise(SourceInequality(
                    mapOf(x to BigFraction.ofLong(2), id to BigFraction.ofLong(if (index % 2 == 0) 1 else -1)),
                    BigFraction.ofLong(100)))
            }
        }
        premises["left"] = SourcePremise(left)
        premises["right"] = SourcePremise(right)
        val source = SourceProofContext(scope, (continuous + listOf(x, y, real)).toSet(), premises)
        val point = continuous.associateWith { 0.0 } + mapOf(x to 0.75, y to 0.75, real to 1.5)
        var attempts = 0L
        var stopped = false

        val cut = LinearAuthorityAggregateCuts.prepareSourcePairs(source, ExecutionControl { stopped },
            observe = { name, count ->
                if (name == "AggregatePairAttempts") {
                    attempts += count
                    if (attempts == 2L) stopped = true
                }
            }, maximumCandidates = 1, limits = LinearAuthorityAggregateCuts.pointSourcePairLimits.copy(work = 18_150L),
            point = point).single()

        assertEquals(setOf(200, 201), cut.owners)
        assertEquals(mapOf(x to BigFraction.ONE, y to BigFraction.ONE), cut.inequality.coefficients)
        assertEquals(1L, attempts)
        assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(source).verify(
            SourceEvidence(scope, SourceClaim.Consequence(cut.inequality), cut.derivation)))
    }

    @Test
    fun `point ordering should reach scoped half rounding before an unrelated separating pair`() {
        for (upper in listOf(false, true)) {
            val a = SourceCoordinate(SourceCoordinate.Kind.Integer, 2)
            val b = SourceCoordinate(SourceCoordinate.Kind.Integer, 3)
            val continuous = SourceCoordinate(SourceCoordinate.Kind.Real, 1)
            val sign = if (upper) BigFraction.MINUS_ONE else BigFraction.ONE
            val condition = if (upper) SourceCondition(upper = mapOf(x to sign))
            else SourceCondition(lower = mapOf(x to sign))
            val source = SourceProofContext(scope.copy(condition = condition),
                setOf(x, y, real, a, b, continuous), linkedMapOf(
                    "unrelated left" to SourcePremise(SourceInequality(
                        mapOf(a to BigFraction.ofLong(2), continuous to BigFraction.ONE), BigFraction.ofLong(3))),
                    "unrelated right" to SourcePremise(SourceInequality(
                        mapOf(b to BigFraction.ofLong(2), continuous to BigFraction.MINUS_ONE), BigFraction.ZERO)),
                    "scoped left" to SourcePremise(SourceInequality(
                        mapOf(x to BigFraction.ofLong(3) * sign, real to BigFraction.ONE), BigFraction.ofLong(4))),
                    "scoped right" to SourcePremise(SourceInequality(
                        mapOf(y to BigFraction.ofLong(2), real to BigFraction.MINUS_ONE), BigFraction.ZERO)),
                    "endpoint" to SourcePremise(SourceInequality(mapOf(x to sign.negated()), BigFraction.ZERO)),
                ))
            val point = mapOf(a to 0.75, b to 0.75, continuous to 1.5,
                x to sign.toDouble(), y to 0.5, real to 1.0)

            val cut = LinearAuthorityAggregateCuts.prepareSourcePairs(source, ExecutionControl.Never,
                maximumCandidates = 1, limits = LinearAuthorityAggregateCuts.pointSourcePairLimits,
                point = point).single()

            assertEquals(SourceInequality(mapOf(x to sign, y to BigFraction.ONE), BigFraction.ONE), cut.inequality)
            assertEquals(setOf(2, 3), cut.owners)
            assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(source).verify(
                SourceEvidence(source.scope, SourceClaim.Consequence(cut.inequality), cut.derivation)))
            assertIs<SourceEvidenceCheck.Rejected>(SourceEvidenceVerifier(source.atScope(scope)).verify(
                SourceEvidence(scope, SourceClaim.Consequence(cut.inequality), cut.derivation)))
        }
    }

    @Test
    fun `point ordering should preserve the first separating pair without an integer scope`() {
        val a = SourceCoordinate(SourceCoordinate.Kind.Integer, 2)
        val b = SourceCoordinate(SourceCoordinate.Kind.Integer, 3)
        val continuous = SourceCoordinate(SourceCoordinate.Kind.Real, 1)
        val source = SourceProofContext(scope, setOf(x, y, real, a, b, continuous), linkedMapOf(
            "first left" to SourcePremise(SourceInequality(
                mapOf(a to BigFraction.ofLong(2), continuous to BigFraction.ONE), BigFraction.ofLong(3))),
            "first right" to SourcePremise(SourceInequality(
                mapOf(b to BigFraction.ofLong(2), continuous to BigFraction.MINUS_ONE), BigFraction.ZERO)),
            "left" to SourcePremise(left), "right" to SourcePremise(right)))

        val cut = LinearAuthorityAggregateCuts.prepareSourcePairs(source, ExecutionControl.Never,
            maximumCandidates = 1, limits = LinearAuthorityAggregateCuts.pointSourcePairLimits,
            point = mapOf(a to 0.75, b to 0.75, continuous to 1.5, x to 0.75, y to 0.75, real to 1.5)).single()

        assertEquals(SourceInequality(mapOf(a to BigFraction.ONE, b to BigFraction.ONE), BigFraction.ONE),
            cut.inequality)
        assertEquals(setOf(0, 1), cut.owners)
        assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(source).verify(
            SourceEvidence(scope, SourceClaim.Consequence(cut.inequality), cut.derivation)))
    }

    @Test
    fun `cancelled scoped point ordering should withhold proposals and retain its charge`() {
        val source = SourceProofContext(scope.copy(condition = SourceCondition(lower = mapOf(x to BigFraction.ZERO))),
            setOf(x, y, real), linkedMapOf("left" to SourcePremise(left), "right" to SourcePremise(right),
                "loose" to SourcePremise(SourceInequality(left.coefficients, BigFraction.ofLong(100)))))
        val events = mutableMapOf<String, Long>()
        val limits = LinearAuthorityAggregateCuts.pointSourcePairLimits
        var stopped = false

        val cuts = LinearAuthorityAggregateCuts.prepareSourcePairs(source, ExecutionControl { stopped },
            observe = { name, count ->
                events[name] = (events[name] ?: 0L) + count
                if (name == "AggregatePointScopedRows") stopped = true
            }, limits = limits, point = mapOf(x to 0.75, y to 0.75, real to 1.5))

        assertTrue(cuts.isEmpty())
        assertTrue(assertNotNull(events["AggregateWork"]) in 1L..limits.work)
        assertTrue(assertNotNull(events["AggregateAllocation"]) in 1L..limits.allocation)
        assertNull(events["AggregatePairAttempts"])
        assertEquals(1L, events["AggregatePairStopped"])
    }

    @Test
    fun `point ordering should retain its charge and withhold proposals on cancellation`() {
        val source = SourceProofContext(scope, setOf(x, y, real),
            linkedMapOf("left" to SourcePremise(left), "right" to SourcePremise(right),
                "loose" to SourcePremise(SourceInequality(left.coefficients, BigFraction.ofLong(100)))))
        var stopped = false
        var work = 0L
        val events = mutableMapOf<String, Long>()

        val cuts = LinearAuthorityAggregateCuts.prepareSourcePairs(source, ExecutionControl { stopped },
            observe = { name, count ->
                events[name] = (events[name] ?: 0L) + count
                if (name == "AggregateWork") work += count
                if (name == "AggregatePointRowsScored") stopped = true
            }, point = mapOf(x to 0.75, y to 0.75, real to 1.5))

        assertTrue(cuts.isEmpty())
        assertTrue(work > 0L)
        assertEquals(null, events["AggregatePairAttempts"])
        assertEquals(1L, events["AggregatePairStopped"])
    }

    @Test
    fun `point directed pair preparation should retain a later separating proposal before its pool fills`() {
        val a = SourceCoordinate(SourceCoordinate.Kind.Integer, 2)
        val b = SourceCoordinate(SourceCoordinate.Kind.Integer, 3)
        val continuous = SourceCoordinate(SourceCoordinate.Kind.Real, 1)
        val source = SourceProofContext(scope, setOf(x, y, real, a, b, continuous), linkedMapOf(
            "first left" to SourcePremise(SourceInequality(
                mapOf(a to BigFraction.ofLong(2), continuous to BigFraction.ONE), BigFraction.ofLong(3))),
            "first right" to SourcePremise(SourceInequality(
                mapOf(b to BigFraction.ofLong(2), continuous to BigFraction.MINUS_ONE), BigFraction.ZERO)),
            "left" to SourcePremise(left), "right" to SourcePremise(right)))
        val point = mapOf(a to 0.0, b to 0.0, continuous to 0.0, x to 0.75, y to 0.75, real to 1.5)

        val cut = LinearAuthorityAggregateCuts.prepareSourcePairs(source, ExecutionControl.Never,
            maximumCandidates = 1, point = point).single()

        assertEquals(mapOf(x to BigFraction.ONE, y to BigFraction.ONE), cut.inequality.coefficients)
        assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(source).verify(
            SourceEvidence(scope, SourceClaim.Consequence(cut.inequality), cut.derivation)))
    }

    @Test
    fun `point directed aggregate bounds should combine different established endpoint sides`() {
        val residual = SourceCoordinate(SourceCoordinate.Kind.Real, 1)
        val other = SourceCoordinate(SourceCoordinate.Kind.Real, 2)
        val mixed = SourceInequality(left.coefficients + mapOf(
            residual to BigFraction.MINUS_ONE, other to BigFraction.MINUS_ONE), left.rhs)
        val source = SourceProofContext(scope, setOf(x, y, real, residual, other), linkedMapOf(
            "left" to SourcePremise(mixed), "right" to SourcePremise(right),
            "lower" to SourcePremise(SourceInequality(mapOf(residual to BigFraction.MINUS_ONE),
                BigFraction.ZERO)),
            "upper" to SourcePremise(SourceInequality(mapOf(residual to BigFraction.ONE),
                BigFraction.ofLong(2))),
            "other lower" to SourcePremise(SourceInequality(mapOf(other to BigFraction.MINUS_ONE),
                BigFraction.ZERO)),
            "other upper" to SourcePremise(SourceInequality(mapOf(other to BigFraction.ONE),
                BigFraction.ofLong(2))),
        ))
        val point = mapOf(x to 1.25, y to 1.25, real to 2.5, residual to 2.0, other to 0.0)

        val cut = LinearAuthorityAggregateCuts.prepareSourcePairs(source, ExecutionControl.Never, point =
            point).single()

        assertEquals(SourceInequality(mapOf(x to BigFraction.ONE, y to BigFraction.ONE,
            other to BigFraction.MINUS_ONE), BigFraction.ofLong(2)), cut.inequality)
        assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(source).verify(
            SourceEvidence(scope, SourceClaim.Consequence(cut.inequality), cut.derivation)))
    }

    @Test
    fun `a farther stated endpoint should separate when nearer aggregate rounding does not`() {
        val otherReal = SourceCoordinate(SourceCoordinate.Kind.Real, 1)
        for (sign in listOf(-1L, 1L)) {
            val source = SourceProofContext(scope, setOf(x, y, real, otherReal), linkedMapOf(
                "left" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ofLong(5),
                    otherReal to BigFraction.ONE, real to BigFraction.ofLong(4L * sign)), BigFraction.ofLong(7))),
                "right" to SourcePremise(SourceInequality(mapOf(y to BigFraction.ofLong(5),
                    otherReal to BigFraction.MINUS_ONE), BigFraction.ZERO)),
                "lower" to SourcePremise(SourceInequality(mapOf(real to BigFraction.MINUS_ONE),
                    if (sign < 0) BigFraction.ZERO else BigFraction.ONE)),
                "upper" to SourcePremise(SourceInequality(mapOf(real to BigFraction.ONE),
                    if (sign < 0) BigFraction.ONE else BigFraction.ZERO)),
            ))

            val cuts = LinearAuthorityAggregateCuts.prepareSourcePairs(source, ExecutionControl.Never,
                maximumCandidates = 1, limits = LinearAuthorityAggregateCuts.pointSourcePairLimits.copy(rows = 2),
                point = mapOf(x to 1.88, y to 0.0, real to -0.6 * sign, otherReal to 0.0))

            val cut = cuts.single()
            assertEquals(SourceInequality(mapOf(x to BigFraction.ONE, y to BigFraction.ONE,
                real to BigFraction.ofLong(4L * sign) * BigFraction.ofLong(3).reciprocal()), BigFraction.ONE),
                cut.inequality)
            assertEquals(setOf(0, 1, if (sign < 0) 2 else 3), cut.owners)
            assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(source).verify(
                SourceEvidence(scope, SourceClaim.Consequence(cut.inequality), cut.derivation)))
        }
    }

    @Test
    fun `interrupting a preferred endpoint derivation should withhold its partial proposal`() {
        val otherReal = SourceCoordinate(SourceCoordinate.Kind.Real, 1)
        val source = SourceProofContext(scope, setOf(x, y, real, otherReal), linkedMapOf(
            "left" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ofLong(5),
                otherReal to BigFraction.ONE, real to BigFraction.ofLong(-4)), BigFraction.ofLong(7))),
            "right" to SourcePremise(SourceInequality(mapOf(y to BigFraction.ofLong(5),
                otherReal to BigFraction.MINUS_ONE), BigFraction.ZERO)),
            "lower" to SourcePremise(SourceInequality(mapOf(real to BigFraction.MINUS_ONE), BigFraction.ZERO)),
            "upper" to SourcePremise(SourceInequality(mapOf(real to BigFraction.ONE), BigFraction.ONE)),
        ))
        var stopped = false

        val cuts = LinearAuthorityAggregateCuts.prepareSourcePairs(source, ExecutionControl { stopped },
            observe = { name, _ -> if (name == "AggregatePreferredSideAttempts") stopped = true },
            maximumCandidates = 1, limits = LinearAuthorityAggregateCuts.pointSourcePairLimits.copy(rows = 2),
            point = mapOf(x to 1.88, y to 0.0, real to 0.6, otherReal to 0.0))

        assertTrue(stopped)
        assertTrue(cuts.isEmpty())
    }

    @Test
    fun `an alternative endpoint should share the spent generation work allowance`() {
        val otherReal = SourceCoordinate(SourceCoordinate.Kind.Real, 1)
        val source = SourceProofContext(scope, setOf(x, y, real, otherReal), linkedMapOf(
            "left" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ofLong(5),
                otherReal to BigFraction.ONE, real to BigFraction.ofLong(-4)), BigFraction.ofLong(7))),
            "right" to SourcePremise(SourceInequality(mapOf(y to BigFraction.ofLong(5),
                otherReal to BigFraction.MINUS_ONE), BigFraction.ZERO)),
            "lower" to SourcePremise(SourceInequality(mapOf(real to BigFraction.MINUS_ONE), BigFraction.ZERO)),
            "upper" to SourcePremise(SourceInequality(mapOf(real to BigFraction.ONE), BigFraction.ONE)),
        ))
        val point = mapOf(x to 1.88, y to 0.0, real to 0.6, otherReal to 0.0)
        val limits = LinearAuthorityAggregateCuts.pointSourcePairLimits.copy(rows = 2)
        var work = 0L
        var beforePreferred = 0L
        LinearAuthorityAggregateCuts.prepareSourcePairs(source, ExecutionControl.Never, observe = { name, count ->
            if (name == "AggregateWork") work += count
            if (name == "AggregatePreferredSideAttempts") beforePreferred = work
        }, maximumCandidates = 1, limits = limits, point = point)
        assertTrue(beforePreferred > 0L)
        val observations = mutableMapOf<String, Long>()

        val cuts = LinearAuthorityAggregateCuts.prepareSourcePairs(source, ExecutionControl.Never,
            observe = { name, count -> observations[name] = (observations[name] ?: 0L) + count },
            maximumCandidates = 1, limits = limits.copy(work = beforePreferred), point = point)

        assertTrue(cuts.isEmpty())
        assertTrue((observations["AggregateWork"] ?: 0L) <= beforePreferred)
    }
}

internal object AggregateCutTestSource {
    val x = SourceCoordinate(SourceCoordinate.Kind.Integer, 0)
    val y = SourceCoordinate(SourceCoordinate.Kind.Integer, 1)
    val real = SourceCoordinate(SourceCoordinate.Kind.Real, 0)
    val scope = SourceScope(SourceRevision("aggregation", "exact-affine"))
    val left = SourceInequality(mapOf(x to BigFraction.ofLong(2), real to BigFraction.ONE),
        BigFraction.ofLong(3))
    val right = SourceInequality(mapOf(y to BigFraction.ofLong(2), real to BigFraction.MINUS_ONE),
        BigFraction.ZERO)
}
