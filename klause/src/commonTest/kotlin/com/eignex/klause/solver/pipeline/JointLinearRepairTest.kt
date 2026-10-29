package com.eignex.klause.solver.pipeline

import com.eignex.klause.backtrack.NodeBudget
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.Problem
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
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JointLinearRepairTest {
    @Test
    fun `a checked center trial should retain its condition after a publication pause`() {
        val model = Problem(0, intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(2), null, null),
            factors = arrayOf(
                Linear(intArrayOf(0), doubleArrayOf(1.0), intArrayOf(0), doubleArrayOf(-1.0), LinearOp.GE, 0.0),
            ), numRealVars = 1, realLower = doubleArrayOf(0.75), realUpper = doubleArrayOf(2.0))
        val objective = LinearObjective(intCoefficients = longArrayOf(1))
        val source = assertNotNull(model.linearAuthorityContext(objective, ObjectiveSense.MINIMIZE, Cancellation.Never))
        val seed = Sample(booleanArrayOf(), longArrayOf(2), doubleArrayOf(0.75),
            exactReals = listOf(BigFraction.ofLong(3) / BigFraction.ofLong(4)))
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
            exactReals = listOf(BigFraction.ofLong(3) / BigFraction.ofLong(4)))

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
