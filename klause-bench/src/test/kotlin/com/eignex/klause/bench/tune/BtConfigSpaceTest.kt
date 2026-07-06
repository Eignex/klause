package com.eignex.klause.bench.tune

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BtConfigSpaceTest {

    @Test
    fun `sampled points decode to params and gate the lp child knob`() {
        val rng = Random(1)
        val presets = mutableSetOf<String>()
        repeat(1000) {
            val a = BtConfigSpace.sample(rng)
            presets += a["preset"] as String
            // lp.lbtree is a child of lp.emphasis != off.
            val lpOn = a["lp.emphasis"] != "off"
            assertEquals(lpOn, a.containsKey("lp.lbtree"), "lp.lbtree gated by lp.emphasis: $a")
            // Decodes without throwing; LP config presence matches the emphasis choice.
            val p = BtConfigSpace.toParams(a)
            assertEquals(!lpOn, p.lpConfig == null, "lpConfig set iff emphasis != off: $a")
        }
        assertEquals(setOf("conflictDriven", "satOptimized", "free"), presets, "all presets reachable")
    }

    @Test
    fun `decoding maps knobs onto the params`() {
        val a = mapOf<String, Any>(
            "preset" to "free",
            "var-selector" to "chb",
            "val-selector" to "min",
            "luby" to "256",
            "phase-saving" to "true",
            "adaptive-restart" to "false",
            "tiered-db" to "true",
            "max-learned" to "20000",
            "lp.emphasis" to "off",
        )
        val p = BtConfigSpace.toParams(a)
        assertEquals(256L, p.lubyRestartBase)
        assertTrue(p.phaseSaving)
        assertTrue(p.tieredLearnedDb)
        assertEquals(20000, p.maxLearnedClauses)
        assertEquals(null, p.lpConfig, "emphasis=off -> no LP")
    }
}
