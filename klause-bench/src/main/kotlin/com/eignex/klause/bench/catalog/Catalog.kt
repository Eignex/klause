package com.eignex.klause.bench.catalog

import com.eignex.klause.solver.Problem

/*
 * The bench problem catalog — a declarative, in-code registry of problems grouped into
 * named [Suite]s by [Category]. It is the single source of truth for "which problems are
 * in my bench"; nothing else discovers instances by walking arbitrary directories.
 *
 * The four orthogonal axes the bench separates are: **format** ([Format] — how an instance
 * is encoded), **source** ([ProblemSource] — where the bytes come from), **solver**
 * (`com.eignex.klause.bench.solver`), and **runner** (`com.eignex.klause.bench.runner`).
 * A [ProblemRef] binds the first two; targets bind the rest.
 */

/** Encoding of a problem instance. Drives which parser / pipeline turns it into a [Problem]. */
enum class Format {
    /** DIMACS CNF (SAT). In-process via `Dimacs.parse`. */
    DIMACS,

    /** Pseudo-Boolean OPB. In-process via `Opb.parse`. */
    OPB,

    /** klause JSON `SchemaDef`. In-process via `JsonSchema.parseProblem`. */
    JSON_SCHEMA,

    /** FlatZinc. In-process via `parseFlatZinc`. */
    FLATZINC,

    /** MiniZinc model (+ optional `.dzn`). Compiled to FlatZinc by the `minizinc` CLI, then
     *  parsed in-process. See `runner.MiniZincRunner`. */
    MINIZINC,

    /** XCSP3 instance. Net-new ingest (phase 3). */
    XCSP3,

    /** SMT-LIB QF_LIA script. Net-new ingest (phase 3). */
    SMTLIB_QF_LIA,

    /** Programmatic — the instance is built directly in Kotlin, no file. */
    IN_CODE,
}

/** Coarse problem family, used to compose and filter suites. */
internal enum class Category {
    SAT,
    UNSAT,
    CSP,
    SCHEDULING,
    PACKING,
    ROUTING,
    ASSIGNMENT,
    OPTIMIZATION,
}

/** Known/expected outcome for an instance — the oracle the bench checks solvers against. */
sealed interface Expected {
    /** A satisfying assignment exists (satisfaction problem). */
    data object Sat : Expected

    /** Proven infeasible. */
    data object Unsat : Expected

    /** Optimization instance with a known optimal objective [value] (minimization). */
    data class Opt(val value: Long) : Expected

    /** Feasible/optimal status not recorded. */
    data object Unknown : Expected

    /** Whether a *feasible* assignment is expected to exist (true for [Sat] and [Opt]). */
    val expectsSat: Boolean get() = this is Sat || this is Opt
}

/** How an [ExternalCollection] is materialized into the local cache. */
internal sealed interface FetchMethod {
    /** Shallow git clone of [url]. [sparsePath], when set, restricts checkout to that subtree. */
    data class GitClone(val depth: Int = 1, val sparsePath: String? = null) : FetchMethod

    /** Download a `.tar.gz` from [url] and extract it. */
    data object Tarball : FetchMethod

    /** Download a `.zip` from [url] and extract it (instances may stay individually
     *  compressed inside, e.g. XCSP3 `*.xml.lzma`). */
    data object Zip : FetchMethod
}

/**
 * A non-vendored problem collection fetched on demand. Recording [license] + [reason] keeps
 * the "why isn't this in the repo" decision auditable rather than implicit in a build script.
 */
internal data class ExternalCollection(
    val id: String,
    val url: String,
    val license: String,
    val reason: String,
    val fetch: FetchMethod,
)

/** Where a problem's bytes come from. Resolved to a concrete file by `source.CorpusFetcher`. */
internal sealed interface ProblemSource {
    /** A file tracked in this repository, path relative to the workspace root. Use [corpus]
     *  for the common case of a file under `klause-bench/corpus/`. */
    data class Vendored(val workspaceRelPath: String) : ProblemSource

    /** Built directly in Kotlin — no file, no parsing. */
    data class InCode(val build: () -> Problem) : ProblemSource

    /** A file inside an [ExternalCollection] that is fetched (cloned/downloaded) on first
     *  use into a cache. [relPath] is relative to the collection's root. */
    data class External(val collection: ExternalCollection, val relPath: String) : ProblemSource
}

/** Convenience: a vendored file under `klause-bench/corpus/<relPath>`. */
internal fun corpus(relPath: String): ProblemSource.Vendored = ProblemSource.Vendored("klause-bench/corpus/$relPath")

/**
 * One catalog entry: a named instance in a [Format], sourced from a [ProblemSource], tagged
 * with a [Category] and its [Expected] oracle. [data] is an optional companion source (a
 * `.dzn` for MiniZinc). [license] records provenance so vendoring decisions stay auditable.
 */
internal data class ProblemRef(
    val name: String,
    val format: Format,
    val source: ProblemSource,
    val category: Category,
    val expected: Expected,
    val data: ProblemSource? = null,
    val tags: Set<String> = emptySet(),
    val license: String = "internal",
)

/** A named, described collection of [ProblemRef]s. */
internal data class Suite(val id: String, val description: String, val problems: List<ProblemRef>)

/**
 * A suite whose problems are *discovered* on demand (e.g. an external corpus selected from a
 * fetched cache) rather than listed statically. The [provider] is invoked only when the suite
 * is actually resolved — listing shows [id]/[description] without triggering a fetch.
 */
internal data class DynamicSuite(val id: String, val description: String, val provider: () -> List<ProblemRef>)

/** The registry. Static [suites] + [dynamicSuites] are assembled in [Suites]; lookups are by
 *  id / category / tag. */
internal object Catalog {
    val suites: List<Suite> get() = Suites.all
    val dynamicSuites: List<DynamicSuite> get() = Suites.dynamic

    /** All suite ids (static + dynamic), for listings and selection. */
    val suiteIds: List<String> get() = suites.map { it.id } + dynamicSuites.map { it.id }

    fun suite(id: String): Suite {
        suites.firstOrNull { it.id == id }?.let { return it }
        dynamicSuites.firstOrNull { it.id == id }?.let { return Suite(it.id, it.description, it.provider()) }
        error("no such suite: $id (have $suiteIds)")
    }

    fun problems(vararg suiteIds: String): List<ProblemRef> =
        (if (suiteIds.isEmpty()) suites.map { it.id } else suiteIds.toList()).flatMap { suite(it).problems }

    fun byCategory(category: Category): List<ProblemRef> =
        suites.flatMap { it.problems }.filter { it.category == category }

    fun byTag(tag: String): List<ProblemRef> = suites.flatMap { it.problems }.filter { tag in it.tags }
}
