package com.eignex.klause.lp.optimization

import com.eignex.klause.RuntimeInitializedTest
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.lp.SourceLpBudget
import com.eignex.klause.lp.SourceLpFunding
import com.eignex.klause.lp.engine.CutFamily
import com.eignex.klause.solver.evidence.SourceClaim
import com.eignex.klause.solver.evidence.SourceCondition
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.solver.evidence.SourceDerivation
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinearAuthorityReplayTest : RuntimeInitializedTest() {
    private val x = SourceCoordinate(SourceCoordinate.Kind.Integer, 0)
    private val y = SourceCoordinate(SourceCoordinate.Kind.Integer, 1)
    private val r = SourceCoordinate(SourceCoordinate.Kind.Real, 0)
    private val s = SourceCoordinate(SourceCoordinate.Kind.Real, 1)
    private val half = BigFraction.ofLong(2).reciprocal()
    private val source = SourceProofContext(
        SourceScope(SourceRevision("captured-replay", "exact-affine"),
            objective = SourceObjective("x+2y", ObjectiveSense.MINIMIZE)),
        setOf(x, y, r, s), mapOf(
            "x-plus" to SourcePremise(SourceInequality(mapOf(x to BigFraction.MINUS_ONE, r to BigFraction.ONE),
                half.negated())),
            "x-minus" to SourcePremise(SourceInequality(mapOf(x to BigFraction.MINUS_ONE, r to BigFraction.MINUS_ONE),
                half.negated())),
            "y-plus" to SourcePremise(SourceInequality(mapOf(y to BigFraction.MINUS_ONE, s to BigFraction.ONE),
                half.negated())),
            "y-minus" to SourcePremise(SourceInequality(mapOf(y to BigFraction.MINUS_ONE, s to BigFraction.MINUS_ONE),
                half.negated())),
        ), mapOf(x to BigFraction.ONE, y to BigFraction.ofLong(2)),
    )
    private val cuts = listOf(
        LinearAuthorityCut(0, SourceInequality(mapOf(x to BigFraction.MINUS_ONE), BigFraction.MINUS_ONE),
            SourceDerivation.IntegerRounding(SourceDerivation.Combination(listOf(
                SourceDerivation.Combination.Term(half, SourceDerivation.Premise("x-plus")),
                SourceDerivation.Combination.Term(half, SourceDerivation.Premise("x-minus")),
            ))), owners = setOf(0, 1), family = CutFamily.SOURCE_AGGREGATION),
        LinearAuthorityCut(2, SourceInequality(mapOf(y to BigFraction.MINUS_ONE), BigFraction.MINUS_ONE),
            SourceDerivation.IntegerRounding(SourceDerivation.Combination(listOf(
                SourceDerivation.Combination.Term(half, SourceDerivation.Premise("y-plus")),
                SourceDerivation.Combination.Term(half, SourceDerivation.Premise("y-minus")),
            ))), owners = setOf(2, 3), family = CutFamily.SOURCE_AGGREGATION),
    )

    @Test
    fun `a checked conditional candidate should refute receiving bounds without LP installation`() {
        val restricted = source.atScope(source.scope.copy(condition = SourceCondition(
            upper = mapOf(x to BigFraction.ZERO))))
        val original = cuts.first()
        val candidate = LinearAuthorityCut(original.owner, original.inequality, original.derivation,
            owners = original.owners, family = original.family, conditionalCandidate = true)
        val budget = SourceLpBudget()

        val result = LinearAuthority(budget, retainLp = true).use {
            it.useReplayCuts(listOf(candidate))
            it.advance(restricted, ExecutionControl.Never, cuts = true, cutMask = emptySet())
        }

        assertEquals(SourceEvidenceCheck.Checked,
            SourceEvidenceVerifier(restricted.withLinearAuthority()).verify(assertNotNull(result.evidence)))
        assertEquals(setOf(CutFamily.SOURCE_AGGREGATION), result.cutConflictFamilies)
        assertEquals(0, budget.operations)
    }

    @Test
    fun `capture replay should preserve an uninstalled conditional candidate`() {
        val restricted = source.atScope(source.scope.copy(condition = SourceCondition(
            upper = mapOf(x to BigFraction.ZERO))))
        val original = cuts.first()
        val candidate = LinearAuthorityCut(original.owner, original.inequality, original.derivation,
            owners = original.owners, family = original.family, conditionalCandidate = true)
        val capture = LinearAuthorityCapture(1, 10_000L, 1_000_000L, ExecutionControl.Never)
        LinearAuthority(SourceLpBudget(), retainLp = true).use {
            it.useReplayCuts(listOf(candidate))
            it.capture = capture
            it.advance(restricted, ExecutionControl.Never, cuts = true, cutMask = emptySet())
        }

        val replay = LinearAuthorityReplay.run(capture.records.single(), emptySet(),
            1_000_000L, 4_000_000L, ExecutionControl.Never)

        val query = assertNotNull(replay.query)
        assertTrue(query.cuts.single().conditionalCandidate)
        assertEquals(false, query.cuts.single().installed)
        assertEquals(0, replay.operations)
        assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(restricted.withLinearAuthority())
            .verify(assertNotNull(query.refutation)))
    }

    @Test
    fun `pausing retained conditional preparation should withhold the query until another grant`() {
        val original = cuts.first()
        val candidate = LinearAuthorityCut(original.owner, original.inequality, original.derivation,
            owners = original.owners, family = original.family, conditionalCandidate = true)
        val template = assertNotNull(LinearAuthorityTemplate.prepare(source, ExecutionControl.Never))
            .withReplayCuts(listOf(candidate), ExecutionControl.Never) { _, _ -> }
        val funding = SourceLpFunding(1_000_000L, 4_000_000L).apply { grant(1_000_000L) }
        val control = ExecutionControl { funding.paused }

        val paused = LinearAuthorityQuery.prepare(source, assertNotNull(template), control,
            cutMask = emptySet(), checkLpCandidates = false, onCutCost = { work, bytes ->
                funding.charge(work, bytes)
                funding.grant(0L)
            })
        funding.grant(1_000_000L)
        val resumed = assertNotNull(LinearAuthorityQuery.prepare(source, template, control,
            cutMask = emptySet(), checkLpCandidates = false, onCutCost = funding::charge))

        assertNull(paused)
        assertTrue(resumed.installedCuts.isEmpty())
        assertEquals(1, resumed.conditionalCutFamilies.size)
        assertTrue(resumed.conditionalCutFamilies.keys.all { it in resumed.inactive })
    }

    @Test
    fun `LP discovery retirement should preserve a conditional receiving conflict`() {
        val restricted = source.atScope(source.scope.copy(condition = SourceCondition(
            upper = mapOf(x to BigFraction.ZERO))))
        val original = cuts.first()
        val candidate = LinearAuthorityCut(original.owner, original.inequality, original.derivation,
            owners = original.owners, family = original.family, conditionalCandidate = true)
        val candidates = listOf(candidate)
        val selection = LinearAuthorityPointCuts(SourceLpBudget())
        repeat(25) {
            selection.select(candidates, listOf(x, y, r, s),
                listOf(BigFraction.ONE, BigFraction.ofLong(2), BigFraction.ZERO, BigFraction.ZERO), setOf(0),
                listOf(BigFraction.ONE, BigFraction.ONE, BigFraction.ZERO, BigFraction.ZERO), emptySet(),
                ExecutionControl.Never)
        }
        assertTrue(selection.candidatesRetired(candidates))
        val template = assertNotNull(LinearAuthorityTemplate.prepare(source, ExecutionControl.Never))
            .withReplayCuts(candidates, ExecutionControl.Never) { _, _ -> }
        val query = assertNotNull(LinearAuthorityQuery.prepare(restricted, assertNotNull(template),
            ExecutionControl.Never, cutMask = emptySet(),
            checkLpCandidates = !selection.candidatesRetired(candidates)))

        val conflict = assertNotNull(query.selectedCutConflict(ExecutionControl.Never, { _, _ -> }))

        assertTrue(query.installedCuts.isEmpty())
        assertTrue(query.cutFamilies.isEmpty())
        assertEquals(1, query.conditionalCutFamilies.size)
        assertTrue(conflict.retainedConditional)
        assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(restricted.withLinearAuthority())
            .verify(SourceEvidence(restricted.scope, SourceClaim.Refutation, LinearPinnedRefutation(conflict.proof))))
    }

    @Test
    fun `conditional retention should not replace either of its first two definitions`() {
        val restricted = source.atScope(source.scope.copy(condition = SourceCondition(
            upper = mapOf(x to BigFraction.ONE, y to BigFraction.ZERO))))
        for ((checkCandidates, validFirst) in listOf(false, true).flatMap { checkCandidates ->
            listOf(checkCandidates to false, checkCandidates to true)
        }) {
            val original = cuts.first()
            val first = LinearAuthorityCut(original.owner, original.inequality,
                if (validFirst) original.derivation else SourceDerivation.Premise("missing"),
                owners = original.owners, family = original.family, conditionalCandidate = true)
            val candidates = listOf(first) + cuts.map { cut ->
                LinearAuthorityCut(cut.owner, cut.inequality, cut.derivation,
                    owners = cut.owners, family = cut.family, conditionalCandidate = true)
            }
            val template = assertNotNull(LinearAuthorityTemplate.prepare(source, ExecutionControl.Never))
                .withReplayCuts(candidates, ExecutionControl.Never) { _, _ -> }

            val query = assertNotNull(LinearAuthorityQuery.prepare(restricted, assertNotNull(template),
                ExecutionControl.Never, cutMask = emptySet(), checkLpCandidates = checkCandidates))
            val conflict = query.selectedCutConflict(ExecutionControl.Never, { _, _ -> })

            assertEquals(if (validFirst) setOf(0, 1) else setOf(1), query.checkedCuts)
            assertEquals(if (validFirst) 2 else 1, query.conditionalCutFamilies.size)
            assertTrue(query.installedCuts.isEmpty())
            assertNull(conflict)
        }
    }

    @Test
    fun `retained conditional definitions should retract with an inactive source owner`() {
        val guarded = SourceProofContext(source.scope.copy(condition = SourceCondition(
            bools = mapOf(0 to false), upper = mapOf(x to BigFraction.ZERO))),
            source.coordinates + SourceCoordinate(SourceCoordinate.Kind.Boolean, 0),
            source.premises.mapValues { (id, premise) ->
                SourcePremise(premise.inequality, if (id == "x-plus") mapOf(0 to true) else emptyMap())
            }, source.objective)
        val original = cuts.first()
        val candidate = LinearAuthorityCut(original.owner, original.inequality, original.derivation,
            owners = original.owners, family = original.family, conditionalCandidate = true)
        val template = assertNotNull(LinearAuthorityTemplate.prepare(guarded, ExecutionControl.Never))
            .withReplayCuts(listOf(candidate), ExecutionControl.Never) { _, _ -> }

        val query = assertNotNull(LinearAuthorityQuery.prepare(guarded, assertNotNull(template),
            ExecutionControl.Never, cutMask = emptySet(), checkLpCandidates = false))

        assertTrue(query.checkedCuts.isEmpty())
        assertTrue(query.conditionalCutFamilies.isEmpty())
        assertNull(query.selectedCutConflict(ExecutionControl.Never, { _, _ -> }))
    }

    @Test
    fun `an exact conflict fallback should preserve a selected recipe when its proof is unavailable`() {
        val restricted = SourceProofContext(source.scope, source.coordinates, source.premises +
            ("sum upper" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ONE, y to BigFraction.ONE),
                BigFraction.ofLong(3) * half))), source.objective)
        var proof: SourceDerivation = SourceDerivation.Combination(cuts.map {
            SourceDerivation.Combination.Term(BigFraction.ONE, it.derivation)
        })
        // The cut fits the proof depth limit; its surrounding refutation does not.
        repeat(123) {
            proof = SourceDerivation.Combination(listOf(SourceDerivation.Combination.Term(BigFraction.ONE, proof)))
        }
        val cut = LinearAuthorityCut(0,
            SourceInequality(mapOf(x to BigFraction.MINUS_ONE, y to BigFraction.MINUS_ONE), BigFraction.ofLong(-2)),
            proof, owners = setOf(0, 1, 2, 3), family = CutFamily.SOURCE_AGGREGATION)
        val budget = SourceLpBudget()

        val result = LinearAuthority(budget, retainLp = true).use {
            it.useReplayCuts(listOf(cut))
            it.advance(restricted, ExecutionControl.Never, cuts = true, cutMask = setOf(0))
        }

        assertEquals(1L, result.primalStages["ConflictExactFallback"])
        assertNull(result.evidence)
        assertNull(result.incumbent)
        assertNull(result.split)
    }

    @Test
    fun `only active source rows should supply missing sides for a selected cut conflict`() {
        for (active in listOf(false, true)) {
            val restricted = SourceProofContext(source.scope.copy(condition = SourceCondition(
                bools = mapOf(0 to active), lower = mapOf(y to BigFraction.ONE))),
                source.coordinates + SourceCoordinate(SourceCoordinate.Kind.Boolean, 0),
                source.premises + ("sum upper" to SourcePremise(SourceInequality(
                    mapOf(x to BigFraction.ONE, y to BigFraction.ONE), BigFraction.ofLong(3) * half),
                    guards = mapOf(0 to true))), source.objective)
            val budget = SourceLpBudget()

            val result = LinearAuthority(budget, retainLp = true).use {
                it.useReplayCuts(cuts)
                it.advance(restricted, ExecutionControl.Never, cuts = true, cutMask = setOf(0))
            }

            assertEquals(if (active) 1L else null, result.primalStages["SelectedCutBoundConflicts"])
            if (active) {
                assertEquals(0, budget.operations)
                assertEquals(SourceEvidenceCheck.Checked,
                    SourceEvidenceVerifier(restricted.withLinearAuthority()).verify(assertNotNull(result.evidence)))
            } else assertTrue(budget.operations > 0)
        }
    }

    @Test
    fun `a projected side should preserve strictness at zero residual`() {
        for (strict in listOf(false, true)) {
            val restricted = SourceProofContext(source.scope.copy(condition = SourceCondition(
                lower = mapOf(y to BigFraction.ONE))), source.coordinates,
                source.premises + ("sum upper" to SourcePremise(SourceInequality(
                    mapOf(x to BigFraction.ONE, y to BigFraction.ONE), BigFraction.ofLong(2), strict))),
                source.objective)
            val budget = SourceLpBudget()

            val result = LinearAuthority(budget, retainLp = true).use {
                it.useReplayCuts(cuts)
                it.advance(restricted, ExecutionControl.Never, cuts = true, cutMask = setOf(0))
            }

            assertEquals(if (strict) 1L else null, result.primalStages["SelectedCutBoundConflicts"])
            if (strict) {
                assertEquals(0, budget.operations)
                assertEquals(SourceEvidenceCheck.Checked,
                    SourceEvidenceVerifier(restricted.withLinearAuthority()).verify(assertNotNull(result.evidence)))
            } else assertTrue(result.evidence?.claim != SourceClaim.Refutation)
        }
    }

    @Test
    fun `an interrupted optional scan should leave the next grant for the ordinary LP`() {
        val restricted = SourceProofContext(source.scope.copy(condition = SourceCondition(
            lower = mapOf(y to BigFraction.ONE))), source.coordinates,
            source.premises + ("sum upper" to SourcePremise(SourceInequality(
                mapOf(x to BigFraction.ONE, y to BigFraction.ONE), BigFraction.ofLong(3) * half))),
            source.objective)
        val funding = SourceLpFunding(10_000_000L, 4_000_000L).apply { grant(1_000_000L) }
        var interrupted = false
        val capture = LinearAuthorityCapture(1, 10_000L, 1_000_000L, ExecutionControl {
            if (!interrupted) {
                interrupted = true
                funding.grant(0L)
            }
            false
        })
        val budget = SourceLpBudget(funding = funding)
        LinearAuthority(budget, retainLp = true).use { owner ->
            owner.capture = capture
            owner.useReplayCuts(cuts)
            val partial = owner.advance(restricted, ExecutionControl.Never, cuts = true, cutMask = setOf(0))
            assertTrue(interrupted && partial.paused)
            assertEquals(0, budget.operations)
            funding.grant(1_000_000L)

            val resumed = owner.advance(restricted, ExecutionControl.Never, cuts = true, cutMask = setOf(0))

            assertTrue(budget.operations > 0)
            assertNull(resumed.primalStages["SelectedCutBoundConflicts"])
            assertEquals(SourceEvidenceCheck.Checked,
                SourceEvidenceVerifier(restricted.withLinearAuthority()).verify(assertNotNull(resumed.evidence)))
        }
    }

    @Test
    fun `a projected side should not supply another projection`() {
        val z = SourceCoordinate(SourceCoordinate.Kind.Real, 2)
        val restricted = SourceProofContext(source.scope.copy(condition = SourceCondition(
            upper = mapOf(z to BigFraction.ZERO))), source.coordinates + z,
            source.premises + mapOf(
                "x upper" to SourcePremise(SourceInequality(
                    mapOf(x to BigFraction.ONE, z to BigFraction.MINUS_ONE), BigFraction.ZERO)),
                "y upper" to SourcePremise(SourceInequality(
                    mapOf(y to BigFraction.ONE, x to BigFraction.MINUS_ONE), BigFraction.ZERO)),
            ), source.objective)
        val combined = LinearAuthorityCut(0, SourceInequality(
            mapOf(x to BigFraction.MINUS_ONE, y to BigFraction.MINUS_ONE), BigFraction.ofLong(-2)),
            SourceDerivation.Combination(cuts.map {
                SourceDerivation.Combination.Term(BigFraction.ONE, it.derivation)
            }), owners = setOf(0, 1, 2, 3), family = CutFamily.SOURCE_AGGREGATION)
        val budget = SourceLpBudget()

        val result = LinearAuthority(budget, retainLp = true).use {
            it.useReplayCuts(listOf(combined))
            it.advance(restricted, ExecutionControl.Never, cuts = true, cutMask = setOf(0))
        }

        assertNull(result.primalStages["SelectedCutBoundConflicts"])
        assertTrue(budget.operations > 0)
    }

    @Test
    fun `an interrupted cut scan should leave the checked query reusable`() {
        val restricted = source.atScope(source.scope.copy(condition = SourceCondition(
            upper = mapOf(x to BigFraction.ZERO))))
        for ((conditional, cancelled) in listOf(false, true).flatMap { conditional ->
            listOf(conditional to false, conditional to true)
        }) {
            val original = cuts.first()
            val candidate = LinearAuthorityCut(original.owner, original.inequality, original.derivation,
                owners = original.owners, family = original.family, conditionalCandidate = conditional)
            val template = assertNotNull(LinearAuthorityTemplate.prepare(source, ExecutionControl.Never))
                .withReplayCuts(listOf(candidate), ExecutionControl.Never) { _, _ -> }
            val query = assertNotNull(LinearAuthorityQuery.prepare(restricted, assertNotNull(template),
                ExecutionControl.Never, cutMask = if (conditional) emptySet() else setOf(0),
                checkLpCandidates = false))
            val funding = SourceLpFunding(1_000_000L, 4_000_000L).apply { grant(1_000_000L) }
            var stopped = false
            val control = ExecutionControl { stopped || funding.paused }

            val interrupted = query.selectedCutConflict(control, onCost = { work, bytes ->
                funding.charge(work, bytes)
                if (funding.spent >= 8L) {
                    if (cancelled) stopped = true else funding.grant(0L)
                }
            })
            assertNull(interrupted)
            stopped = false
            funding.grant(1_000_000L)
            val resumed = assertNotNull(query.selectedCutConflict(control, funding::charge))

            val evidence = SourceEvidence(restricted.scope, SourceClaim.Refutation,
                LinearPinnedRefutation(resumed.proof))
            assertEquals(SourceEvidenceCheck.Checked,
                SourceEvidenceVerifier(restricted.withLinearAuthority()).verify(evidence))
        }
    }

    @Test
    fun `an unverified selected cut should not authorize an early conflict`() {
        val restricted = source.atScope(source.scope.copy(condition = SourceCondition(
            upper = mapOf(x to BigFraction.ZERO))))
        val original = cuts.first()
        for (conditional in listOf(false, true)) {
            val budget = SourceLpBudget()
            val forged = LinearAuthorityCut(original.owner, original.inequality, SourceDerivation.Premise("missing"),
                owners = original.owners, family = original.family, conditionalCandidate = conditional)

            val result = LinearAuthority(budget, retainLp = true).use {
                it.useReplayCuts(listOf(forged))
                it.advance(restricted, ExecutionControl.Never, cuts = true,
                    cutMask = if (conditional) emptySet() else setOf(0))
            }

            assertTrue(result.cutConflictFamilies.isEmpty())
            assertNull(result.primalStages["SelectedCutBoundConflicts"])
            assertTrue(budget.operations > 0)
        }
    }

    @Test
    fun `a selected cut should retract when a source owner is inactive`() {
        val guarded = SourceProofContext(source.scope.copy(condition = SourceCondition(
            bools = mapOf(0 to false), upper = mapOf(x to BigFraction.ZERO))),
            source.coordinates + SourceCoordinate(SourceCoordinate.Kind.Boolean, 0),
            source.premises.mapValues { (id, premise) ->
                SourcePremise(premise.inequality, if (id == "x-plus") mapOf(0 to true) else emptyMap())
            }, source.objective)
        for (conditional in listOf(false, true)) {
            val budget = SourceLpBudget()
            val original = cuts.first()
            val candidate = LinearAuthorityCut(original.owner, original.inequality, original.derivation,
                owners = original.owners, family = original.family, conditionalCandidate = conditional)

            val result = LinearAuthority(budget, retainLp = true).use {
                it.useReplayCuts(listOf(candidate))
                it.advance(guarded, ExecutionControl.Never, cuts = true,
                    cutMask = if (conditional) emptySet() else setOf(0))
            }

            assertTrue(result.cutConflictFamilies.isEmpty())
            assertNull(result.primalStages["SelectedCutBoundConflicts"])
            assertTrue(budget.operations > 0)
        }
    }

    @Test
    fun `mixed sign selected cuts should use opposite sides and preserve strict conflicts`() {
        for ((strict, maximum, conflicts) in listOf(
            Triple(false, BigFraction.ZERO, true),
            Triple(false, BigFraction.ONE, false),
            Triple(true, BigFraction.ONE, true),
        )) {
            val mixed = SourceProofContext(source.scope, setOf(x, y, r), linkedMapOf(
                "plus" to SourcePremise(SourceInequality(mapOf(x to BigFraction.MINUS_ONE,
                    y to BigFraction.ONE, r to BigFraction.ONE), half.negated())),
                "minus" to SourcePremise(SourceInequality(mapOf(x to BigFraction.MINUS_ONE,
                    y to BigFraction.ONE, r to BigFraction.MINUS_ONE), half.negated())),
                "y lower" to SourcePremise(SourceInequality(mapOf(y to BigFraction.MINUS_ONE),
                    BigFraction.ZERO, strict = strict)),
            ), mapOf(x to BigFraction.ONE)).atScope(source.scope.copy(condition = SourceCondition(
                upper = mapOf(x to maximum))))
            val cut = LinearAuthorityCut(0, SourceInequality(mapOf(x to BigFraction.MINUS_ONE,
                y to BigFraction.ONE), BigFraction.MINUS_ONE),
                SourceDerivation.IntegerRounding(SourceDerivation.Combination(listOf(
                    SourceDerivation.Combination.Term(half, SourceDerivation.Premise("plus")),
                    SourceDerivation.Combination.Term(half, SourceDerivation.Premise("minus")),
                ))), owners = setOf(0, 1), family = CutFamily.SOURCE_AGGREGATION)
            val budget = SourceLpBudget()

            val result = LinearAuthority(budget, retainLp = true).use {
                it.useReplayCuts(listOf(cut))
                it.advance(mixed, ExecutionControl.Never, cuts = true, cutMask = setOf(0))
            }

            assertEquals(if (conflicts) 1L else null, result.primalStages["SelectedCutBoundConflicts"])
            if (conflicts) {
                assertEquals(0, budget.operations)
                assertEquals(SourceEvidenceCheck.Checked,
                    SourceEvidenceVerifier(mixed.withLinearAuthority()).verify(assertNotNull(result.evidence)))
            } else {
                assertTrue(budget.operations > 0)
                assertTrue(result.evidence?.claim != SourceClaim.Refutation)
            }
        }
    }

    @Test
    fun `a selected cut with a missing receiving side should continue through the LP`() {
        val budget = SourceLpBudget()

        val result = LinearAuthority(budget, retainLp = true).use {
            it.useReplayCuts(cuts)
            it.advance(source, ExecutionControl.Never, cuts = true, cutMask = setOf(0))
        }

        assertNull(result.primalStages["SelectedCutBoundConflicts"])
        assertNotNull(result.bound)
        assertTrue(budget.operations > 0)
    }

    @Test
    fun `selected cuts should refute receiving bounds before another LP solve`() {
        val restricted = source.atScope(source.scope.copy(condition = SourceCondition(
            upper = mapOf(x to BigFraction.ZERO))))
        val budget = SourceLpBudget()

        val result = LinearAuthority(budget, retainLp = true).use {
            it.useReplayCuts(cuts)
            it.advance(restricted, ExecutionControl.Never, cuts = true, cutMask = setOf(0))
        }

        assertEquals(SourceEvidenceCheck.Checked,
            SourceEvidenceVerifier(restricted.withLinearAuthority()).verify(assertNotNull(result.evidence)))
        assertEquals(setOf(CutFamily.SOURCE_AGGREGATION), result.cutConflictFamilies)
        assertEquals(1L, result.primalStages["SelectedCutBoundConflicts"])
        assertEquals(0, budget.operations)
    }

    @Test
    fun `unselected cuts should not receive bound conflict attribution`() {
        val restricted = source.atScope(source.scope.copy(condition = SourceCondition(
            upper = mapOf(x to BigFraction.ZERO))))
        val budget = SourceLpBudget()

        val result = LinearAuthority(budget, retainLp = true).use {
            it.useReplayCuts(cuts)
            it.advance(restricted, ExecutionControl.Never, cuts = true, cutMask = emptySet())
        }

        assertTrue(result.cutConflictFamilies.isEmpty())
        assertNull(result.primalStages["SelectedCutBoundConflicts"])
        assertTrue(budget.operations > 0)
    }

    @Test
    fun `a selected cut conflict should retract with its receiving bound`() {
        val budget = SourceLpBudget()
        val restricted = source.atScope(source.scope.copy(condition = SourceCondition(
            upper = mapOf(x to BigFraction.ZERO))))
        val sibling = source.atScope(source.scope.copy(condition = SourceCondition(
            upper = mapOf(x to BigFraction.ONE))))

        val result = LinearAuthority(budget, retainLp = true).use {
            it.useReplayCuts(cuts)
            assertNotNull(it.advance(restricted, ExecutionControl.Never, cuts = true, cutMask = setOf(0)).evidence)
            it.advance(sibling, ExecutionControl.Never, cuts = true, cutMask = setOf(0))
        }

        assertNull(result.evidence)
        assertNull(result.primalStages["SelectedCutBoundConflicts"])
        assertNotNull(result.bound)
    }

    @Test
    fun `replay masks should target supplied captured cuts after their owner closes`() {
        val capture = LinearAuthorityCapture(1, 10_000L, 1_000_000L, ExecutionControl.Never)
        LinearAuthority(SourceLpBudget(), retainLp = true, additionalCuts = cuts).use {
            it.capture = capture
            it.advance(source, ExecutionControl.Never, cuts = true, cutMask = setOf(0))
        }
        val original = capture.records.single()
        assertEquals(2, original.cuts.size)

        for ((mask, expected) in listOf(
            emptySet<Int>() to BigFraction.ofLong(3) * half,
            setOf(0) to BigFraction.ofLong(2),
            setOf(1) to BigFraction.ofLong(5) * half,
            setOf(0, 1) to BigFraction.ofLong(3),
        )) {
            val replay = LinearAuthorityReplay.run(original, mask, 1_000_000L, 4_000_000L, ExecutionControl.Never)

            val bound = assertNotNull(replay.strongestBound)
            val replayed = assertNotNull(replay.query)
            assertEquals(SourceValue.Rational(expected), (bound.claim as SourceClaim.Bound).value)
            assertEquals(1, replay.operations)
            assertEquals(mask, replayed.cuts.filter { it.installed }.mapTo(HashSet()) { it.id })
            assertTrue(replayed.cuts.all { it.family == CutFamily.SOURCE_AGGREGATION })
            assertEquals(SourceEvidenceCheck.Checked,
                SourceEvidenceVerifier(source.withLinearAuthority()).verify(bound))
        }
    }

    @Test
    fun `replay should decline masks outside the captured pool`() {
        val capture = LinearAuthorityCapture(1, 10_000L, 1_000_000L, ExecutionControl.Never)
        LinearAuthority(SourceLpBudget(), retainLp = true, additionalCuts = cuts).use {
            it.capture = capture
            it.advance(source, ExecutionControl.Never, cuts = true, cutMask = setOf(0))
        }

        for (mask in listOf(setOf(-1), setOf(2))) {
            val replay = LinearAuthorityReplay.run(capture.records.single(), mask,
                1_000_000L, 4_000_000L, ExecutionControl.Never)

            assertNotNull(replay.decline)
            assertNull(replay.query)
            assertEquals(0, replay.operations)
        }
    }

    @Test
    fun `replay should decline an incomplete captured pool`() {
        val capture = LinearAuthorityCapture(1, 10_000L, 1_000_000L, ExecutionControl.Never)
        LinearAuthority(SourceLpBudget(), retainLp = true, additionalCuts = cuts).use {
            it.capture = capture
            it.advance(source, ExecutionControl.Never, cuts = true, cutMask = setOf(0))
        }
        val original = capture.records.single()

        for (incomplete in listOf(
            original.copy(projection = LinearAuthorityFeedbackRequest(
                cuts = LinearAuthorityFeedbackRequest.Cuts.Installed)),
            original.copy(cuts = original.cuts.map { it.copy(family = null) }),
        )) {
            val replay = LinearAuthorityReplay.run(incomplete, emptySet(),
                1_000_000L, 4_000_000L, ExecutionControl.Never)

            assertNotNull(replay.decline)
            assertNull(replay.query)
            assertEquals(0, replay.operations)
        }
    }

    @Test
    fun `replay should decline before importing beyond its allowance`() {
        val capture = LinearAuthorityCapture(1, 10_000L, 1_000_000L, ExecutionControl.Never)
        LinearAuthority(SourceLpBudget(), retainLp = true, additionalCuts = cuts).use {
            it.capture = capture
            it.advance(source, ExecutionControl.Never, cuts = true, cutMask = setOf(0))
        }

        for ((work, allocation) in listOf(1L to 4_000_000L, 1_000_000L to 1L)) {
            val replay = LinearAuthorityReplay.run(capture.records.single(), setOf(0), work, allocation,
                ExecutionControl.Never)

            assertNotNull(replay.decline)
            assertNull(replay.query)
            assertEquals(0, replay.operations)
            assertTrue(replay.work <= work && replay.allocation <= allocation)
        }
    }

    @Test
    fun `replay should recheck captured derivations instead of trusting prior check flags`() {
        val capture = LinearAuthorityCapture(1, 10_000L, 1_000_000L, ExecutionControl.Never)
        LinearAuthority(SourceLpBudget(), retainLp = true, additionalCuts = cuts).use {
            it.capture = capture
            it.advance(source, ExecutionControl.Never, cuts = true, cutMask = setOf(0))
        }
        val original = capture.records.single()
        val invalid = original.copy(cuts = original.cuts.map {
            if (it.id == 0) it.copy(derivation = SourceDerivation.Premise("missing"), checked = true) else it
        })

        val replay = LinearAuthorityReplay.run(invalid, setOf(0), 1_000_000L, 4_000_000L, ExecutionControl.Never)

        val bound = replay.strongestBound
        if (bound != null) {
            assertEquals(SourceValue.Rational(BigFraction.ofLong(3) * half), (bound.claim as SourceClaim.Bound).value)
            assertEquals(SourceEvidenceCheck.Checked,
                SourceEvidenceVerifier(source.withLinearAuthority()).verify(bound))
        }
        assertTrue(replay.query?.cuts?.none { it.id == 0 && it.installed } != false)
    }
}
