package com.eignex.klause.backtrack.lp

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.bounding.LpEngine
import com.eignex.klause.lp.bounding.LpParams
import com.eignex.klause.lp.bounding.LpPlan
import com.eignex.klause.lp.engine.Csc
import com.eignex.klause.lp.engine.LpModel
import com.eignex.klause.lp.engine.LpRowPremises
import com.eignex.klause.lp.relaxation.CpToLpRelaxation
import com.eignex.klause.lp.relaxation.LpAssemblyCancelled
import com.eignex.klause.lp.relaxation.RelaxationDomains
import com.eignex.klause.lp.relaxation.RootDomains
import com.eignex.klause.lp.relaxation.buildPrimalSource
import com.eignex.klause.lp.relaxation.withModel
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.FactorProjectionProviders
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.SolveStatsSink
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LpPrimalContinuationTest {
    private val problem = Problem(1, 3, Array(3) { IntDomain(0, 10) }, (1..12).map {
        Linear(intArrayOf(1, it, 1), intArrayOf(0, 1, 2), LinearOp.GE, 6)
    })
    private val objective = LinearObjective(intCoefficients = longArrayOf(1, 1, 1))
    private val selected = FactorProjectionProviders.Default.prepare(problem)
    private val domains = RootDomains(problem)

    @Test
    fun `equivalent domain values should validate a continued definition`() {
        var domain = IntDomain(0, 10).excludeValue(4)
        val live = object : RelaxationDomains by domains {
            override fun intDomain(varId: Int) = if (varId == 0) domain else domains.intDomain(varId)
        }
        val root = CpToLpRelaxation(problem, objective, projections = selected, reuseSourceTemplates = false)
            .build(live)
        LpEngine(problem, objective, LpParams(lpPlan = LpPlan(bounding = true)), SolveStatsSink("equal-source"),
            projections = selected, reusePumpPreparation = true).use { engine ->
            assertNotNull(LpPrimalContinuation.capture(engine, root, live, Cancellation.Never, 4L) {}).use { guard ->
                domain = IntDomain(0, 10).excludeValue(4)

                assertTrue(guard.matches(engine, root, live, Cancellation.Never) {})
            }
        }
    }

    @Test
    fun `changed interior holes should invalidate a continued definition`() {
        var domain = IntDomain(0, 10).excludeValue(4)
        val live = object : RelaxationDomains by domains {
            override fun intDomain(varId: Int) = if (varId == 0) domain else domains.intDomain(varId)
        }
        val root = CpToLpRelaxation(problem, objective, projections = selected, reuseSourceTemplates = false)
            .build(live)
        LpEngine(problem, objective, LpParams(lpPlan = LpPlan(bounding = true)), SolveStatsSink("changed-hole"),
            projections = selected, reusePumpPreparation = true).use { engine ->
            assertNotNull(LpPrimalContinuation.capture(engine, root, live, Cancellation.Never, 4L) {}).use { guard ->
                domain = IntDomain(0, 10).excludeValue(5)

                assertFalse(guard.matches(engine, root, live, Cancellation.Never) {})
                assertEquals(0L, guard.paidWorkFor(engine))
            }
        }
    }

    @Test
    fun `factory definitions should retain complete live domain validation`() {
        var domain = IntDomain(0, 10).excludeValue(4)
        val live = object : RelaxationDomains by domains {
            override fun intDomain(varId: Int) = if (varId == 0) domain else domains.intDomain(varId)
        }
        val root = buildPrimalSource(problem, objective, live, Cancellation.Never, {}, selected,
            reuseCompleteTemplates = false, reuseSourceTemplates = false, retainPrivateDefinition = true)
        LpEngine(problem, objective, LpParams(lpPlan = LpPlan(bounding = true)), SolveStatsSink("factory-changed-hole"),
            projections = selected, reusePumpPreparation = true).use { engine ->
            assertNotNull(LpPrimalContinuation.capture(engine, root, live, Cancellation.Never, 4L) {}).use { guard ->
                domain = IntDomain(0, 10).excludeValue(5)

                assertFalse(guard.matches(engine, root, live, Cancellation.Never) {})
                assertEquals(0L, guard.paidWorkFor(engine))
            }
        }
    }

    @Test
    fun `changed bounds should invalidate a continued definition`() {
        var domain = IntDomain(0, 10)
        val live = object : RelaxationDomains by domains {
            override fun intDomain(varId: Int) = if (varId == 0) domain else domains.intDomain(varId)
        }
        val root = CpToLpRelaxation(problem, objective, projections = selected, reuseSourceTemplates = false)
            .build(live)
        LpEngine(problem, objective, LpParams(lpPlan = LpPlan(bounding = true)), SolveStatsSink("changed-bound"),
            projections = selected, reusePumpPreparation = true).use { engine ->
            assertNotNull(LpPrimalContinuation.capture(engine, root, live, Cancellation.Never, 4L) {}).use { guard ->
                domain = domain.withMinAtLeast(1)

                assertFalse(guard.matches(engine, root, live, Cancellation.Never) {})
            }
        }
    }

    @Test
    fun `changed Boolean pins should invalidate a continued definition`() {
        var value: Boolean? = null
        val live = object : RelaxationDomains by domains {
            override fun boolValue(varId: Int) = value
        }
        val root = CpToLpRelaxation(problem, objective, projections = selected, reuseSourceTemplates = false)
            .build(live)
        LpEngine(problem, objective, LpParams(lpPlan = LpPlan(bounding = true)), SolveStatsSink("changed-pin"),
            projections = selected, reusePumpPreparation = true).use { engine ->
            assertNotNull(LpPrimalContinuation.capture(engine, root, live, Cancellation.Never, 4L) {}).use { guard ->
                value = true

                assertFalse(guard.matches(engine, root, live, Cancellation.Never) {})
            }
        }
    }

    @Test
    fun `foreign raw and finite engines should not consume a continued definition`() {
        val root = CpToLpRelaxation(problem, objective, projections = selected, reuseSourceTemplates = false)
            .build(domains)
        LpEngine(problem, objective, LpParams(lpPlan = LpPlan(bounding = true)), SolveStatsSink("source-owner"),
            projections = selected, reusePumpPreparation = true).use { owner ->
            assertNotNull(LpPrimalContinuation.capture(owner, root, domains, Cancellation.Never, 4L) {}).use { guard ->
                for (source in listOf(problem, problem.bake(FactorProjectionProviders.Default))) {
                    LpEngine(source, objective, LpParams(lpPlan = LpPlan(bounding = true)), SolveStatsSink("foreign"),
                        projections = FactorProjectionProviders.Default.prepare(source),
                        reusePumpPreparation = true).use { foreign ->
                        val before = foreign.totalSolveWork()

                        assertFalse(guard.matches(foreign, root, RootDomains(source), Cancellation.Never) {})

                        assertEquals(before, foreign.totalSolveWork())
                    }
                }
                assertTrue(guard.matches(owner, root, domains, Cancellation.Never) {})
            }
        }
    }

    @Test
    fun `changed factor order should invalidate a continued definition`() {
        val root = CpToLpRelaxation(problem, objective, projections = selected, reuseSourceTemplates = false)
            .build(domains)
        LpEngine(problem, objective, LpParams(lpPlan = LpPlan(bounding = true)), SolveStatsSink("changed-order"),
            projections = selected, reusePumpPreparation = true).use { engine ->
            assertNotNull(LpPrimalContinuation.capture(engine, root, domains, Cancellation.Never, 4L) {}).use { guard ->
                problem.factors.reverse()
                try {
                    assertFalse(guard.matches(engine, root, domains, Cancellation.Never) {})
                } finally {
                    problem.factors.reverse()
                }
            }
        }
    }

    @Test
    fun `discarded handoff work should remain in continued admission`() {
        val root = CpToLpRelaxation(problem, objective, projections = selected, reuseSourceTemplates = false)
            .build(domains)
        LpEngine(problem, objective, LpParams(lpPlan = LpPlan(bounding = true)), SolveStatsSink("discarded-work"),
            projections = selected, reusePumpPreparation = true).use { engine ->
            assertNotNull(LpPrimalContinuation.capture(engine, root, domains, Cancellation.Never, 4L) {}).use { guard ->
                val paid = guard.paidWorkFor(engine)
                val before = engine.totalSolveWork()
                assertNotNull(LpPrimalHandoff.open(engine, Cancellation.Never)).use { handoff ->
                    handoff.includeSourceWork(engine, paid)
                    handoff.attachContinuation(engine, guard)
                    handoff.capture(engine, root, Cancellation.Never)
                }

                assertEquals(paid + engine.totalSolveWork() - before, guard.paidWorkFor(engine))
            }
        }
    }

    @Test
    fun `closed provenance should withhold its continued definition`() {
        val root = CpToLpRelaxation(problem, objective, projections = selected, reuseSourceTemplates = false)
            .build(domains)
        LpEngine(problem, objective, LpParams(lpPlan = LpPlan(bounding = true)), SolveStatsSink("closed-source"),
            projections = selected, reusePumpPreparation = true).use { engine ->
            val guard = assertNotNull(LpPrimalContinuation.capture(engine, root, domains, Cancellation.Never, 4L) {})

            guard.close()

            assertFalse(guard.matches(engine, root, domains, Cancellation.Never) {})
            assertEquals(0L, guard.paidWorkFor(engine))
        }
    }

    @Test
    fun `cancelled validation should retire its continued definition`() {
        val root = CpToLpRelaxation(problem, objective, projections = selected, reuseSourceTemplates = false)
            .build(domains)
        var armed = false
        var stopped = false
        var charged = 0L
        LpEngine(problem, objective, LpParams(lpPlan = LpPlan(bounding = true)), SolveStatsSink("cancelled-source"),
            projections = selected, reusePumpPreparation = true, containingWork = {
                if (armed) {
                    charged += it
                    stopped = true
                }
            }).use { engine ->
            assertNotNull(LpPrimalContinuation.capture(engine, root, domains, Cancellation.Never, 4L) {}).use { guard ->
                armed = true

                assertFailsWith<LpAssemblyCancelled> {
                    guard.matches(engine, root, domains, Cancellation { stopped }) {}
                }

                assertEquals(1L, charged)
                assertEquals(0L, guard.paidWorkFor(engine))
            }
        }
    }

    @Test
    fun `oversized completed buffers should withhold continued provenance before copying domains`() {
        for (largeMatrix in listOf(false, true)) {
            val original = CpToLpRelaxation(problem, objective, projections = selected, reuseSourceTemplates = false)
                .build(domains)
            val base = original.model
            val rows = if (largeMatrix) 16_384 else base.m
            val model = LpModel(
                n = base.n,
                m = rows,
                csc = if (largeMatrix) Csc(
                    IntArray(base.n + 1) { it * rows },
                    IntArray(base.n * rows) { it % rows },
                    LongArray(base.n * rows) { -1L },
                ) else base.csc,
                rhs = LongArray(rows) { -6L },
                cost = base.cost.copyOf(base.n + rows),
                upper = base.upper.copyOf(base.n + rows),
                hasUpper = base.hasUpper.copyOf(base.n + rows),
                loShift = base.loShift,
                objConstant = base.objConstant,
                sense = base.sense,
                tag = base.tag,
                rowPremises = Array(rows) {
                    if (!largeMatrix && it == 0) {
                        LpRowPremises(IntArray(65_536), BooleanArray(65_536), LongArray(65_536))
                    } else null
                },
            )
            val root = original.withModel(model, sources = null)
            var charged = 0L
            LpEngine(problem, objective, LpParams(lpPlan = LpPlan(bounding = true)),
                SolveStatsSink("large-buffer-source"), projections = selected,
                reusePumpPreparation = true, containingWork = { charged += it }).use { engine ->

                val guard = LpPrimalContinuation.capture(engine, root, domains, Cancellation.Never, 4L) {}

                assertNull(guard)
                assertTrue(charged <= 2L)
            }
        }
    }

    @Test
    fun `large carved domains should withhold continued provenance`() {
        val source = Problem(1, 3, Array(3) { IntDomain(0, 1_000_000) }, problem.factors)
        val selection = FactorProjectionProviders.Default.prepare(source)
        val declared = RootDomains(source)
        val live = object : RelaxationDomains by declared {
            override fun intDomain(varId: Int) = if (varId == 0) {
                declared.intDomain(varId).excludeValue(4)
            } else declared.intDomain(varId)
        }
        val root = CpToLpRelaxation(source, objective, projections = selection, reuseSourceTemplates = false)
            .build(live)
        LpEngine(source, objective, LpParams(lpPlan = LpPlan(bounding = true)), SolveStatsSink("large-domain-source"),
            projections = selection, reusePumpPreparation = true).use { engine ->
            val guard = LpPrimalContinuation.capture(engine, root, live, Cancellation.Never, 4L) {}

            assertNull(guard)
        }
    }
}
