package com.eignex.klause.solver.pipeline

import com.eignex.klause.arithmetic.polynomial.AlgebraicArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.arithmetic.polynomial.RationalPolynomial
import com.eignex.klause.arithmetic.real.ExactRealExpression
import com.eignex.klause.arithmetic.real.ExactRealValue
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedRealLinear
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.SourceIntId
import com.eignex.klause.ir.SourceRealId
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.search.AssembledSearchModel
import com.eignex.klause.solver.search.ComponentCheck
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.SearchComponent
import com.eignex.klause.solver.search.SearchComponentSet
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.SearchExactAlgebraicRealValue
import com.eignex.klause.solver.search.SearchExactIntValue
import com.eignex.klause.solver.search.SearchExactRealExpressionValue
import com.eignex.klause.solver.search.SearchExactRealValue
import com.eignex.klause.solver.search.SearchLearnedDbParams
import com.eignex.klause.solver.search.SearchResult
import com.eignex.klause.solver.search.contribute
import com.eignex.klause.theory.qflra.ExactLiraSearchComponent
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Bits
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import com.eignex.klause.util.parseBigInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PreparedSourceAgreementTest {
    @Test
    fun `peer equality conditions the sole real coordinate owner`() {
        val source = Problem(0, 0, emptyArray(), arrayOf(
            Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0), doubleArrayOf(1.0), LinearOp.GE, 0.0),
            Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0), doubleArrayOf(1.0), LinearOp.EQ, 1.0),
        ), numRealVars = 1, realLower = doubleArrayOf(Double.NEGATIVE_INFINITY),
            realUpper = doubleArrayOf(Double.POSITIVE_INFINITY))
        val registrations = source.factors.indices.map { index ->
            val fragment = source.withFactors(arrayOf(source.factors[index]))
            PreparedSearchRegistration(source, ExecutionComponentDescriptor("linear-$index",
                ComponentResponsibility.COMPLETION, listOf(index), emptyList(),
                if (index == 0) listOf(0) else emptyList())) { opening ->
                listOf(opening.own(ExactLiraSearchComponent(fragment) { assignment, model ->
                    if (index == 0) model.contribute(SearchExactRealValue(SourceRealId(0)), assignment.reals[0])
                }))
            }
        }

        PreparedSearchRegistrations(source, registrations)
            .open(Long.MAX_VALUE, Cancellation.Never, SearchLearnedDbParams(), null, null).use { search ->
                val result = assertIs<SearchResult.Satisfied>(search.session.solve(0))

                assertEquals(BigFraction.ONE, result.model.valueOf(SearchExactRealValue(SourceRealId(0))))
            }
    }

    @Test
    fun `strict peer bounds exclude the owners closure point`() {
        val source = Problem(0, 0, emptyArray(), arrayOf(
            Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0), doubleArrayOf(1.0), LinearOp.GE, 1.0),
            Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0), doubleArrayOf(1.0), LinearOp.GE, 1.0, strict = true),
        ), numRealVars = 1, realLower = doubleArrayOf(1.0), realUpper = doubleArrayOf(2.0))
        val registrations = source.factors.indices.map { index ->
            val fragment = source.withFactors(arrayOf(source.factors[index]))
            PreparedSearchRegistration(source, ExecutionComponentDescriptor("linear-$index",
                ComponentResponsibility.COMPLETION, listOf(index), emptyList(),
                if (index == 0) listOf(0) else emptyList())) { opening ->
                listOf(opening.own(ExactLiraSearchComponent(fragment) { assignment, model ->
                    if (index == 0) model.contribute(SearchExactRealValue(SourceRealId(0)), assignment.reals[0])
                }))
            }
        }
        PreparedSearchRegistrations(source, registrations)
            .open(Long.MAX_VALUE, Cancellation.Never, SearchLearnedDbParams(), null, null).use { search ->
                val result = assertIs<SearchResult.Satisfied>(search.session.solve(0))
                val value = requireNotNull(result.model.valueOf(SearchExactRealValue(SourceRealId(0))))

                assertTrue(value > BigFraction.ONE && value <= BigFraction.ofLong(2))
            }
    }

    @Test
    fun `incompatible shared real source equalities refute the admitted combined scope`() {
        val source = Problem(0, 0, emptyArray(), Array(2) { index ->
            Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0), doubleArrayOf(1.0), LinearOp.EQ, index.toDouble())
        }, numRealVars = 1, realLower = doubleArrayOf(Double.NEGATIVE_INFINITY),
            realUpper = doubleArrayOf(Double.POSITIVE_INFINITY))
        val registrations = source.factors.indices.map { index ->
            val fragment = source.withFactors(arrayOf(source.factors[index]))
            PreparedSearchRegistration(source, ExecutionComponentDescriptor("linear-$index",
                ComponentResponsibility.COMPLETION, listOf(index), emptyList(),
                if (index == 0) listOf(0) else emptyList())) { opening ->
                listOf(opening.own(ExactLiraSearchComponent(fragment) { assignment, model ->
                    if (index == 0) model.contribute(SearchExactRealValue(SourceRealId(0)), assignment.reals[0])
                }))
            }
        }

        PreparedSearchRegistrations(source, registrations)
            .open(Long.MAX_VALUE, Cancellation.Never, SearchLearnedDbParams(), null, null).use { search ->
                val result = search.session.solve(0)

                assertIs<SearchResult.Exhausted>(result)
            }
    }

    @Test
    fun `wide integer completion retains adjacent exact values`() {
        for (negative in listOf(false, true)) {
            val lower = parseBigInt(if (negative) "-9223372036854775810" else "9223372036854775808")
            val expected = lower + BIG_ONE
            val open = Bits(1).also { it.set(0) }
            val source = Problem(0, intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(0), open, open),
                factors = arrayOf(
                    Linear(intArrayOf(0), arrayOf(BIG_ONE), LinearOp.GE, lower),
                    Linear(intArrayOf(0), arrayOf(BIG_ONE), LinearOp.EQ, expected),
                ))
            val registrations = source.factors.indices.map { index ->
                val fragment = source.withFactors(arrayOf(source.factors[index]))
                PreparedSearchRegistration(source, ExecutionComponentDescriptor("linear-$index",
                    ComponentResponsibility.COMPLETION, listOf(index),
                    if (index == 0) listOf(0) else emptyList(), emptyList())) { opening ->
                    listOf(opening.own(ExactLiraSearchComponent(fragment) { assignment, model ->
                        if (index == 0) model.contribute(SearchExactIntValue(SourceIntId(0)), assignment.ints[0])
                    }))
                }
            }

            PreparedSearchRegistrations(source, registrations)
                .open(Long.MAX_VALUE, Cancellation.Never, SearchLearnedDbParams(), null, null).use { search ->
                    val result = assertIs<SearchResult.Satisfied>(search.session.solve(0))

                    assertEquals(expected, result.model.valueOf(SearchExactIntValue(SourceIntId(0))))
                }
        }
    }

    @Test
    fun `retracting a guard removes its shared real condition`() {
        val source = Problem(1, 0, emptyArray(), arrayOf(
            Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0), doubleArrayOf(1.0), LinearOp.GE, 0.0),
            ReifiedRealLinear(0, intArrayOf(), doubleArrayOf(), intArrayOf(0), doubleArrayOf(1.0), LinearOp.GE, 1.0),
        ), numRealVars = 1, realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(2.0))
        val registrations = source.factors.indices.map { index ->
            val fragment = source.withFactors(arrayOf(source.factors[index]))
            PreparedSearchRegistration(source, ExecutionComponentDescriptor("linear-$index",
                ComponentResponsibility.COMPLETION, listOf(index), emptyList(),
                if (index == 0) listOf(0) else emptyList(), if (index == 0) listOf(0) else emptyList())) { opening ->
                listOf(opening.own(ExactLiraSearchComponent(fragment) { assignment, model ->
                    if (index == 0) model.contribute(SearchExactRealValue(SourceRealId(0)), assignment.reals[0])
                }))
            }
        }
        PreparedSearchRegistrations(source, registrations)
            .open(Long.MAX_VALUE, Cancellation.Never, SearchLearnedDbParams(), null, null).use { search ->
                assertIs<ComponentResult.Consistent>(search.session.initialize())
                assertIs<ComponentResult.Consistent>(search.session.push(SearchDecision.Bool(0)))
                assertIs<ComponentCheck.Feasible>(search.session.check())
                val first = search.session.model()

                search.session.popTo(0)
                assertIs<ComponentResult.Consistent>(search.session.push(SearchDecision.Bool(1)))
                assertIs<ComponentCheck.Feasible>(search.session.check())
                val value = requireNotNull(search.session.model().valueOf(SearchExactRealValue(SourceRealId(0))))

                assertTrue(value >= BigFraction.ZERO && value < BigFraction.ONE)
                assertTrue(requireNotNull(first.valueOf(SearchExactRealValue(SourceRealId(0)))) >= BigFraction.ONE)
            }
    }

    @Test
    fun `missing authoritative coordinates remain indeterminate`() {
        val source = Problem(0, 0, emptyArray(), emptyArray(), numRealVars = 1,
            realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(1.0))
        val registrations = List(2) { index ->
            PreparedSearchRegistration(source, ExecutionComponentDescriptor("linear-$index",
                ComponentResponsibility.COMPLETION, emptyList(), emptyList(),
                if (index == 0) listOf(0) else emptyList())) { opening ->
                listOf(opening.own(ExactLiraSearchComponent(source)))
            }
        }
        PreparedSearchRegistrations(source, registrations)
            .open(Long.MAX_VALUE, Cancellation.Never, SearchLearnedDbParams(), null, null).use { search ->
                val result = search.session.solve(0)

                assertIs<SearchResult.Indeterminate>(result)
            }
    }

    @Test
    fun `a peer cannot replace the declared coordinate owner`() {
        val source = Problem(0, 0, emptyArray(), emptyArray(), numRealVars = 1,
            realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(1.0))
        val registrations = List(2) { index ->
            PreparedSearchRegistration(source, ExecutionComponentDescriptor("linear-$index",
                ComponentResponsibility.COMPLETION, emptyList(), emptyList(),
                if (index == 0) listOf(0) else emptyList())) { opening ->
                listOf(opening.own(ExactLiraSearchComponent(source) { assignment, model ->
                    if (index == 1) model.contribute(SearchExactRealValue(SourceRealId(0)), assignment.reals[0])
                }))
            }
        }
        PreparedSearchRegistrations(source, registrations)
            .open(Long.MAX_VALUE, Cancellation.Never, SearchLearnedDbParams(), null, null).use { search ->
                val result = search.session.solve(0)

                assertIs<SearchResult.Indeterminate>(result)
            }
    }

    @Test
    fun `unsupported arithmetic agreement cannot assemble independently feasible fragments`() {
        val source = Problem(0, 0, emptyArray(), emptyArray())
        val registrations = List(2) { index ->
            PreparedSearchRegistration(source, ExecutionComponentDescriptor("unsupported-$index",
                ComponentResponsibility.COMPLETION, emptyList(), emptyList(), emptyList())) {
                listOf(object : SearchComponent {})
            }
        }
        PreparedSearchRegistrations(source, registrations)
            .open(Long.MAX_VALUE, Cancellation.Never, SearchLearnedDbParams(), null, null).use { search ->
                val result = search.session.solve(0)

                assertIs<SearchResult.Indeterminate>(result)
            }
    }

    @Test
    fun `an exhausted check allowance prevents agreement completion`() {
        val source = Problem(0, 0, emptyArray(), emptyArray(), numRealVars = 1,
            realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(1.0))
        val registrations = List(2) { index ->
            PreparedSearchRegistration(source, ExecutionComponentDescriptor("linear-$index",
                ComponentResponsibility.COMPLETION, emptyList(), emptyList(),
                if (index == 0) listOf(0) else emptyList())) { opening ->
                listOf(opening.own(ExactLiraSearchComponent(source) { assignment, model ->
                    if (index == 0) model.contribute(SearchExactRealValue(SourceRealId(0)), assignment.reals[0])
                }))
            }
        }
        PreparedSearchRegistrations(source, registrations)
            .open(0, Cancellation.Never, SearchLearnedDbParams(), null, null).use { search ->
                val result = search.session.solve(0)

                assertIs<SearchResult.Indeterminate>(result)
            }
    }

    @Test
    fun `rational peers decline algebraic and symbolic authoritative coordinates`() {
        val source = Problem(0, 0, emptyArray(), emptyArray(), numRealVars = 1,
            realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(4.0))
        val arithmetic = AlgebraicArithmetic(PolynomialArithmetic())
        val root = arithmetic.roots(RationalPolynomial.of(-2, 0, 1)).last()
        val coordinates: List<Map<Any, Any>> = listOf(
            mapOf(SearchExactAlgebraicRealValue(SourceRealId(0)) to root),
            mapOf(SearchExactRealExpressionValue(SourceRealId(0)) to ExactRealValue(ExactRealExpression.Pi)),
        )
        ExactLiraSearchComponent(source).use { component ->
            val session = SearchComponentSet(listOf(component)).session()
            assertIs<ComponentResult.Consistent>(session.initialize())
            assertIs<ComponentCheck.Feasible>(session.check())

            for (values in coordinates) {
                val result = component.checkSourceCoordinates(AssembledSearchModel(values), session)

                assertIs<ComponentCheck.Indeterminate>(result)
            }
        }
    }

    @Test
    fun `coordinate checks retain cancellation from the containing native meter`() {
        val source = Problem(0, 0, emptyArray(), emptyArray(), numRealVars = 1,
            realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(1.0))
        var armed = false
        var cancelled = false
        val stop = object : Cancellation {
            override fun isCancelled(): Boolean = cancelled
            override fun workMeter(): WorkMeter = WorkMeter { if (armed) cancelled = true }
        }
        ExactLiraSearchComponent(source).use { component ->
            val session = SearchComponentSet(listOf(component)).session(cancellation = stop)
            assertIs<ComponentResult.Consistent>(session.initialize())
            assertIs<ComponentCheck.Feasible>(session.check())
            val point = AssembledSearchModel(mapOf(SearchExactRealValue(SourceRealId(0)) to BigFraction.ZERO))
            armed = true

            val result = component.checkSourceCoordinates(point, session)

            assertTrue(cancelled)
            assertIs<ComponentCheck.Indeterminate>(result)
        }
    }
}
