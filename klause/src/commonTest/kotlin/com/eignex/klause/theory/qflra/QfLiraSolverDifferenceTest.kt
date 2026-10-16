package com.eignex.klause.theory.qflra

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.arithmetic.ReifiedRealLinear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.LinearForm
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.RealConstants
import com.eignex.klause.ir.RealConsts
import com.eignex.klause.ir.TaggedLinearRow
import com.eignex.klause.ir.Term
import com.eignex.klause.lp.engine.LpCertificationPolicy
import com.eignex.klause.lp.engine.LpCertifier
import com.eignex.klause.lp.engine.LpSolveContext
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.result.SmtStatsSink
import com.eignex.klause.solver.search.ClauseSearchComponent
import com.eignex.klause.solver.search.ComponentCheck
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.SearchAtomRegistry
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.SearchResult
import com.eignex.klause.solver.search.SearchSession
import com.eignex.klause.util.Bits
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.bigIntOf
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QfLiraSolverDifferenceTest {
    @Test
    fun `integer subset conflicts should publish their guards and retract for a sibling`() {
        val model = Problem(
            numBoolVars = 1,
            intBounds = openBounds(),
            factors = arrayOf(
                ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.LE, 0),
                Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 1),
            ),
        )
        val stats = SmtStatsSink()
        ExactLiraSearchComponent(model).use { component ->
            component.observeWith(stats)
            component.observeCompleteTraversal()
            val session = SearchSession(listOf(component), atoms = SearchAtomRegistry(1))
            assertIs<ComponentResult.Consistent>(session.initialize())

            val conflict = assertIs<ComponentResult.Conflict>(session.push(SearchDecision.Bool(Lit.make(0, true))))

            assertContentEquals(intArrayOf(Lit.make(0, false)), assertNotNull(conflict.explanation).literals)
            assertEquals(1L, stats.snapshot().completeSearchStages?.get("INTEGER_DIFFERENCE_PUBLISHED_CONFLICTS"))
            session.popTo(0)
            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(0, false))))
            val result = assertIs<SearchResult.Satisfied>(session.solve(1))
            val assignment = assertNotNull(result.model.valueOf<ExactLiraAssignment>(component))
            assertTrue(assignment.intValue(0) >= bigIntOf(1))
        }
    }

    @Test
    fun `integer subset conflicts should retain custom certification policy`() {
        val model = Problem(
            numBoolVars = 1,
            intBounds = openBounds(3),
            factors = arrayOf(
                Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.LE, 0),
                Linear(intArrayOf(1, -1), intArrayOf(1, 2), LinearOp.LE, 0),
                ReifiedLinear(0, intArrayOf(1, -1), intArrayOf(2, 0), LinearOp.LE, -1),
            ),
        )
        ExactLiraSearchComponent(model).use { component ->
            component.solveWith(LpSolveContext(certificationPolicy = LpCertificationPolicy { _, _ -> false }))
            val session = SearchSession(listOf(component))
            assertIs<ComponentResult.Consistent>(session.initialize())

            val result = session.push(SearchDecision.Bool(Lit.make(0, true)))

            assertIs<ComponentResult.Consistent>(result)
            assertIs<SearchResult.Indeterminate>(session.solve(1))
        }
    }

    @Test
    fun `partial difference checks and complete source witnesses should need no LP owner`() {
        val model = Problem(
            numBoolVars = 2,
            intBounds = openBounds(0),
            numRealVars = 1,
            realLower = doubleArrayOf(Double.NEGATIVE_INFINITY),
            realUpper = doubleArrayOf(Double.POSITIVE_INFINITY),
            factors = arrayOf(
                Linear(
                    intArrayOf(),
                    doubleArrayOf(),
                    intArrayOf(0),
                    doubleArrayOf(1.0),
                    LinearOp.GE,
                    -2.0,
                ),
                ReifiedRealLinear(
                    0,
                    intArrayOf(),
                    doubleArrayOf(),
                    intArrayOf(0),
                    doubleArrayOf(1.0),
                    LinearOp.LE,
                    0.0,
                ),
            ),
        )
        ExactLiraSearchComponent(model).use { component ->
            val session = SearchSession(listOf(component), atoms = SearchAtomRegistry(2))
            assertIs<ComponentResult.Consistent>(session.initialize())
            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(0, true))))
            assertIs<ComponentCheck.Indeterminate>(component.check(session))

            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(1, false))))

            assertIs<ComponentCheck.Feasible>(component.check(session))
            val witness = assertNotNull(session.model().valueOf<ExactLraAssignment>(component))
            assertTrue(witness.reals.single() >= BigFraction.ofLong(-2L))
            assertTrue(witness.reals.single() <= BigFraction.ZERO)
            assertNull(component.lpMetrics)
        }
    }

    @Test
    fun `fractional difference completion should check source rows without an LP owner`() {
        val model = Problem(
            numBoolVars = 2,
            intBounds = openBounds(0),
            numRealVars = 1,
            realLower = doubleArrayOf(Double.NEGATIVE_INFINITY),
            realUpper = doubleArrayOf(Double.POSITIVE_INFINITY),
            factors = arrayOf(
                Linear(
                    intArrayOf(),
                    doubleArrayOf(),
                    intArrayOf(0),
                    doubleArrayOf(3.0),
                    LinearOp.GE,
                    1.0,
                ),
                ReifiedRealLinear(
                    0,
                    intArrayOf(),
                    doubleArrayOf(),
                    intArrayOf(0),
                    doubleArrayOf(2.0),
                    LinearOp.LE,
                    1.0,
                ),
            ),
        )
        ExactLiraSearchComponent(model).use { component ->
            val session = SearchSession(listOf(component), atoms = SearchAtomRegistry(2))
            assertIs<ComponentResult.Consistent>(session.initialize())
            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(0, true))))
            assertIs<ComponentCheck.Indeterminate>(component.check(session))

            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(1, false))))

            assertIs<ComponentCheck.Feasible>(component.check(session))
            val witness = assertNotNull(session.model().valueOf<ExactLraAssignment>(component))
            assertTrue(witness.reals.single() >= BigFraction.of(bigIntOf(1), bigIntOf(3)))
            assertTrue(witness.reals.single() <= BigFraction.of(bigIntOf(1), bigIntOf(2)))
            assertNull(component.lpMetrics)
        }
    }

    @Test
    fun `late source validation fallback should drop guarded rows in its sibling scope`() {
        val model = Problem(
            numBoolVars = 2,
            intBounds = openBounds(0),
            numRealVars = 1,
            realLower = doubleArrayOf(Double.NEGATIVE_INFINITY),
            realUpper = doubleArrayOf(Double.POSITIVE_INFINITY),
            factors = arrayOf(
                ReifiedRealLinear(
                    0,
                    intArrayOf(),
                    doubleArrayOf(),
                    intArrayOf(0),
                    doubleArrayOf(1.0),
                    LinearOp.LE,
                    0.0,
                ),
                Clause(intArrayOf(Lit.make(1, true))),
            ),
        )
        ExactLiraSearchComponent(model).use { component ->
            val session = SearchSession(listOf(component), atoms = SearchAtomRegistry(2))
            assertIs<ComponentResult.Consistent>(session.initialize())
            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(0, true))))
            assertIs<ComponentResult.Conflict>(session.push(SearchDecision.Bool(Lit.make(1, false))))
            session.popTo(0)

            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(0, false))))
            val siblingConflict = assertIs<ComponentResult.Conflict>(
                session.push(SearchDecision.Bool(Lit.make(1, false))),
            )
            assertContentEquals(intArrayOf(Lit.make(1, true)), assertNotNull(siblingConflict.explanation).literals)
            session.popTo(1)
            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(1, true))))

            assertIs<ComponentCheck.Feasible>(component.check(session))
            val witness = assertNotNull(session.model().valueOf<ExactLraAssignment>(component))
            assertTrue(witness.reals.single() > BigFraction.ZERO)
            assertEquals(true, witness.bools[1])
        }
    }

    @Test
    fun `interrupted difference preparation should leave the component indeterminate`() {
        val model = Problem(
            numBoolVars = 1,
            intBounds = openBounds(0),
            numRealVars = 1,
            realLower = doubleArrayOf(0.0),
            realUpper = doubleArrayOf(1.0),
            factors = arrayOf(),
        )
        ExactLiraSearchComponent(model).use { component ->
            component.useOperationAllowance { Cancellation { true } }
            val session = SearchSession(listOf(component), atoms = SearchAtomRegistry(1))

            assertIs<ComponentResult.Indeterminate>(session.initialize())

            assertIs<ComponentCheck.Indeterminate>(component.check(session))
            assertNull(session.model().valueOf<ExactLraAssignment>(component))
            assertNull(component.lpMetrics)
        }
    }

    @Test
    fun `guarded rows should support conflicts after deeper scope rollback`() {
        val model = Problem(
            numBoolVars = 4,
            intBounds = openBounds(0),
            numRealVars = 2,
            realLower = doubleArrayOf(Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY),
            realUpper = doubleArrayOf(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY),
            factors = arrayOf(
                ReifiedRealLinear(
                    0,
                    intArrayOf(),
                    doubleArrayOf(),
                    intArrayOf(0),
                    doubleArrayOf(1.0),
                    LinearOp.LE,
                    0.0,
                ),
                ReifiedRealLinear(
                    1,
                    intArrayOf(),
                    doubleArrayOf(),
                    intArrayOf(1),
                    doubleArrayOf(1.0),
                    LinearOp.LE,
                    0.0,
                ),
                ReifiedRealLinear(
                    2,
                    intArrayOf(),
                    doubleArrayOf(),
                    intArrayOf(0, 1),
                    doubleArrayOf(1.0, 1.0),
                    LinearOp.GE,
                    1.0,
                ),
            ),
        )
        ExactLiraSearchComponent(model).use { component ->
            val session = SearchSession(listOf(component), atoms = SearchAtomRegistry(4))
            assertIs<ComponentResult.Consistent>(session.initialize())
            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(0, true))))
            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(1, true))))
            val conflict = assertIs<ComponentResult.Conflict>(session.push(SearchDecision.Bool(Lit.make(2, true))))
            assertEquals(setOf(1, 3, 5), assertNotNull(conflict.explanation).literals.toSet())
            session.popTo(1)
            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(1, false))))
            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(2, true))))

            val result = assertIs<SearchResult.Satisfied>(session.solve(4))

            val witness = assertNotNull(result.model.valueOf<ExactLraAssignment>(component))
            assertTrue(witness.reals[0] <= BigFraction.ZERO)
            assertTrue(witness.reals[1] > BigFraction.ZERO)
            assertTrue(witness.reals[0] + witness.reals[1] >= BigFraction.ONE)
        }
    }

    @Test
    fun `a guarded Boolean coefficient should be resolved again in its sibling scope`() {
        val declaration = object : Factor by ReifiedRealLinear(
            0,
            intArrayOf(),
            doubleArrayOf(),
            intArrayOf(0),
            doubleArrayOf(1.0),
            LinearOp.LE,
            0.0,
        ) {
            override val linearForm: LinearForm = LinearForm.Conjunction(
                listOf(
                    TaggedLinearRow(
                        intArrayOf(Term.ofLit(Lit.make(1, true)), Term.ofRealVar(0)),
                        RealConstants(RealConsts(doubleArrayOf(1.0)), RealConsts(doubleArrayOf(1.0)), 0.0, false),
                        LinearOp.LE,
                        activator = 0,
                    ),
                ),
            )
        }
        val model = Problem(
            numBoolVars = 4,
            intBounds = openBounds(0),
            numRealVars = 1,
            realLower = doubleArrayOf(Double.NEGATIVE_INFINITY),
            realUpper = doubleArrayOf(Double.POSITIVE_INFINITY),
            factors = arrayOf(
                declaration,
                ReifiedRealLinear(
                    2,
                    intArrayOf(),
                    doubleArrayOf(),
                    intArrayOf(0),
                    doubleArrayOf(1.0),
                    LinearOp.GE,
                    1.0,
                ),
            ),
        )
        ExactLiraSearchComponent(model).use { component ->
            val session = SearchSession(listOf(component), atoms = SearchAtomRegistry(4))
            assertIs<ComponentResult.Consistent>(session.initialize())
            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(0, true))))
            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(1, false))))
            assertIs<ComponentResult.Conflict>(session.push(SearchDecision.Bool(Lit.make(2, true))))
            session.popTo(1)
            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(1, true))))
            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(2, false))))

            val result = assertIs<SearchResult.Satisfied>(session.solve(4))

            val witness = assertNotNull(result.model.valueOf<ExactLraAssignment>(component))
            assertEquals(true, witness.bools[1])
            assertTrue(witness.reals.single() <= BigFraction.ONE.negated())
        }
    }

    @Test
    fun `a guarded strict witness should satisfy its source interval`() {
        val model = Problem(
            numBoolVars = 1,
            intBounds = openBounds(0),
            numRealVars = 1,
            realLower = doubleArrayOf(0.0),
            realUpper = doubleArrayOf(1.0),
            factors = arrayOf(
                ReifiedRealLinear(
                    0,
                    intArrayOf(),
                    doubleArrayOf(),
                    intArrayOf(0),
                    doubleArrayOf(1.0),
                    LinearOp.LE,
                    0.0,
                ),
                ReifiedRealLinear(
                    0,
                    intArrayOf(),
                    doubleArrayOf(),
                    intArrayOf(0),
                    doubleArrayOf(1.0),
                    LinearOp.GE,
                    1.0,
                ),
            ),
        )
        for (atRoot in listOf(false, true)) {
            ExactLiraSearchComponent(model).use { component ->
                val decision = SearchDecision.Bool(Lit.make(0, false))
                val clauses = if (atRoot) {
                    listOf(ClauseSearchComponent(listOf(Clause(intArrayOf(decision.literal)))))
                } else {
                    emptyList()
                }
                val session = SearchSession(clauses + component, atoms = SearchAtomRegistry(1))
                assertIs<ComponentResult.Consistent>(session.initialize())
                if (atRoot) {
                    assertEquals(0, session.decisionLevel)
                } else {
                    assertIs<ComponentResult.Consistent>(session.push(decision))
                }

                val result = assertIs<SearchResult.Satisfied>(session.solve(1))

                val witness = assertNotNull(result.model.valueOf<ExactLraAssignment>(component))
                assertEquals(false, witness.bools.single())
                assertTrue(witness.reals.single() > BigFraction.ZERO)
                assertTrue(witness.reals.single() < BigFraction.ONE)
            }
        }
    }

    @Test
    fun `guarded real conflicts should survive a feasible sibling`() {
        val model = Problem(
            numBoolVars = 2,
            intBounds = openBounds(0),
            numRealVars = 1,
            realLower = doubleArrayOf(0.0),
            realUpper = doubleArrayOf(1.0),
            factors = arrayOf(
                ReifiedRealLinear(
                    0,
                    intArrayOf(),
                    doubleArrayOf(),
                    intArrayOf(0),
                    doubleArrayOf(1.0),
                    LinearOp.LE,
                    0.0,
                ),
                ReifiedRealLinear(
                    1,
                    intArrayOf(),
                    doubleArrayOf(),
                    intArrayOf(0),
                    doubleArrayOf(1.0),
                    LinearOp.GE,
                    1.0,
                ),
            ),
        )
        ExactLiraSearchComponent(model).use { component ->
            val session = SearchSession(listOf(component), atoms = SearchAtomRegistry(2))
            assertIs<ComponentResult.Consistent>(session.initialize())
            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(0, true))))
            assertIs<ComponentResult.Conflict>(session.push(SearchDecision.Bool(Lit.make(1, true))))
            session.popTo(0)
            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(0, false))))
            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(1, false))))
            assertIs<ComponentCheck.Feasible>(component.check(session))
            session.popTo(0)
            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(0, true))))

            val result = session.push(SearchDecision.Bool(Lit.make(1, true)))

            assertIs<ComponentResult.Conflict>(result)
        }
    }

    @Test
    fun `strict difference conflicts should retain false guard premises across siblings`() {
        val model = Problem(
            numBoolVars = 3,
            intBounds = openBounds(0),
            numRealVars = 1,
            realLower = doubleArrayOf(Double.NEGATIVE_INFINITY),
            realUpper = doubleArrayOf(Double.POSITIVE_INFINITY),
            factors = arrayOf(
                ReifiedRealLinear(
                    0,
                    intArrayOf(),
                    doubleArrayOf(),
                    intArrayOf(0),
                    doubleArrayOf(1.0),
                    LinearOp.LE,
                    0.0,
                ),
                ReifiedRealLinear(
                    1,
                    intArrayOf(),
                    doubleArrayOf(),
                    intArrayOf(0),
                    doubleArrayOf(1.0),
                    LinearOp.LE,
                    0.0,
                ),
            ),
        )
        ExactLiraSearchComponent(model).use { component ->
            val session = SearchSession(listOf(component))
            assertIs<ComponentResult.Consistent>(session.initialize())
            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(0, false))))

            val conflict = assertIs<ComponentResult.Conflict>(session.push(SearchDecision.Bool(Lit.make(1, true))))

            assertContentEquals(intArrayOf(Lit.make(0, true), Lit.make(1, false)), conflict.explanation?.literals)
            session.popTo(1)
            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(1, false))))
            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(2, true))))
            assertIs<ComponentCheck.Feasible>(component.check(session))
        }
    }

    @Test
    fun `difference learning should retain a root guard and assert its feasible sibling`() {
        val model = Problem(
            numBoolVars = 2,
            intBounds = openBounds(0),
            numRealVars = 1,
            realLower = doubleArrayOf(Double.NEGATIVE_INFINITY),
            realUpper = doubleArrayOf(Double.POSITIVE_INFINITY),
            factors = arrayOf(
                ReifiedRealLinear(
                    0,
                    intArrayOf(),
                    doubleArrayOf(),
                    intArrayOf(0),
                    doubleArrayOf(1.0),
                    LinearOp.LE,
                    0.0,
                ),
                ReifiedRealLinear(
                    1,
                    intArrayOf(),
                    doubleArrayOf(),
                    intArrayOf(0),
                    doubleArrayOf(1.0),
                    LinearOp.LE,
                    0.0,
                ),
            ),
        )
        ExactLiraSearchComponent(model).use { component ->
            val rootClause = ClauseSearchComponent(listOf(Clause(intArrayOf(Lit.make(0, false)))))
            val session = SearchSession(listOf(rootClause, component))
            assertIs<ComponentResult.Consistent>(session.initialize())
            assertEquals(false, session.boolValue(0))
            val conflict = assertIs<ComponentResult.Conflict>(session.push(SearchDecision.Bool(Lit.make(1, true))))
            assertContentEquals(intArrayOf(Lit.make(0, true), Lit.make(1, false)), conflict.explanation?.literals)

            val result = assertIs<SearchResult.Satisfied>(session.solve(2))

            val witness = assertNotNull(result.model.valueOf<ExactLraAssignment>(component))
            assertEquals(false, witness.bools[0])
            assertEquals(false, witness.bools[1])
            assertTrue(witness.reals.single() > BigFraction.ZERO)
            assertEquals(1L, session.learnedClauseStats().assertingConflicts)
        }
    }

    @Test
    fun `unconditional strict difference contradiction should have an empty root explanation`() {
        val model = Problem(
            numBoolVars = 0,
            intBounds = openBounds(0),
            numRealVars = 1,
            realLower = doubleArrayOf(0.0),
            realUpper = doubleArrayOf(Double.POSITIVE_INFINITY),
            factors = arrayOf(
                Linear(
                    intArrayOf(),
                    doubleArrayOf(),
                    intArrayOf(0),
                    doubleArrayOf(1.0),
                    LinearOp.LE,
                    0.0,
                    true,
                ),
            ),
        )
        ExactLiraSearchComponent(model).use { component ->
            val session = SearchSession(listOf(component))

            val conflict = assertIs<ComponentResult.Conflict>(session.initialize())

            assertContentEquals(intArrayOf(), conflict.explanation?.literals)
            assertIs<ComponentCheck.Infeasible>(component.check(session))
        }
    }

    @Test
    fun `overridden certification should be able to decline negative difference cycles at root and below`() {
        for (atRoot in listOf(false, true)) {
            val model = Problem(
                numBoolVars = if (atRoot) 0 else 1,
                intBounds = openBounds(0),
                numRealVars = 3,
                realLower = DoubleArray(3) { Double.NEGATIVE_INFINITY },
                realUpper = DoubleArray(3) { Double.POSITIVE_INFINITY },
                factors = arrayOf(
                    Linear(
                        intArrayOf(),
                        doubleArrayOf(),
                        intArrayOf(0, 1),
                        doubleArrayOf(1.0, -1.0),
                        LinearOp.LE,
                        0.0,
                    ),
                    Linear(
                        intArrayOf(),
                        doubleArrayOf(),
                        intArrayOf(1, 2),
                        doubleArrayOf(1.0, -1.0),
                        LinearOp.LE,
                        0.0,
                    ),
                    if (atRoot) {
                        Linear(
                            intArrayOf(),
                            doubleArrayOf(),
                            intArrayOf(2, 0),
                            doubleArrayOf(1.0, -1.0),
                            LinearOp.LE,
                            -1.0,
                        )
                    } else {
                        ReifiedRealLinear(
                            0,
                            intArrayOf(),
                            doubleArrayOf(),
                            intArrayOf(2, 0),
                            doubleArrayOf(1.0, -1.0),
                            LinearOp.LE,
                            -1.0,
                        )
                    },
                ),
            )
            ExactLiraSearchComponent(model).use { component ->
                component.solveWith(
                    LpSolveContext(
                        certificationPolicy = LpCertificationPolicy { certifier, success ->
                            success && certifier != LpCertifier.EXACT_FARKAS && certifier != LpCertifier.RATIONAL
                        },
                    ),
                )
                val session = SearchSession(listOf(component))

                val result = if (atRoot) {
                    session.initialize()
                } else {
                    assertIs<ComponentResult.Consistent>(session.initialize())
                    session.push(SearchDecision.Bool(Lit.make(0, true)))
                }

                assertIs<ComponentResult.Consistent>(result)
                assertIs<SearchResult.Indeterminate>(session.solve(model.numBoolVars))
            }
        }
    }

    private fun openBounds(size: Int = 1): IntBounds {
        val open = Bits(size).also { bits -> repeat(size, bits::set) }
        return IntBounds.fromModelBounds(LongArray(size), LongArray(size), open, open)
    }
}
