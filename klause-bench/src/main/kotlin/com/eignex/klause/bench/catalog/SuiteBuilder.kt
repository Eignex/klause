package com.eignex.klause.bench.catalog

import com.eignex.klause.ir.Problem

// Suite-level defaults (format, category, license) cascade into each entry.
internal class SuiteBuilder(val id: String, val description: String) {
    var format: Format? = null

    var category: Category = Category.CSP

    var license: String = "internal"

    private val problems = mutableListOf<ProblemRef>()

    fun problem(
        name: String,
        source: ProblemSource,
        format: Format = requireFormat(),
        category: Category = this.category,
        expected: Expected = Expected.Unknown,
        data: ProblemSource? = null,
        tags: Set<String> = emptySet(),
        license: String = this.license,
    ) {
        problems += ProblemRef(name, format, source, category, expected, data, tags, license)
    }

    // Under `klause-bench/smoke-corpus/`; relPath defaults to `<formatDir>/<name>.<ext>`.
    fun vendored(
        name: String,
        category: Category = this.category,
        expected: Expected = Expected.Unknown,
        relPath: String = defaultRelPath(name, requireFormat()),
        format: Format = requireFormat(),
        tags: Set<String> = emptySet(),
        license: String = this.license,
    ) = problem(name, corpus(relPath), format, category, expected, tags = tags, license = license)

    fun vendoredMzn(
        name: String,
        category: Category = this.category,
        expected: Expected = Expected.Unknown,
        model: String = "minizinc/$name.mzn",
        data: String? = null,
        tags: Set<String> = emptySet(),
        license: String = this.license,
    ) = problem(
        name,
        corpus(model),
        Format.MINIZINC,
        category,
        expected,
        data = data?.let { corpus(it) },
        tags = tags,
        license = license,
    )

    fun workspace(
        name: String,
        workspaceRelPath: String,
        category: Category = this.category,
        expected: Expected = Expected.Unknown,
        format: Format = requireFormat(),
        data: ProblemSource? = null,
        tags: Set<String> = emptySet(),
        license: String = this.license,
    ) = problem(name, ProblemSource.Vendored(workspaceRelPath), format, category, expected, data, tags, license)

    fun external(
        name: String,
        collection: ExternalCollection,
        relPath: String,
        category: Category = this.category,
        expected: Expected = Expected.Unknown,
        format: Format = requireFormat(),
        data: ProblemSource? = null,
        tags: Set<String> = emptySet(),
    ) = problem(
        name,
        ProblemSource.External(collection, relPath),
        format,
        category,
        expected,
        data = data,
        tags = tags,
        license = collection.license,
    )

    fun externalIndexed(
        name: String,
        collection: ExternalCollection,
        index: Int,
        ext: String,
        category: Category = this.category,
        expected: Expected = Expected.Unknown,
        format: Format = requireFormat(),
        tags: Set<String> = emptySet(),
    ) = problem(
        name,
        ProblemSource.ExternalIndexed(collection, index, ext),
        format,
        category,
        expected,
        tags = tags,
        license = collection.license,
    )

    fun inCode(
        name: String,
        category: Category = this.category,
        expected: Expected = Expected.Unknown,
        tags: Set<String> = emptySet(),
        build: () -> Problem,
    ) = problem(name, ProblemSource.InCode(build), Format.IN_CODE, category, expected, tags = tags)

    private fun requireFormat(): Format = format ?: error("suite '$id': set a default `format` or pass one per problem")

    private fun defaultRelPath(name: String, fmt: Format): String = "${fmt.dir}/$name.${fmt.ext}"

    internal fun build(): Suite = Suite(id, description, problems.toList())
}

internal fun suite(id: String, description: String, block: SuiteBuilder.() -> Unit): Suite =
    SuiteBuilder(id, description).apply(block).build()

/** The `klause-bench/smoke-corpus/` subdirectory for this format. */
val Format.dir: String
    get() = when (this) {
        Format.DIMACS -> "dimacs"
        Format.WCNF -> "dimacs"
        Format.OPB -> "opb"
        Format.JSON_SCHEMA -> "schema"
        Format.MINIZINC -> "minizinc"
        Format.XCSP3 -> "xcsp3"
        Format.SMTLIB -> "smtlib"
        Format.MPS -> "mps"
        Format.IN_CODE -> error("IN_CODE has no corpus directory")
    }

/** The canonical file extension of this format. */
val Format.ext: String
    get() = when (this) {
        Format.DIMACS -> "cnf"
        Format.WCNF -> "wcnf"
        Format.OPB -> "opb"
        Format.JSON_SCHEMA -> "json"
        Format.MINIZINC -> "mzn"
        Format.XCSP3 -> "xml"
        Format.SMTLIB -> "smt2"
        Format.MPS -> "mps"
        Format.IN_CODE -> error("IN_CODE has no extension")
    }
