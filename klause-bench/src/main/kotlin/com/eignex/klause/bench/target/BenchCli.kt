package com.eignex.klause.bench.target

import com.eignex.klause.bench.catalog.Catalog
import com.eignex.klause.bench.catalog.Category
import com.eignex.klause.bench.catalog.Format
import com.eignex.klause.bench.catalog.ProblemRef
import com.eignex.klause.bench.catalog.ProblemSets
import com.eignex.klause.bench.metric.ArmMining
import com.eignex.klause.bench.metric.InstalledBuild
import com.eignex.klause.bench.metric.InstanceClassifier
import com.eignex.klause.bench.metric.InstanceFeatures
import com.eignex.klause.bench.metric.KlauseSearch
import com.eignex.klause.bench.metric.ReferenceStore
import com.eignex.klause.bench.metric.ResultCredit
import com.eignex.klause.bench.metric.SolveMetric
import com.eignex.klause.bench.metric.SolverInvocation
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.bench.runner.Budget
import com.eignex.klause.bench.runner.MZN_COMPATIBILITY_POLICY
import com.eignex.klause.bench.runner.MiniZincCompatibility
import com.eignex.klause.bench.source.CorpusCache
import com.eignex.klause.bench.source.CorpusFetcher
import com.eignex.klause.bench.source.CorpusFiles
import com.eignex.klause.bench.source.CorpusSelection
import com.eignex.klause.bench.source.ProblemKind
import com.eignex.klause.bench.tools.ProfileConfig
import com.eignex.klause.bench.tools.ProfileEvent
import com.eignex.klause.bench.tools.ProfileScope
import java.io.File
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Benchmark command dispatcher for solving, selection, result credit and corpus
 * maintenance. Solves write per-problem [SolveMetric] outputs and a per-run result CSV;
 * comparison and scoring operate on those saved records.
 */
object BenchCli {
    private const val MIB = 1024L * 1024

    private const val MIB_COLUMN_WIDTH = 14

    /** CLI entry point dispatching bench subcommands. */
    @JvmStatic
    fun main(args: Array<String>) {
        when (val cmd = args.firstOrNull() ?: "list") {
            "list", "--list", "help", "--help" -> if (args.size > 1) listProblems(args[1]) else printListing()

            "solve" -> run(args.drop(1), preview = false)

            "preview" -> run(args.drop(1), preview = true)

            "select" -> printSelection(args.drop(1))

            "solve-one" -> solveOne(args.drop(1))

            "provenance" -> {
                val output = args.drop(1).singleOrNull()?.takeIf { it.startsWith("out=") }?.substringAfter('=')
                require(!output.isNullOrBlank()) { "Use provenance out=<file>" }
                InstalledBuild.writeManifest(File(output))
            }


            "classify" -> classify(args.drop(1))

            "credit" -> credit(args.drop(1))

            "mine" -> mine(args.drop(1))

            "corpus" -> corpus(args.drop(1))

            else ->
                error(
                    "unknown command '$cmd' " +
                        "(commands: solve, solve-one, select, preview, " +
                        "classify, credit, mine, corpus, provenance, list)",
                )
        }
    }

    /** Populate the source-text feature columns (format / structure / global+linear counts / bool-heavy)
     *  of the reference tables for the selected instances — the substrate for the stratified pool and for
     *  data analysis. Streams one source file at a time (no klause compile), so a whole-corpus pass stays
     *  flat in memory; an unreadable/unsupported source is skipped (blank features, counted). */
    private fun classify(filterArgs: List<String>) {
        val f = filterArgs.filter { "=" in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
        val refs = select(f)
        if (refs.isEmpty()) {
            println("(no problems matched the selection)")
            return
        }
        println("=== classify: ${refs.size} instance(s) ===")
        var skipped = 0
        val features = HashMap<Pair<String, String>, InstanceFeatures>(refs.size)
        for (ref in refs) {
            val feat = InstanceClassifier.classify(ref)
            if (feat == null) {
                skipped++
                continue
            }
            features[ReferenceStore.suiteOf(ref) to ref.name] = feat
        }
        val (updated, unmatched) = ReferenceStore.mergeFeatures(features)
        println(
            "classified ${features.size} (skipped $skipped unreadable); reference tables: " +
                "$updated rows updated, $unmatched with no table row",
        )
    }

    /** `corpus compress [<dir>]`: compress the plain instances under [dir] (default: the corpus cache) in
     *  place. Safe to interrupt and re-run; see [CorpusFiles.compressTree]. `corpus gc` applies the cache cap
     *  and `corpus status` lists the cached collections; see [CorpusCache]. */
    private fun corpus(args: List<String>) {
        when (args.firstOrNull()) {
            "compress" -> corpusCompress(args.getOrNull(1)?.let { File(it) } ?: CorpusFetcher.cacheRoot)
            "gc" -> corpusGc()
            "status" -> printCorpusStatus(CorpusFetcher.cache())
            else -> error("usage: corpus compress [<dir>] | corpus gc | corpus status")
        }
    }

    private fun corpusCompress(root: File) {
        require(root.isDirectory) { "no corpus directory at $root" }
        println("=== corpus compress: $root ===")
        val done = CorpusFiles.compressTree(root, ::println)
        CorpusFetcher.cache().recordSizesUnder(root)
        println(
            "compressed ${done.files} instance(s): ${done.plainBytes / MIB} MiB -> ${done.packedBytes / MIB} MiB",
        )
    }

    private fun corpusGc() {
        val cap = CorpusCache.configuredCapBytes()
        println("=== corpus gc: ${CorpusFetcher.cacheRoot} (cap ${cap?.let { "${it / MIB} MiB" } ?: "off"}) ===")
        val cache = CorpusFetcher.cache()
        val evicted = cache.enforce().evicted
        evicted.forEach { println("evicted ${it.id} (${it.bytes / MIB} MiB)") }
        println("evicted ${evicted.size} collection(s)")
        printCorpusStatus(cache)
    }

    private fun printCorpusStatus(cache: CorpusCache) {
        val entries = cache.collections()
        for (e in entries.asReversed()) {
            val used = Instant.ofEpochMilli(e.lastUsedMillis).truncatedTo(ChronoUnit.SECONDS)
            println("${mibColumn(e.bytes)}  $used  ${e.id}")
        }
        println("${mibColumn(entries.sumOf { it.bytes })}  total, ${entries.size} collection(s)")
    }

    private fun mibColumn(bytes: Long): String = "${bytes / MIB} MiB".padStart(MIB_COLUMN_WIDTH)

    /** Credit between per-run result CSVs (emitted by `solve` as `output/<config>.csv`, reference-table
     *  schema): `credit [--by structure|format] <a.csv> <b.csv> [c.csv …]`. Joins on (suite, problem),
     *  picks each instance's winner(s), and reports win-share + a greedy diverse set-cover
     *  ([ResultCredit]). `--by` slices the credit within each feature-column value. Include the
     *  committed `reference/<solver>.csv` as a file to score against that solver's baseline. */
    private fun credit(args: List<String>) {
        val by = args.zipWithNext().firstOrNull { it.first == "--by" }?.second
        val files = args
            .filterIndexed { i, a -> !a.startsWith("--") && args.getOrNull(i - 1) != "--by" }
            .map { File(it) }
        require(files.size >= 2) { "credit needs >= 2 result CSVs (got ${files.size})" }
        files.firstOrNull { !it.isFile }?.let { error("no such result CSV: $it") }
        print(ResultCredit.credit(files, by))
    }

    private fun mine(args: List<String>) {
        val by = args.firstOrNull { it.startsWith("by=") }?.substringAfter('=')
        val files = args.filterNot { it.startsWith("by=") }.map { File(it) }
        require(files.isNotEmpty()) { "mine needs >= 1 lab case file" }
        files.firstOrNull { !it.isFile }?.let { error("no such case file: $it") }
        val cases = ArmMining.load(files)
        if (cases.isEmpty()) {
            println("(no case carries arm telemetry; run the portfolio with -s)")
            return
        }
        print(ArmMining.render(cases, by))
    }

    /** Run `solve` over the [filterArgs] selection (or just print it when [preview]). `solve` is the
     *  bench's one measurement: one solver per invocation, as a subprocess, saving per-problem output
     *  (see [SolveMetric]) plus an `output/<config>.csv` result table that `credit` / `output/credit.sh`
     *  compare; `output/compare.sh` does Borda scoring over the dirs. */
    private fun run(filterArgs: List<String>, preview: Boolean) {
        val f = filterArgs.filter { "=" in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
        val refs = select(f)
        if (refs.isEmpty()) {
            println("(no problems matched the selection)")
            return
        }
        if (preview) {
            println("=== preview: solve over ${refs.size} instance(s) ===")
            refs.forEach { println("  ${it.name}  [${it.format}/${it.category}]") }
            return
        }
        val budget = f["timeout"]?.toLongOrNull()?.let { Budget(it) } ?: Budget()
        // `backend=` is the solver id: a registered MiniZinc solver (choco/gecode/yuck/…) run via
        // `minizinc --solver`; unset (or `klause`) runs klause via klause-cli.
        val backend = f["backend"]?.lowercase()?.takeIf { it != "klause" }
        val profile = parseProfile(f)
        // `param=` is repeatable (`param=var-selector=vsids param=luby=256`), so collect it from the
        // raw args rather than the dedup'd filter map; each value is a klause-cli `key=value` knob.
        val params = filterArgs.filter { it.startsWith("param=") }.map { it.substringAfter('=') }
        val search = parseKlauseSearch(f, params)
        println("=== solve over ${refs.size} instance(s) ===")
        SolveMetric.run(
            BenchLoad.resolveLazily(refs, exact = backend == null && search?.exact == true),
            budget,
            backend ?: SolverInvocation.KLAUSE,
            search ?: KlauseSearch(),
            profile,
            label = f["label"],
        )
    }

    /** Print the [filterArgs] selection one JSON object per line: the suite and problem that name each instance
     *  exactly for `solve-one`, its reference-table suite (`collection`), family, format and category. Resolving
     *  a dynamic suite fetches its collection, so a selection also leaves every instance it names on disk.
     *  `files=true` adds the absolute `model` and `data` files and MiniZinc `includeDirs`, which lets a runner
     *  outside the bench solve the instance. */
    private fun printSelection(filterArgs: List<String>) {
        val f = filterArgs.filter { "=" in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
        val features = f["features"] == "true"
        val files = f["files"] == "true"
        for (selected in selectProblems(f)) {
            val line = buildJsonObject {
                put("suite", selected.suite)
                put("problem", selected.ref.name)
                put("collection", ReferenceStore.suiteOf(selected.ref))
                put("family", selected.family.substringAfter(':'))
                put("format", selected.ref.format.name)
                put("category", selected.ref.category.name)
                if (selected.suite == "mzn-bench-compatible-v1" && f["set"] == null) {
                    put("selectionPolicy", MZN_COMPATIBILITY_POLICY)
                    put("compilerIdentity", Reports.json.encodeToJsonElement(MiniZincCompatibility.compiler))
                }
                if (files) {
                    put("model", CorpusFetcher.resolve(selected.ref.source).absolutePath)
                    selected.ref.data?.let { put("data", CorpusFetcher.resolve(it).absolutePath) }
                    putJsonArray("includeDirs") {
                        CorpusFetcher.includeDirs(selected.ref.source).forEach { add(it.absolutePath) }
                    }
                }
                if (features) {
                    InstanceClassifier.classify(selected.ref)?.let { feat ->
                        put("structure", feat.structure)
                        put("logic", feat.logic)
                        putJsonArray("themes") { feat.themes.sorted().forEach { add(it) } }
                    }
                }
            }
            println(line)
        }
    }

    /** Solve exactly one problem, `suite=<id> problem=<name>` as `select` prints it, with `solve`'s solver
     *  arguments, writing its record to `out=<dir>` (default `output/<config>/`). The suite resolves uncapped,
     *  so any instance a selection could name is found. Exits non-zero only when the problem is unknown; one
     *  that fails to load or to solve still writes its record, saying why. */
    private fun solveOne(args: List<String>) {
        val f = args.filter { "=" in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
        val suite = requireNotNull(f["suite"]) { "solve-one needs suite=<id>" }
        val name = requireNotNull(f["problem"]) { "solve-one needs problem=<name>" }
        val ref = Catalog.uncapped(suite).problems.singleOrNull { it.name == name }
            ?: error("no problem '$name' in suite '$suite'")
        val params = args.filter { it.startsWith("param=") }.map { it.substringAfter('=') }
        val record = SolveMetric.solveOne(
            ref,
            f["timeout"]?.toLongOrNull()?.let { Budget(it) } ?: Budget(),
            f["backend"]?.lowercase()?.takeIf { it != "klause" } ?: SolverInvocation.KLAUSE,
            parseKlauseSearch(f, params) ?: KlauseSearch(),
            label = f["label"],
            outDir = f["out"]?.let(::File),
        )
        println("${record.problem}: feasible=${record.feasible} objective=${record.objective} proven=${record.proven}")
    }

    /** The klause-side search for a `solve` run, from `engine=` / `processors=` / `fixed=` / `param=`.
     *  Returns null when none are set. Defaults: `engine` unset ⇒ no `-e`, so klause follows the cli's
     *  own default engine (the bench has no engine default of its own); **single core** (`processors`
     *  unset ⇒ no `-p`), so multi-thread tracks pass `processors=` explicitly. `engine`/`param` forward
     *  to the cli `-e`/`--param`; `fixed=true` is the reference (`-f`) toggle. The cli owns the engine
     *  model; the bench just forwards. */
    private fun parseKlauseSearch(f: Map<String, String>, params: List<String>): KlauseSearch? {
        val anySet = listOf("engine", "processors", "fixed", "lp", "presolve", "solver-seed", "exact")
            .any { f[it] != null } || params.isNotEmpty()
        if (!anySet) return null
        return KlauseSearch(
            engine = f["engine"]?.let(::parseEngine),
            processors = f["processors"]?.toIntOrNull(),
            fixed = f["fixed"]?.toBoolean() ?: false,
            params = params,
            lp = f["lp"],
            presolve = f["presolve"],
            seed = f["solver-seed"]?.toLongOrNull(),
            exact = f["exact"]?.toBooleanStrict() ?: false,
        )
    }

    /** Map an `engine=` alias to a klause-cli `-e` value (fixed | cp | mixed | ls | alns). */
    private fun parseEngine(name: String): String = when (name.lowercase()) {
        "cp", "backtrack", "bt" -> "cp"
        "ls", "localsearch", "local-search" -> "ls"
        "mixed", "portfolio", "pf" -> "mixed"
        "alns", "lns", "hybrid-lns" -> "alns"
        "fixed", "fd" -> "fixed"
        else -> error("engine must be fixed|cp|mixed|ls|alns, got '$name'")
    }

    private fun select(f: Map<String, String>): List<ProblemRef> = selectProblems(f).map { it.ref }

    /** Build the selection from filters: suites (`core` expands to the in-process core; static-only unless
     *  named) → category/tag/name filter → per-family caps → overall cap → shard. Dynamic suites resolve
     *  uncapped, so `per-family`/`seed` sample each corpus by its provider's own family key; a suite's default
     *  per-family cap applies only when `per-family` is unset. Families are counted per suite, so two suites
     *  sharing a family name do not share its cap. `kind=cop|csp` is checked only on the instances a cap
     *  reaches (via [ProblemKind]), so a capped selection fills its cap with the requested kind without reading
     *  every source of a large corpus. */
    internal fun selectProblems(f: Map<String, String>): List<SelectedProblem> {
        val suiteIds = f["suite"]?.split(",")?.map { it.trim() }?.flatMap { expandSuiteIds(it) }
            ?: Catalog.suites.map { it.id }
        var candidates = f["set"]?.let { sets ->
            setProblems(
                sets.split(",").map { it.trim() },
                f["suite"]?.let { suiteIds.toSet() },
            )
        }
            ?: suiteIds.flatMap { id ->
                val default = Catalog.defaultPerFamily(id)
                Catalog.uncapped(id).problems.map { SelectedProblem(id, it, default) }
            }
        f["category"]?.split(",")?.map { Category.valueOf(it.trim().uppercase()) }?.toSet()?.let { cats ->
            candidates = candidates.filter { it.ref.category in cats }
        }
        f["tag"]?.split(",")?.map { it.trim() }?.toSet()?.let { tags ->
            candidates = candidates.filter { it.ref.tags.any { t -> t in tags } }
        }
        // Comma-separated name patterns are ORed; each is a substring or * glob.
        f["name"]?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.let { pats ->
            candidates = candidates.filter { c -> pats.any { matches(it, c.ref.name) } }
        }
        val wantCop = f["kind"]?.let(::parseKind)
        val capped = BenchSelection.capFamilies(
            candidates,
            perFamily = f["per-family"]?.toIntOrNull(),
            seed = f["seed"]?.toLongOrNull(),
            compatible = { candidate ->
                f["set"] != null || candidate.suite != "mzn-bench-compatible-v1" || compatible(candidate)
            },
            accept = { wantCop == null || ProblemKind.isCop(it) == wantCop },
        )
        // Families are already capped; what is left is the round-robin interleave and the overall `max`.
        val sel = CorpusSelection.Selection(maxInstances = f["max"]?.toIntOrNull())
        // Format balancing water-fills the total cap across formats; unset uses the family-global cap.
        val selected = when (f["balance"]?.lowercase()) {
            null -> CorpusSelection.applySelectionBy(capped, sel) { it.family }
            "format" -> CorpusSelection.applyBalancedBy(capped, sel, { it.family }) { it.ref.format }
            else -> error("balance must be 'format', got '${f["balance"]}'")
        }
        // Sharding for parallel sweeps: -Dklause.bench.shard=i/n keeps every n-th selected
        // problem starting at i (0-based). Applied before resolution so each worker only
        // compiles its own rows — disjoint shards never race on the shared mzn-fzn cache.
        val shard = System.getProperty("klause.bench.shard") ?: return selected
        val (idx, n) = shard.split("/").map { it.trim().toInt() }
        require(n > 0 && idx in 0 until n) { "klause.bench.shard must be i/n with 0 <= i < n, got $shard" }
        return selected.filterIndexed { i, _ -> i % n == idx }
    }

    private fun compatible(candidate: SelectedProblem): Boolean =
        MiniZincCompatibility.admit(candidate.suite, candidate.ref)

    /** The problems of the named sets, restricted to [suites] when given. A set is selected whole: no suite's
     *  default per-family cap applies to it, only an explicit `per-family`. */
    private fun setProblems(names: List<String>, suites: Set<String>?): List<SelectedProblem> {
        val entries = ProblemSets.load(names).filter { suites == null || it.suite in suites }
        val bySuite = entries.map { it.suite }.distinct().associateWith { id ->
            Catalog.uncapped(
                id,
            ).problems.associateBy { it.name }
        }
        val missing = entries.filter { bySuite.getValue(it.suite)[it.problem] == null }
        require(missing.isEmpty()) {
            "${missing.size} set problems are in no suite, first ${missing.take(
                3,
            ).joinToString { "${it.suite}/${it.problem}" }}"
        }
        return entries.map { SelectedProblem(it.suite, bySuite.getValue(it.suite).getValue(it.problem)) }
    }

    /** `kind=cop` keeps optimization problems, `kind=csp` keeps satisfaction problems. */
    private fun parseKind(kind: String): Boolean = when (kind.lowercase()) {
        "cop", "opt", "optimization" -> true
        "csp", "sat", "satisfaction" -> false
        else -> error("kind must be cop|csp, got '$kind'")
    }

    /** Expand a suite token: `core` → every in-process core suite; otherwise the named suite. */
    private fun expandSuiteIds(token: String): List<String> = when (token) {
        "core" -> Targets.IN_PROCESS_CORE
        else -> listOf(token)
    }

    private fun parseProfile(f: Map<String, String>): ProfileConfig? {
        val ev = f["profile"] ?: return null
        val event = runCatching { ProfileEvent.valueOf(ev.uppercase()) }
            .getOrElse { error("profile must be one of cpu|wall|alloc, got '$ev'") }
        val scope = f["profile-scope"]?.let {
            runCatching { ProfileScope.valueOf(it.uppercase()) }
                .getOrElse { _ -> error("profile-scope must be solve|all, got '${f["profile-scope"]}'") }
        } ?: ProfileScope.SOLVE
        return ProfileConfig(event = event, scope = scope, topN = f["profile-top"]?.toIntOrNull() ?: 40)
    }

    private fun matches(pattern: String, name: String): Boolean = if ('*' in pattern) {
        // Escape each literal segment between `*`s (Regex.escape wraps in \Q…\E, so escaping the
        // whole pattern then substituting `*` doesn't work), and join with `.*`.
        val rx = pattern.split('*').joinToString(".*") { Regex.escape(it) }
        Regex("^$rx$").containsMatchIn(name)
    } else {
        name.contains(pattern)
    }

    private fun listProblems(suite: String) {
        val s = Catalog.suite(suite)
        println("=== suite '${s.id}' — ${s.problems.size} problems ===")
        s.problems.forEach { println("  ${it.name.padEnd(28)} [${it.format}/${it.category}] expected=${it.expected}") }
    }

    private fun printListing() {
        println("Suites:")
        for (s in Catalog.suites) println("  ${s.id.padEnd(22)} ${s.problems.size} problems — ${s.description}")
        for (d in Catalog.dynamicSuites) println("  ${d.id.padEnd(22)} (discovered) — ${d.description}")
        println(
            """
            |
            |Usage:
            |  bench solve [filters…]                solve a selection (the bench's one measurement)
            |  bench reference [filters…]            harvest optima/verdicts into per-solver tables (cp-sat/clasp/z3 by format)
            |  bench preview [filters…]              show what a run would cover
            |  bench select [filters…]               the selection as JSON lines (suite, problem, …)
            |  bench solve-one suite= problem= […]   solve one instance; out=<dir> for its record
            |  bench provenance out=<file>           capture installed build/runtime once for immutable campaigns
            |  bench corpus compress [<dir>]         zstd-compress the plain instances in the corpus cache, in place
            |  bench list [<suite>]                  list suites, or problems in a suite
            |
            |Filters: suite=a,b (suite=core = in-process core) kind=cop|csp category=SAT,OPTIMIZATION
            |         tag=… name=<glob>[,…] (comma=OR) per-family=N max=N seed=N backend=<minizinc solver id> timeout=<ms>
            |         balance=format (split max evenly across the formats present, for broad sweeps)
            |         engine=fixed|cp|mixed|ls processors=N solver-seed=N (klause search for solve)
            |         lp=off|conservative|default|aggressive[±id] (klause-cli --lp LP emphasis)
            |         presolve=off|conservative|default|aggressive[,±pass] (klause-cli --presolve)
            |         fixed=true (reference -f toggle)  param=key=value (klause-cli --param; var-/val-selector edit the cp pool)
            |         exact=true (klause-cli --exact; continuous FlatZinc floats and exact MPS certificates)
            |         label=<name> (tag the run, e.g. a klause version, so re-runs coexist as distinct dirs)
            |         profile=cpu|wall|alloc profile-scope=solve|all profile-top=N
            |
            |Examples:
            |  bench solve suite=mzn-bench kind=cop per-family=1               (klause, engine=fixed ×1 by default)
            |  bench solve suite=mzn-bench backend=choco timeout=300000        (Choco baseline)
            |  bench solve suite=mzn-bench backend=yuck timeout=300000         (Yuck baseline)
            |  bench solve suite=mzn-bench engine=cp processors=8              (klause parallel backtrack portfolio)
            |  bench solve suite=mzn-bench engine=cp param=var-selector=vsids (heuristic A/B: re-run with =chb, then compare.sh)
            |  bench solve suite=mzn-bench engine=fixed                        (klause follows the model annotation)
            |
            |To compare configs, run `solve` once per config (each writes output/<config>/) and diff dirs offline.
            """.trimMargin(),
        )
    }
}
