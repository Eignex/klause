package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.IntBounds
import com.eignex.klause.lp.SourceLpBudget
import com.eignex.klause.lp.SourceLpFunding
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.solver.evidence.SourceDerivation
import com.eignex.klause.solver.evidence.SourceInequality
import com.eignex.klause.solver.evidence.SourcePremise
import com.eignex.klause.solver.evidence.SourceProofContext
import com.eignex.klause.solver.evidence.SourceRevision
import com.eignex.klause.solver.evidence.SourceScope
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.numbers.BigFraction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JointRepairNeighborhoodTest {
    @Test
    fun `advisory neighborhoods should pin other integers and leave continuous recourse free`() {
        val integers = (0..2).map { SourceCoordinate(SourceCoordinate.Kind.Integer, it) }
        val real = SourceCoordinate(SourceCoordinate.Kind.Real, 0)
        val source = SourceProofContext(SourceScope(SourceRevision("advisory-connected", "exact")),
            integers.toSet() + real, mapOf("edge" to SourcePremise(SourceInequality(
                mapOf(integers[0] to BigFraction.ONE, integers[1] to BigFraction.ONE, real to BigFraction.ONE,
                    integers[2] to BigFraction.ONE), BigFraction.ONE))))
        val repair = assertNotNull(JointRepairNeighborhood.prepare(source, 3, 1, ExecutionControl.Never,
            sourceIntegers = intArrayOf(1, 2)))
        val funding = SourceLpFunding(1_000_000, 1_000_000).also { it.grant(1_000_000) }

        val condition = assertIs<JointRepairNeighborhood.AdvisorySelection.Ready>(repair.advisoryNeighborhood(
            listOf(BigFraction.ONE, BigFraction.ONE, BigFraction.ZERO), 0, BigFraction.ONE,
            SourceLpBudget(funding = funding), ExecutionControl.Never)).condition

        assertEquals(mapOf(0 to BigFraction.ONE), condition.lower)
        assertEquals(condition.lower, condition.upper)
        assertTrue(condition.bools.isEmpty())
        assertTrue(funding.spent > 0)
        assertTrue(funding.allocated > 0)
    }

    @Test
    fun `advisory neighborhoods should close integer support without joining continuous recourse`() {
        val integers = (0..4).map { SourceCoordinate(SourceCoordinate.Kind.Integer, it) }
        val real = SourceCoordinate(SourceCoordinate.Kind.Real, 0)
        val edges = listOf(integers[0] to integers[1], integers[1] to integers[3],
            integers[3] to integers[2], integers[2] to integers[0], integers[3] to real, integers[4] to real)
        val source = SourceProofContext(SourceScope(SourceRevision("advisory-cycle", "exact")),
            integers.toSet() + real, edges.mapIndexed { id, (a, b) ->
                id.toString() to SourcePremise(SourceInequality(mapOf(a to BigFraction.ONE,
                    b to BigFraction.ONE), BigFraction.ONE))
            }.toMap())
        val repair = assertNotNull(JointRepairNeighborhood.prepare(source, 5, 1, ExecutionControl.Never))
        val funding = SourceLpFunding(1_000_000, 1_000_000).also { it.grant(1_000_000) }

        val condition = assertIs<JointRepairNeighborhood.AdvisorySelection.Ready>(repair.advisoryNeighborhood(
            listOf(BigFraction.ONE, BigFraction.ZERO, BigFraction.ZERO, BigFraction.ONE, BigFraction.ONE),
            0, BigFraction.ONE, SourceLpBudget(funding = funding), ExecutionControl.Never, expand = true)).condition

        assertEquals(mapOf(4 to BigFraction.ONE), condition.lower)
        assertEquals(condition.lower, condition.upper)
    }

    @Test
    fun `advisory support should respect the existing free integer limit`() {
        for (count in listOf(32, 33)) {
            val columns = (0 until count).map { SourceCoordinate(SourceCoordinate.Kind.Integer, it) }
            val source = SourceProofContext(SourceScope(SourceRevision("advisory-width", "exact")),
                columns.toSet(), mapOf("row" to SourcePremise(SourceInequality(
                    columns.associateWith { BigFraction.ONE }, BigFraction.ONE))))
            val repair = assertNotNull(JointRepairNeighborhood.prepare(source, count, 0, ExecutionControl.Never))
            val funding = SourceLpFunding(1_000_000, 1_000_000).also { it.grant(1_000_000) }

            val selected = repair.advisoryNeighborhood(List(count) { BigFraction.ZERO }, 0, BigFraction.ONE,
                SourceLpBudget(funding = funding), ExecutionControl.Never, expand = true)

            if (count == 32) assertTrue(assertIs<JointRepairNeighborhood.AdvisorySelection.Ready>(selected)
                .condition.lower.isEmpty())
            else assertEquals(JointRepairNeighborhood.AdvisorySelection.Limit.Width,
                assertIs<JointRepairNeighborhood.AdvisorySelection.Declined>(selected).limit)
        }
    }

    @Test
    fun `declined support and ordinary fallback should share a graph visit allowance`() {
        val columns = (0..1).map { SourceCoordinate(SourceCoordinate.Kind.Integer, it) }
        val source = SourceProofContext(SourceScope(SourceRevision("advisory-visits", "exact")),
            columns.toSet(), (0 until 4096).associate { id ->
                id.toString() to SourcePremise(SourceInequality(
                    columns.associateWith { BigFraction.ONE }, BigFraction.ONE))
            })
        val repair = assertNotNull(JointRepairNeighborhood.prepare(source, 2, 0, ExecutionControl.Never))
        val funding = SourceLpFunding(1_000_000, 1_000_000).also { it.grant(1_000_000) }

        val selected = repair.advisoryNeighborhood(List(2) { BigFraction.ZERO }, 0, BigFraction.ONE,
            SourceLpBudget(funding = funding), ExecutionControl.Never, expand = true)

        assertEquals(JointRepairNeighborhood.AdvisorySelection.Limit.Visits,
            assertIs<JointRepairNeighborhood.AdvisorySelection.Declined>(selected).limit)
        val fallback = repair.proposeWithinSelectionBudget(List(2) { BigFraction.ZERO }, booleanArrayOf(), 2, 1,
            BigFraction.ONE, repair.visitedEntries)
        assertEquals(mapOf(1 to BigFraction.ZERO), fallback.lower)
        assertTrue(repair.visitedEntries <= 8192)
        assertTrue(funding.spent > 0)
    }

    @Test
    fun `advisory support should withhold selection when its resources are unavailable`() {
        val columns = (0..3).map { SourceCoordinate(SourceCoordinate.Kind.Integer, it) }
        val source = SourceProofContext(SourceScope(SourceRevision("advisory-unavailable", "exact")),
            columns.toSet(), mapOf("row" to SourcePremise(SourceInequality(
                columns.associateWith { BigFraction.ONE }, BigFraction.ONE))))
        val repair = assertNotNull(JointRepairNeighborhood.prepare(source, 4, 0, ExecutionControl.Never))
        for (variant in listOf("work", "allocation", "cancelled")) {
            val funding = SourceLpFunding(if (variant == "work") 0 else 1_000_000,
                if (variant == "allocation") 0 else 1_000_000).also { it.grant(1_000_000) }
            var polls = 0
            val token = ExecutionControl { variant == "cancelled" && ++polls >= 6 }

            val selected = repair.advisoryNeighborhood(List(4) { BigFraction.ZERO }, 0, BigFraction.ONE,
                SourceLpBudget(funding = funding), token, expand = true)

            assertEquals(JointRepairNeighborhood.AdvisorySelection.Unavailable, selected)
        }
    }

    @Test
    fun `binary neighborhoods should allow coordinated changes to agreeing integers`() {
        val columns = (0 until 40).map { SourceCoordinate(SourceCoordinate.Kind.Integer, it) }
        val source = SourceProofContext(SourceScope(SourceRevision("binary-support", "exact")),
            columns.toSet(), emptyMap())
        val funding = SourceLpFunding(1_000_000, 1_000_000).also { it.grant(1_000_000) }
        val budget = SourceLpBudget(funding = funding)
        val guidance = LinearRepairGuidance()
        assertTrue(guidance.remember(source, columns.associateWith {
            if (it.id < 2) 1.0 else if (it.id == 39) 0.0 else 0.5
        }, budget, ExecutionControl.Never))
        val repair = assertNotNull(JointRepairNeighborhood.prepare(source, 40, 0, ExecutionControl.Never))

        val condition = assertNotNull(repair.binaryNeighborhood(guidance.find(source),
            List(40) { if (it < 2) BigFraction.ONE else BigFraction.ZERO },
            IntBounds.fromModelBounds(LongArray(40), LongArray(40) { 1 }, null, null), booleanArrayOf(),
            BigFraction.ONE, budget, ExecutionControl.Never))

        assertEquals(mapOf(39 to BigFraction.ZERO), condition.lower)
        assertEquals(condition.lower, condition.upper)
    }

    @Test
    fun `relaxation agreement should pin only agreeing eligible integers`() {
        val columns = (0..41).map { SourceCoordinate(SourceCoordinate.Kind.Integer, it) }
        val source = SourceProofContext(SourceScope(SourceRevision("agreement", "exact")),
            columns.toSet(), emptyMap())
        val funding = SourceLpFunding(1_000_000, 1_000_000).also { it.grant(1_000_000) }
        val budget = SourceLpBudget(funding = funding)
        val guidance = LinearRepairGuidance()
        assertTrue(guidance.remember(source, columns.associateWith { if (it.id < 2) 1.0 else 0.5 },
            budget, ExecutionControl.Never))
        val repair = assertNotNull(JointRepairNeighborhood.prepare(source, 42, 0, ExecutionControl.Never,
            sourceIntegers = (1..41).toList().toIntArray()))

        val condition = assertNotNull(repair.relaxationNeighborhood(guidance.find(source),
            List(42) { BigFraction.ONE }, booleanArrayOf(true), BigFraction.ONE, budget, ExecutionControl.Never))

        assertEquals(mapOf(1 to BigFraction.ONE), condition.lower)
        assertEquals(condition.lower, condition.upper)
        assertEquals(mapOf(0 to true), condition.bools)
        assertEquals(BigFraction.ONE, condition.cutoff)
        assertEquals(40L, repair.selectedIntegers)
    }

    @Test
    fun `unavailable or trivial agreement should decline the relaxation neighborhood`() {
        val columns = (0..1).map { SourceCoordinate(SourceCoordinate.Kind.Integer, it) }
        val source = SourceProofContext(SourceScope(SourceRevision("agreement-decline", "exact")),
            columns.toSet(), emptyMap())
        for (variant in listOf("missing", "all-agree", "all-disagree", "cutoff", "work", "allocation", "cancelled")) {
            val budget = SourceLpBudget(funding = SourceLpFunding(100_000, 100_000).also { it.grant(100_000) })
            val guidance = LinearRepairGuidance()
            val values = when (variant) {
                "all-agree" -> listOf(1.0, 1.0)
                "all-disagree" -> listOf(0.5, 0.5)
                else -> listOf(1.0, 0.5)
            }
            assertTrue(guidance.remember(source, columns.zip(values).toMap(), budget, ExecutionControl.Never))
            val consumer = SourceLpBudget(funding = SourceLpFunding(if (variant == "work") 0 else 100_000,
                if (variant == "allocation") 0 else 100_000).also { it.grant(100_000) })
            val repair = assertNotNull(JointRepairNeighborhood.prepare(source, 2, 0, ExecutionControl.Never))

            val condition = repair.relaxationNeighborhood(if (variant == "missing") null else guidance.find(source),
                List(2) { BigFraction.ONE }, booleanArrayOf(), if (variant == "cutoff") null else BigFraction.ONE,
                consumer, ExecutionControl { variant == "cancelled" })

            assertNull(condition)
        }
    }

    @Test
    fun `a cited eligible pin should persist until its advice is cleared`() {
        val columns = (0..3).map { SourceCoordinate(SourceCoordinate.Kind.Integer, it) }
        val source = SourceProofContext(SourceScope(SourceRevision("refuted-pins", "exact")), columns.toSet(),
            mapOf("edge" to SourcePremise(SourceInequality(mapOf(columns[2] to BigFraction.ONE,
                columns[3] to BigFraction.ONE), BigFraction.ONE))))
        val repair = assertNotNull(JointRepairNeighborhood.prepare(source, 4, 0, ExecutionControl.Never,
            sourceIntegers = intArrayOf(0, 2, 3)))
        val centers = List(4) { BigFraction.ZERO }
        val pinned = repair.propose(centers, booleanArrayOf(), 2, 0, BigFraction.ONE, null)
        val ordinary = repair.propose(centers, booleanArrayOf(), 2, 1, BigFraction.ONE, null)
        val funding = SourceLpFunding(1_000, 1_000).also { it.grant(1_000) }
        assertTrue(repair.guideRefutation(linkedSetOf(
            SourceDerivation.Assumption(columns[1], false), SourceDerivation.Assumption(columns[2], true)),
            pinned, SourceLpBudget(funding = funding), ExecutionControl.Never))

        val guided = repair.propose(centers, booleanArrayOf(), 2, 1, BigFraction.ONE, null)

        assertEquals(mapOf(0 to BigFraction.ZERO), guided.lower)
        assertEquals(guided.lower, guided.upper)
        assertEquals(guided, repair.propose(centers, booleanArrayOf(), 2, 1, BigFraction.ONE, null))
        repair.clearRefutation()
        assertEquals(ordinary, repair.propose(centers, booleanArrayOf(), 2, 1, BigFraction.ONE, null))
        assertTrue(funding.spent > 0)
        assertTrue(funding.allocated > 0)
    }

    @Test
    fun `stale or unpaid refutation guidance should retain the ordinary proposal`() {
        val columns = (0..2).map { SourceCoordinate(SourceCoordinate.Kind.Integer, it) }
        val source = SourceProofContext(SourceScope(SourceRevision("refuted-fallback", "exact")),
            columns.toSet() + SourceCoordinate(SourceCoordinate.Kind.Boolean, 0), emptyMap())
        for (variant in listOf("center", "boolean", "cutoff", "cancelled", "work", "allocation", "unpinned")) {
            val repair = assertNotNull(JointRepairNeighborhood.prepare(source, 3, 0, ExecutionControl.Never))
            val centers = List(3) { BigFraction.ZERO }
            val pinned = repair.propose(centers, booleanArrayOf(false), 2, 0, BigFraction.ONE, null)
            val funding = SourceLpFunding(if (variant == "work") 0 else 1_000,
                if (variant == "allocation") 0 else 1_000).also { it.grant(1_000) }
            repair.guideRefutation(setOf(SourceDerivation.Assumption(columns[2], false)),
                if (variant == "unpinned") pinned.copy(upper = emptyMap()) else pinned,
                SourceLpBudget(funding = funding), ExecutionControl { variant == "cancelled" })
            val current = if (variant == "center") listOf(BigFraction.ZERO, BigFraction.ZERO, BigFraction.ONE)
            else centers
            val cutoff = if (variant == "cutoff") BigFraction.ONE else null

            val proposed = repair.propose(current, booleanArrayOf(variant == "boolean"), 2, 1, BigFraction.ONE, cutoff)

            assertEquals(mapOf(1 to current[1], 2 to current[2]), proposed.lower)
            assertEquals(proposed.lower, proposed.upper)
        }
    }

    @Test
    fun `independent refuted pins should share the existing free variable quota`() {
        val columns = (0..2).map { SourceCoordinate(SourceCoordinate.Kind.Integer, it) }
        val source = SourceProofContext(SourceScope(SourceRevision("independent-pins", "exact")),
            columns.toSet(), emptyMap())
        val repair = assertNotNull(JointRepairNeighborhood.prepare(source, 3, 0, ExecutionControl.Never))
        val centers = List(3) { BigFraction.ZERO }
        val budget = SourceLpBudget(funding = SourceLpFunding(100_000, 100_000).also { it.grant(100_000) })
        val pinned = repair.propose(centers, booleanArrayOf(), 2, 0, BigFraction.ONE, null)
        assertTrue(repair.guideRefutation(setOf(SourceDerivation.Assumption(columns[0], false)),
            pinned, budget, ExecutionControl.Never))
        val first = repair.propose(centers, booleanArrayOf(), 2, 1, BigFraction.ONE, null)
        assertTrue(repair.guideRefutation(setOf(SourceDerivation.Assumption(columns[1], false)),
            first, budget, ExecutionControl.Never))

        val combined = repair.propose(centers, booleanArrayOf(), 2, 2, BigFraction.ONE, null)

        assertEquals(mapOf(2 to BigFraction.ZERO), combined.lower)
        assertEquals(combined.lower, combined.upper)
    }

    @Test
    fun `retained guidance should spend containing funding without renewing a numerical grant`() {
        val columns = (0..2).map { SourceCoordinate(SourceCoordinate.Kind.Integer, it) }
        val source = SourceProofContext(SourceScope(SourceRevision("retained-funding", "exact")), columns.toSet(),
            mapOf("edge" to SourcePremise(SourceInequality(mapOf(columns[1] to BigFraction.ONE,
                columns[2] to BigFraction.ONE), BigFraction.ONE))))
        val repair = assertNotNull(JointRepairNeighborhood.prepare(source, 3, 0, ExecutionControl.Never))
        val centers = List(3) { BigFraction.ZERO }
        val funding = SourceLpFunding(10_000, 10_000).also { it.grant(1_000) }
        val pinned = repair.propose(centers, booleanArrayOf(), 2, 0, BigFraction.ONE, null)
        assertTrue(repair.guideRefutation(setOf(SourceDerivation.Assumption(columns[2], false)),
            pinned, SourceLpBudget(funding = funding), ExecutionControl.Never))
        funding.grant(0)
        val before = funding.spent

        val guided = repair.propose(centers, booleanArrayOf(), 2, 1, BigFraction.ONE, null)

        assertEquals(mapOf(0 to BigFraction.ZERO), guided.lower)
        assertTrue(funding.spent > before)
        assertEquals(0L, funding.availableWork)
    }

    @Test
    fun `exhausted or cancelled retained guidance should restore ordinary selection`() {
        val columns = (0..2).map { SourceCoordinate(SourceCoordinate.Kind.Integer, it) }
        val source = SourceProofContext(SourceScope(SourceRevision("retained-stop", "exact")), columns.toSet(),
            mapOf("edge" to SourcePremise(SourceInequality(mapOf(columns[1] to BigFraction.ONE,
                columns[2] to BigFraction.ONE), BigFraction.ONE))))
        for (variant in listOf("work", "allocation", "cancellation")) {
            val repair = assertNotNull(JointRepairNeighborhood.prepare(source, 3, 0, ExecutionControl.Never))
            val centers = List(3) { BigFraction.ZERO }
            val ordinary = repair.propose(centers, booleanArrayOf(), 2, 1, BigFraction.ONE, null)
            val funding = SourceLpFunding(10_000, 10_000).also { it.grant(1_000) }
            val pinned = repair.propose(centers, booleanArrayOf(), 2, 0, BigFraction.ONE, null)
            var cancelled = false
            assertTrue(repair.guideRefutation(setOf(SourceDerivation.Assumption(columns[2], false)),
                pinned, SourceLpBudget(funding = funding), ExecutionControl { cancelled }))
            when (variant) {
                "work" -> funding.charge(funding.remainingWork)
                "allocation" -> funding.charge(0L, funding.remainingAllocation)
                else -> cancelled = true
            }

            val proposed = repair.propose(centers, booleanArrayOf(), 2, 1, BigFraction.ONE, null)

            assertEquals(ordinary, proposed)
        }
    }

    @Test
    fun `paid guidance should select a connected pair while retaining unrelated pins and cutoff`() {
        val columns = (0..4).map { SourceCoordinate(SourceCoordinate.Kind.Integer, it) }
        val source = SourceProofContext(SourceScope(SourceRevision("shared-connected", "exact")), columns.toSet(),
            mapOf("left" to SourcePremise(SourceInequality(mapOf(columns[0] to BigFraction.ONE,
                columns[2] to BigFraction.ONE), BigFraction.ONE)),
                "right" to SourcePremise(SourceInequality(mapOf(columns[3] to BigFraction.ONE,
                    columns[4] to BigFraction.ONE), BigFraction.ONE))))
        val funding = SourceLpFunding(100_000, 100_000).also { it.grant(100_000) }
        val budget = SourceLpBudget(funding = funding)
        val guidance = LinearRepairGuidance()
        guidance.remember(source, mapOf(columns[1] to 100.0, columns[2] to 3.0, columns[3] to 5.0),
            budget, ExecutionControl.Never)
        val repair = assertNotNull(JointRepairNeighborhood.prepare(source, 5, 0, ExecutionControl.Never,
            sourceIntegers = intArrayOf(0, 2, 3, 4)))
        val centers = listOf(BigFraction.ZERO, BigFraction.ZERO, BigFraction.ZERO,
            BigFraction.ONE, BigFraction.ONE)
        assertTrue(repair.guide(guidance.find(source), centers, budget, ExecutionControl.Never))

        val condition = repair.propose(centers, booleanArrayOf(true), 2, 1, BigFraction.ONE, BigFraction.ONE)

        assertEquals(mapOf(0 to BigFraction.ZERO, 2 to BigFraction.ZERO), condition.lower)
        assertEquals(condition.lower, condition.upper)
        assertEquals(mapOf(0 to true), condition.bools)
        assertEquals(BigFraction.ONE, condition.cutoff)
        assertEquals(2L, repair.selectedIntegers)
    }

    @Test
    fun `declined guidance should restore the ordinary proposal after a ranked proposal`() {
        val columns = (0..2).map { SourceCoordinate(SourceCoordinate.Kind.Integer, it) }
        val source = SourceProofContext(SourceScope(SourceRevision("shared-fallback", "exact")), columns.toSet(),
            mapOf("edge" to SourcePremise(SourceInequality(mapOf(columns[0] to BigFraction.ONE,
                columns[1] to BigFraction.ONE), BigFraction.ONE))))
        val retained = SourceLpFunding(100_000, 100_000).also { it.grant(100_000) }
        val guidance = LinearRepairGuidance()
        guidance.remember(source, mapOf(columns[2] to 2.0), SourceLpBudget(funding = retained), ExecutionControl.Never)
        val centers = List(3) { BigFraction.ZERO }

        for (variant in listOf("missing", "work", "allocation", "cancelled", "mid-ranking")) {
            val repair = assertNotNull(JointRepairNeighborhood.prepare(source, 3, 0, ExecutionControl.Never))
            val ordinary = repair.propose(centers, booleanArrayOf(), 2, 1, BigFraction.ONE, null)
            assertTrue(repair.guide(guidance.find(source), centers, SourceLpBudget(funding = retained),
                ExecutionControl.Never))
            assertEquals(mapOf(0 to BigFraction.ZERO, 1 to BigFraction.ZERO),
                repair.propose(centers, booleanArrayOf(), 2, 1, BigFraction.ONE, null).lower)
            val funding = SourceLpFunding(if (variant == "work") 0 else 100_000,
                if (variant == "allocation") 0 else 100_000).also { it.grant(100_000) }
            var checks = 0

            assertFalse(repair.guide(if (variant == "missing") null else guidance.find(source), centers,
                SourceLpBudget(funding = funding), ExecutionControl {
                    variant == "cancelled" || (variant == "mid-ranking" && ++checks == 3)
                }))

            assertEquals(ordinary, repair.propose(centers, booleanArrayOf(), 2, 1, BigFraction.ONE, null))
        }
    }

    @Test
    fun `ranking should debit the consuming workers work and allocation`() {
        val integer = SourceCoordinate(SourceCoordinate.Kind.Integer, 0)
        val source = SourceProofContext(SourceScope(SourceRevision("shared-cost", "exact")), setOf(integer),
            emptyMap())
        val producer = SourceLpFunding(1000, 1000).also { it.grant(1000) }
        val guidance = LinearRepairGuidance()
        guidance.remember(source, mapOf(integer to 2.0), SourceLpBudget(funding = producer), ExecutionControl.Never)
        val consumer = SourceLpFunding(1000, 1000).also { it.grant(1000) }
        val repair = assertNotNull(JointRepairNeighborhood.prepare(source, 1, 0, ExecutionControl.Never))

        assertTrue(repair.guide(guidance.find(source), listOf(BigFraction.ZERO),
            SourceLpBudget(funding = consumer), ExecutionControl.Never))

        assertTrue(consumer.spent > 0)
        assertTrue(consumer.allocated > 0)
    }

    @Test
    fun `initial repair should leave integer backed real columns outside the source complement`() {
        val columns = (0..2).map { SourceCoordinate(SourceCoordinate.Kind.Integer, it) }
        val source = SourceProofContext(SourceScope(SourceRevision("backings", "exact")), columns.toSet(), emptyMap())
        val repair = assertNotNull(JointRepairNeighborhood.prepare(source, 3, 0, ExecutionControl.Never,
            sourceIntegers = intArrayOf(0, 2)))

        val condition = repair.propose(List(3) { BigFraction.ONE }, booleanArrayOf(), 1, 0,
            BigFraction.ONE, null)

        assertEquals(mapOf(0 to BigFraction.ONE, 2 to BigFraction.ONE), condition.lower)
        assertEquals(condition.lower, condition.upper)
    }

    @Test
    fun `integer backed real bridges should connect source inputs without consuming their quota`() {
        val columns = (0..2).map { SourceCoordinate(SourceCoordinate.Kind.Integer, it) }
        val source = SourceProofContext(SourceScope(SourceRevision("backing-bridge", "exact")), columns.toSet(),
            mapOf("left" to SourcePremise(SourceInequality(mapOf(columns[0] to BigFraction.ONE,
                columns[1] to BigFraction.ONE), BigFraction.ONE)),
                "right" to SourcePremise(SourceInequality(mapOf(columns[1] to BigFraction.ONE,
                    columns[2] to BigFraction.ONE), BigFraction.ONE))))
        val repair = assertNotNull(JointRepairNeighborhood.prepare(source, 3, 0, ExecutionControl.Never,
            sourceIntegers = intArrayOf(0, 2)))

        val condition = repair.propose(List(3) { BigFraction.ZERO }, booleanArrayOf(), 2, 1,
            BigFraction.ONE, null)

        assertEquals(emptyMap(), condition.lower)
        assertEquals(2L, repair.selectedIntegers)
    }

    @Test
    fun `fractional integer backed reals should not displace source input seeds`() {
        val columns = (0..3).map { SourceCoordinate(SourceCoordinate.Kind.Integer, it) }
        val source = SourceProofContext(SourceScope(SourceRevision("backing-seeds", "exact")),
            columns.toSet(), emptyMap())
        val repair = assertNotNull(JointRepairNeighborhood.prepare(source, 4, 0, ExecutionControl.Never,
            sourceIntegers = intArrayOf(0, 2, 3)))
        repair.guide(mapOf(columns[0] to 0.25, columns[1] to 0.5))

        val condition = repair.propose(List(4) { BigFraction.ZERO }, booleanArrayOf(), 2, 1,
            BigFraction.ONE, null)

        assertEquals(mapOf(2 to BigFraction.ZERO, 3 to BigFraction.ZERO), condition.lower)
    }

    @Test
    fun `an integer indicator should connect its trigger with the guarded residual`() {
        val trigger = SourceCoordinate(SourceCoordinate.Kind.Integer, 0)
        val x = SourceCoordinate(SourceCoordinate.Kind.Integer, 1)
        val real = SourceCoordinate(SourceCoordinate.Kind.Real, 0)
        val source = SourceProofContext(SourceScope(SourceRevision("indicator", "exact")), setOf(trigger, x, real),
            mapOf("guarded" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ONE, real to BigFraction.ONE),
                BigFraction.ONE), emptyMap(), mapOf(trigger to BigFraction.ZERO))))
        val repair = assertNotNull(JointRepairNeighborhood.prepare(source, 2, 1, ExecutionControl.Never))

        val condition = repair.propose(List(3) { BigFraction.ZERO }, booleanArrayOf(), 2, 1,
            BigFraction.ONE, null)

        assertEquals(emptyMap(), condition.lower)
        assertEquals(emptyMap(), condition.upper)
    }

    @Test
    fun `oversized coordinate indexes should decline before allocation`() {
        val source = SourceProofContext(SourceScope(SourceRevision("large", "exact")), emptySet(), emptyMap())

        assertNull(JointRepairNeighborhood.prepare(source, 1_048_577, 0, ExecutionControl.Never))
    }

    @Test
    fun `repair should free related integers through a real bridge and retain unrelated pins`() {
        val x = SourceCoordinate(SourceCoordinate.Kind.Integer, 1)
        val y = SourceCoordinate(SourceCoordinate.Kind.Integer, 2)
        val z = SourceCoordinate(SourceCoordinate.Kind.Integer, 0)
        val real = SourceCoordinate(SourceCoordinate.Kind.Real, 0)
        val source = SourceProofContext(SourceScope(SourceRevision("bridge", "exact")), setOf(x, y, z, real),
            mapOf("x-real" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ONE, real to BigFraction.ONE),
                BigFraction.ONE)),
                "real-y" to SourcePremise(SourceInequality(mapOf(real to BigFraction.ONE, y to BigFraction.ONE),
                    BigFraction.ONE))))
        val repair = assertNotNull(JointRepairNeighborhood.prepare(source, 3, 1, ExecutionControl.Never))
        repair.guide(mapOf(y to 0.5, x to 1.0, z to 2.0, real to 0.5))

        val condition = repair.propose(List(4) { BigFraction.ZERO }, booleanArrayOf(), 2, 1,
            BigFraction.ONE, BigFraction.ONE)

        assertEquals(mapOf(0 to BigFraction.ZERO), condition.lower)
        assertEquals(condition.lower, condition.upper)
        assertEquals(BigFraction.ONE, condition.cutoff)
        assertEquals(2L, repair.selectedIntegers)
    }

    @Test
    fun `initial rounding should pin all integer centers and leave continuous coordinates free`() {
        val x = SourceCoordinate(SourceCoordinate.Kind.Integer, 0)
        val real = SourceCoordinate(SourceCoordinate.Kind.Real, 0)
        val source = SourceProofContext(SourceScope(SourceRevision("rounding", "exact")), setOf(x, real), emptyMap())
        val repair = assertNotNull(JointRepairNeighborhood.prepare(source, 1, 1, ExecutionControl.Never))

        val condition = repair.propose(listOf(BigFraction.ONE, BigFraction.ZERO), booleanArrayOf(true),
            1, 0, BigFraction.ONE, null)

        assertEquals(mapOf(0 to BigFraction.ONE), condition.lower)
        assertEquals(condition.lower, condition.upper)
        assertEquals(mapOf(0 to true), condition.bools)
    }

    @Test
    fun `cancelled connectivity preparation should withhold a partial index`() {
        val source = SourceProofContext(SourceScope(SourceRevision("cancelled", "exact")), emptySet(),
            mapOf("empty" to SourcePremise(SourceInequality(emptyMap(), BigFraction.ZERO))))

        assertNull(JointRepairNeighborhood.prepare(source, 0, 0, ExecutionControl { true }))
    }
}
