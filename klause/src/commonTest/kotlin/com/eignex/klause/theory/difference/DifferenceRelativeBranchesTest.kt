package com.eignex.klause.theory.difference

import com.eignex.klause.arithmetic.difference.DifferenceEdge
import com.eignex.klause.arithmetic.difference.DifferenceFragment
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.SourceIntId
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.SearchAtomRegistry
import com.eignex.klause.solver.search.SearchBranchProposal
import com.eignex.klause.solver.search.SearchExactIntValue
import com.eignex.klause.solver.search.SearchRunEvent
import com.eignex.klause.solver.search.SearchSession
import com.eignex.klause.util.Bits
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.minus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DifferenceRelativeBranchesTest {
    @Test
    fun `relative splits retain both original coordinate outcomes across retraction`() {
        val open = Bits.full(2)
        val model = Problem(
            numBoolVars = 1,
            intBounds = IntBounds.fromModelBounds(LongArray(2), LongArray(2), open, open),
            factors = arrayOf(
                Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.LE, 1),
                Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.GE, 0),
            ),
        )
        val component = DifferenceSearchComponent(model).withRelativeBranches(Cancellation.Never)
        val brancher = assertIs<SearchBranchProposal>(component)
        val session = SearchSession(listOf(component), atoms = SearchAtomRegistry(1))
        assertIs<ComponentResult.Consistent>(session.initialize())
        val alternatives = assertNotNull(brancher.nextBranch(session))
        assertEquals(2, alternatives.size)
        assertEquals(session.atomLiteral(alternatives[0])?.xor(1), session.atomLiteral(alternatives[1]))

        for ((index, difference) in listOf(0 to "1", 1 to "0")) {
            assertIs<ComponentResult.Consistent>(session.push(alternatives[index]))
            val result = assertIs<SearchRunEvent.Satisfied>(session.openRun(1).next())

            val first = assertNotNull(result.model.valueOf(SearchExactIntValue(SourceIntId(0))))
            val second = assertNotNull(result.model.valueOf(SearchExactIntValue(SourceIntId(1))))
            assertEquals(difference, (first - second).toString())
            session.popTo(0)
            assertEquals(session.atomLiteral(alternatives[0]),
                session.atomLiteral(assertNotNull(brancher.nextBranch(session))[0]))
        }
        assertEquals(false, model.intBounds.hasLower(0))
        assertEquals(false, model.intBounds.hasUpper(1))
    }

    @Test
    fun `registered relative bounds explain original comparison consequences`() {
        val open = Bits.full(2)
        val model = Problem(
            numBoolVars = 1,
            intBounds = IntBounds.fromModelBounds(LongArray(2), LongArray(2), open, open),
            factors = arrayOf(
                Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.LE, 1),
                Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.GE, 0),
                ReifiedLinear(0, intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.LE, 0),
            ),
        )
        val component = DifferenceSearchComponent(model).withRelativeBranches(Cancellation.Never)
        val brancher = assertIs<SearchBranchProposal>(component)
        val session = SearchSession(listOf(component), atoms = SearchAtomRegistry(1))
        assertIs<ComponentResult.Consistent>(session.initialize())
        val branch = assertNotNull(brancher.nextBranch(session))[0]

        assertIs<ComponentResult.Consistent>(session.push(branch))

        assertEquals(false, session.boolValue(0))
        assertEquals(setOf(Lit.make(0, false), assertNotNull(session.atomLiteral(branch)).xor(1)),
            session.reasonFor(0)?.literals?.toSet())
    }

    @Test
    fun `unused registered relative names impose no complementary bound at a leaf`() {
        val open = Bits.full(2)
        val model = Problem(
            numBoolVars = 1,
            intBounds = IntBounds.fromModelBounds(LongArray(2), LongArray(2), open, open),
            factors = arrayOf(
                Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.LE, 3),
                Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.GE, 0),
            ),
        )
        val component = DifferenceSearchComponent(model).withRelativeBranches(Cancellation.Never)
        val brancher = assertIs<SearchBranchProposal>(component)
        val session = SearchSession(listOf(component), atoms = SearchAtomRegistry(1))
        assertIs<ComponentResult.Consistent>(session.initialize())
        val first = assertNotNull(brancher.nextBranch(session))[1]
        assertIs<ComponentResult.Consistent>(session.push(first))
        assertNotNull(brancher.nextBranch(session))
        session.popTo(0)
        assertIs<ComponentResult.Consistent>(session.push(assertNotNull(brancher.nextBranch(session))[0]))

        val result = assertIs<SearchRunEvent.Satisfied>(session.openRun(1).next())

        val firstValue = assertNotNull(result.model.valueOf(SearchExactIntValue(SourceIntId(0))))
        val secondValue = assertNotNull(result.model.valueOf(SearchExactIntValue(SourceIntId(1))))
        assertTrue((firstValue - secondValue).toString() in setOf("2", "3"))
    }

    @Test
    fun `declined relative atom capacity retains an original source witness`() {
        val open = Bits.full(2)
        val model = Problem(
            numBoolVars = 1,
            intBounds = IntBounds.fromModelBounds(LongArray(2), LongArray(2), open, open),
            factors = arrayOf(
                Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.LE, 1),
                Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.GE, 0),
            ),
        )
        val component = DifferenceSearchComponent(model).withRelativeBranches(Cancellation.Never)
        val session = SearchSession(listOf(component), atoms = SearchAtomRegistry(1, maxAtoms = 0))
        assertIs<ComponentResult.Consistent>(session.initialize())

        assertIs<SearchRunEvent.Satisfied>(session.openRun(1).next())
    }

    @Test
    fun `relative preparation declines unsafe source bounds`() {
        val source = DifferenceFragment(listOf(
            DifferenceEdge(0, 1, Long.MAX_VALUE),
            DifferenceEdge(1, 0, 0),
        ))

        assertNull(DifferenceRelativeBranches.create(source, 1, Cancellation.Never))
    }

    @Test
    fun `containing cancellation withholds a relative branch`() {
        var stopped = false
        val source = DifferenceFragment(listOf(DifferenceEdge(0, 1, 1), DifferenceEdge(1, 0, 0)))
        val brancher = assertNotNull(DifferenceRelativeBranches.create(source, 1, Cancellation.Never))
        val session = SearchSession(emptyList(), cancellation = Cancellation { stopped }, atoms = SearchAtomRegistry(1))
        stopped = true

        assertNull(brancher.nextBranch(session))
    }

    @Test
    fun `closing a relative opening releases its branch proposals`() {
        val source = DifferenceFragment(listOf(DifferenceEdge(0, 1, 1), DifferenceEdge(1, 0, 0)))
        val brancher = assertNotNull(DifferenceRelativeBranches.create(source, 1, Cancellation.Never))
        val session = SearchSession(emptyList(), atoms = SearchAtomRegistry(1))
        assertNotNull(brancher.nextBranch(session))

        brancher.close()

        assertNull(brancher.nextBranch(session))
    }
}
