package com.eignex.klause.solver.search

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.theory.qflra.SourceBoundAtom
import com.eignex.klause.theory.qflra.SourceBoundTerm
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SearchAtomRegistryTest {
    @Test
    fun `registration reuses both polarities and rejects incompatible complements`() {
        val session = SearchSession(emptyList(), atoms = SearchAtomRegistry(3, maxAtoms = 1))
        val first = assertNotNull(session.registerAtom(Symbol("yes"), Symbol("no")))

        assertSame(first.positive, assertNotNull(session.registerAtom(Symbol("yes"), Symbol("no"))).positive)
        assertSame(first.negative, assertNotNull(session.registerAtom(Symbol("no"), Symbol("yes"))).positive)
        assertNull(session.registerAtom(Symbol("yes"), Symbol("different")))
        assertNull(session.registerAtom(Symbol("other"), Symbol("no")))
        assertNull(session.registerAtom(Symbol("same"), Symbol("same")))
        assertNull(session.registerAtom(Symbol("a"), Symbol("b")))
        assertEquals(6, first.positive.literal)
        assertEquals(7, first.negative.literal)
    }

    @Test
    fun `both theory alternatives wake Boolean clauses and retract at one shared level`() {
        for (positive in listOf(true, false)) {
            val receiver = BoundReceiver()
            val session = SearchSession(
                listOf(receiver, ClauseSearchComponent(listOf(Clause(intArrayOf(3, 0)), Clause(intArrayOf(2, 1))))),
                atoms = SearchAtomRegistry(1),
            )
            val atom = assertNotNull(
                SourceBoundAtom.integerSplit(
                    session,
                    listOf(SourceBoundTerm(SearchIntValue(0), BigFraction.ONE)),
                    BigFraction.ZERO,
                ),
            )
            val assertion = if (positive) atom.positive else atom.negative
            assertIs<ComponentResult.Consistent>(session.initialize())

            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Theory(assertion)))
            assertEquals(positive, session.boolValue(0))
            assertEquals(positive, session.boolValue(1))
            assertEquals(listOf(assertion.literal to 1), receiver.active)
            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Theory(assertion)))
            assertEquals(1, session.decisionLevel)
            assertIs<ComponentResult.Conflict>(
                session.push(SearchDecision.Theory(if (positive) atom.negative else atom.positive)),
            )
            assertEquals(1, receiver.active.size)
            session.popTo(0)
            assertTrue(receiver.active.isEmpty())
            assertNull(session.boolValue(1))
            assertSame(
                atom.positive,
                assertNotNull(
                    SourceBoundAtom.integerSplit(
                        session,
                        listOf(SourceBoundTerm(SearchIntValue(0), BigFraction.ONE)),
                        BigFraction.ZERO,
                    ),
                ).positive,
            )
        }
    }

    @Test
    fun `Boolean implications deliver registered payloads to their publishing component`() {
        for (theoryPublication in listOf(false, true)) {
            var target: RegisteredTheoryDecision? = null
            val seen = ArrayList<SearchDecision>()
            val component = object : SearchComponent {
                override fun assert(decision: SearchDecision, context: SearchContext): ComponentResult {
                    seen.add(decision)
                    if (decision == SearchDecision.Bool(0)) {
                        val assertion = assertNotNull(target)
                        return if (theoryPublication) {
                            context.publish(SearchDecision.Theory(assertion))
                        } else {
                            context.imply(assertion.literal, SearchExplanation(intArrayOf(1, assertion.literal)))
                        }
                    }
                    return ComponentResult.Consistent
                }
            }
            val session = SearchSession(listOf(component), atoms = SearchAtomRegistry(1))
            target = assertNotNull(session.registerAtom(Symbol("a"), Symbol("b"))).positive

            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(0)))

            assertEquals(true, session.boolValue(1))
            assertEquals(1, session.levelOf(2))
            assertTrue(if (theoryPublication) SearchDecision.Bool(2) in seen else SearchDecision.Theory(target) in seen)
            if (!theoryPublication) assertContentEquals(intArrayOf(1, 2), session.reasonFor(1)?.literals)
        }
    }

    @Test
    fun `recursive explanations retain every active source antecedent and decline incomplete support`() {
        val session = SearchSession(emptyList(), atoms = SearchAtomRegistry(1))
        val atom = assertNotNull(session.registerAtom(Symbol("a"), Symbol("b")))
        session.push(SearchDecision.Bool(0))
        session.push(SearchDecision.Theory(atom.positive))
        val premise = SearchAtomPremise.All(
            listOf(
                SearchAtomPremise.Asserted(SearchDecision.Bool(0)),
                SearchAtomPremise.All(listOf(SearchAtomPremise.Asserted(SearchDecision.Theory(atom.positive)))),
            ),
        )

        assertEquals(setOf(1, 3), assertNotNull(session.explainAtoms(premise)).literals.toSet())
        assertNull(session.explainAtoms(SearchAtomPremise.All(listOf(premise, SearchAtomPremise.Unavailable))))
        assertNull(session.explainAtoms(premise, maxNodes = 2))
        assertNull(
            session.explainAtoms(
                SearchAtomPremise.Asserted(SearchDecision.Bool(0)),
                SearchDecision.Theory(atom.positive),
                maxNodes = 1,
            ),
        )
        assertNull(session.explainAtoms(SearchAtomPremise.Asserted(SearchDecision.Theory(Symbol("opaque")))))
        session.popTo(1)
        assertNull(session.explainAtoms(premise))
        assertEquals(0, session.learnedClauseCount)
    }

    @Test
    fun `foreign owners and rebuilt roots cannot reuse registered meaning`() {
        val registry = SearchAtomRegistry(1)
        val session = SearchSession(emptyList(), atoms = registry)
        val foreignSession = SearchSession(emptyList(), atoms = SearchAtomRegistry(1))
        val foreign = assertNotNull(foreignSession.registerAtom(Symbol("a"), Symbol("b")))
        session.publish(0)

        assertIs<ComponentResult.Indeterminate>(session.push(SearchDecision.Theory(foreign.positive)))
        assertIs<ComponentResult.Indeterminate>(session.publish(SearchDecision.Theory(foreign.positive)))
        assertNull(session.atomLiteral(SearchDecision.Theory(foreign.positive)))
        assertFailsWith<IllegalStateException> { SearchSession(emptyList(), atoms = registry) }
        assertFailsWith<IllegalArgumentException> { session.resetRootFacts() }
        assertEquals(true, session.boolValue(0))
        assertEquals(0, session.decisionLevel)
    }

    @Test
    fun `allocation and literal overflow decline without changing the trail or next id`() {
        for (invalid in listOf(-1, Int.MIN_VALUE, 2, 3)) {
            val session = SearchSession(emptyList(), atoms = SearchAtomRegistry(1))
            assertIs<ComponentResult.Indeterminate>(session.push(SearchDecision.Bool(invalid)))
            assertIs<ComponentResult.Indeterminate>(session.publish(invalid))
            assertEquals(0, session.decisionLevel)
            assertEquals(2, assertNotNull(session.registerAtom(Symbol("a"), Symbol("b"))).positive.literal)
        }
        val full = SearchSession(emptyList(), atoms = SearchAtomRegistry(1 shl 30))
        assertNull(full.registerAtom(Symbol("a"), Symbol("b")))
        val last = SearchSession(emptyList(), atoms = SearchAtomRegistry((1 shl 30) - 1))
        val atom = assertNotNull(last.registerAtom(Symbol("a"), Symbol("b")))
        assertEquals(Int.MAX_VALUE, atom.negative.literal)
        assertNull(last.registerAtom(Symbol("c"), Symbol("d")))
        assertIs<ComponentResult.Consistent>(last.push(SearchDecision.Theory(atom.negative)))
        assertEquals(false, last.boolValue((1 shl 30) - 1))
        assertNull(SearchSession(emptyList()).registerAtom(Symbol("a"), Symbol("b")))
        assertNull(
            SearchSession(
                emptyList(),
                atoms = SearchAtomRegistry(0, maxAtoms = 0),
            ).registerAtom(Symbol("a"), Symbol("b")),
        )
    }

    @Test
    fun `source clause ingress accepts registered atoms and rejects future names before storage`() {
        for (registered in listOf(false, true)) {
            val receiver = BoundReceiver()
            val session = SearchSession(
                listOf(receiver, ClauseSearchComponent(listOf(Clause(intArrayOf(2))))),
                atoms = SearchAtomRegistry(1),
            )
            if (registered) session.registerAtom(Symbol("a"), Symbol("b"))

            val initialized = session.initialize()

            if (registered) {
                assertIs<ComponentResult.Consistent>(initialized)
                assertEquals(true, session.boolValue(1))
                assertEquals(listOf(2 to 0), receiver.active)
            } else {
                assertIs<ComponentResult.Indeterminate>(initialized)
                session.registerAtom(Symbol("a"), Symbol("b"))
                assertIs<ComponentResult.Consistent>(session.propagate())
                assertNull(session.boolValue(1))
                assertTrue(receiver.active.isEmpty())
            }
        }
    }

    @Test
    fun `fixed source heuristic retracts registered atoms without indexing them in its heap`() {
        val component = LinearComponent()
        val session = SearchSession(listOf(component), atoms = SearchAtomRegistry(2))
        component.initializeAtoms(session)
        val branching = HeuristicBooleanBranching(Vsids(), numBoolVars = 2)
        session.openRun(2, booleanBranching = branching, observer = branching)
        assertNotNull(branching.alternatives(session))
        session.push(SearchDecision.Bool(0))
        val conflict = assertIs<ComponentResult.Conflict>(session.push(SearchDecision.Theory(component.split.positive)))
        val learned = assertIs<SearchConflictResolution.Backjump>(
            session.explainedConflict(conflict.explanation),
        ).conflict
        branching.onLearnedConflict(learned)

        session.popTo(learned.decisionLevel)
        assertIs<SearchLearnedConflictResult.Resume>(learned.apply(session))
        assertTrue(component.active.contains(component.split.negative.literal to 1))
        assertIs<ComponentResult.Consistent>(session.restart())
        assertNotNull(branching.alternatives(session))
        assertTrue(component.active.isEmpty())
        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(0)))
        assertTrue(component.active.contains(component.split.negative.literal to 1))
    }

    @Test
    fun `cancelled registration consumes no Boolean ids`() {
        var cancelled = true
        val session = SearchSession(
            emptyList(),
            cancellation = Cancellation { cancelled },
            atoms = SearchAtomRegistry(1),
        )
        assertNull(session.registerAtom(Symbol("a"), Symbol("b")))
        cancelled = false
        assertEquals(2, assertNotNull(session.registerAtom(Symbol("a"), Symbol("b"))).positive.literal)
        assertEquals(0, session.decisionLevel)
    }

    @Test
    fun `unnamed clause and eager reason literals cannot acquire meaning through later registration`() {
        val session = SearchSession(emptyList(), atoms = SearchAtomRegistry(1))
        val explanation = SearchExplanation(intArrayOf(0, 2))
        session.learn(explanation)
        session.imply(0, explanation)

        assertNull(session.reasonFor(0))
        assertEquals(0, session.learnedClauseCount)
        assertNull(session.explainedConflict(SearchExplanation(intArrayOf(1, 3))))
        val atom = assertNotNull(session.registerAtom(Symbol("a"), Symbol("b")))
        assertIs<ComponentResult.Consistent>(session.propagate())
        assertNull(session.boolValue(atom.positive.literal ushr 1))
    }

    @Test
    fun `unnamed lazy reasons are withheld from first UIP analysis`() {
        val component = object : SearchComponent {
            override fun assert(decision: SearchDecision, context: SearchContext): ComponentResult =
                if (decision == SearchDecision.Bool(0)) context.publish(2) else ComponentResult.Consistent

            override fun reasonFor(literal: Int): SearchExplanation = SearchExplanation(intArrayOf(literal, 1, 4))
        }
        val session = SearchSession(listOf(component), atoms = SearchAtomRegistry(2))
        session.push(SearchDecision.Bool(0))

        assertEquals(
            SearchConflictResolution.Chronological,
            session.explainedConflict(SearchExplanation(intArrayOf(1, 3))),
        )
        assertNull(session.reasonFor(1))
    }

    @Test
    fun `shared traversal resolves first UIP across a source split and replays its bound after restart`() {
        val component = LinearComponent()
        val session = SearchSession(listOf(component), atoms = SearchAtomRegistry(2))
        component.initializeAtoms(session)
        var learned: SearchLearnedConflict? = null
        val result = session.solve(
            0,
            observer = object : SearchRunObserver {
                override fun onLearnedConflict(conflict: SearchLearnedConflict) {
                    learned = conflict
                }
            },
        )

        assertIs<SearchResult.Satisfied>(result)
        val conflict = assertNotNull(learned)
        assertEquals(1, conflict.decisionLevel)
        assertEquals(setOf(1, component.split.negative.literal), conflict.guardLiterals.toSet())
        assertEquals(1, session.learnedClauseCount)
        assertTrue(component.retractions.contains(1))
        assertTrue(component.active.contains(component.split.negative.literal to 1))
        assertIs<ComponentResult.Consistent>(session.restart())
        assertTrue(component.active.isEmpty())
        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(0)))
        assertEquals(false, session.boolValue(component.split.positive.literal ushr 1))
        assertTrue(component.active.contains(component.split.negative.literal to 1))
        assertEquals(
            conflict.guardLiterals.toSet(),
            session.reasonFor(component.split.positive.literal ushr 1)?.literals?.toSet(),
        )

        for (x in -2..2) {
            for (y in -2..2) {
                for (z in -2..2) {
                    for (guard in listOf(false, true)) {
                        if (y > x || z > x || (guard && y + z < 1)) continue
                        val sourceTruth = mapOf(0 to guard, 2 to (x <= 0), 3 to (y <= 0), 4 to (z <= 0))
                        assertTrue(conflict.guardLiterals.any { sourceTruth.getValue(it ushr 1) == (it and 1 == 0) })
                        for (reason in component.reasons) {
                            assertTrue(reason.literals.any { sourceTruth.getValue(it ushr 1) == (it and 1 == 0) })
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `unnameable arithmetic conflict explores the sibling chronologically without learning`() {
        val component = LinearComponent(unnameable = true)
        val session = SearchSession(listOf(component), atoms = SearchAtomRegistry(2))
        component.initializeAtoms(session)

        assertIs<SearchResult.Satisfied>(session.solve(0))
        assertEquals(0, session.learnedClauseCount)
        assertTrue(component.retractions.contains(2))
        assertTrue(component.active.any { it.first == component.split.negative.literal })
    }

    private data class Symbol(val name: String) : SearchTheoryDecision

    private open class BoundReceiver : SearchComponent {
        val active = ArrayList<Pair<Int, Int>>()
        val retractions = ArrayList<Int>()

        override fun assert(decision: SearchDecision, context: SearchContext): ComponentResult {
            val assertion = (decision as? SearchDecision.Theory)?.decision as? RegisteredTheoryDecision
            if (assertion != null) active.add(assertion.literal to context.decisionLevel)
            return ComponentResult.Consistent
        }

        override fun retract(decisionLevel: Int) {
            active.removeAll { it.second > decisionLevel }
            retractions.add(decisionLevel)
        }
    }

    // Root constraints are y <= x, z <= x and guard => y + z >= 1.
    private class LinearComponent(private val unnameable: Boolean = false) :
        BoundReceiver(),
        SearchBrancher {
        lateinit var split: SearchTheoryAtom
        private lateinit var y: SearchTheoryAtom
        private lateinit var z: SearchTheoryAtom
        val reasons = ArrayList<SearchExplanation>()

        fun initializeAtoms(context: SearchContext) {
            val atoms = (0..2).map { variable ->
                assertNotNull(
                    SourceBoundAtom.integerSplit(
                        context,
                        listOf(SourceBoundTerm(SearchIntValue(variable), BigFraction.ONE)),
                        BigFraction.ZERO,
                    ),
                )
            }
            split = atoms[0]
            y = atoms[1]
            z = atoms[2]
        }

        override fun assert(decision: SearchDecision, context: SearchContext): ComponentResult {
            super<BoundReceiver>.assert(decision, context)
            if (decision == SearchDecision.Theory(split.positive)) {
                for (consequence in listOf(y.positive, z.positive)) {
                    val reason = assertNotNull(
                        context.explainAtoms(SearchAtomPremise.Asserted(decision), SearchDecision.Theory(consequence)),
                    )
                    reasons.add(reason)
                    val result = context.imply(consequence.literal, reason)
                    if (result !is ComponentResult.Consistent) return result
                }
            }
            return ComponentResult.Consistent
        }

        override fun propagate(context: SearchContext): ComponentResult {
            if (context.boolValue(
                    0,
                ) != true || context.boolValue(
                    y.positive.literal ushr 1,
                ) != true || context.boolValue(z.positive.literal ushr 1) != true
            ) {
                return ComponentResult.Consistent
            }
            val premises = listOf(
                SearchAtomPremise.Asserted(SearchDecision.Bool(0)),
                SearchAtomPremise.Asserted(SearchDecision.Theory(y.positive)),
                SearchAtomPremise.Asserted(SearchDecision.Theory(z.positive)),
            )
            val explanation = context.explainAtoms(
                SearchAtomPremise.All(if (unnameable) premises + SearchAtomPremise.Unavailable else premises),
            )
            if (explanation != null) reasons.add(explanation)
            return ComponentResult.Conflict(explanation)
        }

        override fun nextBranch(context: SearchContext): List<SearchDecision>? = when {
            context.boolValue(0) == null -> listOf(SearchDecision.Bool(0), SearchDecision.Bool(1))
            context.boolValue(1) == null -> listOf(SearchDecision.Bool(2), SearchDecision.Bool(3))
            context.boolValue(split.positive.literal ushr 1) == null -> split.alternatives()
            else -> null
        }
    }
}
