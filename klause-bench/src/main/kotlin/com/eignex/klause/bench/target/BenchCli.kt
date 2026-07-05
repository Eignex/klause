package com.eignex.klause.bench.target

import com.eignex.klause.bench.catalog.Catalog
import com.eignex.klause.bench.catalog.Category
import com.eignex.klause.bench.catalog.ProblemRef
import com.eignex.klause.bench.metric.ArmCalibration
import com.eignex.klause.bench.metric.KlauseSearch
import com.eignex.klause.bench.metric.SolveMetric
import com.eignex.klause.bench.metric.SolveRecord
import com.eignex.klause.bench.metric.SolverInvocation
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.bench.runner.Budget
import com.eignex.klause.bench.source.CorpusSelection
import com.eignex.klause.bench.source.ProblemKind
import com.eignex.klause.bench.tools.ProfileConfig
import com.eignex.klause.bench.tools.ProfileEvent
import com.eignex.klause.bench.tools.ProfileScope
import com.eignex.klause.localsearch.strategy.LsCatalog
import com.eignex.klause.portfolio.BacktrackCatalog
import com.eignex.klause.portfolio.Kind
import kotlinx.serialization.decodeFromString
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors

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
 * `engine=fixed|cp|mixed|ls|cp-single|ls-single` `processors=N` `fixed=true` (references) `param=key=value`
 * `lp=off|conservative|balanced|aggressive[±id…]` (klause-cli `--lp` LP-relaxation emphasis)
 * `presolve=off|conservative|default|aggressive[,±pass…]` (klause-cli `--presolve` presolve emphasis + deltas)
 * `label=<name>` (tag the run — e.g. a klause version — so re-runs coexist instead of overwriting)
 * `profile=cpu|wall|alloc` `profile-scope=solve|all` `profile-top=N`.
 *
 * Other commands:
 *  - `calibrate [filters…]` — the fair arm tester: run every LS arm in isolation over the selection
 *    and recalibrate a diverse palette by per-problem wins under the Challenge rules (see [calibrate]).
 *  - `preview [filters…]` — print the instances a run would cover, without running.
 *  - `list` — suites; `list <suite>` — problems in a suite.
 */
object BenchCli {
    /** CLI entry point dispatching bench subcommands. */
    @JvmStatic
    fun main(args: Array<String>) {
        when (val cmd = args.firstOrNull() ?: "list") {
            "list", "--list", "help", "--help" -> if (args.size > 1) listProblems(args[1]) else printListing()
            "solve" -> run(args.drop(1), preview = false)
            "preview" -> run(args.drop(1), preview = true)
            "calibrate" -> calibrate(args.drop(1))
            else -> error("unknown command '$cmd' (commands: solve, preview, calibrate, list)")
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

    /** The fair arm tester: run every candidate arm **in isolation** (one subprocess each, full
     *  budget, single core — no shared incumbent) over the selection, then score by the MiniZinc-
     *  Challenge rules and recalibrate a diverse palette (see [ArmCalibration]). Optimize instances
     *  only (pass `kind=cop`).
     *
     *  - `engine=ls` (default): candidates are [LsCatalog] arm labels (or an `arms=a,b` subset), each
     *    run as `-e ls --param arm=<label>`; scored **incomplete** (LS proves nothing).
     *  - `engine=cp`: candidates are [BacktrackCatalog] arm labels (or an `arms=a,b` subset), each run
     *    as `-e cp --param bt-arm=<label>` (a one-arm backtrack pool); scored **complete**.
     *  - `engine=cp-single`: candidates are the `var-selector` heuristics given in `arms=v1,v2`, each
     *    run as `-e cp-single --param var-selector=<v>`; scored **complete**.
     *
     *  `mode=complete|incomplete` overrides the per-engine default. `jobs=N` sweeps arms across N
     *  threads (default one per core); use `jobs=1` for contention-free timing when the `faster`
     *  tiebreak matters. */
    private fun calibrate(filterArgs: List<String>) {
        val f = filterArgs.filter { "=" in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
        val refs = select(f)
        if (refs.isEmpty()) {
            println("(no problems matched the selection)")
            return
        }
        val engine = f["engine"]?.lowercase() ?: "ls"
        val (paramKey, defaults) = when (engine) {
            "ls" -> "arm" to LsCatalog.labels()
            "cp" -> "bt-arm" to BacktrackCatalog.labels(Kind.COP)
            "cp-single", "cpsingle" -> "var-selector" to emptyList()
            else -> error("calibrate supports engine=ls | cp | cp-single, got '$engine'")
        }
        val arms = f["arms"]?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?: defaults.ifEmpty { error("engine=$engine needs an arms= list (e.g. arms=vsids,chb,linucb)") }
        val complete = when (f["mode"]?.lowercase()) {
            "complete" -> true
            "incomplete" -> false
            null -> engine != "ls"
            else -> error("mode must be complete|incomplete, got '${f["mode"]}'")
        }
        val budget = f["timeout"]?.toLongOrNull()?.let { Budget(it) } ?: Budget()
        // Arms are independent isolated subprocesses writing distinct output/cache keys, so we sweep
        // them across `jobs` threads (default: one per core, capped at the arm count). Each arm still
        // runs its instances serially at processors=1. `jobs=1` keeps timing clean when the `faster`
        // tiebreak matters (small/final runs); jobs>1 trades some timing-under-contention noise for
        // wall-clock — fine for the quality-dominated win-share ranking.
        val jobs = (f["jobs"]?.toIntOrNull() ?: minOf(arms.size, Runtime.getRuntime().availableProcessors()))
            .coerceAtLeast(1)
        val entries = BenchLoad.resolveRefs(refs)
        println(
            "=== calibrate ($engine): ${arms.size} arm(s) × ${refs.size} instance(s), " +
                "${budget.timeoutMillis}ms each (isolated, jobs=$jobs) ===",
        )
        val pool = Executors.newFixedThreadPool(jobs)
        val armDirs = try {
            arms.map { arm ->
                pool.submit(
                    Callable {
                        arm to SolveMetric.run(
                            entries,
                            budget,
                            SolverInvocation.KLAUSE,
                            KlauseSearch(engine = engine, processors = 1, params = listOf("$paramKey=$arm")),
                        )
                    },
                )
            }.mapNotNull { future -> future.get().let { (arm, dir) -> dir?.let { arm to dir } } }.toMap()
        } finally {
            pool.shutdown()
        }
        val instances = loadCalibration(armDirs)
        if (instances.isEmpty()) {
            println("\n(no optimize instances scored — pass kind=cop)")
            return
        }
        println()
        println(ArmCalibration.render(ArmCalibration.score(instances, complete)))
    }

    /** Read each arm's isolated `solve` output dir back into per-instance [ArmCalibration.Instance]s,
     *  grouping by problem. */
    private fun loadCalibration(armDirs: Map<String, File>): List<ArmCalibration.Instance> {
        val byProblem = LinkedHashMap<String, MutableList<Pair<String, SolveRecord>>>()
        for ((arm, dir) in armDirs) {
            dir.listFiles { file -> file.extension == "json" }?.sortedBy { it.name }?.forEach { jsonFile ->
                val rec = runCatching { Reports.json.decodeFromString<SolveRecord>(jsonFile.readText()) }.getOrNull()
                if (rec != null && rec.kind == "optimize") {
                    byProblem.getOrPut(rec.problem) { mutableListOf() }.add(arm to rec)
                }
            }
        }
        return byProblem.map { (problem, runs) ->
            ArmCalibration.Instance(
                problem = problem,
                maximize = runs.first().second.maximize,
                runs = runs.map { (arm, rec) -> toArmRun(arm, rec) },
            )
        }
    }

    private fun toArmRun(arm: String, rec: SolveRecord): ArmCalibration.ArmRun = ArmCalibration.ArmRun(
        arm = arm,
        feasible = rec.feasible == true,
        finalObjective = rec.objective,
        proven = rec.proven,
        timeToBestMs = rec.timeToBestMs,
    )

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
     *  ls | cp-single); the bench just forwards. */
    private fun parseEngine(name: String): String = when (name.lowercase()) {
        "cp", "backtrack", "bt" -> "cp"
        "ls", "localsearch", "local-search" -> "ls"
        "mixed", "portfolio", "pf" -> "mixed"
        "fixed", "fd" -> "fixed"
        "cp-single", "cpsingle" -> "cp-single"
        "ls-single", "lssingle" -> "ls-single"
        else -> error("engine must be fixed|cp|mixed|ls|cp-single|ls-single, got '$name'")
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
            |  bench calibrate [filters…]            fair-test arms; diverse palette (kind=cop; engine=ls|cp|cp-single, mode=, jobs=)
            |  bench preview [filters…]              show what a run would cover
            |  bench list [<suite>]                  list suites, or problems in a suite
            |
            |Filters: suite=a,b (suite=core = in-process core) kind=cop|csp category=SAT,OPTIMIZATION
            |         tag=… name=<glob>[,…] (comma=OR) per-family=N max=N seed=N backend=<minizinc solver id> timeout=<ms>
            |         engine=fixed|cp|mixed|ls|cp-single|ls-single processors=N (klause search for solve)
            |         lp=off|conservative|balanced|aggressive[±id] (klause-cli --lp LP emphasis)
            |         presolve=off|conservative|default|aggressive[,±pass] (klause-cli --presolve)
            |         fixed=true (reference -f toggle)  param=key=value (klause-cli --param; cp-single only for var-/val-selector)
            |         label=<name> (tag the run, e.g. a klause version, so re-runs coexist as distinct dirs)
            |         profile=cpu|wall|alloc profile-scope=solve|all profile-top=N
            |
            |Examples:
            |  bench solve suite=mzn-bench kind=cop per-family=1               (klause, engine=fixed ×1 by default)
            |  bench solve suite=mzn-bench backend=choco timeout=300000        (Choco baseline)
            |  bench solve suite=mzn-bench backend=yuck timeout=300000         (Yuck baseline)
            |  bench solve suite=mzn-bench engine=cp processors=8              (klause parallel backtrack portfolio)
            |  bench solve suite=mzn-bench engine=cp-single param=var-selector=vsids (heuristic A/B: re-run with =chb, then compare.sh)
            |  bench solve suite=mzn-bench engine=fixed                        (klause follows the model annotation)
            |
            |To compare configs, run `solve` once per config (each writes output/<config>/) and diff dirs offline.
            """.trimMargin(),
        )
    }
}
