package com.eignex.klause.propagation

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.table.Table
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.search.ComponentCheck
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.SearchAtomPremise
import com.eignex.klause.solver.search.SearchAtomRegistry
import com.eignex.klause.solver.search.SearchComponent
import com.eignex.klause.solver.search.SearchConflictResolution
import com.eignex.klause.solver.search.SearchContext
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.SearchIntValue
import com.eignex.klause.solver.search.SearchIntegerBound
import com.eignex.klause.solver.search.SearchLearnedConflictResult
import com.eignex.klause.solver.search.SearchResult
import com.eignex.klause.solver.search.SearchRunEvent
import com.eignex.klause.solver.search.SearchSession
import com.eignex.klause.solver.search.SourceBoundAtom
import com.eignex.klause.solver.search.SourceBoundTerm
import com.eignex.klause.solver.search.explainAtoms
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CpSearchComponentTest {
    @Test
    fun `known shared bounds should avoid reconstructing native proofs`() {
        for (lower in listOf(false, true)) for (stronger in listOf(false, true)) {
            val mapping = intArrayOf(4, 7, 9, 12, 14, 17, 20, 23)
            val native = PropagationSession(Problem(0, 8, Array(8) { IntDomain(0, 10) },
                Array(7) { Linear(intArrayOf(1, -1), intArrayOf(it, it + 1), LinearOp.LE, 0) }))
            val cp = CpSearchComponent(native, mapping)
            var observed: PropagationResult.Implied? = null
            cp.propagationObserver = {
                if (it.intMinKeys.isNotEmpty() || it.intMaxKeys.isNotEmpty()) observed = it
            }
            val shared = SearchSession(listOf(cp), atoms = SearchAtomRegistry(0))
            assertIs<ComponentResult.Consistent>(shared.initialize())
            observed = null
            assertIs<ComponentResult.Consistent>(shared.push(if (lower)
                SearchDecision.IntAtLeast(mapping.first(), 3) else SearchDecision.IntAtMost(mapping.last(), 7)))
            val result = assertNotNull(observed)
            if (stronger) {
                val bound = if (lower) SearchDecision.IntAtLeast(mapping[1], 4)
                    else SearchDecision.IntAtMost(mapping[6], 6)
                assertIs<ComponentResult.Consistent>(shared.publish(bound, SearchAtomPremise.Unavailable))
            }
            val budget = result.boolKeys.size + result.intKeys.size +
                result.intMinKeys.size + result.intMaxKeys.size
            val before = native.work

            val publication = cp.publish(result, shared)

            assertIs<ComponentResult.Consistent>(publication)
            assertTrue(native.work - before <= budget, "work=${native.work - before} budget=$budget")
            assertIs<ComponentResult.Consistent>(shared.propagate())
        }
    }

    @Test
    fun `known shared equalities should avoid reconstructing native proofs`() {
        val native = PropagationSession(Problem(1, 8, Array(8) { IntDomain(0, 10) },
            List(7) { Linear(intArrayOf(1, -1), intArrayOf(it, it + 1), LinearOp.EQ, 0) } +
                ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.EQ, 3)))
        val cp = CpSearchComponent(native)
        var observed: PropagationResult.Implied? = null
        cp.propagationObserver = { if (it.intKeys.isNotEmpty()) observed = it }
        val shared = SearchSession(listOf(cp), atoms = SearchAtomRegistry(1))
        assertIs<ComponentResult.Consistent>(shared.initialize())
        observed = null
        assertIs<ComponentResult.Consistent>(shared.push(SearchDecision.Bool(Lit.make(0, true))))
        val result = assertNotNull(observed)
        val budget = result.boolKeys.size + result.intKeys.size + result.intMinKeys.size + result.intMaxKeys.size
        val before = native.work

        val publication = cp.publish(result, shared)

        assertIs<ComponentResult.Consistent>(publication)
        assertTrue(native.work - before <= budget, "work=${native.work - before} budget=$budget")
    }

    @Test
    fun `an externally owned publication should not become a native Boolean decision leaf`() {
        val cp = CpSearchComponent(PropagationSession(Problem(1, 1, arrayOf(IntDomain(0, 10)), emptyArray())))
        val peer = object : SearchComponent {
            override fun initialize(context: SearchContext): ComponentResult = context.publish(Lit.make(0, true))
        }
        val shared = SearchSession(listOf(cp, peer))
        assertIs<ComponentResult.Consistent>(shared.initialize())

        val available = shared.supportsNativeBooleanAnalysis(0, cp, 1)

        assertEquals(false, available)
        assertEquals(true, cp.session.boolValue(0))
    }

    @Test
    fun `a rejected publication should stop before later facts`() {
        for (mode in listOf("initial", "prepared", "fixpoint")) {
            for (rejection in listOf(ComponentResult.Indeterminate, ComponentResult.Conflict())) {
                val native = PropagationSession(
                    Problem(3, 0, emptyArray(), Array(3) { Clause(intArrayOf(Lit.make(it, true))) }),
                )
                val cp = CpSearchComponent(native)
                var publications = 0
                val context = object : SearchContext by SearchSession(emptyList()) {
                    override fun publish(literal: Int): ComponentResult {
                        publications++
                        return rejection
                    }
                    override fun publish(decision: SearchDecision, premise: SearchAtomPremise): ComponentResult {
                        publications++
                        return rejection
                    }
                }
                val observed = when (mode) {
                    "initial" -> cp.initialize(context)
                    "prepared" -> cp.prepareInitialization(context).asSequence().filterNotNull().first()
                    else -> cp.publish(PropagationResult.Implied(
                        intArrayOf(0, 1, 2), booleanArrayOf(true, true, true), intArrayOf(), longArrayOf(),
                    ), context)
                }

                assertSame(rejection, observed)
                assertEquals(1, publications)
            }
        }
    }

    @Test
    fun `a cancelled decision atom cascade should retract before its retry`() {
        val native = PropagationSession(Problem(0, 1, arrayOf(IntDomain(0, 255)), emptyArray()))
        val state = native.explanationState
        val thresholds = IntArray(128) { state.atomVarGe(0, it.toLong() + 1) }
        var armed = false
        val cp = CpSearchComponent(native)
        val shared = SearchSession(listOf(cp), cancellation = Cancellation {
            armed && state.atoms.truth[state.atomIdOf(thresholds[15])] == 1
        })
        assertIs<ComponentResult.Consistent>(shared.initialize())
        armed = true

        assertIs<ComponentResult.Indeterminate>(shared.push(SearchDecision.IntAtLeast(0, 129)))
        assertNotNull(state.pendingFiniteMutation)
        shared.popTo(0)
        armed = false
        val retried = shared.push(SearchDecision.IntAtLeast(0, 129))

        assertNull(state.pendingFiniteMutation)
        assertIs<ComponentResult.Consistent>(retried)
        assertEquals(129L, native.intDomain(0).min)
    }

    @Test
    fun `a shared assertion allowance should interrupt native filtering and allow a retry after retraction`() {
        val native = PropagationSession(
            Problem(
                0, 2, Array(2) { IntDomain(0, 255) },
                arrayOf(Table(intArrayOf(0, 1), LongArray(512) { (it / 2).toLong() })),
            ),
        )
        val cp = CpSearchComponent(native)
        var armed = false
        var start = 0L
        val shared = SearchSession(listOf(cp), cancellation = Cancellation { armed && native.work - start >= 128L })
        assertIs<ComponentResult.Consistent>(shared.initialize())
        start = native.work
        armed = true

        val interrupted = shared.push(SearchDecision.IntEqual(0, 17))

        assertIs<ComponentResult.Indeterminate>(interrupted)
        assertEquals(true, native.fixpointCancelled)
        assertIs<ComponentCheck.Indeterminate>(cp.check(shared))
        shared.popTo(0)
        assertEquals(false, native.fixpointCancelled)
        armed = false
        assertIs<ComponentResult.Consistent>(shared.push(SearchDecision.IntEqual(0, 17)))
        assertEquals(17L, native.intDomain(1).min)
        assertEquals(17L, native.intDomain(1).max)
    }

    @Test
    fun `cancelled publication should stop visiting later deductions`() {
        for (initializing in listOf(false, true)) {
            var cancelled = false
            val native = PropagationSession(
                Problem(512, 0, emptyArray(), Array(512) { Clause(intArrayOf(Lit.make(it, true))) }),
                cancellation = Cancellation { cancelled },
            )
            val cp = CpSearchComponent(native)
            var publications = 0
            val context = object : SearchContext by SearchSession(emptyList()) {
                override fun publish(literal: Int): ComponentResult {
                    publications++
                    return ComponentResult.Consistent
                }
                override fun publish(decision: SearchDecision, premise: SearchAtomPremise): ComponentResult {
                    publications++
                    return ComponentResult.Consistent
                }
            }
            val result = PropagationResult.Implied(
                IntArray(512) { it }, BooleanArray(512) { true }, intArrayOf(), longArrayOf(),
            )
            val before = native.work
            cancelled = true

            val publication = if (initializing) cp.initialize(context) else cp.publish(result, context)

            assertIs<ComponentResult.Indeterminate>(publication)
            assertEquals(63, publications)
            assertEquals(63L, native.work - before)
            assertEquals(true, native.fixpointCancelled)
        }
    }

    @Test
    fun `shared native bound chains should preserve source coordinates and their Boolean premise`() {
        val mapping = intArrayOf(4, 7, 9, 12, 14)
        val native = PropagationSession(
            Problem(
                1,
                5,
                Array(5) { IntDomain(0, 10) },
                arrayOf(
                    Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.LE, 0),
                    Linear(intArrayOf(1, -1), intArrayOf(1, 2), LinearOp.LE, 0),
                    Linear(intArrayOf(1, -1), intArrayOf(2, 3), LinearOp.LE, 0),
                    Linear(intArrayOf(1, -1), intArrayOf(3, 4), LinearOp.LE, 0),
                    ReifiedLinear(0, intArrayOf(1), intArrayOf(4), LinearOp.LE, 3),
                ),
            ),
        )
        val cp = CpSearchComponent(native, mapping)
        cp.rebase()
        val session = SearchSession(listOf(cp), atoms = SearchAtomRegistry(1))
        assertIs<ComponentResult.Consistent>(session.initialize())

        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(0, true))))

        for (source in mapping) {
            assertEquals(3L, session.intUpperBound(source))
            assertContentEquals(
                intArrayOf(Lit.make(0, false)),
                session.explainAtoms(session.intUpperBoundPremise(source))?.literals,
            )
        }
    }

    @Test
    fun `a repeated native bound should explain through the new sibling Boolean premise`() {
        val cp = CpSearchComponent(
            PropagationSession(
                Problem(
                    2,
                    4,
                    Array(4) { IntDomain(0, 10) },
                    arrayOf(
                        Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.LE, 0),
                        Linear(intArrayOf(1, -1), intArrayOf(1, 2), LinearOp.LE, 0),
                        Linear(intArrayOf(1, -1), intArrayOf(1, 3), LinearOp.LE, 0),
                        ReifiedLinear(0, intArrayOf(1), intArrayOf(2), LinearOp.LE, 5),
                        ReifiedLinear(1, intArrayOf(1), intArrayOf(3), LinearOp.LE, 5),
                    ),
                ),
            ),
            intArrayOf(4, 7, 9, 12),
        )
        cp.rebase()
        val session = SearchSession(listOf(cp), atoms = SearchAtomRegistry(2))
        assertIs<ComponentResult.Consistent>(session.initialize())
        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(0, true))))
        assertContentEquals(
            intArrayOf(Lit.make(0, false)),
            session.explainAtoms(session.intUpperBoundPremise(4))?.literals,
        )
        session.popTo(0)

        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(1, true))))

        assertEquals(5L, session.intUpperBound(4))
        assertContentEquals(
            intArrayOf(Lit.make(1, false)),
            session.explainAtoms(session.intUpperBoundPremise(4))?.literals,
        )
    }

    @Test
    fun `a learned assertion should report its cascading native conflict`() {
        for (nativeSat in listOf(false, true)) {
            val problem = Problem(
                4,
                0,
                emptyArray(),
                arrayOf(
                    Clause(intArrayOf(Lit.make(0, false), Lit.make(1, false), Lit.make(2, true))),
                    Clause(intArrayOf(Lit.make(0, false), Lit.make(1, false), Lit.make(2, false))),
                    Clause(intArrayOf(Lit.make(0, false), Lit.make(1, true), Lit.make(3, true))),
                    Clause(intArrayOf(Lit.make(0, false), Lit.make(1, true), Lit.make(3, false))),
                ),
            )
            val cp = CpSearchComponent(PropagationSession(problem, nativeSat = nativeSat))
            val conflicts = ArrayList<PropagationResult.Unsat>()
            cp.cascadeConflictObserver = conflicts::add
            cp.rebase()
            val session = SearchSession(listOf(cp), atoms = SearchAtomRegistry(4))
            assertIs<ComponentResult.Consistent>(session.initialize())
            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(0, true))))
            assertIs<ComponentResult.Conflict>(session.push(SearchDecision.Bool(Lit.make(1, true))))
            val resolution = assertIs<SearchConflictResolution.Backjump>(cp.resolveConflict(session))
            session.popTo(resolution.conflict.decisionLevel)

            val result = resolution.conflict.apply(session)

            assertIs<SearchLearnedConflictResult.Backjump>(result)
            assertEquals(1, conflicts.size)
            assertSame(conflicts.single(), cp.lastConflict)
            assertEquals(setOf(0, 1, 3), conflicts.single().conflictBools.toSet())
        }
    }

    @Test
    fun `shared indicator explanations should retain their originating bound after narrowing`() {
        for (value in listOf(false, true)) {
            val cp = CpSearchComponent(
                PropagationSession(
                    Problem(
                        1,
                        1,
                        arrayOf(IntDomain(0, 10)),
                        arrayOf(ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.LE, 5)),
                    ),
                ),
            )
            cp.rebase()
            val session = SearchSession(listOf(cp), atoms = SearchAtomRegistry(1))
            assertIs<ComponentResult.Consistent>(session.initialize())
            val bound = if (value) SearchDecision.IntAtMost(0, 5L) else SearchDecision.IntAtLeast(0, 6L)
            assertIs<ComponentResult.Consistent>(session.push(bound))
            val boundLiteral = assertNotNull(session.atomLiteral(bound))
            val later = if (value) SearchDecision.IntAtMost(0, 3L) else SearchDecision.IntAtLeast(0, 8L)
            assertIs<ComponentResult.Consistent>(session.push(later))
            val implied = Lit.make(0, value)

            val reason = cp.reasonFor(implied)

            assertEquals(setOf(implied, boundLiteral xor 1), reason?.literals?.toSet())
        }
    }

    @Test
    fun `an unnameable native antecedent should decline its shared proof`() {
        val cp = CpSearchComponent(
            PropagationSession(
                Problem(
                    0,
                    2,
                    arrayOf(IntDomain(0, 10), IntDomain(0, 10)),
                    arrayOf(Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.LE, 0)),
                ),
            ),
        )
        cp.rebase()
        val session = SearchSession(listOf(cp))
        assertIs<ComponentResult.Consistent>(session.initialize())

        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.IntAtMost(1, 3L)))

        assertEquals(3L, session.intUpperBound(0))
        assertSame(SearchAtomPremise.Unavailable, session.intUpperBoundPremise(0))
        assertNull(session.explainAtoms(session.intUpperBoundPremise(0)))
    }

    @Test
    fun `native bound proofs should preserve source integer remapping and rollback`() {
        val native = PropagationSession(
            Problem(
                0,
                2,
                arrayOf(IntDomain(0, 10), IntDomain(0, 10)),
                arrayOf(Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.LE, 0)),
            ),
        )
        val cp = CpSearchComponent(native, intArrayOf(4, 7))
        cp.rebase()
        val session = SearchSession(listOf(cp), atoms = SearchAtomRegistry(0))
        assertIs<ComponentResult.Consistent>(session.initialize())
        val bound = SearchDecision.IntAtMost(7, 3L)
        assertIs<ComponentResult.Consistent>(session.push(bound))
        val literal = assertNotNull(session.atomLiteral(bound))

        assertEquals(3L, session.intUpperBound(4))
        assertContentEquals(intArrayOf(literal xor 1), session.explainAtoms(session.intUpperBoundPremise(4))?.literals)

        session.popTo(0)
        assertEquals(10L, session.intUpperBound(4))
        val sibling = SearchDecision.IntAtLeast(4, 5L)
        assertIs<ComponentResult.Consistent>(session.push(sibling))
        val siblingLiteral = assertNotNull(session.atomLiteral(sibling))
        assertEquals(5L, session.intLowerBound(7))
        assertContentEquals(
            intArrayOf(siblingLiteral xor 1),
            session.explainAtoms(session.intLowerBoundPremise(7))?.literals,
        )
    }

    @Test
    fun `a native order antecedent should become its shared integer witness`() {
        val native = PropagationSession(
            Problem(1, 1, arrayOf(IntDomain(0, 10)), emptyArray()),
        )
        val order = native.boundGeLit(0, 5L, positive = false)
        assertIs<PropagationResult.Implied>(
            native.addLearnedClause(Clause(intArrayOf(Lit.make(0, true), order)), lbd = 2),
        )
        val cp = CpSearchComponent(native)
        cp.rebase()
        val session = SearchSession(listOf(cp), atoms = SearchAtomRegistry(1))
        assertIs<ComponentResult.Consistent>(session.initialize())
        val reserved = assertNotNull(SearchIntegerBound.of(SearchDecision.IntAtMost(0, 0L)))
        assertNotNull(session.registerAtom(reserved, reserved.complement()))
        val bound = SearchDecision.IntAtLeast(0, 5L)

        assertIs<ComponentResult.Consistent>(session.push(bound))

        assertEquals(true, session.boolValue(0))
        assertEquals(
            setOf(Lit.make(0, true), assertNotNull(session.atomLiteral(bound)) xor 1),
            cp.reasonFor(Lit.make(0, true))?.literals?.toSet(),
        )
    }

    @Test
    fun `late equality exclusions should explain through their establishing source bound`() {
        val cases = listOf(
            Triple(IntDomain(0, 10), 5L, SearchDecision.IntAtLeast(7, 6L) to 8L),
            Triple(
                IntDomain(Long.MAX_VALUE - 4, Long.MAX_VALUE),
                Long.MAX_VALUE - 1,
                SearchDecision.IntAtMost(7, Long.MAX_VALUE - 2) to Long.MAX_VALUE - 3,
            ),
        )
        for ((domain, excluded, bounds) in cases) {
            val (establishing, later) = bounds
            val atLeast = establishing is SearchDecision.IntAtLeast
            val native = PropagationSession(Problem(1, 1, arrayOf(domain), emptyArray()))
            val cp = CpSearchComponent(native, intArrayOf(7))
            cp.rebase()
            val session = SearchSession(listOf(cp), atoms = SearchAtomRegistry(1))
            assertIs<ComponentResult.Consistent>(session.initialize())
            assertIs<ComponentResult.Consistent>(session.push(establishing))
            val equality = native.equalityLit(0, excluded, positive = true)
            val consequence = if (atLeast) {
                native.boundGeLit(0, later, positive = true)
            } else {
                native.boundLeLit(0, later, positive = true)
            }

            cp.importLearned(native, session, Lit.make(0, true), equality)
            cp.importLearned(native, session, Lit.make(0, false), consequence)

            assertEquals(later, if (atLeast) session.intLowerBound(7) else session.intUpperBound(7))
            assertEquals(
                setOf(Lit.make(0, true), assertNotNull(session.atomLiteral(establishing)) xor 1),
                cp.reasonFor(Lit.make(0, true))?.literals?.toSet(),
            )
        }
    }

    @Test
    fun `complements of extreme finite bounds should refute without wrapping`() {
        for (upper in listOf(false, true)) {
            val cp = CpSearchComponent(
                PropagationSession(Problem(0, 1, arrayOf(IntDomain(Long.MIN_VALUE, Long.MAX_VALUE)), emptyArray())),
            )
            cp.rebase()
            val session = SearchSession(listOf(cp), atoms = SearchAtomRegistry(0))
            assertIs<ComponentResult.Consistent>(session.initialize())
            val bound = if (upper) {
                SearchDecision.IntAtMost(0, Long.MAX_VALUE)
            } else {
                SearchDecision.IntAtLeast(0, Long.MIN_VALUE)
            }
            assertIs<ComponentResult.Consistent>(session.push(bound))
            val literal = assertNotNull(session.atomLiteral(bound))
            session.popTo(0)

            val conflict = assertIs<ComponentResult.Conflict>(session.push(SearchDecision.Bool(literal xor 1)))

            assertContentEquals(intArrayOf(literal), conflict.explanation?.literals)
        }
    }

    @Test
    fun `registered theory names should bypass CP arrays while source Booleans still propagate`() {
        val problem = Problem(1, 1, arrayOf(IntDomain(0, 1)), emptyArray())
        val component = CpSearchComponent(PropagationSession(problem))
        component.rebase()
        val session = SearchSession(listOf(component), atoms = SearchAtomRegistry(1))
        session.initialize()
        val atom = requireNotNull(
            SourceBoundAtom.integerSplit(
                session,
                listOf(
                    SourceBoundTerm(
                        SearchIntValue(0),
                        BigFraction.ONE,
                    ),
                ),
                BigFraction.ZERO,
            ),
        )

        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Theory(atom.positive)))
        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(0, true))))
        assertEquals(true, component.session.boolValue(0))
    }

    @Test
    fun `a decision whose fixpoint the deadline cut should be reported undecided`() {
        var armed = false
        val propagation = PropagationSession(
            Problem(2, 0, emptyArray(), arrayOf(Clause(intArrayOf(Lit.make(0, false), Lit.make(1, true))))),
            Cancellation { armed },
            propagationCancelFloor = 0,
        )
        val session = SearchSession(listOf(CpSearchComponent(propagation)))
        assertIs<ComponentResult.Consistent>(session.initialize())

        armed = true

        assertIs<ComponentResult.Indeterminate>(session.push(SearchDecision.Bool(Lit.make(0, true))))
    }

    @Test
    fun `a leaf on a fixpoint the deadline cut should not be feasible`() {
        var armed = false
        val propagation = PropagationSession(
            Problem(2, 0, emptyArray(), arrayOf(Clause(intArrayOf(Lit.make(0, false), Lit.make(1, true))))),
            Cancellation { armed },
            propagationCancelFloor = 0,
        )
        val session = SearchSession(listOf(CpSearchComponent(propagation)))
        assertIs<ComponentResult.Consistent>(session.initialize())
        armed = true
        propagation.pinBool(0, true)

        assertIs<ComponentCheck.Indeterminate>(session.check())
    }

    @Test
    fun `CP should explain a Boolean it published when the shared analyzer asks`() {
        val propagation = PropagationSession(
            Problem(
                2,
                0,
                emptyArray(),
                arrayOf(Clause(intArrayOf(Lit.make(0, false), Lit.make(1, true)))),
            ),
        )
        val component = CpSearchComponent(propagation)
        val session = SearchSession(listOf(component))
        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(0, true))))

        val reason = component.reasonFor(Lit.make(1, true))

        assertEquals(
            setOf(Lit.make(1, true), Lit.make(0, false)),
            reason?.literals?.toSet(),
        )
    }

    @Test
    fun `shared trail retraction should rewind the CP component`() {
        val propagation = PropagationSession(
            Problem(1, 0, emptyArray(), arrayOf(Clause(intArrayOf(Lit.make(0, true))))),
        )
        val session = SearchSession(listOf(CpSearchComponent(propagation)))

        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(0, true))))
        session.popTo(0)

        assertEquals(0, propagation.decisionLevel)
    }

    @Test
    fun `a shared integer bound decision should retract to the rebased CP root`() {
        val propagation = PropagationSession(
            Problem(0, 1, arrayOf(IntDomain(0, 10)), emptyArray()),
        )
        val component = CpSearchComponent(propagation)
        component.rebase()
        val session = SearchSession(listOf(component))

        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.IntAtMost(0, 4)))
        assertEquals(1, propagation.decisionLevel)
        assertEquals(4L, propagation.intDomain(0).max)
        session.popTo(0)

        assertEquals(0, propagation.decisionLevel)
        assertEquals(10L, propagation.intDomain(0).max)
    }

    @Test
    fun `a native learned implication should be shared without a second CP pin`() {
        val propagation = PropagationSession(Problem(1, 0, emptyArray(), emptyArray()))
        val component = CpSearchComponent(propagation)
        component.rebase()
        var observed: Boolean? = null
        val peer = object : SearchComponent {
            override fun assert(decision: SearchDecision, context: SearchContext): ComponentResult {
                observed = context.boolValue(0)
                return ComponentResult.Consistent
            }
        }
        val session = SearchSession(listOf(component, peer))

        assertIs<ComponentResult.Consistent>(session.initialize())
        val learned = assertIs<PropagationResult.Implied>(
            propagation.addLearnedClause(Clause(intArrayOf(Lit.make(0, true))), lbd = 1),
        )
        assertIs<ComponentResult.Consistent>(component.import(learned, session))
        assertIs<ComponentResult.Consistent>(session.propagate())

        assertEquals(true, observed)
        assertEquals(0, propagation.decisionLevel)
    }

    @Test
    fun `a learned clause a peer cannot decide should leave the run unknown`() {
        val propagation = PropagationSession(
            Problem(
                2,
                0,
                emptyArray(),
                arrayOf(
                    Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
                    Clause(intArrayOf(Lit.make(0, true), Lit.make(1, false))),
                ),
            ),
        )
        val peer = object : SearchComponent {
            override fun assert(decision: SearchDecision, context: SearchContext): ComponentResult =
                if (context.boolValue(0) == true) ComponentResult.Indeterminate else ComponentResult.Consistent
        }
        val session = SearchSession(listOf(CpSearchComponent(propagation), peer))
        val run = session.openRun(numBoolVars = 2)

        assertIs<ComponentResult.Consistent>(session.initialize())

        assertIs<SearchRunEvent.Indeterminate>(run.next())
    }

    @Test
    fun `a Boolean CP conflict should not be duplicated into the shared database`() {
        val propagation = PropagationSession(
            Problem(
                2,
                0,
                emptyArray(),
                arrayOf(
                    Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
                    Clause(intArrayOf(Lit.make(0, true), Lit.make(1, false))),
                ),
            ),
        )
        val session = SearchSession(listOf(CpSearchComponent(propagation)))

        assertIs<ComponentResult.Consistent>(session.initialize())
        assertIs<SearchResult.Satisfied>(session.solve(1))

        assertEquals(0, session.learnedClauseCount)
        assertEquals(true, session.boolValue(0))
    }
}

private fun CpSearchComponent.importLearned(native: PropagationSession, session: SearchSession, vararg lits: Int) {
    val implied = assertIs<PropagationResult.Implied>(native.addLearnedClause(Clause(intArrayOf(*lits)), lbd = 2))
    assertIs<ComponentResult.Consistent>(this.import(implied, session))
}
