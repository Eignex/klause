package com.eignex.klause.lp.relaxation

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.SourceFormat
import com.eignex.klause.ir.SourceSemantics
import com.eignex.klause.ir.VarRemap
import com.eignex.klause.lp.engine.ExactLpNumber
import com.eignex.klause.lp.engine.ExactLpSide
import com.eignex.klause.lp.engine.LpExactState
import com.eignex.klause.lp.engine.LpScopedSolver
import com.eignex.klause.lp.engine.authoritativeModel
import com.eignex.klause.lp.engine.solveAndCertify
import com.eignex.klause.propagation.bake
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.FactorProjectionProviders
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LpPrimalTemplatesTest {
    private val source = Problem(1, 3, Array(3) { IntDomain(0, 10) }, arrayOf<Factor>(
        Linear(intArrayOf(1, 1, 1), intArrayOf(0, 1, 2), LinearOp.GE, 6),
        Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 2),
        Linear(intArrayOf(1, 1), intArrayOf(1, 2), LinearOp.GE, 2),
    ))
    private val selected = FactorProjectionProviders.Default.prepare(source)
    private val objective = LinearObjective(intCoefficients = longArrayOf(1, 1, 1))

    @Test
    fun `completed primal definitions should amortize construction across private numerical owners`() {
        var assembly = 0L
        val definitions = ArrayList<String>()
        repeat(8) {
            buildPrimalSource(source, objective, RootDomains(source), Cancellation.Never, { assembly += it },
                selected, onDefinition = definitions::add)
            if (it == 0) assertEquals(LpPrimalTemplates.DEMAND_UNITS, selected.lpPreparationReuseUnits)
        }
        assembly = 0L

        val relaxation = buildPrimalSource(source, objective, RootDomains(source), Cancellation.Never,
            { assembly += it }, selected, onDefinition = definitions::add)
        val exact = assertNotNull(relaxation.model.authoritativeModel())
        LpScopedSolver(LpExactState(exact)).use { first ->
            LpScopedSolver(LpExactState(exact)).use { second ->
                assertEquals(BigFraction.ofLong(6), assertNotNull(first.solve()).lowerBound)
                assertTrue(first.push())
                assertTrue(first.assertBound(relaxation.intColOf[0], false,
                    ExactLpSide(ExactLpNumber.of(8L)), 1L))
                assertEquals(BigFraction.ofLong(10), assertNotNull(first.solve()).lowerBound)
                assertEquals(BigFraction.ofLong(6), assertNotNull(second.solve()).lowerBound)
                assertNotSame(first.state, second.state)
            }
        }

        assertEquals(0L, assembly)
        assertEquals(1, definitions.count { it == "PUBLISHED" })
        assertTrue(definitions.count { it == "REUSED" } > 0)
        assertNull(relaxation.sourceMap)
        assertNull(relaxation.preparationTemplates)
        assertTrue(selected.lpPreparationReuseUnits in 1L..LpEmissionTemplates.MAX_UNITS)
    }

    @Test
    fun `repeated changed builds should earn a replacement without changing earlier models`() {
        repeat(8) { buildPrimalSource(source, objective, RootDomains(source), Cancellation.Never, {}, selected) }
        val original = assertNotNull(selected.borrowLpPrimal(objective, RootDomains(source), Cancellation.Never))
        val changed = Problem(1, 3, arrayOf(IntDomain(8, 10), IntDomain(0, 10), IntDomain(0, 10)), source.factors)
        val selection = selected.forEngineView(changed)
        repeat(8) { buildPrimalSource(changed, objective, RootDomains(changed), Cancellation.Never, {}, selection) }

        val replacement = assertNotNull(selection.borrowLpPrimal(objective, RootDomains(changed), Cancellation.Never))

        assertEquals(BigFraction.ofLong(10), solveAndCertify(replacement.model).lowerBound)
        assertEquals(BigFraction.ofLong(6), solveAndCertify(original.model).lowerBound)
        assertNull(selected.borrowLpPrimal(objective, RootDomains(source), Cancellation.Never))
    }

    @Test
    fun `changed objective costs should preserve the cached model objective`() {
        repeat(8) { buildPrimalSource(source, objective, RootDomains(source), Cancellation.Never, {}, selected) }
        val original = assertNotNull(selected.borrowLpPrimal(objective, RootDomains(source), Cancellation.Never))
        val next = LinearObjective(intCoefficients = longArrayOf(2, 2, 2), constant = 7)

        val rebound = assertNotNull(selected.borrowLpPrimal(next, RootDomains(source), Cancellation.Never))

        assertEquals(BigFraction.ofLong(12), solveAndCertify(rebound.model).lowerBound)
        assertEquals(BigFraction.ofLong(6), solveAndCertify(original.model).lowerBound)
        assertEquals(7L, rebound.objectiveConstant)
        assertSame(original.model.csc, rebound.model.csc)
        assertNotSame(original.model.cost, rebound.model.cost)
    }

    @Test
    fun `a new objective only coordinate should require current construction`() {
        val problem = Problem(0, 4, Array(4) { IntDomain(0, 10) }, source.factors)
        val selection = FactorProjectionProviders.Default.prepare(problem)
        repeat(8) { buildPrimalSource(problem, objective, RootDomains(problem), Cancellation.Never, {}, selection) }
        val next = LinearObjective(intCoefficients = longArrayOf(1, 1, 1, -1))

        val borrowed = selection.borrowLpPrimal(next, RootDomains(problem), Cancellation.Never)
        val fresh = buildPrimalSource(problem, next, RootDomains(problem), Cancellation.Never, {}, selection)

        assertNull(borrowed)
        assertTrue(fresh.intColOf[3] >= 0)
        assertEquals(BigFraction.ofLong(-4), solveAndCertify(fresh.model).lowerBound)
    }

    @Test
    fun `source changes should withhold a prior whole model binding`() {
        repeat(8) { buildPrimalSource(source, objective, RootDomains(source), Cancellation.Never, {}, selected) }
        val changed = listOf(
            Problem(1, 3, arrayOf(IntDomain(1, 10), IntDomain(0, 10), IntDomain(0, 10)), source.factors),
            Problem(1, 3, Array(3) { IntDomain(0, 10) }, source.factors,
                openIntHi = booleanArrayOf(true, false, false)),
            source.withFactors(source.factors.copyOf().also {
                it[0] = Linear(intArrayOf(2, 1, 1), intArrayOf(0, 1, 2), LinearOp.GE, 6)
            }),
            source.withSourceSemantics(SourceSemantics(SourceFormat.SCHEMA).resolve()),
            Problem(1, 3, Array(3) { IntDomain(0, 10) }, source.factors,
                settings = source.settings.copy(lpMaxTableauCells = 1024L)),
            Problem(1, 4, Array(4) { IntDomain(0, 10) }, source.factors),
        )

        for (problem in changed) {
            assertNull(selected.forEngineView(problem).borrowLpPrimal(objective,
                RootDomains(problem), Cancellation.Never))
        }
    }

    @Test
    fun `live restrictions should invalidate a cached primal model`() {
        repeat(8) { buildPrimalSource(source, objective, RootDomains(source), Cancellation.Never, {}, selected) }
        val root = RootDomains(source)
        val variants = listOf(
            object : RelaxationDomains by root {
                override fun intDomain(varId: Int): IntDomain =
                    if (varId == 0) IntDomain(1, 10) else root.intDomain(varId)
            },
            object : RelaxationDomains by root {
                override fun boolValue(varId: Int): Boolean = true
            },
            object : RelaxationDomains by root {
                override val honorsOpenSides: Boolean = false
            },
        )

        for (domains in variants) assertNull(selected.borrowLpPrimal(objective, domains, Cancellation.Never))
    }

    @Test
    fun `raw finite and remapped views should require their own completed definitions`() {
        repeat(8) { buildPrimalSource(source, objective, RootDomains(source), Cancellation.Never, {}, selected) }
        val finite = source.bake(selected)
        val remapped = selected.projected(source, IntArray(source.numFactors) { it },
            VarRemap(intArrayOf(0), IntArray(3) { it }))

        assertNull(assertNotNull(finite.factorProjections).borrowLpPrimal(objective,
            RootDomains(finite), Cancellation.Never))
        assertNull(remapped.borrowLpPrimal(objective, RootDomains(source), Cancellation.Never))
    }

    @Test
    fun `unary equality holes should remain part of a complete primal binding`() {
        val problem = Problem(1, 1, arrayOf(IntDomain(0, 2).excludeValue(1)), arrayOf<Factor>(
            ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.EQ, 1),
        ))
        val selection = FactorProjectionProviders.Default.prepare(problem)
        val cost = LinearObjective(boolWeights = longArrayOf(-1))
        repeat(16) { buildPrimalSource(problem, cost, RootDomains(problem), Cancellation.Never, {}, selection) }
        assertNotNull(selection.borrowLpPrimal(cost, RootDomains(problem), Cancellation.Never))
        val changed = Problem(1, 1, arrayOf(IntDomain(0, 2)), problem.factors)

        val borrowed = selection.forEngineView(changed).borrowLpPrimal(cost, RootDomains(changed), Cancellation.Never)

        assertNull(borrowed)
    }

    @Test
    fun `cancellation after a definition debit should withhold the borrowed model`() {
        repeat(8) { buildPrimalSource(source, objective, RootDomains(source), Cancellation.Never, {}, selected) }
        var cancelled = false
        val meter = WorkMeter { cancelled = true }
        val token = object : Cancellation {
            override fun isCancelled(): Boolean = cancelled
            override fun workMeter(): WorkMeter = meter
        }

        assertFailsWith<LpAssemblyCancelled> {
            buildPrimalSource(source, objective, RootDomains(source), token, {}, selected)
        }
        assertNotNull(selected.borrowLpPrimal(objective, RootDomains(source), Cancellation.Never))
    }

    @Test
    fun `live equality holes should invalidate a definition with unchanged endpoints`() {
        val problem = Problem(1, 1, arrayOf(IntDomain(0, 2)), arrayOf<Factor>(
            ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.EQ, 1),
        ))
        val selection = FactorProjectionProviders.Default.prepare(problem)
        val cost = LinearObjective(boolWeights = longArrayOf(-1))
        repeat(16) { buildPrimalSource(problem, cost, RootDomains(problem), Cancellation.Never, {}, selection) }
        assertNotNull(selection.borrowLpPrimal(cost, RootDomains(problem), Cancellation.Never))
        val domains = object : RelaxationDomains by RootDomains(problem) {
            override fun intDomain(varId: Int): IntDomain = IntDomain(0, 2).excludeValue(1)
        }

        assertNull(selection.borrowLpPrimal(cost, domains, Cancellation.Never))
    }

    @Test
    fun `a refused whole definition should keep construction on the ordinary route`() {
        val problem = Problem(1, 44_000, Array(44_000) { IntDomain(0, 10) }, source.factors)
        val selection = FactorProjectionProviders.Default.prepare(problem)
        var published = 0
        var assembly = 0L
        repeat(4) {
            buildPrimalSource(problem, objective, RootDomains(problem), Cancellation.Never,
                { assembly += it }, selection, onDefinition = { if (it == "PUBLISHED") published++ })
        }

        assertNull(selection.borrowLpPrimal(objective, RootDomains(problem), Cancellation.Never))
        assertEquals(0, published)
        assertTrue(assembly > 0L)
        assertTrue(selection.lpPreparationReuseUnits <= LpEmissionTemplates.MAX_UNITS)
    }

    @Test
    fun `an unsupported source row should keep complete model reuse unavailable`() {
        val problem = Problem(0, 3, Array(3) { IntDomain(0, 10) }, arrayOf<Factor>(
            AllDifferent(intArrayOf(0, 1, 2)),
        ))
        val selection = FactorProjectionProviders.Default.prepare(problem)
        var reused = 0
        repeat(8) {
            buildPrimalSource(problem, objective, RootDomains(problem), Cancellation.Never, {}, selection,
                onDefinition = { if (it == "REUSED") reused++ })
        }

        assertNull(selection.borrowLpPrimal(objective, RootDomains(problem), Cancellation.Never))
        assertEquals(0, reused)
        assertTrue(selection.lpPreparationReuseUnits <= LpEmissionTemplates.MAX_UNITS)
    }

    @Test
    fun `cancelled replacement should preserve the prior completed definition`() {
        repeat(8) { buildPrimalSource(source, objective, RootDomains(source), Cancellation.Never, {}, selected) }
        val original = assertNotNull(selected.borrowLpPrimal(objective, RootDomains(source), Cancellation.Never))
        val changed = Problem(1, 3, arrayOf(IntDomain(1, 10), IntDomain(0, 10), IntDomain(0, 10)), source.factors)
        val selection = selected.forEngineView(changed)
        val fresh = buildPrimalSource(changed, objective, RootDomains(changed), Cancellation.Never, {}, selection,
            reuseCompleteTemplates = false)
        var cancelled = false
        val meter = WorkMeter { cancelled = true }
        val token = object : Cancellation {
            override fun isCancelled(): Boolean = cancelled
            override fun workMeter(): WorkMeter = meter
        }

        val published = selection.rememberLpPrimal(fresh, objective, RootDomains(changed), 100L, token)

        assertEquals(false, published)
        assertSame(original, selected.borrowLpPrimal(objective, RootDomains(source), Cancellation.Never))
        assertNull(selection.borrowLpPrimal(objective, RootDomains(changed), Cancellation.Never))
    }

    @Test
    fun `an explicit selective metadata request should retain its own route`() {
        repeat(8) { buildPrimalSource(source, objective, RootDomains(source), Cancellation.Never, {}, selected) }
        val metadata = assertNotNull(selected.prepareLpTemplates(objective, Cancellation.Never, reuseMetadata = true))
        repeat(8) { buildPrimalSource(source, objective, RootDomains(source), Cancellation.Never, {}, selected) }

        assertTrue(metadata.objectiveIndependent)
        assertNull(selected.borrowLpPrimal(objective, RootDomains(source), Cancellation.Never))
        assertEquals(metadata.storageUnits, selected.lpPreparationReuseUnits)
    }
}
