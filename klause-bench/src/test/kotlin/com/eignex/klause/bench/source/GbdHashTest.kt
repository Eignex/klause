package com.eignex.klause.bench.source

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.Test
import kotlin.test.assertEquals

class GbdHashTest {

    @ParameterizedTest
    @ValueSource(strings = ["c x\np cnf 2 2\n1 -2 0\n2 0\n", "1   -2 0\r\n  2 0"])
    fun `of should hash clauses apart from comments header and layout`(text: String) {
        assertEquals("177c0b03f0c47b5f973797b2f46c901d", GbdHash.of(text.byteInputStream()))
    }

    @Test
    fun `of should close an unterminated last clause`() {
        assertEquals("a451306aa6a2be8fd7cd44dc5f9511ae", GbdHash.of("p cnf 1 1\n1".byteInputStream()))
    }
}
