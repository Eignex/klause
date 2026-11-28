package com.eignex.klause.bench.catalog

import com.eignex.klause.bench.source.CommittedCorpusIndexes
import org.junit.jupiter.api.BeforeAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SuitesTest {
    companion object {
        private lateinit var byId: Map<String, DynamicSuite>

        @BeforeAll
        @JvmStatic
        fun initializeCatalog() {
            byId = Catalog.dynamicSuites.associateBy { it.id }
        }
    }

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
    fun `a ground SMT sweep source has committed identities and capped catalog discovery`() {
        val logic = "QF_UFLIA"
        val id = "smtlib-${logic.lowercase().replace("_", "")}"
        val suite = byId.getValue(id)
        val layout = assertNotNull(suite.sourceLayout)
        val index = assertNotNull(CommittedCorpusIndexes.load(layout.collection.id))
        val cases = ProblemSets.load(listOf("smt-ground-v1")).filter { it.suite == id }

        assertEquals(1, suite.defaultPerFamily)
        assertTrue(cases.isNotEmpty(), "no sweep cases for $logic")
        for (case in cases) {
            assertNotNull(index.entry("non-incremental/$logic/${case.problem}.smt2"), case.problem)
        }
    }
}
