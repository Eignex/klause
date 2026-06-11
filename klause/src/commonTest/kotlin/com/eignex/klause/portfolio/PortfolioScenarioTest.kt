package com.eignex.klause.portfolio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PortfolioScenarioTest {

    @Test
    fun `fixed has no scenario - it is the annotation path`() {
        assertNull(PortfolioScenario.forMode(CompetitionMode.FIXED, Kind.COP, threads = 8))
    }

    @Test
    fun `free is single-core mixed regardless of available threads`() {
        val s = PortfolioScenario.forMode(CompetitionMode.FREE, Kind.COP, threads = 8)!!
        assertEquals(1, s.threads)
        assertEquals(EngineMix.MIXED, s.engine)
    }

    @Test
    fun `parallel is a single-engine backtrack pool over the available threads`() {
        val s = PortfolioScenario.forMode(CompetitionMode.PARALLEL, Kind.CSP, threads = 8)!!
        assertEquals(8, s.threads)
        assertEquals(EngineMix.BACKTRACK, s.engine)
    }

    @Test
    fun `open is the mixed parallel pool`() {
        val s = PortfolioScenario.forMode(CompetitionMode.OPEN, Kind.COP, threads = 4)!!
        assertEquals(4, s.threads)
        assertEquals(EngineMix.MIXED, s.engine)
    }

    @Test
    fun `local-search is the LS-only parallel pool`() {
        val s = PortfolioScenario.forMode(CompetitionMode.LOCAL_SEARCH, Kind.COP, threads = 4)!!
        assertEquals(4, s.threads)
        assertEquals(EngineMix.LOCAL_SEARCH, s.engine)
    }

    @Test
    fun `kind and seed flow through to every scenario`() {
        val s = PortfolioScenario.forMode(CompetitionMode.OPEN, Kind.CSP, threads = 2, seed = 7L)!!
        assertEquals(Kind.CSP, s.kind)
        assertEquals(7L, s.seed)
    }
}
