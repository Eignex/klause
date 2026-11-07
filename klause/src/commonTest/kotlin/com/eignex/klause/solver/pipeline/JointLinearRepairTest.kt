package com.eignex.klause.solver.pipeline

import com.eignex.klause.backtrack.NodeBudget
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
import com.eignex.klause.lp.SparseSourceLpPolicy
import com.eignex.klause.lp.optimization.withLinearAuthority
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.evidence.SourceClaim
import com.eignex.klause.solver.evidence.SourceDerivation
import com.eignex.klause.solver.evidence.SourceEvidence
import com.eignex.klause.solver.evidence.SourceEvidenceCheck
import com.eignex.klause.solver.evidence.SourceEvidenceVerifier
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.solver.validation.LinearSourceCheck
import com.eignex.klause.util.Cancellation
import com.eignex.util.numbers.BIG_ONE
import com.eignex.util.numbers.bigIntOf
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JointLinearRepairTest {
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
                objective, ObjectiveSense.MINIMIZE, Cancellation.Never))
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
                    search.advance(Cancellation.Never, 1)
                    assertEquals(BigFraction.ofLong(4 * sign * oldValue),
                        events.first { it.phase == "center" }.completedValue)
                    offered = Sample(BooleanArray(0), LongArray(3) { 1 - oldValue },
                        doubleArrayOf(oldValue.toDouble()), exactReals = listOf(BigFraction.ofLong(oldValue)))
                    offeredVersion = 2
                    var done = false
                    repeat(32) { if (!done) done = search.advance(Cancellation.Never, 5_000) }

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
                objective, ObjectiveSense.MINIMIZE, Cancellation.Never))
            val oldValue = if (sign > 0) 1L else 0L
            var offered = Sample(BooleanArray(0), LongArray(3) { oldValue }, doubleArrayOf(oldValue.toDouble()),
                exactReals = listOf(BigFraction.ofLong(oldValue)))
            val events = mutableListOf<JointRepairComparison>()
            JointLinearRepair(model, source, objective,
                TheoryParams(sourceIncumbentCompletion = true, maxFlips = 1), external = { offered },
                incumbentCompletionCheck = { _, _, _ -> true }, onComparison = { events += it }).use { repair ->
                repair.search(adaptive = false).use { search ->
                    search.advance(Cancellation.Never, 1)
                    offered = offered.copy(reals = doubleArrayOf((1 - oldValue).toDouble()),
                        exactReals = listOf(BigFraction.ofLong(1 - oldValue)))
                    var done = false
                    repeat(32) { if (!done) done = search.advance(Cancellation.Never, 5_000) }

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
                objective, ObjectiveSense.MINIMIZE, Cancellation.Never))
            var offered = Sample(BooleanArray(0), longArrayOf(2), doubleArrayOf(1.0),
                exactReals = listOf(BigFraction.ONE))
            var cancelled = false
            var originalChecked = false
            val events = mutableListOf<JointRepairComparison>()
            val params = TheoryParams(sourceIncumbentCompletion = true, sourceLpWorkLimit = 1_000_000,
                maxFlips = 1, cancellation = Cancellation { cancelled })
            JointLinearRepair(model, source, objective, params, external = { offered },
                incumbentCompletionCheck = { _, _, token ->
                    originalChecked = true
                    token.charge(if (limit == "funding") params.sourceLpWorkLimit else 1L)
                    cancelled = limit == "cancellation"
                    true
                }, onComparison = { events += it }).use { repair ->
                repair.search(adaptive = false).use { search ->
                    search.advance(Cancellation.Never, 1)
                    offered = offered.copy(ints = longArrayOf(0))
                    var done = false
                    repeat(32) { if (!done) done = search.advance(Cancellation.Never, 5_000) }

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
        val source = assertNotNull(model.linearAuthorityContext(objective, ObjectiveSense.MINIMIZE, Cancellation.Never))
        for ((exact, real, originalAccepted) in listOf(Triple(false, 1L, true), Triple(true, 2L, true),
                Triple(true, 1L, false))) {
            var offered = Sample(BooleanArray(0), longArrayOf(2), doubleArrayOf(1.0),
                exactReals = listOf(BigFraction.ONE))
            var halted = false
            val events = mutableListOf<JointRepairComparison>()
            val params = TheoryParams(sourceIncumbentCompletion = true, maxFlips = 1,
                cancellation = Cancellation { halted })
            JointLinearRepair(model, source, objective, params, external = { offered },
                incumbentCompletionCheck = { _, _, _ -> originalAccepted }, onComparison = {
                    events += it
                    if (it.phase == "query") halted = true
                }).use { repair ->
                repair.search(adaptive = false).use { search ->
                    search.advance(Cancellation.Never, 1)
                    assertEquals(BigFraction.ofLong(3), events.first { it.phase == "center" }.completedValue)
                    offered = Sample(BooleanArray(0), longArrayOf(0), doubleArrayOf(real.toDouble()),
                        exactReals = if (exact) listOf(BigFraction.ofLong(real)) else null)
                    repeat(32) { if (!halted) search.advance(Cancellation.Never, 5_000) }

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
        val source = assertNotNull(model.linearAuthorityContext(objective, ObjectiveSense.MINIMIZE, Cancellation.Never))
        val offered = Sample(BooleanArray(0), longArrayOf(1), doubleArrayOf(0.333))
        val events = mutableListOf<JointRepairComparison>()

        JointLinearRepair(model, source, objective,
            TheoryParams(sourceIncumbentCompletion = true, maxFlips = 1), external = { offered },
            onComparison = { events += it }).use { repair ->
            repair.search(adaptive = false).use { search ->
                var done = false
                repeat(32) { if (!done) done = search.advance(Cancellation.Never, 5_000) }
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
        val source = assertNotNull(model.linearAuthorityContext(objective, ObjectiveSense.MINIMIZE, Cancellation.Never))
        val condition = ExactNeighborhoodCondition(emptyMap(), emptyMap(), emptyMap(), BigFraction.ZERO)

        for (accepted in listOf(false, true)) {
            val events = mutableListOf<JointRepairComparison>()
            JointLinearRepair(model, source, objective, TheoryParams(sourceIncumbentCompletion = true),
                incumbentCompletionCheck = { _, _, _ -> accepted }, onComparison = { events += it }).use { repair ->
                assertEquals(ExactNeighborhoodResult.Paused, repair.check(condition, Cancellation { true }))
                assertTrue(events.isEmpty())
                var result = repair.check(condition, Cancellation.Never)
                repeat(32) {
                    if (result == ExactNeighborhoodResult.Paused) result = repair.check(condition, Cancellation.Never)
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
        val source = assertNotNull(model.linearAuthorityContext(objective, ObjectiveSense.MINIMIZE, Cancellation.Never))
        for (enabled in listOf(false, true)) {
            val params = TheoryParams(sourceCuts = enabled, maxFlips = 1)
            JointLinearRepair(model, source, objective, params, boundOrdered = true).use { repair ->
                repair.search(adaptive = false).use { search ->
                    var done = false
                    repeat(64) { if (!done) done = search.advance(Cancellation.Never, 1) }

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
        val source = assertNotNull(model.linearAuthorityContext(objective, ObjectiveSense.MINIMIZE, Cancellation.Never))
        for (enabled in listOf(false, true)) {
            val params = TheoryParams(sourceAggregateCuts = enabled, sourcePointCuts = true, maxFlips = 1)
            JointLinearRepair(model, source, objective, params, boundOrdered = true).use { repair ->
                repair.search(adaptive = false).use { search ->
                    var done = false
                    repeat(64) { if (!done) done = search.advance(Cancellation.Never, 1) }

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
    fun `joint cut statistics should include root preparation and final owner retirement`() {
        val model = Problem(0, intBounds = IntBounds.fromModelBounds(longArrayOf(0, 0), longArrayOf(2, 2), null, null),
            factors = arrayOf(Linear(intArrayOf(2, 2), intArrayOf(0, 1), LinearOp.GE, 1)))
        val objective = LinearObjective(intCoefficients = longArrayOf(1, 1))
        val source = assertNotNull(model.linearAuthorityContext(objective, ObjectiveSense.MINIMIZE, Cancellation.Never))
        val repair = JointLinearRepair(model, source, objective, TheoryParams(), boundOrdered = true)
        val root = repair.search(adaptive = false).use { search ->
            search.advance(Cancellation.Never, 1)

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
        val source = assertNotNull(model.linearAuthorityContext(objective, ObjectiveSense.MINIMIZE, Cancellation.Never))
        val condition = ExactNeighborhoodCondition(emptyMap(), emptyMap(), emptyMap())
        JointLinearRepair(model, source, objective, TheoryParams(sourceAggregateCuts = true),
            boundOrdered = true).use { repair ->
            var first: Map<String, Long>? = null
            repeat(3) { index ->
                var result = repair.check(condition, Cancellation.Never)
                repeat(32) {
                    if (result == ExactNeighborhoodResult.Paused) result = repair.check(condition, Cancellation.Never)
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
    fun `adaptive checked repair should change coupled integer groups together`() {
        for (size in listOf(3, 7)) {
            val model = Problem(0,
                intBounds = IntBounds.fromModelBounds(LongArray(size), LongArray(size) { 1 }, null, null),
                factors = Array(size - 1) { id ->
                    Linear(intArrayOf(1, -1), intArrayOf(id, id + 1), LinearOp.EQ, 0)
                })
            val objective = LinearObjective(intCoefficients = LongArray(size) { 1 })
            val source = assertNotNull(model.linearAuthorityContext(
                objective, ObjectiveSense.MINIMIZE, Cancellation.Never))
            val seed = Sample(BooleanArray(0), LongArray(size) { 1 })
            val params = TheoryParams(sourceIncumbentCompletion = true, maxFlips = 3)

            for (adaptive in listOf(false, true)) {
                JointLinearRepair(model, source, objective, params, external = { seed }).use { repair ->
                    repair.search(adaptive = adaptive).use { search ->
                        repeat(128) {
                            if (repair.stats.smt.completeSearchStages?.get("jointRepairCutoffClosures") != 1L) {
                                search.advance(Cancellation.Never, 1)
                            }
                        }
                        assertEquals(adaptive, repair.incumbentTrialPending)
                        var done = false
                        repeat(128) { if (!done) done = search.advance(Cancellation.Never, 1) }

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
                            assertEquals(if (size == 3) 1L else 2L, stages["jointRepairLargerNeighborhoodCompletions"])
                            assertEquals(1L, stages["jointRepairLargerNeighborhoodCheckedIncumbents"])
                        } else assertTrue(result !is MinimizeResult.WithSample)
                        assertFalse(repair.incumbentTrialPending)
                    }
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
        val source = assertNotNull(model.linearAuthorityContext(objective, ObjectiveSense.MINIMIZE, Cancellation.Never))
        val seed = Sample(BooleanArray(0), LongArray(3) { 1 }, doubleArrayOf(1.0),
            exactReals = listOf(BigFraction.ONE))
        val params = TheoryParams(sourceIncumbentCompletion = true, maxFlips = 2)

        for (adaptive in listOf(false, true)) {
            JointLinearRepair(model, source, objective, params, external = { seed },
                sourceIntegers = intArrayOf(0, 1, 2)).use { repair ->
                repair.search(adaptive = adaptive).use { search ->
                    val points = ArrayList<Sample>()
                    repeat(128) {
                        if (points.isEmpty()) search.advance(Cancellation.Never, 1) { sample, _ -> points += sample }
                    }
                    val first = points.single()
                    assertContentEquals(LongArray(3) { 1 }, LongArray(3) { first.intValue(it) })
                    assertEquals(listOf(BigFraction.ZERO), first.exactReals)
                    assertEquals(adaptive, repair.incumbentTrialPending)
                    var done = false
                    repeat(128) { if (!done) done = search.advance(Cancellation.Never, 1) }

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
        val source = assertNotNull(model.linearAuthorityContext(objective, ObjectiveSense.MINIMIZE, Cancellation.Never))
        val seed = Sample(BooleanArray(0), LongArray(3) { 1 })
        val params = TheoryParams(sourceIncumbentCompletion = true, maxFlips = 1)
        JointLinearRepair(model, source, objective, params, external = { seed }).use { repair ->
            repair.search(adaptive = true).use { search ->
                var done = false
                repeat(128) { if (!done) done = search.advance(Cancellation.Never, 1) }

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
        val source = assertNotNull(model.linearAuthorityContext(objective, ObjectiveSense.MINIMIZE, Cancellation.Never))
        var cancelled = false
        val params = TheoryParams(sourceIncumbentCompletion = true, cancellation = Cancellation { cancelled })
        val seed = Sample(BooleanArray(0), LongArray(3) { 1 }, doubleArrayOf(1.0),
            exactReals = listOf(BigFraction.ONE))
        JointLinearRepair(model, source, objective, params, external = { seed }).use { repair ->
            repair.search(adaptive = true).use { search ->
                repeat(128) {
                    if (!cancelled) search.advance(Cancellation.Never, 1) { _, _ -> cancelled = true }
                }
                search.advance(Cancellation.Never, 1)

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
        val source = assertNotNull(model.linearAuthorityContext(objective, ObjectiveSense.MINIMIZE, Cancellation.Never))
        var cancelled = false
        val params = TheoryParams(sourceIncumbentCompletion = true, cancellation = Cancellation { cancelled })
        val seed = Sample(BooleanArray(0), longArrayOf(1, 1, 1))
        JointLinearRepair(model, source, objective, params, external = { seed }).use { repair ->
            repair.search(adaptive = true).use { search ->
                repeat(128) {
                    if (repair.stats.smt.completeSearchStages?.get("jointRepairCutoffClosures") != 1L) {
                        search.advance(Cancellation.Never, 1)
                    }
                }
                assertTrue(repair.incumbentTrialPending)
                cancelled = true
                search.advance(Cancellation.Never, 1)

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
        val source = assertNotNull(model.linearAuthorityContext(objective, ObjectiveSense.MINIMIZE, Cancellation.Never))
        val params = TheoryParams(sourceIncumbentCompletion = true, maxFlips = 1)
        val seed = Sample(BooleanArray(0), longArrayOf(1, 1, 1))
        JointLinearRepair(model, source, objective, params, external = { seed }).use { repair ->
            repair.search(adaptive = true).use { search ->
                var done = false
                repeat(128) { if (!done) done = search.advance(Cancellation.Never, 1) }

                assertTrue(done)
                assertEquals(1L, repair.stats.smt.completeSearchStages?.get("jointRepairCandidates"))
                assertNull(repair.stats.smt.completeSearchStages?.get("jointRepairEnlargedTrialStarts"))
            }
            assertFalse(repair.incumbentTrialPending)
        }
    }

    @Test
    fun `completion repair should keep an imported point out of discovery publication`() {
        val model = Problem(0, intBounds = IntBounds.fromModelBounds(longArrayOf(1), longArrayOf(1), null, null),
            factors = emptyArray(), numRealVars = 1,
            realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(0.0))
        val objective = LinearObjective(intCoefficients = longArrayOf(1))
        val source = assertNotNull(model.linearAuthorityContext(objective, ObjectiveSense.MINIMIZE, Cancellation.Never))
        val center = Sample(booleanArrayOf(), longArrayOf(1), doubleArrayOf(0.0), exactReals = listOf(BigFraction.ZERO))
        val condition = ExactNeighborhoodCondition(emptyMap(), emptyMap(), emptyMap(), BigFraction.ofLong(2))

        for (completion in listOf(false, true)) {
            var publicationChecks = 0
            JointLinearRepair(model, source, objective, TheoryParams(sourceIncumbentCompletion = completion),
                external = { center }, incumbentCompletionCheck = { _, _, _ -> publicationChecks++; true },
            ).use { repair ->
                var attempt = repair.attempt(condition, Cancellation.Never)
                repeat(32) {
                    if (attempt.result == ExactNeighborhoodResult.Paused) {
                        attempt = repair.attempt(condition, Cancellation.Never)
                    }
                }

                if (completion) {
                    assertEquals(ExactNeighborhoodResult.Undecided, attempt.result)
                    assertEquals(0, publicationChecks)
                    assertEquals(center, attempt.feasiblePoint)
                } else {
                    assertEquals(center, assertIs<ExactNeighborhoodResult.Witness>(attempt.result).sample)
                    assertEquals(1, publicationChecks)
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
        val source = assertNotNull(model.linearAuthorityContext(objective, ObjectiveSense.MINIMIZE, Cancellation.Never))
        val seed = Sample(booleanArrayOf(), longArrayOf(1), doubleArrayOf(0.0), exactReals = listOf(BigFraction.ZERO))

        val result = JointLinearRepair(model, source, objective,
            TheoryParams(sourceIncumbentCompletion = true, sourceLpOperations = 1, maxFlips = 1),
            external = { seed }).use { repair ->
            repair.search(adaptive = false).use { search ->
                var done = false
                repeat(32) { if (!done) done = search.advance(Cancellation.Never, 5_000) }
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
        val source = assertNotNull(model.linearAuthorityContext(objective, ObjectiveSense.MINIMIZE, Cancellation.Never))
        val condition = ExactNeighborhoodCondition(emptyMap(), emptyMap(), emptyMap())
        val params = TheoryParams(sourceIncumbentCompletion = true)

        for (limit in listOf("none", "lifetime", "funding")) {
            var cancelled = false
            var checkedValue: BigFraction? = null
            JointLinearRepair(model, source, objective, params,
                publicationLifetime = Cancellation { cancelled },
                incumbentCompletionCheck = { _, value, token ->
                    checkedValue = value
                    token.charge(if (limit == "funding") params.sourceLpWorkLimit else 1L)
                    cancelled = limit == "lifetime"
                    true
                },
            ).use { repair ->
                var result = repair.check(condition, Cancellation.Never)
                repeat(64) {
                    if (result == ExactNeighborhoodResult.Paused) result = repair.check(condition, Cancellation.Never)
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
        val source = assertNotNull(model.linearAuthorityContext(objective, ObjectiveSense.MINIMIZE, Cancellation.Never))
        val endpoint = assertNotNull(BigFraction.ofDouble(1.0000005))
        val condition = ExactNeighborhoodCondition(emptyMap(), mapOf(0 to endpoint), mapOf(0 to endpoint))

        for (checkOriginal in listOf(false, true)) {
            JointLinearRepair(model, source, objective, TheoryParams(sourceIncumbentCompletion = true),
                incumbentCompletionCheck = if (checkOriginal) original.incumbentCompletionCheck(null) else null,
            ).use { repair ->
                var result = repair.check(condition, Cancellation.Never)
                repeat(64) {
                    if (result == ExactNeighborhoodResult.Paused) result = repair.check(condition, Cancellation.Never)
                }

                if (checkOriginal) assertEquals(ExactNeighborhoodResult.Undecided, result)
                else {
                    val point = assertIs<ExactNeighborhoodResult.Witness>(result).sample
                    val accepted = assertIs<LinearSourceCheck.Accepted>(original.check(point, Cancellation.Never))
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
        val source = assertNotNull(model.linearAuthorityContext(objective, ObjectiveSense.MINIMIZE, Cancellation.Never))
        val seed = Sample(booleanArrayOf(), longArrayOf(2), doubleArrayOf(3.0),
            exactReals = listOf(BigFraction.ofLong(3)))
        var consumed = false

        JointLinearRepair(model, source, objective, TheoryParams(sourceIncumbentCompletion = true, maxFlips = 1),
            external = { seed }, onIncumbentUse = { consumed = true }).use { repair ->
            repair.search(adaptive = false).use { search ->
                search.advance(Cancellation.Never, 1)
                assertTrue(repair.incumbentTrialPending)
                var done = false
                repeat(32) { if (!done) done = search.advance(Cancellation.Never, 5_000) }

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
        val source = assertNotNull(model.linearAuthorityContext(objective, ObjectiveSense.MINIMIZE, Cancellation.Never))
        for (exact in listOf(false, true)) {
            val seed = Sample(booleanArrayOf(), longArrayOf(2), doubleArrayOf(0.75),
                exactReals = if (exact) listOf(BigFraction.ofLong(3) * BigFraction.ofLong(4).reciprocal()) else null)
            for (completion in listOf(false, true)) {
                var consumed = false
                JointLinearRepair(model, source, objective, TheoryParams(sourceIncumbentCompletion = completion),
                    external = { seed }, onIncumbentUse = { consumed = true }).use { repair ->
                    repair.search(adaptive = false).use { search ->
                        search.advance(Cancellation.Never, 1)

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
        val source = assertNotNull(model.linearAuthorityContext(objective, ObjectiveSense.MINIMIZE, Cancellation.Never))
        val seed = Sample(booleanArrayOf(), longArrayOf(2), doubleArrayOf(0.75))
        var consumed = false

        JointLinearRepair(model, source, objective, TheoryParams(sourceIncumbentCompletion = true, maxFlips = 1),
            external = { seed }, onIncumbentUse = { consumed = true },
            incumbentCompletionCheck = { _, _, _ -> false }).use { repair ->
            repair.search(adaptive = false).use { search ->
                search.advance(Cancellation.Never, 1)
                assertTrue(repair.incumbentTrialPending)
                var done = false
                repeat(32) { if (!done) done = search.advance(Cancellation.Never, 5_000) }

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
        val source = assertNotNull(model.linearAuthorityContext(objective, ObjectiveSense.MINIMIZE, Cancellation.Never))
        val seed = Sample(booleanArrayOf(), longArrayOf(2), doubleArrayOf(0.75),
            exactReals = listOf(BigFraction.ofLong(3) * BigFraction.ofLong(4).reciprocal()))
        var paused = false

        JointLinearRepair(model, source, objective, TheoryParams(sourceIncumbentCompletion = true, maxFlips = 1),
            external = { seed }, onIncumbentUse = { paused = true }).use { repair ->
            repair.search(adaptive = false).use { search ->
                search.advance(Cancellation { paused }, 5_000)
                assertTrue(repair.incumbentTrialPending)
                paused = false
                var done = false
                repeat(32) { if (!done) done = search.advance(Cancellation.Never, 5_000) }

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
        val source = assertNotNull(model.linearAuthorityContext(objective, ObjectiveSense.MINIMIZE, Cancellation.Never))
        val seed = Sample(booleanArrayOf(), longArrayOf(2), doubleArrayOf(0.75),
            exactReals = listOf(BigFraction.ofLong(3) * BigFraction.ofLong(4).reciprocal()))

        for (completion in listOf(false, true)) {
            val result = JointLinearRepair(model, source, objective,
                TheoryParams(sourceIncumbentCompletion = completion, maxFlips = 1), external = { seed }).use { repair ->
                repair.search(adaptive = false).use { search ->
                    var done = false
                    repeat(32) { if (!done) done = search.advance(Cancellation.Never, 5_000) }
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
        val source = assertNotNull(model.linearAuthorityContext(objective, ObjectiveSense.MINIMIZE, Cancellation.Never))
        val seed = Sample(booleanArrayOf(), longArrayOf(1), doubleArrayOf(0.75))
        val events = mutableListOf<String>()

        JointLinearRepair(model, source, objective, TheoryParams(sourceIncumbentCompletion = true, maxFlips = 1),
            external = { seed }, onIncumbentImport = { events += "import" },
            onIncumbentUse = { events += "use" }).use { repair ->
            repair.search(adaptive = false).use { search ->
                var done = false
                repeat(32) { if (!done) done = search.advance(Cancellation.Never, 1) }
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
        val source = assertNotNull(model.linearAuthorityContext(objective, ObjectiveSense.MINIMIZE, Cancellation.Never))
        val center = Sample(BooleanArray(0), longArrayOf(1, 1))
        val condition = ExactNeighborhoodCondition(emptyMap(), emptyMap(), emptyMap(), BigFraction.ONE)
        JointLinearRepair(model, source, objective, TheoryParams(), external = { center }).use { repair ->
            var result = repair.check(condition, Cancellation.Never)
            repeat(64) {
                if (result == ExactNeighborhoodResult.Paused) result = repair.check(condition, Cancellation.Never)
            }

            val point = assertIs<ExactNeighborhoodResult.Witness>(result).sample
            assertEquals(0L, point.intValue(0))
            assertEquals(0L, point.intValue(1))
        }
    }

    @Test
    fun `an unconsumed improving ray should not report conditioned exhaustion`() {
        val model = Problem(0, 0, emptyArray(), emptyArray(), numRealVars = 1,
            realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(Double.POSITIVE_INFINITY))
        val objective = LinearObjective(realCoefficients = doubleArrayOf(-1.0))
        val source = assertNotNull(model.linearAuthorityContext(objective, ObjectiveSense.MINIMIZE, Cancellation.Never))
        val condition = ExactNeighborhoodCondition(emptyMap(), emptyMap(), emptyMap(), BigFraction.MINUS_ONE)
        JointLinearRepair(model, source, objective, TheoryParams(), boundOrdered = true).use { repair ->
            var attempt = repair.attempt(condition, Cancellation.Never)
            repeat(64) {
                if (attempt.result == ExactNeighborhoodResult.Paused) {
                    attempt = repair.attempt(condition, Cancellation.Never)
                }
            }

            assertEquals(ExactNeighborhoodResult.Undecided, attempt.result)
            assertEquals(TerminationReason.Unsupported, attempt.reason)
        }
    }

    @Test
    fun `attempt should distinguish a slice pause from lifetime budget exhaustion`() {
        val model = Problem(0, intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(1), null, null),
            factors = emptyArray())
        val objective = LinearObjective(intCoefficients = longArrayOf(1))
        val source = assertNotNull(model.linearAuthorityContext(objective, ObjectiveSense.MINIMIZE, Cancellation.Never))
        val condition = ExactNeighborhoodCondition(emptyMap(), emptyMap(), emptyMap())
        JointLinearRepair(model, source, objective, TheoryParams(sourceLpWorkLimit = 0)).use { repair ->
            val result = repair.attempt(condition, Cancellation.Never)

            assertEquals(ExactNeighborhoodResult.Undecided, result.result)
            assertEquals(TerminationReason.BudgetExhausted, result.reason)
        }
        JointLinearRepair(model, source, objective, TheoryParams()).use { repair ->
            val result = repair.attempt(condition, Cancellation { true })

            assertEquals(ExactNeighborhoodResult.Paused, result.result)
            assertEquals(null, result.reason)
            assertEquals(0L, repair.stats.smt.completeSearchStages?.get("jointRepairCandidates") ?: 0L)
        }
    }

    @Test
    fun `attempt should expose a nonimproving feasible center alongside its scoped cutoff closure`() {
        val model = Problem(0, intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(1), null, null),
            factors = emptyArray())
        val objective = LinearObjective(intCoefficients = longArrayOf(1))
        val source = assertNotNull(model.linearAuthorityContext(objective, ObjectiveSense.MINIMIZE, Cancellation.Never))
        val center = Sample(BooleanArray(0), longArrayOf(1))
        val condition = ExactNeighborhoodCondition(emptyMap(), mapOf(0 to BigFraction.ONE),
            mapOf(0 to BigFraction.ONE), BigFraction.ONE)
        JointLinearRepair(model, source, objective, TheoryParams(), boundOrdered = true,
            external = { center }).use { repair ->
            var attempt = repair.attempt(condition, Cancellation.Never)
            repeat(64) {
                if (attempt.result == ExactNeighborhoodResult.Paused) {
                    attempt = repair.attempt(condition, Cancellation.Never)
                }
            }

            assertEquals(ExactNeighborhoodResult.Refuted, attempt.result)
            assertEquals(TerminationReason.SearchExhausted, attempt.reason)
            assertEquals(1L, assertNotNull(attempt.feasiblePoint).intValue(0))
            assertIs<SourceClaim.Bound>(assertNotNull(attempt.bound).claim)
        }
    }

    @Test
    fun `a publication lifetime should retain a checked point after its query slice expires`() {
        val model = Problem(0, intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(2), null, null),
            factors = arrayOf(
                Linear(intArrayOf(0), doubleArrayOf(1.0), intArrayOf(0), doubleArrayOf(-1.0), LinearOp.GE, 0.0),
            ), numRealVars = 1, realLower = doubleArrayOf(0.75), realUpper = doubleArrayOf(2.0))
        val objective = LinearObjective(intCoefficients = longArrayOf(1))
        val source = assertNotNull(model.linearAuthorityContext(objective, ObjectiveSense.MINIMIZE, Cancellation.Never))
        val condition = ExactNeighborhoodCondition(emptyMap(), mapOf(0 to BigFraction.ONE),
            mapOf(0 to BigFraction.ONE))
        for (atomic in listOf(false, true)) {
            JointLinearRepair(model, source, objective, TheoryParams(),
                publicationLifetime = if (atomic) Cancellation.Never else null).use { repair ->
                val slice = Cancellation {
                    (repair.stats.smt.completeSearchStages?.get("jointRepairClosedLeaves") ?: 0L) > 0
                }

                var result = repair.check(condition, slice)
                repeat(64) { if (result == ExactNeighborhoodResult.Paused) result = repair.check(condition, slice) }

                assertTrue(slice())
                if (atomic) assertEquals(1L, assertIs<ExactNeighborhoodResult.Witness>(result).sample.intValue(0))
                else assertEquals(ExactNeighborhoodResult.Paused, result)
            }
        }
    }

    @Test
    fun `a cancelled publication lifetime should withhold the completed point`() {
        val model = Problem(0, intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(2), null, null),
            factors = arrayOf(
                Linear(intArrayOf(0), doubleArrayOf(1.0), intArrayOf(0), doubleArrayOf(-1.0), LinearOp.GE, 0.0),
            ), numRealVars = 1, realLower = doubleArrayOf(0.75), realUpper = doubleArrayOf(2.0))
        val objective = LinearObjective(intCoefficients = longArrayOf(1))
        val source = assertNotNull(model.linearAuthorityContext(objective, ObjectiveSense.MINIMIZE, Cancellation.Never))
        val condition = ExactNeighborhoodCondition(emptyMap(), mapOf(0 to BigFraction.ONE),
            mapOf(0 to BigFraction.ONE))
        lateinit var repair: JointLinearRepair
        val lifetime = Cancellation {
            (repair.stats.smt.completeSearchStages?.get("jointRepairClosedLeaves") ?: 0L) > 0
        }
        repair = JointLinearRepair(model, source, objective, TheoryParams(), publicationLifetime = lifetime)

        val result = repair.use { current ->
            var result = current.check(condition, Cancellation.Never)
            repeat(64) {
                if (result == ExactNeighborhoodResult.Paused) result = current.check(condition, Cancellation.Never)
            }
            result
        }

        assertTrue(lifetime())
        assertEquals(ExactNeighborhoodResult.Undecided, result)
    }

    @Test
    fun `joint repair should find a checked point within a priced LP node allowance`() {
        val model = Problem(0, intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(2), null, null),
            factors = arrayOf(
                Linear(intArrayOf(0), doubleArrayOf(1.0), intArrayOf(0), doubleArrayOf(-1.0), LinearOp.GE, 0.0),
            ), numRealVars = 1, realLower = doubleArrayOf(0.75), realUpper = doubleArrayOf(2.0))
        val objective = LinearObjective(intCoefficients = longArrayOf(1))
        val source = assertNotNull(model.linearAuthorityContext(objective, ObjectiveSense.MINIMIZE, Cancellation.Never))
        val repair = JointLinearRepair(model, source, objective,
            TheoryParams(maxFlips = 1, nodeBudget = NodeBudget(1000)))

        val result = repair.use {
            it.search(adaptive = false).use { search ->
                while (!search.advance(Cancellation.Never, 1000)) Unit
                search.minimizeResult(TerminationReason.BudgetExhausted)
            }
        }

        assertEquals(1L, assertNotNull(result.assignment).intValue(0))
    }

    @Test
    fun `joint repair should consume a sparse residual rejected by small scalar admission`() {
        val model = Problem(0, intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(1), null, null),
            factors = arrayOf(
                Linear(intArrayOf(), doubleArrayOf(), IntArray(65) { it }, DoubleArray(65) { 3.141592653589793 },
                    LinearOp.EQ, 0.1),
            ), numRealVars = 129, realLower = DoubleArray(129), realUpper = DoubleArray(129) { 1.0 })
        val objective = LinearObjective(intCoefficients = longArrayOf(1))
        val source = assertNotNull(model.linearAuthorityContext(objective, ObjectiveSense.MINIMIZE, Cancellation.Never))
        val condition = ExactNeighborhoodCondition(emptyMap(), mapOf(0 to BigFraction.ZERO),
            mapOf(0 to BigFraction.ZERO))

        JointLinearRepair(model, source, objective, TheoryParams(maxFlips = 1)).use { repair ->
            var result = repair.check(condition, Cancellation.Never)
            repeat(64) {
                if (result == ExactNeighborhoodResult.Paused) result = repair.check(condition, Cancellation.Never)
            }

            val sample = assertIs<ExactNeighborhoodResult.Witness>(result).sample
            val stages = assertNotNull(repair.stats.smt.completeSearchStages)
            assertEquals(1L, stages["jointRepairPrimalRecoveryExactAdmissionInputBits"])
            assertEquals(1L, stages["jointRepairPrimalRecoverySparseCompleted"])
            assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(source).verify(
                SourceEvidence(source.scope, SourceClaim.Witness(sample), SourceDerivation.SourceWitness)))
        }
    }

    @Test
    fun `wide repair refutations should discharge only their pinned candidate and account certificate work`() {
        val model = Problem(0, intBounds = IntBounds.fromModelBounds(longArrayOf(0, 0), longArrayOf(1, 1), null, null),
            factors = arrayOf(
                Linear(intArrayOf(0), doubleArrayOf(1.0), intArrayOf(0), doubleArrayOf(-1.0), LinearOp.EQ, 0.0),
                Linear(intArrayOf(1), doubleArrayOf(1.0), intArrayOf(0), doubleArrayOf(-1.0), LinearOp.EQ, 0.0),
            ), numRealVars = 127, realLower = DoubleArray(127) { 0.5 }, realUpper = DoubleArray(127) { 1.0 })
        val objective = LinearObjective(realCoefficients = DoubleArray(127) { if (it == 0) 1.0 else 0.0 })
        val source = assertNotNull(model.linearAuthorityContext(objective, ObjectiveSense.MINIMIZE, Cancellation.Never))
        JointLinearRepair(model, source, objective, TheoryParams(), boundOrdered = true).use { repair ->
            val condition = ExactNeighborhoodCondition(emptyMap(), mapOf(0 to BigFraction.ZERO),
                mapOf(0 to BigFraction.ZERO))
            var result = repair.check(condition, Cancellation.Never)
            repeat(64) {
                if (result == ExactNeighborhoodResult.Paused) result = repair.check(condition, Cancellation.Never)
            }

            assertEquals(ExactNeighborhoodResult.Refuted, result)
            assertEquals(1L, repair.stats.smt.completeSearchStages?.get("jointRepairLpConflicts"))
            assertTrue(repair.stats.smt.sourceLp.conflictCertificationWork > 0L)
            val feasible = ExactNeighborhoodCondition(emptyMap(), mapOf(0 to BigFraction.ONE, 1 to BigFraction.ONE),
                mapOf(0 to BigFraction.ONE, 1 to BigFraction.ONE))
            result = repair.check(feasible, Cancellation.Never)
            repeat(64) {
                if (result == ExactNeighborhoodResult.Paused) result = repair.check(feasible, Cancellation.Never)
            }

            val witness = assertIs<ExactNeighborhoodResult.Witness>(result).sample
            assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(source).verify(
                SourceEvidence(source.scope, SourceClaim.Witness(witness), SourceDerivation.SourceWitness)))
        }
    }

    @Test
    fun `joint repair should consume a recovered continuous residual beyond full exact admission`() {
        val model = Problem(0, intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(1), null, null),
            factors = arrayOf(
                Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0, 1), doubleArrayOf(3.0, 3.0), LinearOp.EQ, 0.1),
            ), numRealVars = 129, realLower = DoubleArray(129), realUpper = DoubleArray(129) { 1.0 })
        val objective = LinearObjective(intCoefficients = longArrayOf(1))
        val source = assertNotNull(model.linearAuthorityContext(objective, ObjectiveSense.MINIMIZE, Cancellation.Never))
        val repair = JointLinearRepair(model, source, objective, TheoryParams(maxFlips = 1))
        val condition = ExactNeighborhoodCondition(emptyMap(), mapOf(0 to BigFraction.ZERO),
            mapOf(0 to BigFraction.ZERO))

        val result = repair.use { current ->
            var result = current.check(condition, Cancellation.Never)
            repeat(64) {
                if (result == ExactNeighborhoodResult.Paused) result = current.check(condition, Cancellation.Never)
            }
            result
        }

        val sample = assertIs<ExactNeighborhoodResult.Witness>(result).sample
        val reals = assertNotNull(sample.exactReals)
        assertEquals(checkNotNull(BigFraction.ofDouble(0.1)), (reals[0] + reals[1]) * BigFraction.ofLong(3))
        assertEquals(1L, repair.stats.smt.completeSearchStages?.get("jointRepairPrimalRecoverySourceChecked"))
        assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(source).verify(
            SourceEvidence(source.scope, SourceClaim.Witness(sample), SourceDerivation.SourceWitness)))
    }

    @Test
    fun `a checked incumbent should close a nonimproving repair without global proof`() {
        val model = Problem(0, intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(2), null, null),
            factors = arrayOf(
                Linear(intArrayOf(0), doubleArrayOf(1.0), intArrayOf(0), doubleArrayOf(-1.0), LinearOp.GE, 0.0),
            ), numRealVars = 1, realLower = doubleArrayOf(0.75), realUpper = doubleArrayOf(2.0))
        val objective = LinearObjective(intCoefficients = longArrayOf(1))
        val source = assertNotNull(model.linearAuthorityContext(objective, ObjectiveSense.MINIMIZE, Cancellation.Never))
        for (inputPass in listOf(false, true)) {
            val repair = JointLinearRepair(model, source, objective, TheoryParams(maxFlips = 2),
                sourceIntegers = intArrayOf(0))

            val result = repair.use {
                it.search(adaptive = false, sourceInputPass = inputPass).use { search ->
                    while (!search.advance(Cancellation.Never, 1_000_000)) Unit
                    search.minimizeResult(TerminationReason.BudgetExhausted)
                }
            }

            assertIs<MinimizeResult.BestFound>(result)
            assertEquals(1L, assertNotNull(result.assignment).intValue(0))
            assertEquals(1.0, result.stats.ls.completionsRefuted.sum)
            assertEquals(1L, result.stats.smt.completeSearchStages?.get("jointRepairCutoffClosures"))
        }
    }

    @Test
    fun `paused guidance should keep its check allowance through incumbent publication`() {
        val model = Problem(0, intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(2), null, null),
            factors = arrayOf(
                Linear(intArrayOf(0), doubleArrayOf(1.0), intArrayOf(0), doubleArrayOf(-1.0), LinearOp.GE, 0.0),
            ), numRealVars = 1, realLower = doubleArrayOf(0.75), realUpper = doubleArrayOf(2.0))
        val objective = LinearObjective(intCoefficients = longArrayOf(1))
        val source = assertNotNull(model.linearAuthorityContext(objective, ObjectiveSense.MINIMIZE, Cancellation.Never))
        for (boundOrdered in listOf(false, true)) {
            val repair = JointLinearRepair(model, source, objective,
                TheoryParams(maxFlips = 1, maxLeaves = 2, openWorkLimit = 2), SparseSourceLpPolicy(quantum = 1L),
                boundOrdered = boundOrdered)

            val result = repair.use {
                it.search(adaptive = false).use { search ->
                    assertFalse(search.advance(Cancellation.Never, 1))
                    assertEquals(0.0, search.stats.ls.completions.sum)
                    var done = false
                    repeat(64) { if (!done) done = search.advance(Cancellation.Never, 1) }
                    assertTrue(done)
                    search.minimizeResult(TerminationReason.BudgetExhausted)
                }
            }

            assertEquals(1L, assertNotNull(result.assignment).intValue(0))
            assertEquals(2L, result.stats.openTheory.openTheoryChecks)
            assertTrue((result.stats.smt.completeSearchStages?.get("jointRepairRootPauses") ?: 0) > 0)
        }
    }

    @Test
    fun `the first repair should round the LP guide rather than the declared lower bound`() {
        val model = Problem(0, intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(2), null, null),
            factors = arrayOf(
                Linear(intArrayOf(0), doubleArrayOf(1.0), intArrayOf(0), doubleArrayOf(-1.0), LinearOp.GE, 0.0),
            ), numRealVars = 1, realLower = doubleArrayOf(0.75), realUpper = doubleArrayOf(2.0))
        val objective = LinearObjective(intCoefficients = longArrayOf(1))
        val source = assertNotNull(model.linearAuthorityContext(objective, ObjectiveSense.MINIMIZE, Cancellation.Never))
        val repair = JointLinearRepair(model, source, objective, TheoryParams(maxFlips = 1))

        val result = repair.use {
            it.search(adaptive = false).use { search ->
                while (!search.advance(Cancellation.Never, 1_000_000)) Unit
                search.minimizeResult(TerminationReason.BudgetExhausted)
            }
        }

        val sample = assertNotNull(result.assignment)
        assertEquals(1L, sample.intValue(0))
    }

    @Test
    fun `joint repair should change coupled integers and complete their real residual after rounding fails`() {
        val model = Problem(0, intBounds = IntBounds.fromModelBounds(longArrayOf(0, 0), longArrayOf(1, 1), null, null),
            factors = arrayOf(
                Linear(intArrayOf(0), doubleArrayOf(1.0), intArrayOf(0), doubleArrayOf(-1.0), LinearOp.EQ, 0.0),
                Linear(intArrayOf(1), doubleArrayOf(1.0), intArrayOf(0), doubleArrayOf(-1.0), LinearOp.EQ, 0.0),
            ), numRealVars = 1, realLower = doubleArrayOf(0.5), realUpper = doubleArrayOf(1.0))
        val objective = LinearObjective(realCoefficients = doubleArrayOf(1.0))
        val source = assertNotNull(model.linearAuthorityContext(objective, ObjectiveSense.MINIMIZE, Cancellation.Never))
        val repair = JointLinearRepair(model, source, objective, TheoryParams(maxFlips = 2))

        val result = repair.use {
            it.search(adaptive = false).use { search ->
                while (!search.advance(Cancellation.Never, 1_000_000)) Unit
                search.minimizeResult(TerminationReason.BudgetExhausted)
            }
        }

        val sample = assertNotNull(result.assignment)
        assertEquals(listOf(1L, 1L), sample.ints.toList())
        assertEquals(listOf(BigFraction.ONE), sample.exactReals)
        assertEquals(1.0, result.stats.ls.completionsRefuted.sum)
        assertEquals(1L, result.stats.smt.completeSearchStages?.get("jointRepairCheckedIncumbents"))
        assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(source).verify(
            SourceEvidence(source.scope, SourceClaim.Witness(sample), SourceDerivation.SourceWitness)))
    }

    @Test
    fun `exhausted joint neighborhoods should keep an unknown source result`() {
        val model = Problem(0, intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(0), null, null),
            factors = arrayOf(Linear(longArrayOf(1), intArrayOf(0), LinearOp.GE, 1)))
        val source = assertNotNull(model.linearAuthorityContext(LinearObjective(), ObjectiveSense.MINIMIZE,
            Cancellation.Never))
        val repair = JointLinearRepair(model, source, null, TheoryParams(maxFlips = 2))

        val result = repair.use {
            it.search(adaptive = false).use { search ->
                while (!search.advance(Cancellation.Never, 1_000_000)) Unit
                search.solveResult(TerminationReason.BudgetExhausted)
            }
        }

        assertIs<SolveResult.Unknown>(result)
        assertTrue(result.stats.ls.completionsRefuted.sum > 0)
    }
}
