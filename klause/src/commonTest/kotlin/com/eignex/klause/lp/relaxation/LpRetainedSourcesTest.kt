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
    fun `live integer bounds preserve exact origins and earlier snapshots through nested pops`() {
        for (origin in listOf(5L, Long.MIN_VALUE + 1L, Long.MAX_VALUE - 5L)) {
            val problem = Problem(0, 1, arrayOf(IntDomain(origin, origin + 4L)), emptyArray())
            val session = PropagationSession(problem)
            val domains = SessionDomains(session)
            val sources = LpRetainedSources(problem,
                CpToLpRelaxation(problem, LinearObjective(intCoefficients = longArrayOf(1L))))
            LpScopedSolver(LpExactState(LpRetainedSources.emptyModel())).use { owner ->
                val edit = sources.prepare(owner.state, domains)
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
    fun `live bounds distinguish open source sides from finite search bounds`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(5L, 9L)), emptyArray(), openIntHi = booleanArrayOf(true))
        val rootDomains = RootDomains(problem)
        val searchDomains = SessionDomains(PropagationSession(problem))
        val sources = LpRetainedSources(problem,
            CpToLpRelaxation(problem, LinearObjective(intCoefficients = longArrayOf(1L))))
        LpScopedSolver(LpExactState(LpRetainedSources.emptyModel())).use { owner ->
            val edit = sources.prepare(owner.state, rootDomains)
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
    fun `live auxiliary bounds follow interior membership changes without altering earlier snapshots`() {
        val problem = Problem(0, 2, arrayOf(IntDomain(0L, 2L), IntDomain(3L, 9L)),
            arrayOf(Element(idx = 0, result = 1, arr = longArrayOf(3L, 5L, 9L), arrIsVars = false, indexOffset = 0)))
        var live = problem.finiteIntDomain(0)
        val domains = object : RelaxationDomains {
            override fun intDomain(varId: Int): IntDomain = if (varId == 0) live else problem.finiteIntDomain(varId)
            override fun boolValue(varId: Int): Boolean? = null
        }
        val sources = LpRetainedSources(problem,
            CpToLpRelaxation(problem, LinearObjective(intCoefficients = longArrayOf(0L, 1L)), elementHull = true))
        LpScopedSolver(LpExactState(LpRetainedSources.emptyModel())).use { owner ->
            val edit = sources.prepare(owner.state, domains)
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
    fun `catalog compaction rejects stale plans and preserves numerical authority`() {
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
            val edit = sources.prepare(owner.state, domains)
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
    fun `source compaction preserves ancestor bindings through same scope replacements and pop`() {
        val problem = Problem(0, 3, Array(3) { IntDomain(0, 6) },
            arrayOf(ArrayMinMax(result = 0, xs = intArrayOf(1, 2), max = true)))
        val root = problem.finiteIntDomain(1)
        var live = root
        var honors = true
        val domains = object : RelaxationDomains {
            override val honorsOpenSides: Boolean get() = honors
            override fun intDomain(varId: Int): IntDomain = if (varId == 1) live else problem.finiteIntDomain(varId)
            override fun boolValue(varId: Int): Boolean? = null
        }
        val sources = LpRetainedSources(problem, CpToLpRelaxation(problem,
            LinearObjective(intCoefficients = longArrayOf(1, 0, 0)), linMaxTightFace = true))
        LpScopedSolver(LpExactState(LpRetainedSources.emptyModel())).use { owner ->
            val initial = sources.prepare(owner.state, domains)
            assertTrue(owner.replaceRows(
                initial.retired,
                initial.columns,
                initial.rows,
                false,
                objective = initial.objective,
            ))
            initial.commit()
            val rootColumns = owner.state.model.n
            val rootRows = owner.state.model.m
            val rootIds = owner.state.rows.entries().map { it.id }
            assertTrue(owner.push())
            for (lower in 1L..3L) {
                live = root.withMinAtLeast(lower)
                honors = lower % 2L == 0L
                val edit = sources.prepare(owner.state, domains)
                assertTrue(owner.replaceRows(edit.retired, edit.columns, edit.rows, true, objective = edit.objective))
                edit.commit()
                val variable = sources.relaxation(owner.state, domains).intColOf[1]
                assertTrue(owner.assertBound(variable, false, assertNotNull(edit.bounds[variable].lower), lower))
            }
            var compaction: LpSourceCompaction? = null
            for (iteration in 0 until 16) {
                honors = !honors
                val edit = sources.prepare(owner.state, domains)
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
            val restored = sources.prepare(owner.state, domains)
            assertTrue(restored.rows.isEmpty() && restored.columns.isEmpty() && restored.retired.isEmpty())
            restored.commit()
            assertEquals(rootIds, owner.state.rows.entries().filter { it.active }.map { it.id })
            assertEquals(BigFraction.ZERO, assertNotNull(owner.solve()).lowerBound)
            var rootCompaction = sources.prepareCompaction(owner.state)
            for (iteration in 0 until 16) {
                if (rootCompaction != null) break
                assertTrue(owner.push())
                honors = false
                val edit = sources.prepare(owner.state, domains)
                assertTrue(owner.replaceRows(edit.retired, edit.columns, edit.rows, true, objective = edit.objective))
                edit.commit()
                assertTrue(owner.pop(0))
                sources.retract(0)
                honors = true
                val rootEdit = sources.prepare(owner.state, domains)
                assertTrue(rootEdit.rows.isEmpty() && rootEdit.columns.isEmpty() && rootEdit.retired.isEmpty())
                rootEdit.commit()
                rootCompaction = sources.prepareCompaction(owner.state)
            }
            assertNotNull(rootCompaction)
            assertTrue(owner.compact(rootCompaction.remap))
            rootCompaction.commit()
            assertEquals(rootColumns, owner.state.model.n)
            assertEquals(rootRows, owner.state.model.m)
            assertEquals(rootIds, owner.state.rows.entries().map { it.id })
            assertEquals(BigFraction.ZERO, assertNotNull(owner.solve()).lowerBound)
        }
    }

    @Test
    fun `mixed emitters share integer coordinates and preserve IEEE row authority`() {
        val problem = Problem(
            0, 1, arrayOf(IntDomain(0, 5)),
            arrayOf<Factor>(Linear(
                intArrayOf(0), doubleArrayOf(1.0), intArrayOf(0), doubleArrayOf(1.0), LinearOp.GE, 2.5,
                strict = true,
            )), numRealVars = 1, realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(1.0),
        )
        val domains = RootDomains(problem)
        val sources = LpRetainedSources(
            problem, CpToLpRelaxation(problem, LinearObjective(intCoefficients = longArrayOf(1L))),
        )
        LpScopedSolver(LpExactState(LpRetainedSources.emptyModel())).use { owner ->
            val edit = sources.prepare(owner.state, domains)

            assertTrue(owner.replaceRows(
                edit.retired, edit.columns, edit.rows, false,
                permanentRows = edit.permanentRows, objective = edit.objective,
            ))
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
    fun `bound only source edits reuse emitted rows metadata and the numerical owner`() {
        val problem = Problem(
            0, 1, arrayOf(IntDomain(3, 9)),
            arrayOf<Factor>(Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 4)),
        )
        val root = problem.finiteIntDomain(0)
        var live = root
        val domains = object : RelaxationDomains {
            override fun intDomain(varId: Int): IntDomain = live
            override fun boolValue(varId: Int): Boolean? = null
        }
        val sources = LpRetainedSources(
            problem, CpToLpRelaxation(problem, LinearObjective(intCoefficients = longArrayOf(1L))),
        )
        LpScopedSolver(LpExactState(LpRetainedSources.emptyModel())).use { owner ->
            val first = sources.prepare(owner.state, domains)
            assertTrue(owner.replaceRows(first.retired, first.columns, first.rows, false, objective = first.objective))
            first.commit()
            val original = sources.relaxation(owner.state, domains)
            assertEquals(BigFraction.ofLong(4L), assertNotNull(owner.solve()).lowerBound)
            assertTrue(owner.push())
            live = root.withMinAtLeast(5L)

            val edit = sources.prepare(owner.state, domains)

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
    fun `split real upper definitions are shared and survive the activating source scope`() {
        val problem = Problem(
            1, 0, emptyArray(), arrayOf<Factor>(
                ReifiedRealLinear(
                    0, intArrayOf(), doubleArrayOf(), intArrayOf(0), doubleArrayOf(1.0), LinearOp.GE, 1.0,
                ),
                ReifiedRealLinear(
                    0, intArrayOf(), doubleArrayOf(), intArrayOf(0), doubleArrayOf(1.0), LinearOp.LE, 4.0,
                ),
            ), numRealVars = 1, realLower = doubleArrayOf(Double.NEGATIVE_INFINITY), realUpper = doubleArrayOf(5.0),
        )
        var pin: Boolean? = null
        val domains = object : RelaxationDomains {
            override fun intDomain(varId: Int): IntDomain = error("no integer sources")
            override fun boolValue(varId: Int): Boolean? = pin
        }
        val sources = LpRetainedSources(problem, CpToLpRelaxation(problem, null))
        LpScopedSolver(LpExactState(LpRetainedSources.emptyModel())).use { owner ->
            val root = sources.prepare(owner.state, domains)
            assertTrue(owner.replaceRows(root.retired, root.columns, root.rows, false, objective = root.objective))
            root.commit()
            assertTrue(owner.push())
            pin = true
            val child = sources.prepare(owner.state, domains)
            assertTrue(owner.replaceRows(
                child.retired, child.columns, child.rows, true,
                permanentRows = child.permanentRows, objective = child.objective,
            ))
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
    fun `cancelled and stale source publications preserve the committed layout`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(0, 3)), arrayOf<Factor>())
        val sources = LpRetainedSources(
            problem, CpToLpRelaxation(problem, LinearObjective(intCoefficients = longArrayOf(1L))),
        )
        val state = LpExactState(LpRetainedSources.emptyModel())
        val domains = RootDomains(problem)
        val first = sources.prepare(state, domains)
        assertFailsWith<LpAssemblyCancelled> { sources.prepare(state, domains, Cancellation { true }) }
        val second = sources.prepare(state, domains)
        second.commit()

        assertFailsWith<IllegalStateException> { first.commit() }

        assertEquals(1, second.columns.size)
    }
}
