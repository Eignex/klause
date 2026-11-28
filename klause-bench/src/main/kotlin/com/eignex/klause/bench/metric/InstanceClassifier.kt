package com.eignex.klause.bench.metric

import com.eignex.klause.bench.catalog.Format
import com.eignex.klause.bench.catalog.ProblemRef
import com.eignex.klause.bench.source.CorpusFetcher
import com.eignex.klause.bench.source.CorpusFiles

// Read from the raw source without compiling, so a whole-corpus pass stays flat in memory.
internal data class InstanceFeatures(
    val format: String,
    val structure: String,
    val numGlobal: Int,
    val numLinear: Int,
    val boolHeavy: Boolean,
    // An SMT-LIB `(set-logic …)` or the MPS integrality class (`MIP`/`LP`); blank for formats classified by structure.
    val logic: String = "",
    // Named problem sets are drawn per theme.
    val themes: Set<String> = emptySet(),
)

// Approximate by design (regex counts, never a klause `Problem`): it feeds `select features=true` and set drawing,
// not the solver.
internal object InstanceClassifier {
    // Either the `.mzn` name or a flattened `_int` form.
    private val MZN_GLOBALS = listOf(
        "all_different", "alldifferent", "all_equal", "cumulative", "circuit", "subcircuit", "table",
        "element", "global_cardinality", "count", "among", "nvalue", "inverse", "regular", "bin_packing",
        "diffn", "disjunctive", "lex_less", "lex_lesseq", "value_precede", "knapsack", "sort",
        "arg_max", "arg_min", "maximum", "minimum", "network_flow",
    )

    private val XCSP_GLOBALS = listOf(
        "allDifferent", "allEqual", "cumulative", "circuit", "element", "extension", "count", "nValues",
        "cardinality", "regular", "mdd", "noOverlap", "binPacking", "channel", "ordered", "lex",
        "maximum", "minimum", "knapsack",
    )

    // open-int: integer variables without a finite domain. linear-real / nonlinear-real: real variables under linear /
    // polynomial constraints. mip: MPS with integer columns. scheduling, routing, packing: a matching global; globals:
    // any other global. nonlinear-int, arrays, bitvectors, datatypes, free-functions: declared SMT theories.
    val THEMES =
        listOf(
            "open-int", "linear-real", "nonlinear-real", "mip", "scheduling", "routing", "packing", "globals", "sat",
            "maxsat", "pb", "nonlinear-int", "arrays", "bitvectors", "datatypes", "free-functions",
        )

    // `globals` is for an instance with globals but none of these.
    private val STRUCTURED = setOf("scheduling", "routing", "packing")
    private val MZN_SCHEDULING = listOf("cumulative", "disjunctive", "unary", "no_overlap")
    private val MZN_ROUTING = listOf("circuit", "subcircuit")
    private val MZN_PACKING = listOf("bin_packing", "bin_packing_capa", "bin_packing_load", "knapsack", "diffn")
    private val XCSP_SCHEDULING = listOf("cumulative", "noOverlap")
    private val XCSP_ROUTING = listOf("circuit")
    private val XCSP_PACKING = listOf("binPacking", "knapsack")

    fun classify(ref: ProblemRef): InstanceFeatures? =
        runCatching { fromSource(ref.format, CorpusFiles.readText(CorpusFetcher.resolve(ref.source))) }.getOrNull()

    fun fromSource(format: Format, text: String): InstanceFeatures {
        val fmt = format.name.lowercase()
        val features = when (format) {
            Format.MINIZINC -> minizinc(fmt, text)
            Format.XCSP3 -> xcsp3(fmt, text)
            Format.OPB -> pseudoBoolean(fmt, text)
            Format.DIMACS -> sat(fmt, text)
            Format.SMTLIB -> smtlib(fmt, text)
            Format.MPS -> mps(fmt, text)
            Format.WCNF -> InstanceFeatures(fmt, "sat", 0, 0, boolHeavy = true)
            else -> InstanceFeatures(fmt, "arithmetic", 0, 0, boolHeavy = false)
        }
        return features.copy(themes = themes(format, text, features))
    }

    private fun themes(format: Format, text: String, features: InstanceFeatures): Set<String> = buildSet {
        fun uses(words: List<String>) = words.any { Regex("\\b${Regex.escape(it)}\\b").containsMatchIn(text) }
        fun usesElement(names: List<String>) = names.any { Regex("<$it\\b").containsMatchIn(text) }
        when (format) {
            Format.MINIZINC -> {
                if (Regex("""\bvar\s+int\b""").containsMatchIn(text)) add("open-int")
                if (Regex("""\bvar\s+(float\b|-?\d+\.\d+\s*\.\.)""").containsMatchIn(text)) add("linear-real")
                if (uses(MZN_SCHEDULING)) add("scheduling")
                if (uses(MZN_ROUTING)) add("routing")
                if (uses(MZN_PACKING)) add("packing")
                if (features.numGlobal > 0 && none { it in STRUCTURED }) add("globals")
            }

            Format.XCSP3 -> {
                if (usesElement(XCSP_SCHEDULING)) add("scheduling")
                if (usesElement(XCSP_ROUTING)) add("routing")
                if (usesElement(XCSP_PACKING)) add("packing")
                if (features.numGlobal > 0 && none { it in STRUCTURED }) add("globals")
            }

            Format.SMTLIB -> addAll(smtThemes(features.logic))

            Format.MPS -> {
                if (features.logic == "MIP") add("mip")
                if (hasContinuousColumns(text)) add("linear-real")
            }

            Format.DIMACS -> add("sat")

            Format.WCNF -> add("maxsat")

            Format.OPB -> add("pb")

            else -> Unit
        }
    }

    fun smtThemes(logic: String): Set<String> = buildSet {
        if (!logic.startsWith("QF_")) return@buildSet
        val theory = logic.removePrefix("QF_")
        if (listOf("LIA", "IDL", "LIRA", "NIA", "NIRA").any(theory::endsWith)) add("open-int")
        if (listOf("LRA", "RDL", "LIRA").any(theory::endsWith)) add("linear-real")
        if (listOf("NIA", "NIRA").any(theory::endsWith)) add("nonlinear-int")
        if (listOf("NRA", "NIRA").any(theory::endsWith)) add("nonlinear-real")
        if (theory.startsWith("A") && theory != "ALL") add("arrays")
        if ("BV" in theory) add("bitvectors")
        if ("DT" in theory) add("datatypes")
        if ("UF" in theory) add("free-functions")
    }

    private fun hasContinuousColumns(text: String): Boolean {
        var inColumns = false
        var integer = false
        for (line in text.lineSequence()) {
            if (line.isEmpty() || line.startsWith("*")) continue
            if (!line[0].isWhitespace()) {
                inColumns = line.startsWith("COLUMNS")
                continue
            }
            if (!inColumns) continue
            when {
                "'INTORG'" in line -> integer = true
                "'INTEND'" in line -> integer = false
                !integer -> return true
            }
        }
        return false
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

    private fun smtlib(fmt: String, text: String): InstanceFeatures {
        val logic = Regex("""\(set-logic\s+([A-Za-z0-9_]+)\)""").find(text)?.groupValues?.get(1).orEmpty()
        val asserts = Regex("""\(assert\b""").findAll(text).count()
        return InstanceFeatures(fmt, "arithmetic", 0, asserts, boolHeavy = false, logic = logic)
    }

    private fun mps(fmt: String, text: String): InstanceFeatures {
        val logic = if (Regex("""\bINTORG\b""").containsMatchIn(text)) "MIP" else "LP"
        return InstanceFeatures(fmt, "arithmetic", 0, 0, boolHeavy = false, logic = logic)
    }
}
