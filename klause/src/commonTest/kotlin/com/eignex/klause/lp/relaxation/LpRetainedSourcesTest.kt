package com.eignex.klause.lp.relaxation

import com.eignex.klause.factor.arithmetic.ArrayMinMax
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.arithmetic.ReifiedRealLinear
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.engine.ExactLpNumber
import com.eignex.klause.lp.engine.ExactLpSide
import com.eignex.klause.lp.engine.LpExactState
import com.eignex.klause.lp.engine.LpScopedSolver
import com.eignex.klause.lp.engine.RevisedSimplex
import com.eignex.klause.lp.engine.integerCertify
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LpRetainedSourcesTest {
    @Test
    fun `source bound mode changes reclaim anonymous columns without changing fresh bounds`() {
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
        val relaxer = CpToLpRelaxation(problem, LinearObjective(intCoefficients = longArrayOf(1, 0, 0)),
            linMaxTightFace = true)
        val sources = LpRetainedSources(problem, relaxer)
        LpScopedSolver(LpExactState(LpRetainedSources.emptyModel())).use { owner ->
            for (lower in 0L..3L) {
                live = root.withMinAtLeast(lower)
                honors = lower % 2L == 0L
                val edit = sources.prepare(owner.state, domains)
                assertTrue(owner.replaceRows(edit.retired, edit.columns, edit.rows, false, objective = edit.objective))
                edit.commit()
                val variable = sources.relaxation(owner.state, domains).intColOf[1]
                assertTrue(owner.assertBound(variable, false, assertNotNull(edit.bounds[variable].lower), lower))
                if (lower > 0) {
                    val compaction = assertNotNull(sources.prepareCompaction(owner.state))
                    assertTrue(owner.compact(compaction.remap))
                    compaction.commit()
                }
                val retained = sources.relaxation(owner.state, domains)
                val fresh = relaxer.build(domains)
                val expected = RevisedSimplex(fresh.model).use { assertNotNull(it.solve()).objective }

                assertEquals(BigFraction.ofLong(lower), assertNotNull(owner.solve()).lowerBound)
                assertEquals(lower.toDouble(), expected)
                assertEquals(fresh.model.n, retained.model.n)
                assertEquals(fresh.model.m, retained.model.m)
                assertEquals(fresh.rowFactorIds.toList(), retained.rowFactorIds.toList())
                assertEquals(fresh.colVarId.toList(), retained.colVarId.toList())
            }
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
            assertTrue(owner.replaceRows(initial.retired, initial.columns, initial.rows, false, objective = initial.objective))
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
            val compaction = assertNotNull(sources.prepareCompaction(owner.state))

            assertTrue(owner.compact(compaction.remap))
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
            val rootCompaction = assertNotNull(sources.prepareCompaction(owner.state))
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
    fun `retained source assembly shares variable columns and counts objective origins once`() {
        val problem = Problem(
            0, 2, arrayOf(IntDomain(3, 9), IntDomain(-1, 8)),
            arrayOf<Factor>(
                Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 8),
                Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 8),
            ),
        )
        val relaxer = CpToLpRelaxation(problem, LinearObjective(intCoefficients = longArrayOf(2L, 3L)))
        val domains = RootDomains(problem)
        val sources = LpRetainedSources(problem, relaxer)
        LpScopedSolver(LpExactState(LpRetainedSources.emptyModel())).use { owner ->
            val edit = sources.prepare(owner.state, domains)
            assertTrue(owner.replaceRows(
                edit.retired, edit.columns, edit.rows, false,
                permanentRows = edit.permanentRows, objective = edit.objective,
            ))
            edit.commit()
            val retained = sources.relaxation(owner.state, domains)
            val fresh = relaxer.build(domains)
            val solved = assertNotNull(RevisedSimplex(fresh.model).solve())

            val certificate = assertNotNull(owner.solve())

            assertEquals(2, owner.state.model.n)
            assertEquals(BigFraction.ofLong(3L), owner.state.model.objective.constant.value)
            assertEquals(solved.objective, assertNotNull(certificate.lowerBound).toDouble())
            assertEquals(fresh.rowFactorIds.toList(), retained.rowFactorIds.toList())
            assertEquals(0, retained.intColOf[0])
            assertEquals(1, retained.intColOf[1])
        }
    }

    @Test
    fun `retained reified rows match fresh bounds through repeated edits and nested rollback`() {
        val problem = Problem(
            1, 1, arrayOf(IntDomain(0, 10)),
            arrayOf<Factor>(ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.GE, 8)),
        )
        val root = problem.finiteIntDomain(0)
        var live = root
        val domains = object : RelaxationDomains {
            override fun intDomain(varId: Int): IntDomain = live
            override fun boolValue(varId: Int): Boolean? = null
        }
        val relaxer = CpToLpRelaxation(problem, LinearObjective(intCoefficients = longArrayOf(1L)))
        val sources = LpRetainedSources(problem, relaxer)
        LpScopedSolver(LpExactState(LpRetainedSources.emptyModel())).use { owner ->
            for ((level, lower) in listOf(0 to 0L, 1 to 2L, 1 to 3L, 2 to 4L)) {
                live = root.withMinAtLeast(lower)
                while (owner.state.depth < level) assertTrue(owner.push())
                val edit = sources.prepare(owner.state, domains)
                assertTrue(owner.replaceRows(
                    edit.retired, edit.columns, edit.rows, level > 0,
                    permanentRows = edit.permanentRows, objective = edit.objective,
                ))
                edit.commit()
                val current = sources.relaxation(owner.state, domains)
                val column = current.intColOf[0]
                assertTrue(owner.assertBound(column, false, ExactLpSide(ExactLpNumber.of(lower)), lower))
                val fresh = relaxer.build(domains)
                val result = assertNotNull(RevisedSimplex(fresh.model).solve())
                val expected = assertNotNull(integerCertify(fresh.model, result.duals)).objectiveBoundCeil(0L)

                assertEquals(expected, assertNotNull(owner.solve()).integerObjectiveLowerBound)
            }
            assertTrue(owner.pop(1))
            sources.retract(1)
            live = root.withMinAtLeast(3L)
            assertEquals(BigFraction.ofLong(3L), assertNotNull(owner.solve()).lowerBound)
            assertTrue(owner.pop(0))
            sources.retract(0)
            live = root
            assertEquals(BigFraction.ZERO, assertNotNull(owner.solve()).lowerBound)
            val restored = sources.prepare(owner.state, domains)
            assertTrue(restored.columns.isEmpty() && restored.rows.isEmpty() && restored.retired.isEmpty())
            restored.commit()
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
