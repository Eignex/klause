package com.eignex.klause.formats.xcsp3

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class XmlElementTest {

    @Test
    fun `parses nested elements and attributes and text content`() {
        val root = parseXml(
            """<instance format="XCSP3" type="CSP">
                 <variables><var id="x"> 1..4 </var></variables>
               </instance>""",
        )
        assertEquals("instance", root.tag)
        assertEquals("XCSP3", root.attr("format"))
        assertEquals("CSP", root.attr("type"))
        val vars = root.child("variables")!!
        val v = vars.child("var")!!
        assertEquals("x", v.attr("id"))
        assertEquals("1..4", v.textContent.trim())
    }

    @Test
    fun `handles single and double quoted attributes`() {
        val e = parseXml("""<e a='1' b="2"/>""")
        assertEquals("1", e.attr("a"))
        assertEquals("2", e.attr("b"))
        assertTrue(e.children.isEmpty())
    }

    @Test
    fun `self-closing elements have no children or text`() {
        val root = parseXml("<root><a/><b>hi</b></root>")
        assertEquals(2, root.children.size)
        assertEquals("", root.child("a")!!.textContent)
        assertEquals("hi", root.child("b")!!.textContent)
    }

    @Test
    fun `skips prolog and doctype and comments`() {
        val root = parseXml(
            """<?xml version="1.0" encoding="UTF-8"?>
               <!DOCTYPE instance>
               <!-- a comment -->
               <r><!-- inner --><x>1</x></r>""",
        )
        assertEquals("r", root.tag)
        assertEquals("1", root.child("x")!!.textContent)
    }

    @Test
    fun `decodes predefined entities in text and attributes`() {
        val e = parseXml("""<e note="a &lt; b &amp; c">x &gt; 0 &apos;ok&apos;</e>""")
        assertEquals("a < b & c", e.attr("note"))
        assertEquals("x > 0 'ok'", e.textContent)
    }

    @Test
    fun `reads CDATA verbatim`() {
        val e = parseXml("<e><![CDATA[ raw < & > text ]]></e>")
        assertEquals(" raw < & > text ", e.textContent)
    }

    @Test
    fun `textContent concatenates descendant text`() {
        val root = parseXml("<sum><list> a b </list><coeffs> 1 2 </coeffs></sum>")
        assertEquals("a b", root.child("list")!!.textContent.trim())
        assertEquals("1 2", root.child("coeffs")!!.textContent.trim())
    }

    @Test
    fun `missing child returns null`() {
        assertNull(parseXml("<r><a/></r>").child("missing"))
    }

    @Test
    fun `substituteParams expands placeholders and reuses percent-free text verbatim`() {
        val template = parseXml("<t><a>%0 %1</a><b>no placeholder</b></t>")
        val out = template.substituteParams(listOf("x", "y"))
        assertEquals("x y", out.child("a")!!.textContent)
        assertEquals("no placeholder", out.child("b")!!.textContent)
    }

    @Test
    fun `rejects a mismatched closing tag`() {
        val e = assertFailsWith<IllegalArgumentException> { parseXml("<a><b>1</c></a>") }
        assertTrue("mismatched closing tag" in e.message.orEmpty(), e.message.orEmpty())
    }
}
