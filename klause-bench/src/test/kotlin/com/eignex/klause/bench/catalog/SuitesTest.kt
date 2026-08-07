package com.eignex.klause.bench.catalog

import com.eignex.klause.bench.source.CorpusSelection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Declared dynamic-suite metadata. Reads the catalog only; never resolves a provider, so no
 *  corpus is fetched. */
class SuitesTest {

    /** Suites over corpora big enough that resolving them uncapped would ingest thousands of live
     *  problems in one process. */
    private val largeCorpusSuites = listOf(
        "mzn-bench",
        "libminizinc-tests",
        "hakank",
        "smtlib-qflia",
        "smtlib-qflra",
        "smtlib-qflira",
        "miplib2017",
        "pb-comp",
        "pb-comp-wbo",
        "maxsat-unweighted",
        "maxsat-weighted",
    )

    @Test
    fun `every large dynamic suite declares a per-family default`() {
        val byId = Catalog.dynamicSuites.associateBy { it.id }
        for (id in largeCorpusSuites) {
            val suite = assertNotNull(byId[id], "no such dynamic suite: $id")
            assertNotNull(
                suite.defaultPerFamily,
                "dynamic suite '$id' is over a large corpus but declares no defaultPerFamily; " +
                    "an uncapped suite ingests every instance into memory and OOMs the bench",
            )
        }
    }

    @Test
    fun `a declared per-family default reaches the resolved selection`() {
        assertEquals(1, CorpusSelection.Selection.fromProps(defaultPerFamily = 1).perFamily)
    }

    @Test
    fun `an undeclared per-family default leaves the selection uncapped`() {
        assertNull(CorpusSelection.Selection.fromProps(defaultPerFamily = null).perFamily)
    }
}
