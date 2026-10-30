package com.eignex.klause.lp.relaxation

import com.eignex.klause.factor.arithmetic.ArrayMinMax
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedRealLinear
import com.eignex.klause.factor.table.Element
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.engine.CutAuxiliaryDefinition
import com.eignex.klause.lp.engine.LpExactState
import com.eignex.klause.lp.engine.LpScopedSolver
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.FactorProjectionProviders
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LpRetainedSourcesTest {
    @Test
    fun `retained template imports should preserve wide exact origins and open sides`() {
        val modes = listOf(false to false, true to false, true to true)
        for ((templates, metadata) in modes) for (open in listOf(false, true)) {
            val origin = 9_007_199_254_740_993L
            val problem = Problem(0, 2, Array(2) { IntDomain(origin, origin + 4L) }, arrayOf<Factor>(
                Linear(longArrayOf(1L, 1L), intArrayOf(0, 1), LinearOp.GE, 2L * origin + 3L),
            ), openIntHi = booleanArrayOf(false, open))
            val consumer = CpToLpRelaxation(problem, LinearObjective(intCoefficients = longArrayOf(1L, 1L)),
                projections = FactorProjectionProviders.Default.prepare(problem),
                reuseSourceTemplates = templates, reuseTemplateMetadata = metadata)
            val sources = LpRetainedSources(problem, consumer)
            LpScopedSolver(LpExactState(LpRetainedSources.emptyModel())).use { owner ->
                val edit = assertNotNull(sources.prepare(owner.state, RootDomains(problem)))
                val assertions = assertNotNull(edit.boundAssertions(0L))
                assertTrue(owner.replaceRows(edit.retired, edit.columns, edit.rows, false,
                    permanentRows = edit.permanentRows, objective = edit.objective, assertions = assertions))
                edit.commit()

                val result = assertNotNull(owner.solve())

                assertEquals(BigFraction.ofLong(2L * origin + 3L), result.lowerBound)
                assertEquals(BigFraction.ofLong(origin), owner.state.model.column(0).origin.value)
                assertEquals(open, owner.state.model.column(1).bounds.upper == null)
                if (templates) assertSame(assertNotNull(consumer.preparationTemplates).bounds.getValue(0).column,
                    owner.state.model.column(0))
            }
        }
    }

    @Test
    fun `selected element groups retain exact joint bounds across holes and pop`() {
        val problem = Problem(
            0, 5, arrayOf(IntDomain(0, 2), IntDomain(0, 2), IntDomain(0, 1), IntDomain(0, 2), IntDomain(0, 2)),
            arrayOf(
                Element(0, 1, longArrayOf(2, 0, 2), false, 0),
                Element(0, 2, longArrayOf(7, 0, 1, 0), false, -1),
                Element(3, 4, longArrayOf(2, 1, 2), false, 0),
            ),
        )
        var live = problem.finiteIntDomain(0)
        val domains = object : RelaxationDomains {
            override fun intDomain(varId: Int): IntDomain = if (varId == 0) live else problem.finiteIntDomain(varId)
            override fun boolValue(varId: Int): Boolean? = null
        }
        val sources = LpRetainedSources(
            problem,
            CpToLpRelaxation(
                problem, LinearObjective(intCoefficients = longArrayOf(0, 1, 1, 0, 1)),
                elementHull = true, elementIndexes = setOf(0),
            ),
        )
        LpScopedSolver(LpExactState(LpRetainedSources.emptyModel())).use { owner ->
            val root = assertNotNull(sources.prepare(owner.state, domains))
            assertTrue(owner.replaceRows(root.retired, root.columns, root.rows, false, objective = root.objective))
            root.commit()
            val relaxation = sources.relaxation(owner.state, domains)
            val column = relaxation.colReq.indices.single { relaxation.colReq[it].contentEquals(longArrayOf(0, 1)) }
            assertEquals(3, relaxation.colReq.count { it != null })
            assertEquals(3, assertNotNull(relaxation.sourceMap).auxiliaryDefinitions.size)
            assertEquals(BigFraction.ONE, assertNotNull(owner.solve()).lowerBound)
            assertTrue(owner.push())
            live = live.excludeValue(1L)

            val child = assertNotNull(sources.prepare(owner.state, domains))

            assertTrue(child.columns.isEmpty())
            assertTrue(owner.replaceRows(child.retired, child.columns, child.rows, true, objective = child.objective))
            child.commit()
            val absent = assertNotNull(sources.liveBounds(domains)[column].upper)
            assertEquals(BigFraction.ZERO, absent.number.value)
            assertTrue(owner.assertBound(column, true, absent, 7L))
            assertEquals(BigFraction.ofLong(2L), assertNotNull(owner.solve()).lowerBound)
            assertTrue(owner.pop(0))
            sources.retract(0)
            live = problem.finiteIntDomain(0)
            val restored = assertNotNull(sources.prepare(owner.state, domains))
            assertTrue(
                owner.replaceRows(
                    restored.retired, restored.columns, restored.rows, false, objective = restored.objective,
                ),
            )
            restored.commit()
            assertEquals(BigFraction.ONE, assertNotNull(sources.liveBounds(domains)[column].upper).number.value)
            assertEquals(BigFraction.ONE, assertNotNull(owner.solve()).lowerBound)
            assertEquals(relaxation.model.n, owner.state.model.n)
        }
    }

    @Test
    fun `unrepresentable emission should decline without publishing a partial layout`() {
        val problem = Problem(
            0,
            1,
            arrayOf(IntDomain(0L, 1L)),
            emptyArray(),
            numRealVars = 1,
            realLower = doubleArrayOf(0.0),
            realUpper = doubleArrayOf(1.0),
        )
        val objective = LinearObjective(
            intCoefficients = longArrayOf(9007199254740993L),
            realCoefficients = doubleArrayOf(1.0),
        )
        val sources = LpRetainedSources(problem, CpToLpRelaxation(problem, objective))
        val state = LpExactState(LpRetainedSources.emptyModel())
        val domains = RootDomains(problem)

        val edit = sources.prepare(state, domains)

        assertNull(edit)
        assertEquals(0, sources.relaxation(state, domains).model.n)
    }

    @Test
    fun `live integer bounds should preserve exact origins and earlier snapshots through nested pops`() {
        for (origin in listOf(5L, Long.MIN_VALUE + 1L, Long.MAX_VALUE - 5L)) {
            val problem = Problem(0, 1, arrayOf(IntDomain(origin, origin + 4L)), emptyArray())
            val session = PropagationSession(problem)
            val domains = SessionDomains(session)
            val sources = LpRetainedSources(
                problem,
                CpToLpRelaxation(problem, LinearObjective(intCoefficients = longArrayOf(1L))),
            )
            LpScopedSolver(LpExactState(LpRetainedSources.emptyModel())).use { owner ->
                val edit = assertNotNull(sources.prepare(owner.state, domains))
                assertTrue(owner.replaceRows(edit.retired, edit.columns, edit.rows, false, objective = edit.objective))
                edit.commit()
                val column = sources.relaxation(owner.state, domains).intColOf[0]
                val root = sources.liveBounds(domains)[column]

                session.pinIntAtLeast(0, origin + 2L)
                val child = sources.liveBounds(domains)[column]
                session.pinIntAtMost(0, origin + 3L)
                val nested = sources.liveBounds(domains)[column]
                session.popLast()
                val parent = sources.liveBounds(domains)[column]
                session.popLast()
                val restored = sources.liveBounds(domains)[column]
                session.pinIntAtMost(0, origin + 1L)
                val sibling = sources.liveBounds(domains)[column]

                assertEquals(BigFraction.ZERO, assertNotNull(root.lower).number.value)
                assertEquals(BigFraction.ofLong(4L), assertNotNull(root.upper).number.value)
                assertEquals(BigFraction.ofLong(2L), assertNotNull(child.lower).number.value)
                assertEquals(BigFraction.ofLong(4L), assertNotNull(child.upper).number.value)
                assertEquals(BigFraction.ofLong(2L), assertNotNull(nested.lower).number.value)
                assertEquals(BigFraction.ofLong(3L), assertNotNull(nested.upper).number.value)
                assertEquals(child, parent)
                assertEquals(root, restored)
                assertEquals(BigFraction.ZERO, assertNotNull(sibling.lower).number.value)
                assertEquals(BigFraction.ONE, assertNotNull(sibling.upper).number.value)
            }
        }
    }

    @Test
    fun `live bounds should distinguish open source sides from finite search bounds`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(5L, 9L)), emptyArray(), openIntHi = booleanArrayOf(true))
        val rootDomains = RootDomains(problem)
        val searchDomains = SessionDomains(PropagationSession(problem))
        val sources = LpRetainedSources(
            problem,
            CpToLpRelaxation(problem, LinearObjective(intCoefficients = longArrayOf(1L))),
        )
        LpScopedSolver(LpExactState(LpRetainedSources.emptyModel())).use { owner ->
            val edit = assertNotNull(sources.prepare(owner.state, rootDomains))
            assertTrue(owner.replaceRows(edit.retired, edit.columns, edit.rows, false, objective = edit.objective))
            edit.commit()
            val column = sources.relaxation(owner.state, rootDomains).intColOf[0]
            val root = sources.liveBounds(rootDomains)[column]

            val finite = sources.liveBounds(searchDomains)[column]
            val reopened = sources.liveBounds(rootDomains)[column]

            assertEquals(BigFraction.ZERO, assertNotNull(root.lower).number.value)
            assertNull(root.upper)
            assertEquals(BigFraction.ZERO, assertNotNull(finite.lower).number.value)
            assertEquals(BigFraction.ofLong(4L), assertNotNull(finite.upper).number.value)
            assertEquals(root, reopened)
        }
    }

    @Test
    fun `live auxiliary bounds should follow membership changes without altering earlier snapshots`() {
        val problem = Problem(
            0,
            2,
            arrayOf(IntDomain(0L, 2L), IntDomain(3L, 9L)),
            arrayOf(Element(idx = 0, result = 1, arr = longArrayOf(3L, 5L, 9L), arrIsVars = false, indexOffset = 0)),
        )
        var live = problem.finiteIntDomain(0)
        val domains = object : RelaxationDomains {
            override fun intDomain(varId: Int): IntDomain = if (varId == 0) live else problem.finiteIntDomain(varId)
            override fun boolValue(varId: Int): Boolean? = null
        }
        val sources = LpRetainedSources(
            problem,
            CpToLpRelaxation(problem, LinearObjective(intCoefficients = longArrayOf(0L, 1L)), elementHull = true),
        )
        LpScopedSolver(LpExactState(LpRetainedSources.emptyModel())).use { owner ->
            val edit = assertNotNull(sources.prepare(owner.state, domains))
            assertTrue(owner.replaceRows(edit.retired, edit.columns, edit.rows, false, objective = edit.objective))
            edit.commit()
            val relaxation = sources.relaxation(owner.state, domains)
            val column = relaxation.colReq.indices.single { relaxation.colReq[it].contentEquals(longArrayOf(0L, 1L)) }
            val root = sources.liveBounds(domains)[column]

            live = live.excludeValue(1L)
            val absent = sources.liveBounds(domains)[column]
            live = live.includeInteriorValue(1L)
            val restored = sources.liveBounds(domains)[column]

            assertEquals(0L, live.min)
            assertEquals(2L, live.max)
            assertEquals(BigFraction.ZERO, assertNotNull(root.lower).number.value)
            assertEquals(BigFraction.ONE, assertNotNull(root.upper).number.value)
            assertEquals(BigFraction.ZERO, assertNotNull(absent.lower).number.value)
            assertEquals(BigFraction.ZERO, assertNotNull(absent.upper).number.value)
            assertEquals(root, restored)
        }
    }

    @Test
    fun `catalog compaction should reject stale plans and preserve numerical authority`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(0, 1)), emptyArray())
        val catalog = LpAuxiliarySources()
        val relaxer = CpToLpRelaxation(
            problem,
            LinearObjective(intCoefficients = longArrayOf(1)),
            auxiliarySources = catalog,
        )
        val sources = LpRetainedSources(problem, relaxer)
        val domains = RootDomains(problem)
        LpScopedSolver(LpExactState(LpRetainedSources.emptyModel())).use { owner ->
            val edit = assertNotNull(sources.prepare(owner.state, domains))
            assertTrue(owner.replaceRows(edit.retired, edit.columns, edit.rows, false, objective = edit.objective))
            edit.commit()
            assertEquals(BigFraction.ZERO, assertNotNull(owner.solve()).lowerBound)
            val before = owner.state
            val owners = owner.metrics.createdOwners
            for (id in 0L until 64L) catalog.source(CutAuxiliaryDefinition(listOf(id), emptyList(), 1L, true))
            val stale = assertNotNull(sources.prepareCompaction(before))
            catalog.source(CutAuxiliaryDefinition(listOf(64L), emptyList(), 1L, true))
            assertFalse(stale.isCurrent())
            assertFailsWith<IllegalStateException> { stale.commit() }
            assertSame(before, owner.state)
            val current = assertNotNull(sources.prepareCompaction(before))
            assertTrue(current.remap.unchanged)

            assertTrue(owner.compact(current.remap))
            current.commit()

            assertEquals(0, catalog.size)
            assertEquals(0L, catalog.storageUnits)
            assertSame(before, owner.state)
            assertEquals(owners, owner.metrics.createdOwners)
            assertEquals(BigFraction.ZERO, assertNotNull(owner.solve()).lowerBound)
        }
    }

    @Test
    fun `source compaction should preserve ancestor bindings through same scope replacements and pop`() {
        val problem = Problem(
            0,
            3,
            Array(3) { IntDomain(0, 6) },
            arrayOf(ArrayMinMax(result = 0, xs = intArrayOf(1, 2), max = true)),
        )
        val root = problem.finiteIntDomain(1)
        var live = root
        var honors = true
        val domains = object : RelaxationDomains {
            override val honorsOpenSides: Boolean get() = honors
            override fun intDomain(varId: Int): IntDomain = if (varId == 1) live else problem.finiteIntDomain(varId)
            override fun boolValue(varId: Int): Boolean? = null
        }
        val sources = LpRetainedSources(
            problem,
            CpToLpRelaxation(
                problem,
                LinearObjective(intCoefficients = longArrayOf(1, 0, 0)),
                linMaxTightFace = true,
            ),
        )
        LpScopedSolver(LpExactState(LpRetainedSources.emptyModel())).use { owner ->
            val initial = assertNotNull(sources.prepare(owner.state, domains))
            assertTrue(
                owner.replaceRows(initial.retired, initial.columns, initial.rows, false, objective = initial.objective),
            )
            initial.commit()
            val rootColumns = owner.state.model.n
            val rootRows = owner.state.model.m
            val rootIds = owner.state.rows.entries().map { it.id }
            assertTrue(owner.push())
            for (lower in 1L..3L) {
                live = root.withMinAtLeast(lower)
                honors = lower % 2L == 0L
                val edit = assertNotNull(sources.prepare(owner.state, domains))
                assertTrue(owner.replaceRows(edit.retired, edit.columns, edit.rows, true, objective = edit.objective))
                edit.commit()
                val variable = sources.relaxation(owner.state, domains).intColOf[1]
                assertTrue(owner.assertBound(variable, false, assertNotNull(edit.bounds[variable].lower), lower))
            }
            var compaction: LpSourceCompaction? = null
            for (iteration in 0 until 16) {
                honors = !honors
                val edit = assertNotNull(sources.prepare(owner.state, domains))
                assertTrue(owner.replaceRows(edit.retired, edit.columns, edit.rows, true, objective = edit.objective))
                edit.commit()
                compaction = sources.prepareCompaction(owner.state)
                if (compaction != null) break
            }

            assertTrue(owner.compact(assertNotNull(compaction).remap))
            compaction.commit()

            assertEquals(rootColumns + 2, owner.state.model.n)
            assertEquals(rootRows * 2, owner.state.model.m)
            assertEquals(BigFraction.ofLong(3L), assertNotNull(owner.solve()).lowerBound)
            assertTrue(owner.pop(0))
            sources.retract(0)
            live = root
            honors = true
            val restored = assertNotNull(sources.prepare(owner.state, domains))
            assertTrue(restored.rows.isEmpty() && restored.columns.isEmpty() && restored.retired.isEmpty())
            restored.commit()
            assertEquals(rootIds, owner.state.rows.entries().filter { it.active }.map { it.id })
            assertEquals(BigFraction.ZERO, assertNotNull(owner.solve()).lowerBound)
        }
    }

    @Test
    fun `mixed emitters should share integer coordinates and preserve IEEE row authority`() {
        val problem = Problem(
            0,
            1,
            arrayOf(IntDomain(0, 5)),
            arrayOf<Factor>(
                Linear(
                    intArrayOf(0),
                    doubleArrayOf(1.0),
                    intArrayOf(0),
                    doubleArrayOf(1.0),
                    LinearOp.GE,
                    2.5,
                    strict = true,
                ),
            ),
            numRealVars = 1,
            realLower = doubleArrayOf(0.0),
            realUpper = doubleArrayOf(1.0),
        )
        val domains = RootDomains(problem)
        val sources = LpRetainedSources(
            problem,
            CpToLpRelaxation(problem, LinearObjective(intCoefficients = longArrayOf(1L))),
        )
        LpScopedSolver(LpExactState(LpRetainedSources.emptyModel())).use { owner ->
            val edit = assertNotNull(sources.prepare(owner.state, domains))

            assertTrue(
                owner.replaceRows(
                    edit.retired,
                    edit.columns,
                    edit.rows,
                    false,
                    permanentRows = edit.permanentRows,
                    objective = edit.objective,
                ),
            )
            edit.commit()

            val relaxation = sources.relaxation(owner.state, domains)
            val column = relaxation.intColOf[0]
            assertEquals(2, owner.state.model.n)
            assertEquals(BigFraction.ZERO, owner.state.model.column(column).origin.value)
            assertEquals(BigFraction.ONE, owner.state.model.objective.cost(column).value)
            assertEquals(null, owner.state.model.objective.cost(column).ieeeBits)
            assertTrue(owner.state.model.entries(column).all { it.number.ieeeBits != null })
            assertTrue(owner.state.model.row(0).strict)
        }
    }

    @Test
    fun `bound only source edits should reuse emitted row metadata and the numerical owner`() {
        val problem = Problem(
            0,
            1,
            arrayOf(IntDomain(3, 9)),
            arrayOf<Factor>(Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 4)),
        )
        val root = problem.finiteIntDomain(0)
        var live = root
        val domains = object : RelaxationDomains {
            override fun intDomain(varId: Int): IntDomain = live
            override fun boolValue(varId: Int): Boolean? = null
        }
        val sources = LpRetainedSources(
            problem,
            CpToLpRelaxation(problem, LinearObjective(intCoefficients = longArrayOf(1L))),
        )
        LpScopedSolver(LpExactState(LpRetainedSources.emptyModel())).use { owner ->
            val first = assertNotNull(sources.prepare(owner.state, domains))
            assertTrue(owner.replaceRows(first.retired, first.columns, first.rows, false, objective = first.objective))
            first.commit()
            val original = sources.relaxation(owner.state, domains)
            assertEquals(BigFraction.ofLong(4L), assertNotNull(owner.solve()).lowerBound)
            assertTrue(owner.push())
            live = root.withMinAtLeast(5L)

            val edit = assertNotNull(sources.prepare(owner.state, domains))

            assertTrue(edit.rows.isEmpty() && edit.columns.isEmpty() && edit.retired.isEmpty())
            edit.commit()
            val bounds = sources.liveBounds(domains)
            assertEquals(BigFraction.ofLong(2L), bounds[0].lower?.number?.value)
            assertTrue(owner.assertBound(0, false, assertNotNull(bounds[0].lower), 7L))
            val rebound = sources.relaxation(owner.state, domains)
            assertSame(original.rowFactorIds, rebound.rowFactorIds)
            assertEquals(BigFraction.ofLong(5L), assertNotNull(owner.solve()).lowerBound)
            assertEquals(1L, owner.metrics.createdOwners)
        }
    }

    @Test
    fun `split real upper definitions should be shared and survive the activating source scope`() {
        val problem = Problem(
            1,
            0,
            emptyArray(),
            arrayOf<Factor>(
                ReifiedRealLinear(
                    0,
                    intArrayOf(),
                    doubleArrayOf(),
                    intArrayOf(0),
                    doubleArrayOf(1.0),
                    LinearOp.GE,
                    1.0,
                ),
                ReifiedRealLinear(
                    0,
                    intArrayOf(),
                    doubleArrayOf(),
                    intArrayOf(0),
                    doubleArrayOf(1.0),
                    LinearOp.LE,
                    4.0,
                ),
            ),
            numRealVars = 1,
            realLower = doubleArrayOf(Double.NEGATIVE_INFINITY),
            realUpper = doubleArrayOf(5.0),
        )
        var pin: Boolean? = null
        val domains = object : RelaxationDomains {
            override fun intDomain(varId: Int): IntDomain = error("no integer sources")
            override fun boolValue(varId: Int): Boolean? = pin
        }
        val sources = LpRetainedSources(problem, CpToLpRelaxation(problem, null))
        LpScopedSolver(LpExactState(LpRetainedSources.emptyModel())).use { owner ->
            val root = assertNotNull(sources.prepare(owner.state, domains))
            assertTrue(owner.replaceRows(root.retired, root.columns, root.rows, false, objective = root.objective))
            root.commit()
            assertTrue(owner.push())
            pin = true
            val child = assertNotNull(sources.prepare(owner.state, domains))
            assertTrue(
                owner.replaceRows(
                    child.retired,
                    child.columns,
                    child.rows,
                    true,
                    permanentRows = child.permanentRows,
                    objective = child.objective,
                ),
            )
            child.commit()

            assertEquals(2, owner.state.model.n)
            assertEquals(1, child.permanentRows.size)
            assertEquals(3, owner.state.rows.activeCount)
            assertEquals(1, sources.relaxation(owner.state, domains).realUpperRows.size)
            assertEquals(BigFraction.ZERO, assertNotNull(owner.solve()).lowerBound)
            assertTrue(owner.pop(0))
            sources.retract(0)
            pin = null
            assertEquals(1, owner.state.rows.activeCount)
            assertEquals(BigFraction.ZERO, assertNotNull(owner.solve()).lowerBound)
        }
    }

    @Test
    fun `cancelled and stale source publications should preserve the committed layout`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(0, 3)), arrayOf<Factor>())
        val sources = LpRetainedSources(
            problem,
            CpToLpRelaxation(problem, LinearObjective(intCoefficients = longArrayOf(1L))),
        )
        val state = LpExactState(LpRetainedSources.emptyModel())
        val domains = RootDomains(problem)
        val first = assertNotNull(sources.prepare(state, domains))
        assertFailsWith<LpAssemblyCancelled> { sources.prepare(state, domains, Cancellation { true }) }
        val second = assertNotNull(sources.prepare(state, domains))
        second.commit()

        assertFailsWith<IllegalStateException> { first.commit() }

        assertEquals(1, second.columns.size)
    }
}
