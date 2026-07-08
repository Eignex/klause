package com.eignex.klause.bench.tune

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UnifiedConfigSpaceTest {

    @Test
    fun `a pinned ls sample carries only ls params and decodes to an Ls config`() {
        val a = UnifiedConfigSpace.samplePinned("ls", Random(1))
        assertEquals("ls", a["engine"])
        assertTrue("family" in a, "an ls sample carries the LS family param")
        assertTrue("preset" !in a, "an ls sample carries no BT params")
        assertTrue(UnifiedConfigSpace.decode(UnifiedConfigSpace.coerce(a)) is EngineConfig.Ls)
    }

    @Test
    fun `a pinned bt sample carries only bt params and decodes to a Bt config`() {
        val a = UnifiedConfigSpace.samplePinned("bt", Random(1))
        assertEquals("bt", a["engine"])
        assertTrue("preset" in a, "a bt sample carries the BT preset param")
        assertTrue("family" !in a, "a bt sample carries no LS params")
        assertTrue(UnifiedConfigSpace.decode(UnifiedConfigSpace.coerce(a)) is EngineConfig.Bt)
    }

    @Test
    fun `the flat param list is engine plus every ls and bt param, no name collisions`() {
        val names = UnifiedConfigSpace.params.map { it.name }
        assertEquals("engine", names.first())
        assertTrue("family" in names && "preset" in names, "both sub-spaces' params are declared flat")
        assertEquals(names.size, names.toSet().size, "no param-name collides across the two engines")
    }
}
