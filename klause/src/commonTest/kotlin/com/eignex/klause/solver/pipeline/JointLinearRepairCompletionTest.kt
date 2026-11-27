package com.eignex.klause.solver.pipeline

import com.eignex.klause.RuntimeInitializedTest
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.LinearSourceObjective
import com.eignex.klause.ir.LinearSourceProblem
import com.eignex.klause.ir.LinearSourceTerm
import com.eignex.klause.ir.LinearSourceVariable
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.ProblemSettings
import com.eignex.klause.ir.SemanticRational
import com.eignex.klause.ir.SourceFormat
import com.eignex.klause.ir.SourceSemantics
import com.eignex.klause.ir.SourceTolerance
import com.eignex.klause.lp.SourceLpBudget
import com.eignex.klause.lp.SourceLpFunding
import com.eignex.klause.lp.optimization.withLinearAuthority
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.evidence.SourceClaim
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.solver.evidence.SourceDerivation
import com.eignex.klause.solver.evidence.SourceEvidence
import com.eignex.klause.solver.evidence.SourceEvidenceCheck
import com.eignex.klause.solver.evidence.SourceEvidenceVerifier
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.solver.validation.LinearSourceCheck
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.numbers.BIG_ONE
import com.eignex.util.numbers.BigFraction
import com.eignex.util.numbers.bigIntOf
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JointLinearRepairCompletionTest : RuntimeInitializedTest() {
    @Test
    fun `a newer incompatible point should not displace the checked proposal seed`() {
        val model = Problem(0,
            intBounds = IntBounds.fromModelBounds(LongArray(3), LongArray(3) { 1 }, null, null),
            factors = arrayOf(
                Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.EQ, 0),
                Linear(intArrayOf(1, -1), intArrayOf(1, 2), LinearOp.EQ, 0),
            ), numRealVars = 1, realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(0.0))
        val objective = LinearObjective(intCoefficients = longArrayOf(1, 1, 1))
        val source = assertNotNull(model.linearAuthorityContext(objective, ObjectiveSense.MINIMIZE,
            ExecutionControl.Never))
        var offered = Sample(booleanArrayOf(), longArrayOf(1, 1, 1), doubleArrayOf(0.0),
            exactReals = listOf(BigFraction.ZERO))

        JointLinearRepair(model, source, objective, TheoryParams(sourceIncumbentCompletion = true, maxFlips = 1),
            external = { offered }, incumbentCompletionCheck = { _, _, _ -> true }, onComparison = { event ->
                if (event.phase == "query") offered = offered.copy(ints = longArrayOf(0, 0, 0))
            }).use { repair ->
            repair.search(adaptive = false).use { search ->
                var done = false
                repeat(32) { if (!done) done = search.advance(ExecutionControl.Never, 5_000) }

                val stages = repair.stats.smt.completeSearchStages.orEmpty()
                assertEquals(1L, stages["jointRepairImportedIncumbents"])
                assertEquals(0L, stages["jointRepairCheckedIncumbents"] ?: 0L)
                assertTrue(done)
            }
        }
    }

    @Test
    fun `a dominated rounded bootstrap should leave the first query for incumbent repair`() {
        val model = Problem(0,
            intBounds = IntBounds.fromModelBounds(LongArray(3), LongArray(3) { 1 }, null, null),
            factors = arrayOf(
                Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.EQ, 0),
                Linear(intArrayOf(1, -1), intArrayOf(1, 2), LinearOp.EQ, 0),
            ), numRealVars = 1, realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(0.0))
        val objective = LinearObjective(intCoefficients = longArrayOf(1, 1, 1))
        val source = assertNotNull(model.linearAuthorityContext(objective, ObjectiveSense.MINIMIZE,
            ExecutionControl.Never))
        val guidance = LinearRepairGuidance()
        val budget = SourceLpBudget(funding = SourceLpFunding(100_000, 100_000).also { it.grant(100_000) })
        assertTrue(guidance.remember(source, (0..2).associate {
            SourceCoordinate(SourceCoordinate.Kind.Integer, it) to if (it == 1) 0.9 else 1.0
        }, budget, ExecutionControl.Never))
        val center = Sample(booleanArrayOf(), longArrayOf(1, 1, 1), doubleArrayOf(0.0),
            exactReals = listOf(BigFraction.ZERO))

        JointLinearRepair(model, source, objective,
            TheoryParams(sourceIncumbentCompletion = true, maxFlips = 1), external = { center },
            incumbentCompletionCheck = { _, _, _ -> true }, repairGuidance = guidance).use { repair ->
            repair.search(adaptive = false).use { search ->
                var done = false
                repeat(32) { if (!done) done = search.advance(ExecutionControl.Never, 5_000) }

                val result =
                    assertIs<MinimizeResult.WithSample>(search.minimizeResult(TerminationReason.BudgetExhausted))
                assertEquals(BigFraction.ZERO, objective.evaluateExact(result.sample))
                assertEquals(1L, repair.stats.smt.completeSearchStages?.get("jointRepairCandidates"))
                assertTrue(done)
            }
        }
    }

    @Test
    fun `an improving relaxation query should retain its private search for the next witness`() {
        val groups = 4
        val integers = groups * 4
        val model = Problem(0,
            intBounds = IntBounds.fromModelBounds(LongArray(integers), LongArray(integers) { 1 }, null, null),
            factors = Array(groups) { group ->
                Linear(intArrayOf(2, 3), intArrayOf(group * 2, group * 2 + 1), LinearOp.LE, 3)
            }, numRealVars = 1, realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(0.0))
        val objective = LinearObjective(intCoefficients = LongArray(integers) {
            if (it >= groups * 2) 0 else if (it % 2 == 0) -3 else -4
        })
        val source = assertNotNull(model.linearAuthorityContext(objective, ObjectiveSense.MINIMIZE,
            ExecutionControl.Never))
        val guidance = LinearRepairGuidance()
        val budget = SourceLpBudget(funding = SourceLpFunding(100_000, 100_000).also { it.grant(100_000) })
        assertTrue(guidance.remember(source, (0 until integers).associate { id ->
            SourceCoordinate(SourceCoordinate.Kind.Integer, id) to
                if (id >= groups * 2) 0.0 else if (id % 2 == 0) 0.9 else 1.0 / 3.0
        }, budget, ExecutionControl.Never))
        var center = Sample(booleanArrayOf(), LongArray(integers), doubleArrayOf(0.0),
            exactReals = listOf(BigFraction.ZERO))
        val values = mutableListOf<BigFraction>()

        JointLinearRepair(model, source, objective,
            TheoryParams(sourceIncumbentCompletion = true, sourceCuts = false, maxFlips = 3), external = { center },
            incumbentCompletionCheck = { _, _, _ -> true }, repairGuidance = guidance).use { repair ->
            repair.search(adaptive = false).use { search ->
                var done = false
                repeat(96) {
                    if (!done) done = search.advance(ExecutionControl.Never, 5_000) { sample, value ->
                        center = sample
                        values += assertNotNull(value)
                    }
                }

                assertEquals(3, values.size)
                assertTrue(values.zipWithNext().all { (before, after) -> after < before })
                val stages = repair.stats.smt.completeSearchStages.orEmpty()
                assertEquals(2L, stages["jointRepairCandidates"])
                assertTrue((stages["jointRepairRetainedQueryIncumbents"] ?: 0L) > 0)
                assertFalse(repair.incumbentTrialPending)
                assertTrue(done)
            }
        }
    }

    @Test
    fun `a checked proposal center should seed its query without discovery credit`() {
        val model = Problem(0,
            intBounds = IntBounds.fromModelBounds(LongArray(3), LongArray(3) { 1 }, null, null),
            factors = arrayOf(
                Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.EQ, 0),
                Linear(intArrayOf(1, -1), intArrayOf(1, 2), LinearOp.EQ, 0),
            ), numRealVars = 1, realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(0.0))
        val objective = LinearObjective(intCoefficients = longArrayOf(1, 1, 1))
        val source = assertNotNull(model.linearAuthorityContext(objective, ObjectiveSense.MINIMIZE,
            ExecutionControl.Never))
        val center = Sample(booleanArrayOf(), longArrayOf(1, 1, 1), doubleArrayOf(0.0),
            exactReals = listOf(BigFraction.ZERO))

        JointLinearRepair(model, source, objective, TheoryParams(sourceIncumbentCompletion = true, maxFlips = 1),
            external = { center }, incumbentCompletionCheck = { _, _, _ -> true }).use { repair ->
            repair.search(adaptive = false).use { search ->
                var done = false
                repeat(32) { if (!done) done = search.advance(ExecutionControl.Never, 5_000) }

                assertIs<MinimizeResult.Unknown>(search.minimizeResult(TerminationReason.BudgetExhausted))
                val stages = repair.stats.smt.completeSearchStages.orEmpty()
                assertEquals(1L, stages["jointRepairImportedIncumbents"])
                assertTrue((stages["jointRepairImportedPointWithheld"] ?: 0L) > 0)
                assertEquals(1L, stages["jointRepairCutoffClosures"])
                assertEquals(0L, stages["jointRepairCheckedIncumbents"] ?: 0L)
                assertTrue(done)
            }
        }
    }

    @Test
    fun `a binary repair should change root agreements to reach a coupled improvement`() {
        val model = Problem(0,
            intBounds = IntBounds.fromModelBounds(LongArray(4), LongArray(4) { 1 }, null, null),
            factors = arrayOf(
                Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.EQ, 0),
                Linear(intArrayOf(1, -1), intArrayOf(1, 2), LinearOp.EQ, 0),
            ), numRealVars = 1, realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(1.0))
        val objective = LinearObjective(intCoefficients = longArrayOf(1, 1, 1, 1))
        val source = assertNotNull(model.linearAuthorityContext(objective, ObjectiveSense.MINIMIZE,
            ExecutionControl.Never))
        val guidance = LinearRepairGuidance()
        val budget = SourceLpBudget(funding = SourceLpFunding(100_000, 100_000).also { it.grant(100_000) })
        assertTrue(guidance.remember(source, (0..3).associate {
            SourceCoordinate(SourceCoordinate.Kind.Integer, it) to
                if (it == 3) 0.0 else if (it == 0) 1.0 else if (it == 1) 0.8 else 0.2
        }, budget, ExecutionControl.Never))
        val standing = Sample(booleanArrayOf(), longArrayOf(1, 1, 1, 0), doubleArrayOf(0.0),
            exactReals = listOf(BigFraction.ZERO))

        JointLinearRepair(model, source, objective,
            TheoryParams(sourceIncumbentCompletion = true, maxFlips = 2), external = { standing },
            incumbentCompletionCheck = { _, _, _ -> true }, repairGuidance = guidance).use { repair ->
            repair.search(adaptive = false).use { search ->
                var done = false
                repeat(32) { if (!done) done = search.advance(ExecutionControl.Never, 5_000) }

                val result =
                    assertIs<MinimizeResult.WithSample>(search.minimizeResult(TerminationReason.BudgetExhausted))
                assertEquals(BigFraction.ZERO, objective.evaluateExact(result.sample))
                assertEquals(1L, repair.stats.smt.completeSearchStages?.get("jointRepairBinarySupportNeighborhoods"))
                assertTrue((repair.stats.smt.completeSearchStages?.get("jointRepairImportedIncumbents") ?: 0L) > 0)
                assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(source.withLinearAuthority()).verify(
                    SourceEvidence(source.scope, SourceClaim.Witness(result.sample), SourceDerivation.SourceWitness)))
                assertTrue(done)
            }
        }
    }

    @Test
    fun `a delayed first neighborhood should improve from the newer checked center`() {
        for (sign in listOf(1L, -1L)) {
            val model = Problem(0,
                intBounds = IntBounds.fromModelBounds(LongArray(3), LongArray(3) { 1 }, null, null),
                factors = arrayOf(
                    Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.EQ, 0),
                    Linear(intArrayOf(1, -1), intArrayOf(1, 2), LinearOp.EQ, 0),
                ), numRealVars = 1, realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(1.0))
            val objective = LinearObjective(intCoefficients = LongArray(3) { sign },
                realCoefficients = doubleArrayOf(sign.toDouble()))
            val source = assertNotNull(model.linearAuthorityContext(
                objective, ObjectiveSense.MINIMIZE, ExecutionControl.Never))
            val oldValue = if (sign > 0) 1L else 0L
            var offered = Sample(BooleanArray(0), LongArray(3) { oldValue }, doubleArrayOf(oldValue.toDouble()),
                exactReals = listOf(BigFraction.ofLong(oldValue)))
            var offeredVersion = 1
            var readVersion = 0
            var importedVersion = 0
            val imports = mutableListOf<Int>()
            val uses = mutableListOf<Int>()
            val events = mutableListOf<JointRepairComparison>()

            JointLinearRepair(model, source, objective,
                TheoryParams(sourceIncumbentCompletion = true, maxFlips = 1), external = {
                    readVersion = offeredVersion
                    offered
                }, onIncumbentImport = {
                    importedVersion = readVersion
                    imports += importedVersion
                },
                onIncumbentUse = { uses += importedVersion },
                incumbentCompletionCheck = { _, _, _ -> true }, onComparison = { events += it }).use { repair ->
                repair.search(adaptive = false).use { search ->
                    search.advance(ExecutionControl.Never, 1)
                    assertEquals(BigFraction.ofLong(4 * sign * oldValue),
                        events.first { it.phase == "center" }.completedValue)
                    offered = Sample(BooleanArray(0), LongArray(3) { 1 - oldValue },
                        doubleArrayOf(oldValue.toDouble()), exactReals = listOf(BigFraction.ofLong(oldValue)))
                    offeredVersion = 2
                    var done = false
                    repeat(32) { if (!done) done = search.advance(ExecutionControl.Never, 5_000) }

                    assertEquals(listOf(BigFraction.ofLong(sign * (3 - 2 * oldValue))),
                        events.filter { it.phase == "query" }.map { it.cutoff })
                    val result = assertIs<MinimizeResult.WithSample>(
                        search.minimizeResult(TerminationReason.BudgetExhausted))
                    assertEquals(BigFraction.ofLong(if (sign > 0) 0 else -4), objective.evaluateExact(result.sample))
                    val evidence = SourceEvidence(source.scope, SourceClaim.Witness(result.sample),
                        SourceDerivation.SourceWitness)
                    assertEquals(SourceEvidenceCheck.Checked,
                        SourceEvidenceVerifier(source.withLinearAuthority()).verify(evidence))
                    assertEquals(listOf(1, 2), imports)
                    assertEquals(listOf(2), uses)
                    assertEquals(1L, repair.stats.smt.completeSearchStages?.get("jointRepairCheckedCenterRefreshes"))
                    assertEquals(0L, repair.stats.smt.completeSearchStages?.get("jointRepairCutoffClosures") ?: 0L)
                    assertTrue(done)
                }
            }
        }
    }

    @Test
    fun `a fresh point with unchanged discrete coordinates should preserve the neighborhood pins`() {
        for (sign in listOf(1L, -1L)) {
            val model = Problem(0,
                intBounds = IntBounds.fromModelBounds(LongArray(3), LongArray(3) { 1 }, null, null),
                factors = arrayOf(
                    Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.EQ, 0),
                    Linear(intArrayOf(1, -1), intArrayOf(1, 2), LinearOp.EQ, 0),
                ), numRealVars = 1, realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(1.0))
            val objective = LinearObjective(intCoefficients = LongArray(3) { sign },
                realCoefficients = doubleArrayOf(sign.toDouble()))
            val source = assertNotNull(model.linearAuthorityContext(
                objective, ObjectiveSense.MINIMIZE, ExecutionControl.Never))
            val oldValue = if (sign > 0) 1L else 0L
            var offered = Sample(BooleanArray(0), LongArray(3) { oldValue }, doubleArrayOf(oldValue.toDouble()),
                exactReals = listOf(BigFraction.ofLong(oldValue)))
            val events = mutableListOf<JointRepairComparison>()
            JointLinearRepair(model, source, objective,
                TheoryParams(sourceIncumbentCompletion = true, maxFlips = 1), external = { offered },
                incumbentCompletionCheck = { _, _, _ -> true }, onComparison = { events += it }).use { repair ->
                repair.search(adaptive = false).use { search ->
                    search.advance(ExecutionControl.Never, 1)
                    offered = offered.copy(reals = doubleArrayOf((1 - oldValue).toDouble()),
                        exactReals = listOf(BigFraction.ofLong(1 - oldValue)))
                    var done = false
                    repeat(32) { if (!done) done = search.advance(ExecutionControl.Never, 5_000) }

                    assertIs<MinimizeResult.Unknown>(search.minimizeResult(TerminationReason.BudgetExhausted))
                    assertEquals(listOf(BigFraction.ofLong(sign * (1 + 2 * oldValue))),
                        events.filter { it.phase == "query" }.map { it.cutoff })
                    assertEquals(1L, repair.stats.smt.completeSearchStages?.get("jointRepairCheckedCenterRefreshes"))
                    assertEquals(1L, repair.stats.smt.completeSearchStages?.get("jointRepairCutoffClosures"))
                    assertTrue(done)
                }
            }
        }
    }

    @Test
    fun `expired checking should withhold a refreshed center before its query opens`() {
        for (limit in listOf("cancellation", "funding")) {
            val model = Problem(0,
                intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(2), null, null),
                factors = emptyArray(), numRealVars = 1,
                realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(1.0))
            val objective = LinearObjective(intCoefficients = longArrayOf(1), realCoefficients = doubleArrayOf(1.0))
            val source = assertNotNull(model.linearAuthorityContext(
                objective, ObjectiveSense.MINIMIZE, ExecutionControl.Never))
            val prepared = Sample(BooleanArray(0), longArrayOf(2), doubleArrayOf(1.0),
                exactReals = listOf(BigFraction.ONE))
            var offered = prepared
            var cancelled = false
            var originalChecked = false
            val events = mutableListOf<JointRepairComparison>()
            val params = TheoryParams(sourceIncumbentCompletion = true, sourceLpWorkLimit = 1_000_000,
                maxFlips = 1, cancellation = ExecutionControl { cancelled })
            JointLinearRepair(model, source, objective, params, external = { offered },
                incumbentCompletionCheck = { sample, _, token ->
                    if (sample !== prepared) {
                        originalChecked = true
                        token.charge(if (limit == "funding") params.sourceLpWorkLimit else 1L)
                        cancelled = limit == "cancellation"
                    }
                    true
                }, onComparison = { events += it }).use { repair ->
                repair.search(adaptive = false).use { search ->
                    search.advance(ExecutionControl.Never, 1)
                    offered = offered.copy(ints = longArrayOf(0))
                    var done = false
                    repeat(32) { if (!done) done = search.advance(ExecutionControl.Never, 5_000) }

                    assertTrue(originalChecked)
                    assertTrue(events.none { it.phase == "query" })
                    assertEquals(0L,
                        repair.stats.smt.completeSearchStages?.get("jointRepairCheckedCenterRefreshes") ?: 0L)
                    assertIs<MinimizeResult.Unknown>(search.minimizeResult(TerminationReason.BudgetExhausted))
                }
            }
        }
    }

    @Test
    fun `unqualified fresh points should preserve the prepared cutoff without advisory completion`() {
        val model = Problem(0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(2), null, null),
            factors = emptyArray(), numRealVars = 1,
            realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(1.0))
        val objective = LinearObjective(intCoefficients = longArrayOf(1), realCoefficients = doubleArrayOf(1.0))
        val source = assertNotNull(model.linearAuthorityContext(objective, ObjectiveSense.MINIMIZE,
            ExecutionControl.Never))
        for ((exact, real, originalAccepted) in listOf(Triple(false, 1L, true), Triple(true, 2L, true),
            Triple(true, 1L, false))) {
            val prepared = Sample(BooleanArray(0), longArrayOf(2), doubleArrayOf(1.0),
                exactReals = listOf(BigFraction.ONE))
            var offered = prepared
            var halted = false
            val events = mutableListOf<JointRepairComparison>()
            val params = TheoryParams(sourceIncumbentCompletion = true, maxFlips = 1,
                cancellation = ExecutionControl { halted })
            JointLinearRepair(model, source, objective, params, external = { offered },
                incumbentCompletionCheck = { sample, _, _ -> sample === prepared || originalAccepted }, onComparison = {
                    events += it
                    if (it.phase == "query") halted = true
                }).use { repair ->
                repair.search(adaptive = false).use { search ->
                    search.advance(ExecutionControl.Never, 1)
                    assertEquals(BigFraction.ofLong(3), events.first { it.phase == "center" }.completedValue)
                    offered = Sample(BooleanArray(0), longArrayOf(0), doubleArrayOf(real.toDouble()),
                        exactReals = if (exact) listOf(BigFraction.ofLong(real)) else null)
                    repeat(32) { if (!halted) search.advance(ExecutionControl.Never, 5_000) }

                    assertEquals(listOf(BigFraction.ofLong(3)),
                        events.filter { it.phase == "query" }.map { it.cutoff })
                    assertEquals(0L,
                        repair.stats.smt.completeSearchStages?.get("jointRepairExternalCompletionAttempts") ?: 0L)
                    assertEquals(0L,
                        repair.stats.smt.completeSearchStages?.get("jointRepairCheckedCenterRefreshes") ?: 0L)
                }
            }
        }
    }

    @Test
    fun `completion comparisons should distinguish advisory and exact real objectives`() {
        val model = Problem(0, intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(1), null, null),
            factors = arrayOf(Linear(intArrayOf(0), doubleArrayOf(-1.0), intArrayOf(0), doubleArrayOf(3.0),
                LinearOp.EQ, 0.0)), numRealVars = 1,
            realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(1.0))
        val objective = LinearObjective(realCoefficients = doubleArrayOf(1.0))
        val source = assertNotNull(model.linearAuthorityContext(objective, ObjectiveSense.MINIMIZE,
            ExecutionControl.Never))
        val offered = Sample(BooleanArray(0), longArrayOf(1), doubleArrayOf(0.333))
        val events = mutableListOf<JointRepairComparison>()

        JointLinearRepair(model, source, objective,
            TheoryParams(sourceIncumbentCompletion = true, maxFlips = 1), external = { offered },
            onComparison = { events += it }).use { repair ->
            repair.search(adaptive = false).use { search ->
                var done = false
                repeat(32) { if (!done) done = search.advance(ExecutionControl.Never, 5_000) }
            }
        }

        val completed = events.first { it.phase == "external" && it.completedValue != null }
        assertEquals(BigFraction.ofDouble(0.333), completed.rawValue)
        assertEquals(BigFraction.ofLong(3).reciprocal(), completed.completedValue)
        assertEquals(0L, completed.condition)
    }

    @Test
    fun `comparison query identity should survive a cancelled slice before checked discovery`() {
        val model = Problem(0, 0, emptyArray(), emptyArray(), numRealVars = 1,
            realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(1.0))
        val objective = LinearObjective(realCoefficients = doubleArrayOf(-1.0))
        val source = assertNotNull(model.linearAuthorityContext(objective, ObjectiveSense.MINIMIZE,
            ExecutionControl.Never))
        val condition = ExactNeighborhoodCondition(emptyMap(), emptyMap(), emptyMap(), BigFraction.ZERO)

        for (accepted in listOf(false, true)) {
            val events = mutableListOf<JointRepairComparison>()
            JointLinearRepair(model, source, objective, TheoryParams(sourceIncumbentCompletion = true),
                incumbentCompletionCheck = { _, _, _ -> accepted }, onComparison = { events += it }).use { repair ->
                assertEquals(ExactNeighborhoodResult.Paused, repair.check(condition, ExecutionControl { true }))
                assertTrue(events.isEmpty())
                var result = repair.check(condition, ExecutionControl.Never)
                repeat(32) {
                    if (result == ExactNeighborhoodResult.Paused) result = repair.check(condition,
                        ExecutionControl.Never)
                }

                assertEquals(listOf(1L), events.filter { it.phase == "query" }.map { it.condition })
                val discoveries = events.filter { it.phase == "discovery" }
                if (accepted) {
                    assertIs<ExactNeighborhoodResult.Witness>(result)
                    assertEquals(listOf(BigFraction.MINUS_ONE), discoveries.map { it.candidateValue })
                    assertEquals(listOf(BigFraction.ZERO), discoveries.map { it.cutoff })
                    assertEquals(listOf(1L), discoveries.map { it.condition })
                } else assertTrue(discoveries.isEmpty())
            }
        }
    }

    @Test
    fun `disabled source cuts should preserve the uncut root bootstrap`() {
        val model = Problem(0, intBounds = IntBounds.fromModelBounds(longArrayOf(0, 0), longArrayOf(2, 2), null, null),
            factors = arrayOf(Linear(intArrayOf(2, 2), intArrayOf(0, 1), LinearOp.GE, 1)))
        val objective = LinearObjective(intCoefficients = longArrayOf(1, 1))
        val source = assertNotNull(model.linearAuthorityContext(objective, ObjectiveSense.MINIMIZE,
            ExecutionControl.Never))
        for (enabled in listOf(false, true)) {
            val params = TheoryParams(sourceCuts = enabled, maxFlips = 1)
            JointLinearRepair(model, source, objective, params, boundOrdered = true).use { repair ->
                repair.search(adaptive = false).use { search ->
                    var done = false
                    repeat(64) { if (!done) done = search.advance(ExecutionControl.Never, 1) }

                    val result = search.minimizeResult(TerminationReason.BudgetExhausted)

                    assertTrue(done)
                    if (enabled) {
                        val completed = assertIs<MinimizeResult.WithSample>(result)
                        assertEquals(1.0, completed.objective)
                        val point = completed.sample
                        val proof = SourceEvidence(source.scope, SourceClaim.Witness(point),
                            SourceDerivation.SourceWitness)
                        assertEquals(SourceEvidenceCheck.Checked,
                            SourceEvidenceVerifier(source.withLinearAuthority()).verify(proof))
                    } else assertTrue(result !is MinimizeResult.WithSample)
                }
            }
        }
    }

    @Test
    fun `source aggregation should guide the mixed root bootstrap only when enabled`() {
        val model = Problem(0, intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(2), null, null),
            factors = arrayOf(
                Linear(intArrayOf(0), doubleArrayOf(1.0), intArrayOf(0), doubleArrayOf(1.0), LinearOp.GE, 1.0),
                Linear(intArrayOf(0), doubleArrayOf(1.0), intArrayOf(0), doubleArrayOf(-1.0), LinearOp.GE, 0.0),
            ), numRealVars = 1, realLower = doubleArrayOf(Double.NEGATIVE_INFINITY),
            realUpper = doubleArrayOf(Double.POSITIVE_INFINITY))
        val objective = LinearObjective(intCoefficients = longArrayOf(1))
        val source = assertNotNull(model.linearAuthorityContext(objective, ObjectiveSense.MINIMIZE,
            ExecutionControl.Never))
        for (enabled in listOf(false, true)) {
            val params = TheoryParams(sourceAggregateCuts = enabled, sourcePointCuts = true, maxFlips = 1)
            JointLinearRepair(model, source, objective, params, boundOrdered = true).use { repair ->
                repair.search(adaptive = false).use { search ->
                    var done = false
                    repeat(64) { if (!done) done = search.advance(ExecutionControl.Never, 1) }

                    val result = search.minimizeResult(TerminationReason.BudgetExhausted)

                    assertTrue(done)
                    if (enabled) {
                        val completed = assertIs<MinimizeResult.WithSample>(result)
                        assertEquals(1.0, completed.objective)
                        val point = completed.sample
                        val proof = SourceEvidence(source.scope, SourceClaim.Witness(point),
                            SourceDerivation.SourceWitness)
                        assertEquals(SourceEvidenceCheck.Checked,
                            SourceEvidenceVerifier(source.withLinearAuthority()).verify(proof))
                    } else assertTrue(result !is MinimizeResult.WithSample)
                }
            }
        }
    }

    @Test
    fun `borrowed fractional guidance should open a connected first region`() {
        val model = Problem(0, intBounds = IntBounds.fromModelBounds(longArrayOf(0, 0), longArrayOf(2, 2), null, null),
            factors = arrayOf(Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1)))
        val objective = LinearObjective(intCoefficients = longArrayOf(1, 1))
        val source = assertNotNull(model.linearAuthorityContext(objective, ObjectiveSense.MINIMIZE,
            ExecutionControl.Never))
        val funding = SourceLpFunding(10_000, 10_000).also { it.grant(10_000) }
        val point = (0..1).associate { SourceCoordinate(SourceCoordinate.Kind.Integer, it) to 0.5 }
        val guidance = LinearRepairGuidance()
        assertTrue(guidance.remember(source, point, SourceLpBudget(funding = funding), ExecutionControl.Never))
        val observations = mutableListOf<JointRepairObservation>()
        val repair = JointLinearRepair(model, source, objective, TheoryParams(maxFlips = 1), boundOrdered = true,
            repairGuidance = guidance, onObservation = observations::add)

        repair.search(adaptive = false).use { search ->
            search.advance(ExecutionControl.Never, 5_000)

            val first = observations.first { it.phase == "proposal" }
            assertEquals(listOf(0, 1), first.freed)
            assertTrue(first.lower.isEmpty())
        }
    }

    @Test
    fun `matching paid root coordinates should remove the joint root query`() {
        val model = Problem(0, intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(2), null, null),
            factors = arrayOf(Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 1)))
        val objective = LinearObjective(intCoefficients = longArrayOf(1))
        val source = assertNotNull(model.linearAuthorityContext(objective, ObjectiveSense.MINIMIZE,
            ExecutionControl.Never))
        val funding = SourceLpFunding(10_000, 10_000).also { it.grant(10_000) }
        val guidance = LinearRepairGuidance()
        assertTrue(guidance.remember(source, mapOf(SourceCoordinate(SourceCoordinate.Kind.Integer, 0) to 1.0),
            SourceLpBudget(funding = funding), ExecutionControl.Never))
        val repair = JointLinearRepair(model, source, objective, TheoryParams(), boundOrdered = true,
            repairGuidance = guidance)

        repair.search(adaptive = false).use { search ->
            search.advance(ExecutionControl.Never, 1)

            val stages = assertNotNull(repair.stats.smt.completeSearchStages)
            assertEquals(1L, stages["jointRepairSharedRootGuidance"])
            assertEquals(0L, stages["jointRepairRootQueries"] ?: 0L)
        }
    }

    @Test
    fun `root guidance should spend no work on an uncut proof baseline`() {
        val model = Problem(0, intBounds = IntBounds.fromModelBounds(longArrayOf(0, 0), longArrayOf(2, 2), null, null),
            factors = arrayOf(Linear(intArrayOf(2, 2), intArrayOf(0, 1), LinearOp.GE, 1)))
        val objective = LinearObjective(intCoefficients = longArrayOf(1, 1))
        val source = assertNotNull(model.linearAuthorityContext(objective, ObjectiveSense.MINIMIZE,
            ExecutionControl.Never))
        val params = TheoryParams(sourceAggregateCuts = true, sourcePointCuts = true)
        val repair = JointLinearRepair(model, source, objective, params, boundOrdered = true)

        repair.search(adaptive = false).use { search ->
            search.advance(ExecutionControl.Never, 1)

            val stages = assertNotNull(repair.stats.smt.completeSearchStages)
            assertEquals(1L, stages["jointRepairRootQueries"])
            assertEquals(0L, stages["jointRepairCutBaselineQueries"] ?: 0L)
        }
    }

    @Test
    fun `joint cut statistics should include root preparation and final owner retirement`() {
        val model = Problem(0, intBounds = IntBounds.fromModelBounds(longArrayOf(0, 0), longArrayOf(2, 2), null, null),
            factors = arrayOf(Linear(intArrayOf(2, 2), intArrayOf(0, 1), LinearOp.GE, 1)))
        val objective = LinearObjective(intCoefficients = longArrayOf(1, 1))
        val source = assertNotNull(model.linearAuthorityContext(objective, ObjectiveSense.MINIMIZE,
            ExecutionControl.Never))
        val repair = JointLinearRepair(model, source, objective, TheoryParams(), boundOrdered = true)
        val root = repair.search(adaptive = false).use { search ->
            search.advance(ExecutionControl.Never, 1)

            val stages = assertNotNull(repair.stats.smt.completeSearchStages)
            assertEquals(1L, stages["jointRepairRootQueries"])
            assertEquals(0L, stages["jointRepairCandidates"] ?: 0L)
            assertTrue(stages.getValue("jointRepairCutPreparationWork") > 0L)
            stages
        }

        val retired = assertNotNull(repair.stats.smt.completeSearchStages)
        assertEquals(root["jointRepairCutPreparationWork"], retired["jointRepairCutPreparationWork"])
        assertEquals(1L, retired["jointRepairCutLifecycleSOURCE_ROUNDING_OwnerClosed"])
    }

    @Test
    fun `retired repair queries should not multiply shared aggregate eligibility`() {
        val model = Problem(0, intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(2), null, null),
            factors = arrayOf(
                Linear(intArrayOf(0), doubleArrayOf(1.0), intArrayOf(0), doubleArrayOf(1.0), LinearOp.GE, 1.0),
                Linear(intArrayOf(0), doubleArrayOf(1.0), intArrayOf(0), doubleArrayOf(-1.0), LinearOp.GE, 0.0),
            ), numRealVars = 1, realLower = doubleArrayOf(Double.NEGATIVE_INFINITY),
            realUpper = doubleArrayOf(Double.POSITIVE_INFINITY))
        val objective = LinearObjective(intCoefficients = longArrayOf(1))
        val source = assertNotNull(model.linearAuthorityContext(objective, ObjectiveSense.MINIMIZE,
            ExecutionControl.Never))
        val condition = ExactNeighborhoodCondition(emptyMap(), emptyMap(), emptyMap())
        JointLinearRepair(model, source, objective, TheoryParams(sourceAggregateCuts = true),
            boundOrdered = true).use { repair ->
            var first: Map<String, Long>? = null
            repeat(3) { index ->
                var result = repair.check(condition, ExecutionControl.Never)
                repeat(32) {
                    if (result == ExactNeighborhoodResult.Paused) result = repair.check(condition,
                        ExecutionControl.Never)
                }

                assertIs<ExactNeighborhoodResult.Witness>(result)
                val stages = assertNotNull(repair.stats.smt.completeSearchStages)
                assertEquals(index.toLong() + 1L, stages["jointRepairCandidates"])
                assertNotNull(stages["jointRepairCutBoundsWithoutIncumbent"])
                val previous = first
                if (previous == null) {
                    assertTrue(stages.getValue("jointRepairCutAggregatePairAttempts") > 0L)
                    first = stages
                } else {
                    assertEquals(previous["jointRepairCutAggregatePairAttempts"],
                        stages["jointRepairCutAggregatePairAttempts"])
                    assertEquals(previous["jointRepairMaxRetainedCuts"], stages["jointRepairMaxRetainedCuts"])
                }
            }
        }
    }

    @Test
    fun `adaptive checked repair should complete an enlarged coupled group`() {
        val size = 3
        val model = Problem(0,
            intBounds = IntBounds.fromModelBounds(LongArray(size), LongArray(size) { 1 }, null, null),
            factors = Array(size - 1) { id ->
                Linear(intArrayOf(1, -1), intArrayOf(id, id + 1), LinearOp.EQ, 0)
            })
        val objective = LinearObjective(intCoefficients = LongArray(size) { 1 })
        val source = assertNotNull(model.linearAuthorityContext(
            objective, ObjectiveSense.MINIMIZE, ExecutionControl.Never))
        val seed = Sample(BooleanArray(0), LongArray(size) { 1 })
        val params = TheoryParams(sourceIncumbentCompletion = true, maxFlips = 3)

        for (adaptive in listOf(false, true)) {
            JointLinearRepair(model, source, objective, params, external = { seed }).use { repair ->
                repair.search(adaptive = adaptive).use { search ->
                    repeat(128) {
                        if (repair.stats.smt.completeSearchStages?.get("jointRepairCutoffClosures") != 1L) {
                            search.advance(ExecutionControl.Never, 1)
                        }
                    }
                    assertEquals(adaptive, repair.incumbentTrialPending)
                    var done = false
                    repeat(128) { if (!done) done = search.advance(ExecutionControl.Never, 1) }

                    val result = search.minimizeResult(TerminationReason.BudgetExhausted)
                    if (adaptive) {
                        val point = assertIs<MinimizeResult.WithSample>(result).sample
                        assertContentEquals(LongArray(size), LongArray(size) { point.intValue(it) })
                        val evidence = SourceEvidence(source.scope, SourceClaim.Witness(point),
                            SourceDerivation.SourceWitness)
                        val checked = SourceEvidenceVerifier(source.withLinearAuthority()).verify(evidence)
                        val stages = repair.stats.smt.completeSearchStages.orEmpty()
                        assertEquals(SourceEvidenceCheck.Checked, checked)
                        assertEquals(1L, stages["jointRepairEnlargedTrialCompletions"])
                        assertEquals(1L, stages["jointRepairLargerNeighborhoodCompletions"])
                        assertEquals(1L, stages["jointRepairLargerNeighborhoodCheckedIncumbents"])
                    } else assertTrue(result !is MinimizeResult.WithSample)
                    assertFalse(repair.incumbentTrialPending)
                }
            }
        }
    }

    @Test
    fun `adaptive checked repair should continue after a larger coupled group is refuted`() {
        val size = 7
        val model = Problem(0,
            intBounds = IntBounds.fromModelBounds(LongArray(size), LongArray(size) { 1 }, null, null),
            factors = Array(size - 1) { id ->
                Linear(intArrayOf(1, -1), intArrayOf(id, id + 1), LinearOp.EQ, 0)
            })
        val objective = LinearObjective(intCoefficients = LongArray(size) { 1 })
        val source = assertNotNull(model.linearAuthorityContext(
            objective, ObjectiveSense.MINIMIZE, ExecutionControl.Never))
        val seed = Sample(BooleanArray(0), LongArray(size) { 1 })
        val params = TheoryParams(sourceIncumbentCompletion = true, maxFlips = 3)

        for (adaptive in listOf(false, true)) {
            JointLinearRepair(model, source, objective, params, external = { seed }).use { repair ->
                repair.search(adaptive = adaptive).use { search ->
                    repeat(128) {
                        if (repair.stats.smt.completeSearchStages?.get("jointRepairCutoffClosures") != 1L) {
                            search.advance(ExecutionControl.Never, 1)
                        }
                    }
                    assertEquals(adaptive, repair.incumbentTrialPending)
                    var done = false
                    repeat(128) { if (!done) done = search.advance(ExecutionControl.Never, 1) }

                    val result = search.minimizeResult(TerminationReason.BudgetExhausted)
                    if (adaptive) {
                        val point = assertIs<MinimizeResult.WithSample>(result).sample
                        assertContentEquals(LongArray(size), LongArray(size) { point.intValue(it) })
                        val evidence = SourceEvidence(source.scope, SourceClaim.Witness(point),
                            SourceDerivation.SourceWitness)
                        val checked = SourceEvidenceVerifier(source.withLinearAuthority()).verify(evidence)
                        val stages = repair.stats.smt.completeSearchStages.orEmpty()
                        assertEquals(SourceEvidenceCheck.Checked, checked)
                        assertEquals(1L, stages["jointRepairEnlargedTrialCompletions"])
                        assertEquals(2L, stages["jointRepairLargerNeighborhoodCompletions"])
                        assertEquals(1L, stages["jointRepairLargerNeighborhoodCheckedIncumbents"])
                    } else assertTrue(result !is MinimizeResult.WithSample)
                    assertFalse(repair.incumbentTrialPending)
                }
            }
        }
    }

    @Test
    fun `adaptive checked repair should widen after continuous progress leaves integers unchanged`() {
        val model = Problem(0,
            intBounds = IntBounds.fromModelBounds(LongArray(3), LongArray(3) { 1 }, null, null),
            factors = arrayOf(
                Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.EQ, 0),
                Linear(intArrayOf(1, -1), intArrayOf(1, 2), LinearOp.EQ, 0),
            ), numRealVars = 1, realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(1.0))
        val objective = LinearObjective(intCoefficients = longArrayOf(1, 1, 1), realCoefficients = doubleArrayOf(1.0))
        val source = assertNotNull(model.linearAuthorityContext(objective, ObjectiveSense.MINIMIZE,
            ExecutionControl.Never))
        val seed = Sample(BooleanArray(0), LongArray(3) { 1 }, doubleArrayOf(1.0),
            exactReals = listOf(BigFraction.ONE))
        val params = TheoryParams(sourceIncumbentCompletion = true, maxFlips = 2)

        for (adaptive in listOf(false, true)) {
            JointLinearRepair(model, source, objective, params, external = { seed },
                sourceIntegers = intArrayOf(0, 1, 2)).use { repair ->
                repair.search(adaptive = adaptive).use { search ->
                    val points = ArrayList<Sample>()
                    repeat(128) {
                        if (points.isEmpty()) search.advance(ExecutionControl.Never, 1) { sample, _, ->
                            points += sample
                        }
                    }
                    val first = points.single()
                    assertContentEquals(LongArray(3) { 1 }, LongArray(3) { first.intValue(it) })
                    assertEquals(listOf(BigFraction.ZERO), first.exactReals)
                    assertEquals(adaptive, repair.incumbentTrialPending)
                    var done = false
                    repeat(128) { if (!done) done = search.advance(ExecutionControl.Never, 1) }

                    val result = assertIs<MinimizeResult.WithSample>(
                        search.minimizeResult(TerminationReason.BudgetExhausted))
                    val expected = if (adaptive) 0L else 1L
                    assertContentEquals(LongArray(3) { expected }, LongArray(3) { result.sample.intValue(it) })
                    val evidence = SourceEvidence(source.scope, SourceClaim.Witness(result.sample),
                        SourceDerivation.SourceWitness)
                    assertEquals(SourceEvidenceCheck.Checked,
                        SourceEvidenceVerifier(source.withLinearAuthority()).verify(evidence))
                    assertTrue(done)
                    assertFalse(repair.incumbentTrialPending)
                }
            }
        }
    }

    @Test
    fun `integer progress should end the small checked repair trial`() {
        val model = Problem(0,
            intBounds = IntBounds.fromModelBounds(LongArray(3), LongArray(3) { 1 }, null, null),
            factors = arrayOf(Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.EQ, 0)))
        val objective = LinearObjective(intCoefficients = longArrayOf(1, 1, 1))
        val source = assertNotNull(model.linearAuthorityContext(objective, ObjectiveSense.MINIMIZE,
            ExecutionControl.Never))
        val seed = Sample(BooleanArray(0), LongArray(3) { 1 })
        val params = TheoryParams(sourceIncumbentCompletion = true, maxFlips = 1)
        JointLinearRepair(model, source, objective, params, external = { seed }).use { repair ->
            repair.search(adaptive = true).use { search ->
                var done = false
                repeat(128) { if (!done) done = search.advance(ExecutionControl.Never, 1) }

                val result = assertIs<MinimizeResult.WithSample>(
                    search.minimizeResult(TerminationReason.BudgetExhausted))
                assertContentEquals(longArrayOf(0, 0, 1), LongArray(3) { result.sample.intValue(it) })
                assertFalse(repair.incumbentTrialPending)
            }
        }
    }

    @Test
    fun `containing cancellation should stop widening after continuous progress`() {
        val model = Problem(0,
            intBounds = IntBounds.fromModelBounds(LongArray(3), LongArray(3) { 1 }, null, null),
            factors = arrayOf(
                Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.EQ, 0),
                Linear(intArrayOf(1, -1), intArrayOf(1, 2), LinearOp.EQ, 0),
            ), numRealVars = 1, realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(1.0))
        val objective = LinearObjective(intCoefficients = longArrayOf(1, 1, 1), realCoefficients = doubleArrayOf(1.0))
        val source = assertNotNull(model.linearAuthorityContext(objective, ObjectiveSense.MINIMIZE,
            ExecutionControl.Never))
        var cancelled = false
        val params = TheoryParams(sourceIncumbentCompletion = true, cancellation = ExecutionControl { cancelled })
        val seed = Sample(BooleanArray(0), LongArray(3) { 1 }, doubleArrayOf(1.0),
            exactReals = listOf(BigFraction.ONE))
        JointLinearRepair(model, source, objective, params, external = { seed }).use { repair ->
            repair.search(adaptive = true).use { search ->
                repeat(128) {
                    if (!cancelled) search.advance(ExecutionControl.Never, 1) { _, _ -> cancelled = true }
                }
                search.advance(ExecutionControl.Never, 1)

                assertTrue(cancelled)
                assertEquals(1L, repair.stats.smt.completeSearchStages?.get("jointRepairCandidates"))
                assertNull(repair.stats.smt.completeSearchStages?.get("jointRepairEnlargedTrialStarts"))
            }
        }
    }

    @Test
    fun `containing cancellation should stop an enlarged checked trial before opening its query`() {
        val model = Problem(0, intBounds = IntBounds.fromModelBounds(LongArray(3), LongArray(3) { 1 }, null, null),
            factors = arrayOf(
                Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.EQ, 0),
                Linear(intArrayOf(1, -1), intArrayOf(1, 2), LinearOp.EQ, 0),
            ))
        val objective = LinearObjective(intCoefficients = longArrayOf(1, 1, 1))
        val source = assertNotNull(model.linearAuthorityContext(objective, ObjectiveSense.MINIMIZE,
            ExecutionControl.Never))
        var cancelled = false
        val params = TheoryParams(sourceIncumbentCompletion = true, cancellation = ExecutionControl { cancelled })
        val seed = Sample(BooleanArray(0), longArrayOf(1, 1, 1))
        JointLinearRepair(model, source, objective, params, external = { seed }).use { repair ->
            repair.search(adaptive = true).use { search ->
                repeat(128) {
                    if (repair.stats.smt.completeSearchStages?.get("jointRepairCutoffClosures") != 1L) {
                        search.advance(ExecutionControl.Never, 1)
                    }
                }
                assertTrue(repair.incumbentTrialPending)
                cancelled = true
                search.advance(ExecutionControl.Never, 1)

                assertFalse(repair.incumbentTrialPending)
                assertEquals(1L, repair.stats.smt.completeSearchStages?.get("jointRepairCandidates"))
                assertNull(repair.stats.smt.completeSearchStages?.get("jointRepairEnlargedTrialStarts"))
            }
        }
    }

    @Test
    fun `an exhausted adaptive move allowance should not open a larger query`() {
        val model = Problem(0, intBounds = IntBounds.fromModelBounds(LongArray(3), LongArray(3) { 1 }, null, null),
            factors = arrayOf(
                Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.EQ, 0),
                Linear(intArrayOf(1, -1), intArrayOf(1, 2), LinearOp.EQ, 0),
            ))
        val objective = LinearObjective(intCoefficients = longArrayOf(1, 1, 1))
        val source = assertNotNull(model.linearAuthorityContext(objective, ObjectiveSense.MINIMIZE,
            ExecutionControl.Never))
        val params = TheoryParams(sourceIncumbentCompletion = true, maxFlips = 1)
        val seed = Sample(BooleanArray(0), longArrayOf(1, 1, 1))
        JointLinearRepair(model, source, objective, params, external = { seed }).use { repair ->
            repair.search(adaptive = true).use { search ->
                var done = false
                repeat(128) { if (!done) done = search.advance(ExecutionControl.Never, 1) }

                assertTrue(done)
                assertEquals(1L, repair.stats.smt.completeSearchStages?.get("jointRepairCandidates"))
                assertNull(repair.stats.smt.completeSearchStages?.get("jointRepairEnlargedTrialStarts"))
            }
            assertFalse(repair.incumbentTrialPending)
        }
    }

    @Test
    fun `reused exact imports should still respect each receiving neighborhoods integer pins`() {
        val model = Problem(0, intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(1), null, null),
            factors = emptyArray(), numRealVars = 1,
            realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(0.0))
        val objective = LinearObjective(intCoefficients = longArrayOf(1))
        val source = assertNotNull(model.linearAuthorityContext(objective, ObjectiveSense.MINIMIZE,
            ExecutionControl.Never))
        val offered = Sample(BooleanArray(0), longArrayOf(1), doubleArrayOf(0.0),
            exactReals = listOf(BigFraction.ZERO))
        val conditions = listOf(
            ExactNeighborhoodCondition(emptyMap(), mapOf(0 to BigFraction.ZERO), mapOf(0 to BigFraction.ZERO)),
            ExactNeighborhoodCondition(emptyMap(), mapOf(0 to BigFraction.ZERO, 1 to BigFraction.ZERO),
                mapOf(0 to BigFraction.ZERO, 1 to BigFraction.ZERO)),
        )
        var importChecks = 0

        JointLinearRepair(model, source, objective, TheoryParams(sourceIncumbentCompletion = true),
            external = { offered }, incumbentCompletionCheck = { sample, _, _ ->
                if (sample === offered) importChecks++
                true
            }).use { repair ->
            for (condition in conditions) {
                var attempt = repair.attempt(condition, ExecutionControl.Never)
                repeat(32) {
                    if (attempt.result == ExactNeighborhoodResult.Paused) {
                        attempt = repair.attempt(condition, ExecutionControl.Never)
                    }
                }

                assertEquals(0L, assertIs<ExactNeighborhoodResult.Witness>(attempt.result).sample.intValue(0))
            }
            assertEquals(1, importChecks)
            assertTrue((repair.stats.smt.completeSearchStages?.get(
                "jointRepairExternalCompletionExactCheckedReused") ?: 0L) > 0L)
        }
    }

    @Test
    fun `non improving exact external points should need only their preparation check`() {
        val model = Problem(0, intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(1), null, null),
            factors = emptyArray(), numRealVars = 1,
            realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(0.0))
        val objective = LinearObjective(intCoefficients = longArrayOf(1))
        val source = assertNotNull(model.linearAuthorityContext(objective, ObjectiveSense.MINIMIZE,
            ExecutionControl.Never))
        val offered = Sample(BooleanArray(0), longArrayOf(1), doubleArrayOf(0.0),
            exactReals = listOf(BigFraction.ZERO))
        val condition = ExactNeighborhoodCondition(emptyMap(), emptyMap(), emptyMap(), BigFraction.ONE)
        for (logging in listOf(false, true)) {
            var importChecks = 0
            var comparisons = 0
            JointLinearRepair(model, source, objective, TheoryParams(sourceIncumbentCompletion = true),
                external = { offered }, incumbentCompletionCheck = { sample, _, _ ->
                    if (sample === offered) importChecks++
                    true
                }, onComparison = if (logging) {
                    { comparisons++ }
                } else null).use { repair ->
                repair.search(adaptive = false).use { search ->
                    search.advance(ExecutionControl.Never, 1)
                    assertEquals(1, importChecks)
                    repeat(40) {
                        var attempt = repair.attempt(condition, ExecutionControl.Never)
                        repeat(32) {
                            if (attempt.result == ExactNeighborhoodResult.Paused) {
                                attempt = repair.attempt(condition, ExecutionControl.Never)
                            }
                        }
                        assertEquals(0L, assertNotNull(attempt.feasiblePoint).intValue(0))
                    }

                    assertEquals(1, importChecks)
                    assertEquals(if (logging) 64 else 0, comparisons)
                }
            }
        }
    }

    @Test
    fun `an advisory value above the cutoff should remain eligible for completion`() {
        val model = Problem(0, intBounds = IntBounds.fromModelBounds(longArrayOf(1), longArrayOf(1), null, null),
            factors = emptyArray(), numRealVars = 1,
            realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(1.0))
        val objective = LinearObjective(realCoefficients = doubleArrayOf(1.0))
        val source = assertNotNull(model.linearAuthorityContext(objective, ObjectiveSense.MINIMIZE,
            ExecutionControl.Never))
        val offered = Sample(BooleanArray(0), longArrayOf(1), doubleArrayOf(2.0))
        val condition = ExactNeighborhoodCondition(emptyMap(), emptyMap(), emptyMap(), BigFraction.ONE)

        JointLinearRepair(model, source, objective, TheoryParams(sourceIncumbentCompletion = true),
            external = { offered }, incumbentCompletionCheck = { _, _, _ -> true }).use { repair ->
            var attempt = repair.attempt(condition, ExecutionControl.Never)
            repeat(32) {
                if (attempt.result == ExactNeighborhoodResult.Paused) {
                    attempt = repair.attempt(condition, ExecutionControl.Never)
                }
            }

            assertEquals(ExactNeighborhoodResult.Undecided, attempt.result)
            assertEquals(listOf(BigFraction.ZERO), assertNotNull(attempt.feasiblePoint).exactReals)
        }
    }

    @Test
    fun `completion repair should keep an imported point out of discovery publication`() {
        val model = Problem(0, intBounds = IntBounds.fromModelBounds(longArrayOf(1), longArrayOf(1), null, null),
            factors = emptyArray(), numRealVars = 1,
            realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(0.0))
        val objective = LinearObjective(intCoefficients = longArrayOf(1))
        val source = assertNotNull(model.linearAuthorityContext(objective, ObjectiveSense.MINIMIZE,
            ExecutionControl.Never))
        val center = Sample(booleanArrayOf(), longArrayOf(1), doubleArrayOf(0.0), exactReals = listOf(BigFraction.ZERO))
        val condition = ExactNeighborhoodCondition(emptyMap(), emptyMap(), emptyMap(), BigFraction.ofLong(2))

        for (completion in listOf(false, true)) {
            JointLinearRepair(model, source, objective, TheoryParams(sourceIncumbentCompletion = completion),
                external = { center }, incumbentCompletionCheck = { _, _, _ -> true },
            ).use { repair ->
                var attempt = repair.attempt(condition, ExecutionControl.Never)
                repeat(32) {
                    if (attempt.result == ExactNeighborhoodResult.Paused) {
                        attempt = repair.attempt(condition, ExecutionControl.Never)
                    }
                }

                if (completion) {
                    assertEquals(ExactNeighborhoodResult.Undecided, attempt.result)
                    assertEquals(center, attempt.feasiblePoint)
                } else {
                    assertEquals(center, assertIs<ExactNeighborhoodResult.Witness>(attempt.result).sample)
                }
            }
        }
    }

    @Test
    fun `a checked center should improve within one LP operation`() {
        val model = Problem(0, intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(1), null, null),
            factors = arrayOf(
                Linear(intArrayOf(0), doubleArrayOf(1.0), intArrayOf(0), doubleArrayOf(1.0), LinearOp.GE, 1.0),
            ), numRealVars = 1, realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(1.0))
        val objective = LinearObjective(intCoefficients = longArrayOf(1))
        val source = assertNotNull(model.linearAuthorityContext(objective, ObjectiveSense.MINIMIZE,
            ExecutionControl.Never))
        val seed = Sample(booleanArrayOf(), longArrayOf(1), doubleArrayOf(0.0), exactReals = listOf(BigFraction.ZERO))

        val result = JointLinearRepair(model, source, objective,
            TheoryParams(sourceIncumbentCompletion = true, sourceLpOperations = 1, maxFlips = 1),
            external = { seed }).use { repair ->
            repair.search(adaptive = false).use { search ->
                var done = false
                repeat(32) { if (!done) done = search.advance(ExecutionControl.Never, 5_000) }
                search.minimizeResult(TerminationReason.BudgetExhausted)
            }
        }

        val sample = assertIs<MinimizeResult.WithSample>(result).sample
        assertEquals(0L, sample.intValue(0))
        assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(source.withLinearAuthority()).verify(
            SourceEvidence(source.scope, SourceClaim.Witness(sample), SourceDerivation.SourceWitness)))
    }

    @Test
    fun `accepted original checks should respect publication lifetime and funding`() {
        val model = Problem(0, 0, emptyArray(), emptyArray(), numRealVars = 1,
            realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(1.0))
        val objective = LinearObjective(realCoefficients = doubleArrayOf(-1.0))
        val source = assertNotNull(model.linearAuthorityContext(objective, ObjectiveSense.MINIMIZE,
            ExecutionControl.Never))
        val condition = ExactNeighborhoodCondition(emptyMap(), emptyMap(), emptyMap())
        val params = TheoryParams(sourceIncumbentCompletion = true)

        for (limit in listOf("none", "lifetime", "funding")) {
            var cancelled = false
            var checkedValue: BigFraction? = null
            JointLinearRepair(model, source, objective, params,
                publicationLifetime = ExecutionControl { cancelled },
                incumbentCompletionCheck = { _, value, token ->
                    checkedValue = value
                    token.charge(if (limit == "funding") params.sourceLpWorkLimit else 1L)
                    cancelled = limit == "lifetime"
                    true
                },
            ).use { repair ->
                var result = repair.check(condition, ExecutionControl.Never)
                repeat(64) {
                    if (result == ExactNeighborhoodResult.Paused) result = repair.check(condition,
                        ExecutionControl.Never)
                }

                assertEquals(BigFraction.MINUS_ONE, checkedValue)
                if (limit != "none") assertEquals(ExactNeighborhoodResult.Undecided, result)
                else assertEquals(BigFraction.ONE,
                    assertNotNull(assertIs<ExactNeighborhoodResult.Witness>(result).sample.exactReals).single())
            }
        }
    }

    @Test
    fun `exact original checking should withhold a tolerance accepted repair point`() {
        val originalSource = LinearSourceProblem(listOf(LinearSourceVariable("r", false,
            SemanticRational.ZERO, SemanticRational.ONE)), emptyList(),
            LinearSourceObjective(listOf(LinearSourceTerm(0, SemanticRational.ONE)), sense = ObjectiveSense.MAXIMIZE),
            SourceSemantics(SourceFormat.MPS, feasibilityTolerance = SourceTolerance(
                absolute = SemanticRational.of(BIG_ONE, bigIntOf(1_000_000)))).resolve())
        val invocation = SourceSetup.prepare(SourceSetupRequest(SourceAttachment(originalSource, ProblemSettings()),
            ExecutionOperation.OPTIMIZE, FiniteEngine.MIXED, maximize = true))
        val original = assertNotNull(invocation.linearSource)
        val model = Problem(0, 0, emptyArray(), emptyArray(), numRealVars = 1,
            realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(1.0000005))
        val objective = LinearObjective(realCoefficients = doubleArrayOf(-1.0))
        val source = assertNotNull(model.linearAuthorityContext(objective, ObjectiveSense.MINIMIZE,
            ExecutionControl.Never))
        val endpoint = assertNotNull(BigFraction.ofDouble(1.0000005))
        val condition = ExactNeighborhoodCondition(emptyMap(), mapOf(0 to endpoint), mapOf(0 to endpoint))

        for (checkOriginal in listOf(false, true)) {
            JointLinearRepair(model, source, objective, TheoryParams(sourceIncumbentCompletion = true),
                incumbentCompletionCheck = if (checkOriginal) original.incumbentCompletionCheck(null) else null,
            ).use { repair ->
                var result = repair.check(condition, ExecutionControl.Never)
                repeat(64) {
                    if (result == ExactNeighborhoodResult.Paused) result = repair.check(condition,
                        ExecutionControl.Never)
                }

                if (checkOriginal) assertEquals(ExactNeighborhoodResult.Undecided, result)
                else {
                    val point = assertIs<ExactNeighborhoodResult.Witness>(result).sample
                    val accepted = assertIs<LinearSourceCheck.Accepted>(original.check(point, ExecutionControl.Never))
                    assertFalse(accepted.witness.exact)
                }
            }
        }
    }

    @Test
    fun `a rejected center should clear its preparation continuation request`() {
        val model = Problem(0, intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(2), null, null),
            factors = arrayOf(
                Linear(intArrayOf(0), doubleArrayOf(1.0), intArrayOf(0), doubleArrayOf(-1.0), LinearOp.GE, 0.0),
            ), numRealVars = 1, realLower = doubleArrayOf(0.75), realUpper = doubleArrayOf(2.0))
        val objective = LinearObjective(intCoefficients = longArrayOf(1))
        val source = assertNotNull(model.linearAuthorityContext(objective, ObjectiveSense.MINIMIZE,
            ExecutionControl.Never))
        val seed = Sample(booleanArrayOf(), longArrayOf(2), doubleArrayOf(3.0),
            exactReals = listOf(BigFraction.ofLong(3)))
        var consumed = false

        JointLinearRepair(model, source, objective, TheoryParams(sourceIncumbentCompletion = true, maxFlips = 1),
            external = { seed }, onIncumbentUse = { consumed = true }).use { repair ->
            repair.search(adaptive = false).use { search ->
                search.advance(ExecutionControl.Never, 1)
                assertTrue(repair.incumbentTrialPending)
                var done = false
                repeat(32) { if (!done) done = search.advance(ExecutionControl.Never, 5_000) }

                assertFalse(consumed)
                assertFalse(repair.incumbentTrialPending)
            }
        }
    }

    @Test
    fun `a compatible pooled incumbent should request preparation continuation only with enabled completion`() {
        val model = Problem(0, intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(2), null, null),
            factors = arrayOf(
                Linear(intArrayOf(0), doubleArrayOf(1.0), intArrayOf(0), doubleArrayOf(-1.0), LinearOp.GE, 0.0),
            ), numRealVars = 1, realLower = doubleArrayOf(0.75), realUpper = doubleArrayOf(2.0))
        val objective = LinearObjective(intCoefficients = longArrayOf(1))
        val source = assertNotNull(model.linearAuthorityContext(objective, ObjectiveSense.MINIMIZE,
            ExecutionControl.Never))
        for (exact in listOf(false, true)) {
            val seed = Sample(booleanArrayOf(), longArrayOf(2), doubleArrayOf(0.75),
                exactReals = if (exact) listOf(BigFraction.ofLong(3) * BigFraction.ofLong(4).reciprocal()) else null)
            for (completion in listOf(false, true)) {
                var consumed = false
                JointLinearRepair(model, source, objective, TheoryParams(sourceIncumbentCompletion = completion),
                    external = { seed }, onIncumbentUse = { consumed = true }).use { repair ->
                    repair.search(adaptive = false).use { search ->
                        search.advance(ExecutionControl.Never, 1)

                        assertFalse(consumed)
                        assertEquals(completion, repair.incumbentTrialPending)
                    }
                }
            }
        }
    }

    @Test
    fun `original source rejection should clear advisory preparation without consumption`() {
        val model = Problem(0, intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(2), null, null),
            factors = arrayOf(
                Linear(intArrayOf(0), doubleArrayOf(1.0), intArrayOf(0), doubleArrayOf(-1.0), LinearOp.GE, 0.0),
            ), numRealVars = 1, realLower = doubleArrayOf(0.75), realUpper = doubleArrayOf(2.0))
        val objective = LinearObjective(intCoefficients = longArrayOf(1))
        val source = assertNotNull(model.linearAuthorityContext(objective, ObjectiveSense.MINIMIZE,
            ExecutionControl.Never))
        val seed = Sample(booleanArrayOf(), longArrayOf(2), doubleArrayOf(0.75))
        var consumed = false

        JointLinearRepair(model, source, objective, TheoryParams(sourceIncumbentCompletion = true, maxFlips = 1),
            external = { seed }, onIncumbentUse = { consumed = true },
            incumbentCompletionCheck = { _, _, _ -> false }).use { repair ->
            repair.search(adaptive = false).use { search ->
                search.advance(ExecutionControl.Never, 1)
                assertTrue(repair.incumbentTrialPending)
                var done = false
                repeat(32) { if (!done) done = search.advance(ExecutionControl.Never, 5_000) }

                assertFalse(consumed)
                assertFalse(repair.incumbentTrialPending)
            }
        }
    }

    @Test
    fun `a checked center trial should retain its condition after a publication pause`() {
        val model = Problem(0, intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(2), null, null),
            factors = arrayOf(
                Linear(intArrayOf(0), doubleArrayOf(1.0), intArrayOf(0), doubleArrayOf(-1.0), LinearOp.GE, 0.0),
            ), numRealVars = 1, realLower = doubleArrayOf(0.75), realUpper = doubleArrayOf(2.0))
        val objective = LinearObjective(intCoefficients = longArrayOf(1))
        val source = assertNotNull(model.linearAuthorityContext(objective, ObjectiveSense.MINIMIZE,
            ExecutionControl.Never))
        val seed = Sample(booleanArrayOf(), longArrayOf(2), doubleArrayOf(0.75),
            exactReals = listOf(BigFraction.ofLong(3) * BigFraction.ofLong(4).reciprocal()))
        var paused = false

        JointLinearRepair(model, source, objective, TheoryParams(sourceIncumbentCompletion = true, maxFlips = 1),
            external = { seed }, onIncumbentUse = { paused = true }).use { repair ->
            repair.search(adaptive = false).use { search ->
                search.advance(ExecutionControl { paused }, 5_000)
                assertTrue(repair.incumbentTrialPending)
                paused = false
                var done = false
                repeat(32) { if (!done) done = search.advance(ExecutionControl.Never, 5_000) }

                assertEquals(1L, assertIs<MinimizeResult.WithSample>(
                    search.minimizeResult(TerminationReason.BudgetExhausted)).sample.intValue(0))
                assertFalse(repair.incumbentTrialPending)
            }
        }
    }

    @Test
    fun `checked handoff should improve its first connected integer neighborhood`() {
        val model = Problem(0, intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(2), null, null),
            factors = arrayOf(
                Linear(intArrayOf(0), doubleArrayOf(1.0), intArrayOf(0), doubleArrayOf(-1.0), LinearOp.GE, 0.0),
            ), numRealVars = 1, realLower = doubleArrayOf(0.75), realUpper = doubleArrayOf(2.0))
        val objective = LinearObjective(intCoefficients = longArrayOf(1))
        val source = assertNotNull(model.linearAuthorityContext(objective, ObjectiveSense.MINIMIZE,
            ExecutionControl.Never))
        val seed = Sample(booleanArrayOf(), longArrayOf(2), doubleArrayOf(0.75),
            exactReals = listOf(BigFraction.ofLong(3) * BigFraction.ofLong(4).reciprocal()))

        for (completion in listOf(false, true)) {
            val result = JointLinearRepair(model, source, objective,
                TheoryParams(sourceIncumbentCompletion = completion, maxFlips = 1), external = { seed }).use { repair ->
                repair.search(adaptive = false).use { search ->
                    var done = false
                    repeat(32) { if (!done) done = search.advance(ExecutionControl.Never, 5_000) }
                    search.minimizeResult(TerminationReason.BudgetExhausted)
                }
            }

            if (completion) {
                val sample = assertIs<MinimizeResult.WithSample>(result).sample
                assertEquals(1L, sample.intValue(0))
                assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(source.withLinearAuthority()).verify(
                    SourceEvidence(source.scope, SourceClaim.Witness(sample), SourceDerivation.SourceWitness)))
            } else assertNull((result as? MinimizeResult.WithSample)?.sample)
        }
    }

    @Test
    fun `an advisory pooled center should be completed before neighborhood consumption`() {
        val model = Problem(0, intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(2), null, null),
            factors = arrayOf(
                Linear(intArrayOf(0), doubleArrayOf(1.0), intArrayOf(0), doubleArrayOf(-1.0), LinearOp.GE, 0.0),
            ), numRealVars = 1, realLower = doubleArrayOf(0.75), realUpper = doubleArrayOf(2.0))
        val objective = LinearObjective(intCoefficients = longArrayOf(1))
        val source = assertNotNull(model.linearAuthorityContext(objective, ObjectiveSense.MINIMIZE,
            ExecutionControl.Never))
        val seed = Sample(booleanArrayOf(), longArrayOf(1), doubleArrayOf(0.75))
        val events = mutableListOf<String>()

        JointLinearRepair(model, source, objective, TheoryParams(sourceIncumbentCompletion = true, maxFlips = 1),
            external = { seed }, onIncumbentImport = { events += "import" },
            onIncumbentUse = { events += "use" }).use { repair ->
            repair.search(adaptive = false).use { search ->
                var done = false
                repeat(32) { if (!done) done = search.advance(ExecutionControl.Never, 1) }
            }
            assertTrue((repair.stats.smt.completeSearchStages?.get("jointRepairExternalCompletionChecked") ?: 0L) > 0L)
        }

        assertEquals(listOf("import", "use"), events.take(2))
    }

    @Test
    fun `a freed integer should complete its affine backing at a lower objective`() {
        val model = Problem(0, intBounds = IntBounds.fromModelBounds(longArrayOf(0, 0),
            longArrayOf(1, 1), null, null), factors = arrayOf(
            Linear(intArrayOf(-1, 1), intArrayOf(0, 1), LinearOp.EQ, 0),
        ))
        val objective = LinearObjective(intCoefficients = longArrayOf(0, 1))
        val source = assertNotNull(model.linearAuthorityContext(objective, ObjectiveSense.MINIMIZE,
            ExecutionControl.Never))
        val center = Sample(BooleanArray(0), longArrayOf(1, 1))
        val condition = ExactNeighborhoodCondition(emptyMap(), emptyMap(), emptyMap(), BigFraction.ONE)
        JointLinearRepair(model, source, objective, TheoryParams(), external = { center }).use { repair ->
            var result = repair.check(condition, ExecutionControl.Never)
            repeat(64) {
                if (result == ExactNeighborhoodResult.Paused) result = repair.check(condition, ExecutionControl.Never)
            }

            val point = assertIs<ExactNeighborhoodResult.Witness>(result).sample
            assertEquals(0L, point.intValue(0))
            assertEquals(0L, point.intValue(1))
        }
    }
}
