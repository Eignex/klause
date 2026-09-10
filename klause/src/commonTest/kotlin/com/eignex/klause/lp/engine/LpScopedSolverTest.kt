package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.basis.BasisArithmeticException
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
    fun `rejected preparation preserves the primary failure when cleanup also throws`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val source = ExactLpModel(
            listOf(emptyList()),
            emptyList(),
            listOf(ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(one)))),
            emptyList(),
            ExactLpObjective(listOf(one)),
        )
        val primary = IllegalStateException("preparation failure")
        val cleanup = IllegalStateException("cleanup failure")
        val factory = object : LpEngineFactory by ProductionLpEngineFactory {
            override fun newPersistentSolver(
                model: LpModel,
                cancellation: Cancellation,
                refactorUpdateLimit: Int,
                iterationLimit: Int,
                workLimit: Long,
                trackDegeneracy: Boolean,
            ): PersistentLpSolver {
                val delegate = RevisedSimplex(model, cancellation)
                return object : PersistentLpSolver by delegate {
                    override fun prepareLogicals(token: Cancellation): Basis? =
                        if (model.m > 0) throw primary else delegate.prepareLogicals(token)

                    override fun close() {
                        delegate.close()
                        if (model.m > 0) throw cleanup
                    }
                }
            }
        }
        LpScopedSolver(LpExactState(source), context = LpSolveContext(engineFactory = factory)).use { solver ->
            val original = assertNotNull(solver.solve())
            val before = solver.state
            val row = LpScopedRow(1, listOf(0 to one), one, ExactLpColumn(ExactLpBounds()))

            val failure = assertFailsWith<IllegalStateException> { solver.append(row, false) }

            assertSame(primary, failure)
            assertSame(cleanup, failure.suppressedExceptions.single())
            assertSame(before, solver.state)
            assertSame(original, solver.lastResult)
            assertEquals(1L, solver.metrics.editDeclines)
            assertEquals(1L, solver.metrics.closedOwners)
            assertEquals(1L, solver.metrics.currentOwners)
        }
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
            ): PersistentLpSolver {
                val delegate = RevisedSimplex(model, cancellation)
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
    fun `compaction preserves a priced rational logical and IEEE row in source objective units`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val third = ExactLpNumber.of(BigFraction.ofLong(3L).reciprocal())
        val half = ExactLpNumber.ofIeee(0.5)
        val logical = ExactLpColumn(ExactLpBounds(ExactLpSide(zero)))
        val column = ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(one)), origin = one)
        val source = ExactLpModel(
            listOf(emptyList()),
            emptyList(),
            listOf(column),
            emptyList(),
            ExactLpObjective(listOf(zero), third, third, third),
        )
        LpScopedSolver(LpExactState(source)).use { solver ->
            assertTrue(solver.push())
            assertTrue(solver.append(LpScopedRow(1, listOf(0 to one), one, logical), true))
            assertTrue(solver.append(LpScopedRow(2, listOf(0 to third), third, logical, cost = third), false))
            assertTrue(solver.append(LpScopedRow(3, listOf(0 to half), half, logical), false))
            val before = assertNotNull(solver.solve())

            assertTrue(solver.pop(0))
            assertTrue(solver.compact())
            val result = assertNotNull(solver.solve())

            assertEquals(LpVerdict.ATTAINED_OPTIMUM, result.verdict)
            assertEquals(listOf(BigFraction.ofLong(2L)), result.exactPrimal)
            assertEquals(BigFraction.ONE + third.value, result.lowerBound)
            assertEquals(before.lowerBound, result.lowerBound)
            assertEquals(third, solver.state.model.objective.cost(1))
            assertEquals(half, solver.state.model.entries(0)[1].number)
            assertEquals(half, solver.state.model.rhs(1))
            val fresh = ExactLpModel(
                listOf(listOf(ExactLpEntry(0, third), ExactLpEntry(1, half))),
                listOf(third, half),
                listOf(column, logical, logical),
                List(2) { ExactLpRow() },
                ExactLpObjective(listOf(zero, third, zero), third, third, third),
            )
            val independent = solveAndCertify(fresh)
            assertEquals(independent.exactPrimal, result.exactPrimal)
            assertEquals(independent.lowerBound, result.lowerBound)
        }
    }

    @Test
    fun `local nonbasic logical pops and compacts with bounded retained state and exact optima`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val minusOne = ExactLpNumber.of(-1L)
        val logical = ExactLpColumn(ExactLpBounds(ExactLpSide(zero)))
        val source = ExactLpModel(
            listOf(listOf(ExactLpEntry(0, minusOne))),
            listOf(minusOne),
            listOf(ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(ExactLpNumber.of(10L)))), logical),
            listOf(ExactLpRow()),
            ExactLpObjective(listOf(one, zero)),
        )
        val solver = LpScopedSolver(LpExactState(source))
        var solveWork = 0L
        var solveAttempts = 0
        var solves = 0
        val counters = LpCounterResults()
        solver.use {
            repeat(12) { cycle ->
                val lower = 2L + cycle % 3
                val premises = ExactLpPremises(emptyList(), listOf(101 + cycle))
                assertTrue(solver.push())
                assertTrue(
                    solver.append(
                        LpScopedRow(
                            cycle + 1L,
                            listOf(0 to minusOne),
                            ExactLpNumber.of(-lower),
                            logical,
                            ExactLpRow(false, premises = premises),
                        ),
                        scoped = true,
                    ),
                )
                solveAttempts++
                val local = assertNotNull(solver.solve(counterResults = counters))
                solves++
                solveWork += solver.lastMetrics.workOps
                val point = assertNotNull(local.witness).primal.single()
                assertEquals(BigFraction.ofLong(lower), point)
                assertEquals(point, local.lowerBound)
                assertEquals(LpVerdict.ATTAINED_OPTIMUM, local.verdict)
                assertFalse(assertNotNull(local.float).basis.status[2] == VarStatus.BASIC)
                val support = assertNotNull(local.bound?.support)
                assertEquals(cycle + 1L, support.state.rows.row(support.rows.single().first).id)
                assertEquals(premises, support.rows.single().second.premises)
                val duals = local.float.duals
                assertEquals(-1.0, duals[1])
                assertEquals(0.0, duals[0])
                assertEquals(
                    BigFraction.ZERO,
                    point.negated() + (point - BigFraction.ofLong(lower)) + BigFraction.ofLong(lower),
                )
                val localKey = assertNotNull(LpExactCapture.stateKey(solver.state))

                assertTrue(solver.pop(0))
                assertNull(solver.lastResult)
                assertNull(counters.read(assertNotNull(solver.state.toWorkingModel()), ProductionLpCertificationPolicy))
                solveAttempts++
                val popped = assertNotNull(solver.solve())
                solves++
                solveWork += solver.lastMetrics.workOps
                assertEquals(BigFraction.ONE, popped.lowerBound)
                assertEquals(listOf(BigFraction.ONE), popped.exactPrimal)
                assertEquals(listOf(0), assertNotNull(popped.bound?.support).rows.map { it.first })
                assertEquals(lower.toDouble(), assertNotNull(local.safeLowerBound), 1e-9)
                assertFalse(localKey.contentEquals(assertNotNull(LpExactCapture.stateKey(solver.state))))

                assertTrue(solver.compact())
                solveAttempts++
                val compacted = assertNotNull(solver.solve())
                solves++
                solveWork += solver.lastMetrics.workOps
                assertEquals(BigFraction.ONE, compacted.lowerBound)
                assertEquals(listOf(BigFraction.ONE), compacted.exactPrimal)
                assertEquals(1, solver.metrics.retainedRows)
                assertEquals(1, solver.metrics.activeRows)
                assertEquals(1L, solver.metrics.currentOwners)
                if (cycle == 0) {
                    val fresh = solveAndCertify(source)
                    assertEquals(compacted.lowerBound, fresh.lowerBound)
                    assertEquals(compacted.exactPrimal, fresh.exactPrimal)
                    val freshLocal = ExactLpModel(
                        listOf(listOf(ExactLpEntry(0, minusOne))),
                        listOf(ExactLpNumber.of(-lower)),
                        List(source.numVars) { source.column(it) },
                        listOf(ExactLpRow(false, premises = premises)),
                        source.objective,
                    )
                    val checkedFresh = solveAndCertify(freshLocal)
                    assertEquals(local.lowerBound, checkedFresh.lowerBound)
                    assertEquals(local.exactPrimal, checkedFresh.exactPrimal)
                }
            }
            assertEquals(36, solves)
            assertEquals(solveAttempts, solves)
            assertEquals(0L, solver.metrics.editDeclines)
            assertEquals(0L, solver.metrics.preparationDeclines)
            assertEquals(36L, solver.metrics.preparationAttempts)
            assertEquals(2L, solver.metrics.peakOwners)
            println("scoped-row trace solves=$solves attempts=$solveAttempts work=$solveWork metrics=${solver.metrics}")
        }
        assertEquals(0L, solver.metrics.currentOwners)
        assertEquals(solver.metrics.createdOwners, solver.metrics.closedOwners)
        solver.close()
        assertFalse(solver.push())
        assertNull(solver.solve())
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
            assertNull(strict.witness)
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
    fun `conflicting append retains row guards and immutable direct support after remap recenter and pop`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val guard = ExactLpPremises(emptyList(), listOf(31))
        val lowerPremises = ExactLpPremises(listOf(ExactLpPremise(5, false, zero)))
        val upperPremises = ExactLpPremises(emptyList(), listOf(32))
        val source = ExactLpModel(
            listOf(emptyList()),
            emptyList(),
            listOf(ExactLpColumn(ExactLpBounds())),
            emptyList(),
            ExactLpObjective(listOf(one)),
        )
        LpScopedSolver(LpExactState(source)).use { solver ->
            assertTrue(solver.append(LpScopedRow(2, listOf(0 to one), one, ExactLpColumn(ExactLpBounds())), false))
            assertTrue(
                solver.append(
                    LpScopedRow(
                        3,
                        listOf(0 to one),
                        ExactLpNumber.of(8L),
                        ExactLpColumn(ExactLpBounds(ExactLpSide(zero, premises = lowerPremises), ExactLpSide(zero))),
                        ExactLpRow(false, true, guard),
                    ),
                    false,
                ),
            )
            val appended = assertNotNull(solver.solve())
            assertEquals(LpVerdict.INFEASIBLE, appended.verdict)
            assertTrue(assertNotNull(appended.boundConflict).lower.side.strict)
            assertEquals(listOf(1), assertNotNull(appended.conflictSupport).rows.map { it.first })
            assertTrue(solver.deactivate(2))
            assertTrue(solver.compact())
            assertTrue(solver.push())
            assertTrue(solver.assertBound(1, true, ExactLpSide(ExactLpNumber.of(-1L), premises = upperPremises), 99))
            assertTrue(solver.recenter(listOf(one)))
            val conflict = assertNotNull(solver.solve())
            val support = assertNotNull(conflict.conflictSupport)
            assertEquals(listOf(0), support.rows.map { it.first })
            assertEquals(3L, support.state.rows.row(0).id)
            assertEquals(guard, support.rows.single().second.premises)
            assertEquals(listOf(-3L, 99L), support.sides.map { it.witness })
            assertEquals(lowerPremises, support.sides[0].side.premises)
            assertEquals(upperPremises, support.sides[1].side.premises)
            assertTrue(support.sides[0].side.number.value > support.sides[1].side.number.value)

            assertTrue(solver.pop(0))
            assertTrue(solver.deactivate(3))
            assertTrue(solver.compact())
            assertTrue(solver.recenter(listOf(zero)))

            assertEquals(one, support.state.model.column(0).origin)
            assertEquals(ExactLpNumber.of(7L), support.state.model.rhs(0))
            assertEquals(1, support.state.assertions.size)
            assertEquals(0, solver.state.model.m)
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
                ): PersistentLpSolver {
                    val delegate = RevisedSimplex(model, cancellation, basisSolverFactory = { matrix ->
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
                        }
                    })
                    return object : PersistentLpSolver by delegate {
                        override fun prepareLogicals(token: Cancellation): Basis? =
                            if (fail && failure == "unsupported") null else delegate.prepareLogicals(token)
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
    fun `construction failures and cancellation after an accepted append preserve source ownership`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val source = ExactLpModel(
            listOf(emptyList()),
            emptyList(),
            listOf(ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(one)))),
            emptyList(),
            ExactLpObjective(listOf(one)),
        )
        var failConstruction = false
        var cancelCertificate = false
        var cancelled = false
        val token = Cancellation { cancelled }
        val factory = object : LpEngineFactory by ProductionLpEngineFactory {
            override fun newPersistentSolver(
                model: LpModel,
                cancellation: Cancellation,
                refactorUpdateLimit: Int,
                iterationLimit: Int,
                workLimit: Long,
                trackDegeneracy: Boolean,
            ): PersistentLpSolver {
                if (failConstruction) error("injected factory failure")
                return RevisedSimplex(model, cancellation)
            }
        }
        val policy = object : LpCertificationPolicy by ProductionLpCertificationPolicy {
            override fun accepts(certifier: LpCertifier, successful: Boolean): Boolean {
                if (cancelCertificate) cancelled = true
                return successful
            }
        }
        LpScopedSolver(LpExactState(source), token, LpSolveContext(factory, policy)).use { solver ->
            assertNotNull(solver.solve())
            val before = solver.state
            val row = LpScopedRow(
                2,
                listOf(0 to one),
                zero,
                ExactLpColumn(ExactLpBounds(ExactLpSide(one), ExactLpSide(zero))),
            )
            failConstruction = true
            assertFailsWith<IllegalStateException> { solver.append(row, true) }
            assertSame(before, solver.state)
            assertEquals(1L, solver.metrics.currentOwners)
            failConstruction = false
            assertTrue(solver.append(row, true))
            cancelCertificate = true

            assertNull(solver.solve())

            assertNull(solver.lastResult)
            assertEquals(2L, solver.state.rows.row(0).id)
            assertNotNull(solver.state.conflict)
            cancelled = false
            cancelCertificate = false
            val result = assertNotNull(solver.solve())
            assertEquals(LpVerdict.INFEASIBLE, result.verdict)
            assertNotNull(result.conflictSupport)
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
                ): PersistentLpSolver {
                    val delegate = RevisedSimplex(model, cancellation)
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
            ): PersistentLpSolver {
                val delegate = RevisedSimplex(model, cancellation)
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
