package com.eignex.klause.formats.opb

import com.eignex.klause.formats.FormatException
import com.eignex.klause.lowering.opb.OpbDocument
import com.eignex.klause.util.CharSource
import com.eignex.klause.util.StringCharSource

/** Raised when an OPB/WBO document is malformed, so a caller can catch it via [FormatException] like
 *  the other input formats. */
class OpbFormatException(msg: String) : FormatException("OPB", msg)

/** OPB/WBO format facade. It parses external syntax into an immutable source document. */
object Opb {

    /** Parse OPB [text] into an [OpbDocument]. */
    fun parse(text: String): OpbDocument = parse(StringCharSource(text))

    /** Parse an OPB [source] into an [OpbDocument], consuming it line by line. */
    fun parse(source: CharSource): OpbDocument = OpbSyntax.parse(source)
}
