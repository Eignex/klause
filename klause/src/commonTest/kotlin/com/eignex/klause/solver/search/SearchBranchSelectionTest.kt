package com.eignex.klause.solver.search

import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame

class SearchBranchSelectionTest {
    @Test
    fun `default selection does not consult components when Boolean alternatives exist`() {
        val alternatives = listOf(SearchDecision.Bool(0), SearchDecision.Bool(1))
        val sources = object : SearchBranchSources {
            override fun booleanAlternatives(): List<SearchDecision> = alternatives
            override fun componentAlternatives(kind: SearchBranchKind?): List<SearchDecision>? =
                error("component proposal was evaluated")
        }

        val selected = SearchBranchSelection.Default.alternatives(sources)

        assertSame(alternatives, selected)
    }

    @Test
    fun `explicit priority selects the first available category lazily`() {
        for (first in SearchBranchKind.entries) {
            val consulted = ArrayList<SearchBranchKind>()
            val alternatives = listOf(SearchDecision.IntEqual(0, 0), SearchDecision.IntEqual(0, 1))
            val sources = object : SearchBranchSources {
                override fun booleanAlternatives(): List<SearchDecision>? {
                    consulted += SearchBranchKind.Boolean
                    return alternatives
                }
                override fun componentAlternatives(kind: SearchBranchKind?): List<SearchDecision>? {
                    consulted += requireNotNull(kind)
                    return alternatives
                }
            }
            val order = listOf(first) + SearchBranchKind.entries.filter { it != first }

            val selected = SearchBranchSelection.Priority(order).alternatives(sources)

            assertSame(alternatives, selected)
            assertEquals(listOf(first), consulted)
        }
    }

    @Test
    fun `priority falls through unavailable categories without dropping alternatives`() {
        val consulted = ArrayList<SearchBranchKind>()
        val alternatives = listOf(SearchDecision.IntEqual(0, 0), SearchDecision.IntEqual(0, 1))
        val sources = object : SearchBranchSources {
            override fun booleanAlternatives(): List<SearchDecision>? = null
            override fun componentAlternatives(kind: SearchBranchKind?): List<SearchDecision>? {
                consulted += requireNotNull(kind)
                return if (kind == SearchBranchKind.Arithmetic) alternatives else null
            }
        }

        val selected = SearchBranchSelection.Priority(listOf(
            SearchBranchKind.Finite, SearchBranchKind.Boolean, SearchBranchKind.Arithmetic,
        )).alternatives(sources)

        assertSame(alternatives, selected)
        assertEquals(listOf(SearchBranchKind.Finite, SearchBranchKind.Boolean, SearchBranchKind.Arithmetic), consulted)
    }

    @Test
    fun `an empty exhaustive proposal is selected without evaluating another category`() {
        val sources = object : SearchBranchSources {
            override fun booleanAlternatives(): List<SearchDecision> = emptyList()
            override fun componentAlternatives(kind: SearchBranchKind?): List<SearchDecision>? =
                error("component proposal was evaluated")
        }

        assertEquals(emptyList(), SearchBranchSelection.Default.alternatives(sources))
    }

    @Test
    fun `priority snapshots the supplied order`() {
        val order = mutableListOf(SearchBranchKind.Arithmetic, SearchBranchKind.Finite, SearchBranchKind.Boolean)
        val policy = SearchBranchSelection.Priority(order)
        val consulted = ArrayList<SearchBranchKind>()
        val sources = object : SearchBranchSources {
            override fun booleanAlternatives(): List<SearchDecision>? = error("Boolean proposal was evaluated")
            override fun componentAlternatives(kind: SearchBranchKind?): List<SearchDecision> {
                consulted += requireNotNull(kind)
                return emptyList()
            }
        }
        order.reverse()

        policy.alternatives(sources)

        assertEquals(listOf(SearchBranchKind.Arithmetic), consulted)
    }

    @Test
    fun `priority rejects missing or duplicate categories`() {
        for (order in listOf(emptyList(), listOf(SearchBranchKind.Boolean),
            listOf(SearchBranchKind.Boolean, SearchBranchKind.Boolean, SearchBranchKind.Arithmetic))) {
            assertFailsWith<IllegalArgumentException> { SearchBranchSelection.Priority(order) }
        }
    }
    @Test
    fun `default selection preserves Boolean installed legacy and independent proposal order`() {
        val commits = ArrayList<Int>()
        val installed = object : SearchBrancher, SearchBranchProposal {
            override val branchKind = SearchBranchKind.Arithmetic
            override fun nextBranch(context: SearchContext): List<SearchDecision>? =
                if (context.boolValue(1) == null) listOf(SearchDecision.Bool(2), SearchDecision.Bool(3)) else null
            override fun check(context: SearchContext): ComponentCheck = ComponentCheck.Feasible
        }
        val legacy = object : SearchBrancher {
            override fun nextBranch(context: SearchContext): List<SearchDecision>? =
                if (context.boolValue(2) == null) listOf(SearchDecision.Bool(4), SearchDecision.Bool(5)) else null
        }
        val independent = object : SearchBranchProposal {
            override val branchKind = SearchBranchKind.Finite
            override fun nextBranch(context: SearchContext): List<SearchDecision>? =
                if (context.boolValue(3) == null) listOf(SearchDecision.Bool(6), SearchDecision.Bool(7)) else null
        }
        val session = SearchComponentSet(listOf(installed), listOf(legacy), listOf(independent)).session()
        session.initialize()
        val run = session.openRun(1, observer = object : SearchRunObserver {
            override fun onCommit(decision: SearchDecision, level: Int) {
                commits += (decision as SearchDecision.Bool).literal ushr 1
            }
        })

        assertIs<SearchRunEvent.Satisfied>(run.next())

        assertEquals(listOf(0, 1, 2, 3), commits)
    }

    @Test
    fun `category priority changes selection without changing deductive installation order`() {
        for (first in SearchBranchKind.entries) {
            val initialized = ArrayList<Int>()
            val commits = ArrayList<Int>()
            val components = listOf(SearchBranchKind.Finite, SearchBranchKind.Arithmetic).mapIndexed { index, kind ->
                object : SearchBrancher, SearchBranchProposal {
                    override val branchKind = kind
                    override fun initialize(context: SearchContext): ComponentResult {
                        initialized += index + 1
                        return ComponentResult.Consistent
                    }
                    override fun nextBranch(context: SearchContext): List<SearchDecision>? =
                        if (context.boolValue(index + 1) == null) {
                            listOf(SearchDecision.Bool((index + 1) * 2), SearchDecision.Bool((index + 1) * 2 + 1))
                        } else {
                            null
                        }
                    override fun check(context: SearchContext): ComponentCheck = ComponentCheck.Feasible
                }
            }
            val session = SearchSession(components)
            session.initialize()
            val order = listOf(first) + SearchBranchKind.entries.filter { it != first }
            val run = session.openRun(1, branchSelection = SearchBranchSelection.Priority(order),
                observer = object : SearchRunObserver {
                    override fun onCommit(decision: SearchDecision, level: Int) {
                        commits += (decision as SearchDecision.Bool).literal ushr 1
                    }
                })

            assertIs<SearchRunEvent.Satisfied>(run.next())

            assertEquals(listOf(1, 2), initialized)
            assertEquals(order.map { it.ordinal }, commits)
        }
    }

    @Test
    fun `independent proposals traverse every alternative through an installed checker`() {
        for (selection in listOf(SearchBranchSelection.Default, SearchBranchSelection.Priority(
            listOf(SearchBranchKind.Arithmetic, SearchBranchKind.Finite, SearchBranchKind.Boolean)))) {
            val checked = ArrayList<Boolean?>()
            val component = object : SearchComponent {
                override fun check(context: SearchContext): ComponentCheck {
                    checked += context.boolValue(0)
                    return ComponentCheck.Feasible
                }
            }
            val proposal = object : SearchBranchProposal {
                override val branchKind = SearchBranchKind.Arithmetic
                override fun nextBranch(context: SearchContext): List<SearchDecision>? =
                    if (context.boolValue(0) == null) listOf(SearchDecision.Bool(0), SearchDecision.Bool(1)) else null
            }
            val session = SearchComponentSet(listOf(component), proposals = listOf(proposal)).session()
            session.initialize()
            val run = session.openRun(0, branchSelection = selection)

            assertIs<SearchRunEvent.Satisfied>(run.next())
            assertIs<SearchRunEvent.Satisfied>(run.next())
            assertEquals(SearchRunEvent.Exhausted, run.next())

            assertEquals(listOf<Boolean?>(true, false), checked)
        }
    }

    @Test
    fun `an unknown selected leaf prevents exhaustion from becoming a refutation`() {
        val component = object : SearchComponent {
            override fun check(context: SearchContext): ComponentCheck = if (context.boolValue(0) == true) {
                ComponentCheck.Indeterminate
            } else {
                ComponentCheck.Infeasible()
            }
        }
        val proposal = object : SearchBranchProposal {
            override val branchKind = SearchBranchKind.Arithmetic
            override fun nextBranch(context: SearchContext): List<SearchDecision>? =
                if (context.boolValue(0) == null) listOf(SearchDecision.Bool(0), SearchDecision.Bool(1)) else null
        }
        val session = SearchSession(listOf(component), proposals = listOf(proposal))
        session.initialize()
        val run = session.openRun(0, branchSelection = SearchBranchSelection.Priority(
            listOf(SearchBranchKind.Arithmetic, SearchBranchKind.Boolean, SearchBranchKind.Finite)))

        assertEquals(SearchRunEvent.Indeterminate.Component, run.next())
    }

    @Test
    fun `an empty independent proposal exhausts without checking a leaf`() {
        val component = object : SearchComponent {
            override fun check(context: SearchContext): ComponentCheck = error("leaf was checked")
        }
        val proposal = object : SearchBranchProposal {
            override val branchKind = SearchBranchKind.Arithmetic
            override fun nextBranch(context: SearchContext): List<SearchDecision> = emptyList()
        }
        val session = SearchSession(listOf(component), proposals = listOf(proposal))
        session.initialize()
        val run = session.openRun(0, branchSelection = SearchBranchSelection.Priority(
            listOf(SearchBranchKind.Arithmetic, SearchBranchKind.Finite, SearchBranchKind.Boolean)))

        assertEquals(SearchRunEvent.Exhausted, run.next())
    }

    @Test
    fun `cancellation prevents consulting independent proposals`() {
        val proposal = object : SearchBranchProposal {
            override val branchKind = SearchBranchKind.Finite
            override fun nextBranch(context: SearchContext): List<SearchDecision>? = error("proposal was consulted")
        }
        val session = SearchSession(emptyList(), cancellation = Cancellation { true }, proposals = listOf(proposal))
        val run = session.openRun(0, branchSelection = SearchBranchSelection.Priority(
            listOf(SearchBranchKind.Finite, SearchBranchKind.Arithmetic, SearchBranchKind.Boolean)))

        assertEquals(SearchRunEvent.Indeterminate.Cancelled, run.next())
    }
}
