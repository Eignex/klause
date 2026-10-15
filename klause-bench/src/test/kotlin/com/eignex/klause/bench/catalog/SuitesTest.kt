package com.eignex.klause.bench.catalog

import kotlin.test.Test
import kotlin.test.assertNotNull

class SuitesTest {

    private val largeCorpusSuites = listOf(
        "mzn-bench",
        "libminizinc-tests",
        "hakank",
        "smtlib-qflia",
        "smtlib-qflra",
        "smtlib-qfnra",
        "smtlib-qflira",
        "smtlib-qfidl",
        "smtlib-qfrdl",
        "miplib2017",
        "pb-comp",
        "pb-comp-wbo",
        "pb-comp-2025",
        "maxsat-unweighted",
        "maxsat-weighted",
    )

    @Test
    fun `every large dynamic suite should declare a per-family default`() {
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
}
