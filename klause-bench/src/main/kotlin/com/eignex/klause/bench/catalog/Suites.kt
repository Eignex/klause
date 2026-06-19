package com.eignex.klause.bench.catalog

import com.eignex.klause.bench.source.CorpusSelection
import com.eignex.klause.bench.source.LibminizincExpected
import com.eignex.klause.model.PbOp
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.factor.bool.Cardinality
import com.eignex.klause.solver.factor.bool.Clause
import com.eignex.klause.solver.factor.bool.PseudoBoolean
import com.eignex.klause.solver.factor.bool.Xor
import com.eignex.klause.solver.factor.global.AllDifferent
import com.eignex.klause.solver.factor.linear.Linear
import com.eignex.klause.solver.factor.linear.LinearOp
import com.eignex.klause.solver.factor.linear.ReifiedCardinality
import com.eignex.klause.solver.factor.linear.ReifiedLinear

/**
 * Catalog content: every [Suite] the bench knows about, grouped by where the instances come
 * from.
 *
 *  - [handwrittenCore] — small SAT/CSP instances built directly in Kotlin (`InCode`).
 *  - [dimacsCore]/[opbCore]/[schemaCore]/[flatzincCore] — vendored under `klause-bench/smoke-corpus/`.
 *  - [mznSmoke] — the in-tree `klause-mzn-lib/test-models/` smoke set (referenced, not copied).
 *  - external MiniZinc/SAT collections (fetched on demand) are declared in [ExternalCollections].
 */
internal object Suites {

    val all: List<Suite> by lazy {
        listOf(
            handwrittenCore, slackAllDifferent, dimacsCore, opbCore, schemaCore, flatzincCore,
            smtlibCore, xcsp3Core, mznSmoke, satlibUf20, satLadder, satCrafted,
        )
    }

    /** Discovered-on-demand suites over fetched external corpora. The provider runs the
     *  [com.eignex.klause.bench.source.CorpusSelection] machinery (per-family caps / sampling
     *  via `-Dklause.bench.select.*`) and fetches the collection on first resolve. */
    val dynamic: List<DynamicSuite> by lazy {
        listOf(
            DynamicSuite("mzn-bench", "MiniZinc Challenge benchmarks (fetched; 1/family by default)") {
                CorpusSelection.select(
                    ExternalCollections.minizincBenchmarks,
                    CorpusSelection.Layout.MznChallenge(),
                    CorpusSelection.Selection.fromProps(defaultPerFamily = 1),
                    Category.OPTIMIZATION,
                )
            },
            DynamicSuite("libminizinc-tests", "libminizinc compiler test suite (fetched; 1/family by default)") {
                CorpusSelection.select(
                    ExternalCollections.libminizincTests,
                    CorpusSelection.Layout.FlatMzn("tests/spec/unit"),
                    CorpusSelection.Selection.fromProps(defaultPerFamily = 1),
                    Category.CSP,
                    expected = { LibminizincExpected.parse(it) },
                )
            },
            DynamicSuite("hakank", "hakank MiniZinc collection (fetched, sparse minizinc/; 1/family by default)") {
                CorpusSelection.select(
                    ExternalCollections.hakank,
                    CorpusSelection.Layout.FlatMzn("minizinc"),
                    CorpusSelection.Selection.fromProps(defaultPerFamily = 1),
                    Category.CSP,
                )
            },
        )
    }

    // --- Slack all_different (Hall-prone; for explanation / clause-learning A/B) ---

    /**
     * Golomb-ruler feasibility built directly as a [Problem]: marks 0 = m0 < m1 < ... < m,
     * with the C(m,2) pairwise differences channeled into aux int vars (domain `1..maxLen`)
     * and constrained all_different. Because `maxLen` >> C(m,2) the all_different is *slack*
     * (more values than vars ⇒ strict Hall sub-sets / multiple Régin SCCs), unlike the tight
     * n-vars/n-values all_differents in latin_square/sudoku. That's exactly the regime where a
     * responsible-subset (Hall-set) conflict explanation can differ from an all-vars one, so
     * this suite is the discriminating workload for that sharpening. `maxLen >= optimal` ⇒ SAT.
     */
    private fun golomb(m: Int, maxLen: Int): Problem {
        val pairs = buildList { for (i in 0 until m) for (j in i + 1 until m) add(i to j) }
        val nDiffs = pairs.size
        val numInt = m + nDiffs
        val domains = Array(numInt) { idx ->
            when {
                idx == 0 -> IntDomain(0, 0)

                // mark[0] pinned to 0
                idx < m -> IntDomain(0, maxLen)

                // marks
                else -> IntDomain(1, maxLen) // differences
            }
        }
        val factors = ArrayList<Factor>()
        // Strictly increasing marks: mark[i] - mark[i+1] <= -1.
        for (i in 0 until m - 1) factors.add(Linear(intArrayOf(1, -1), intArrayOf(i, i + 1), LinearOp.LE, -1))
        // Difference channeling: mark[j] - mark[i] - d = 0.
        pairs.forEachIndexed { k, (i, j) ->
            factors.add(Linear(intArrayOf(1, -1, -1), intArrayOf(j, i, m + k), LinearOp.EQ, 0))
        }
        // All differences distinct — the slack all_different under test.
        factors.add(AllDifferent(IntArray(nDiffs) { m + it }, domainMin = 1, domainSize = maxLen))
        // Symmetry break: first gap shorter than last (d_first - d_last <= -1).
        factors.add(Linear(intArrayOf(1, -1), intArrayOf(m, m + nDiffs - 1), LinearOp.LE, -1))
        return Problem(numBoolVars = 0, numIntVars = numInt, intDomains = domains, factors = factors.toTypedArray())
    }

    private val slackAllDifferent = suite("slack-alldiff", "Slack all_different (Golomb rulers; Hall-prone)") {
        license = "internal"
        // (m, maxLen) with maxLen >= optimal ruler length ⇒ satisfiable; a spread of slack/size.
        for ((m, maxLen) in listOf(6 to 17, 6 to 20, 7 to 25, 7 to 30, 7 to 40, 8 to 45, 8 to 50)) {
            inCode("golomb_${m}_$maxLen", Category.CSP, Expected.Sat) { golomb(m, maxLen) }
        }
    }

    // --- In-code SAT/CSP ---

    private val handwrittenCore = suite("handwritten-core", "Small hand-built SAT/CSP instances") {
        license = "internal"

        inCode("threeClauses", Category.SAT, Expected.Sat) {
            Problem(
                numBoolVars = 4,
                numIntVars = 0,
                intDomains = emptyArray(),
                factors = arrayOf<Factor>(
                    Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, false))),
                    Clause(intArrayOf(Lit.make(0, false), Lit.make(2, true), Lit.make(3, true))),
                    Clause(intArrayOf(Lit.make(1, false), Lit.make(3, true))),
                ),
            )
        }
        inCode("cardXor", Category.SAT, Expected.Sat) {
            Problem(
                numBoolVars = 4,
                numIntVars = 0,
                intDomains = emptyArray(),
                factors = arrayOf<Factor>(
                    Cardinality(
                        intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true), Lit.make(3, true)),
                        min = 2,
                        max = 3,
                    ),
                    Xor(intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true)), targetParity = 1),
                ),
            )
        }
        inCode("pseudoBoolean", Category.SAT, Expected.Sat) {
            Problem(
                numBoolVars = 4,
                numIntVars = 0,
                intDomains = emptyArray(),
                factors = arrayOf<Factor>(
                    PseudoBoolean(
                        weights = intArrayOf(2, 3, 1, 1),
                        literals = intArrayOf(
                            Lit.make(0, true),
                            Lit.make(1, true),
                            Lit.make(2, true),
                            Lit.make(3, true),
                        ),
                        op = PbOp.LE,
                        bound = 3,
                    ),
                    Clause(intArrayOf(Lit.make(2, true), Lit.make(3, true))),
                ),
            )
        }
        inCode("linearLE", Category.CSP, Expected.Sat) {
            Problem(
                numBoolVars = 0,
                numIntVars = 2,
                intDomains = arrayOf(IntDomain(0, 3), IntDomain(0, 3)),
                factors = arrayOf<Factor>(
                    Linear(coeffs = intArrayOf(1, 1), vars = intArrayOf(0, 1), op = LinearOp.LE, 4),
                    Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 1),
                    Linear(intArrayOf(1), intArrayOf(1), LinearOp.LE, 2),
                ),
            )
        }
        inCode("permutation3", Category.CSP, Expected.Sat) {
            Problem(
                numBoolVars = 0,
                numIntVars = 3,
                intDomains = arrayOf(IntDomain(0, 2), IntDomain(0, 2), IntDomain(0, 2)),
                factors = arrayOf<Factor>(AllDifferent(vars = intArrayOf(0, 1, 2), domainMin = 0, domainSize = 3)),
            )
        }
        inCode("mixedBoolInt", Category.CSP, Expected.Sat) {
            Problem(
                numBoolVars = 2,
                numIntVars = 1,
                intDomains = arrayOf(IntDomain(0, 3)),
                factors = arrayOf<Factor>(
                    Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
                    Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 2),
                ),
            )
        }
        inCode("cardinalityStress", Category.SAT, Expected.Sat) {
            Problem(
                numBoolVars = 8,
                numIntVars = 0,
                intDomains = emptyArray(),
                factors = arrayOf<Factor>(
                    Cardinality((0..5).map { Lit.make(it, true) }.toIntArray(), min = 0, max = 3),
                    Cardinality((0..7).map { Lit.make(it, true) }.toIntArray(), min = 3, max = 8),
                    Cardinality(intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true)), min = 2, max = 2),
                    Cardinality(intArrayOf(Lit.make(2, true), Lit.make(3, true)), min = 0, max = 1),
                    Cardinality((0..7).map { Lit.make(it, true) }.toIntArray(), min = 3, max = 8),
                    Cardinality((0..7).map { Lit.make(it, true) }.toIntArray(), min = 0, max = 5),
                ),
            )
        }
        inCode("pbReifiedMix", Category.SAT, Expected.Sat) {
            Problem(
                numBoolVars = 6,
                numIntVars = 0,
                intDomains = emptyArray(),
                factors = arrayOf<Factor>(
                    ReifiedCardinality(
                        auxBoolVar = 4,
                        literals = intArrayOf(Lit.make(1, true), Lit.make(2, true)),
                        min = 2,
                        max = 2,
                    ),
                    Clause(intArrayOf(Lit.make(0, false), Lit.make(4, true))),
                    ReifiedCardinality(
                        auxBoolVar = 5,
                        literals = intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true)),
                        min = 1,
                        max = 3,
                    ),
                    Clause(intArrayOf(Lit.make(3, false), Lit.make(5, true))),
                    Cardinality(
                        intArrayOf(
                            Lit.make(0, false),
                            Lit.make(1, true),
                            Lit.make(2, true),
                            Lit.make(3, false),
                        ),
                        min = 0,
                        max = 2,
                    ),
                    PseudoBoolean(
                        weights = intArrayOf(1, 2, 3, 4),
                        literals = intArrayOf(
                            Lit.make(0, true),
                            Lit.make(1, true),
                            Lit.make(2, true),
                            Lit.make(3, true),
                        ),
                        op = PbOp.GE,
                        bound = 3,
                    ),
                ),
            )
        }
        inCode("smallRandom3sat", Category.SAT, Expected.Sat) {
            Problem(
                numBoolVars = 12,
                numIntVars = 0,
                intDomains = emptyArray(),
                factors = arrayOf<Factor>(
                    Clause(intArrayOf(Lit.make(0, true), Lit.make(3, false), Lit.make(7, true))),
                    Clause(intArrayOf(Lit.make(1, false), Lit.make(4, true), Lit.make(8, false))),
                    Clause(intArrayOf(Lit.make(2, true), Lit.make(5, false), Lit.make(9, true))),
                    Clause(intArrayOf(Lit.make(0, false), Lit.make(6, true), Lit.make(10, false))),
                    Clause(intArrayOf(Lit.make(3, true), Lit.make(7, false), Lit.make(11, true))),
                    Clause(intArrayOf(Lit.make(1, true), Lit.make(5, true), Lit.make(9, false))),
                    Clause(intArrayOf(Lit.make(2, false), Lit.make(4, false), Lit.make(11, true))),
                    Clause(intArrayOf(Lit.make(0, true), Lit.make(8, true), Lit.make(10, true))),
                    Clause(intArrayOf(Lit.make(6, false), Lit.make(7, true), Lit.make(8, false))),
                    Clause(intArrayOf(Lit.make(1, false), Lit.make(2, false), Lit.make(3, true))),
                    Clause(intArrayOf(Lit.make(4, true), Lit.make(5, true), Lit.make(6, true))),
                    Clause(intArrayOf(Lit.make(7, false), Lit.make(9, false), Lit.make(11, false))),
                    Clause(intArrayOf(Lit.make(0, false), Lit.make(2, true), Lit.make(5, true))),
                    Clause(intArrayOf(Lit.make(3, false), Lit.make(8, true), Lit.make(10, false))),
                    Clause(intArrayOf(Lit.make(1, true), Lit.make(6, false), Lit.make(11, false))),
                    Clause(intArrayOf(Lit.make(0, true), Lit.make(4, false), Lit.make(9, true))),
                    Clause(intArrayOf(Lit.make(2, true), Lit.make(7, true), Lit.make(10, false))),
                    Clause(intArrayOf(Lit.make(3, true), Lit.make(5, false), Lit.make(8, false))),
                    Clause(intArrayOf(Lit.make(1, false), Lit.make(6, true), Lit.make(11, true))),
                    Clause(intArrayOf(Lit.make(0, false), Lit.make(2, false), Lit.make(11, true))),
                    Clause(intArrayOf(Lit.make(4, true), Lit.make(7, false), Lit.make(9, false))),
                    Clause(intArrayOf(Lit.make(5, true), Lit.make(8, true), Lit.make(10, true))),
                    Clause(intArrayOf(Lit.make(1, true), Lit.make(3, true), Lit.make(6, false))),
                    Clause(intArrayOf(Lit.make(0, true), Lit.make(5, false), Lit.make(7, false))),
                    Clause(intArrayOf(Lit.make(2, false), Lit.make(8, true), Lit.make(11, false))),
                    Clause(intArrayOf(Lit.make(3, false), Lit.make(6, true), Lit.make(9, true))),
                    Clause(intArrayOf(Lit.make(4, false), Lit.make(10, true), Lit.make(11, true))),
                    Clause(intArrayOf(Lit.make(0, false), Lit.make(1, false), Lit.make(8, false))),
                    Clause(intArrayOf(Lit.make(2, true), Lit.make(3, false), Lit.make(4, true))),
                    Clause(intArrayOf(Lit.make(5, true), Lit.make(6, true), Lit.make(7, true))),
                ),
            )
        }
        inCode("budgetCampaign", Category.ASSIGNMENT, Expected.Sat) {
            Problem(
                numBoolVars = 3,
                numIntVars = 1,
                intDomains = arrayOf(IntDomain(0, 100)),
                factors = arrayOf<Factor>(
                    Cardinality.exactlyOne(
                        intArrayOf(
                            Lit.make(0, true),
                            Lit.make(1, true),
                            Lit.make(2, true),
                        ),
                    ),
                    ReifiedLinear(auxBoolVar = 0, coeffs = intArrayOf(1), vars = intArrayOf(0), op = LinearOp.EQ, 30),
                    ReifiedLinear(auxBoolVar = 1, coeffs = intArrayOf(1), vars = intArrayOf(0), op = LinearOp.EQ, 50),
                    ReifiedLinear(auxBoolVar = 2, coeffs = intArrayOf(1), vars = intArrayOf(0), op = LinearOp.EQ, 80),
                    Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 60),
                ),
            )
        }

        inCode("clauseContradiction", Category.UNSAT, Expected.Unsat) {
            Problem(
                numBoolVars = 1,
                numIntVars = 0,
                intDomains = emptyArray(),
                factors = arrayOf<Factor>(
                    Clause(intArrayOf(Lit.make(0, true))),
                    Clause(intArrayOf(Lit.make(0, false))),
                ),
            )
        }
        inCode("intEqContradiction", Category.UNSAT, Expected.Unsat) {
            Problem(
                numBoolVars = 0,
                numIntVars = 1,
                intDomains = arrayOf(IntDomain(0, 3)),
                factors = arrayOf<Factor>(
                    Linear(intArrayOf(1), intArrayOf(0), LinearOp.EQ, 1),
                    Linear(intArrayOf(1), intArrayOf(0), LinearOp.EQ, 3),
                ),
            )
        }
        inCode("pigeonhole", Category.UNSAT, Expected.Unsat) {
            Problem(
                numBoolVars = 0,
                numIntVars = 3,
                intDomains = arrayOf(IntDomain(0, 1), IntDomain(0, 1), IntDomain(0, 1)),
                factors = arrayOf<Factor>(AllDifferent(vars = intArrayOf(0, 1, 2), domainMin = 0, domainSize = 2)),
            )
        }
    }

    // --- Vendored smoke-corpus suites (klause-bench/smoke-corpus/) ---

    private val dimacsCore = suite("dimacs-core", "Curated small DIMACS CNF (SAT + UNSAT)") {
        format = Format.DIMACS
        license = "SATLIB-style (public benchmarks)"
        vendored("php4", Category.UNSAT, Expected.Unsat)
        vendored("php3", Category.UNSAT, Expected.Unsat)
        vendored("implication-chain", Category.SAT, Expected.Sat)
        vendored("bipartite-2col", Category.SAT, Expected.Sat)
        vendored("random3sat-20-80", Category.SAT, Expected.Sat)
        vendored("random3sat-50-200", Category.SAT, Expected.Sat)
    }

    private val opbCore = suite("opb-core", "Curated pseudo-Boolean OPB") {
        format = Format.OPB
        license = "internal"
        vendored("setcover-tiny", Category.PACKING, Expected.Sat)
        vendored("pb-cardinality", Category.SAT, Expected.Sat)
    }

    private val schemaCore = suite("schema-core", "klause JSON schema instances") {
        format = Format.JSON_SCHEMA
        license = "internal"
        vendored("campaign", Category.ASSIGNMENT, Expected.Sat)
        vendored("roster", Category.ASSIGNMENT, Expected.Sat)
    }

    private val flatzincCore = suite("flatzinc-core", "Curated small FlatZinc (satisfaction)") {
        format = Format.FLATZINC
        license = "internal"
        vendored("cardinality", Category.CSP, Expected.Sat)
        vendored("permutation4", Category.CSP, Expected.Sat)
        vendored("small-linear", Category.CSP, Expected.Sat)
        vendored("magic-square-3", Category.CSP, Expected.Sat)
        vendored("graph-coloring-4cycle", Category.CSP, Expected.Sat)
        vendored("element-channel", Category.CSP, Expected.Sat)
    }

    private val smtlibCore = suite("smtlib-core", "Curated SMT-LIB QF_LIA instances") {
        format = Format.SMTLIB_QF_LIA
        license = "internal"
        vendored("lia-basic", Category.CSP, Expected.Sat)
        vendored("lia-opt", Category.OPTIMIZATION, Expected.Opt(7))
        vendored("lia-unsat", Category.UNSAT, Expected.Unsat)
        vendored("lia-disjunction", Category.CSP, Expected.Sat)
    }

    private val xcsp3Core = suite("xcsp3-core", "Curated XCSP3 integer CSP/COP instances") {
        format = Format.XCSP3
        license = "internal"
        vendored("magic-series-tiny", Category.CSP, Expected.Sat, relPath = "xcsp3/magic-series-tiny.xml")
        vendored("sum-opt-tiny", Category.OPTIMIZATION, Expected.Unknown, relPath = "xcsp3/sum-opt-tiny.xml")
        vendored("magic-square-3", Category.CSP, Expected.Sat, relPath = "xcsp3/magic-square-3.xml")
        vendored("graph-coloring-tiny", Category.CSP, Expected.Sat, relPath = "xcsp3/graph-coloring-tiny.xml")
    }

    // --- External SAT collection (auto-fetched SATLIB tarball) ---

    private val satlibUf20 = suite("satlib-uf20", "SATLIB uf20-91 SAT instances (auto-fetched sample)") {
        // The full set is 1000 instances; reference a small, stable sample so the suite is
        // usable out of the box. CorpusFetcher downloads the tarball on first resolve.
        format = Format.DIMACS
        val col = ExternalCollections.satlibUf20
        // Tarball names instances `uf20-0<n>.cnf` (raw, unpadded n). Reference a small sample.
        for (n in 1..5) {
            external("uf20-$n", col, "uf20-0$n.cnf", Category.SAT, Expected.Sat)
        }
    }

    // --- SAT performance datasets ---

    /** SATLIB random-3SAT phase-transition ladder (uf=SAT, uuf=UNSAT), V=50…250 — labelled
     *  instances at increasing size for measuring CDCL scaling. A small sample per family
     *  (the full tarball is fetched once and cached). */
    private val satLadder = suite(
        "sat-ladder",
        "SATLIB random-3SAT phase-transition ladder (uf=SAT / uuf=UNSAT, 50–250 vars)",
    ) {
        format = Format.DIMACS
        license = "SATLIB (public benchmarks)"
        for ((name, col) in ExternalCollections.satlibLadder) {
            val sat = name.startsWith("uf")
            // The SATLIB tarballs sample instances with inconsistent zero-padding
            // (uf50-031.cnf, uf50-0433.cnf, …), so a guessed filename like uf50-01.cnf doesn't
            // exist. Reference the first few by index over the sorted collection instead.
            for (n in 0 until 5) {
                externalIndexed(
                    "$name-${n + 1}",
                    col,
                    index = n,
                    ext = "cnf",
                    category = if (sat) Category.SAT else Category.UNSAT,
                    expected = if (sat) Expected.Sat else Expected.Unsat,
                )
            }
        }
    }

    /** In-code crafted SAT: pigeonhole PHPₙ (UNSAT, CDCL stress) + random-3SAT at the phase
     *  transition. No fetch / no license; parametric for performance regression tracking. */
    private val satCrafted = suite(
        "sat-crafted",
        "In-code crafted SAT: pigeonhole (UNSAT) + random-3SAT (phase transition)",
    ) {
        for (n in 5..9) inCode("php$n", Category.UNSAT, Expected.Unsat) { SatGenerators.php(n) }
        for (v in intArrayOf(
            50,
            100,
            150,
            200,
            250,
        )) {
            inCode("rand3sat-$v", Category.SAT, Expected.Unknown) { SatGenerators.random3Sat(v) }
        }
    }

    private val mznSmoke = suite("mzn-smoke", "Mandatory MiniZinc smoke models (CI parity)") {
        format = Format.MINIZINC
        license = "internal"
        val base = "klause-mzn-lib/test-models"
        workspace("argmax", "$base/argmax.mzn", Category.CSP, Expected.Sat)
        workspace("bin_packing", "$base/bin_packing.mzn", Category.PACKING, Expected.Sat)
        workspace("connected", "$base/connected.mzn", Category.CSP, Expected.Sat)
        workspace("diffn_nonstrict_k", "$base/diffn_nonstrict_k.mzn", Category.PACKING, Expected.Sat)
        workspace("graph_coloring", "$base/graph_coloring.mzn", Category.CSP, Expected.Sat)
        workspace("latin_square", "$base/latin_square.mzn", Category.CSP, Expected.Sat)
        workspace("magic_square", "$base/magic_square.mzn", Category.CSP, Expected.Sat)
        workspace("queens", "$base/queens.mzn", Category.CSP, Expected.Sat)
        workspace("send_more_money", "$base/send_more_money.mzn", Category.CSP, Expected.Sat)
        workspace("sudoku", "$base/sudoku.mzn", Category.CSP, Expected.Sat)
        workspace("zero_one_knapsack", "$base/zero_one_knapsack.mzn", Category.PACKING, Expected.Unknown)
    }
}

/**
 * Non-vendored problem collections, fetched on demand by `source.CorpusFetcher`. Declared
 * here so the "where did this come from / why isn't it in the repo" answer is auditable.
 */
internal object ExternalCollections {
    val minizincBenchmarks = ExternalCollection(
        id = "minizinc-benchmarks",
        url = "https://github.com/MiniZinc/minizinc-benchmarks.git",
        license = "GPLv3 + per-problem licenses",
        reason = "GPLv3 — redistribution outside a fetch cache is restricted",
        fetch = FetchMethod.GitClone(depth = 1),
    )
    val libminizincTests = ExternalCollection(
        id = "libminizinc-tests",
        url = "https://github.com/MiniZinc/libminizinc.git",
        license = "MPL-2.0",
        reason = "owned by MiniZinc CI; tracked upstream rather than copied",
        fetch = FetchMethod.GitClone(depth = 1),
    )
    val hakank = ExternalCollection(
        id = "hakank",
        url = "https://github.com/hakank/hakank.git",
        license = "GPL (per-file varies)",
        reason = "large (~1GB); sparse-checkout of the minizinc/ subtree only",
        fetch = FetchMethod.GitClone(depth = 1, sparsePath = "minizinc"),
    )
    val satlibUf20 = ExternalCollection(
        id = "satlib-uf20-91",
        url = "https://www.cs.ubc.ca/~hoos/SATLIB/Benchmarks/SAT/RND3SAT/uf20-91.tar.gz",
        license = "SATLIB (public benchmarks)",
        reason = "1000-instance set; fetched rather than vendored wholesale",
        fetch = FetchMethod.Tarball,
    )
    val satlibUuf50 = ExternalCollection(
        id = "satlib-uuf50-218",
        url = "https://www.cs.ubc.ca/~hoos/SATLIB/Benchmarks/SAT/RND3SAT/uuf50-218.tar.gz",
        license = "SATLIB (public benchmarks)",
        reason = "1000-instance set; fetched rather than vendored wholesale",
        fetch = FetchMethod.Tarball,
    )

    val all = listOf(minizincBenchmarks, libminizincTests, hakank, satlibUf20, satlibUuf50)

    // --- XCSP3 competition library (instance archives, xcsp.org / CRIL) ---
    // Instances ship as individually `.xml.lzma`-compressed files inside each zip; the
    // coverage tool decompresses them on the fly. Per-year full archives cover 2017–2019 (no
    // competition in 2020–2021); the 2022–2025 range is taken from the two curated main-track
    // aggregates published at xcsp.org/instances (COP22to25, CSP22to25) rather than the
    // overlapping per-year archives — same range, deduplicated to the CSP/COP tracks klause targets.
    private fun xcsp(year: Int, mb: Int) = ExternalCollection(
        id = "xcsp3-$year",
        url = "https://www.cril.univ-artois.fr/~lecoutre/compets/instancesXCSP${year % 100}.zip",
        license = "XCSP3 competition (academic benchmarks)",
        reason = "${mb}MB competition archive; fetched rather than vendored",
        fetch = FetchMethod.Zip,
    )

    /** A curated main-track aggregate spanning the 2022–2025 competitions, published at
     *  xcsp.org/instances: `COP22to25` (1000 COP instances) and `CSP22to25` (800 CSP). */
    private fun xcspAggregate(track: String, count: Int) = ExternalCollection(
        id = "xcsp3-${track.lowercase()}-22to25",
        url = "https://www.cril.univ-artois.fr/~lecoutre/compets/${track}22to25.zip",
        license = "XCSP3 competition (academic benchmarks)",
        reason = "$count main-track $track instances (2022–2025 aggregate); fetched rather than vendored",
        fetch = FetchMethod.Zip,
    )
    val xcsp3Competition = listOf(
        xcsp(2017, 102),
        xcsp(2018, 69),
        xcsp(2019, 136),
        xcspAggregate("COP", 1000),
        xcspAggregate("CSP", 800),
    )

    /** SMT-LIB QF_LIA non-incremental benchmark set (official CLC repository). */
    val smtlibQfLia = ExternalCollection(
        id = "smtlib-qf_lia",
        url = "https://clc-gitlab.cs.uiowa.edu:2443/SMT-LIB-benchmarks/QF_LIA.git",
        license = "SMT-LIB (per-family licenses)",
        reason = "large benchmark set; fetched rather than vendored",
        fetch = FetchMethod.GitClone(depth = 1),
    )

    /** SATLIB random-3SAT phase-transition ladder: `uf<V>-<C>` (SAT) and `uuf<V>-<C>` (UNSAT),
     *  V = 50…250. Each is a 1000-instance family fetched on demand; flat `*.cnf` inside. */
    private fun satlibRnd(name: String) = ExternalCollection(
        id = "satlib-$name",
        url = "https://www.cs.ubc.ca/~hoos/SATLIB/Benchmarks/SAT/RND3SAT/$name.tar.gz",
        license = "SATLIB (public benchmarks)",
        reason = "1000-instance RND3SAT family; fetched rather than vendored",
        fetch = FetchMethod.Tarball,
    )

    /** Ordered rungs (low→high vars) of the SAT and UNSAT ladders, keyed by family name. */
    val satlibLadder: Map<String, ExternalCollection> = listOf(
        "uf50-218", "uf75-325", "uf100-430", "uf125-538", "uf150-645",
        "uf175-753", "uf200-860", "uf225-960", "uf250-1065",
        "uuf50-218", "uuf75-325", "uuf100-430", "uuf125-538", "uuf150-645",
        "uuf175-753", "uuf200-860", "uuf225-960", "uuf250-1065",
    ).associateWith { satlibRnd(it) }
}
