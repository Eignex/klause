package com.eignex.klause.lp.relaxation

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.Lit
import com.eignex.klause.lp.engine.ExactLpBounds
import com.eignex.klause.lp.engine.ExactLpColumn
import com.eignex.klause.lp.engine.ExactLpNumber
import com.eignex.klause.lp.engine.ExactLpPremises
import com.eignex.klause.lp.engine.ExactLpRow
import com.eignex.klause.lp.engine.ExactLpSide
import com.eignex.klause.lp.engine.CutExpression
import com.eignex.klause.lp.engine.CutPremise
import com.eignex.klause.lp.engine.CutProofFact
import com.eignex.klause.lp.engine.CutProvenance
import com.eignex.klause.lp.engine.CutSource
import com.eignex.klause.lp.engine.CutSourceKind
import com.eignex.klause.lp.engine.LpBoundTrail
import com.eignex.klause.lp.engine.LpBuilder
import com.eignex.klause.lp.engine.LpExactState
import com.eignex.klause.lp.engine.LpRowPremises
import com.eignex.klause.lp.engine.LpScopedRow
import com.eignex.klause.lp.engine.Relation
import com.eignex.klause.lp.engine.RevisedSimplex
import com.eignex.klause.lp.engine.Sense
import com.eignex.klause.lp.engine.integerCertify
import com.eignex.klause.lp.engine.authoritativeModel
import com.eignex.klause.lp.engine.integerFarkasRay
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.IntHashSet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * #281/#705: LP objective-bound learning on the sparse revised-simplex path. The reason is built from
 * the exact basis-certificate; every literal it cites is false at the node.
 */
class LpExplanationTest {
    @Test
    fun `opaque exact literal identifiers require a native Boolean interpretation before citation`() {
        for (literal in listOf(-13, Int.MAX_VALUE)) {
            val problem = Problem(1, 1, arrayOf(IntDomain(0, 9)), emptyArray())
            val source = LpBuilder().apply {
                val x = addVar(0L, 9L)
                addRow(intArrayOf(x), longArrayOf(1L), Relation.GE, 3L)
            }.build(Sense.MINIMIZE)
            val exact = assertNotNull(source.authoritativeModel()).copy(
                rows = listOf(ExactLpRow(global = false, premises = ExactLpPremises(emptyList(), listOf(literal)))),
            )
            val model = assertNotNull(LpExactState(exact).toWorkingModel())
            val relaxation = LpRelaxation(model, intArrayOf(0), booleanArrayOf(false), 0L, intArrayOf(0), intArrayOf())

            assertEquals(false, LpExplanation.addRowPremiseLits(IntArrayList(), IntHashSet(), relaxation,
                intArrayOf(0), PropagationSession(problem)))
        }
    }

    @Test
    fun `affine rational and strict source premises name their exact integer lattice bounds`() {
        val half = BigFraction.ofLong(2L).reciprocal()
        val cases = listOf(
            Triple(BigFraction.ofLong(-3L), false, -3L to -3L),
            Triple(BigFraction.ofLong(-3L), true, -4L to -2L),
            Triple(BigFraction.ofLong(-5L) * half, false, -3L to -2L),
            Triple(BigFraction.ofLong(-5L) * half, true, -3L to -2L),
            Triple(BigFraction.ofLong(5L) * half, false, 2L to 3L),
            Triple(BigFraction.ofLong(5L) * half, true, 2L to 3L),
            Triple(BigFraction.ofLong(3L), false, 3L to 3L),
            Triple(BigFraction.ofLong(3L), true, 2L to 4L),
        )
        for ((threshold, strict, endpoints) in cases) for (upper in listOf(false, true)) for (sign in listOf(-2L, 2L)) {
            val problem = Problem(0, 1, arrayOf(IntDomain(-6, 6)), emptyArray())
            val session = PropagationSession(problem)
            val endpoint = if (upper) endpoints.first else endpoints.second
            if (upper) session.implyIntAtMost(0, endpoint) else session.implyIntAtLeast(0, endpoint)
            val source = CutSource(CutSourceKind.INTEGER, 0)
            val coefficient = BigFraction.ofLong(sign)
            val expression = CutExpression(mapOf(source to coefficient), BigFraction.ONE)
            val premise = CutPremise.Bound(
                expression,
                upper == (sign > 0L),
                threshold * coefficient + BigFraction.ONE,
                strict,
            )
            val proof = CutProvenance(problem, 0L, listOf(CutProofFact(premise, false)))
            val model = LpBuilder().apply {
                addVar(-6L, 6L)
                addRow(intArrayOf(), longArrayOf(), Relation.LE, 0L)
            }.build(Sense.MINIMIZE)
            model.rowGlobal[0] = false
            val relaxation = LpRelaxation(model, intArrayOf(0), booleanArrayOf(false), 0L, intArrayOf(0), intArrayOf(),
                sourceMap = CutSourceMap(problem, 0L, listOf(CutColumnSource(source)), parentRows = mapOf(0 to proof)))
            val literals = IntArrayList()

            assertTrue(LpExplanation.addRowPremiseLits(literals, IntHashSet(), relaxation, intArrayOf(0), session))

            val expected = if (upper) session.boundLeLit(0, endpoint, false) else session.boundGeLit(0, endpoint, false)
            assertEquals(listOf(expected), literals.toIntArray().toList())
            assertEquals(false, session.litTruth(expected))
            assertEquals(false, LpExplanation.addRowPremiseLits(IntArrayList(), IntHashSet(), relaxation,
                intArrayOf(0), PropagationSession(problem)))
        }
    }

    @Test
    fun `rational Boolean source bounds cite only the value that satisfies the predicate`() {
        val half = BigFraction.ofLong(2L).reciprocal()
        val cases = listOf(
            Triple(CutPremise.Bound(CutExpression(emptyMap()), false, half), true, true),
            Triple(CutPremise.Bound(CutExpression(emptyMap()), true, half), false, true),
            Triple(CutPremise.Bound(CutExpression(emptyMap()), false, BigFraction.ZERO, true), true, true),
            Triple(CutPremise.Bound(CutExpression(emptyMap()), true, BigFraction.ONE, true), false, true),
            Triple(CutPremise.Bound(CutExpression(emptyMap()), false, BigFraction.ZERO), null, true),
            Triple(CutPremise.Bound(CutExpression(emptyMap()), true, BigFraction.ONE), null, true),
            Triple(CutPremise.Bound(CutExpression(emptyMap()), false, BigFraction.ONE, true), null, false),
            Triple(CutPremise.Bound(CutExpression(emptyMap()), true, BigFraction.ZERO, true), null, false),
        )
        for ((bound, pin, accepted) in cases) for (sign in listOf(-2L, 2L)) {
            val problem = Problem(1, 0, emptyArray(), emptyArray())
            val session = PropagationSession(problem)
            if (pin != null) session.implyBool(0, pin)
            val source = CutSource(CutSourceKind.BOOLEAN, 0)
            val coefficient = BigFraction.ofLong(sign)
            val expression = CutExpression(mapOf(source to coefficient), BigFraction.ONE)
            val premise = CutPremise.Bound(
                expression,
                bound.upper == (sign > 0L),
                bound.value * coefficient + BigFraction.ONE,
                bound.strict,
            )
            val proof = CutProvenance(problem, 0L, listOf(CutProofFact(premise, false)))
            val model = LpBuilder().apply {
                addVar(0L, 1L)
                addRow(intArrayOf(), longArrayOf(), Relation.LE, 0L)
            }.build(Sense.MINIMIZE)
            model.rowGlobal[0] = false
            val relaxation = LpRelaxation(model, intArrayOf(0), booleanArrayOf(true), 0L, intArrayOf(), intArrayOf(0),
                sourceMap = CutSourceMap(problem, 0L, listOf(CutColumnSource(source)), parentRows = mapOf(0 to proof)))
            val literals = IntArrayList()

            assertEquals(
                accepted,
                LpExplanation.addRowPremiseLits(literals, IntHashSet(), relaxation, intArrayOf(0), session),
            )

            assertEquals(if (pin == null) emptyList() else listOf(Lit.make(0, !pin)), literals.toIntArray().toList())
        }
    }

    @Test
    fun `projected global flags cannot erase exact row premises`() {
        val problem = Problem(1, 1, arrayOf(IntDomain(0, 9)),
            arrayOf(ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.GE, 3)))
        val session = PropagationSession(problem)
        session.pinBool(0, true)
        val source = LpBuilder().apply {
            val x = addVar(0L, 9L, cost = 1L)
            addRow(intArrayOf(x), longArrayOf(1L), Relation.GE, 3L)
        }.build(Sense.MINIMIZE)
        source.rowGlobal[0] = false
        source.rowPremises[0] = LpRowPremises(
            intArrayOf(),
            booleanArrayOf(),
            longArrayOf(),
            intArrayOf(Lit.make(0, true)),
        )
        val model = assertNotNull(LpExactState(assertNotNull(source.authoritativeModel())).toWorkingModel())
        val relaxation = LpRelaxation(model, intArrayOf(0), booleanArrayOf(false), 0L, intArrayOf(0), intArrayOf())
        val certificate = assertNotNull(integerCertify(model, doubleArrayOf(-1.0)))

        model.rowGlobal[0] = true

        assertEquals(
            listOf(Lit.make(0, false)),
            LpExplanation.objectiveBoundReason(relaxation, certificate, session)?.toList(),
        )
    }

    @Test
    fun `legacy and retained row activators cannot be cited after native rollback`() {
        for (retained in listOf(false, true)) {
            val problem = Problem(1, 1, arrayOf(IntDomain(0, 9)),
                arrayOf(ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.GE, 3)))
            val session = PropagationSession(problem)
            session.pinBool(0, true)
            val source = LpBuilder().apply {
                val x = addVar(0L, 9L, cost = 1L)
                addRow(intArrayOf(x), longArrayOf(1L), Relation.GE, 3L)
            }.build(Sense.MINIMIZE)
            source.rowGlobal[0] = false
            source.rowPremises[0] = LpRowPremises(
                intArrayOf(),
                booleanArrayOf(),
                longArrayOf(),
                intArrayOf(Lit.make(0, true)),
            )
            val model = if (retained) {
                assertNotNull(LpExactState(assertNotNull(source.authoritativeModel())).toWorkingModel())
            } else {
                source
            }
            val relaxation = LpRelaxation(model, intArrayOf(0), booleanArrayOf(false), 0L, intArrayOf(0), intArrayOf())
            val certificate = assertNotNull(integerCertify(model, doubleArrayOf(-1.0)))
            assertEquals(
                listOf(Lit.make(0, false)),
                LpExplanation.objectiveBoundReason(relaxation, certificate, session)?.toList(),
            )

            session.popToLevel(0)

            assertNull(LpExplanation.objectiveBoundReason(relaxation, certificate, session))
        }
    }

    @Test
    fun `retained objective explanations decline certificates from popped bounds`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(3, 9)), arrayOf<Factor>())
        val session = PropagationSession(problem)
        val relaxation = CpToLpRelaxation(problem, LinearObjective(intCoefficients = longArrayOf(1L))).build(session)
        val trail = LpBoundTrail(assertNotNull(relaxation.model.authoritativeModel()))
        assertTrue(trail.push())
        assertTrue(trail.assertBound(0, false, ExactLpSide(ExactLpNumber.of(2L)), 7L))
        val child = relaxation.withModel(assertNotNull(trail.state.toWorkingModel()))
        val certificate = assertNotNull(integerCertify(child.model, doubleArrayOf()))
        assertTrue(trail.pop(0))
        val parent = relaxation.withModel(assertNotNull(trail.state.toWorkingModel()))

        assertNull(LpExplanation.objectiveBoundReason(parent, certificate, session))
        val fresh = assertNotNull(integerCertify(parent.model, doubleArrayOf()))
        assertEquals(
            listOf(session.boundGeLit(0, 3L, false)),
            LpExplanation.objectiveBoundReason(parent, fresh, session)?.toList(),
        )
    }

    @Test
    fun `retained objective explanations cite live absolute endpoints in root coordinates`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(3, 9)), arrayOf<Factor>())
        val session = PropagationSession(problem)
        val relaxation = CpToLpRelaxation(problem, LinearObjective(intCoefficients = longArrayOf(2L))).build(session)
        val trail = LpBoundTrail(assertNotNull(relaxation.model.authoritativeModel()))
        assertTrue(trail.push())
        session.implyIntAtLeast(0, 5L)
        session.implyIntAtMost(0, 7L)
        assertTrue(trail.assertBound(0, false, ExactLpSide(ExactLpNumber.of(2L)), 7L))
        assertTrue(trail.assertBound(0, true, ExactLpSide(ExactLpNumber.of(4L)), 8L))
        val retained = relaxation.withModel(assertNotNull(trail.state.toWorkingModel()))
        val certificate = assertNotNull(integerCertify(retained.model, doubleArrayOf()))

        val reason = LpExplanation.objectiveBoundReason(retained, certificate, session)

        assertEquals(listOf(session.boundGeLit(0, 5L, positive = false)), reason?.toList())
        assertEquals(session.boundLeLit(0, 7L, positive = false), LpExplanation.premiseLit(retained, session, 0, false))
    }

    @Test
    fun `retained integer explanations round rational and strict sides in the source lattice`() {
        for (strict in listOf(false, true)) {
            val problem = Problem(0, 1, arrayOf(IntDomain(3, 9)), arrayOf<Factor>())
            val session = PropagationSession(problem)
            val relaxation = CpToLpRelaxation(
                problem,
                LinearObjective(intCoefficients = longArrayOf(1L)),
            ).build(session)
            val trail = LpBoundTrail(assertNotNull(relaxation.model.authoritativeModel()))
            val lo = if (strict) 6L else 5L
            val hi = if (strict) 6L else 7L
            session.implyIntAtLeast(0, lo)
            session.implyIntAtMost(0, hi)
            val lower = if (strict) BigFraction.ofLong(2L) else assertNotNull(BigFraction.ofDouble(1.5))
            val upper = if (strict) BigFraction.ofLong(4L) else assertNotNull(BigFraction.ofDouble(4.5))
            assertTrue(trail.assertBound(0, false, ExactLpSide(ExactLpNumber.of(lower), strict), 7L))
            assertTrue(trail.assertBound(0, true, ExactLpSide(ExactLpNumber.of(upper), strict), 8L))
            val retained = relaxation.withModel(assertNotNull(trail.state.toWorkingModel()))

            assertEquals(session.boundGeLit(0, lo, false), LpExplanation.premiseLit(retained, session, 0, true))
            assertEquals(session.boundLeLit(0, hi, false), LpExplanation.premiseLit(retained, session, 0, false))
        }
    }

    @Test
    fun `retained rational Farkas explanations reject a popped row and name its live bound`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(0, 5)), arrayOf<Factor>())
        val session = PropagationSession(problem)
        val relaxation = CpToLpRelaxation(problem, LinearObjective(intCoefficients = longArrayOf(1L))).build(session)
        val trail = LpBoundTrail(assertNotNull(relaxation.model.authoritativeModel()))
        assertTrue(trail.push())
        session.implyIntAtLeast(0, 2L)
        assertTrue(trail.assertBound(0, false, ExactLpSide(ExactLpNumber.of(2L)), 7L))
        val third = ExactLpNumber.of(BigFraction.ofLong(3L).reciprocal())
        assertTrue(trail.append(
            LpScopedRow(0L, listOf(0 to third), third, ExactLpColumn(ExactLpBounds(ExactLpSide(ExactLpNumber.of(0L))))),
            true,
        ))
        val retained = relaxation.withModel(assertNotNull(trail.state.toWorkingModel()))
        val ray = assertNotNull(integerFarkasRay(retained.model, doubleArrayOf(-1.0)))

        val clause = LpExplanation.infeasibilityClause(retained, ray, session)

        assertEquals(listOf(session.boundGeLit(0, 2L, false)), clause?.toList())
        assertTrue(trail.pop(0))
        val parent = relaxation.withModel(assertNotNull(trail.state.toWorkingModel()))
        assertNull(LpExplanation.infeasibilityClause(parent, ray, session))
    }

    @Test
    fun `objective-bound reason cites the load-bearing column premise and is all-false at the node`() {
        // minimize x, x in [3,8], constraint x >= 5: LP optimum 5, the binding bound is x >= 5 (its
        // GE row), so the reason cites the negated live lower-bound premise of x.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(3, 8)),
            factors = arrayOf<Factor>(Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 5)),
        )
        val session = PropagationSession(problem)
        val relaxation = CpToLpRelaxation(problem, LinearObjective(intCoefficients = longArrayOf(1)))
            .build(session)
        val result = assertNotNull(RevisedSimplex(relaxation.model).solve())
        val cert = assertNotNull(integerCertify(relaxation.model, result.duals))
        val reason = LpExplanation.objectiveBoundReason(relaxation, cert, session)
        assertNotNull(reason, "an optimal LP over global rows must yield an objective-bound reason")
        // x is seated at its (binding) lower bound with a positive reduced cost, so the reason cites
        // the negated live lower-bound premise of x — the load-bearing support for objective >= 5.
        val lo = relaxation.model.loShift[0]
        assertTrue(
            session.boundGeLit(0, lo, positive = false) in reason.toList(),
            "reason ${reason.toList()} must cite the negated lower-bound premise of x (lo=$lo)",
        )
    }

}
