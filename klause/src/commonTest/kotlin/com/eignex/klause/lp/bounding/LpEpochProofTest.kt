package com.eignex.klause.lp.bounding

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.cut.SourceCut
import com.eignex.klause.lp.cut.orNull
import com.eignex.klause.lp.engine.Basis
import com.eignex.klause.lp.engine.Cut
import com.eignex.klause.lp.engine.CutAuxiliaryDefinition
import com.eignex.klause.lp.engine.CutInputRow
import com.eignex.klause.lp.engine.CutProvenance
import com.eignex.klause.lp.engine.CutRowTransform
import com.eignex.klause.lp.engine.CutSource
import com.eignex.klause.lp.engine.CutSourceKind
import com.eignex.klause.lp.engine.LpBuilder
import com.eignex.klause.lp.engine.LpExactState
import com.eignex.klause.lp.engine.LpModel
import com.eignex.klause.lp.engine.LpRowPremises
import com.eignex.klause.lp.engine.Relation
import com.eignex.klause.lp.engine.Sense
import com.eignex.klause.lp.engine.TableauCutProvenance
import com.eignex.klause.lp.engine.VarStatus
import com.eignex.klause.lp.engine.integerCertify
import com.eignex.klause.lp.relaxation.CpToLpRelaxation
import com.eignex.klause.lp.relaxation.CutSourceMap
import com.eignex.klause.lp.relaxation.LpExplanation
import com.eignex.klause.lp.relaxation.LpRelaxation
import com.eignex.klause.lp.relaxation.RootDomains
import com.eignex.klause.lp.relaxation.withModel
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.SolveStatsSink
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.IntHashSet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LpEpochProofTest {
    @Test
    fun `streamed proofs preserve duplicate and zero coefficient semantics`() {
        val problem = Problem(
            0,
            2,
            Array(2) { IntDomain(0, 5) },
            arrayOf(
                Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 7),
                Linear(intArrayOf(2, -1), intArrayOf(0, 1), LinearOp.LE, 3),
            ),
        )
        val relaxation = CpToLpRelaxation(problem, null, tidy = RelaxationTidyConfig(enabled = true))
            .build(RootDomains(problem))
        val proof = assertNotNull(relaxation.tidyProof)
        for (model in listOf(proof.derivation.sourceModel, proof.derivation.transformedModel)) {
            intArrayOf(1, 1, 0, 0).copyInto(model.csc.rowIdx)
            longArrayOf(4, 0, 2, 3).copyInto(model.csc.colVal)
        }

        assertTrue(proof.forEachRowProof { row, actual ->
            val expected = assertNotNull(proof.rowProof(row))
            assertEquals(expected.facts, actual.facts)
            assertEquals(expected.conclusion, actual.conclusion)
            val before = assertIs<CutRowTransform.Algebraic>(expected.transformations.single())
            val after = assertIs<CutRowTransform.Algebraic>(actual.transformations.single())
            assertEquals(before.input, after.input)
            assertEquals(before.conclusion, after.conclusion)
        })
    }

    @Test
    fun `streamed proofs preserve scanner metadata algebra and parent identity`() {
        for (fixed in listOf(false, true)) {
            val problem = Problem(
                0,
                3,
                arrayOf(if (fixed) IntDomain(1, 1) else IntDomain(0, 4), IntDomain(0, 5), IntDomain(0, 5)),
                arrayOf(
                    Linear(intArrayOf(2, 3), intArrayOf(0, 1), LinearOp.LE, 7),
                    Linear(intArrayOf(1, 2), intArrayOf(1, 2), LinearOp.LE, 8),
                ),
            )
            val relaxation = CpToLpRelaxation(problem, null, tidy = RelaxationTidyConfig(enabled = true))
                .build(RootDomains(problem))
            val original = assertNotNull(relaxation.tidyProof)
            val sources = original.sources
            val parent = assertNotNull(original.rowProof(0))
            for (withParent in listOf(false, true)) {
                val mapping = CutSourceMap(
                    sources.model,
                    sources.epoch,
                    sources.columns,
                    parent.facts.filter { it.global }.map { it.premise }.toSet(),
                    parentRows = if (withParent) mapOf(0 to parent) else emptyMap(),
                )
                val proof = assertNotNull(LpEpochProof.create(original.derivation, mapping))
                var visited = 0

                assertTrue(proof.forEachRowProof { row, actual ->
                    val expected = assertNotNull(proof.rowProof(row))
                    assertEquals(visited++, row)
                    assertSame(expected.model, actual.model)
                    assertEquals(expected.epoch, actual.epoch)
                    assertEquals(expected.assumptions, actual.assumptions)
                    assertEquals(expected.facts, actual.facts)
                    assertEquals(expected.rules, actual.rules)
                    assertEquals(expected.conclusion, actual.conclusion)
                    assertEquals(expected.auxiliaryDefinitions, actual.auxiliaryDefinitions)
                    assertEquals(expected.transformations.size, actual.transformations.size)
                    for ((left, right) in expected.transformations.zip(actual.transformations)) {
                        if (left is CutRowTransform.Algebraic) {
                            val algebraic = assertIs<CutRowTransform.Algebraic>(right)
                            assertEquals(left.input, algebraic.input)
                            assertEquals(left.conclusion, algebraic.conclusion)
                            assertEquals(left.multiplier, algebraic.multiplier)
                            assertEquals(left.inputStrict, algebraic.inputStrict)
                            assertEquals(left.outputStrict, algebraic.outputStrict)
                            assertEquals(left.fixings, algebraic.fixings)
                        } else {
                            assertEquals(left, right)
                        }
                    }
                    if (withParent && row == 0) assertSame(parent, proof.sources.parent(row))
                })
                assertEquals(relaxation.model.m, visited)
            }
        }
    }

    @Test
    fun `a late streamed mutation or decline rejects the entire batch`() {
        val problem = Problem(
            0,
            2,
            Array(2) { IntDomain(0, 5) },
            arrayOf(
                Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 7),
                Linear(intArrayOf(2, -1), intArrayOf(0, 1), LinearOp.LE, 3),
            ),
        )
        val relaxation = CpToLpRelaxation(problem, null, tidy = RelaxationTidyConfig(enabled = true))
            .build(RootDomains(problem))
        val proof = assertNotNull(relaxation.tidyProof)
        val source = proof.derivation.sourceModel
        for (target in listOf(source, proof.derivation.transformedModel)) {
            assertFalse(proof.forEachRowProof { row, _ ->
                if (row == 0) target.csc.colVal[0]++
            })
            target.csc.colVal[0]--
            assertTrue(proof.forEachRowProof { _, _ -> })
        }
        val last = source.m - 1
        source.rowGlobal[last] = false
        source.rowPremises[last] = null
        var visited = 0
        assertFalse(proof.forEachRowProof { _, _ -> visited++ })
        assertEquals(relaxation.model.m - 1, visited)
        assertNull(proof.rowProof(relaxation.model.m - 1))
        source.rowGlobal[last] = true
        assertTrue(proof.forEachRowProof { _, _ -> })
    }

    @Test
    fun `stream cancellation after a consumed proof rejects partial results`() {
        val problem = Problem(
            0,
            2,
            Array(2) { IntDomain(0, 5) },
            arrayOf(Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 7)),
        )
        val relaxation = CpToLpRelaxation(problem, null, tidy = RelaxationTidyConfig(enabled = true))
            .build(RootDomains(problem))
        val proof = assertNotNull(relaxation.tidyProof)
        var cancelled = false

        assertFalse(proof.forEachRowProof(Cancellation { cancelled }) { _, _ -> cancelled = true })
        assertTrue(proof.forEachRowProof { _, _ -> })
    }

    @Test
    fun `binding detects and recovers from mutations of the same model`() {
        val problem = Problem(
            0,
            2,
            Array(2) { IntDomain(-3, 5) },
            arrayOf(
                Linear(intArrayOf(2, 3), intArrayOf(0, 1), LinearOp.LE, 8),
                Linear(intArrayOf(3, -2), intArrayOf(0, 1), LinearOp.LE, 7),
            ),
        )
        val relaxation = CpToLpRelaxation(problem, null, tidy = RelaxationTidyConfig(enabled = true))
            .build(RootDomains(problem))
        val proof = assertNotNull(relaxation.tidyProof)
        val model = relaxation.model
        val row = model.m - 1
        val column = model.n - 1
        val mutations = listOf<Pair<String, () -> Unit>>(
            "coefficient" to { model.csc.colVal[model.csc.colVal.lastIndex] = model.csc.colVal.last() xor 1L },
            "row index" to { model.csc.rowIdx[model.csc.rowIdx.lastIndex] = model.csc.rowIdx.last() xor 1 },
            "column pointer" to { model.csc.colPtr[model.n] = model.csc.colPtr[model.n] xor 1 },
            "rhs" to { model.rhs[row] = model.rhs[row] xor 1L },
            "cost" to { model.cost[column] = model.cost[column] xor 1L },
            "slack cost" to { model.cost[model.n + row] = model.cost[model.n + row] xor 1L },
            "origin" to { model.loShift[column] = model.loShift[column] xor 1L },
            "tag" to { model.tag[column] = model.tag[column] xor 1 },
            "continuous flag" to { model.colContinuous[column] = !model.colContinuous[column] },
            "lower clamp" to { model.probeClampedLo[column] = !model.probeClampedLo[column] },
            "upper clamp" to { model.probeClampedHi[column] = !model.probeClampedHi[column] },
            "strict row" to { model.rowStrict[row] = !model.rowStrict[row] },
            "global row" to { model.rowGlobal[row] = !model.rowGlobal[row] },
            "slack presence" to { model.hasUpper[model.n + row] = !model.hasUpper[model.n + row] },
        )

        for ((name, mutate) in mutations) {
            mutate()
            assertNull(proof.bind(model, relaxation.sourceMap), name)
            mutate()
            val rebound = assertNotNull(proof.bind(model, relaxation.sourceMap), name)
            assertSame(model, rebound.model)
            assertSame(relaxation.sourceMap, rebound.sources)
        }
        assertNotNull(proof.rowProof(row))
    }

    @Test
    fun `binding compares premise arrays with their construction snapshot`() {
        val problem = Problem(
            1,
            2,
            Array(2) { IntDomain(-3, 5) },
            arrayOf(Linear(intArrayOf(2, 3), intArrayOf(0, 1), LinearOp.LE, 8)),
        )
        val plain = CpToLpRelaxation(problem, null).build(RootDomains(problem))
        val premise = LpRowPremises(intArrayOf(0), booleanArrayOf(true), longArrayOf(5), intArrayOf(0))
        plain.model.rowGlobal[0] = false
        plain.model.rowPremises[0] = premise
        val relaxation = assertIs<RelaxationTidyResult.Applied>(
            RelaxationTidy.apply(
                plain,
                RelaxationTidyScope(problem, assertNotNull(plain.sourceMap).epoch, null, true),
                RelaxationTidyConfig(enabled = true),
            ),
        ).relaxation
        val proof = assertNotNull(relaxation.tidyProof)
        val mutations = listOf<Pair<String, () -> Unit>>(
            "variable" to { premise.vars[0] = premise.vars[0] xor 1 },
            "side" to { premise.isUpper[0] = !premise.isUpper[0] },
            "threshold" to { premise.thresholds[0] = premise.thresholds[0] xor 1L },
            "literal" to { premise.boolLits[0] = premise.boolLits[0] xor 1 },
        )

        for ((name, mutate) in mutations) {
            mutate()
            assertNull(proof.bind(relaxation.model, relaxation.sourceMap), name)
            mutate()
            assertNotNull(proof.bind(relaxation.model, relaxation.sourceMap), name)
        }
        relaxation.model.rowPremises[0] = null
        assertNull(proof.bind(relaxation.model, relaxation.sourceMap))
        relaxation.model.rowPremises[0] = premise
        assertNotNull(proof.bind(relaxation.model, relaxation.sourceMap))
    }

    @Test
    fun `binding accepts recentered narrowing and rejects widening`() {
        val problem = Problem(
            0,
            2,
            Array(2) { IntDomain(-3, 5) },
            arrayOf(Linear(intArrayOf(2, 3), intArrayOf(0, 1), LinearOp.LE, 8)),
        )
        val objective = LinearObjective(intCoefficients = longArrayOf(3, -2))
        val relaxation = CpToLpRelaxation(problem, objective, tidy = RelaxationTidyConfig(enabled = true))
            .build(RootDomains(problem))
        val proof = assertNotNull(relaxation.tidyProof)
        val model = relaxation.model
        for (offset in listOf(-1L, 1L)) {
            val lower = model.loShift.copyOf().also { it[0] += offset }
            val upper = LongArray(model.n) { model.loShift[it] + model.upper[it] }
            val candidate = model.rebind(lower, upper)
            val mapping = assertNotNull(relaxation.sourceMap).withBounds(candidate)

            val rebound = proof.bind(candidate, mapping)

            if (offset < 0L) {
                assertNull(rebound)
            } else {
                assertSame(candidate, assertNotNull(rebound).model)
                assertSame(mapping, rebound.sources)
                assertNotNull(rebound.rowProof(0))
            }
        }
    }

    @Test
    fun `binding preserves complete tags and scalar model identity checks`() {
        val problem = Problem(
            0,
            2,
            Array(2) { IntDomain(-3, 5) },
            arrayOf(Linear(intArrayOf(2, 3), intArrayOf(0, 1), LinearOp.LE, 8)),
        )
        val relaxation = CpToLpRelaxation(problem, null, tidy = RelaxationTidyConfig(enabled = true))
            .build(RootDomains(problem))
        val proof = assertNotNull(relaxation.tidyProof)
        val model = relaxation.model
        for (changed in listOf("extra tag", "missing tag", "constant", "sense", "exact state")) {
            val candidate = LpModel(
                model.n, model.m, model.csc, model.rhs, model.cost, model.upper, model.hasUpper,
                model.loShift, model.objConstant + if (changed == "constant") 1L else 0L,
                if (changed == "sense") Sense.MAXIMIZE else model.sense,
                when (changed) {
                    "extra tag" -> model.tag + 0
                    "missing tag" -> model.tag.copyOf(model.tag.size - 1)
                    else -> model.tag
                },
                rowGlobal = model.rowGlobal, rowStrict = model.rowStrict, rowPremises = model.rowPremises,
                flippedRhs = model.flippedRhs, probeClampedLo = model.probeClampedLo,
                probeClampedHi = model.probeClampedHi, colContinuous = model.colContinuous,
                exactState = if (changed == "exact state") {
                    LpExactState(assertNotNull(model.trailModel()))
                } else {
                    null
                },
            )

            assertNull(proof.bind(candidate, relaxation.sourceMap), changed)
        }
    }

    @Test
    fun `binding distinguishes empty premises from absent premises`() {
        val problem = Problem(
            0,
            2,
            Array(2) { IntDomain(-3, 5) },
            arrayOf(Linear(intArrayOf(2, 3), intArrayOf(0, 1), LinearOp.LE, 8)),
        )
        val relaxation = CpToLpRelaxation(problem, null, tidy = RelaxationTidyConfig(enabled = true))
            .build(RootDomains(problem))
        val proof = assertNotNull(relaxation.tidyProof)
        val model = relaxation.model
        assertNull(model.rowPremises[0])
        model.rowPremises[0] = LpRowPremises(intArrayOf(), booleanArrayOf(), longArrayOf())
        assertNull(proof.bind(model, relaxation.sourceMap))
        model.rowPremises[0] = null

        assertNotNull(proof.bind(model, relaxation.sourceMap))
    }

    @Test
    fun `binding ignores absent bound payloads`() {
        val problem = Problem(
            0,
            2,
            Array(2) { IntDomain(-3, 5) },
            arrayOf(Linear(intArrayOf(2, 3), intArrayOf(0, 1), LinearOp.LE, 8)),
        )
        val relaxation = CpToLpRelaxation(problem, null, tidy = RelaxationTidyConfig(enabled = true))
            .build(RootDomains(problem))
        val proof = assertNotNull(relaxation.tidyProof)
        val model = relaxation.model
        assertFalse(model.hasUpper[model.n])
        model.upper[model.n] = Long.MIN_VALUE

        assertNotNull(proof.bind(model, relaxation.sourceMap))
    }

    @Test
    fun `binding rejects changed source mappings and parent proofs`() {
        val problem = Problem(
            0,
            2,
            Array(2) { IntDomain(-3, 5) },
            arrayOf(Linear(intArrayOf(2, 3), intArrayOf(0, 1), LinearOp.LE, 8)),
        )
        val relaxation = CpToLpRelaxation(problem, null, tidy = RelaxationTidyConfig(enabled = true))
            .build(RootDomains(problem))
        val proof = assertNotNull(relaxation.tidyProof)
        val sources = assertNotNull(relaxation.sourceMap)
        for (changed in listOf("columns", "assumptions", "parent", "auxiliary")) {
            val mapping = CutSourceMap(
                sources.model,
                sources.epoch,
                if (changed == "columns") sources.columns.reversed() else sources.columns,
                assumptions = if (changed == "assumptions") setOf("assumption") else sources.assumptions,
                parentRows = if (changed == "parent") {
                    mapOf(0 to CutProvenance(sources.model, sources.epoch, emptyList()))
                } else {
                    emptyMap()
                },
                auxiliaryDefinitions = if (changed == "auxiliary") {
                    mapOf(
                        CutSource(CutSourceKind.AUXILIARY, 99) to
                            CutAuxiliaryDefinition(listOf(1L), emptyList(), 1L, true),
                    )
                } else {
                    sources.auxiliaryDefinitions
                },
            )

            assertNull(proof.bind(relaxation.model, mapping), changed)
        }
        assertNotNull(proof.bind(relaxation.model, sources))
    }

    @Test
    fun `binding retains the identity of a saved parent proof`() {
        val problem = Problem(
            0,
            2,
            Array(2) { IntDomain(-3, 5) },
            arrayOf(Linear(intArrayOf(2, 3), intArrayOf(0, 1), LinearOp.LE, 8)),
        )
        val relaxation = CpToLpRelaxation(problem, null, tidy = RelaxationTidyConfig(enabled = true))
            .build(RootDomains(problem))
        val sources = assertNotNull(relaxation.sourceMap)
        val parent = CutProvenance(sources.model, sources.epoch, emptyList())
        val mapping = CutSourceMap(
            sources.model,
            sources.epoch,
            sources.columns,
            parentRows = mapOf(0 to parent),
        )
        val proof = assertNotNull(LpEpochProof.create(assertNotNull(relaxation.tidyDerivation), mapping))
        for (preserve in listOf(true, false)) {
            val next = CutSourceMap(
                sources.model,
                sources.epoch,
                sources.columns,
                parentRows = mapOf(
                    0 to if (preserve) parent else CutProvenance(sources.model, sources.epoch, emptyList()),
                ),
            )

            val rebound = proof.bind(relaxation.model, next)

            if (preserve) assertNotNull(rebound) else assertNull(rebound)
        }
    }

    @Test
    fun `cancellation interrupts fixed substitution within a wide row`() {
        val size = 64
        val problem = Problem(
            0,
            size,
            Array(size) { IntDomain(0, 1) },
            arrayOf(
                Linear(IntArray(size) { 1 }, IntArray(size) { it }, LinearOp.LE, size / 2),
            ),
        )
        val session = PropagationSession(problem)
        repeat(size) { session.implyIntAtMost(it, 0) }
        val plain = CpToLpRelaxation(problem, null).build(session)
        var checks = 0

        val result = RelaxationTidy.apply(
            plain,
            RelaxationTidyScope(
                problem,
                assertNotNull(plain.sourceMap).epoch,
                null,
                true,
                searchRoot = assertNotNull(LpEpochRoot.capture(session)),
            ),
            RelaxationTidyConfig(enabled = true, cancellation = Cancellation { ++checks >= 16 }),
        )

        assertEquals(RelaxationTidyDecline.CANCELLED, assertIs<RelaxationTidyResult.Declined>(result).reason)
        assertEquals(0, result.stats.applied(RelaxationTidyRule.FIXED_SUBSTITUTION))
        assertNull(plain.tidyProof)
    }

    @Test
    fun `cancellation during validation leaves a checked proof reusable`() {
        val size = 32
        val problem = Problem(
            0,
            size,
            Array(size) { IntDomain(0, 1) },
            arrayOf(
                Linear(IntArray(size) { 1 }, IntArray(size) { it }, LinearOp.LE, size / 2),
            ),
        )
        val plain = CpToLpRelaxation(problem, null).build(RootDomains(problem))
        val tidy = assertIs<RelaxationTidyResult.Applied>(
            RelaxationTidy.apply(
                plain,
                RelaxationTidyScope(problem, assertNotNull(plain.sourceMap).epoch, null, true),
                RelaxationTidyConfig(enabled = true),
            ),
        )
        var checks = 0

        val proof = LpEpochProof.create(
            tidy.derivation,
            assertNotNull(tidy.relaxation.sourceMap),
            Cancellation { ++checks >= 16 },
        )

        assertNull(proof)
        assertEquals(16, checks)
        assertTrue(tidy.derivation.validate())
        assertNotNull(tidy.relaxation.tidyProof?.rowProof(0))
    }

    @Test
    fun `cancellation interrupts basis remapping without consuming the donor basis`() {
        val size = 32
        val problem = Problem(
            0,
            size,
            Array(size) { IntDomain(0, 1) },
            arrayOf(
                Linear(IntArray(size) { 1 }, IntArray(size) { it }, LinearOp.LE, size / 2),
            ),
        )
        val plain = CpToLpRelaxation(problem, null).build(RootDomains(problem))
        val tidy = assertIs<RelaxationTidyResult.Applied>(
            RelaxationTidy.apply(
                plain,
                RelaxationTidyScope(problem, assertNotNull(plain.sourceMap).epoch, null, true),
                RelaxationTidyConfig(enabled = true),
            ),
        ).relaxation
        val basis = Basis(intArrayOf(size), Array(size + 1) { if (it == size) VarStatus.BASIC else VarStatus.AT_LOWER })
        var checks = 0

        val mapped = LpEpochState.remapBasis(tidy, tidy, basis, Cancellation { ++checks >= 16 })

        assertNull(mapped)
        assertEquals(16, checks)
        assertEquals(size, basis.basicVars.single())
        assertNotNull(LpEpochState.remapBasis(tidy, tidy, basis))
    }

    @Test
    fun `conditional substitution retains its premises after exact recentering`() {
        val problem = Problem(
            0,
            3,
            arrayOf(IntDomain(0, 3), IntDomain(-3, 5), IntDomain(-2, 4)),
            arrayOf(Linear(intArrayOf(3, 2, 3), intArrayOf(0, 1, 2), LinearOp.LE, 13)),
        )
        val objective = LinearObjective(intCoefficients = longArrayOf(-1, 2, 0))
        val session = PropagationSession(problem)
        session.implyIntAtLeast(0, 1)
        session.implyIntAtMost(0, 1)
        val root = assertNotNull(LpEpochRoot.capture(session))
        val plain = CpToLpRelaxation(problem, objective).build(session)
        val result = assertIs<RelaxationTidyResult.Applied>(
            RelaxationTidy.apply(
                plain,
                RelaxationTidyScope(problem, assertNotNull(plain.sourceMap).epoch, objective, true, searchRoot = root),
                RelaxationTidyConfig(enabled = true),
            ),
        )
        assertTrue(result.derivation.rowMaps.any { it.fixings.any { fixing -> !fixing.global } })
        session.implyIntAtLeast(1, -1)
        val rebound = LpEngine(problem, objective, LpParams(), SolveStatsSink(backend = "epoch-proof")).use {
            assertNotNull(it.cpAdapter.relaxation(result.relaxation, session))
        }
        assertSame(result.derivation, rebound.tidyDerivation)
        assertNotNull(rebound.tidyProof)
        val literals = IntArrayList()
        assertTrue(
            LpExplanation.addRowPremiseLits(
                literals,
                IntHashSet(),
                rebound,
                IntArray(rebound.model.m) { it },
                session,
            ),
        )
        assertTrue(session.boundGeLit(0, 1, positive = false) in literals.toIntArray())
        assertTrue(session.boundLeLit(0, 1, positive = false) in literals.toIntArray())
        assertFalse(assertNotNull(rebound.tidyProof.rowProof(0)).global)
        assertEquals(1, assertNotNull(rebound.tidyProof.rowProof(0)).transformations.size)
        assertFalse(rebound.tidyProof.active(PropagationSession(problem)))
        val donor = Basis(
            IntArray(rebound.model.m) { rebound.model.n + it },
            Array(rebound.model.numVars) {
                if (it >= rebound.model.n) VarStatus.BASIC else VarStatus.AT_LOWER
            },
        )
        val mapped = assertNotNull(LpEpochState.remapBasis(rebound, rebound, donor))
        assertTrue(donor.basicVars.contentEquals(mapped.basicVars))
        assertTrue(donor.status.contentEquals(mapped.status))
    }

    @Test
    fun `a transformed model cannot lose its proof on incompatible replacement`() {
        val problem = Problem(
            0,
            2,
            Array(2) { IntDomain(-3, 5) },
            arrayOf(Linear(intArrayOf(2, 3), intArrayOf(0, 1), LinearOp.LE, 8)),
        )
        val session = PropagationSession(problem)
        val plain = CpToLpRelaxation(problem, null).build(session)
        val result = assertIs<RelaxationTidyResult.Applied>(
            RelaxationTidy.apply(
                plain,
                RelaxationTidyScope(problem, assertNotNull(plain.sourceMap).epoch, null, true),
                RelaxationTidyConfig(enabled = true),
            ),
        )
        val foreign = CpToLpRelaxation(
            Problem(0, 2, Array(2) { IntDomain(-3, 5) }, problem.factors),
            null,
        ).build(session)
        assertFailsWith<IllegalArgumentException> { result.relaxation.withModel(foreign.model, foreign.sourceMap) }
    }

    @Test
    fun `a lattice cut retains substitution and rounding through source remapping`() {
        val problem = Problem(
            0,
            2,
            arrayOf(IntDomain(1, 1), IntDomain(0, 5)),
            arrayOf(Linear(intArrayOf(2, 3), intArrayOf(0, 1), LinearOp.LE, 7)),
        )
        val plain = CpToLpRelaxation(problem, null).build(RootDomains(problem))
        val tidy = assertIs<RelaxationTidyResult.Applied>(
            RelaxationTidy.apply(
                plain,
                RelaxationTidyScope(problem, assertNotNull(plain.sourceMap).epoch, null, true),
                RelaxationTidyConfig(enabled = true),
            ),
        ).relaxation
        val row = 0
        val columns = (0 until tidy.model.n).filter { column ->
            var present = false
            tidy.model.forEachInColumn(column) { index, value -> if (index == row && value != 0L) present = true }
            present
        }.toIntArray()
        val coefficients = LongArray(columns.size) { k ->
            var coefficient = 0L
            tidy.model.forEachInColumn(columns[k]) { index, value -> if (index == row) coefficient = value }
            coefficient
        }
        val cut = Cut(
            columns,
            coefficients,
            Relation.LE,
            tidy.model.flippedRhs[row],
            global = true,
            tableau = TableauCutProvenance(
                tidy.model,
                emptyList(),
                listOf(
                    CutInputRow(
                        row,
                        true,
                        1L,
                        BigFraction.ofLong(tidy.model.flippedRhs[row]),
                        Relation.LE,
                        columns,
                        coefficients,
                        null,
                    ),
                ),
                1L,
                false,
            ),
        )

        val portable = assertNotNull(SourceCut.fromCut(cut, tidy).orNull())
        val restored = assertNotNull(portable.toCut(assertNotNull(plain.sourceMap)).orNull())

        val algebraic = assertIs<CutRowTransform.Algebraic>(portable.provenance.transformations[0])
        val lattice = assertIs<CutRowTransform.Lattice>(portable.provenance.transformations[1])
        assertEquals(algebraic.conclusion, lattice.input)
        assertEquals(1, algebraic.fixings.size)
        assertEquals(portable.provenance.transformations, assertNotNull(restored.provenance).transformations)
        for (y in 0L..5L) {
            if (2L + 3L * y > 7L) continue
            val assignment = longArrayOf(1L, y)
            val original = algebraic.input.expression.value { BigFraction.ofLong(assignment[it.id]) }
            val fixed = algebraic.conclusion.expression.value { BigFraction.ofLong(assignment[it.id]) }
            val rounded = lattice.conclusion.expression.value { BigFraction.ofLong(assignment[it.id]) }
            assertTrue(original <= algebraic.input.rhs)
            assertTrue(fixed <= algebraic.conclusion.rhs)
            assertTrue(rounded <= lattice.conclusion.rhs)
        }
    }

    @Test
    fun `target exclusion preserves both conditional fixing sides used by a row`() {
        val problem = Problem(
            0,
            3,
            arrayOf(IntDomain(0, 3), IntDomain(0, 5), IntDomain(0, 20)),
            arrayOf(Linear(intArrayOf(2, 3, -1), intArrayOf(0, 1, 2), LinearOp.LE, 10)),
        )
        val objective = LinearObjective(intCoefficients = longArrayOf(0, 0, 1))
        val session = PropagationSession(problem)
        session.implyIntAtLeast(0, 1)
        session.implyIntAtMost(0, 1)
        session.implyIntAtMost(2, 10)
        val plain = CpToLpRelaxation(problem, objective).build(session)
        val tidy = assertIs<RelaxationTidyResult.Applied>(
            RelaxationTidy.apply(
                plain,
                RelaxationTidyScope(
                    problem,
                    assertNotNull(plain.sourceMap).epoch,
                    objective,
                    true,
                    searchRoot = assertNotNull(LpEpochRoot.capture(session)),
                ),
                RelaxationTidyConfig(enabled = true),
            ),
        ).relaxation
        val certificate = assertNotNull(integerCertify(tidy.model, DoubleArray(tidy.model.m) { -1.0 }, scaleBits = 0))

        val reason = assertNotNull(
            reducedCostFixingReasons(tidy, certificate, session, 2, 10),
        ).reasonFor(tidy.intColOf[0])

        assertTrue(session.boundGeLit(0, 1, positive = false) in reason)
        assertTrue(session.boundLeLit(0, 1, positive = false) in reason)
        assertTrue(session.boundLeLit(2, 10, positive = false) in reason)
    }

    @Test
    fun `aliased source columns retain the other certificate endpoint`() {
        val problem = Problem(
            0,
            2,
            arrayOf(IntDomain(0, 3), IntDomain(0, 10)),
            arrayOf(Linear(intArrayOf(2, -1), intArrayOf(0, 1), LinearOp.LE, 0)),
        )
        val session = PropagationSession(problem)
        session.implyIntAtLeast(0, 1)
        session.implyIntAtMost(0, 1)
        val builder = LpBuilder()
        builder.addVar(1L, 1L)
        builder.addVar(1L, 1L)
        builder.addVar(2L, 10L, cost = 1L)
        builder.addRow(intArrayOf(0, 1, 2), longArrayOf(1L, 1L, -1L), Relation.LE, 0L)
        val model = builder.build(Sense.MINIMIZE)
        val relaxation = LpRelaxation(model, intArrayOf(0, 0, 1), BooleanArray(3), 0L, intArrayOf(0, 2), intArrayOf())
        val certificate = assertNotNull(integerCertify(model, doubleArrayOf(-1.0), scaleBits = 0))

        val reasons = assertNotNull(reducedCostFixingReasons(relaxation, certificate, session, 1, 10))

        assertTrue(session.boundGeLit(0, 1, positive = false) in reasons.reasonFor(0))
        assertTrue(session.boundGeLit(0, 1, positive = false) in reasons.reasonFor(1))
    }
}
