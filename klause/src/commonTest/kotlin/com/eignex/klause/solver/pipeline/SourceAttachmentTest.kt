package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.DefinitionHint
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.SearchGroup
import com.eignex.klause.ir.SearchHints
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame

class SourceAttachmentTest {
    @Test
    fun `an attachment should retain its original source interpretation`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(0, 2)), emptyArray())

        val attachment = SourceAttachment(problem)

        assertSame(problem, attachment.originalSource)
        assertSame(problem.semantics, attachment.semantics)
    }

    @Test
    fun `an attachment should own its definition and search intent containers`() {
        val problem = Problem(1, 2, Array(2) { IntDomain(0, 2) }, emptyArray())
        val definitions = mutableListOf<DefinitionHint>(DefinitionHint.IntegerReference(1))
        val booleans = intArrayOf(0)
        val integers = intArrayOf(1, 0)
        val groups = mutableListOf(SearchGroup(booleans, integers))
        val attachment = SourceAttachment(problem, definitions, SearchHints(groups))
        definitions.clear()
        groups.clear()
        booleans[0] = 8
        integers[0] = 8
        val exposed = assertNotNull(attachment.searchHints)
        exposed.tiers.single().intVars[0] = 7

        val captured = assertNotNull(attachment.searchHints)

        assertEquals(1, attachment.definitionHints.single().output)
        assertEquals(listOf(0), captured.tiers.single().boolVars.toList())
        assertEquals(listOf(1, 0), captured.tiers.single().intVars.toList())
    }
}
