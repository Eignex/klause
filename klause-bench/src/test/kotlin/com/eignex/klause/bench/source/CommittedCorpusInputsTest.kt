package com.eignex.klause.bench.source

import com.eignex.klause.bench.catalog.Category
import com.eignex.klause.bench.catalog.ExternalCollections
import com.eignex.klause.bench.catalog.Format
import com.eignex.klause.bench.lab.LabInputs
import com.eignex.lab.bench.BenchInputRoot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CommittedCorpusInputsTest {
    @Test
    fun `committed planning should resolve stored DIMACS identities without materialization`() {
        val input = CommittedCorpusInputs.planning {
            val ref = CorpusSelection.select(
                ExternalCollections.satlibUf20,
                CorpusSelection.Layout.Flat("", "cnf"),
                CorpusSelection.Selection(maxInstances = 1),
                Category.SAT,
                Format.DIMACS,
            ).single()
            LabInputs.resolve(ref).single()
        }

        assertEquals("satlib-uf20-91/uf20-01.cnf.zst", input.path)
        assertEquals(BenchInputRoot.CORPUS, input.root)
        assertEquals("63d2aa6f5a11ac02221faaca2a97e476280f2681e92fc9fb1251087c5343c9ce", input.sha256)
    }

    @Test
    fun `committed planning should reject corpus materialization`() {
        assertFailsWith<IllegalStateException> {
            CommittedCorpusInputs.planning { CorpusFetcher.ensure(ExternalCollections.satlibUf20) }
        }
    }
}
