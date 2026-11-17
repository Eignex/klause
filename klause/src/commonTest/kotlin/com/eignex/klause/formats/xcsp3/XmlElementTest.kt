package com.eignex.klause.formats.xcsp3

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class XmlElementTest {

    @Test
    fun `predefined entities should decode in text and attributes`() {
        val e = parseXml("""<e note="a &lt; b &amp; c">x &gt; 0 &apos;ok&apos;</e>""")
        assertEquals("a < b & c", e.attr("note"))
        assertEquals("x > 0 'ok'", e.textContent)
    }

    @Test
    fun `CDATA should be read verbatim`() {
        val e = parseXml("<e><![CDATA[ raw < & > text ]]></e>")
        assertEquals(" raw < & > text ", e.textContent)
    }

    @Test
    fun `substituteParams should expand placeholders and reuse percent-free text verbatim`() {
        val template = parseXml("<t><a>%0 %1</a><b>no placeholder</b></t>")
        val out = template.substituteParams(listOf("x", "y"))
        assertEquals("x y", out.child("a")!!.textContent)
        assertEquals("no placeholder", out.child("b")!!.textContent)
    }

    @Test
    fun `a mismatched closing tag should be rejected`() {
        val e = assertFailsWith<IllegalArgumentException> { parseXml("<a><b>1</c></a>") }
        assertTrue("mismatched closing tag" in e.message.orEmpty(), e.message.orEmpty())
    }

    @Test
    fun `content after the root element should be rejected`() {
        val e = assertFailsWith<IllegalArgumentException> { parseXml("<a/> trailing") }
        assertTrue("after root" in e.message.orEmpty(), e.message.orEmpty())
    }

    @Test
    fun `unknown entity references should be rejected`() {
        val e = assertFailsWith<IllegalArgumentException> { parseXml("<a>&unknown;</a>") }
        assertTrue("unknown entity" in e.message.orEmpty(), e.message.orEmpty())
    }
}
