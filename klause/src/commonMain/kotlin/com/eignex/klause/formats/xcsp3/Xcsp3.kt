package com.eignex.klause.formats.xcsp3

import com.eignex.klause.formats.FormatException
import com.eignex.klause.ir.LinearObjectiveSpec
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.Problem
import com.eignex.klause.util.CharSource
import com.eignex.klause.util.StringCharSource
import com.eignex.klause.lowering.xcsp3.Xcsp3 as Xcsp3Lowering

/** Raised when an XCSP3 construct outside the supported subset is encountered. */
class UnsupportedXcsp3Exception(msg: String) : FormatException("XCSP3", msg)

/** An XCSP3 source decoded and lowered into klause's representation. */
data class Xcsp3Problem(
    /** Compiled solver problem. */
    val problem: Problem,
    /** Objective, or null for satisfaction instances. */
    val objective: LinearObjectiveSpec?,
    /** Declared variable name to int var id. */
    val intVarNames: Map<String, Int> = emptyMap(),
    /** The objective's optimisation sense (minimise for satisfaction instances, which have none). */
    val sense: ObjectiveSense = ObjectiveSense.MINIMIZE,
    /** Int vars the front-end knows are functionally defined (sound local-search `defines_var` hints). */
    val definedVars: IntArray = IntArray(0),
)

/** XCSP3 integer format facade. */
object Xcsp3 {
    /** Decode and lower XCSP3 [text]. */
    fun parse(text: String): Xcsp3Problem = parse(StringCharSource(text))

    /** Decode and lower a streamed XCSP3 [source], retaining only bounded subtrees. */
    @Suppress("SwallowedException")
    fun parse(source: CharSource): Xcsp3Problem = try {
        Xcsp3Lowering.Builder().run {
            val reader = XmlReader(source)
            reader.openRoot()
            // Single forward pass over `<instance>`'s children keeps the container order and tolerates
            // absent sections without rewinding the streaming reader.
            while (true) {
                when (reader.nextChildTag() ?: break) {
                    "variables" -> if (reader.enterPeeked()) {
                        while (reader.nextChildTag() != null) declareVar(reader.materializeChild())
                    }

                    "constraints" -> if (reader.enterPeeked()) streamConstraints(reader)

                    // Only the first objective is taken; drain the rest to keep the outer cursor aligned.
                    "objectives" -> if (reader.enterPeeked()) {
                        var first = true
                        while (reader.nextChildTag() != null) {
                            if (first) objective(reader.materializeChild()) else reader.materializeChild()
                            first = false
                        }
                    }

                    else -> reader.materializeChild()
                }
            }
            build()
        }
    } catch (e: IllegalArgumentException) {
        // XML, expression, and tuple scanners use require; numeric reads may throw the same base type.
        throw UnsupportedXcsp3Exception(e.message ?: "malformed XCSP3 document")
    }
}
