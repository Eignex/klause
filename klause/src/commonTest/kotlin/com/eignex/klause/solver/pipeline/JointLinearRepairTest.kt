package com.eignex.klause.solver.pipeline

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.optimization.withLinearAuthority
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.evidence.SourceClaim
import com.eignex.klause.solver.evidence.SourceDerivation
import com.eignex.klause.solver.evidence.SourceEvidence
import com.eignex.klause.solver.evidence.SourceEvidenceCheck
import com.eignex.klause.solver.evidence.SourceEvidenceVerifier
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.numbers.BigFraction
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class JointLinearRepairTest {
    @Test
    fun `advisory center steps should escape three independent pinned groups`() {
        val model = Problem(0,
            intBounds = IntBounds.fromModelBounds(LongArray(3), LongArray(3) { 1 }, null, null),
            factors = Array(3) { id ->
                Linear(intArrayOf(id), doubleArrayOf(1.0), intArrayOf(id), doubleArrayOf(-1.0), LinearOp.EQ, 0.0)
            }, numRealVars = 3, realLower = DoubleArray(3) { 0.5 }, realUpper = DoubleArray(3) { 1.0 })
        val objective = LinearObjective(realCoefficients = DoubleArray(3) { 1.0 })
        val source = assertNotNull(model.linearAuthorityContext(objective, ObjectiveSense.MINIMIZE,
            ExecutionControl.Never))
        for (enabled in listOf(false, true)) {
            val params = TheoryParams(sourceIncumbentCompletion = true, sourceRefutationCenterStep = enabled,
                sourceCuts = false, maxFlips = 6)
            JointLinearRepair(model, source, objective, params).use { repair ->
                repair.search(adaptive = false).use { search ->
                    var done = false
                    var found: Sample? = null
                    val stopOnPoint = ExecutionControl { found != null }
                    repeat(64) {
                        if (!done && found == null) done = search.advance(stopOnPoint, 5_000) { sample, _ ->
                            found = sample
                        }
                    }
                    val result = search.minimizeResult(TerminationReason.BudgetExhausted)
                    val stages = repair.stats.smt.completeSearchStages.orEmpty()

                    assertTrue(done || found != null)
                    assertTrue((stages["jointRepairMaxFreedIntegers"] ?: 0L) <= 2L)
                    if (enabled) {
                        val sample = assertIs<MinimizeResult.WithSample>(result).sample
                        assertEquals(List(3) { 1L }, sample.ints.toList())
                        assertTrue((stages["jointRepairAdvisoryCenterMoves"] ?: 0L) > 0L)
                        assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(source).verify(
                            SourceEvidence(source.scope, SourceClaim.Witness(sample), SourceDerivation.SourceWitness)))
                    } else {
                        assertIs<MinimizeResult.Unknown>(result)
                        assertEquals(0L, stages["jointRepairAdvisoryCenterMoves"] ?: 0L)
                    }
                }
            }
        }
    }

    @Test
    fun `checked pooled centers should not receive advisory center steps`() {
        val model = Problem(0,
            intBounds = IntBounds.fromModelBounds(LongArray(3), LongArray(3) { 1 }, null, null),
            factors = Array(3) { id ->
                Linear(intArrayOf(id), doubleArrayOf(1.0), intArrayOf(id), doubleArrayOf(-1.0), LinearOp.EQ, 0.0)
            }, numRealVars = 3, realLower = DoubleArray(3) { 0.5 }, realUpper = DoubleArray(3) { 1.0 })
        val objective = LinearObjective(realCoefficients = DoubleArray(3) { 1.0 })
        val source = assertNotNull(model.linearAuthorityContext(objective, ObjectiveSense.MINIMIZE,
            ExecutionControl.Never))
        val pooled = Sample(booleanArrayOf(), LongArray(3) { 1 }, DoubleArray(3) { 1.0 },
            exactReals = List(3) { BigFraction.ONE })
        val params = TheoryParams(sourceIncumbentCompletion = true, sourceRefutationCenterStep = true,
            sourceCuts = false, maxFlips = 4)

        JointLinearRepair(model, source, objective, params, external = { pooled }).use { repair ->
            repair.search(adaptive = false).use { search ->
                var done = false
                repeat(64) { if (!done) done = search.advance(ExecutionControl.Never, 5_000) }
                assertEquals(0L, repair.stats.smt.completeSearchStages?.get("jointRepairAdvisoryCenterMoves") ?: 0L)
                assertContentEquals(LongArray(3) { 1 }, pooled.ints)
            }
        }
    }

    @Test
    fun `advisory center moves should retain original publication checking`() {
        val model = Problem(0,
            intBounds = IntBounds.fromModelBounds(LongArray(3), LongArray(3) { 1 }, null, null),
            factors = Array(3) { id ->
                Linear(intArrayOf(id), doubleArrayOf(1.0), intArrayOf(id), doubleArrayOf(-1.0), LinearOp.EQ, 0.0)
            }, numRealVars = 3, realLower = DoubleArray(3) { 0.5 }, realUpper = DoubleArray(3) { 1.0 })
        val objective = LinearObjective(realCoefficients = DoubleArray(3) { 1.0 })
        val source = assertNotNull(model.linearAuthorityContext(objective, ObjectiveSense.MINIMIZE,
            ExecutionControl.Never))
        val params = TheoryParams(sourceIncumbentCompletion = true, sourceRefutationCenterStep = true,
            sourceCuts = false, maxFlips = 6)
        var originalChecks = 0

        JointLinearRepair(model, source, objective, params, incumbentCompletionCheck = { _, _, _ ->
            originalChecks++
            false
        }).use { repair ->
            repair.search(adaptive = false).use { search ->
                var done = false
                repeat(64) { if (!done) done = search.advance(ExecutionControl.Never, 5_000) }

                assertIs<MinimizeResult.Unknown>(search.minimizeResult(TerminationReason.BudgetExhausted))
                assertTrue(originalChecks > 0)
                assertEquals(0L, repair.stats.smt.completeSearchStages?.get("jointRepairCheckedIncumbents") ?: 0L)
            }
        }
    }

    @Test
    fun `ordinary proposals should correlate refutation support and selected roots`() {
        val model = Problem(0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0, 0), longArrayOf(1, 1), null, null),
            factors = Array(2) { id ->
                Linear(intArrayOf(id), doubleArrayOf(1.0), intArrayOf(id), doubleArrayOf(-1.0), LinearOp.EQ, 0.0)
            }, numRealVars = 2, realLower = DoubleArray(2) { 0.5 }, realUpper = DoubleArray(2) { 1.0 })
        val objective = LinearObjective(realCoefficients = DoubleArray(2) { 1.0 })
        val source = assertNotNull(model.linearAuthorityContext(objective, ObjectiveSense.MINIMIZE,
            ExecutionControl.Never))
        val records = mutableListOf<JointRepairObservation>()
        val params = TheoryParams(sourceIncumbentCompletion = true, sourceCuts = false, maxFlips = 4)

        JointLinearRepair(model, source, objective, params, onObservation = records::add).use { repair ->
            repair.search(adaptive = false).use { search ->
                var done = false
                repeat(64) { if (!done) done = search.advance(ExecutionControl.Never, 5_000) }
                assertIs<MinimizeResult.WithSample>(search.minimizeResult(TerminationReason.BudgetExhausted))
            }
        }

        val proposals = records.filter { it.phase == "proposal" }
        assertTrue(proposals.size >= 3)
        assertEquals(2, proposals.first().operator)
        assertEquals(0L, proposals.first().proposalRound)
        assertTrue(proposals.first().freed.isEmpty())
        assertTrue(proposals.any { it.refutationRoots.size == 2 && it.freed.size == 2 })
        assertTrue(records.any {
            it.phase == "feedback" &&
                it.disposition == "jointRepairRefutationGuidance" && it.support.isNotEmpty()
        })
        assertTrue(records.any { it.phase == "result" && it.disposition == "checked-source-witness" })
        for (feedback in records.filter { it.phase == "feedback" && it.support.isNotEmpty() }) {
            assertTrue(records.any {
                it.phase == "query" &&
                    it.proposal == feedback.proposal && it.query == feedback.query
            })
        }
    }

    @Test
    fun `persistent refutation roots should complete independent pin groups within the fixed width`() {
        val model = Problem(0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0, 0), longArrayOf(1, 1), null, null),
            factors = Array(2) { id ->
                Linear(intArrayOf(id), doubleArrayOf(1.0), intArrayOf(id), doubleArrayOf(-1.0), LinearOp.EQ, 0.0)
            }, numRealVars = 2, realLower = DoubleArray(2) { 0.5 }, realUpper = DoubleArray(2) { 1.0 })
        val objective = LinearObjective(realCoefficients = DoubleArray(2) { 1.0 })
        val source = assertNotNull(model.linearAuthorityContext(objective, ObjectiveSense.MINIMIZE,
            ExecutionControl.Never))
        for (completion in listOf(false, true)) {
            val params = TheoryParams(sourceIncumbentCompletion = completion, sourceCuts = false, maxFlips = 4)
            JointLinearRepair(model, source, objective, params).use { repair ->
                repair.search(adaptive = false).use { search ->
                    var done = false
                    repeat(64) { if (!done) done = search.advance(ExecutionControl.Never, 5_000) }

                    val result = search.minimizeResult(TerminationReason.BudgetExhausted)

                    assertTrue(done)
                    val stages = repair.stats.smt.completeSearchStages.orEmpty()
                    assertTrue((stages["jointRepairClosedLeaves"] ?: 0L) >= 2L)
                    if (completion) {
                        val completed = assertIs<MinimizeResult.WithSample>(result)
                        assertEquals(listOf(1L, 1L), completed.sample.ints.toList())
                        assertEquals(2L, stages["jointRepairMaxFreedIntegers"])
                        assertTrue((stages["jointRepairAdditionalRefutationSeeds"] ?: 0L) > 0L)
                        assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(source).verify(
                            SourceEvidence(source.scope, SourceClaim.Witness(completed.sample),
                                SourceDerivation.SourceWitness)))
                    } else {
                        assertIs<MinimizeResult.Unknown>(result)
                        assertEquals(1L, stages["jointRepairMaxFreedIntegers"])
                    }
                }
            }
        }
    }

    @Test
    fun `freeing both independent pin groups should complete an original source point`() {
        val model = Problem(0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0, 0), longArrayOf(1, 1), null, null),
            factors = Array(2) { id ->
                Linear(intArrayOf(id), doubleArrayOf(1.0), intArrayOf(id), doubleArrayOf(-1.0), LinearOp.EQ, 0.0)
            }, numRealVars = 2, realLower = DoubleArray(2) { 0.5 }, realUpper = DoubleArray(2) { 1.0 })
        val objective = LinearObjective(realCoefficients = DoubleArray(2) { 1.0 })
        val source = assertNotNull(model.linearAuthorityContext(objective, ObjectiveSense.MINIMIZE,
            ExecutionControl.Never))
        val condition = ExactNeighborhoodCondition(emptyMap(), emptyMap(), emptyMap())
        JointLinearRepair(model, source, objective, TheoryParams(sourceCuts = false)).use { repair ->
            var result = repair.check(condition, ExecutionControl.Never)
            repeat(64) {
                if (result == ExactNeighborhoodResult.Paused) result = repair.check(condition, ExecutionControl.Never)
            }

            val sample = assertIs<ExactNeighborhoodResult.Witness>(result).sample

            assertEquals(listOf(1L, 1L), sample.ints.toList())
            assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(source).verify(
                SourceEvidence(source.scope, SourceClaim.Witness(sample), SourceDerivation.SourceWitness)))
        }
    }

    @Test
    fun `returning to a completed refuted region should reuse it without another query or proof credit`() {
        val model = Problem(0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(1), null, null),
            factors = arrayOf(Linear(intArrayOf(0), doubleArrayOf(1.0), intArrayOf(0), doubleArrayOf(-1.0),
                LinearOp.EQ, 0.0)), numRealVars = 1,
            realLower = doubleArrayOf(0.5), realUpper = doubleArrayOf(1.0))
        val objective = LinearObjective(intCoefficients = longArrayOf(1))
        val source = assertNotNull(model.linearAuthorityContext(objective, ObjectiveSense.MINIMIZE,
            ExecutionControl.Never))
        val condition = ExactNeighborhoodCondition(emptyMap(), mapOf(0 to BigFraction.ZERO),
            mapOf(0 to BigFraction.ZERO))
        for (completion in listOf(false, true)) {
            val events = mutableListOf<JointRepairComparison>()
            JointLinearRepair(model, source, objective, TheoryParams(sourceIncumbentCompletion = completion),
                onComparison = events::add).use { repair ->
                var result = repair.check(condition, ExecutionControl.Never)
                repeat(64) {
                    if (result == ExactNeighborhoodResult.Paused) result = repair.check(condition,
                        ExecutionControl.Never)
                }
                assertEquals(ExactNeighborhoodResult.Refuted, result)
                val other = condition.copy(lower = mapOf(0 to BigFraction.ZERO, 1 to BigFraction.ONE))
                result = repair.check(other, ExecutionControl.Never)
                repeat(64) {
                    if (result == ExactNeighborhoodResult.Paused) result = repair.check(other, ExecutionControl.Never)
                }
                assertEquals(ExactNeighborhoodResult.Refuted, result)
                val before = repair.stats.smt.sourceLp

                val repeated = repair.check(condition, ExecutionControl.Never)
                val after = repair.stats.smt.sourceLp

                assertEquals(ExactNeighborhoodResult.Refuted, repeated)
                assertEquals(2, events.count { it.phase == "query" && it.disposition == "opened" })
                assertEquals(before.operations, after.operations)
                assertEquals(before.floatWork, after.floatWork)
                assertEquals(before.conflictCertificationWork, after.conflictCertificationWork)
                assertTrue(after.preparationWork > before.preparationWork)
            }
        }
    }

    @Test
    fun `changed Boolean bounds and cutoff conditions should open their own query`() {
        val model = Problem(1,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(1), null, null),
            factors = arrayOf(Linear(intArrayOf(0), doubleArrayOf(1.0), intArrayOf(0), doubleArrayOf(-1.0),
                LinearOp.EQ, 0.0)), numRealVars = 1,
            realLower = doubleArrayOf(0.5), realUpper = doubleArrayOf(1.0))
        val objective = LinearObjective(intCoefficients = longArrayOf(1))
        val source = assertNotNull(model.linearAuthorityContext(objective, ObjectiveSense.MINIMIZE,
            ExecutionControl.Never))
        val condition = ExactNeighborhoodCondition(mapOf(0 to false), mapOf(0 to BigFraction.ZERO),
            mapOf(0 to BigFraction.ZERO))
        val variants = listOf(condition.copy(bools = mapOf(0 to true)),
            condition.copy(lower = mapOf(0 to BigFraction.ONE)),
            condition.copy(upper = mapOf(0 to BigFraction.ONE)), condition.copy(cutoff = BigFraction.ONE))
        for (changed in variants) {
            val events = mutableListOf<JointRepairComparison>()
            JointLinearRepair(model, source, objective, TheoryParams(), onComparison = events::add).use { repair ->
                var result = repair.check(condition, ExecutionControl.Never)
                repeat(64) {
                    if (result == ExactNeighborhoodResult.Paused) result = repair.check(condition,
                        ExecutionControl.Never)
                }
                assertEquals(ExactNeighborhoodResult.Refuted, result)

                result = repair.check(changed, ExecutionControl.Never)
                repeat(64) {
                    if (result == ExactNeighborhoodResult.Paused) result = repair.check(changed, ExecutionControl.Never)
                }

                assertTrue(result != ExactNeighborhoodResult.Paused)
                assertEquals(2, events.count { it.phase == "query" && it.disposition == "opened" })
            }
        }
    }

    @Test
    fun `mutating caller bounds should not turn a later feasible region into a remembered refutation`() {
        val model = Problem(0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(1), null, null),
            factors = arrayOf(Linear(intArrayOf(0), doubleArrayOf(1.0), intArrayOf(0), doubleArrayOf(-1.0),
                LinearOp.EQ, 0.0)), numRealVars = 1,
            realLower = doubleArrayOf(0.5), realUpper = doubleArrayOf(1.0))
        val objective = LinearObjective(intCoefficients = longArrayOf(1))
        val source = assertNotNull(model.linearAuthorityContext(objective, ObjectiveSense.MINIMIZE,
            ExecutionControl.Never))
        val pins = mutableMapOf(0 to BigFraction.ZERO)
        val condition = ExactNeighborhoodCondition(emptyMap(), pins, pins)
        JointLinearRepair(model, source, objective, TheoryParams()).use { repair ->
            var result = repair.check(condition, ExecutionControl.Never)
            repeat(64) {
                if (result == ExactNeighborhoodResult.Paused) result = repair.check(condition, ExecutionControl.Never)
            }
            assertEquals(ExactNeighborhoodResult.Refuted, result)
            pins[0] = BigFraction.ONE

            result = repair.check(condition, ExecutionControl.Never)
            repeat(64) {
                if (result == ExactNeighborhoodResult.Paused) result = repair.check(condition, ExecutionControl.Never)
            }

            val point = assertIs<ExactNeighborhoodResult.Witness>(result).sample
            assertEquals(1L, point.intValue(0))
            assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(source).verify(
                SourceEvidence(source.scope, SourceClaim.Witness(point), SourceDerivation.SourceWitness)))
        }
    }

    @Test
    fun `slice cancellation during reuse should pause before opening another query`() {
        val model = Problem(0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(1), null, null),
            factors = arrayOf(Linear(intArrayOf(0), doubleArrayOf(1.0), intArrayOf(0), doubleArrayOf(-1.0),
                LinearOp.EQ, 0.0)), numRealVars = 1,
            realLower = doubleArrayOf(0.5), realUpper = doubleArrayOf(1.0))
        val objective = LinearObjective(intCoefficients = longArrayOf(1))
        val source = assertNotNull(model.linearAuthorityContext(objective, ObjectiveSense.MINIMIZE,
            ExecutionControl.Never))
        val condition = ExactNeighborhoodCondition(emptyMap(), mapOf(0 to BigFraction.ZERO),
            mapOf(0 to BigFraction.ZERO))
        val events = mutableListOf<JointRepairComparison>()
        JointLinearRepair(model, source, objective, TheoryParams(), onComparison = events::add).use { repair ->
            var result = repair.check(condition, ExecutionControl.Never)
            repeat(64) {
                if (result == ExactNeighborhoodResult.Paused) result = repair.check(condition, ExecutionControl.Never)
            }
            assertEquals(ExactNeighborhoodResult.Refuted, result)
            var polls = 0

            val cancelled = repair.check(condition, ExecutionControl { ++polls >= 3 })
            val resumed = repair.check(condition, ExecutionControl.Never)

            assertEquals(ExactNeighborhoodResult.Paused, cancelled)
            assertEquals(ExactNeighborhoodResult.Refuted, resumed)
            assertEquals(1, events.count { it.phase == "query" && it.disposition == "opened" })
        }
    }

    @Test
    fun `containing cancellation should withhold a remembered regional result`() {
        val model = Problem(0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(1), null, null),
            factors = arrayOf(Linear(intArrayOf(0), doubleArrayOf(1.0), intArrayOf(0), doubleArrayOf(-1.0),
                LinearOp.EQ, 0.0)), numRealVars = 1,
            realLower = doubleArrayOf(0.5), realUpper = doubleArrayOf(1.0))
        val objective = LinearObjective(intCoefficients = longArrayOf(1))
        val source = assertNotNull(model.linearAuthorityContext(objective, ObjectiveSense.MINIMIZE,
            ExecutionControl.Never))
        val condition = ExactNeighborhoodCondition(emptyMap(), mapOf(0 to BigFraction.ZERO),
            mapOf(0 to BigFraction.ZERO))
        var cancelled = false
        val params = TheoryParams(cancellation = ExecutionControl { cancelled })
        JointLinearRepair(model, source, objective, params).use { repair ->
            var result = repair.check(condition, ExecutionControl.Never)
            repeat(64) {
                if (result == ExactNeighborhoodResult.Paused) result = repair.check(condition, ExecutionControl.Never)
            }
            assertEquals(ExactNeighborhoodResult.Refuted, result)
            cancelled = true

            val stopped = repair.attempt(condition, ExecutionControl.Never)

            assertEquals(ExactNeighborhoodResult.Undecided, stopped.result)
            assertEquals(TerminationReason.Cancelled, stopped.reason)
        }
    }

    @Test
    fun `an attempt that saw an external point should not seed regional reuse`() {
        val model = Problem(0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(1), null, null),
            factors = arrayOf(Linear(intArrayOf(0), doubleArrayOf(1.0), intArrayOf(0), doubleArrayOf(-1.0),
                LinearOp.EQ, 0.0)), numRealVars = 1,
            realLower = doubleArrayOf(0.5), realUpper = doubleArrayOf(1.0))
        val objective = LinearObjective(intCoefficients = longArrayOf(1))
        val source = assertNotNull(model.linearAuthorityContext(objective, ObjectiveSense.MINIMIZE,
            ExecutionControl.Never))
        val condition = ExactNeighborhoodCondition(emptyMap(), mapOf(0 to BigFraction.ZERO),
            mapOf(0 to BigFraction.ZERO))
        var offered: Sample? = Sample(BooleanArray(0), longArrayOf(1), doubleArrayOf(1.0),
            exactReals = listOf(BigFraction.ONE))
        val events = mutableListOf<JointRepairComparison>()
        JointLinearRepair(model, source, objective, TheoryParams(sourceIncumbentCompletion = true),
            external = { offered }, onComparison = events::add).use { repair ->
            var result = repair.check(condition, ExecutionControl.Never)
            repeat(64) {
                if (result == ExactNeighborhoodResult.Paused) result = repair.check(condition, ExecutionControl.Never)
            }
            assertEquals(ExactNeighborhoodResult.Refuted, result)
            offered = null

            result = repair.check(condition, ExecutionControl.Never)
            repeat(64) {
                if (result == ExactNeighborhoodResult.Paused) result = repair.check(condition, ExecutionControl.Never)
            }

            assertEquals(ExactNeighborhoodResult.Refuted, result)
            assertEquals(2, events.count { it.phase == "query" && it.disposition == "opened" })
        }
    }

    @Test
    fun `an unavailable original source point should not become a remembered refutation`() {
        val model = Problem(0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(1), null, null),
            factors = emptyArray())
        val objective = LinearObjective(intCoefficients = longArrayOf(1))
        val source = assertNotNull(model.linearAuthorityContext(objective, ObjectiveSense.MINIMIZE,
            ExecutionControl.Never))
        val condition = ExactNeighborhoodCondition(emptyMap(), emptyMap(), emptyMap())
        val events = mutableListOf<JointRepairComparison>()
        JointLinearRepair(model, source, objective, TheoryParams(sourceIncumbentCompletion = true),
            incumbentCompletionCheck = { _, _, _ -> false }, onComparison = events::add).use { repair ->
            repeat(2) {
                var result = repair.check(condition, ExecutionControl.Never)
                repeat(64) {
                    if (result == ExactNeighborhoodResult.Paused) result = repair.check(condition,
                        ExecutionControl.Never)
                }

                assertEquals(ExactNeighborhoodResult.Undecided, result)
            }

            assertEquals(2, events.count { it.phase == "query" && it.disposition == "opened" })
        }
    }

    @Test
    fun `refuted root pins should guide a checked first point within two repair attempts`() {
        val model = Problem(0,
            intBounds = IntBounds.fromModelBounds(LongArray(6), LongArray(6) { 1 }, null, null),
            factors = (List(3) { id ->
                listOf(
                    Linear(intArrayOf(id), doubleArrayOf(1.0), intArrayOf(id), doubleArrayOf(-1.0),
                        LinearOp.LE, 0.5),
                    Linear(intArrayOf(id), doubleArrayOf(-1.0), intArrayOf(id), doubleArrayOf(-1.0),
                        LinearOp.LE, -0.5),
                )
            }.flatten() + List(3) { id ->
                Linear(intArrayOf(1, 1), intArrayOf(3 + id, 3 + (id + 1) % 3), LinearOp.GE, 1)
            }).toTypedArray(), numRealVars = 3,
            realLower = DoubleArray(3), realUpper = DoubleArray(3) { 1.0 })
        val objective = LinearObjective(intCoefficients = longArrayOf(0, 0, 0, 1, 1, 1),
            realCoefficients = DoubleArray(3) { 1.0 })
        val source = assertNotNull(model.linearAuthorityContext(objective, ObjectiveSense.MINIMIZE,
            ExecutionControl.Never))
        for (completion in listOf(false, true)) {
            val params = TheoryParams(sourceIncumbentCompletion = completion, sourceCuts = false, maxFlips = 2)
            JointLinearRepair(model, source, objective, params).use { repair ->
                repair.search(adaptive = false).use { search ->
                    var done = false
                    repeat(64) { if (!done) done = search.advance(ExecutionControl.Never, 5_000) }

                    val result = search.minimizeResult(TerminationReason.BudgetExhausted)

                    assertTrue(done)
                    if (completion) {
                        val completed = assertIs<MinimizeResult.WithSample>(result)
                        assertEquals(3.5, completed.objective)
                        val evidence = SourceEvidence(source.scope, SourceClaim.Witness(completed.sample),
                            SourceDerivation.SourceWitness)
                        assertEquals(SourceEvidenceCheck.Checked,
                            SourceEvidenceVerifier(source.withLinearAuthority()).verify(evidence))
                    } else assertIs<MinimizeResult.Unknown>(result)
                }
            }
        }
    }
}
