package com.eignex.klause.solver.search

import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class SearchComponentSetTest {
    @Test
    fun `both session overloads share one mutable component claim`() {
        for (explicitControls in listOf(false, true)) {
            var initializations = 0
            val component = object : SearchComponent {
                override fun initialize(context: SearchContext): ComponentResult {
                    initializations++
                    return ComponentResult.Consistent
                }
            }
            val components = SearchComponentSet(listOf(component))
            val session = if (explicitControls) {
                components.session(executionControls = SearchExecutionControlAdapter(Cancellation.Never))
            } else components.session()
            assertIs<ComponentResult.Consistent>(session.initialize())

            assertFailsWith<IllegalStateException> { components.session() }
            assertFailsWith<IllegalStateException> {
                components.session(executionControls = SearchExecutionControlAdapter(Cancellation.Never))
            }

            assertEquals(1, initializations)
        }
    }

    @Test
    fun `rejected reuse leaves its unused atom registry available`() {
        val components = SearchComponentSet(emptyList())
        components.session()
        val atoms = SearchAtomRegistry(0)

        assertFailsWith<IllegalStateException> { components.session(atoms = atoms) }

        val independent = SearchComponentSet(emptyList()).session(atoms = atoms)
        assertIs<ComponentResult.Consistent>(independent.initialize())
    }

    @Test
    fun `failed atom attachment consumes the component set claim`() {
        val atoms = SearchAtomRegistry(0)
        SearchComponentSet(emptyList()).session(atoms = atoms)
        val components = SearchComponentSet(emptyList())

        assertFailsWith<IllegalStateException> { components.session(atoms = atoms) }
        assertFailsWith<IllegalStateException> { components.session(atoms = SearchAtomRegistry(0)) }
    }

    @Test
    fun `one claimed session retains ordinary push and pop behavior`() {
        val components = SearchComponentSet(listOf(ClauseSearchComponent(emptyList())))
        val session = components.session(atoms = SearchAtomRegistry(1))
        assertIs<ComponentResult.Consistent>(session.initialize())
        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(0)))
        assertEquals(true, session.boolValue(0))

        session.popTo(0)
        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(1)))

        assertEquals(false, session.boolValue(0))
    }
}
