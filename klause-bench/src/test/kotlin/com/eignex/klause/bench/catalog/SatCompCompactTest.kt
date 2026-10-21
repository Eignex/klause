package com.eignex.klause.bench.catalog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SatCompCompactTest {

    private val instances = SatCompCompact.instances

    @Test
    fun `instances should be distinct content hashes`() {
        assertTrue(instances.all { it.hash.matches(Regex("[0-9a-f]{32}")) })
        assertEquals(instances.size, instances.map { it.hash }.distinct().size)
    }

    @Test
    fun `instances should split evenly between SAT and UNSAT`() {
        assertEquals(instances.size / 2, instances.count { it.expected == Expected.Sat })
        assertEquals(instances.size / 2, instances.count { it.expected == Expected.Unsat })
    }

    @Test
    fun `instance paths should be distinct names a set file can hold`() {
        assertTrue(instances.all { it.relPath.matches(Regex("[A-Za-z0-9._-]+/[A-Za-z0-9._-]+\\.cnf")) })
        assertEquals(instances.size, instances.map { it.relPath }.distinct().size)
    }
}
