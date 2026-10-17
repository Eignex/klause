package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.basis.BasisArithmeticException
import com.eignex.klause.simplex.basis.BasisRepair
import com.eignex.klause.simplex.basis.BasisRepairControl
import com.eignex.klause.simplex.basis.BasisSolver
import com.eignex.klause.simplex.basis.KotlinBasisSolver
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LpScopedSolverTest {
    @Test
    fun `a bound request should retain only its requested evidence before a complete solve`() {
        LpScopedSolver(LpExactState(lowerBoundModel())).use { owner ->
            val bound = assertNotNull(owner.solve(evidenceGoal = LpEvidenceGoal.BOUND))

            assertTrue(bound.boundRequestSatisfied)
            assertEquals(LpVerdict.CERTIFIED_BOUND, bound.verdict)
            assertEquals(BigFraction.ONE, bound.lowerBound)
            assertNull(bound.exactPrimal)
            assertNull(bound.reconstruction)
            assertNull(bound.refinement)
            assertNull(bound.continuation)
            assertSame(owner.state, assertNotNull(bound.bound?.support).state)

            val complete = assertNotNull(owner.solve())

            assertFalse(complete.boundRequestSatisfied)
            assertEquals(LpVerdict.ATTAINED_OPTIMUM, complete.verdict)
            assertEquals(listOf(BigFraction.ONE), complete.exactPrimal)
        }
    }

    @Test
    fun `a bound request should preserve a numerical infeasibility certificate`() {
        val source = LpBuilder().apply {
            val x = addVar(0L, 2L)
            val y = addVar(0L, 2L)
            addRow(intArrayOf(x, y), longArrayOf(1L, 1L), Relation.GE, 3L)
            addRow(intArrayOf(x, y), longArrayOf(1L, 1L), Relation.LE, 1L)
        }.build(Sense.MINIMIZE)
        LpScopedSolver(LpExactState(assertNotNull(source.authoritativeModel()))).use { owner ->
            val result = assertNotNull(owner.solve(evidenceGoal = LpEvidenceGoal.BOUND))

            assertFalse(result.boundRequestSatisfied)
            assertEquals(LpVerdict.INFEASIBLE, result.verdict)
            assertNotNull(result.conflictSupport)
            assertNull(result.witness)
        }
    }

    @Test
    fun `a bound request should preserve a contradictory source side`() {
        LpScopedSolver(LpExactState(lowerBoundModel())).use { owner ->
            assertTrue(owner.assertBound(0, false, ExactLpSide(ExactLpNumber.of(11L)), 1L))

            val result = assertNotNull(owner.solve(evidenceGoal = LpEvidenceGoal.BOUND))

            assertFalse(result.boundRequestSatisfied)
            assertEquals(LpVerdict.INFEASIBLE, result.verdict)
            assertNotNull(result.boundConflict)
            assertSame(owner.state, assertNotNull(result.conflictSupport).state)
        }
    }

    @Test
    fun `new columns rows and bound assertions should publish in one preparation`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val column = ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(ExactLpNumber.of(5L))), integral = true)
        val row = LpScopedRow(
            0L,
            listOf(0 to ExactLpNumber.of(-1L)),
            ExactLpNumber.of(-1L),
            ExactLpColumn(ExactLpBounds(ExactLpSide(zero))),
        )
        val empty = ExactLpModel(emptyList(), emptyList(), emptyList(), emptyList(), ExactLpObjective(emptyList()))
        LpScopedSolver(LpExactState(empty)).use { owner ->
            assertTrue(owner.push())

            assertTrue(
                owner.replaceRows(
                    emptySet(),
                    listOf(LpStructuralColumn(column, one)),
                    listOf(row),
                    true,
                    assertions = listOf(LpBoundAssertion(0, false, ExactLpSide(ExactLpNumber.of(3L)), 0L, 1)),
                ),
            )

            assertEquals(BigFraction.ofLong(3L), assertNotNull(owner.solve()).lowerBound)
            assertEquals(1L, owner.metrics.preparationAttempts)
            assertTrue(owner.pop(0))
            assertEquals(BigFraction.ZERO, assertNotNull(owner.solve()).lowerBound)
            assertEquals(1L, owner.metrics.createdOwners)
        }
    }

    @Test
    fun `an invalid bound in a structural batch should preserve the original authority`() {
        val source = LpBuilder().apply {
            val x = addVar(0L, 3L, cost = 1L)
            addRow(intArrayOf(x), longArrayOf(1L), Relation.GE, 1L)
        }.build(Sense.MINIMIZE)
        val row = LpScopedRow(
            1L,
            listOf(0 to ExactLpNumber.of(-1L)),
            ExactLpNumber.of(-2L),
            ExactLpColumn(ExactLpBounds(ExactLpSide(ExactLpNumber.of(0L)))),
        )
        LpScopedSolver(LpExactState(assertNotNull(source.authoritativeModel()))).use { owner ->
            assertEquals(BigFraction.ONE, assertNotNull(owner.solve()).lowerBound)
            assertTrue(owner.push())
            val before = owner.state
            val preparations = owner.metrics.preparationAttempts

            assertFalse(
                owner.replaceRows(
                    setOf(0L),
                    emptyList(),
                    listOf(row),
                    true,
                    assertions = listOf(LpBoundAssertion(2, false, ExactLpSide(ExactLpNumber.of(2L)), 0L, 0)),
                ),
            )

            assertSame(before, owner.state)
            assertEquals(preparations, owner.metrics.preparationAttempts)
            assertEquals(BigFraction.ONE, assertNotNull(owner.solve()).lowerBound)
        }
    }

    @Test
    fun `mixed row lifetimes should prepare once and keep a permanent definition through pop`() {
        val source = LpBuilder().apply { addVar(0L, 3L, cost = 1L) }.build(Sense.MINIMIZE)
        val logical = ExactLpColumn(ExactLpBounds(ExactLpSide(ExactLpNumber.of(0L))))
        val rows = listOf(
            LpScopedRow(0L, listOf(0 to ExactLpNumber.of(-1L)), ExactLpNumber.of(-1L), logical),
            LpScopedRow(1L, listOf(0 to ExactLpNumber.of(-1L)), ExactLpNumber.of(-2L), logical),
        )
        LpScopedSolver(LpExactState(assertNotNull(source.authoritativeModel()))).use { owner ->
            assertEquals(BigFraction.ZERO, assertNotNull(owner.solve()).lowerBound)
            assertTrue(owner.push())

            assertTrue(owner.replaceRows(emptySet(), emptyList(), rows, true, permanentRows = setOf(0L)))

            assertEquals(BigFraction.ofLong(2L), assertNotNull(owner.solve()).lowerBound)
            assertEquals(2L, owner.metrics.createdOwners)
            assertTrue(owner.pop(0))
            assertEquals(BigFraction.ONE, assertNotNull(owner.solve()).lowerBound)
            assertTrue(owner.state.rows.row(0).active)
            assertFalse(owner.state.rows.row(1).active)
            assertTrue(owner.compact())
            assertEquals(1, owner.state.model.m)
        }
    }

    @Test
    fun `a source row replacement should publish one structural edit and restore the parent on pop`() {
        val source = LpBuilder().apply {
            val x = addVar(0L, 3L, cost = 1L)
            addRow(intArrayOf(x), longArrayOf(1L), Relation.GE, 1L)
        }.build(Sense.MINIMIZE)
        val zero = ExactLpNumber.of(0L)
        val row = LpScopedRow(
            1,
            listOf(0 to ExactLpNumber.of(-1L)),
            ExactLpNumber.of(-2L),
            ExactLpColumn(ExactLpBounds(ExactLpSide(zero))),
        )
        LpScopedSolver(LpExactState(assertNotNull(source.authoritativeModel()))).use { owner ->
            assertEquals(BigFraction.ONE, assertNotNull(owner.solve()).lowerBound)
            assertTrue(owner.push())

            assertTrue(owner.replaceRows(setOf(0), emptyList(), listOf(row), true))

            assertEquals(BigFraction.ofLong(2L), assertNotNull(owner.solve()).lowerBound)
            assertEquals(2L, owner.metrics.createdOwners)
            assertTrue(owner.pop(0))
            assertEquals(BigFraction.ONE, assertNotNull(owner.solve()).lowerBound)
        }
    }

    @Test
    fun `failed adoption should not reuse a prior work stop`() {
        val state = LpExactState(lowerBoundModel())
        var reject = false
        val factory = object : LpEngineFactory by ProductionLpEngineFactory {
            override fun newPersistentSolver(
                model: LpModel,
                cancellation: Cancellation,
                refactorUpdateLimit: Int,
                iterationLimit: Int,
                workLimit: Long,
                trackDegeneracy: Boolean,
                pricing: LpPricingOptions,
            ): PersistentLpSolver {
                val delegate = ProductionLpEngineFactory.newPersistentSolver(
                    model,
                    cancellation,
                    refactorUpdateLimit,
                    iterationLimit,
                    workLimit,
                    trackDegeneracy,
                    pricing,
                )
                return object : PersistentLpSolver by delegate {
                    override fun adopt(state: LpExactState, token: Cancellation): Boolean =
                        !reject && delegate.adopt(state, token)
                }
            }
        }
        LpScopedSolver(state, context = LpSolveContext(engineFactory = factory)).use { owner ->
            owner.withWorkingModel(LpWorkingModel.overrides(state)) { scope ->
                val first = assertNotNull(scope.solveFloat(allowance = LpFloatAllowance(1L, 100)))
                assertNull(first.second)
                assertEquals(LpFloatTermination.WORK, scope.lastFloatTermination)
                val spent = scope.metrics.solves
                reject = true

                assertNull(scope.solveFloat())

                assertNull(scope.lastFloatTermination)
                assertEquals(spent, scope.metrics.solves)
            }
        }
    }

    @Test
    fun `working scopes and bound pops should restore the source objective`() {
        val x = 0
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val state = LpExactState(
            ExactLpModel(
                listOf(listOf(ExactLpEntry(0, one))),
                listOf(ExactLpNumber.of(2L)),
                listOf(
                    ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(ExactLpNumber.of(3L)))),
                    ExactLpColumn(ExactLpBounds(ExactLpSide(zero))),
                ),
                listOf(ExactLpRow()),
                ExactLpObjective(listOf(one, zero)),
            ),
        )
        LpScopedSolver(state).use { owner ->
            assertTrue(owner.push())
            assertTrue(owner.assertBound(x, false, ExactLpSide(one), 42L))
            val temporary = LpWorkingModel.overrides(
                owner.state,
                ExactLpObjective(listOf(ExactLpNumber.of(-1L), zero)),
            )
            owner.withWorkingModel(temporary) { scope ->
                assertEquals(BigFraction.ofLong(-2L), assertNotNull(scope.solve()).lowerBound)
            }
            assertEquals(BigFraction.ONE, assertNotNull(owner.solve()).lowerBound)
            assertTrue(owner.pop(0))

            assertEquals(BigFraction.ZERO, assertNotNull(owner.solve()).lowerBound)
        }
    }

    @Test
    fun `exact source validation should use shifted coordinates logical costs and minimized scale`() {
        val zero = ExactLpNumber.of(0L)
        val model = ExactLpModel(
            listOf(listOf(ExactLpEntry(0, ExactLpNumber.of(-1L)))),
            listOf(ExactLpNumber.of(-1L)),
            listOf(
                ExactLpColumn(
                    ExactLpBounds(ExactLpSide(zero), ExactLpSide(ExactLpNumber.of(10L))),
                    origin = ExactLpNumber.of(1L),
                ),
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero))),
            ),
            listOf(ExactLpRow()),
            ExactLpObjective(
                listOf(ExactLpNumber.of(2L), ExactLpNumber.of(3L)),
                constant = ExactLpNumber.of(4L),
                scale = ExactLpNumber.of(2L),
                externalConstant = ExactLpNumber.of(5L),
            ),
        )
        LpScopedSolver(LpExactState(model)).use { solver ->
            val result = assertNotNull(solver.solve())

            IndependentExactSourceValidator.validate(solver.state, result)

            assertEquals(listOf(BigFraction.ofLong(2L)), result.exactPrimal)
            assertEquals(BigFraction.ofLong(8L), assertNotNull(result.witness).objective)
        }
    }

    private fun lowerBoundModel(): ExactLpModel {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val minusOne = ExactLpNumber.of(-1L)
        return ExactLpModel(
            listOf(listOf(ExactLpEntry(0, minusOne))),
            listOf(minusOne),
            listOf(
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(ExactLpNumber.of(10L)))),
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero))),
            ),
            listOf(ExactLpRow()),
            ExactLpObjective(listOf(one, zero)),
        )
    }

    @Test
    fun `a published replacement should stay accepted and clear solve metrics when old owner cleanup throws`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val minusOne = ExactLpNumber.of(-1L)
        val source = ExactLpModel(
            listOf(listOf(ExactLpEntry(0, minusOne))),
            listOf(minusOne),
            listOf(
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(ExactLpNumber.of(2L)))),
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero))),
            ),
            listOf(ExactLpRow()),
            ExactLpObjective(listOf(one, zero)),
        )
        val cleanup = IllegalStateException("old owner cleanup failure")
        val factory = object : LpEngineFactory by ProductionLpEngineFactory {
            override fun newPersistentSolver(
                model: LpModel,
                cancellation: Cancellation,
                refactorUpdateLimit: Int,
                iterationLimit: Int,
                workLimit: Long,
                trackDegeneracy: Boolean,
                pricing: LpPricingOptions,
            ): PersistentLpSolver {
                val delegate = RevisedSimplex(model, cancellation, pricing = pricing)
                return object : PersistentLpSolver by delegate {
                    override fun close() {
                        delegate.close()
                        if (model.m == 1) throw cleanup
                    }
                }
            }
        }
        LpScopedSolver(LpExactState(source), context = LpSolveContext(engineFactory = factory)).use { solver ->
            assertNotNull(solver.solve())
            assertTrue(solver.lastMetrics.workOps > 0L)
            val row = LpScopedRow(2, listOf(0 to one), one, ExactLpColumn(ExactLpBounds()))

            val failure = assertFailsWith<IllegalStateException> { solver.append(row, false) }

            assertSame(cleanup, failure)
            assertEquals(listOf(0L, 2L), solver.state.rows.entries().map { it.id })
            assertNull(solver.lastResult)
            assertEquals(LpSolveMetrics(), solver.lastMetrics)
            assertEquals(1L, solver.metrics.editSuccesses)
            assertEquals(BigFraction.ONE, assertNotNull(solver.solve()).lowerBound)
        }
    }

    @Test
    fun `an appended row conflict should cancel structural coefficients and preserve guarded proof after pop`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val minusOne = ExactLpNumber.of(-1L)
        val logical = ExactLpColumn(ExactLpBounds(ExactLpSide(zero)))
        val source = ExactLpModel(
            listOf(emptyList()),
            emptyList(),
            listOf(ExactLpColumn(ExactLpBounds())),
            emptyList(),
            ExactLpObjective(listOf(zero)),
        )
        LpScopedSolver(LpExactState(source)).use { solver ->
            assertTrue(
                solver.append(
                    LpScopedRow(
                        2,
                        listOf(0 to one),
                        zero,
                        logical,
                        ExactLpRow(false, premises = ExactLpPremises(emptyList(), listOf(41))),
                    ),
                    false,
                ),
            )
            assertTrue(solver.push())
            assertTrue(
                solver.append(
                    LpScopedRow(
                        3,
                        listOf(0 to minusOne),
                        minusOne,
                        logical,
                        ExactLpRow(false, premises = ExactLpPremises(emptyList(), listOf(42))),
                    ),
                    true,
                ),
            )

            val result = assertNotNull(solver.solve())

            assertTrue(solver.pop(0))
            assertTrue(solver.compact())

            assertEquals(LpVerdict.INFEASIBLE, result.verdict)
            val proof = assertNotNull(result.rationalConflict)
            val support = assertNotNull(result.conflictSupport)
            assertEquals(setOf(0, 1), proof.rows.toSet())
            assertEquals(
                setOf(41, 42),
                support.rows.map { assertNotNull(it.second.premises).literalEntries().single() }.toSet(),
            )
            assertEquals(listOf(2L, 3L), support.state.rows.entries().map { it.id })
            assertTrue(checkedLpConflict(assertNotNull(support.state.toWorkingModel()), proof))
        }
    }

    @Test
    fun `explicit deactivation of strict sides should permit the exact boundary witness`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val source = ExactLpModel(
            listOf(emptyList()),
            emptyList(),
            listOf(ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(one)))),
            emptyList(),
            ExactLpObjective(listOf(one)),
        )
        LpScopedSolver(LpExactState(source)).use { solver ->
            assertTrue(
                solver.append(
                    LpScopedRow(
                        4,
                        listOf(0 to one),
                        one,
                        ExactLpColumn(ExactLpBounds(upper = ExactLpSide(one, strict = true))),
                    ),
                    scoped = false,
                ),
            )
            assertEquals(LpVerdict.FEASIBLE, assertNotNull(solver.solve()).verdict)

            assertTrue(solver.deactivate(4))
            val result = assertNotNull(solver.solve())

            assertEquals(LpVerdict.ATTAINED_OPTIMUM, result.verdict)
            assertEquals(listOf(BigFraction.ZERO), result.exactPrimal)
            assertTrue(assertNotNull(result.bound?.support).rows.isEmpty())
        }
    }

    @Test
    fun `a logical objective should be explicitly removed before row lifetime ends`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val bounds = ExactLpBounds(ExactLpSide(zero), ExactLpSide(one))
        val source = ExactLpModel(
            listOf(emptyList()),
            emptyList(),
            listOf(ExactLpColumn(bounds)),
            emptyList(),
            ExactLpObjective(listOf(zero)),
        )
        LpScopedSolver(LpExactState(source)).use { solver ->
            assertTrue(solver.push())
            assertTrue(solver.append(LpScopedRow(1, listOf(0 to one), one, ExactLpColumn(bounds), cost = one), true))
            val priced = assertNotNull(solver.solve())
            assertEquals(listOf(BigFraction.ONE), priced.exactPrimal)
            assertEquals(BigFraction.ZERO, priced.lowerBound)
            val before = solver.state
            assertFalse(solver.pop(0))
            assertSame(before, solver.state)
            assertSame(priced, solver.lastResult)

            assertTrue(solver.replaceObjective(ExactLpObjective(listOf(one, zero))))
            assertTrue(solver.pop(0))
            assertTrue(solver.compact())

            val result = assertNotNull(solver.solve())
            assertEquals(listOf(BigFraction.ZERO), result.exactPrimal)
            assertEquals(BigFraction.ZERO, result.lowerBound)
            assertEquals(1L, solver.metrics.editDeclines)
        }
    }

    @Test
    fun `failed append preparation should close staged owners and preserve the current solve`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val source = ExactLpModel(
            listOf(emptyList()),
            emptyList(),
            listOf(ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(one)))),
            emptyList(),
            ExactLpObjective(listOf(one)),
        )
        for (failure in listOf("singular", "arithmetic", "unexpected")) {
            var fail = false
            var closes = 0
            val factory = object : LpEngineFactory by ProductionLpEngineFactory {
                override fun newPersistentSolver(
                    model: LpModel,
                    cancellation: Cancellation,
                    refactorUpdateLimit: Int,
                    iterationLimit: Int,
                    workLimit: Long,
                    trackDegeneracy: Boolean,
                    pricing: LpPricingOptions,
                ): PersistentLpSolver {
                    val delegate = RevisedSimplex(
                        model,
                        cancellation,
                        pricing = pricing,
                        basisSolverFactory = { matrix ->
                            val factors = KotlinBasisSolver(matrix)
                            object : BasisSolver by factors {
                                private fun singular(): Boolean {
                                    if (!fail) return false
                                    when (failure) {
                                        "arithmetic" -> throw BasisArithmeticException("injected preparation")
                                        "unexpected" -> error("injected preparation")
                                    }
                                    return true
                                }

                                override fun refactorize(basicIndex: IntArray): Boolean =
                                    !singular() && factors.refactorize(basicIndex)

                                override fun refactorizeRepairing(
                                    basicIndex: IntArray,
                                    control: BasisRepairControl,
                                ): BasisRepair? =
                                    if (singular()) null else factors.refactorizeRepairing(basicIndex, control)
                            }
                        },
                    )
                    return object : PersistentLpSolver by delegate {
                        override fun close() {
                            closes++
                            delegate.close()
                        }
                    }
                }
            }
            LpScopedSolver(LpExactState(source), context = LpSolveContext(engineFactory = factory)).use { solver ->
                val original = assertNotNull(solver.solve())
                val before = solver.state
                fail = true
                val row = LpScopedRow(1, listOf(0 to one), zero, ExactLpColumn(ExactLpBounds()))
                if (failure == "unexpected") {
                    assertFailsWith<IllegalStateException> { solver.append(row, true) }
                } else {
                    assertFalse(solver.append(row, true))
                }
                assertSame(before, solver.state)
                assertSame(original, solver.lastResult)
                assertEquals(1L, solver.metrics.preparationDeclines)
                assertEquals(1, closes)
            }
        }
    }

    @Test
    fun `failed compaction staging or reduced build should preserve tombstones and the live owner`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val source = ExactLpModel(
            listOf(listOf(ExactLpEntry(0, one))),
            listOf(one),
            listOf(ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(one))), ExactLpColumn(ExactLpBounds())),
            listOf(ExactLpRow()),
            ExactLpObjective(listOf(one, zero)),
        )
        for (failureOffset in listOf(1, 2)) {
            var builds = 0
            var failAt = -1
            val factory = object : LpEngineFactory by ProductionLpEngineFactory {
                override fun newPersistentSolver(
                    model: LpModel,
                    cancellation: Cancellation,
                    refactorUpdateLimit: Int,
                    iterationLimit: Int,
                    workLimit: Long,
                    trackDegeneracy: Boolean,
                    pricing: LpPricingOptions,
                ): PersistentLpSolver {
                    val delegate = RevisedSimplex(model, cancellation, pricing = pricing)
                    return object : PersistentLpSolver by delegate {
                        override fun prepareLogicals(token: Cancellation): Basis? =
                            if (++builds == failAt) null else delegate.prepareLogicals(token)
                    }
                }
            }
            LpScopedSolver(LpExactState(source), context = LpSolveContext(engineFactory = factory)).use { solver ->
                assertNotNull(solver.solve())
                assertTrue(solver.deactivate(0))
                val before = solver.state
                failAt = builds + failureOffset

                assertFalse(solver.compact())

                assertSame(before, solver.state)
                assertEquals(1L, solver.metrics.currentOwners)
                assertEquals(1, solver.metrics.retainedRows)
                assertEquals(0, solver.metrics.activeRows)
                assertEquals(1L, solver.metrics.preparationDeclines)
                assertEquals(listOf(BigFraction.ZERO), assertNotNull(solver.solve()).exactPrimal)
                failAt = -1
                assertTrue(solver.compact())
                assertEquals(0, solver.state.model.m)
            }
        }
    }

    @Test
    fun `resource and adoption declines should be atomic and cancelled solves should withhold committed assertions`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val source = ExactLpModel(
            listOf(emptyList()),
            emptyList(),
            listOf(ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(one)))),
            emptyList(),
            ExactLpObjective(listOf(one)),
        )
        var reject = false
        val factory = object : LpEngineFactory by ProductionLpEngineFactory {
            override fun newPersistentSolver(
                model: LpModel,
                cancellation: Cancellation,
                refactorUpdateLimit: Int,
                iterationLimit: Int,
                workLimit: Long,
                trackDegeneracy: Boolean,
                pricing: LpPricingOptions,
            ): PersistentLpSolver {
                val delegate = RevisedSimplex(model, cancellation, pricing = pricing)
                return object : PersistentLpSolver by delegate {
                    override fun adopt(state: LpExactState, token: Cancellation): Boolean =
                        !reject && delegate.adopt(state, token)
                }
            }
        }
        LpScopedSolver(
            LpExactState(source),
            context = LpSolveContext(engineFactory = factory),
            maxRetainedRows = 0,
        ).use { solver ->
            val original = assertNotNull(solver.solve())
            val before = solver.state
            assertFalse(solver.append(LpScopedRow(0, emptyList(), zero, ExactLpColumn(ExactLpBounds())), false))
            assertSame(before, solver.state)
            reject = true
            assertFalse(solver.assertBound(0, false, ExactLpSide(one), 1))
            assertSame(before, solver.state)
            assertSame(original, solver.lastResult)
            reject = false
            assertTrue(solver.assertBound(0, false, ExactLpSide(one), 1))

            assertNull(solver.solve(token = Cancellation { true }))

            assertNull(solver.lastResult)
            assertEquals(1L, solver.state.assertions.single().witness)
            assertEquals(listOf(BigFraction.ONE), assertNotNull(solver.solve()).exactPrimal)
            assertEquals(2L, solver.metrics.editDeclines)
        }
    }

    @Test
    fun `projection should exhaust the setup budget before allocating a numerical owner`() {
        val zero = ExactLpNumber.of(0L)
        val source = ExactLpModel(
            listOf(emptyList()),
            emptyList(),
            listOf(ExactLpColumn(ExactLpBounds())),
            emptyList(),
            ExactLpObjective(listOf(zero)),
        )
        LpScopedSolver(LpExactState(source), workLimit = source.keySize + 2L).use { owner ->
            assertNull(owner.solve())

            assertEquals(0L, owner.metrics.createdOwners)
            assertTrue(owner.metrics.preparationWork > 0L)
        }
    }

    @Test
    fun `a warm hint with an explicit allowance should be rejected before preparation`() {
        val builder = LpBuilder()
        builder.addVar(0, 1)
        val state = LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
        LpScopedSolver(state).use { owner ->
            assertFailsWith<IllegalArgumentException> {
                owner.solveFloat(Basis(intArrayOf(), arrayOf(VarStatus.AT_LOWER)), allowance = LpFloatAllowance(1L, 1))
            }

            assertSame(state, owner.state)
            assertEquals(0L, owner.metrics.createdOwners)
            assertEquals(LpSolveMetrics(), owner.lastMetrics)
        }
    }
}

private object IndependentExactSourceValidator {
    fun validate(state: LpExactState, result: CertifiedLpResult) {
        val primal = assertNotNull(result.exactPrimal)
        assertEquals(state.model.n, primal.size)
        val shifted = List(state.model.n) { column ->
            primal[column] - state.model.column(column).origin.value
        }
        for (column in shifted.indices) validateBounds(shifted[column], state.model.column(column).bounds)
        val logicals = MutableList(state.model.m) { BigFraction.ZERO }
        for (row in 0 until state.model.m) {
            var logical = state.model.rhs(row).value
            for (column in 0 until state.model.n) {
                val coefficient = state.model.entries(column).firstOrNull { it.row == row }?.number?.value
                    ?: BigFraction.ZERO
                logical -= coefficient * shifted[column]
            }
            validateBounds(logical, state.model.column(state.model.n + row).bounds)
            logicals[row] = logical
        }
        val coordinates = shifted + logicals
        var objective = state.model.objective.constant.value
        for (column in coordinates.indices) {
            objective += state.model.objective.cost(column).value * coordinates[column]
        }
        objective = objective * state.model.objective.scale.value.reciprocal() +
            state.model.objective.externalConstant.value
        assertEquals(objective, assertNotNull(result.witness).objective)
    }

    private fun validateBounds(value: BigFraction, bounds: ExactLpBounds) {
        bounds.lower?.let { side ->
            if (side.strict) assertTrue(value > side.number.value) else assertTrue(value >= side.number.value)
        }
        bounds.upper?.let { side ->
            if (side.strict) assertTrue(value < side.number.value) else assertTrue(value <= side.number.value)
        }
    }
}
