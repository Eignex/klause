package com.eignex.klause.bench.catalog

import com.eignex.klause.bench.source.CorpusSelection
import com.eignex.klause.ir.Problem
import java.io.File

/** Encoding of a problem instance; selects the parser that turns it into a [Problem]. */
enum class Format {
    /** DIMACS CNF (SAT). */
    DIMACS,

    /** DIMACS WCNF (MaxSAT). */
    WCNF,

    /** Pseudo-Boolean OPB. */
    OPB,

    /** klause JSON `SchemaDef`. */
    JSON_SCHEMA,

    /** MiniZinc model (+ optional `.dzn`), compiled to FlatZinc by the `minizinc` CLI. */
    MINIZINC,

    /** XCSP3 instance. */
    XCSP3,

    /** SMT-LIB source script; catalog membership is independent of Klause frontend support. */
    SMTLIB,

    /** MPS MIP instance. */
    MPS,

    /** Built directly in Kotlin, no file. */
    IN_CODE,
}

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

/** Known outcome of an instance, the oracle the bench checks solvers against. */
sealed interface Expected {
    /** A satisfying assignment exists. */
    data object Sat : Expected

    /** Proven infeasible. */
    data object Unsat : Expected

    /** Optimization instance with a known optimal objective [value] (minimization). */
    data class Opt(val value: Long) : Expected

    /** Status not recorded. */
    data object Unknown : Expected

    /** Whether a feasible assignment is expected to exist (true for [Sat] and [Opt]). */
    val expectsSat: Boolean get() = this is Sat || this is Opt
}

internal sealed interface FetchMethod {
    data class GitClone(val depth: Int = 1, val sparsePath: String? = null) : FetchMethod

    data object Tarball : FetchMethod

    data object Tar : FetchMethod

    data object TarballZst : FetchMethod

    data object Zip : FetchMethod

    // Discovers `extension` instances from the ZIP directory and materializes only selected members.
    data class SelectiveZip(val extension: String) : FetchMethod

    // Fetches the uniquely named member through bounded HTTP ranges, unpacks optional XZ compression and requires
    // the plain bytes to match `sha256`. `name` is a basename, not an archive path.
    data class ZipMember(val name: String, val sha256: String) : FetchMethod

    // Downloads each name and converts (file name, text) to (instance name, file text) pairs written as
    // `<instance>.<extension>`, for libraries whose own text formats need translating to MPS.
    data class Files(
        val names: List<String>,
        val extension: String,
        val convert: (String, String) -> List<Pair<String, String>>,
    ) : FetchMethod

    // Downloads `<url>/file/<hash>` as `.cnf.xz`, keeping it only when its content hash is the pinned one.
    data class GbdFiles(val instances: List<GbdInstance>) : FetchMethod
}

// `hash` is the GbdHash content hash; `expected` is the result the competition verified.
internal data class GbdInstance(val hash: String, val family: String, val name: String, val expected: Expected) {
    val relPath: String get() = "$family/$name.cnf"
}

// `reason` records why the collection is fetched instead of vendored.
internal data class ExternalCollection(
    val id: String,
    val url: String,
    val license: String,
    val reason: String,
    val fetch: FetchMethod,
    // Added to the `minizinc` search path via `-I`, for corpora that keep shared includes in a common dir.
    val includeDirs: List<String> = emptyList(),
    // Maximum compressed file size in MB; larger files are dropped before decompression.
    val maxFileMb: Int? = null,
    // (marker, extension): an extensionless file whose text begins with the marker gains the extension, since
    // MIPLIB 3 ships its MPS files bare.
    val extensionless: Pair<String, String>? = null,
)

internal sealed interface ProblemSource {
    // Resolved and pinned by the lab planner, without corpus discovery during execution.
    data class Prepared(val file: File, val includes: List<File> = emptyList()) : ProblemSource

    // Path relative to the workspace root.
    data class Vendored(val workspaceRelPath: String) : ProblemSource

    // The source MPS with free rows removed, retaining its feasible set.
    data class MpsFeasibility(val source: ProblemSource) : ProblemSource

    data class InCode(val build: () -> Problem) : ProblemSource

    // `relPath` is relative to the collection root; the collection is fetched into a cache on first use.
    data class External(val collection: ExternalCollection, val relPath: String) : ProblemSource

    // The index-th file with extension `ext`, sorted by name, for collections whose member names are not
    // predictable (SATLIB random-3SAT pads inconsistently: `uf50-031.cnf`, `uf50-0433.cnf`).
    data class ExternalIndexed(val collection: ExternalCollection, val index: Int, val ext: String) : ProblemSource
}

internal fun corpus(relPath: String): ProblemSource.Vendored = ProblemSource.Vendored(
    "klause-bench/smoke-corpus/$relPath",
)

// `data` is an optional companion source (a `.dzn` for MiniZinc); `license` keeps vendoring decisions auditable.
internal data class ProblemRef(
    val name: String,
    val format: Format,
    val source: ProblemSource,
    val category: Category,
    val expected: Expected,
    val data: ProblemSource? = null,
    val tags: Set<String> = emptySet(),
    val license: String = "internal",
    // Null when the leading path component of `name` is the family.
    val family: String? = null,
)

internal data class Suite(val id: String, val description: String, val problems: List<ProblemRef>)

// Listing `id` and `description` does not trigger a fetch; `provider` discovers problems on resolution.
internal data class DynamicSuite(
    val id: String,
    val description: String,
    // Instances kept per family when the caller sets no cap; null keeps the whole corpus.
    val defaultPerFamily: Int? = null,
    val sourceLayout: ArchiveSourceLayout? = null,
    val admit: ((ProblemRef) -> Boolean)? = null,
    // Exact identity lookup for fixed sets, without enumerating the rest of a large corpus.
    val namedProvider: ((List<String>) -> List<ProblemRef>)? = null,
    val provider: (CorpusSelection.Selection) -> List<ProblemRef>,
)

internal object Catalog {
    val suites: List<Suite> get() = Suites.all
    val dynamicSuites: List<DynamicSuite> get() = Suites.dynamic

    val suiteIds: List<String> get() = suites.map { it.id } + dynamicSuites.map { it.id }

    fun suite(id: String): Suite {
        suites.firstOrNull { it.id == id }?.let { return it }
        dynamicSuites.firstOrNull { it.id == id }?.let {
            return selected(it, CorpusSelection.Selection.fromProps(it.defaultPerFamily))
        }
        error("no such suite: $id (have $suiteIds)")
    }

    // Explicit `-Dklause.bench.select.*` settings apply only to providers without an admission policy; suites with
    // one discover every original identity without admission or caps.
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
