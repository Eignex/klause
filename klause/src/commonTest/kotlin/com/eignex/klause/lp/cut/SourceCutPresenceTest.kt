package com.eignex.klause.lp.cut

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.factor.table.Element
import com.eignex.klause.factor.table.Table
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.engine.CutAuxiliaryDefinition
import com.eignex.klause.lp.engine.CutExpression
import com.eignex.klause.lp.engine.CutPremise
import com.eignex.klause.lp.engine.CutProofFact
import com.eignex.klause.lp.engine.CutProvenance
import com.eignex.klause.lp.engine.CutSource
import com.eignex.klause.lp.engine.CutSourceKind
import com.eignex.klause.lp.engine.CutRoundingRule
import com.eignex.klause.lp.engine.CutWeightedRow
import com.eignex.klause.lp.engine.Relation
import com.eignex.klause.lp.engine.Cut
import com.eignex.klause.lp.relaxation.LpAuxiliarySources
import com.eignex.klause.lp.relaxation.CpToLpRelaxation
import com.eignex.klause.lp.relaxation.CutColumnSource
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

class SourceCutPresenceTest {
    @Test
    fun `reclaiming auxiliary catalog entries preserves immutable proofs without reusing identifiers`() {
        val root = Any()
        val catalog = LpAuxiliarySources()
        val a = CutAuxiliaryDefinition(listOf(1L), emptyList(), 4L, true)
        val b = CutAuxiliaryDefinition(listOf(2L), emptyList(), 4L, true)
        val old = catalog.source(a)
        val original = CutSourceMap(root, 0L, listOf(CutColumnSource(old)), auxiliaryDefinitions = mapOf(old to a))
        val cut = SourceCut(CutExpression(mapOf(old to BigFraction.ONE)), Relation.LE, BigFraction.ONE,
            CutProvenance(root, 0L, emptyList(), auxiliaryDefinitions = mapOf(old to a)))

        catalog.retain(emptySet())

        assertEquals(0, catalog.size)
        assertEquals(0L, catalog.storageUnits)
        assertNotEquals(old, catalog.source(b))
        val replacement = catalog.source(a)
        assertNotEquals(old, replacement)
        val current = CutSourceMap(
            root,
            1L,
            listOf(CutColumnSource(replacement)),
            auxiliaryDefinitions = mapOf(replacement to a),
        )
        assertNotNull(cut.toCut(original).orNull())
        val rebound = assertNotNull(cut.toCut(current).orNull())
        assertEquals(mapOf(replacement to a), assertNotNull(rebound.provenance).auxiliaryDefinitions)
        catalog.retain(setOf(a))
        assertEquals(1, catalog.size)
        assertEquals(replacement, catalog.source(a))
        assertTrue(catalog.storageWeight(setOf(a)).retired == 0L)
    }

    @Test
    fun `fresh assembly imports portable auxiliary cuts through definitions and source geometry`() {
        val problem = Problem(0, 2, Array(2) { IntDomain(0, 1) }, arrayOf(
            Table(intArrayOf(0, 1), longArrayOf(0L, 1L, 1L, 0L)),
            Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 0),
        ))
        val catalog = LpAuxiliarySources()
        val relaxer = CpToLpRelaxation(problem, null, tableHull = true, auxiliarySources = catalog)
        val original = relaxer.build(RootDomains(problem))
        val column = original.colPresence.indexOfFirst { it?.role?.last() == 0L }
        val source = assertNotNull(assertNotNull(original.sourceMap).column(column)).source
        val definition = assertNotNull(original.colPresence[column])
        val expression = CutExpression(mapOf(source to BigFraction.ONE))
        val proof = CutProvenance(problem, 0L, emptyList(),
            conclusion = CutPremise.Row(
                expression,
                Relation.GE,
                BigFraction.ONE,
            ), auxiliaryDefinitions = mapOf(source to definition))
        val stale = Cut(intArrayOf(999), longArrayOf(7L), Relation.LE, 999L, global = true, provenance = proof)
        catalog.retain(emptySet())
        catalog.source(CutAuxiliaryDefinition(listOf(-1L), emptyList(), 1L, true))

        val rebuilt = relaxer.build(RootDomains(problem), listOf(stale))

        assertEquals(original.model.m + 1, rebuilt.model.m)
        val mappedColumn = rebuilt.colPresence.indexOf(definition)
        val current = assertNotNull(assertNotNull(rebuilt.sourceMap).column(mappedColumn)).source
        assertNotEquals(source, current)
        val parent = assertNotNull(assertNotNull(rebuilt.sourceMap).parent(original.model.m))
        assertEquals(mapOf(current to definition), parent.auxiliaryDefinitions)
        assertEquals(
            CutPremise.Row(CutExpression(mapOf(current to BigFraction.ONE)), Relation.GE, BigFraction.ONE),
            parent.conclusion,
        )
        var coefficient = 0L
        rebuilt.model.forEachInColumn(mappedColumn) { row, value -> if (row == original.model.m) coefficient = value }
        assertEquals(-1L, coefficient)
        assertEquals(-1L, rebuilt.model.rhs.last())
    }

    @Test
    fun `dormant auxiliary cuts revive with complete proofs under equivalent definitions`() {
        val root = Any()
        val oldA = CutSource(CutSourceKind.AUXILIARY, 0)
        val oldB = CutSource(CutSourceKind.AUXILIARY, 1)
        val newA = CutSource(CutSourceKind.AUXILIARY, 7)
        val newB = CutSource(CutSourceKind.AUXILIARY, 8)
        val integer = CutSource(CutSourceKind.INTEGER, 0)
        val a = CutAuxiliaryDefinition(listOf(1L), emptyList(), 4L, true)
        val b = CutAuxiliaryDefinition(listOf(2L), emptyList(), 4L, false)
        val oldExpression = CutExpression(mapOf(oldA to BigFraction.ofLong(2L)))
        val newExpression = CutExpression(mapOf(newA to BigFraction.ofLong(2L)))
        val oldRow = CutPremise.Row(
            CutExpression(mapOf(oldA to BigFraction.ONE, oldB to BigFraction.ONE)),
            Relation.LE,
            BigFraction.ofLong(2L),
        )
        val newRow = oldRow.copy(expression = CutExpression(mapOf(newA to BigFraction.ONE, newB to BigFraction.ONE)))
        val oldFacts = listOf(
            CutProofFact(CutPremise.Bound(oldExpression, true, BigFraction.ONE, strict = true), false),
            CutProofFact(CutPremise.Integral(oldExpression), true),
            CutProofFact(CutPremise.Fixed(oldB, BigFraction.ofLong(2L)), false),
            CutProofFact(CutPremise.Excluded(integer, BigFraction.ofLong(3L)), false),
            CutProofFact(CutPremise.Literal(0), false),
            CutProofFact(oldRow, true),
            CutProofFact(CutPremise.ObjectiveCutoff(oldExpression, BigFraction.ONE), false),
        )
        val newFacts = listOf(
            CutProofFact(CutPremise.Bound(newExpression, true, BigFraction.ONE, strict = true), false),
            CutProofFact(CutPremise.Integral(newExpression), true),
            CutProofFact(CutPremise.Fixed(newB, BigFraction.ofLong(2L)), false),
            CutProofFact(CutPremise.Excluded(integer, BigFraction.ofLong(3L)), false),
            CutProofFact(CutPremise.Literal(0), false),
            CutProofFact(newRow, true),
            CutProofFact(CutPremise.ObjectiveCutoff(newExpression, BigFraction.ONE), false),
        )
        val proof = CutProvenance(root, 0L, oldFacts,
            rules = listOf(CutRoundingRule(2L, true, 3L, listOf(CutWeightedRow(oldRow, -5L)))),
            conclusion = CutPremise.Row(oldExpression, Relation.LE, BigFraction.ZERO),
            auxiliaryDefinitions = mapOf(oldA to a, oldB to b))
        val cut = SourceCut(oldExpression, Relation.LE, BigFraction.ZERO, proof)
        val pool = CutPool()
        val absent = CutSourceMap(root, 1L, emptyList())
        assertTrue(pool.add(cut, absent))
        assertTrue(pool.cuts().isEmpty())
        val present = CutSourceMap(root, 2L, listOf(CutColumnSource(newA)),
            activePremises = newFacts.filter { !it.global }.mapTo(HashSet()) { it.premise },
            fixed = mapOf(newB to BigFraction.ofLong(2L)), auxiliaryDefinitions = mapOf(newA to a, newB to b))

        assertTrue(pool.remap(present).isEmpty())

        val mapped = pool.cuts().single()
        assertEquals(listOf(0), mapped.cols.toList())
        assertEquals(listOf(2L), mapped.coeffs.toList())
        assertEquals(0L, mapped.rhs)
        val remapped = assertNotNull(mapped.provenance)
        assertEquals(newFacts, remapped.facts)
        assertEquals(mapOf(newA to a, newB to b), remapped.auxiliaryDefinitions)
        assertEquals(CutPremise.Row(newExpression, Relation.LE, BigFraction.ZERO), remapped.conclusion)
        assertEquals(listOf(CutWeightedRow(newRow, -5L)), remapped.rules.single().rows)
        assertEquals(listOf(2L, 3L), listOf(remapped.rules.single().divisor, remapped.rules.single().reduction))
        assertTrue(remapped.rules.single().mir)
        assertEquals(oldFacts, proof.facts)
        val equivalent = SourceCut(newExpression, Relation.LE, BigFraction.ZERO, remapped)
        assertEquals(cut.key, equivalent.key)
        assertFalse(pool.add(equivalent, present))
        assertEquals(1, pool.size)
        assertEquals(mapOf(CutMappingDecline.MISSING_SOURCE to 1), pool.remap(absent))
        assertTrue(pool.cuts().isEmpty())
        assertTrue(pool.remap(present).isEmpty())
        assertEquals(1, pool.cuts().size)
    }

    @Test
    fun `ambiguous auxiliary aliases decline instead of choosing a coordinate`() {
        val root = Any()
        val old = CutSource(CutSourceKind.AUXILIARY, 0)
        val a = CutSource(CutSourceKind.AUXILIARY, 1)
        val b = CutSource(CutSourceKind.AUXILIARY, 2)
        val definition = CutAuxiliaryDefinition(listOf(1L), emptyList(), 4L, true)
        val cut = SourceCut(CutExpression(mapOf(old to BigFraction.ONE)), Relation.LE, BigFraction.ONE,
            CutProvenance(root, 0L, emptyList(), auxiliaryDefinitions = mapOf(old to definition)))
        val map = CutSourceMap(root, 1L, listOf(CutColumnSource(a), CutColumnSource(b)),
            auxiliaryDefinitions = mapOf(a to definition, b to definition))

        assertEquals(CutMapping.Declined(CutMappingDecline.MISSING_SOURCE), cut.toCut(map))
    }

    @Test
    fun `integer affine expressions have an intrinsic source lattice`() {
        val integer = CutSource(CutSourceKind.INTEGER, 0)
        val boolean = CutSource(CutSourceKind.BOOLEAN, 0)
        val map = CutSourceMap(Any(), 0L, listOf(CutColumnSource(integer), CutColumnSource(boolean)))
        val expression = CutExpression(
            mapOf(integer to BigFraction.ofLong(-2), boolean to BigFraction.ONE),
            BigFraction.ofLong(9),
        )

        assertTrue(map.isGlobal(CutPremise.Integral(expression)))
    }

    @Test
    fun `fractional integer coordinates require explicit lattice evidence`() {
        val integer = CutSource(CutSourceKind.INTEGER, 0)
        val map = CutSourceMap(Any(), 0L, listOf(CutColumnSource(integer)))
        val expression = CutExpression(mapOf(integer to BigFraction.ofLong(2).reciprocal()))

        assertFalse(map.isGlobal(CutPremise.Integral(expression)))
    }

    @Test
    fun `generic terms do not acquire an intrinsic source lattice`() {
        val term = CutSource(CutSourceKind.TERM, 0)
        val map = CutSourceMap(Any(), 0L, listOf(CutColumnSource(term)))

        assertFalse(map.isGlobal(CutPremise.Integral(CutExpression(mapOf(term to BigFraction.ONE)))))
    }

    @Test
    fun `generic and auxiliary terms with equal ids retain distinct affine coordinates`() {
        val root = Any()
        val term = CutSource(CutSourceKind.TERM, 0)
        val auxiliary = CutSource(CutSourceKind.AUXILIARY, 0)
        val definition = CutAuxiliaryDefinition(listOf(1L), emptyList(), 4L, false)
        val map = CutSourceMap(
            root,
            0L,
            listOf(
                CutColumnSource(term, BigFraction.ofLong(-2), BigFraction.ONE),
                CutColumnSource(auxiliary, BigFraction.ofLong(2), BigFraction.ofLong(-3)),
            ),
            auxiliaryDefinitions = mapOf(auxiliary to definition),
        )
        val cut = SourceCut(
            CutExpression(
                mapOf(
                    term to BigFraction.ofLong(2).reciprocal(),
                    auxiliary to BigFraction.ofLong(3),
                ),
            ),
            Relation.LE,
            BigFraction.ofLong(5),
            CutProvenance(root, 0L, emptyList(), auxiliaryDefinitions = mapOf(auxiliary to definition)),
        )

        val mapped = assertNotNull(cut.toCut(map).orNull())

        assertEquals(listOf(0, 1), mapped.cols.toList())
        assertEquals(listOf(-1L, 6L), mapped.coeffs.toList())
        assertEquals(1L, mapped.rhs)
    }

    @Test
    fun `an auxiliary cannot use a generic term definition with the same id`() {
        val root = Any()
        val term = CutSource(CutSourceKind.TERM, 0)
        val auxiliary = CutSource(CutSourceKind.AUXILIARY, 0)
        val definition = CutAuxiliaryDefinition(listOf(1L), emptyList(), 4L, false)
        val map = CutSourceMap(
            root,
            0L,
            listOf(CutColumnSource(auxiliary)),
            auxiliaryDefinitions = mapOf(term to definition),
        )
        val cut = SourceCut(
            CutExpression(mapOf(auxiliary to BigFraction.ONE)),
            Relation.LE,
            BigFraction.ONE,
            CutProvenance(root, 0L, emptyList(), auxiliaryDefinitions = mapOf(auxiliary to definition)),
        )

        assertNull(cut.toCut(map).orNull())
    }

    @Test
    fun `auxiliary definition changes invalidate a portable cut`() {
        val root = Any()
        val auxiliary = CutSource(CutSourceKind.AUXILIARY, 0)
        val original = CutAuxiliaryDefinition(listOf(1L), emptyList(), 4L, false)
        val replacement = CutAuxiliaryDefinition(listOf(2L), emptyList(), 4L, false)
        val map = CutSourceMap(
            root,
            0L,
            listOf(CutColumnSource(auxiliary)),
            auxiliaryDefinitions = mapOf(auxiliary to replacement),
        )
        val cut = SourceCut(
            CutExpression(mapOf(auxiliary to BigFraction.ONE)),
            Relation.LE,
            BigFraction.ONE,
            CutProvenance(root, 0L, emptyList(), auxiliaryDefinitions = mapOf(auxiliary to original)),
        )

        assertNull(cut.toCut(map).orNull())
    }

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
