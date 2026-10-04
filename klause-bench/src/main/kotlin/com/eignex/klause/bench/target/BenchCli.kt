package com.eignex.klause.bench.target

import com.eignex.klause.bench.catalog.Catalog
import com.eignex.klause.bench.catalog.Category
import com.eignex.klause.bench.catalog.Format
import com.eignex.klause.bench.catalog.ProblemRef
import com.eignex.klause.bench.metric.ArmCalibration
import com.eignex.klause.bench.metric.BenchCache
import com.eignex.klause.bench.metric.ClaspReference
import com.eignex.klause.bench.metric.InstanceClassifier
import com.eignex.klause.bench.metric.InstanceFeatures
import com.eignex.klause.bench.metric.KlauseSearch
import com.eignex.klause.bench.metric.ReferenceEntry
import com.eignex.klause.bench.metric.ReferenceSolve
import com.eignex.klause.bench.metric.ReferenceStore
import com.eignex.klause.bench.metric.ResultCredit
import com.eignex.klause.bench.metric.ScipReference
import com.eignex.klause.bench.metric.SolveMetric
import com.eignex.klause.bench.metric.SolveRecord
import com.eignex.klause.bench.metric.SolverInvocation
import com.eignex.klause.bench.metric.Xcsp3CpSatReference
import com.eignex.klause.bench.metric.Z3Reference
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.bench.runner.Budget
import com.eignex.klause.bench.source.CorpusCache
import com.eignex.klause.bench.source.CorpusFetcher
import com.eignex.klause.bench.source.CorpusFiles
import com.eignex.klause.bench.source.CorpusSelection
import com.eignex.klause.bench.source.ProblemKind
import com.eignex.klause.bench.tools.ProfileConfig
import com.eignex.klause.bench.tools.ProfileEvent
import com.eignex.klause.bench.tools.ProfileScope
import com.eignex.klause.bench.tune.BoTuning
import com.eignex.klause.bench.tune.RandomTuner
import com.eignex.klause.bench.tune.StratifiedPool
import com.eignex.klause.bench.tune.TuneEngine
import com.eignex.klause.bench.tune.Tuner
import com.eignex.klause.bench.tune.VizierTuner
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Single entry point for the bench: `./gradlew :klause-bench:bench --args="<command>"`.
 *
 * The bench does one thing — **solve** a **selection** of problems with one solver, as a subprocess,
 * saving per-problem output (see [SolveMetric]) plus a per-run `output/<config>.csv` result table in
 * the reference-table schema; the `credit` command / `output/credit.sh` compare those tables, and the
 * `output/compare.sh` MiniZinc-Challenge Borda scorer reads the saved dirs. The run form:
 *
 *   `bench solve [filters…]`   e.g. `bench solve suite=smtlib-core backend=choco`
 *
 * Filters: `suite=a,b` (the token `core` expands to the in-process core) `kind=cop|csp`
 * `category=SAT,OPT` `tag=…` `name=<glob>[,…]` (comma = OR) `per-family=N` `max=N` `seed=N`
 * `backend=choco|gecode|yuck` (the solver; default klause) `timeout=<ms>`
 * `engine=fixed|cp|mixed|ls` `processors=N` `fixed=true` (references) `param=key=value`
 * `lp=off|conservative|default|aggressive[±id…]` (klause-cli `--lp` LP-relaxation emphasis)
 * `presolve=off|conservative|default|aggressive[,±pass…]` (klause-cli `--presolve` presolve emphasis + deltas)
 * `label=<name>` (tag the run — e.g. a klause version — so re-runs coexist instead of overwriting)
 * `profile=cpu|wall|alloc` `profile-scope=solve|all` `profile-top=N`.
 *
 * Other commands:
 *  - `calibrate [filters…]` — the fair arm tester: run the pool once as a live portfolio and rank
 *    arms into a diverse palette by per-problem best-holder wins (see [calibrate]).
 *  - `reference [filters…]` — harvest per-instance reference optima/bounds with a format-native strong
 *    solver (cp-sat for MiniZinc/XCSP3, clasp for DIMACS/OPB, z3 for SMT-LIB) into the committed
 *    per-solver tables (see [reference]); the gap-to-optimum reward + a soundness oracle.
 *  - `credit [--by structure|format] <a.csv> <b.csv> …` — win-share + greedy set-cover credit between
 *    per-run result CSVs, keyed by (suite, problem), sliceable by a feature column (see [credit]).
 *  - `preview [filters…]` — print the instances a run would cover, without running.
 *  - `select [filters…]` — the same selection as JSON lines naming each instance exactly, for `solve-one`.
 *  - `solve-one suite=<id> problem=<name> [solve args…] [out=<dir>]` — solve one instance and write its record.
 *  - `corpus compress [<dir>]` — store every plain instance in the corpus cache (or [dir]) zstd-compressed,
 *    in place; the migration for a cache fetched before the cache stored instances compressed.
 *  - `corpus gc` — evict least recently used collections until the corpus cache fits its cap, then list it.
 *  - `corpus status` — list the cached collections with size and last use.
 *  - `list` — suites; `list <suite>` — problems in a suite.
 */
object BenchCli {
    /** Default reference-sweep concurrency: enough to keep cores busy, low enough that a handful of
     *  large-instance cp-sat solves can't exhaust memory. Override with `jobs=N`. */
    private const val DEFAULT_REFERENCE_JOBS = 6

    /** Truncate coverage values to three decimals for the `tune` palette console output. */
    private const val COVERAGE_DECIMALS = 1000.0

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

            "calibrate" -> calibrate(args.drop(1))

            "reference" -> reference(args.drop(1))

            "classify" -> classify(args.drop(1))

            "tune" -> tune(args.drop(1))

            "credit" -> credit(args.drop(1))

            "corpus" -> corpus(args.drop(1))

            else ->
                error(
                    "unknown command '$cmd' " +
                        "(commands: solve, solve-one, select, preview, calibrate, reference, classify, tune, credit, " +
                        "corpus, list)",
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
     *  now and `corpus status` lists the cached collections; see [CorpusCache]. */
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
        val backend = (f["backend"] ?: f["reference"])?.lowercase()?.takeIf { it != "klause" }
        val profile = parseProfile(f)
        // `param=` is repeatable (`param=var-selector=vsids param=luby=256`), so collect it from the
        // raw args rather than the dedup'd filter map; each value is a klause-cli `key=value` knob.
        val params = filterArgs.filter { it.startsWith("param=") }.map { it.substringAfter('=') }
        val search = parseKlauseSearch(f, params)
        println("=== solve over ${refs.size} instance(s) ===")
        SolveMetric.run(
            BenchLoad.resolveLazily(refs),
            budget,
            backend ?: SolverInvocation.KLAUSE,
            search ?: KlauseSearch(),
            profile,
            label = f["label"],
        )
    }

    /** Print the [filterArgs] selection one JSON object per line: the suite and problem that name each instance
     *  exactly for `solve-one`, its reference-table suite (`collection`), family, format and category. Resolving
     *  a dynamic suite fetches its collection, so a selection also leaves every instance it names on disk. */
    private fun printSelection(filterArgs: List<String>) {
        val f = filterArgs.filter { "=" in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
        for (selected in selectProblems(f)) {
            val line = buildJsonObject {
                put("suite", selected.suite)
                put("problem", selected.ref.name)
                put("collection", ReferenceStore.suiteOf(selected.ref))
                put("family", selected.family.substringAfter(':'))
                put("format", selected.ref.format.name)
                put("category", selected.ref.category.name)
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
            (f["backend"] ?: f["reference"])?.lowercase()?.takeIf { it != "klause" } ?: SolverInvocation.KLAUSE,
            parseKlauseSearch(f, params) ?: KlauseSearch(),
            label = f["label"],
            outDir = f["out"]?.let(::File),
        )
        println("${record.problem}: feasible=${record.feasible} objective=${record.objective} proven=${record.proven}")
    }

    /** The fair arm tester: run the pool **once** as a live portfolio and rank arms by their real
     *  marginal contribution. Each problem's winner is its **best-holder** — the arm of the final
     *  incumbent, from the `%%%klause-arm:` attribution — so a greedy set-cover over the per-problem
     *  winners gives a diverse palette (see [ArmCalibration]). One run measures the arms as they
     *  actually co-run (with the portfolio's incumbent/bound sharing), so evaluating a new candidate is
     *  just adding it to the pool; an arm always shadowed by a stronger sibling earns no slot.
     *
     *  - `engine=mixed` (default) `| ls | cp`: which pool to run as `-e <engine> -p<p>` (all emit the
     *    `%%%klause-arm:` attribution under `-s`).
     *  - `p=<cores>` (default 8): the portfolio core count.
     *
     *  Optimize instances only (pass `kind=cop`). */
    private fun calibrate(filterArgs: List<String>) {
        val f = filterArgs.filter { "=" in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
        val refs = select(f)
        if (refs.isEmpty()) {
            println("(no problems matched the selection)")
            return
        }
        val engine = f["engine"]?.lowercase() ?: "mixed"
        if (engine !in setOf("mixed", "ls", "cp", "alns")) {
            error("calibrate engine must be mixed | ls | cp | alns, got '$engine'")
        }
        val cores = f["p"]?.toIntOrNull()?.coerceAtLeast(1) ?: 8
        val budget = f["timeout"]?.toLongOrNull()?.let { Budget(it) } ?: Budget()
        // `param=` is repeatable, so it is collected from the raw args rather than the dedup'd map;
        // `p=1 param=strategy=sweep` is the bandit-scheduled recipe sweep.
        val params = filterArgs.filter { it.startsWith("param=") }.map { it.substringAfter('=') }
        println("=== calibrate ($engine, -p$cores): ${refs.size} instance(s), ${budget.timeoutMillis}ms ===")
        val dir = SolveMetric.run(
            BenchLoad.resolveLazily(refs),
            budget,
            SolverInvocation.KLAUSE,
            KlauseSearch(engine = engine, processors = cores, params = params),
        ) ?: return
        val (arms, won) = portfolioWinners(dir)
        if (won.isEmpty()) {
            println(
                "\n(no optimize instances with attribution — pass kind=cop; klause emits %%%klause-arm: under -s)",
            )
            return
        }
        println()
        println(ArmCalibration.render(ArmCalibration.scoreWinnerSets(arms, won)))
    }

    /** BO config search: greedy residual rounds ([BoTuning]) that ask a [Tuner] for config
     *  points over an engine's `ConfigSpace`, evaluate each in-process on the selection, and build a
     *  diverse palette one complement per round. The reward is per instance kind — gap-to-optimum for a
     *  COP, time-to-first-feasible for a CSP — so `kind=cop` and `kind=csp` both work. Filters:
     *  `engine=ls|bt` `rounds=N` (palette size) `trials=M` (per-round asks) `batch=B` `sample=K`
     *  (problems evaluated per trial — mini-batch, so the selection is a *pool* to sample from)
     *  `timeout=<ms>` `tuner=random|vizier` `warm-start=true|false` `seed=N` (+ the usual `suite=`/`kind=`/…).
     *  Depends only on the [Tuner] seam, so the optimizer backend is swappable. */
    private fun tune(filterArgs: List<String>) {
        val f = filterArgs.filter { "=" in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
        val engine = when (f["engine"]?.lowercase()) {
            "bt", "backtrack", "cp" -> TuneEngine.BT
            "mixed", "pf", "portfolio" -> TuneEngine.MIXED
            "ls", "localsearch", "local-search", null -> TuneEngine.LS
            else -> error("tune engine must be ls | bt | mixed, got '${f["engine"]}'")
        }
        // COP (objective) → gap-to-optimum reward; CSP (satisfy) → time-to-first-feasible. The `select`
        // `kind=cop|csp` filter picks which; a mixed selection is scored per-instance by its own kind.
        // The pool is stratified from the reference tables and resolves lazily — only each round's mini-batch is
        // built, so a huge selection never materialises.
        val pool = StratifiedPool(select(f))
        if (!pool.isNotEmpty()) {
            println("(no referenced instances matched — a stratified pool needs reference-table rows)")
            return
        }
        val rounds = f["rounds"]?.toIntOrNull()?.coerceAtLeast(1) ?: 8
        val trials = f["trials"]?.toIntOrNull()?.coerceAtLeast(1) ?: 32
        val batch = f["batch"]?.toIntOrNull()?.coerceAtLeast(1) ?: 4
        val budgetMs = f["timeout"]?.toLongOrNull() ?: 2000L
        val seed = f["seed"]?.toLongOrNull() ?: 0L
        val warmStart = f["warm-start"]?.toBoolean() ?: true
        val sample = f["sample"]?.toIntOrNull()?.coerceAtLeast(1) ?: BoTuning.DEFAULT_SAMPLE_SIZE
        // `engines=ls,bt` restricts which engines the MIXED search explores (drop one to focus the sweep,
        // e.g. `engines=bt` to nail the best backtrack arm first). Ignored by the single-engine paths.
        val engines = f["engines"]?.split(",")?.map { it.trim().lowercase() }?.filter { it.isNotEmpty() }?.toSet()
            ?: setOf("ls", "bt")
        require(engines.isNotEmpty() && engines.all { it == "ls" || it == "bt" }) {
            "engines must be a comma-separated subset of ls,bt, got '${f["engines"]}'"
        }
        val tunerId = f["tuner"]?.lowercase() ?: "random"
        val tuner: Tuner = when (tunerId) {
            "vizier" -> VizierTuner()
            "random" -> RandomTuner(seed)
            else -> error("tune tuner must be random | vizier, got '${f["tuner"]}'")
        }
        val strata = pool.strata()
        val engineTag = if (engine == TuneEngine.MIXED) "$engine[${engines.sorted().joinToString(",")}]" else "$engine"
        println(
            "=== tune ($engineTag, $tunerId, warm-start=$warmStart): ${strata.values.sum()} instances over " +
                "${strata.size} strata, $rounds rounds × $trials trials × sample $sample × ${budgetMs}ms ===",
        )
        tuner.use {
            when (engine) {
                TuneEngine.LS ->
                    printPalette(BoTuning.tuneLs(pool, tuner, rounds, trials, batch, budgetMs, seed, warmStart, sample))

                TuneEngine.BT ->
                    printPalette(BoTuning.tuneBt(pool, tuner, rounds, trials, batch, budgetMs, seed, warmStart, sample))

                TuneEngine.MIXED ->
                    printMixed(
                        BoTuning.tuneMixed(
                            pool, tuner, rounds, trials, batch, budgetMs, seed, warmStart, sample, engines = engines,
                        ),
                    )
            }
        }
    }

    /** Print an LS/BT single-engine tune's residual-round palette: each round's marginal coverage gain,
     *  then the cumulative coverage after it — the concave curve flattens where added arms stop paying. */
    private fun printPalette(result: BoTuning.Result) {
        println("residual-round palette (round: +marginal coverage → cumulative):")
        result.palette.forEach { slot ->
            val gain = (slot.gain * COVERAGE_DECIMALS).toLong() / COVERAGE_DECIMALS
            val cover = (slot.cumulativeCoverage * COVERAGE_DECIMALS).toLong() / COVERAGE_DECIMALS
            println("  r${slot.round}. +$gain → $cover   ${slot.assignment}")
        }
        if (result.palette.isEmpty()) println("  (no config improved coverage — check the reward / instances)")
    }

    /** Print the MIXED campaign's three set-cover projections: the mixed order with its emergent LS:BT
     *  split (the data-derived pool ratio), then the pure per-engine orders — the covering slots of each. */
    private fun printMixed(result: BoTuning.MixedResult) {
        fun engineOf(label: String) = result.configs[label]?.get("engine") ?: "?"
        val mixed = result.mixed.diverse.filter { it.newlyCovered > 0 }
        val ls = mixed.count { engineOf(it.arm) == "ls" }
        val bt = mixed.count { engineOf(it.arm) == "bt" }
        println("mixed palette (${mixed.size} covering arms; emergent LS:BT = $ls:$bt):")
        mixed.forEach { s ->
            println(
                "  ${s.rank}. [${engineOf(
                    s.arm,
                )}] +${s.newlyCovered} → ${s.cumulativeCovered}   ${result.configs[s.arm]}",
            )
        }
        if (mixed.isEmpty()) println("  (no config improved coverage — check the reward / instances)")
        for ((name, report) in listOf("ls order" to result.ls, "bt order" to result.bt)) {
            val cover = report.diverse.filter { it.newlyCovered > 0 }
            println("$name (${cover.size} covering of ${report.diverse.size} evaluated):")
            cover.forEach { s ->
                println("  ${s.rank}. +${s.newlyCovered} → ${s.cumulativeCovered}   ${result.configs[s.arm]}")
            }
        }
    }

    /** From a portfolio run's per-problem records: every contributing arm label (the ranking pool) and,
     *  per optimize instance with attribution, the best-holder winner set (the final improvement's arm). */
    private fun portfolioWinners(dir: File): Pair<List<String>, List<Set<String>>> {
        val arms = LinkedHashSet<String>()
        val won = ArrayList<Set<String>>()
        dir.listFiles { file -> file.extension == "json" }?.sortedBy { it.name }?.forEach { jsonFile ->
            val rec = runCatching { Reports.json.decodeFromString<SolveRecord>(jsonFile.readText()) }.getOrNull()
            if (rec != null && rec.kind == "optimize" && rec.attribution.isNotEmpty()) {
                rec.attribution.forEach { arms += it.label }
                won += setOf(SolveMetric.best(rec.attribution, rec.maximize).label)
            }
        }
        return arms.toList() to won
    }

    /** Harvest per-instance reference optima/bounds into the committed table (see [ReferenceStore]) —
     *  the gap-to-optimum BO reward + a soundness oracle. Runs the reference solver (`backend=`, default
     *  `cp-sat`) over the selection — cache-replayed if already solved — then merges each instance's
     *  `{objective, proven}` into its solver's `klause-bench/reference/<solver>.csv` (virtual-best).
     *  For optima pass `kind=cop`; match the cached run's `timeout=` to replay it. */
    private fun reference(filterArgs: List<String>) {
        val f = filterArgs.filter { "=" in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
        // Best strong solver per format: cp-sat for MiniZinc (`minizinc --solver`) and XCSP3 (the CPMpy
        // container — OR-Tools has no XCSP3 frontend), clasp for DIMACS/OPB/WCNF (the Boolean formats cp-sat
        // can't read), z3 for SMT-LIB, SCIP for MPS (MIP). Other formats have no path.
        val refs = select(f).filter {
            it.format == Format.MINIZINC || it.format == Format.XCSP3 ||
                it.format == Format.DIMACS || it.format == Format.OPB || it.format == Format.WCNF ||
                it.format == Format.SMTLIB || it.format == Format.MPS
        }
        if (refs.isEmpty()) {
            println("(no MiniZinc/XCSP3/DIMACS/OPB/WCNF/SMT-LIB/MPS problems matched the selection)")
            return
        }
        val backend = (f["backend"] ?: f["reference"] ?: "cp-sat").lowercase()
        require(refs.none { it.format == Format.MINIZINC } || SolverInvocation.referenceAvailable(backend)) {
            "reference solver '$backend' is not registered with minizinc (`minizinc --solvers`)"
        }
        require(refs.none { it.format == Format.XCSP3 } || Xcsp3CpSatReference.imageAvailable()) {
            "XCSP3 reference needs the ${Xcsp3CpSatReference.IMAGE} image " +
                "(build it: docker build -t ${Xcsp3CpSatReference.IMAGE} klause-bench/xcsp3-cpsat)"
        }
        val clasp = refs.any { it.format == Format.DIMACS || it.format == Format.OPB || it.format == Format.WCNF }
        require(!clasp || ClaspReference.imageAvailable()) {
            "DIMACS/OPB reference needs the ${ClaspReference.IMAGE} image " +
                "(build it: docker build -t ${ClaspReference.IMAGE} klause-bench/clasp)"
        }
        require(refs.none { it.format == Format.SMTLIB } || Z3Reference.available()) {
            "SMT-LIB reference needs a z3 binary on PATH (`z3 --version`)"
        }
        require(refs.none { it.format == Format.MPS } || ScipReference.imageAvailable()) {
            "MPS reference needs the ${ScipReference.IMAGE} image " +
                "(build it: docker build -t ${ScipReference.IMAGE} klause-bench/scip)"
        }
        // Reap containers left by an earlier interrupted run, and again on a graceful stop (each is
        // memory-capped, so a hard kill only ever leaves bounded, short-lived ones).
        if (refs.any { it.format == Format.XCSP3 }) {
            Xcsp3CpSatReference.reapStragglers()
            Runtime.getRuntime().addShutdownHook(Thread { Xcsp3CpSatReference.reapStragglers() })
        }
        if (clasp) {
            ClaspReference.reapStragglers()
            Runtime.getRuntime().addShutdownHook(Thread { ClaspReference.reapStragglers() })
        }
        if (refs.any { it.format == Format.MPS }) {
            ScipReference.reapStragglers()
            Runtime.getRuntime().addShutdownHook(Thread { ScipReference.reapStragglers() })
        }
        val budget = f["timeout"]?.toLongOrNull()?.let { Budget(it) } ?: Budget()
        // `workers=` pins each cp-sat/choco job to that many search workers (default 1): without it the
        // reference fans out to every core, so `jobs` concurrent solves would oversubscribe the machine.
        // Total core pressure is `jobs × workers`; keep it within the box.
        val workers = (f["workers"] ?: f["processors"])?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val settings = SolverInvocation.Settings(processors = workers, free = f["fixed"]?.toBoolean() != true)
        val jobs = (f["jobs"]?.toIntOrNull() ?: DEFAULT_REFERENCE_JOBS).coerceIn(1, refs.size)
        println(
            "=== reference ($backend, ${budget.timeoutMillis}ms budget, jobs=$jobs × workers=$workers): " +
                "${refs.size} instance(s) ===",
        )
        val pool = Executors.newFixedThreadPool(jobs)
        val done = AtomicInteger()
        val harvested = try {
            refs.map { ref ->
                pool.submit(
                    Callable { solveReference(ref, backend, settings, budget, done, refs.size) },
                )
            }
                .map { it.get() }
        } finally {
            pool.shutdown()
        }
        val (added, tightened, unchanged) = ReferenceStore.mergeAndSave(harvested)
        // Report the solvers that actually produced rows (per-format: cp-sat / clasp / z3 / scip), not
        // just the requested MiniZinc `backend`, so an all-SMT or all-Boolean run names its true oracle.
        val solvers = harvested.map { it.solver }.distinct().sorted().joinToString("+").ifEmpty { backend }
        val decisive = harvested.count { it.feasible == true || it.proven }
        println(
            "\nreference table: +$added new, $tightened tightened, $unchanged unchanged " +
                "($decisive decisive, ${harvested.size} rows of ${refs.size} from $solvers)",
        )
    }

    /** Solve one instance with the reference [backend] and turn the result into a [ReferenceEntry]: a
     *  feasible witness or a proof when decisive, else an "unknown" row (an undecided timeout or an
     *  error) so every instance is covered. Reuses [BenchCache], so re-runs and resumes replay instantly
     *  and a killed sweep loses no completed work. */
    private fun solveReference(
        ref: ProblemRef,
        backend: String,
        settings: SolverInvocation.Settings,
        budget: Budget,
        counter: AtomicInteger,
        total: Int,
    ): ReferenceEntry = runCatching {
        // Per-format solver: DIMACS/OPB by clasp, XCSP3 by the CPMpy cp-sat container (OR-Tools reads no
        // XCSP3), MiniZinc by `minizinc --solver`. Each row records the solver that produced it, so the
        // table stays honest about which oracle each format came from. All cache and score identically.
        val run = ReferenceSolve.run(ref, backend, settings, budget)
        val solverId = run.solver
        val r = run.result
        val maximize = run.maximize
        r.stats["error"]?.let { println("?? ${ref.name} ERROR: $it") }
        // Proof time when proven (the solver's `solveTime`, seconds -> ms); for an unproven feasible
        // witness the time-to-first-feasible (the CSP metric); a pure timeout stores the full budget.
        val solveMs = r.stats["solveTime"]?.toDoubleOrNull()?.let { (it * 1000).toLong() }
        val elapsedMs = when {
            r.proven -> solveMs ?: budget.timeoutMillis
            r.feasible == true -> r.timeToFirstFeasibleMs ?: solveMs ?: budget.timeoutMillis
            else -> budget.timeoutMillis
        }
        val verdict = when {
            r.proven && r.feasible == false -> "UNSAT"
            r.proven -> "opt=${r.objective ?: "sat"}"
            r.feasible == true -> "best=${r.objective ?: "sat"}"
            else -> "??"
        }
        println("[${counter.incrementAndGet()}/$total] ${ref.name} = $verdict")
        // Decisive = a witness (SAT) or a proof (optimum / UNSAT). An undecided timeout still gets a row
        // — an honest "unknown" (feasible=null, no objective, unproven) — so every instance is covered;
        // the virtual-best merge keeps it from ever displacing a decisive row.
        if (r.feasible == true || r.proven) {
            ReferenceEntry(
                ReferenceStore.suiteOf(ref),
                ref.name,
                maximize,
                r.objective,
                r.feasible,
                r.proven,
                elapsedMs,
                solverId,
                budget.timeoutMillis,
            )
        } else {
            unknownRow(ref, maximize, solverId, budget)
        }
    }.getOrElse {
        // An instance the reference couldn't even run (parse/solver error) is also uncovered — record an
        // unknown row so coverage stays complete; the error is logged for visibility.
        println("?? ${ref.name} ERROR: ${it.message ?: it::class.simpleName}")
        unknownRow(ref, maximize = false, solver = ReferenceSolve.solverIdFor(ref, backend), budget = budget)
    }

    /** An "unknown" reference row for an instance the solver left undecided (timeout) or couldn't run:
     *  no objective, feasibility unknown, unproven, crediting the full budget as elapsed. */
    private fun unknownRow(ref: ProblemRef, maximize: Boolean, solver: String, budget: Budget): ReferenceEntry =
        ReferenceEntry(
            suite = ReferenceStore.suiteOf(ref),
            problem = ref.name,
            maximize = maximize,
            objective = null,
            feasible = null,
            proven = false,
            elapsedMs = budget.timeoutMillis,
            solver = solver,
            budgetMs = budget.timeoutMillis,
        )

    /** The klause-side search for a `solve` run, from `engine=` / `processors=` / `fixed=` / `param=`.
     *  Returns null when none are set. Defaults: `engine` unset ⇒ no `-e`, so klause follows the cli's
     *  own default engine (the bench has no engine default of its own); **single core** (`processors`
     *  unset ⇒ no `-p`), so multi-thread tracks pass `processors=` explicitly. `engine`/`param` forward
     *  to the cli `-e`/`--param`; `fixed=true` is the reference (`-f`) toggle. The cli owns the engine
     *  model; the bench just forwards. */
    private fun parseKlauseSearch(f: Map<String, String>, params: List<String>): KlauseSearch? {
        val anySet = listOf("engine", "processors", "fixed", "lp", "presolve", "solver-seed").any { f[it] != null } ||
            params.isNotEmpty()
        if (!anySet) return null
        return KlauseSearch(
            engine = f["engine"]?.let(::parseEngine),
            processors = f["processors"]?.toIntOrNull(),
            fixed = f["fixed"]?.toBoolean() ?: false,
            params = params,
            lp = f["lp"],
            presolve = f["presolve"],
            seed = f["solver-seed"]?.toLongOrNull(),
        )
    }

    /** Map an `engine=` alias to a klause-cli `-e` value. The cli owns the model (fixed | cp | mixed |
     *  ls | alns); the bench just forwards. */
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
    private fun selectProblems(f: Map<String, String>): List<SelectedProblem> {
        val suiteIds = f["suite"]?.split(",")?.map { it.trim() }?.flatMap { expandSuiteIds(it) }
            ?: Catalog.suites.map { it.id }
        var candidates = suiteIds.flatMap { id ->
            val default = Catalog.defaultPerFamily(id)
            Catalog.uncapped(id).problems.map { SelectedProblem(id, it, default) }
        }
        f["category"]?.split(",")?.map { Category.valueOf(it.trim().uppercase()) }?.toSet()?.let { cats ->
            candidates = candidates.filter { it.ref.category in cats }
        }
        f["tag"]?.split(",")?.map { it.trim() }?.toSet()?.let { tags ->
            candidates = candidates.filter { it.ref.tags.any { t -> t in tags } }
        }
        // `name=` is a comma-separated OR of substring-or-`*`-glob patterns: keep an instance if
        // ANY pattern matches. Lets a curated selection list specific families, e.g.
        // `name=cvrp,nfc,mario`.
        f["name"]?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.let { pats ->
            candidates = candidates.filter { c -> pats.any { matches(it, c.ref.name) } }
        }
        val wantCop = f["kind"]?.let(::parseKind)
        val capped = BenchSelection.capFamilies(
            candidates,
            perFamily = f["per-family"]?.toIntOrNull(),
            seed = f["seed"]?.toLongOrNull(),
            accept = { wantCop == null || ProblemKind.isCop(it) == wantCop },
        )
        // Families are already capped; what is left is the round-robin interleave and the overall `max`.
        val sel = CorpusSelection.Selection(maxInstances = f["max"]?.toIntOrNull())
        // `balance=format` splits `max` evenly across the formats present (water-filling short
        // formats' surplus into larger ones) instead of across families globally — so a broad
        // multi-format sweep touches every format rather than filling with the format that has the
        // most families. Unset keeps the family-global cap.
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
            |  bench calibrate [filters…]            diverse arm palette from a live pool run (kind=cop; engine=mixed|ls|cp, p=)
            |  bench reference [filters…]            harvest optima/verdicts into per-solver tables (cp-sat/clasp/z3 by format)
            |  bench preview [filters…]              show what a run would cover
            |  bench select [filters…]               the selection as JSON lines (suite, problem, …)
            |  bench solve-one suite= problem= […]   solve one instance; out=<dir> for its record
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
