package com.eignex.klause.bench.catalog

import com.eignex.klause.bench.source.CorpusSelection
import com.eignex.klause.ir.Problem
import java.io.File

/*
 * The benchmark catalog groups [ProblemRef] entries into named [Suite]s by [Category].
 * References bind format and source.
 */

/** Encoding of a problem instance. Drives which parser / pipeline turns it into a [Problem]. */
enum class Format {
    /** DIMACS CNF (SAT). In-process via `Dimacs.parse`. */
    DIMACS,

    /** DIMACS WCNF (MaxSAT). In-process via `Dimacs.parseWcnf`. */
    WCNF,

    /** Pseudo-Boolean OPB. In-process via `Opb.parse`. */
    OPB,

    /** klause JSON `SchemaDef`. In-process via `JsonSchema.parse` and compilation. */
    JSON_SCHEMA,

    /** MiniZinc model (+ optional `.dzn`). Compiled to FlatZinc by the `minizinc` CLI, then
     *  parsed in-process. See `runner.MiniZincRunner`. */
    MINIZINC,

    /** XCSP3 instance. */
    XCSP3,

    /** SMT-LIB source script; catalog membership is independent of Klause frontend support. */
    SMTLIB,

    /** MPS (Mathematical Programming System) MIP instance. In-process via `Mps.parse(…).toProblem(…)`. */
    MPS,

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
    /** Shallow git clone of [ExternalCollection.url]. [sparsePath], when set, restricts checkout to that subtree. */
    data class GitClone(val depth: Int = 1, val sparsePath: String? = null) : FetchMethod

    /** Download a `.tar.gz` from [ExternalCollection.url] and extract it. */
    data object Tarball : FetchMethod

    /** Download a plain (uncompressed) `.tar` from [ExternalCollection.url] and extract it. Instances may stay
     *  individually compressed inside (e.g. the PB competition's `*.opb.xz`). */
    data object Tar : FetchMethod

    /** Download a `.tar.zst` (zstd-compressed tar) from [ExternalCollection.url] and extract it — the format the
     *  SMT-LIB Zenodo releases ship per-logic archives in. */
    data object TarballZst : FetchMethod

    /** Download a `.zip` from [ExternalCollection.url] and extract it (instances may stay individually
     *  compressed inside, e.g. XCSP3 `*.xml.lzma`). */
    data object Zip : FetchMethod

    /** Download each of [names] from under [ExternalCollection.url] and [convert] it, (file name, text) to
     *  (instance name, file text) pairs written as `<instance>.<extension>`, for a library whose instances need
     *  translating, such as OR-Library's own text formats into MPS. */
    data class Files(
        val names: List<String>,
        val extension: String,
        val convert: (String, String) -> List<Pair<String, String>>,
    ) : FetchMethod

    /** Download each of [instances] from `<url>/file/<hash>`, where the Global Benchmark Database serves it as
     *  `.cnf.xz`, keeping it only when its content hash is the pinned one. */
    data class GbdFiles(val instances: List<GbdInstance>) : FetchMethod
}

/** A Global Benchmark Database CNF instance pinned by its content [hash]
 *  ([com.eignex.klause.bench.source.GbdHash]), stored as [relPath] with the result the competition verified. */
internal data class GbdInstance(val hash: String, val family: String, val name: String, val expected: Expected) {
    val relPath: String get() = "$family/$name.cnf"
}

/** A collection fetched on demand, with [license] and the storage rationale in [reason]. */
internal data class ExternalCollection(
    val id: String,
    val url: String,
    val license: String,
    val reason: String,
    val fetch: FetchMethod,
    /** Extra include directories (relative to the fetched root) added to the `minizinc` search
     *  path via `-I`, for a corpus that keeps shared included files in a common dir. */
    val includeDirs: List<String> = emptyList(),
    /** Maximum compressed file size in MB. Larger extracted files are dropped before decompression. */
    val maxFileMb: Int? = null,
    /** A (marker, extension) pair: a fetched file with no extension whose text begins with the marker gains the
     *  extension, so every reader recognises it. MIPLIB 3 ships its MPS files bare. */
    val extensionless: Pair<String, String>? = null,
)

/** Where a problem's bytes come from. Resolved to a concrete file by `source.CorpusFetcher`. */
internal sealed interface ProblemSource {
    /** A file resolved and pinned by the lab planner, without corpus discovery during execution. */
    data class Prepared(val file: File, val includes: List<File> = emptyList()) : ProblemSource

    /** A file tracked in this repository, path relative to the workspace root. Use [corpus]
     *  for the common case of a file under `klause-bench/smoke-corpus/`. */
    data class Vendored(val workspaceRelPath: String) : ProblemSource

    /** A source MPS with free rows and their coefficients removed, retaining its feasible set. */
    data class MpsFeasibility(val source: ProblemSource) : ProblemSource

    /** Built directly in Kotlin — no file, no parsing. */
    data class InCode(val build: () -> Problem) : ProblemSource

    /** A file inside an [ExternalCollection] that is fetched (cloned/downloaded) on first
     *  use into a cache. [relPath] is relative to the collection's root. */
    data class External(val collection: ExternalCollection, val relPath: String) : ProblemSource

    /** The [index]-th file with extension [ext] (sorted by name, searched recursively) inside a
     *  fetched [ExternalCollection]. For collections whose member filenames aren't predictable —
     *  e.g. the SATLIB random-3SAT tarballs sample instances with inconsistent zero-padding
     *  (`uf50-031.cnf`, `uf50-0433.cnf`), so a fixed [External.relPath] can't name them. */
    data class ExternalIndexed(val collection: ExternalCollection, val index: Int, val ext: String) : ProblemSource
}

/** Convenience: a vendored file under `klause-bench/smoke-corpus/<relPath>`. */
internal fun corpus(relPath: String): ProblemSource.Vendored = ProblemSource.Vendored(
    "klause-bench/smoke-corpus/$relPath",
)

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
    /** The family a corpus provider grouped this instance under; null when the leading path component of [name]
     *  is the family. */
    val family: String? = null,
)

/** A named, described collection of [ProblemRef]s. */
internal data class Suite(val id: String, val description: String, val problems: List<ProblemRef>)

/**
 * A suite whose [provider] discovers problems on resolution. Listing its [id] and
 * [description] does not trigger a fetch.
 */
internal data class DynamicSuite(
    val id: String,
    val description: String,
    /** Instances kept per family when the caller sets no cap; null keeps the whole corpus. */
    val defaultPerFamily: Int? = null,
    val sourceLayout: ArchiveSourceLayout? = null,
    val admit: ((ProblemRef) -> Boolean)? = null,
    /** Exact identity lookup for fixed sets, without enumerating the rest of a large corpus. */
    val namedProvider: ((List<String>) -> List<ProblemRef>)? = null,
    val provider: (CorpusSelection.Selection) -> List<ProblemRef>,
)

/** The registry. Static [suites] + [dynamicSuites] are assembled in [Suites]; lookups are by
 *  id / category / tag. */
internal object Catalog {
    val suites: List<Suite> get() = Suites.all
    val dynamicSuites: List<DynamicSuite> get() = Suites.dynamic

    /** All suite ids (static + dynamic), for listings and selection. */
    val suiteIds: List<String> get() = suites.map { it.id } + dynamicSuites.map { it.id }

    fun suite(id: String): Suite {
        suites.firstOrNull { it.id == id }?.let { return it }
        dynamicSuites.firstOrNull { it.id == id }?.let {
            return selected(it, CorpusSelection.Selection.fromProps(it.defaultPerFamily))
        }
        error("no such suite: $id (have $suiteIds)")
    }

    /**
     * All problems of suite [id], without its default per-family cap. Explicit
     * `-Dklause.bench.select.*` selection settings apply to providers without an admission policy. Suites
     * with admission policies discover every original identity without admission or caps.
     */
    fun uncapped(id: String): Suite {
        val dynamic = dynamicSuites.firstOrNull { it.id == id } ?: return suite(id)
        return if (dynamic.admit == null) {
            Suite(dynamic.id, dynamic.description, dynamic.provider(CorpusSelection.Selection.fromProps()))
        } else {
            Suite(dynamic.id, dynamic.description, discovered(dynamic))
        }
    }

    fun discovered(dynamic: DynamicSuite): List<ProblemRef> = dynamic.provider(CorpusSelection.Selection())

    fun named(id: String, names: List<String>): List<ProblemRef> {
        val dynamic = dynamicSuites.firstOrNull { it.id == id }
        dynamic?.namedProvider?.let { return it(names) }
        val wanted = names.toSet()
        return uncapped(id).problems.filter { it.name in wanted }
    }

    fun selected(dynamic: DynamicSuite, selection: CorpusSelection.Selection): Suite {
        val admit = dynamic.admit
        val problems = if (admit == null) {
            dynamic.provider(selection)
        } else {
            CorpusSelection.applyAdmittedSelectionBy(
                discovered(dynamic),
                selection,
                { it.family ?: it.name.substringBefore('/') },
                admit,
            )
        }
        return Suite(dynamic.id, dynamic.description, problems)
    }

    /** The per-family cap suite [id] applies when the caller sets none; null for a suite kept whole. */
    fun defaultPerFamily(id: String): Int? = dynamicSuites.firstOrNull { it.id == id }?.defaultPerFamily

    fun problems(vararg suiteIds: String): List<ProblemRef> =
        (if (suiteIds.isEmpty()) suites.map { it.id } else suiteIds.toList()).flatMap { suite(it).problems }

    fun byCategory(category: Category): List<ProblemRef> =
        suites.flatMap { it.problems }.filter { it.category == category }

    fun byTag(tag: String): List<ProblemRef> = suites.flatMap { it.problems }.filter { tag in it.tags }
}

internal data class ArchiveSourceLayout(val collection: ExternalCollection, val layout: CorpusSelection.Layout.Flat) {
    fun member(name: String): String {
        require(name.isNotBlank() && name.length <= 2048)
        require(name.split('/').none { it.isEmpty() || it == "." || it == ".." })
        require(name.none { it == '\\' || it.code < 32 || it.code == 127 })
        return "${layout.subDir}/$name.${layout.ext}"
    }
}
