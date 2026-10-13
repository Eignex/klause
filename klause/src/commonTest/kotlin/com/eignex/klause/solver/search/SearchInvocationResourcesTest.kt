package com.eignex.klause.solver.search

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class SearchInvocationResourcesTest {
    @Test
    fun `resources close once in reverse registration order`() {
        val closed = ArrayList<Int>()
        val resources = SearchInvocationResources()
        val first = resources.own(AutoCloseable { closed += 1 })
        resources.own(AutoCloseable { closed += 2 })
        resources.own(first)

        resources.close()
        resources.close()

        assertEquals(listOf(2, 1), closed)
    }

    @Test
    fun `cleanup attempts all resources after its first failure`() {
        val first = IllegalStateException("first")
        val second = IllegalArgumentException("second")
        val closed = ArrayList<Int>()
        val resources = SearchInvocationResources()
        resources.own(AutoCloseable { closed += 1 })
        resources.own(AutoCloseable { closed += 2; throw second })
        resources.own(AutoCloseable { closed += 3; throw first })

        assertSame(first, assertFailsWith<IllegalStateException> { resources.close() })

        assertEquals(listOf(3, 2, 1), closed)
        assertSame(second, first.suppressedExceptions.single())
        resources.close()
    }

    @Test
    fun `cleanup preserves the primary failure without self suppression`() {
        val primary = IllegalArgumentException("primary")
        val cleanup = IllegalStateException("cleanup")
        val resources = SearchInvocationResources()
        resources.own(AutoCloseable { throw cleanup })
        resources.own(AutoCloseable { throw primary })

        resources.closeAfter(primary)

        assertSame(cleanup, primary.suppressedExceptions.single())
    }

    @Test
    fun `reentrant cleanup cannot release a resource twice`() {
        var closes = 0
        val resources = SearchInvocationResources()
        resources.own(AutoCloseable { closes++; resources.close() })

        resources.close()

        assertEquals(1, closes)
        assertFailsWith<IllegalStateException> { resources.own(AutoCloseable {}) }
    }
}
