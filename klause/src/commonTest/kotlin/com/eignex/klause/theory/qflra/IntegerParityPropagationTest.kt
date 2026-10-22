package com.eignex.klause.theory.qflra

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.formats.smtlib.SmtLib
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearForm
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.lattice.BinaryParityWork
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.SearchAtomRegistry
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.SearchSession
import com.eignex.klause.theory.EncodedTheoryFactor
import com.eignex.klause.theory.EncodingAttribution
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.parseBigInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IntegerParityPropagationTest {
    @Test
    fun `an integer prefix conflict should cite both active equality guards`() {
        for (op in listOf(LinearOp.EQ, LinearOp.NE)) {
            val model = Problem(
                3, 3, Array(3) { IntDomain(0, 0) },
                listOf(
                    ReifiedLinear(0, intArrayOf(1, 1, 1), intArrayOf(0, 1, 2), LinearOp.EQ, 0),
                    ReifiedLinear(1, intArrayOf(1, -1, 1), intArrayOf(0, 1, 2), op, 1),
                ),
                openIntLo = BooleanArray(3) { true }, openIntHi = BooleanArray(3) { true },
            )
            val propagation = assertNotNull(
                IntegerParityPropagation.capture(model, BinaryParityWork(Cancellation.Never)),
            )
            val session = SearchSession(emptyList(), atoms = SearchAtomRegistry(3))
            session.initialize()
            session.push(SearchDecision.Bool(Lit.make(0, true)))
            session.push(SearchDecision.Bool(Lit.make(1, op == LinearOp.EQ)))

            val result = propagation.propagate(session::boolValue, session,
                BinaryParityWork(Cancellation.Never, propagation.retainedBytes),
                { error("Unexpected implication") }, {})

            val reason = assertNotNull(assertIs<ComponentResult.Conflict>(result).explanation)
            assertEquals(listOf(Lit.make(0, false), Lit.make(1, op == LinearOp.NE)), reason.literals.sorted())
            assertNull(session.boolValue(2))
        }
    }

    @Test
    fun `an impossible equality body should imply the matching EQ or NE polarity`() {
        for (op in listOf(LinearOp.EQ, LinearOp.NE)) {
            val model = Problem(
                3, 3, Array(3) { IntDomain(0, 0) },
                listOf(
                    ReifiedLinear(0, intArrayOf(1, 1, 1), intArrayOf(0, 1, 2), LinearOp.EQ, 0),
                    ReifiedLinear(1, intArrayOf(1, -1, 1), intArrayOf(0, 1, 2), op, 1),
                ),
                openIntLo = BooleanArray(3) { true }, openIntHi = BooleanArray(3) { true },
            )
            val propagation = assertNotNull(
                IntegerParityPropagation.capture(model, BinaryParityWork(Cancellation.Never)),
            )
            val session = SearchSession(emptyList(), atoms = SearchAtomRegistry(3))
            session.initialize()
            session.push(SearchDecision.Bool(Lit.make(0, true)))
            val received = ArrayList<Int>()

            val result = propagation.propagate(session::boolValue, session,
                BinaryParityWork(Cancellation.Never, propagation.retainedBytes),
                { received += it; ComponentResult.Consistent }, {})

            assertIs<ComponentResult.Consistent>(result)
            assertEquals(op == LinearOp.NE, session.boolValue(1))
            assertEquals(listOf(Lit.make(1, op == LinearOp.NE)), received)
            assertNull(session.boolValue(2))
        }
    }

    @Test
    fun `compatible parity should not establish equality truth or an integer value`() {
        val model = Problem(
            1, 3, Array(3) { IntDomain(0, 0) },
            listOf(
                Linear(intArrayOf(1, 1, 1), intArrayOf(0, 1, 2), LinearOp.EQ, 0),
                ReifiedLinear(0, intArrayOf(1, -1, 1), intArrayOf(0, 1, 2), LinearOp.EQ, 0),
            ),
            openIntLo = BooleanArray(3) { true }, openIntHi = BooleanArray(3) { true },
        )
        val propagation = assertNotNull(IntegerParityPropagation.capture(model, BinaryParityWork(Cancellation.Never)))
        val session = SearchSession(emptyList(), atoms = SearchAtomRegistry(1))
        session.initialize()

        val result = propagation.propagate(session::boolValue, session,
            BinaryParityWork(Cancellation.Never, propagation.retainedBytes), { error("Unexpected implication") }, {})

        assertNull(result)
        assertNull(session.boolValue(0))
        assertNull(session.intLowerBound(0))
        assertNull(session.intUpperBound(0))
    }

    @Test
    fun `retracted equality guards should not survive in a sibling parity basis`() {
        val model = Problem(
            3, 3, Array(3) { IntDomain(0, 0) },
            listOf(
                ReifiedLinear(0, intArrayOf(1, 1, 1), intArrayOf(0, 1, 2), LinearOp.EQ, 0),
                ReifiedLinear(1, intArrayOf(1, -1, 1), intArrayOf(0, 1, 2), LinearOp.EQ, 1),
            ),
            openIntLo = BooleanArray(3) { true }, openIntHi = BooleanArray(3) { true },
        )
        val propagation = assertNotNull(IntegerParityPropagation.capture(model, BinaryParityWork(Cancellation.Never)))
        val session = SearchSession(emptyList(), atoms = SearchAtomRegistry(3))
        session.initialize()
        session.push(SearchDecision.Bool(Lit.make(0, true)))
        assertIs<ComponentResult.Consistent>(propagation.propagate(session::boolValue, session,
            BinaryParityWork(Cancellation.Never, propagation.retainedBytes), { ComponentResult.Consistent }, {}))
        assertEquals(false, session.boolValue(1))
        session.popTo(0)
        session.push(SearchDecision.Bool(Lit.make(0, false)))

        val result = propagation.propagate(session::boolValue, session,
            BinaryParityWork(Cancellation.Never, propagation.retainedBytes), { error("Stale implication") }, {})

        assertNull(result)
        assertNull(session.boolValue(1))
    }

    @Test
    fun `negative and boundary coefficients should retain their exact parity`() {
        val model = Problem(
            1, 3, Array(3) { IntDomain(0, 0) },
            listOf(
                Linear(longArrayOf(Long.MIN_VALUE, -1, Long.MAX_VALUE), intArrayOf(0, 1, 2),
                    LinearOp.EQ, Long.MIN_VALUE),
                ReifiedLinear(0, longArrayOf(2, 1, 1), intArrayOf(0, 1, 2), LinearOp.EQ, 1L),
            ),
            openIntLo = BooleanArray(3) { true }, openIntHi = BooleanArray(3) { true },
        )
        val propagation = assertNotNull(IntegerParityPropagation.capture(model, BinaryParityWork(Cancellation.Never)))
        val session = SearchSession(emptyList(), atoms = SearchAtomRegistry(1))
        session.initialize()

        propagation.propagate(session::boolValue, session,
            BinaryParityWork(Cancellation.Never, propagation.retainedBytes),
            { ComponentResult.Consistent }, {})

        assertEquals(false, session.boolValue(0))
        assertNull(session.intLowerBound(0))
    }

    @Test
    fun `a containing stop should withhold parity deductions`() {
        val model = Problem(
            1, 1, arrayOf(IntDomain(0, 0)),
            listOf(ReifiedLinear(0, intArrayOf(2), intArrayOf(0), LinearOp.EQ, 1)),
            openIntLo = booleanArrayOf(true), openIntHi = booleanArrayOf(true),
        )
        val propagation = assertNotNull(IntegerParityPropagation.capture(model, BinaryParityWork(Cancellation.Never)))
        val session = SearchSession(emptyList(), atoms = SearchAtomRegistry(1))
        session.initialize()
        val work = BinaryParityWork(Cancellation { true }, propagation.retainedBytes)

        val result = propagation.propagate(session::boolValue, session, work, { error("Stopped implication") }, {})

        assertIs<ComponentResult.Indeterminate>(result)
        assertTrue(work.cancelled)
        assertNull(session.boolValue(0))
    }

    @Test
    fun `only an unchanged single native wrapper should authorize parity feedback`() {
        for (kind in 0..2) {
            val native = ReifiedLinear(0, intArrayOf(1, -1, 1), intArrayOf(0, 1, 2), LinearOp.EQ, 1)
            val attribution = EncodingAttribution(0, emptyList(), emptyList(), emptyList())
            val wrapped = EncodedTheoryFactor(native, native.linearForm, attribution)
            val selected = when (kind) {
                0 -> wrapped
                1 -> EncodedTheoryFactor(native, LinearForm.Conjunction(native.linearForm.rows), attribution)
                else -> EncodedTheoryFactor(wrapped, wrapped.linearForm, attribution)
            }
            val model = Problem(
                1, 3, Array(3) { IntDomain(0, 0) },
                listOf(Linear(intArrayOf(1, 1, 1), intArrayOf(0, 1, 2), LinearOp.EQ, 0), selected),
                openIntLo = BooleanArray(3) { true }, openIntHi = BooleanArray(3) { true },
            )
            val propagation = assertNotNull(
                IntegerParityPropagation.capture(model, BinaryParityWork(Cancellation.Never)),
            )
            val session = SearchSession(emptyList(), atoms = SearchAtomRegistry(1))
            session.initialize()

            propagation.propagate(session::boolValue, session,
                BinaryParityWork(Cancellation.Never, propagation.retainedBytes), { ComponentResult.Consistent }, {})

            assertEquals(if (kind == 0) false else null, session.boolValue(0))
        }
    }

    @Test
    fun `a genuine SMT sum equality should expose its impossible source predicate`() {
        val source = SmtLib.parse(
            "(declare-const x Int) (declare-const y Int) (declare-const z Int) (declare-const spare Bool) " +
                "(assert (or (= (+ x y z) 0) spare)) (assert (or (= (+ x (- y) z) 1) spare))",
        )
        val model = source.model
        val equalities = model.factors.filterIsInstance<ReifiedLinear>().filter { it.op == LinearOp.EQ }
        val premise = equalities.first { it.integerConstants?.bound == 0L }
        val target = equalities.first { it.integerConstants?.bound == 1L }
        val propagation = assertNotNull(IntegerParityPropagation.capture(model, BinaryParityWork(Cancellation.Never)))
        val session = SearchSession(emptyList(), atoms = SearchAtomRegistry(model.numBoolVars))
        session.initialize()
        session.push(SearchDecision.Bool(Lit.make(premise.auxBoolVar, true)))

        propagation.propagate(session::boolValue, session,
            BinaryParityWork(Cancellation.Never, propagation.retainedBytes),
            { ComponentResult.Consistent }, {})

        assertEquals(false, session.boolValue(target.auxBoolVar))
    }

    @Test
    fun `wide predicates and real namespaces should receive no parity deduction`() {
        val wide = parseBigInt("18446744073709551616")
        for (reals in 0..1) {
            val model = Problem(
                1, 1, arrayOf(IntDomain(0, 0)),
                listOf(ReifiedLinear(0, intArrayOf(0), arrayOf(wide), LinearOp.EQ, BIG_ONE)),
                openIntLo = booleanArrayOf(true), openIntHi = booleanArrayOf(true),
                numRealVars = reals, realLower = DoubleArray(reals) { Double.NEGATIVE_INFINITY },
                realUpper = DoubleArray(reals) { Double.POSITIVE_INFINITY },
            )
            val propagation = IntegerParityPropagation.capture(model, BinaryParityWork(Cancellation.Never))
            val session = SearchSession(emptyList(), atoms = SearchAtomRegistry(1))
            session.initialize()

            val result = propagation?.propagate(session::boolValue, session,
                BinaryParityWork(Cancellation.Never, propagation.retainedBytes),
                { error("Unsupported implication") }, {})

            assertNull(result)
            assertNull(session.boolValue(0))
        }
    }
}
