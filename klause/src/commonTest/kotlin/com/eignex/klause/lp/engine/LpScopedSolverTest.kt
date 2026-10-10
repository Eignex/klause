package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.basis.BasisArithmeticException
import com.eignex.klause.simplex.basis.BasisRepair
import com.eignex.klause.simplex.basis.BasisRepairControl
import com.eignex.klause.simplex.basis.BasisSolver
import com.eignex.klause.simplex.basis.BasisUpdate
import com.eignex.klause.simplex.basis.IndexedVector
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
    fun `new columns rows and bound assertions publish in one preparation`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val column = ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(ExactLpNumber.of(5L))), integral = true)
        val row = LpScopedRow(
            0L, listOf(0 to ExactLpNumber.of(-1L)), ExactLpNumber.of(-1L),
            ExactLpColumn(ExactLpBounds(ExactLpSide(zero))),
        )
        val empty = ExactLpModel(emptyList(), emptyList(), emptyList(), emptyList(), ExactLpObjective(emptyList()))
        LpScopedSolver(LpExactState(empty)).use { owner ->
            assertTrue(owner.push())

            assertTrue(owner.replaceRows(
                emptySet(), listOf(LpStructuralColumn(column, one)), listOf(row), true,
                assertions = listOf(LpBoundAssertion(0, false, ExactLpSide(ExactLpNumber.of(3L)), 0L, 1)),
            ))

            assertEquals(BigFraction.ofLong(3L), assertNotNull(owner.solve()).lowerBound)
            assertEquals(1L, owner.metrics.preparationAttempts)
            assertTrue(owner.pop(0))
            assertEquals(BigFraction.ZERO, assertNotNull(owner.solve()).lowerBound)
            assertEquals(1L, owner.metrics.createdOwners)
        }
    }

    @Test
    fun `an invalid bound in a structural batch preserves the original authority`() {
        val source = LpBuilder().apply {
            val x = addVar(0L, 3L, cost = 1L)
            addRow(intArrayOf(x), longArrayOf(1L), Relation.GE, 1L)
        }.build(Sense.MINIMIZE)
        val row = LpScopedRow(
            1L, listOf(0 to ExactLpNumber.of(-1L)), ExactLpNumber.of(-2L),
            ExactLpColumn(ExactLpBounds(ExactLpSide(ExactLpNumber.of(0L)))),
        )
        LpScopedSolver(LpExactState(assertNotNull(source.authoritativeModel()))).use { owner ->
            assertEquals(BigFraction.ONE, assertNotNull(owner.solve()).lowerBound)
            assertTrue(owner.push())
            val before = owner.state
            val preparations = owner.metrics.preparationAttempts

            assertFalse(owner.replaceRows(
                setOf(0L), emptyList(), listOf(row), true,
                assertions = listOf(LpBoundAssertion(2, false, ExactLpSide(ExactLpNumber.of(2L)), 0L, 0)),
            ))

            assertSame(before, owner.state)
            assertEquals(preparations, owner.metrics.preparationAttempts)
            assertEquals(BigFraction.ONE, assertNotNull(owner.solve()).lowerBound)
        }
    }

    @Test
    fun `mixed row lifetimes prepare once and keep a permanent definition through pop`() {
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
    fun `a source row replacement publishes one structural edit and restores the parent on pop`() {
        val source = LpBuilder().apply {
            val x = addVar(0L, 3L, cost = 1L)
            addRow(intArrayOf(x), longArrayOf(1L), Relation.GE, 1L)
        }.build(Sense.MINIMIZE)
        val zero = ExactLpNumber.of(0L)
        val row = LpScopedRow(
            1, listOf(0 to ExactLpNumber.of(-1L)), ExactLpNumber.of(-2L),
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
    fun `failed adoption cannot reuse a prior work stop`() {
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
    fun `engine failure after a pivot retains completed solve charge`() {
        val state = LpExactState(lowerBoundModel())
        val failure = IllegalStateException("basis update failed")
        val factory = object : LpEngineFactory by ProductionLpEngineFactory {
            override fun newPersistentSolver(
                model: LpModel,
                cancellation: Cancellation,
                refactorUpdateLimit: Int,
                iterationLimit: Int,
                workLimit: Long,
                trackDegeneracy: Boolean,
                pricing: LpPricingOptions,
            ): PersistentLpSolver = RevisedSimplex(
                model,
                cancellation,
                refactorUpdateLimit,
                iterationLimit,
                workLimit,
                trackDegeneracy,
                basisSolverFactory = { matrix ->
                    val delegate = KotlinBasisSolver(matrix)
                    object : BasisSolver by delegate {
                        override fun update(
                            pivotRow: Int,
                            entering: Int,
                            spike: IndexedVector,
                            pivotEta: IndexedVector?,
                        ): BasisUpdate = throw failure
                    }
                },
                pricing = pricing,
            )
        }
        LpScopedSolver(state, context = LpSolveContext(engineFactory = factory)).use { owner ->
            owner.withWorkingModel(LpWorkingModel.overrides(state)) { scope ->
                assertSame(failure, assertFailsWith<IllegalStateException> { scope.solveFloat() })

                assertNull(scope.lastFloatTermination)
                assertEquals(1, scope.metrics.solves.pivots)
                assertTrue(scope.metrics.solves.workOps > 0L)
            }
            assertEquals(0L, assertNotNull(owner.lastWorkingMetrics).owners.currentOwners)
            owner.requireAvailable()
        }
    }

    @Test
    fun `working scopes and bound pops restore the source objective`() {
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
            repeat(3) {
                assertEquals(BigFraction.ZERO, assertNotNull(owner.solve()).lowerBound)
                assertTrue(owner.push())
                assertTrue(owner.assertBound(x, false, ExactLpSide(one), 42L))
                assertEquals(BigFraction.ONE, assertNotNull(owner.solve()).lowerBound)
                val temporary = LpWorkingModel.overrides(
                    owner.state,
                    ExactLpObjective(listOf(ExactLpNumber.of(-1L), zero)),
                )
                owner.withWorkingModel(temporary) { scope ->
                    val result = assertNotNull(scope.solve())
                    assertEquals(BigFraction.ofLong(-2L), result.lowerBound)
                    assertEquals(listOf(BigFraction.ofLong(2L)), result.exactPrimal)
                }
                assertEquals(BigFraction.ONE, assertNotNull(owner.solve()).lowerBound)
                assertTrue(owner.pop(0))
            }
            assertEquals(BigFraction.ZERO, assertNotNull(owner.solve()).lowerBound)
        }
    }

    @Test
    fun `exact source validation uses shifted coordinates logical costs and minimized scale`() {
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

            B5bIndependentExactSourceValidator.validate(solver.state, result)

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
    fun `published replacement stays accepted and clears solve metrics when old owner cleanup throws`() {
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
            assertEquals(0L, solver.metrics.editDeclines)
            assertEquals(1L, solver.metrics.closedOwners)
            assertEquals(1L, solver.metrics.currentOwners)
            assertEquals(BigFraction.ONE, assertNotNull(solver.solve()).lowerBound)
        }
    }

    @Test
    fun `appended row conflict cancels structural coefficients and preserves guarded proof after pop`() {
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

            assertEquals(LpVerdict.INFEASIBLE, result.verdict)
            assertNull(result.boundConflict)
            val proof = assertNotNull(result.rationalConflict)
            val support = assertNotNull(result.conflictSupport)
            assertEquals(setOf(0, 1), proof.rows.toSet())
            var coefficient = BigFraction.ZERO
            var rhs = BigFraction.ZERO
            for (entry in proof.rows.indices) {
                val row = proof.rows[entry]
                val multiplier = proof.multipliers[entry]
                assertTrue(multiplier > BigFraction.ZERO)
                coefficient += multiplier * if (row == 0) BigFraction.ONE else BigFraction.ONE.negated()
                rhs += multiplier * if (row == 0) BigFraction.ZERO else BigFraction.ONE.negated()
            }
            assertEquals(BigFraction.ZERO, coefficient)
            assertTrue(rhs < BigFraction.ZERO)
            assertEquals(setOf(1, 2), proof.bounds.map { it.column }.toSet())
            assertTrue(proof.bounds.all { !it.upper })
            assertEquals(
                setOf(41, 42),
                support.rows.map { assertNotNull(it.second.premises).literalEntries().single() }.toSet(),
            )

            assertTrue(solver.pop(0))
            assertTrue(solver.compact())

            val feasible = assertNotNull(solver.solve())
            assertEquals(LpVerdict.ATTAINED_OPTIMUM, feasible.verdict)
            assertTrue(assertNotNull(feasible.exactPrimal).single() <= BigFraction.ZERO)
            assertEquals(listOf(2L), solver.state.rows.entries().map { it.id })
            assertEquals(listOf(2L, 3L), support.state.rows.entries().map { it.id })
            assertTrue(checkedLpConflict(assertNotNull(support.state.toWorkingModel()), proof))
        }
    }

    @Test
    fun `explicit deactivation of strict sides permits the exact boundary witness`() {
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
            val strict = assertNotNull(solver.solve())
            val point = assertNotNull(strict.witness)
            assertTrue(point.primal.single() > BigFraction.ZERO && point.primal.single() <= BigFraction.ONE)
            assertEquals(point.primal.single(), point.objective)
            assertEquals(LpVerdict.FEASIBLE, strict.verdict)
            assertEquals(BigFraction.ZERO, strict.lowerBound)

            assertTrue(solver.deactivate(4))
            val result = assertNotNull(solver.solve())

            assertEquals(LpVerdict.ATTAINED_OPTIMUM, result.verdict)
            assertEquals(listOf(BigFraction.ZERO), result.exactPrimal)
            assertEquals(BigFraction.ZERO, result.lowerBound)
            assertTrue(assertNotNull(result.bound?.support).rows.isEmpty())
            assertEquals(0L, solver.lastMetrics.initialRefactorizations.toLong())
            assertTrue(solver.compact())
            assertEquals(result.exactPrimal, assertNotNull(solver.solve()).exactPrimal)
        }
    }

    @Test
    fun `logical objective must be explicitly removed before row lifetime ends`() {
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
    fun `failed append preparation closes staged owners and preserves the current solve`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val source = ExactLpModel(
            listOf(emptyList()),
            emptyList(),
            listOf(ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(one)))),
            emptyList(),
            ExactLpObjective(listOf(one)),
        )
        for (failure in listOf("unsupported", "singular", "arithmetic", "unexpected", "cancel")) {
            var fail = false
            var cancelled = false
            var closes = 0
            val token = Cancellation { cancelled }
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
                                override fun refactorize(basicIndex: IntArray): Boolean {
                                    if (fail) {
                                        when (failure) {
                                            "singular" -> return false
                                            "arithmetic" -> throw BasisArithmeticException("injected preparation")
                                            "unexpected" -> error("injected preparation")
                                            "cancel" -> cancelled = true
                                        }
                                    }
                                    return factors.refactorize(basicIndex)
                                }

                                override fun refactorizeRepairing(
                                    basicIndex: IntArray,
                                    control: BasisRepairControl,
                                ): BasisRepair? {
                                    if (fail) {
                                        when (failure) {
                                            "singular" -> return null
                                            "arithmetic" -> throw BasisArithmeticException("injected preparation")
                                            "unexpected" -> error("injected preparation")
                                            "cancel" -> cancelled = true
                                        }
                                    }
                                    return factors.refactorizeRepairing(basicIndex, control)
                                }
                            }
                        },
                    )
                    return object : PersistentLpSolver by delegate {
                        override fun prepareLogicals(token: Cancellation): Basis? =
                            if (fail && failure == "unsupported") null else delegate.prepareLogicals(token)

                        override fun prepareBasis(basis: Basis, token: Cancellation): Basis? =
                            if (fail && failure == "unsupported") null else delegate.prepareBasis(basis, token)
                        override fun close() {
                            closes++
                            delegate.close()
                        }
                    }
                }
            }
            LpScopedSolver(LpExactState(source), token, LpSolveContext(engineFactory = factory)).use { solver ->
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
                assertEquals(1L, solver.metrics.currentOwners)
                assertEquals(1L, solver.metrics.editDeclines)
                assertEquals(1L, solver.metrics.preparationDeclines)
                assertEquals(1, closes)
                fail = false
                cancelled = false
                assertEquals(original.exactPrimal, assertNotNull(solver.solve()).exactPrimal)
            }
            assertEquals(2, closes)
        }
    }

    @Test
    fun `failed compaction staging or reduced build preserves tombstones and the live owner`() {
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
    fun `resource and adoption declines are atomic and cancelled solves withhold committed assertions`() {
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

}

internal object B5bIndependentExactSourceValidator {
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
