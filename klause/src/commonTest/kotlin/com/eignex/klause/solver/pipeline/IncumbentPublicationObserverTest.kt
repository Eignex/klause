package com.eignex.klause.solver.pipeline

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class IncumbentPublicationObserverTest {
    @Test
    fun `publication should be installed before the callback observes it`() {
        lateinit var observer: IncumbentPublicationObserver<String>
        observer = IncumbentPublicationObserver { publication ->
            assertEquals(publication, observer.latest)
            assertEquals(7L, observer.version)
        }

        observer.accepted("incumbent", 7L)
    }

    @Test
    fun `caught callback failure should still fail the invocation`() {
        val failure = IllegalArgumentException("publication")
        val observer = IncumbentPublicationObserver<String> { throw failure }

        val thrown = assertFailsWith<IllegalArgumentException> {
            observer.execute {
                runCatching { observer.accepted("incumbent", 1L) }
            }
        }

        assertSame(failure, thrown)
        assertEquals("incumbent", observer.latest)
    }

    @Test
    fun `callback failure should retain precedence over a later execution failure`() {
        val publicationFailure = IllegalArgumentException("publication")
        val executionFailure = IllegalStateException("execution")
        val observer = IncumbentPublicationObserver<String> { throw publicationFailure }

        val thrown = assertFailsWith<IllegalArgumentException> {
            observer.execute {
                runCatching { observer.accepted("incumbent", 1L) }
                throw executionFailure
            }
        }

        assertSame(publicationFailure, thrown)
        assertEquals(listOf(executionFailure), thrown.suppressedExceptions)
    }

    @Test
    fun `execution failure without a callback failure should propagate unchanged`() {
        val failure = IllegalStateException("execution")
        val observer = IncumbentPublicationObserver<String>(null)

        val thrown = assertFailsWith<IllegalStateException> {
            observer.execute { throw failure }
        }

        assertSame(failure, thrown)
    }
}
