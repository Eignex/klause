package com.eignex.klause.theory.qflra

import com.eignex.klause.factor.arithmetic.ComparisonClause
import com.eignex.klause.factor.arithmetic.ExactLinear
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.arithmetic.ReifiedPseudoBoolean
import com.eignex.klause.factor.arithmetic.ReifiedRealLinear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.IntegerConstants
import com.eignex.klause.ir.LinearForm
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.TaggedLinearRow
import com.eignex.klause.ir.Term
import com.eignex.klause.ir.UnitConsts
import com.eignex.klause.ir.linearRows
import com.eignex.klause.lp.engine.LpCertificationObserver
import com.eignex.klause.lp.engine.LpCertifier
import com.eignex.klause.lp.engine.LpCertifierCost
import com.eignex.klause.lp.engine.LpSolveMetrics
import com.eignex.klause.model.PbOp
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.pipeline.SourceProblemRoute
import com.eignex.klause.solver.pipeline.pipelineRoute
import com.eignex.klause.solver.pipeline.search
import com.eignex.klause.solver.search.ComponentCheck
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.SearchAtomRegistry
import com.eignex.klause.solver.search.SearchContext
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.SearchIntValue
import com.eignex.klause.solver.search.SearchModelKey
import com.eignex.klause.solver.search.SearchResult
import com.eignex.klause.solver.search.SearchSession
import com.eignex.klause.solver.search.SearchSolveParams
import com.eignex.klause.solver.search.SourceBoundAtom
import com.eignex.klause.solver.search.SourceBoundTerm
import com.eignex.klause.solver.search.contribute
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.BIG_ZERO
import com.eignex.klause.util.Bits
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.compareTo
import com.eignex.klause.util.parseBigInt
import com.eignex.klause.util.plus
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QfLiraSolverTest {
    @Test
    fun `selected general equations should imply exact comparison guards at either side of a boundary`() {
        for (op in LinearOp.entries) for (strict in if (op == LinearOp.LE || op == LinearOp.GE) {
            listOf(false, true)
        } else listOf(false)) for (bound in -1L..1L) {
            val source = Problem(3, 0, emptyArray(), arrayOf(
                ExactLinear.of(IntArray(3) { Term.ofRealVar(it) }, List(3) { BigFraction.ONE },
                    LinearOp.EQ, BigFraction.ZERO, activator = 0),
                ExactLinear.of(IntArray(3) { Term.ofRealVar(it) }, List(3) { BigFraction.ofLong(2) },
                    op, BigFraction.ofLong(bound), strict = strict, activator = 1),
            ), numRealVars = 3, realLower = DoubleArray(3) { Double.NEGATIVE_INFINITY },
                realUpper = DoubleArray(3) { Double.POSITIVE_INFINITY })
            val expected = when (op) {
                LinearOp.LE -> if (strict) 0L < bound else 0L <= bound
                LinearOp.GE -> if (strict) 0L > bound else 0L >= bound
                LinearOp.EQ -> bound == 0L
                LinearOp.NE -> bound != 0L
            }
            val session = SearchSession(listOf(ExactLiraSearchComponent(source)))
            assertIs<ComponentResult.Consistent>(session.initialize())

            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(0, true))))

            assertEquals(expected, session.boolValue(1), "$op strict=$strict bound=$bound")
            assertEquals(setOf(Lit.make(0, false), Lit.make(1, expected)),
                assertNotNull(session.reasonFor(1)).literals.toSet())
            assertNull(session.boolValue(2))
        }
    }

    @Test
    fun `equation guard consequences should reach Boolean peers and retract with their premises`() {
        val source = Problem(4, 0, emptyArray(), arrayOf(
            ExactLinear.of(IntArray(3) { Term.ofRealVar(it) }, List(3) { BigFraction.ONE },
                LinearOp.EQ, BigFraction.ZERO, activator = 0),
            ExactLinear.of(IntArray(3) { Term.ofRealVar(it) }, List(3) { BigFraction.ofLong(2) },
                LinearOp.LE, BigFraction.ZERO, activator = 1),
            Clause(intArrayOf(Lit.make(1, false), Lit.make(2, true))),
        ), numRealVars = 3, realLower = DoubleArray(3) { Double.NEGATIVE_INFINITY },
            realUpper = DoubleArray(3) { Double.POSITIVE_INFINITY })
        val request = assertIs<SourceProblemRoute.OpenTheory>(source.pipelineRoute()).request
        request.componentPlan.search(request.model, emptyMap()).use { planned ->
            val session = planned.session
            assertIs<ComponentResult.Consistent>(session.initialize())

            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(0, true))))

            assertEquals(true, session.boolValue(1))
            assertEquals(true, session.boolValue(2))
            assertEquals(setOf(Lit.make(0, false), Lit.make(1, true)),
                assertNotNull(session.reasonFor(1)).literals.toSet())
            session.popTo(0)
            assertNull(session.boolValue(1))
            assertNull(session.boolValue(2))
            assertNull(session.reasonFor(1))
            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(0, false))))
            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(1, false))))
            assertIs<SearchResult.Satisfied>(session.solve(4))
        }
    }

    @Test
    fun `shared equation query guards should require every comparison to have the same known truth`() {
        for (kind in listOf("agree", "disagree", "open")) {
            val secondColumns = if (kind == "open") intArrayOf(0, 1, 3) else intArrayOf(0, 1, 2)
            val source = Problem(3, 0, emptyArray(), arrayOf(
                ExactLinear.of(IntArray(3) { Term.ofRealVar(it) }, List(3) { BigFraction.ONE },
                    LinearOp.EQ, BigFraction.ZERO, activator = 0),
                ExactLinear.of(IntArray(3) { Term.ofRealVar(it) }, List(3) { BigFraction.ONE },
                    LinearOp.LE, BigFraction.ZERO, activator = 1),
                ExactLinear.of(IntArray(3) { Term.ofRealVar(secondColumns[it]) }, List(3) { BigFraction.ONE },
                    LinearOp.LE, if (kind == "disagree") BigFraction.MINUS_ONE else BigFraction.ONE, activator = 1),
            ), numRealVars = 4, realLower = DoubleArray(4) { Double.NEGATIVE_INFINITY },
                realUpper = DoubleArray(4) { Double.POSITIVE_INFINITY })
            val session = SearchSession(listOf(ExactLiraSearchComponent(source)))
            assertIs<ComponentResult.Consistent>(session.initialize())

            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(0, true))))

            assertEquals(if (kind == "agree") true else null, session.boolValue(1))
            assertNull(session.boolValue(2))
        }
    }

    @Test
    fun `a compacted general conflict should exclude weaker guards and preserve a source sibling`() {
        val rows = listOf(
            ExactLinear.of(intArrayOf(Term.ofRealVar(0), Term.ofRealVar(1), Term.ofRealVar(3)),
                listOf(BigFraction.ONE, BigFraction.MINUS_ONE, BigFraction.ONE),
                LinearOp.EQ, BigFraction.ZERO, activator = 0),
            ExactLinear.of(IntArray(3) { Term.ofRealVar(it) }, List(3) { BigFraction.ONE },
                LinearOp.LE, BigFraction.ZERO, activator = 1),
            ExactLinear.of(IntArray(3) { Term.ofRealVar(it) }, List(3) { BigFraction.MINUS_ONE },
                LinearOp.LE, BigFraction.MINUS_ONE),
            ExactLinear.of(intArrayOf(Term.ofRealVar(4)), listOf(BigFraction.ONE),
                LinearOp.EQ, BigFraction.ofLong(7), activator = 3),
        ) + List(129) {
            ExactLinear.of(IntArray(3) { Term.ofRealVar(it) }, List(3) { BigFraction.ONE },
                LinearOp.LE, BigFraction.ofLong(10), activator = 2)
        }
        val source = Problem(4, 0, emptyArray(), Array<Factor>(rows.size) { rows[it] }, numRealVars = 5,
            realLower = DoubleArray(5) { if (it == 3) 0.0 else Double.NEGATIVE_INFINITY },
            realUpper = DoubleArray(5) { if (it == 3) 0.0 else Double.POSITIVE_INFINITY })
        var solves = 0
        val component = ExactLiraSearchComponent(source)
        component.observePrimaryLpWith(object : LpCertificationObserver {
            override fun observe(certifier: LpCertifier, success: Boolean, cost: LpCertifierCost) = Unit
            override fun observeExactInput(accepted: Boolean) = Unit
            override fun observeSolve(metrics: LpSolveMetrics, component: Boolean) { solves++ }
        })
        val session = SearchSession(listOf(component))
        assertIs<ComponentResult.Consistent>(session.initialize())
        for (variable in listOf(2, 3, 0)) {
            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(variable, true))))
        }
        solves = 0

        val conflict = assertIs<ComponentResult.Conflict>(session.push(SearchDecision.Bool(Lit.make(1, true))))

        assertEquals(setOf(Lit.make(0, false), Lit.make(1, false)),
            assertNotNull(conflict.explanation).literals.toSet())
        assertEquals(0, solves)
        session.popTo(3)
        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(1, false))))
        assertIs<SearchResult.Satisfied>(session.solve(0))
    }

    @Test
    fun `a necessary real path should propagate through Boolean peers and retract its conditions`() {
        val source = Problem(4, 0, emptyArray(), arrayOf(
            ExactLinear.of(intArrayOf(Term.ofRealVar(0), Term.ofRealVar(1)),
                listOf(BigFraction.ONE, BigFraction.MINUS_ONE), LinearOp.LE, BigFraction.ZERO, activator = 0),
            ExactLinear.of(intArrayOf(Term.ofRealVar(0), Term.ofRealVar(1)),
                listOf(BigFraction.MINUS_ONE, BigFraction.ONE), LinearOp.LE,
                BigFraction.ZERO, strict = true, activator = 1),
            ExactLinear.of(IntArray(3) { Term.ofRealVar(it) }, List(3) { BigFraction.ONE },
                LinearOp.LE, BigFraction.ofLong(100)),
            Clause(intArrayOf(Lit.make(2, false), Lit.make(1, true))),
        ), numRealVars = 3, realLower = DoubleArray(3) { Double.NEGATIVE_INFINITY },
            realUpper = DoubleArray(3) { Double.POSITIVE_INFINITY })
        val request = assertIs<SourceProblemRoute.OpenTheory>(source.pipelineRoute()).request
        request.componentPlan.search(request.model, emptyMap()).use { planned ->
            val session = planned.session
            assertIs<ComponentResult.Consistent>(session.initialize())

            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(0, true))))

            assertEquals(false, session.boolValue(1))
            assertEquals(setOf(Lit.make(0, false), Lit.make(1, false)),
                assertNotNull(session.reasonFor(1)).literals.toSet())
            assertEquals(false, session.boolValue(2))
            assertNull(session.boolValue(3))
            session.popTo(0)
            assertNull(session.boolValue(1))
            assertNull(session.boolValue(2))
            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(1, true))))
            assertEquals(false, session.boolValue(0))
            assertNull(session.boolValue(3))
        }
    }

    @Test
    fun `a residual cycle should exclude unrelated retained guards from its source conflict`() {
        val source = Problem(6, 0, emptyArray(), arrayOf(
            ExactLinear.of(intArrayOf(Term.ofRealVar(0), Term.ofRealVar(1)),
                listOf(BigFraction.ONE, BigFraction.MINUS_ONE), LinearOp.EQ, BigFraction.ZERO, activator = 0),
            ExactLinear.of(intArrayOf(Term.ofRealVar(0), Term.ofRealVar(2)),
                listOf(BigFraction.ONE, BigFraction.MINUS_ONE), LinearOp.LE, BigFraction.ZERO, activator = 1),
            ExactLinear.of(intArrayOf(Term.ofRealVar(1), Term.ofRealVar(2)),
                listOf(BigFraction.MINUS_ONE, BigFraction.ONE), LinearOp.LE, BigFraction.MINUS_ONE, activator = 2),
            ExactLinear.of(intArrayOf(Term.ofRealVar(2), Term.ofRealVar(3)),
                listOf(BigFraction.ONE, BigFraction.MINUS_ONE), LinearOp.LE, BigFraction.ofLong(10), activator = 3),
            ExactLinear.of(intArrayOf(Term.ofRealVar(1), Term.ofRealVar(2), Term.ofRealVar(3)),
                List(3) { BigFraction.ONE }, LinearOp.LE, BigFraction.ofLong(10), activator = 4),
        ), numRealVars = 4, realLower = DoubleArray(4) { Double.NEGATIVE_INFINITY },
            realUpper = DoubleArray(4) { Double.POSITIVE_INFINITY })
        var solves = 0
        val component = ExactLiraSearchComponent(source)
        component.observePrimaryLpWith(object : LpCertificationObserver {
            override fun observe(certifier: LpCertifier, success: Boolean, cost: LpCertifierCost) = Unit
            override fun observeExactInput(accepted: Boolean) = Unit
            override fun observeSolve(metrics: LpSolveMetrics, component: Boolean) { solves++ }
        })
        val session = SearchSession(listOf(component))
        assertIs<ComponentResult.Consistent>(session.initialize())
        for (variable in listOf(3, 4, 0, 1)) {
            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(variable, true))))
        }
        solves = 0

        val reason = assertNotNull(session.reasonFor(2))

        assertIs<ComponentResult.Conflict>(session.push(SearchDecision.Bool(Lit.make(2, true))))

        assertEquals(setOf(Lit.make(0, false), Lit.make(1, false), Lit.make(2, false)),
            reason.literals.toSet())
        assertEquals(0, solves)
        session.popTo(4)
        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(2, false))))
        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(5, false))))
        assertIs<SearchResult.Satisfied>(session.solve(0))
    }

    @Test
    fun `an equality refutation should exclude unrelated guarded equations`() {
        val source = Problem(4, 0, emptyArray(), arrayOf(
            ExactLinear.of(intArrayOf(Term.ofRealVar(0), Term.ofRealVar(1)), List(2) { BigFraction.ONE },
                LinearOp.EQ, BigFraction.ONE, activator = 0),
            ExactLinear.of(intArrayOf(Term.ofRealVar(0)), listOf(BigFraction.ONE),
                LinearOp.EQ, BigFraction.ZERO, activator = 1),
            ExactLinear.of(intArrayOf(Term.ofRealVar(1)), listOf(BigFraction.ONE),
                LinearOp.LE, BigFraction.ONE, strict = true, activator = 2),
            ExactLinear.of(intArrayOf(Term.ofRealVar(2)), listOf(BigFraction.ONE),
                LinearOp.EQ, BigFraction.ZERO, activator = 3),
        ), numRealVars = 3, realLower = DoubleArray(3) { Double.NEGATIVE_INFINITY },
            realUpper = DoubleArray(3) { Double.POSITIVE_INFINITY })
        var solves = 0
        val component = ExactLiraSearchComponent(source)
        component.observePrimaryLpWith(object : LpCertificationObserver {
            override fun observe(certifier: LpCertifier, success: Boolean, cost: LpCertifierCost) = Unit
            override fun observeExactInput(accepted: Boolean) = Unit
            override fun observeSolve(metrics: LpSolveMetrics, component: Boolean) { solves++ }
        })
        val session = SearchSession(listOf(component))
        assertIs<ComponentResult.Consistent>(session.initialize())
        for (variable in listOf(3, 0, 1)) {
            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(variable, true))))
        }
        solves = 0

        val conflict = assertIs<ComponentResult.Conflict>(session.push(SearchDecision.Bool(Lit.make(2, true))))

        assertEquals(setOf(Lit.make(0, false), Lit.make(1, false), Lit.make(2, false)),
            assertNotNull(conflict.explanation).literals.toSet())
        assertEquals(0, solves)
        session.popTo(3)
        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(2, false))))
        assertIs<SearchResult.Satisfied>(session.solve(0))
    }

    @Test
    fun `a reduced general conflict should retain its Boolean premises before the primary solve`() {
        val source = Problem(2, 0, emptyArray(), arrayOf(
            ExactLinear.of(intArrayOf(Term.ofRealVar(0), Term.ofRealVar(1), Term.ofRealVar(3)),
                listOf(BigFraction.ONE, BigFraction.MINUS_ONE, BigFraction.ONE),
                LinearOp.EQ, BigFraction.ZERO, activator = 0),
            ExactLinear.of(intArrayOf(Term.ofRealVar(0), Term.ofRealVar(1), Term.ofRealVar(2)),
                List(3) { BigFraction.ONE }, LinearOp.LE, BigFraction.ZERO, activator = 1),
            ExactLinear.of(intArrayOf(Term.ofRealVar(0), Term.ofRealVar(1), Term.ofRealVar(2)),
                List(3) { BigFraction.MINUS_ONE }, LinearOp.LE, BigFraction.MINUS_ONE),
        ), numRealVars = 4,
            realLower = doubleArrayOf(Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY,
                Double.NEGATIVE_INFINITY, 0.0),
            realUpper = doubleArrayOf(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY,
                Double.POSITIVE_INFINITY, 0.0))
        var solves = 0
        val component = ExactLiraSearchComponent(source)
        component.observePrimaryLpWith(object : LpCertificationObserver {
            override fun observe(certifier: LpCertifier, success: Boolean, cost: LpCertifierCost) = Unit
            override fun observeExactInput(accepted: Boolean) = Unit
            override fun observeSolve(metrics: LpSolveMetrics, component: Boolean) { solves++ }
        })
        val session = SearchSession(listOf(component))
        assertIs<ComponentResult.Consistent>(session.initialize())
        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(0, true))))
        solves = 0

        val conflict = assertIs<ComponentResult.Conflict>(session.push(SearchDecision.Bool(Lit.make(1, true))))

        assertEquals(setOf(Lit.make(0, false), Lit.make(1, false)),
            assertNotNull(conflict.explanation).literals.toSet())
        assertEquals(0, solves)
        session.popTo(1)
        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(1, false))))
        assertIs<SearchResult.Satisfied>(session.solve(0))
    }

    @Test
    fun `pure real conflicts should retain signed guards across sibling retraction`() {
        val source = Problem(4, 0, emptyArray(), arrayOf(
            ExactLinear.of(intArrayOf(Term.ofRealVar(0), Term.ofRealVar(1)),
                List(2) { BigFraction.ONE }, LinearOp.EQ, BigFraction.ONE, activator = 0),
            ExactLinear.of(intArrayOf(Term.ofRealVar(0)), listOf(BigFraction.ONE),
                LinearOp.EQ, BigFraction.ZERO, activator = 1),
            ExactLinear.of(intArrayOf(Term.ofRealVar(1)), listOf(BigFraction.ONE),
                LinearOp.GE, BigFraction.ONE, activator = 2),
            ExactLinear.of(intArrayOf(Term.ofRealVar(2)), listOf(BigFraction.ONE),
                LinearOp.LE, BigFraction.ofLong(4), activator = 3),
        ), numRealVars = 3, realLower = DoubleArray(3) { Double.NEGATIVE_INFINITY },
            realUpper = DoubleArray(3) { Double.POSITIVE_INFINITY })
        val component = ExactLiraSearchComponent(source)
        val session = SearchSession(listOf(component))
        assertIs<ComponentResult.Consistent>(session.initialize())
        for (variable in listOf(3, 0, 1)) {
            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(variable, true))))
        }

        val conflict = assertIs<ComponentResult.Conflict>(session.push(SearchDecision.Bool(Lit.make(2, false))))

        assertEquals(setOf(Lit.make(0, false), Lit.make(1, false), Lit.make(2, true)),
            assertNotNull(conflict.explanation).literals.toSet())
        session.popTo(3)
        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(2, true))))
        assertIs<SearchResult.Satisfied>(session.solve(0))
    }

    @Test
    fun `assigned equations should conflict before unrelated Boolean choices without a numerical solve`() {
        val source = Problem(3, 0, emptyArray(), arrayOf(
            ExactLinear.of(intArrayOf(Term.ofRealVar(0), Term.ofRealVar(1)),
                List(2) { BigFraction.ONE }, LinearOp.EQ, BigFraction.ONE, activator = 0),
            ExactLinear.of(intArrayOf(Term.ofRealVar(0)), listOf(BigFraction.ONE),
                LinearOp.EQ, BigFraction.ZERO, activator = 1),
            ExactLinear.of(intArrayOf(Term.ofRealVar(1)), listOf(BigFraction.ONE),
                LinearOp.LE, BigFraction.ONE, strict = true),
            ExactLinear.of(intArrayOf(Term.ofRealVar(2)), listOf(BigFraction.ONE),
                LinearOp.LE, BigFraction.ofLong(4), activator = 2),
        ), numRealVars = 3, realLower = DoubleArray(3) { Double.NEGATIVE_INFINITY },
            realUpper = DoubleArray(3) { Double.POSITIVE_INFINITY })
        var solves = 0
        val component = ExactLiraSearchComponent(source)
        component.observePrimaryLpWith(object : LpCertificationObserver {
            override fun observe(certifier: LpCertifier, success: Boolean, cost: LpCertifierCost) = Unit
            override fun observeExactInput(accepted: Boolean) = Unit
            override fun observeSolve(metrics: LpSolveMetrics, component: Boolean) { solves++ }
        })
        val session = SearchSession(listOf(component))
        assertIs<ComponentResult.Consistent>(session.initialize())
        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(0, true))))
        solves = 0

        val conflict = assertIs<ComponentResult.Conflict>(session.push(SearchDecision.Bool(Lit.make(1, true))))

        assertEquals(setOf(Lit.make(0, false), Lit.make(1, false)),
            assertNotNull(conflict.explanation).literals.toSet())
        assertNull(session.boolValue(2))
        assertEquals(0, solves)
    }

    @Test
    fun `unassigned Boolean coefficients should remain open during equality refutation`() {
        val declaration = object : Factor by ReifiedRealLinear(0, intArrayOf(), doubleArrayOf(),
            intArrayOf(0), doubleArrayOf(1.0), LinearOp.LE, 0.0) {
            override val linearForm: LinearForm = LinearForm.Conjunction(listOf(
                TaggedLinearRow(intArrayOf(Term.ofRealVar(0), Term.ofLit(Lit.make(0, true))),
                    IntegerConstants(UnitConsts(2), 0L), LinearOp.EQ),
            ))
        }
        val source = Problem(1, 0, emptyArray(), arrayOf(declaration,
            ExactLinear.of(intArrayOf(Term.ofRealVar(0)), listOf(BigFraction.ONE),
                LinearOp.LE, BigFraction.ZERO, strict = true)),
            numRealVars = 1, realLower = doubleArrayOf(Double.NEGATIVE_INFINITY),
            realUpper = doubleArrayOf(Double.POSITIVE_INFINITY))
        val component = ExactLiraSearchComponent(source)
        val session = SearchSession(listOf(component))
        assertIs<ComponentResult.Consistent>(session.initialize())
        assertNull(session.boolValue(0))

        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(0, true))))
        val result = assertIs<SearchResult.Satisfied>(session.solve(0))

        assertEquals(BigFraction.ofLong(-1),
            assertNotNull(result.model.valueOf<ExactLraAssignment>(component)).reals.single())
    }

    @Test
    fun `unselected real alternatives should not hide an independent source contradiction`() {
        val alternatives = listOf(
            ExactLinear.of(intArrayOf(Term.ofRealVar(2)), listOf(BigFraction.ONE),
                LinearOp.NE, BigFraction.ZERO),
            object : Factor by ExactLinear.of(intArrayOf(Term.ofRealVar(2)), listOf(BigFraction.ONE),
                LinearOp.LE, BigFraction.ZERO) {
                override val linearForm: LinearForm = LinearForm.Disjunction(listOf(
                    ExactLinear.of(intArrayOf(Term.ofRealVar(2)), listOf(BigFraction.ONE),
                        LinearOp.LE, BigFraction.ZERO).linearRows.single(),
                    ExactLinear.of(intArrayOf(Term.ofRealVar(2)), listOf(BigFraction.ONE),
                        LinearOp.GE, BigFraction.ONE).linearRows.single(),
                ))
            },
        )
        for (alternative in alternatives) {
            val source = Problem(0, 0, emptyArray(), arrayOf(alternative,
                ExactLinear.of(intArrayOf(Term.ofRealVar(0), Term.ofRealVar(1)),
                    List(2) { BigFraction.ONE }, LinearOp.EQ, BigFraction.ONE),
                ExactLinear.of(intArrayOf(Term.ofRealVar(0)), listOf(BigFraction.ONE),
                    LinearOp.EQ, BigFraction.ZERO),
                ExactLinear.of(intArrayOf(Term.ofRealVar(1)), listOf(BigFraction.ONE),
                    LinearOp.LE, BigFraction.ONE, strict = true)),
                numRealVars = 3, realLower = DoubleArray(3) { Double.NEGATIVE_INFINITY },
                realUpper = DoubleArray(3) { Double.POSITIVE_INFINITY })
            val component = ExactLiraSearchComponent(source)
            val session = SearchSession(listOf(component))

            val conflict = assertIs<ComponentResult.Conflict>(session.initialize())

            assertTrue(assertNotNull(conflict.explanation).literals.isEmpty())
        }
    }

    @Test
    fun `a reduced cycle should conflict before unrelated Boolean choices without a numerical solve`() {
        val source = Problem(3, 0, emptyArray(), arrayOf(
            ExactLinear.of(intArrayOf(Term.ofRealVar(0), Term.ofRealVar(1), Term.ofRealVar(3)),
                listOf(BigFraction.ONE, BigFraction.MINUS_ONE, BigFraction.ONE),
                LinearOp.EQ, BigFraction.ZERO, activator = 0),
            ExactLinear.of(intArrayOf(Term.ofRealVar(0), Term.ofRealVar(2)),
                listOf(BigFraction.ONE, BigFraction.MINUS_ONE),
                LinearOp.LE, BigFraction.ZERO, activator = 1),
            ExactLinear.of(intArrayOf(Term.ofRealVar(1), Term.ofRealVar(2)),
                listOf(BigFraction.MINUS_ONE, BigFraction.ONE), LinearOp.LE, BigFraction.MINUS_ONE),
            ExactLinear.of(intArrayOf(Term.ofRealVar(2)), listOf(BigFraction.ONE),
                LinearOp.LE, BigFraction.ofLong(4), activator = 2),
        ), numRealVars = 4,
            realLower = doubleArrayOf(Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY,
                Double.NEGATIVE_INFINITY, 0.0),
            realUpper = doubleArrayOf(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY,
                Double.POSITIVE_INFINITY, 0.0))
        var solves = 0
        val component = ExactLiraSearchComponent(source)
        component.observePrimaryLpWith(object : LpCertificationObserver {
            override fun observe(certifier: LpCertifier, success: Boolean, cost: LpCertifierCost) = Unit
            override fun observeExactInput(accepted: Boolean) = Unit
            override fun observeSolve(metrics: LpSolveMetrics, component: Boolean) { solves++ }
        })
        val session = SearchSession(listOf(component))
        assertIs<ComponentResult.Consistent>(session.initialize())
        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(0, true))))
        solves = 0

        val conflict = assertIs<ComponentResult.Conflict>(session.push(SearchDecision.Bool(Lit.make(1, true))))

        assertEquals(setOf(Lit.make(0, false), Lit.make(1, false)),
            assertNotNull(conflict.explanation).literals.toSet())
        assertNull(session.boolValue(2))
        assertEquals(0, solves)
    }

    @Test
    fun `a complete equality contradiction should avoid a numerical solve`() {
        val factors = listOf(ExactLinear.of(intArrayOf(Term.ofRealVar(0), Term.ofRealVar(1)),
            List(2) { BigFraction.ONE }, LinearOp.EQ, BigFraction.ZERO)) + (1 until 130).map { variable ->
            ExactLinear.of(intArrayOf(Term.ofRealVar(variable)), listOf(BigFraction.ONE),
                LinearOp.EQ, BigFraction.ZERO)
        } + ExactLinear.of(intArrayOf(Term.ofRealVar(0)), listOf(BigFraction.ONE),
            LinearOp.LE, BigFraction.ZERO, strict = true)
        val source = Problem(0, 0, emptyArray(), factors.toTypedArray(), numRealVars = 130,
            realLower = DoubleArray(130) { Double.NEGATIVE_INFINITY },
            realUpper = DoubleArray(130) { Double.POSITIVE_INFINITY })
        var solves = 0
        val component = ExactLiraSearchComponent(source)
        component.observePrimaryLpWith(object : LpCertificationObserver {
            override fun observe(certifier: LpCertifier, success: Boolean, cost: LpCertifierCost) = Unit
            override fun observeExactInput(accepted: Boolean) = Unit
            override fun observeSolve(metrics: LpSolveMetrics, component: Boolean) { solves++ }
        })
        val session = SearchSession(listOf(component))

        val result = assertIs<ComponentResult.Conflict>(session.initialize())

        assertTrue(assertNotNull(result.explanation).literals.isEmpty())
        assertEquals(0, solves)
    }

    @Test
    fun `whole point proposals should be invalidated by source scope changes`() {
        val source = Problem(1, 0, emptyArray(), arrayOf(
            ExactLinear.of(intArrayOf(Term.ofRealVar(0)), listOf(BigFraction.ONE), LinearOp.LE,
                BigFraction.ZERO, activator = 0),
        ), numRealVars = 1, realLower = doubleArrayOf(-1.0), realUpper = doubleArrayOf(1.0))
        val component = assertNotNull(prepareExactLiraSource(source, Cancellation.Never)).open()
        component.bindWholePointSource(source, source)
        var invalidations = 0
        assertTrue(component.useWholeSourcePoints(source, { _, _ -> null }, { _, _ -> false }, { invalidations++ }))
        val session = SearchSession(listOf(component))
        assertIs<ComponentResult.Consistent>(session.initialize())
        val initial = invalidations

        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(0, true))))
        assertTrue(invalidations > initial)
        val asserted = invalidations
        session.popTo(0)
        assertTrue(invalidations > asserted)
        val retracted = invalidations
        component.onRestart(session)
        assertTrue(invalidations > retracted)
        val restarted = invalidations
        component.close()
        assertTrue(invalidations > restarted)
    }

    @Test
    fun `a necessary difference conflict should avoid the primary solve and preserve its sibling`() {
        val source = Problem(3, 0, emptyArray(), arrayOf(
            ExactLinear.of(intArrayOf(Term.ofRealVar(0), Term.ofRealVar(1)),
                listOf(BigFraction.ONE, BigFraction.MINUS_ONE), LinearOp.LE, BigFraction.ZERO, activator = 0),
            ExactLinear.of(intArrayOf(Term.ofRealVar(0), Term.ofRealVar(1)),
                listOf(BigFraction.MINUS_ONE, BigFraction.ONE), LinearOp.LE, BigFraction.MINUS_ONE, activator = 1),
            ExactLinear.of(IntArray(3) { Term.ofRealVar(it) }, List(3) { BigFraction.ONE },
                LinearOp.LE, BigFraction.ofLong(100)),
        ), numRealVars = 3, realLower = DoubleArray(3) { Double.NEGATIVE_INFINITY },
            realUpper = DoubleArray(3) { Double.POSITIVE_INFINITY })
        var solves = 0
        val component = ExactLiraSearchComponent(source)
        component.observePrimaryLpWith(object : LpCertificationObserver {
            override fun observe(certifier: LpCertifier, success: Boolean, cost: LpCertifierCost) = Unit
            override fun observeExactInput(accepted: Boolean) = Unit
            override fun observeSolve(metrics: LpSolveMetrics, component: Boolean) { solves++ }
        })
        val session = SearchSession(listOf(component))
        assertIs<ComponentResult.Consistent>(session.initialize())
        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(2, true))))
        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(0, true))))
        solves = 0

        val reason = assertNotNull(session.reasonFor(1))

        assertIs<ComponentResult.Conflict>(session.push(SearchDecision.Bool(Lit.make(1, true))))

        assertEquals(setOf(Lit.make(0, false), Lit.make(1, false)),
            reason.literals.toSet())
        assertEquals(0, solves)
        session.popTo(2)
        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(1, false))))
        assertIs<SearchResult.Satisfied>(session.solve(0))
    }

    @Test
    fun `wide Boolean source rows should retain both reification directions in exact arithmetic`() {
        val wide = parseBigInt("18446744073709551616")
        val factor = ReifiedPseudoBoolean(0, arrayOf(wide), intArrayOf(Lit.make(1, false)), PbOp.EQ, wide)
        val real = ReifiedRealLinear(0, intArrayOf(), doubleArrayOf(), intArrayOf(0), doubleArrayOf(1.0),
            LinearOp.LE, 0.0)
        val problem = Problem(2, 0, emptyArray(), arrayOf(factor, real),
            numRealVars = 1, realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(1.0))
        for (indicator in listOf(false, true)) {
            val session = SearchSession(listOf(ExactLiraSearchComponent(problem)))
            assertIs<ComponentResult.Consistent>(session.initialize())
            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(0, indicator))))

            assertIs<ComponentResult.Conflict>(session.push(SearchDecision.Bool(Lit.make(1, indicator))))
        }
    }

    @Test
    fun `exact mixed witnesses should own their constructor and exported coordinates`() {
        val wide = parseBigInt("9223372036854775808")
        val third = BigFraction.ofLong(3).reciprocal()
        val bools = booleanArrayOf(true)
        val ints = arrayOf(wide)
        val reals = mutableListOf(third)
        val assignment = ExactLiraAssignment(bools, ints, reals)
        val copied = assignment.copy()

        bools[0] = false
        ints[0] = BIG_ZERO
        reals[0] = BigFraction.ZERO
        assignment.bools[0] = false
        assignment.ints[0] = BIG_ZERO
        copied.component1()[0] = false
        copied.component2()[0] = BIG_ZERO

        assertEquals(ExactLiraAssignment(booleanArrayOf(true), arrayOf(wide), listOf(third)), assignment)
        assertEquals(assignment, copied)
    }

    @Test
    fun `a clause-only Boolean decision should not spend an arithmetic check`() {
        val model = Problem(
            numBoolVars = 3,
            intBounds = openBounds(2),
            factors = arrayOf(
                Clause(intArrayOf(Lit.make(0, true))),
                ReifiedLinear(2, intArrayOf(2, 3), intArrayOf(0, 1), LinearOp.LE, 1),
            ),
        )
        var checks = 0
        val context = object : SearchContext by SearchSession(emptyList()) {
            override fun consumeCheck(): Boolean {
                checks++
                return true
            }
        }
        val component = ExactLiraSearchComponent(model)
        component.initialize(context)

        component.assert(SearchDecision.Bool(Lit.make(0, true)), context)
        component.propagate(context)

        assertEquals(0, checks)
    }

    @Test
    fun `a Boolean term in an arithmetic row should trigger partial propagation`() {
        val source = ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.LE, 0)
        val factor = object : Factor by source {
            override val linearForm: LinearForm = LinearForm.Conjunction(
                listOf(
                    TaggedLinearRow(
                        intArrayOf(Term.ofLit(Lit.make(0, true)), Term.ofIntVar(0)),
                        IntegerConstants(UnitConsts(2), 0L),
                        LinearOp.LE,
                    ),
                ),
            )
        }
        val model = Problem(
            numBoolVars = 2,
            intBounds = openBounds(),
            factors = arrayOf(factor, Linear(intArrayOf(1), intArrayOf(0), LinearOp.EQ, 0)),
        )
        val session = SearchSession(listOf(ExactLiraSearchComponent(model)))
        session.initialize()

        assertIs<ComponentResult.Conflict>(session.push(SearchDecision.Bool(Lit.make(0, true))))
    }

    @Test
    fun `decided reified row should conflict with unconditional rows and explain assigned literals`() {
        val component = ExactLiraSearchComponent(partialModel())
        val session = SearchSession(listOf(component))

        assertIs<ComponentResult.Consistent>(session.initialize())
        val conflict = assertIs<ComponentResult.Conflict>(
            session.push(SearchDecision.Bool(Lit.make(0, positive = true))),
        )

        assertContentEquals(intArrayOf(Lit.make(0, positive = false)), conflict.explanation?.literals)
        assertNull(session.model().valueOf<ExactLiraAssignment>(component))
    }

    @Test
    fun `ordinary source contradiction should need no disjunction name capacity`() {
        val model = Problem(
            numBoolVars = 0,
            intBounds = openBounds(),
            factors = arrayOf(
                ComparisonClause(intArrayOf(0, 0), arrayOf(LinearOp.NE, LinearOp.NE), longArrayOf(0, 1)),
                Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 0),
                Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 1),
            ),
        )
        ExactLiraSearchComponent(model).use { component ->
            val session = SearchSession(listOf(component), atoms = SearchAtomRegistry(0, maxAtoms = 0))

            session.initialize()

            assertIs<SearchResult.Exhausted>(session.solve(0))
        }
    }

    @Test
    fun `unnamed source disjunction should withhold a relaxation witness`() {
        val model = Problem(
            numBoolVars = 0,
            intBounds = openBounds(),
            factors = arrayOf(
                ComparisonClause(intArrayOf(0, 0), arrayOf(LinearOp.EQ, LinearOp.EQ), longArrayOf(1, 2)),
            ),
        )
        ExactLiraSearchComponent(model).use { component ->
            val session = SearchSession(listOf(component), atoms = SearchAtomRegistry(0, maxAtoms = 0))
            assertIs<ComponentResult.Consistent>(session.initialize())

            val result = session.solve(0)

            assertIs<SearchResult.Indeterminate>(result)
        }
    }

    @Test
    fun `existing source predicate should complete a deferred disjunction witness`() {
        val model = Problem(
            numBoolVars = 0,
            intBounds = openBounds(),
            factors = arrayOf(
                ComparisonClause(intArrayOf(0, 0), arrayOf(LinearOp.LE, LinearOp.GE), longArrayOf(0, 2)),
                Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 0),
                Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 2),
            ),
        )
        ExactLiraSearchComponent(model).use { component ->
            val session = SearchSession(listOf(component), atoms = SearchAtomRegistry(0, maxAtoms = 2))
            assertIs<ComponentResult.Consistent>(session.initialize())
            val split = assertNotNull(
                SourceBoundAtom.integerSplit(
                    session,
                    listOf(SourceBoundTerm(SearchIntValue(0), BigFraction.ONE)),
                    BigFraction.ZERO,
                ),
            )
            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Theory(split.positive)))

            val result = session.solve(0)

            val witness = assertNotNull(
                assertIs<SearchResult.Satisfied>(result).model
                    .valueOf<ExactLiraAssignment>(component),
            )
            assertEquals(BIG_ZERO, witness.intValue(0))
        }
    }

    @Test
    fun `a strict real disjunction should be decided through its atoms`() {
        for ((upper, sat) in listOf(0.5 to true, 1.0 to false)) {
            val below = strictRealBound(LinearOp.LE, 0.0)
            val above = strictRealBound(LinearOp.GE, upper)
            val clause = object : Factor by below {
                override val linearForm: LinearForm = LinearForm.Disjunction(below.linearRows + above.linearRows)
            }
            val model = Problem(
                numBoolVars = 0,
                intBounds = openBounds(0),
                numRealVars = 1,
                realLower = doubleArrayOf(0.0),
                realUpper = doubleArrayOf(1.0),
                factors = arrayOf(clause),
            )
            ExactLiraSearchComponent(model).use { component ->
                val session = SearchSession(listOf(component), atoms = SearchAtomRegistry(0))
                assertIs<ComponentResult.Consistent>(session.initialize())

                val result = session.solve(0)

                if (sat) assertIs<SearchResult.Satisfied>(result) else assertIs<SearchResult.Exhausted>(result)
            }
        }
    }

    @Test
    fun `feasible partial check should not contribute a witness`() {
        val model = Problem(
            numBoolVars = 2,
            intBounds = openBounds(),
            factors = arrayOf(
                Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 0),
                ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.LE, 10),
            ),
        )
        val component = ExactLiraSearchComponent(model)
        val session = SearchSession(listOf(component))

        assertIs<ComponentResult.Consistent>(session.initialize())
        assertIs<ComponentResult.Consistent>(
            session.push(SearchDecision.Bool(Lit.make(0, positive = true))),
        )

        assertIs<ComponentCheck.Indeterminate>(session.check())
        assertNull(session.model().valueOf<ExactLiraAssignment>(component))
    }

    @Test
    fun `partial check should observe cancellation`() {
        val session = SearchSession(
            components = listOf(ExactLiraSearchComponent(partialModel())),
            cancellation = Cancellation { true },
        )

        assertIs<ComponentResult.Indeterminate>(session.initialize())
    }

    @Test
    fun `strict affine ray should be refuted without exhausting the split budget`() {
        val model = Problem(
            numBoolVars = 3,
            intBounds = openBounds(2),
            factors = arrayOf(
                ReifiedLinear(0, intArrayOf(2, -2), intArrayOf(0, 1), LinearOp.EQ, 1),
                ReifiedRealLinear(
                    aux = 1,
                    vars = intArrayOf(),
                    intCoeffs = doubleArrayOf(),
                    realVars = intArrayOf(0),
                    realCoeffs = doubleArrayOf(1.0),
                    op = LinearOp.GE,
                    bound = 0.0,
                    strict = true,
                ),
            ),
            numRealVars = 1,
            realLower = doubleArrayOf(Double.NEGATIVE_INFINITY),
            realUpper = doubleArrayOf(Double.POSITIVE_INFINITY),
        )
        val session = SearchSession(listOf(ExactLiraSearchComponent(model)), maxChecks = 6)

        assertIs<ComponentResult.Consistent>(session.initialize())
        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(1, positive = true))))

        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(0, true))))
        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(2, true))))
        assertIs<SearchResult.Exhausted>(
            session.solve(3, SearchSolveParams(maxDecisions = 0)),
        )
    }

    @Test
    fun `stopped exact source preparation should withhold the factory and retain its charge`() {
        var charged = 0L
        val meter = WorkMeter { charged += it }
        val stop = object : Cancellation {
            override fun isCancelled(): Boolean = charged >= 20L
            override fun workMeter(): WorkMeter = meter
        }

        assertNull(prepareExactLiraSource(partialModel(), stop))
        val stoppedCharge = charged
        assertTrue(stoppedCharge >= 20L)
        assertNull(prepareExactLiraSource(partialModel(), stop))
        assertEquals(stoppedCharge, charged)
    }

    @Test
    fun `prepared source should open fresh disjunction names after cancelled initialization`() {
        val source = comparisonClauseModel(xUpper = 8)
        var preparationExpired = false
        val prepared = assertNotNull(prepareExactLiraSource(source, Cancellation { preparationExpired }))
        preparationExpired = true
        prepared.open().use { interrupted ->
            val session = SearchSession(
                listOf(interrupted),
                cancellation = Cancellation { true },
                atoms = SearchAtomRegistry(0),
            )
            assertIs<ComponentResult.Indeterminate>(session.initialize())
        }

        prepared.open().use { fresh ->
            val session = SearchSession(listOf(fresh), atoms = SearchAtomRegistry(0))

            assertIs<ComponentResult.Consistent>(session.initialize())
            assertIs<SearchResult.Satisfied>(session.solve(0))
        }
    }

    @Test
    fun `prepared source should open an undecided sibling after a conflicting Boolean prefix`() {
        val source = partialModel()
        val prepared = assertNotNull(prepareExactLiraSource(source, Cancellation.Never))
        prepared.open().use { first ->
            val session = SearchSession(listOf(first))
            assertIs<ComponentResult.Consistent>(session.initialize())
            assertIs<ComponentResult.Conflict>(session.push(SearchDecision.Bool(Lit.make(0, true))))
        }

        prepared.open().use { sibling ->
            val session = SearchSession(listOf(sibling))

            assertIs<ComponentResult.Consistent>(session.initialize())
            assertNull(session.boolValue(0))
            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(0, false))))
        }
    }

    @Test
    fun `prepared openings should contribute only the current source model callback`() {
        val source = comparisonClauseModel(xUpper = 8)
        val prepared = assertNotNull(prepareExactLiraSource(source, Cancellation.Never))
        val keys = List(2) { SearchModelKey(ExactLiraAssignment::class) }
        for (index in keys.indices) {
            prepared.open { value, model -> model.contribute(keys[index], value) }.use { component ->
                val session = SearchSession(listOf(component), atoms = SearchAtomRegistry(0))

                assertIs<ComponentResult.Consistent>(session.initialize())
                val model = assertIs<SearchResult.Satisfied>(session.solve(0)).model
                val witness = assertNotNull(model.valueOf(keys[index]))
                assertEquals(BIG_ONE, witness.intValue(0) + witness.intValue(1))
                assertTrue(witness.intValue(0) <= bigIntOf(8))
                assertNull(model.valueOf(keys[1 - index]))
            }
        }
    }

    @Test
    fun `prepared real source should preserve strict bounds on fresh openings`() {
        val source = Problem(
            numBoolVars = 0,
            intBounds = openBounds(0),
            numRealVars = 1,
            realLower = doubleArrayOf(Double.NEGATIVE_INFINITY),
            realUpper = doubleArrayOf(1.0),
            factors = arrayOf(
                Linear(
                    intVars = intArrayOf(),
                    intCoeffs = doubleArrayOf(),
                    realVars = intArrayOf(0),
                    realCoeffs = doubleArrayOf(1.0),
                    op = LinearOp.GE,
                    bound = 0.0,
                    strict = true,
                ),
            ),
        )
        val prepared = assertNotNull(prepareExactLiraSource(source, Cancellation.Never))
        for (key in List(2) { SearchModelKey(ExactLiraAssignment::class) }) {
            prepared.open { value, model -> model.contribute(key, value) }.use { component ->
                val session = SearchSession(listOf(component))

                assertIs<ComponentResult.Consistent>(session.initialize())
                val model = assertIs<SearchResult.Satisfied>(session.solve(0)).model
                val value = assertNotNull(model.valueOf(key)).reals.single()
                assertTrue(value > BigFraction.ZERO)
                assertTrue(value <= BigFraction.ONE)
            }
        }
    }

    @Test
    fun `sibling source scopes should discard derived disequality directions`() {
        val model = Problem(
            numBoolVars = 1,
            intBounds = openBounds(),
            factors = arrayOf(
                Linear(intArrayOf(1), intArrayOf(0), LinearOp.NE, 0),
                Linear(intArrayOf(1), intArrayOf(0), LinearOp.NE, 1),
                Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, -1),
                Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 2),
                ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.GE, 2),
            ),
        )
        ExactLiraSearchComponent(model).use { component ->
            val session = SearchSession(listOf(component), atoms = SearchAtomRegistry(1))
            assertIs<ComponentResult.Consistent>(session.initialize())
            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(0, true))))
            assertIs<SearchResult.Satisfied>(session.solve(1))
            session.popTo(0)
            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(0, false))))

            val result = session.solve(1)

            val witness = assertNotNull(
                assertIs<SearchResult.Satisfied>(result).model
                    .valueOf<ExactLiraAssignment>(component),
            )
            assertEquals(bigIntOf(-1), witness.intValue(0))
        }
    }

    @Test
    fun `a retracted strict Boolean row should admit its closed sibling`() {
        val source = Problem(
            numBoolVars = 1,
            intBounds = openBounds(0),
            numRealVars = 2,
            realLower = doubleArrayOf(0.0, 0.0),
            realUpper = doubleArrayOf(0.0, 0.0),
            factors = arrayOf(
                ReifiedRealLinear(0, intArrayOf(), doubleArrayOf(), intArrayOf(0, 1),
                    doubleArrayOf(1.0, 2.0), LinearOp.LE, 0.0, strict = true),
            ),
        )
        ExactLiraSearchComponent(source).use { component ->
            val session = SearchSession(listOf(component))
            assertIs<ComponentResult.Consistent>(session.initialize())
            assertIs<ComponentResult.Conflict>(session.push(SearchDecision.Bool(Lit.make(0, true))))

            session.popTo(0)
            assertIs<ComponentResult.Consistent>(session.propagate())
            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(0, false))))

            val result = assertIs<SearchResult.Satisfied>(session.solve(1))
            val witness = assertNotNull(result.model.valueOf<ExactLraAssignment>(component))
            assertEquals(listOf(BigFraction.ZERO, BigFraction.ZERO), witness.reals)
            assertEquals(false, witness.bools[0])
        }
    }

    @Test
    fun `a retracted Boolean row should preserve the integer leaf`() {
        val source = Problem(
            numBoolVars = 1,
            intBounds = openBounds(),
            factors = arrayOf(
                Linear(intArrayOf(1), intArrayOf(0), LinearOp.EQ, 1),
                ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.LE, 0),
            ),
        )
        ExactLiraSearchComponent(source).use { component ->
            val session = SearchSession(listOf(component))
            assertIs<ComponentResult.Consistent>(session.initialize())
            assertIs<ComponentResult.Conflict>(session.push(SearchDecision.Bool(Lit.make(0, true))))

            session.popTo(0)
            assertIs<ComponentResult.Consistent>(session.propagate())
            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(0, false))))

            val result = assertIs<SearchResult.Satisfied>(session.solve(1))
            val witness = assertNotNull(result.model.valueOf<ExactLiraAssignment>(component))
            assertEquals(BIG_ONE, witness.intValue(0))
            assertEquals(false, witness.bools[0])
        }
    }

    @Test
    fun `a private operation stop should withhold a Boolean leaf until a live operation`() {
        var privateStopped = false
        val component = ExactLiraSearchComponent(partialModel())
        component.useOperationAllowance { parent -> parent or Cancellation { privateStopped } }
        component.use { owned ->
            val session = SearchSession(listOf(owned))
            assertIs<ComponentResult.Consistent>(session.initialize())
            privateStopped = true

            assertIs<ComponentResult.Indeterminate>(session.push(SearchDecision.Bool(Lit.make(0, false))))
            assertNull(session.model().valueOf<ExactLiraAssignment>(owned))
            session.popTo(0)
            privateStopped = false
            assertIs<ComponentResult.Consistent>(session.propagate())

            assertIs<SearchResult.Satisfied>(session.solve(2))
        }
    }

    @Test
    fun `a containing stop should prevent Boolean leaf publication`() {
        var stopped = false
        ExactLiraSearchComponent(partialModel()).use { component ->
            val session = SearchSession(listOf(component), cancellation = Cancellation { stopped })
            assertIs<ComponentResult.Consistent>(session.initialize())
            stopped = true

            assertIs<ComponentResult.Indeterminate>(session.push(SearchDecision.Bool(Lit.make(0, false))))

            assertNull(session.model().valueOf<ExactLiraAssignment>(component))
        }
    }

    private fun comparisonClauseModel(xUpper: Long) = Problem(
        numBoolVars = 0,
        intBounds = openBounds(2),
        factors = arrayOf(
            ComparisonClause(intArrayOf(0, 1), arrayOf(LinearOp.GE, LinearOp.LE), longArrayOf(5, -3)),
            Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.EQ, 1),
            Linear(longArrayOf(1), intArrayOf(0), LinearOp.LE, xUpper),
        ),
    )

    private fun partialModel(): Problem = Problem(
        numBoolVars = 2,
        intBounds = openBounds(),
        factors = arrayOf(
            Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 1),
            ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.LE, 0),
        ),
    )

    private fun strictRealBound(op: LinearOp, bound: Double) =
        Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0), doubleArrayOf(1.0), op, bound, strict = true)

    private fun openBounds(size: Int = 1): IntBounds {
        val open = Bits(size).also { bits -> repeat(size, bits::set) }
        return IntBounds.fromModelBounds(LongArray(size), LongArray(size), open, open)
    }
}
