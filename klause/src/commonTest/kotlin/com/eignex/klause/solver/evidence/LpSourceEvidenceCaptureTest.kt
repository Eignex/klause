package com.eignex.klause.solver.evidence

import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.lp.engine.CertifiedLpBound
import com.eignex.klause.lp.engine.LpBuilder
import com.eignex.klause.lp.engine.LpExactState
import com.eignex.klause.lp.engine.Relation
import com.eignex.klause.lp.engine.Sense
import com.eignex.klause.lp.engine.authoritativeModel
import com.eignex.klause.lp.engine.integerCertify
import com.eignex.klause.lp.engine.nodeDualCertificate
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.simplex.exact.BigRationalConflict
import com.eignex.klause.simplex.exact.ExactSimplexBound
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class LpSourceEvidenceCaptureTest {
    private val x = SourceCoordinate(SourceCoordinate.Kind.Integer, 0)
    private val scope = SourceScope(SourceRevision("original", "exact-affine"),
        objective = SourceObjective("x", ObjectiveSense.MINIMIZE))

    private fun context(lower: Long, upper: Long = 9): SourceProofContext {
        val premises = mapOf(
            "lower" to SourcePremise(SourceInequality(mapOf(x to BigFraction.MINUS_ONE),
                BigFraction.ofLong(-lower))),
            "upper" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ONE), BigFraction.ofLong(upper))),
        )
        return SourceProofContext(scope, setOf(x), premises, mapOf(x to BigFraction.ONE))
    }

    @Test
    fun `mixed multipliers capture a fractional bound in original objective units`() {
        val real = SourceCoordinate(SourceCoordinate.Kind.Real, 0)
        val quarter = assertNotNull(BigFraction.ofDouble(0.25))
        val source = LpBuilder().apply {
            addVar(0L, 10L, cost = 1L)
            addRealVar(0.25, 1.25, cost = 0.5)
        }.build(Sense.MINIMIZE)
        val model = assertNotNull(LpExactState(assertNotNull(source.authoritativeModel())).toWorkingModel())
        val certificate = assertNotNull(nodeDualCertificate(model, doubleArrayOf()))
        val bound = CertifiedLpBound(
            certificate.exactObjectiveBound(), dualMultipliers = certificate.exactMultipliers(),
        )
        val original = context(0L, 10L)
        val premises = original.premises + mapOf(
            "real lower" to SourcePremise(SourceInequality(mapOf(real to BigFraction.MINUS_ONE), quarter.negated())),
            "real upper" to SourcePremise(SourceInequality(
                mapOf(real to BigFraction.ONE), quarter + BigFraction.ONE,
            )),
        )
        val context = SourceProofContext(scope, setOf(x, real), premises,
            mapOf(x to BigFraction.ONE, real to assertNotNull(BigFraction.ofDouble(0.5))))

        val evidence = LpSourceEvidenceCapture(context, listOf(x, real)).bound(model, bound)

        assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(context).verify(evidence))
    }

    @Test
    fun `retained integer multipliers produce a source bound after coordinate shifting`() {
        val model = LpBuilder().apply {
            val column = addVar(2, 9, cost = 1)
            addRow(intArrayOf(column), longArrayOf(1), Relation.GE, 3)
        }.build(Sense.MINIMIZE)
        val bound = CertifiedLpBound(BigFraction.ofLong(3), assertNotNull(integerCertify(model, doubleArrayOf(-1.0))))
        val context = context(3)

        val evidence = LpSourceEvidenceCapture(context, listOf(x)).bound(model, bound)

        assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(context).verify(evidence))
    }

    @Test
    fun `an LP row absent from the original source cannot establish a stronger bound`() {
        val model = LpBuilder().apply {
            val column = addVar(0, 9, cost = 1)
            addRow(intArrayOf(column), longArrayOf(1), Relation.GE, 4)
        }.build(Sense.MINIMIZE)
        val bound = CertifiedLpBound(BigFraction.ofLong(4), assertNotNull(integerCertify(model, doubleArrayOf(-1.0))))
        val context = context(3)

        val evidence = LpSourceEvidenceCapture(context, listOf(x)).bound(model, bound)

        assertIs<SourceEvidenceCheck.Unavailable>(SourceEvidenceVerifier(context).verify(evidence))
    }

    @Test
    fun `a stale integer LP certificate withholds source capture`() {
        val model = LpBuilder().apply { addVar(3, 9, cost = 1) }.build(Sense.MINIMIZE)
        val bound = CertifiedLpBound(BigFraction.ofLong(3), assertNotNull(integerCertify(model, doubleArrayOf())))
        val context = context(3)
        model.cost[0] = 2

        val evidence = LpSourceEvidenceCapture(context, listOf(x)).bound(model, bound)

        assertIs<SourceEvidenceCheck.Unavailable>(SourceEvidenceVerifier(context).verify(evidence))
    }

    @Test
    fun `an exact Farkas conflict cites the original incompatible rows`() {
        val model = LpBuilder().apply {
            val column = addVar(0, 9)
            addRow(intArrayOf(column), longArrayOf(1), Relation.LE, 1)
            addRow(intArrayOf(column), longArrayOf(1), Relation.GE, 2)
        }.build(Sense.MINIMIZE)
        val conflict = BigRationalConflict(intArrayOf(0, 1), listOf(BigFraction.ONE, BigFraction.ONE),
            listOf(ExactSimplexBound(1, false), ExactSimplexBound(2, false)))
        val context = context(2, 1)

        val evidence = LpSourceEvidenceCapture(context, listOf(x)).conflict(model, conflict)

        assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(context).verify(evidence))
    }

    @Test
    fun `an omitted Farkas endpoint cannot be replaced by the infeasible engine label`() {
        val model = LpBuilder().apply {
            val column = addVar(0, 9)
            addRow(intArrayOf(column), longArrayOf(1), Relation.LE, 1)
            addRow(intArrayOf(column), longArrayOf(1), Relation.GE, 2)
        }.build(Sense.MINIMIZE)
        val conflict = BigRationalConflict(intArrayOf(0, 1), listOf(BigFraction.ONE, BigFraction.ONE),
            listOf(ExactSimplexBound(1, false)))
        val context = context(2, 1)

        val evidence = LpSourceEvidenceCapture(context, listOf(x)).conflict(model, conflict)

        assertIs<SourceEvidenceCheck.Unavailable>(SourceEvidenceVerifier(context).verify(evidence))
    }

    @Test
    fun `cancelled LP derivation capture is explicitly unavailable`() {
        val model = LpBuilder().apply { addVar(3, 9, cost = 1) }.build(Sense.MINIMIZE)
        val bound = CertifiedLpBound(BigFraction.ofLong(3), assertNotNull(integerCertify(model, doubleArrayOf())))
        val context = context(3)

        val evidence = LpSourceEvidenceCapture(context, listOf(x)).bound(model, bound, Cancellation { true })

        assertIs<SourceEvidenceCheck.Unavailable>(SourceEvidenceVerifier(context).verify(evidence))
    }

    @Test
    fun `rational dual capture owns multiplier coordinates`() {
        val model = LpBuilder().apply {
            val column = addVar(0, 9, cost = 1)
            addRow(intArrayOf(column), longArrayOf(1), Relation.GE, 3)
        }.build(Sense.MINIMIZE)
        val multipliers = mutableListOf(BigFraction.MINUS_ONE)
        val bound = CertifiedLpBound(BigFraction.ofLong(3), dualMultipliers = multipliers)
        val context = context(3)
        multipliers[0] = BigFraction.ZERO

        val evidence = LpSourceEvidenceCapture(context, listOf(x)).bound(model, bound)

        assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(context).verify(evidence))
    }

    @Test
    fun `a minimized LP dual captures the original maximization upper bound`() {
        val model = LpBuilder().apply { addVar(0, 3, cost = -1) }.build(Sense.MINIMIZE)
        val bound = CertifiedLpBound(BigFraction.ofLong(-3),
            assertNotNull(integerCertify(model, doubleArrayOf())))
        val original = context(0, 3)
        val scoped = scope.copy(objective = SourceObjective("x", ObjectiveSense.MAXIMIZE))
        val context = SourceProofContext(scoped, original.coordinates, original.premises,
            mapOf(x to BigFraction.ONE))

        val evidence = LpSourceEvidenceCapture(context, listOf(x)).bound(model, bound)

        assertEquals(SourceClaim.Bound(BigFraction.ofLong(3)), evidence.claim)
        assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(context).verify(evidence))
    }

}
