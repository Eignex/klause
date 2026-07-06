package com.eignex.klause.bench.target

import com.eignex.klause.bench.catalog.Catalog
import com.eignex.klause.bench.catalog.Category
import com.eignex.klause.bench.catalog.Format
import com.eignex.klause.bench.catalog.ProblemRef
import com.eignex.klause.bench.catalog.ProblemSource
import com.eignex.klause.bench.metric.ArmCalibration
import com.eignex.klause.bench.metric.BenchCache
import com.eignex.klause.bench.metric.KlauseSearch
import com.eignex.klause.bench.metric.ReferenceEntry
import com.eignex.klause.bench.metric.ReferenceStore
import com.eignex.klause.bench.metric.SolveMetric
import com.eignex.klause.bench.metric.SolveRecord
import com.eignex.klause.bench.metric.SolverInvocation
import com.eignex.klause.bench.metric.Xcsp3CpSatReference
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.bench.runner.Budget
import com.eignex.klause.bench.source.CorpusFetcher
import com.eignex.klause.bench.source.CorpusSelection
import com.eignex.klause.bench.source.ProblemKind
import com.eignex.klause.bench.tools.ProfileConfig
import com.eignex.klause.bench.tools.ProfileEvent
import com.eignex.klause.bench.tools.ProfileScope
import kotlinx.serialization.decodeFromString
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Single entry point for the bench: `./gradlew :klause-bench:bench --args="<command>"`.
 *
 * The bench does one thing — **solve** a **selection** of problems with one solver, as a subprocess,
 * saving per-problem output (see [SolveMetric]); the offline `output/compare.sh` / `output/credit.sh`
 * scripts analyse the saved dirs. The run form:
 *
 *   `bench solve [filters…]`   e.g. `bench solve suite=smtlib-core backend=choco`
 *
 * Filters: `suite=a,b` (the token `core` expands to the in-process core) `kind=cop|csp`
 * `category=SAT,OPT` `tag=…` `name=<glob>[,…]` (comma = OR) `per-family=N` `max=N` `seed=N`
 * `backend=choco|gecode|yuck` (the solver; default klause) `timeout=<ms>`
 * `engine=fixed|cp|mixed|ls` `processors=N` `fixed=true` (references) `param=key=value`
 * `lp=off|conservative|balanced|aggressive[±id…]` (klause-cli `--lp` LP-relaxation emphasis)
 * `presolve=off|conservative|default|aggressive[,±pass…]` (klause-cli `--presolve` presolve emphasis + deltas)
 * `label=<name>` (tag the run — e.g. a klause version — so re-runs coexist instead of overwriting)
 * `profile=cpu|wall|alloc` `profile-scope=solve|all` `profile-top=N`.
 *
 * Other commands:
 *  - `calibrate [filters…]` — the fair arm tester: run the pool once as a live portfolio and rank
 *    arms into a diverse palette by per-problem best-holder wins (see [calibrate]).
 *  - `reference [filters…]` — harvest per-instance reference optima/bounds (default `backend=cp-sat`)
 *    into the committed table (see [reference]); the gap-to-optimum reward + a soundness oracle.
 *  - `preview [filters…]` — print the instances a run would cover, without running.
 *  - `list` — suites; `list <suite>` — problems in a suite.
 */
object BenchCli {
    /** Default reference-sweep concurrency: enough to keep cores busy, low enough that a handful of
     *  large-instance cp-sat solves can't exhaust memory. Override with `jobs=N`. */
    private const val DEFAULT_REFERENCE_JOBS = 6

    /** CLI entry point dispatching bench subcommands. */
    @JvmStatic
    fun main(args: Array<String>) {
        when (val cmd = args.firstOrNull() ?: "list") {
            "list", "--list", "help", "--help" -> if (args.size > 1) listProblems(args[1]) else printListing()
            "solve" -> run(args.drop(1), preview = false)
            "preview" -> run(args.drop(1), preview = true)
            "calibrate" -> calibrate(args.drop(1))
            "reference" -> reference(args.drop(1))
            else -> error("unknown command '$cmd' (commands: solve, preview, calibrate, reference, list)")
        }
    }

    /** Run `solve` over the [filterArgs] selection (or just print it when [preview]). `solve` is the
     *  bench's one measurement: one solver per invocation, as a subprocess, saving per-problem
     *  output (see [SolveMetric]); offline `output/compare.sh` / `output/credit.sh` analyse the dirs. */
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
            BenchLoad.resolveRefs(refs),
            budget,
            backend ?: SolverInvocation.KLAUSE,
            search ?: KlauseSearch(),
            profile,
            label = f["label"],
        )
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
        if (engine !in setOf("mixed", "ls", "cp")) error("calibrate engine must be mixed | ls | cp, got '$engine'")
        val cores = f["p"]?.toIntOrNull()?.coerceAtLeast(1) ?: 8
        val budget = f["timeout"]?.toLongOrNull()?.let { Budget(it) } ?: Budget()
        val entries = BenchLoad.resolveRefs(refs)
        println("=== calibrate ($engine, -p$cores): ${entries.size} instance(s), ${budget.timeoutMillis}ms ===")
        val dir = SolveMetric.run(
            entries,
            budget,
            SolverInvocation.KLAUSE,
            KlauseSearch(engine = engine, processors = cores),
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

    /** From a portfolio run's per-problem records: every contributing arm label (the ranking pool) and,
     *  per optimize instance with attribution, the best-holder winner set (the final improvement's arm). */
    private fun portfolioWinners(dir: File): Pair<List<String>, List<Set<String>>> {
        val arms = LinkedHashSet<String>()
        val won = ArrayList<Set<String>>()
        dir.listFiles { file -> file.extension == "json" }?.sortedBy { it.name }?.forEach { jsonFile ->
            val rec = runCatching { Reports.json.decodeFromString<SolveRecord>(jsonFile.readText()) }.getOrNull()
            if (rec != null && rec.kind == "optimize" && rec.attribution.isNotEmpty()) {
                rec.attribution.forEach { arms += it.label }
                won += setOf(rec.attribution.last().label)
            }
        }
        return arms.toList() to won
    }

    /** Harvest per-instance reference optima/bounds into the committed table (see [ReferenceStore]) —
     *  the gap-to-optimum BO reward + a soundness oracle. Runs the reference solver (`backend=`, default
     *  `cp-sat`) over the selection — cache-replayed if already solved — then merges each optimize
     *  instance's `{objective, proven}` into `klause-bench/reference/references.json` (virtual-best).
     *  Optimize instances only (pass `kind=cop`); match the cached run's `timeout=` to replay it. */
    private fun reference(filterArgs: List<String>) {
        val f = filterArgs.filter { "=" in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
        // cp-sat solves MiniZinc via `minizinc --solver` and XCSP3 via the CPMpy container (OR-Tools has
        // no XCSP3 frontend); both are cp-sat. Other formats have no reference path.
        val refs = select(f).filter { it.format == Format.MINIZINC || it.format == Format.XCSP3 }
        if (refs.isEmpty()) {
            println("(no MiniZinc/XCSP3 problems matched the selection)")
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
                .mapNotNull { it.get() }
        } finally {
            pool.shutdown()
        }
        val (added, tightened, unchanged) = ReferenceStore.mergeAndSave(harvested)
        println(
            "\nreference table: +$added new, $tightened tightened, $unchanged unchanged " +
                "(${harvested.size} decisive of ${refs.size} from $backend)",
        )
    }

    /** Solve one instance with the reference [backend] and turn a decisive result (a feasible witness
     *  or a proof) into a [ReferenceEntry]; a pure timeout yields null. Reuses [BenchCache], so re-runs
     *  and resumes replay instantly and a killed sweep loses no completed work. */
    private fun solveReference(
        ref: ProblemRef,
        backend: String,
        settings: SolverInvocation.Settings,
        budget: Budget,
        counter: AtomicInteger,
        total: Int,
    ): ReferenceEntry? = runCatching {
        // XCSP3 is solved by the CPMpy cp-sat container (OR-Tools reads no XCSP3); MiniZinc by
        // `minizinc --solver`. Both cache under [BenchCache] and flow through the same scoring below.
        val xcsp3 = ref.format == Format.XCSP3
        val key = BenchCache.keyFor(ref, if (xcsp3) "$backend-xcsp3" else backend, budget)
        val cached = BenchCache.load(key)
        val r: SolverInvocation.Result
        val maximize: Boolean
        if (xcsp3) {
            r = cached ?: Xcsp3CpSatReference.run(ref, budget, settings.processors ?: 1)
                .also { BenchCache.store(key, it) }
            // Objective sense is unknowable without parsing the model; the container carries it in stats.
            maximize = r.stats["maximize"].toBoolean()
        } else {
            // MiniZinc: read (optimize, maximize) from the model's solve item.
            val (optimize, max) = solveKind(ref)
            r = cached ?: SolverInvocation.runReference(ref, backend, settings, budget, optimize)
                .also { BenchCache.store(key, it) }
            maximize = max
        }
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
        // Decisive = a witness (SAT) or a proof (optimum / UNSAT); a pure timeout stores nothing.
        if (r.feasible == true || r.proven) {
            ReferenceEntry(
                suiteOf(ref),
                ref.name,
                maximize,
                r.objective,
                r.feasible,
                r.proven,
                elapsedMs,
                backend,
                budget.timeoutMillis,
            )
        } else {
            null
        }
    }.getOrElse {
        println("?? ${ref.name} ERROR: ${it.message ?: it::class.simpleName}")
        null
    }

    /** The instance's corpus id, for the reference table key (see [ReferenceEntry.suite]) — the source
     *  collection for a fetched corpus, else a path-derived label. Distinguishes same-named instances
     *  across corpora (e.g. a `queens` in hakank vs the XCSP3 archive). */
    private fun suiteOf(ref: ProblemRef): String = when (val s = ref.source) {
        is ProblemSource.External -> s.collection.id
        is ProblemSource.ExternalIndexed -> s.collection.id
        is ProblemSource.Vendored -> s.workspaceRelPath.substringBeforeLast('/', "vendored")
        is ProblemSource.InCode -> "in-code"
    }

    /** Read `(optimize, maximize)` from the model's `solve` item (comments stripped) so the reference
     *  path needs no klause `Problem`. A `satisfy` model — or one whose solve item is not in the top
     *  `.mzn` — is treated as a CSP: feasibility only, no `-a` enumeration. */
    private fun solveKind(ref: ProblemRef): Pair<Boolean, Boolean> {
        val stripped = CorpusFetcher.resolve(ref.source).readText()
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), " ")
            .replace(Regex("%[^\n]*"), " ")
        val keyword = Regex("""\bsolve\b[^;]*?\b(satisfy|minimize|maximize)\b""", RegexOption.DOT_MATCHES_ALL)
            .findAll(stripped).lastOrNull()?.groupValues?.get(1)
        return when (keyword) {
            "maximize" -> true to true
            "minimize" -> true to false
            else -> false to false
        }
    }

    /** The klause-side search for a `solve` run, from `engine=` / `processors=` / `fixed=` / `param=`.
     *  Returns null when none are set. Defaults: `engine` unset ⇒ no `-e`, so klause follows the cli's
     *  own default engine (the bench has no engine default of its own); **single core** (`processors`
     *  unset ⇒ no `-p`), so multi-thread tracks pass `processors=` explicitly. `engine`/`param` forward
     *  to the cli `-e`/`--param`; `fixed=true` is the reference (`-f`) toggle. The cli owns the engine
     *  model; the bench just forwards. */
    private fun parseKlauseSearch(f: Map<String, String>, params: List<String>): KlauseSearch? {
        val anySet = listOf("engine", "processors", "fixed", "lp", "presolve").any { f[it] != null } ||
            params.isNotEmpty()
        if (!anySet) return null
        return KlauseSearch(
            engine = f["engine"]?.let(::parseEngine),
            processors = f["processors"]?.toIntOrNull(),
            fixed = f["fixed"]?.toBoolean() ?: false,
            params = params,
            lp = f["lp"],
            presolve = f["presolve"],
        )
    }

    /** Map an `engine=` alias to a klause-cli `-e` value. The cli owns the model (fixed | cp | mixed |
     *  ls); the bench just forwards. */
    private fun parseEngine(name: String): String = when (name.lowercase()) {
        "cp", "backtrack", "bt" -> "cp"
        "ls", "localsearch", "local-search" -> "ls"
        "mixed", "portfolio", "pf" -> "mixed"
        "fixed", "fd" -> "fixed"
        else -> error("engine must be fixed|cp|mixed|ls, got '$name'")
    }

    /** Build the selection from filters: suites (`core` expands to the in-process core;
     *  static-only unless named) → kind/category/tag/name filter → family-aware caps/sampling.
     *  `kind=cop|csp` is applied *before* sampling (via [ProblemKind]) so a capped selection
     *  fills its cap with the requested kind rather than under-filling. */
    private fun select(f: Map<String, String>): List<ProblemRef> {
        var refs: List<ProblemRef> = f["suite"]?.split(",")?.flatMap { expandSuite(it.trim()) }
            ?: Catalog.suites.flatMap { it.problems }
        f["kind"]?.let { kind ->
            val wantCop = parseKind(kind)
            refs = refs.filter { ProblemKind.isCop(it) == wantCop }
        }
        f["category"]?.split(",")?.map { Category.valueOf(it.trim().uppercase()) }?.toSet()?.let { cats ->
            refs = refs.filter { it.category in cats }
        }
        f["tag"]?.split(
            ",",
        )?.map { it.trim() }?.toSet()?.let { tags -> refs = refs.filter { it.tags.any { t -> t in tags } } }
        // `name=` is a comma-separated OR of substring-or-`*`-glob patterns: keep an instance if
        // ANY pattern matches. Lets a curated selection list specific families, e.g.
        // `name=cvrp,nfc,mario`.
        f["name"]?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.let { pats ->
            refs = refs.filter { ref -> pats.any { matches(it, ref.name) } }
        }
        val sel = CorpusSelection.Selection(
            perFamily = f["per-family"]?.toIntOrNull(),
            maxInstances = f["max"]?.toIntOrNull(),
            sampleSeed = f["seed"]?.toLongOrNull(),
        )
        val selected = CorpusSelection.applySelectionBy(refs, sel) { it.name.substringBefore('/') }
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
    private fun expandSuite(token: String): List<ProblemRef> = when (token) {
        "core" -> Targets.IN_PROCESS_CORE.flatMap { Catalog.suite(it).problems }
        else -> Catalog.suite(token).problems
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
            |  bench reference [filters…]            harvest cp-sat optima into the committed reference table (kind=cop)
            |  bench preview [filters…]              show what a run would cover
            |  bench list [<suite>]                  list suites, or problems in a suite
            |
            |Filters: suite=a,b (suite=core = in-process core) kind=cop|csp category=SAT,OPTIMIZATION
            |         tag=… name=<glob>[,…] (comma=OR) per-family=N max=N seed=N backend=<minizinc solver id> timeout=<ms>
            |         engine=fixed|cp|mixed|ls processors=N (klause search for solve)
            |         lp=off|conservative|balanced|aggressive[±id] (klause-cli --lp LP emphasis)
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
