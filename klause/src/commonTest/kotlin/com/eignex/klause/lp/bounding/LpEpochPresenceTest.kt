package com.eignex.klause.lp.bounding

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.factor.table.Element
import com.eignex.klause.factor.table.Table
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.cut.SourceCut
import com.eignex.klause.lp.cut.orNull
import com.eignex.klause.lp.engine.CutExpression
import com.eignex.klause.lp.engine.CutPremise
import com.eignex.klause.lp.engine.CutProofFact
import com.eignex.klause.lp.engine.CutProvenance
import com.eignex.klause.lp.engine.CutSource
import com.eignex.klause.lp.engine.CutSourceKind
import com.eignex.klause.lp.engine.Relation
import com.eignex.klause.lp.relaxation.CpToLpRelaxation
import com.eignex.klause.lp.relaxation.CutSourceMap
import com.eignex.klause.lp.relaxation.LpExplanation
import com.eignex.klause.lp.relaxation.RootDomains
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.simplex.exact.BigFraction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LpEpochPresenceTest {
    @Test
    fun `interior absence explains zero upper and restored membership invalidates the guard`() {
        val problem = Problem(
            0,
            3,
            arrayOf(IntDomain(0, 4), IntDomain(0, 5), IntDomain(0, 4)),
            arrayOf(Table(intArrayOf(0, 1), longArrayOf(0, 5, 2, 2, 4, 0)), AllDifferent(intArrayOf(0, 2), 0, 5)),
        )
        val session = PropagationSession(problem)
        val relaxer = CpToLpRelaxation(problem, null, tableHull = true)
        val before = relaxer.build(session)
        session.pinInt(2, 2)
        val absent = relaxer.build(session)
        val column = absent.colReq.indices.first { absent.colReq[it]?.toList() == listOf(0L, 2L, 1L, 2L) }
        assertEquals(0L, session.intDomain(0).min)
        assertEquals(4L, session.intDomain(0).max)
        assertEquals(0L, absent.model.upper[column])
        assertEquals(1L, absent.colPresentUpper[column])
        val literal = LpExplanation.premiseLit(absent, session, column, lowerSide = false)
        assertEquals(session.equalityLit(0, 2), literal)
        assertEquals(false, session.litTruth(literal))
        val sources = assertNotNull(absent.sourceMap)
        val term = assertNotNull(sources.column(column)).expression()
        val guard = assertNotNull(sources.presenceGuard(CutPremise.Bound(term, true, BigFraction.ZERO)))
        val cut = SourceCut(
            term,
            Relation.LE,
            BigFraction.ZERO,
            CutProvenance(
                problem,
                sources.epoch,
                listOf(CutProofFact(guard, false)),
                auxiliaryDefinitions = sources.auxiliaryDefinitions,
            ),
        )
        assertNotNull(cut.toCut(sources).orNull())
        session.popToLevel(0)
        val restored = relaxer.build(session)
        assertEquals(before.colPresence, restored.colPresence)
        assertEquals(1L, restored.model.upper[column])
        assertNull(cut.toCut(assertNotNull(restored.sourceMap)).orNull())
    }

    @Test
    fun `duplicate tuples and repeated factor occurrences have distinct extensions`() {
        val table = Table(intArrayOf(0, 1), longArrayOf(0, 1, 0, 1, 1, 0))
        val problem = Problem(0, 2, Array(2) { IntDomain(0, 1) }, arrayOf(table, table))
        val relaxation = CpToLpRelaxation(problem, null, tableHull = true).build(RootDomains(problem))
        val definitions = relaxation.colPresence.filterNotNull()
        assertEquals(6, definitions.size)
        assertEquals(6, definitions.distinct().size)
        assertNotEquals(definitions[0], definitions[1])
        for (x in 0L..1L) {
            val values = LongArray(relaxation.model.n)
            values[relaxation.intColOf[0]] = x
            values[relaxation.intColOf[1]] = 1L - x
            for (column in values.indices) {
                val definition = relaxation.colPresence[column] ?: continue
                val tuple = definition.role.last()
                values[column] = if (tuple == if (x == 0L) 0L else 2L) 1L else 0L
            }
            val activity = LongArray(relaxation.model.m)
            for (column in values.indices) {
                relaxation.model.forEachInColumn(column) { row, coefficient ->
                    activity[row] += coefficient * values[column]
                }
            }
            for (row in activity.indices) assertEquals(relaxation.model.flippedRhs[row], activity[row])
        }
    }

    @Test
    fun `RLT products require one in both source variables`() {
        val problem = Problem(
            0,
            3,
            Array(3) { IntDomain(0, 1) },
            arrayOf(Linear(intArrayOf(1, 1), intArrayOf(1, 2), LinearOp.LE, 1)),
        )
        val session = PropagationSession(problem)
        val relaxer = CpToLpRelaxation(problem, null, booleanRlt = true)
        val before = relaxer.build(session)
        assertTrue(
            before.colPresence.filterNotNull().all {
                it.required == listOf(
                    2L,
                    1L,
                    1L,
                    1L,
                ) || it.required == listOf(1L, 1L, 2L, 1L)
            },
        )
        session.implyIntAtMost(1, 0)
        val after = relaxer.build(session)
        val products = after.colPresence.indices.filter { after.colPresence[it] != null }
        assertEquals(2, products.size)
        for (column in products) {
            assertEquals(0L, after.model.upper[column])
            assertEquals(1L, after.colPresentUpper[column])
        }
    }

    @Test
    fun `Element selector index addition preserves values above Int range`() {
        val offset = Int.MAX_VALUE
        val problem = Problem(
            0,
            2,
            arrayOf(IntDomain(offset.toLong(), offset.toLong() + 1L), IntDomain(3, 7)),
            arrayOf(Element(0, 1, longArrayOf(3, 7), false, offset)),
        )
        val relaxation = CpToLpRelaxation(problem, null, elementHull = true).build(RootDomains(problem))
        val definitions = relaxation.colPresence.filterNotNull()
        assertEquals(2, definitions.size)
        assertTrue(definitions.any { it.required == listOf(0L, offset.toLong() + 1L) })
        assertFalse(assertNotNull(relaxation.sourceMap).auxiliaryDefinitions.isEmpty())
    }

    @Test
    fun `an integer cut does not depend on unrelated auxiliary definitions`() {
        val problem = Problem(
            0,
            2,
            Array(2) { IntDomain(0, 1) },
            arrayOf(Table(intArrayOf(0, 1), longArrayOf(0, 1, 1, 0))),
        )
        val relaxation = CpToLpRelaxation(problem, null, tableHull = true).build(RootDomains(problem))
        val original = assertNotNull(relaxation.sourceMap)
        val integer = CutSource(CutSourceKind.INTEGER, 0)
        val expression = CutExpression(mapOf(integer to BigFraction.ONE))
        val cut = SourceCut(
            expression,
            Relation.LE,
            BigFraction.ONE,
            CutProvenance(
                problem,
                original.epoch,
                listOf(CutProofFact(CutPremise.Row(expression, Relation.LE, BigFraction.ONE), true)),
                auxiliaryDefinitions = original.auxiliaryDefinitions,
            ),
        )
        val target = CutSourceMap(problem, 10L, original.columns.filter { it?.source?.kind == CutSourceKind.INTEGER })

        val mapped = assertNotNull(cut.toCut(target).orNull())

        assertTrue(assertNotNull(mapped.provenance).auxiliaryDefinitions.isEmpty())
        assertEquals(cut.provenance.facts, mapped.provenance.facts)
        val term = original.auxiliaryDefinitions.keys.first()
        val missing = SourceCut(
            CutExpression(mapOf(term to BigFraction.ONE)),
            Relation.LE,
            BigFraction.ONE,
            CutProvenance(problem, original.epoch, emptyList()),
        )
        assertNull(missing.toCut(original).orNull())
    }
}
