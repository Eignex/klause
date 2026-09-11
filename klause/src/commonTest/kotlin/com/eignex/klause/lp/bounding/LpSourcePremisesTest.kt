package com.eignex.klause.lp.bounding

import com.eignex.klause.lp.engine.CutExpression
import com.eignex.klause.lp.engine.CutPremise
import com.eignex.klause.lp.engine.CutProofFact
import com.eignex.klause.lp.engine.CutProvenance
import com.eignex.klause.lp.engine.CutSource
import com.eignex.klause.lp.engine.CutSourceKind
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.search.RegisteredTheoryDecision
import com.eignex.klause.solver.search.SearchAtomRegistry
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.SearchIntValue
import com.eignex.klause.solver.search.SearchSession
import com.eignex.klause.theory.qflra.SourceBoundAtom
import com.eignex.klause.theory.qflra.SourceBoundTerm
import com.ionspin.kotlin.bignum.integer.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LpSourcePremisesTest {
    @Test
    fun `registered cut antecedents remain complete through pop and restart`() {
        val root = Any()
        val names = LpSourcePremises(root)
        val session = SearchSession(emptyList(), atoms = SearchAtomRegistry(3))
        val terms = listOf(SourceBoundTerm(SearchIntValue(0), BigFraction.ONE))
        val atom = assertNotNull(SourceBoundAtom.integerSplit(session, terms, BigFraction.ZERO))
        val guard = SearchDecision.Bool(0)
        val bound = SearchDecision.Theory(atom.positive)
        session.push(guard)
        session.push(bound)
        names.record(guard, session)
        names.record(bound, session)
        val expression = CutExpression(mapOf(CutSource(CutSourceKind.INTEGER, 0) to BigFraction.ONE))
        val proof = CutProvenance(
            root, 0,
            listOf(
            CutProofFact(CutPremise.Literal(0), false),
            CutProofFact(CutPremise.Bound(expression, true, BigFraction.ZERO), false),
        )
        )

        val explanation = assertNotNull(names.explain(proof, session))

        assertEquals(setOf(1, 7), explanation.literals.toSet())
        for (guardValue in listOf(false, true)) {
            for (x in 0..2) {
            if (!guardValue || 2 * x >= 1) {
                assertTrue(
                    explanation.literals.any { literal ->
                    val truth = if ((literal ushr 1) == 0) guardValue else x <= 0
                    truth == (literal and 1 == 0)
                }
                )
            }
        }
        }
        assertNull(names.explain(proof, session, maxNodes = 2))
        session.popTo(1)
        assertNull(names.explain(proof, session))
        session.restart()
        assertNull(names.explain(proof, session))
        session.push(guard)
        session.push(bound)
        assertSame(
            atom.positive,
            assertNotNull(SourceBoundAtom.integerSplit(session, terms, BigFraction.ZERO)).positive,
        )
        assertEquals(explanation.literals.toSet(), assertNotNull(names.explain(proof, session)).literals.toSet())
        val incomplete = CutProvenance(root, 0, proof.facts + CutProofFact(CutPremise.Literal(2), false))
        assertNull(names.explain(incomplete, session))
        assertNull(names.explain(CutProvenance(Any(), 0, proof.facts), session))
        val foreign = SearchSession(emptyList(), atoms = SearchAtomRegistry(3))
        foreign.push(guard)
        assertNull(names.explain(CutProvenance(root, 0, listOf(proof.facts[0])), foreign))
    }

    @Test
    fun `source integer splits preserve arbitrary precision and finite adapters decline overflow`() {
        val session = SearchSession(emptyList(), atoms = SearchAtomRegistry(4))
        val threshold = BigInteger.fromLong(Long.MAX_VALUE) + BigInteger.ONE
        val value = BigFraction.of(threshold, BigInteger.ONE) + BigFraction.ofLong(2).reciprocal()

        val alternatives = assertNotNull(lpIntegerBranch(9, value, session, registered = true))

        val left = assertIs<RegisteredTheoryDecision>(assertIs<SearchDecision.Theory>(alternatives[0]).decision)
        val right = assertIs<RegisteredTheoryDecision>(assertIs<SearchDecision.Theory>(alternatives[1]).decision)
        assertEquals(8, left.literal)
        assertEquals(BigFraction.of(threshold, BigInteger.ONE), assertIs<SourceBoundAtom>(left.payload).threshold)
        assertEquals(
            BigFraction.of(threshold + BigInteger.ONE, BigInteger.ONE),
            assertIs<SourceBoundAtom>(right.payload).threshold,
        )
        assertNull(lpIntegerBranch(9, value, session, registered = false))
        assertNull(lpIntegerBranch(9, BigFraction.ONE, session, registered = true))
        val exhausted = SearchSession(emptyList(), atoms = SearchAtomRegistry(4, maxAtoms = 0))
        assertNull(lpIntegerBranch(9, value, exhausted, registered = true))
    }
}
