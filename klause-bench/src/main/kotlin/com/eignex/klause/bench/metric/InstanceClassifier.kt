package com.eignex.klause.bench.metric

import com.eignex.klause.bench.catalog.Format
import com.eignex.klause.bench.catalog.ProblemRef
import com.eignex.klause.bench.source.CorpusFetcher

/**
 * Source-text structural features of an instance — filled by [InstanceClassifier] from the raw source
 * (no klause compile, so a whole-corpus pass stays flat in memory). [structure] is the coarse niche
 * class the arm catalog specialises on (globals → CBLS/structured moves, pseudo-boolean/sat → probSAT,
 * linear → LP arms); the counts make it analysable rather than just a label.
 */
internal data class InstanceFeatures(
    val format: String,
    val structure: String,
    val numGlobal: Int,
    val numLinear: Int,
    val boolHeavy: Boolean,
    /** The instance's theory/logic where the format names one directly: an SMT-LIB `(set-logic …)`
     *  (`QF_LIA`, `QF_NRA`, …) or an MPS integrality class (`MIP` has an `INTORG` marker, else `LP`).
     *  Blank where the format has no such single-axis classification (MiniZinc/XCSP3/OPB/DIMACS use
     *  [structure] instead). */
    val logic: String = "",
)

/**
 * Classifies an instance from its raw source text — a fast, memory-flat heuristic (regex counts of
 * declarations / constraint keywords), never building the klause `Problem`. Approximate by design: it
 * feeds the stratified sampler and analysis, not the solver.
 */
internal object InstanceClassifier {
    /** MiniZinc/FlatZinc global-constraint stems (either the `.mzn` name or a flattened `_int` form). */
    private val MZN_GLOBALS = listOf(
        "all_different", "alldifferent", "all_equal", "cumulative", "circuit", "subcircuit", "table",
        "element", "global_cardinality", "count", "among", "nvalue", "inverse", "regular", "bin_packing",
        "diffn", "disjunctive", "lex_less", "lex_lesseq", "value_precede", "knapsack", "sort",
        "arg_max", "arg_min", "maximum", "minimum", "network_flow",
    )

    /** XCSP3 global-constraint element names. */
    private val XCSP_GLOBALS = listOf(
        "allDifferent", "allEqual", "cumulative", "circuit", "element", "extension", "count", "nValues",
        "cardinality", "regular", "mdd", "noOverlap", "binPacking", "channel", "ordered", "lex",
        "maximum", "minimum", "knapsack",
    )

    /** Source-text features for [ref], or null if the source can't be read. */
    fun classify(ref: ProblemRef): InstanceFeatures? =
        runCatching { fromSource(ref.format, CorpusFetcher.resolve(ref.source).readText()) }.getOrNull()

    /** The format-specific heuristic over the raw source [text] (the testable core of [classify]). */
    fun fromSource(format: Format, text: String): InstanceFeatures {
        val fmt = format.name.lowercase()
        return when (format) {
            Format.MINIZINC -> minizinc(fmt, text)
            Format.XCSP3 -> xcsp3(fmt, text)
            Format.OPB -> pseudoBoolean(fmt, text)
            Format.DIMACS -> sat(fmt, text)
            Format.SMTLIB -> smtlib(fmt, text)
            Format.MPS -> mps(fmt, text)
            else -> InstanceFeatures(fmt, "arithmetic", 0, 0, boolHeavy = false)
        }
    }

    private fun countWords(text: String, words: List<String>): Int =
        words.sumOf { w -> Regex("\\b${Regex.escape(w)}\\b").findAll(text).count() }

    private fun minizinc(fmt: String, text: String): InstanceFeatures {
        val numGlobal = countWords(text, MZN_GLOBALS)
        val numLinear = Regex("\\b(int|bool)_lin_(eq|le|ne)\\b").findAll(text).count() +
            Regex("\\bsum\\s*\\(").findAll(text).count()
        val boolDecls = Regex("var\\s+bool").findAll(text).count()
        val intDecls = Regex("var\\s+(int|\\d|-|\\{|[a-zA-Z_]+\\s*\\.\\.)").findAll(text).count()
        val boolHeavy = boolDecls > intDecls
        val structure = when {
            numGlobal > 0 -> "global"
            boolHeavy -> "pseudo-boolean"
            numLinear > 0 -> "linear"
            else -> "arithmetic"
        }
        return InstanceFeatures(fmt, structure, numGlobal, numLinear, boolHeavy)
    }

    private fun xcsp3(fmt: String, text: String): InstanceFeatures {
        val numGlobal = XCSP_GLOBALS.sumOf { g -> Regex("<$g\\b").findAll(text).count() }
        val numLinear = Regex("<sum\\b").findAll(text).count() + Regex("<intension\\b").findAll(text).count()
        val boolDomains = Regex("\\b0\\.\\.1\\b").findAll(text).count()
        val varDecls = Regex("<(var|array)\\b").findAll(text).count().coerceAtLeast(1)
        val boolHeavy = boolDomains * 2 > varDecls
        val structure = when {
            numGlobal > 0 -> "global"
            numLinear > 0 -> "linear"
            else -> "arithmetic"
        }
        return InstanceFeatures(fmt, structure, numGlobal, numLinear, boolHeavy)
    }

    private fun pseudoBoolean(fmt: String, text: String): InstanceFeatures {
        val constraints = text.lineSequence().count {
            val t = it.trim()
            t.endsWith(";") && !t.startsWith("*")
        }
        return InstanceFeatures(fmt, "pseudo-boolean", 0, constraints, boolHeavy = true)
    }

    private fun sat(fmt: String, text: String): InstanceFeatures {
        val clauses = text.lineSequence().count {
            val t = it.trim()
            t.endsWith(" 0") && !t.startsWith("c")
        }
        return InstanceFeatures(fmt, "sat", 0, clauses, boolHeavy = true)
    }

    /** The declared `(set-logic X)` names the SMT theory directly — the axis the SMT-LIB competition
     *  itself organizes benchmarks by, so it is reported as [InstanceFeatures.logic] verbatim rather
     *  than folded into [InstanceFeatures.structure]. */
    private fun smtlib(fmt: String, text: String): InstanceFeatures {
        val logic = Regex("""\(set-logic\s+([A-Za-z0-9_]+)\)""").find(text)?.groupValues?.get(1).orEmpty()
        val asserts = Regex("""\(assert\b""").findAll(text).count()
        return InstanceFeatures(fmt, "arithmetic", 0, asserts, boolHeavy = false, logic = logic)
    }

    /** An `INTORG` marker section means the model has integer columns (a MIP); its absence means every
     *  column is continuous (an LP). Reported as [InstanceFeatures.logic] since MPS has no other native
     *  theory/structure axis. */
    private fun mps(fmt: String, text: String): InstanceFeatures {
        val logic = if (Regex("""\bINTORG\b""").containsMatchIn(text)) "MIP" else "LP"
        return InstanceFeatures(fmt, "arithmetic", 0, 0, boolHeavy = false, logic = logic)
    }
}
