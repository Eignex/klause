package com.eignex.klause.bench.catalog

import com.eignex.klause.solver.Problem

/**
 * Small Kotlin DSL for declaring [Suite]s. Suite-level defaults ([format], [category],
 * [license]) cascade into each `problem(...)`/`vendored(...)`/`inCode(...)` call so common
 * cases stay terse:
 *
 * ```
 * val s = suite("dimacs-core", "Curated small SAT/UNSAT CNF") {
 *     format = Format.DIMACS; license = "SATLIB (public benchmarks)"
 *     vendored("php4", Category.UNSAT, Expected.Unsat)
 *     vendored("random3sat-20-80", Category.SAT, Expected.Sat)
 * }
 * ```
 */
internal class SuiteBuilder(val id: String, val description: String) {
    /** Default format for entries that don't specify one. */
    var format: Format? = null

    /** Default category for entries that don't specify one. */
    var category: Category = Category.CSP

    /** Default license/provenance string for entries that don't specify one. */
    var license: String = "internal"

    private val problems = mutableListOf<ProblemRef>()

    /** Fully-explicit entry. */
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

    /**
     * A file vendored under `klause-bench/smoke-corpus/`. [relPath] defaults to
     * `<formatDir>/<name>.<ext>` (e.g. DIMACS → `dimacs/<name>.cnf`).
     */
    fun vendored(
        name: String,
        category: Category = this.category,
        expected: Expected = Expected.Unknown,
        relPath: String = defaultRelPath(name, requireFormat()),
        format: Format = requireFormat(),
        tags: Set<String> = emptySet(),
        license: String = this.license,
    ) = problem(name, corpus(relPath), format, category, expected, tags = tags, license = license)

    /** A MiniZinc model + optional `.dzn`, both vendored under `corpus/minizinc/`. */
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

    /** A file tracked elsewhere in the workspace (e.g. `klause-mzn-lib/test-models/`). */
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

    /** A file inside a fetched [ExternalCollection]. */
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

    /** The [index]-th file (sorted by name) with extension [ext] inside a fetched
     *  [ExternalCollection] — for collections whose member filenames aren't predictable. */
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

    /** A programmatic instance built in Kotlin. */
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

/** Build a [Suite] with the DSL. */
internal fun suite(id: String, description: String, block: SuiteBuilder.() -> Unit): Suite =
    SuiteBuilder(id, description).apply(block).build()

/** Default corpus sub-directory for a format (matches the `corpus/<dir>/` layout). */
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

/** Canonical file extension for a format. */
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
