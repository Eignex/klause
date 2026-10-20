package com.eignex.klause.bench.profile

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class JvmSavedReferenceCacheTest {
    @Test
    fun `a repeated key reuses its complete loaded value`() {
        val cache = JvmSavedReferenceCache<String, String>()
        var loads = 0

        val actual = List(3) {
            cache.getOrLoad("key") {
                loads++
                "complete unknown fields" to 23L
            }
        }

        assertEquals(List(3) { "complete unknown fields" }, actual)
        assertEquals(1, loads)
    }

    @ParameterizedTest
    @ValueSource(strings = ["entries", "bytes"])
    fun `either allowance evicts the least recently read value`(allowance: String) {
        val cache = JvmSavedReferenceCache<String, String>(
            maximumEntries = if (allowance == "entries") 2 else 8,
            maximumBytes = if (allowance == "bytes") 2L else 8L,
        )
        var evictedLoads = 0
        cache.getOrLoad("retained") { "whole retained" to 1L }
        cache.getOrLoad("evicted") { evictedLoads++; "whole evicted" to 1L }
        cache.getOrLoad("retained") { error("Recently read value was lost") }
        cache.getOrLoad("new") { "whole new" to 1L }

        assertEquals("whole retained", cache.getOrLoad("retained") { error("Recently read value was lost") })
        assertEquals("whole evicted", cache.getOrLoad("evicted") { evictedLoads++; "whole evicted" to 1L })
        assertEquals(2, evictedLoads)
    }

    @Test
    fun `an oversized value is returned whole without displacing retained values`() {
        val cache = JvmSavedReferenceCache<String, String>(maximumBytes = 2)
        cache.getOrLoad("retained") { "whole retained" to 1L }
        var loads = 0

        val actual = List(2) {
            cache.getOrLoad("oversized") { loads++; "whole oversized" to 3L }
        }

        assertEquals(List(2) { "whole oversized" }, actual)
        assertEquals(2, loads)
        assertEquals("whole retained", cache.getOrLoad("retained") { error("Retained value was displaced") })
    }

    @Test
    fun `payload accounting cannot overflow its allowance`() {
        val cache = JvmSavedReferenceCache<String, String>(maximumBytes = Long.MAX_VALUE)
        var loads = 0
        cache.getOrLoad("large") { loads++; "whole large" to Long.MAX_VALUE }
        cache.getOrLoad("small") { "whole small" to 1L }

        val actual = cache.getOrLoad("large") { loads++; "whole large" to Long.MAX_VALUE }

        assertEquals("whole large", actual)
        assertEquals(2, loads)
    }

    @Test
    fun `a refused weight cannot poison a later complete load`() {
        val cache = JvmSavedReferenceCache<String, String>()
        assertFailsWith<IllegalArgumentException> {
            cache.getOrLoad("key") { "refused" to -1L }
        }

        val actual = cache.getOrLoad("key") { "whole accepted" to 1L }

        assertEquals("whole accepted", actual)
    }
}
