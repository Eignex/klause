package com.eignex.klause.bench.catalog

import com.eignex.klause.bench.runner.MiniZincCompatibility
import com.eignex.klause.bench.source.CorpusSelection
import com.eignex.klause.bench.source.LibminizincExpected
import com.eignex.klause.bench.source.OrLibrary
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedCardinality
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.bool.PseudoBoolean
import com.eignex.klause.factor.bool.Xor
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.model.PbOp

/**
 * Catalog suites: in-code problems, vendored smoke fixtures, MiniZinc test models,
 * and the on-demand corpora declared in [ExternalCollections].
 */
internal object Suites {

    val all: List<Suite> by lazy {
        listOf(
            handwrittenCore, slackAllDifferent, dimacsCore, wcnfCore, opbCore, schemaCore,
            smtlibCore, xcsp3Core, mpsCore, mznSmoke, satCrafted, lpComponentSplit, productAliases,
        ) + QfNraSuites.all + QfNraCanonicalSuite.suite + QfNraPartialConflictSuite.suite +
            QfNraCoupledSuite.suite + QfNraPreparedSupportSuite.suite +
            NonlinearIntegerSuite.suite + NonlinearIntegerCellSuite.suite + NonlinearIntegerResidueSuite.suite +
            NonlinearIntegerImageSuite.suite +
            NonlinearIntegerBooleanResidueSuite.suite +
            DivModSuite.suite + AbsSuite.suite +
            ContinuousArithmeticSuite.suite + MpsLinearSourceSuite.suite + LpLifecycleSuite.suite +
            LpLifecycleSuite.heldoutSuite +
            SourceOwnershipSuite.suite + RewriteStructureSuite.suite + RewriteStructureSuite.canonicalSuite +
            ExactGlobalsSuite.suite +
            SourceEqualitySuite.suite +
            IntegerAggregateSuite.suite
    }

    /** Discovered-on-demand suites over fetched external corpora. The provider runs the
     *  [com.eignex.klause.bench.source.CorpusSelection] machinery (per-family caps / sampling
     *  via `-Dklause.bench.select.*`) and fetches the collection on first resolve. */
    val dynamic: List<DynamicSuite> by lazy {
        listOf(
            DynamicSuite(
                "mzn-bench",
                "MiniZinc Challenge benchmarks, every year 2008-present (fetched; 1/family by default)",
                defaultPerFamily = 1,
            ) { sel ->
                CorpusSelection.select(
                    ExternalCollections.minizincChallenge,
                    CorpusSelection.Layout.MznChallengeAllYears(),
                    sel,
                    Category.OPTIMIZATION,
                )
            },
            DynamicSuite(
                "mzn-bench-compatible-v1",
                "MiniZinc Challenge installed compiler compatibility selection v1 (1/family by default)",
                defaultPerFamily = 1,
                admit = { MiniZincCompatibility.admit("mzn-bench-compatible-v1", it) },
            ) { sel ->
                CorpusSelection.select(
                    ExternalCollections.minizincChallenge,
                    CorpusSelection.Layout.MznChallengeAllYears(legacyExclusions = false),
                    sel,
                    Category.OPTIMIZATION,
                )
            },
            DynamicSuite(
                "libminizinc-tests",
                "libminizinc compiler test suite (fetched; 1/family by default)",
                defaultPerFamily = 1,
            ) { sel ->
                CorpusSelection.select(
                    ExternalCollections.libminizincTests,
                    CorpusSelection.Layout.FlatMzn("tests/spec/unit"),
                    sel,
                    Category.CSP,
                    expected = { LibminizincExpected.parse(it) },
                )
            },
            DynamicSuite(
                "hakank",
                "hakank MiniZinc collection (fetched; 1/family by default)",
                defaultPerFamily = 1,
            ) { sel ->
                CorpusSelection.select(
                    ExternalCollections.hakank,
                    CorpusSelection.Layout.FlatMzn("minizinc"),
                    sel,
                    Category.CSP,
                )
            },
            archiveSuite(
                "smtlib-qflia",
                "SMT-LIB QF_LIA non-incremental set (fetched, full ~13k .smt2; 1/family by default)",
                ExternalCollections.smtlibQfLia,
                "non-incremental/QF_LIA",
            ),
            archiveSuite(
                "smtlib-qflra",
                "SMT-LIB QF_LRA non-incremental set (fetched, ~1.7k .smt2; 1/family by default)",
                ExternalCollections.smtlibQfLra,
                "non-incremental/QF_LRA",
            ),
            archiveSuite(
                "smtlib-qfnra",
                "SMT-LIB QF_NRA archive (fetched; membership unclassified; 1/family by default)",
                ExternalCollections.smtlibQfNra,
                "non-incremental/QF_NRA",
            ),
            archiveSuite(
                "smtlib-qfidl",
                "SMT-LIB QF_IDL non-incremental set (fetched, integer difference logic; 1/family by default)",
                ExternalCollections.smtlibQfIdl,
                "non-incremental/QF_IDL",
            ),
            archiveSuite(
                "smtlib-qfrdl",
                "SMT-LIB QF_RDL non-incremental set (fetched, real difference logic; 1/family by default)",
                ExternalCollections.smtlibQfRdl,
                "non-incremental/QF_RDL",
            ),
            archiveSuite(
                "smtlib-qflira",
                "SMT-LIB QF_LIRA non-incremental set (fetched, small mixed int/real .smt2; 1/family by default)",
                ExternalCollections.smtlibQfLira,
                "non-incremental/QF_LIRA",
            ),
            DynamicSuite(
                "miplib2017",
                "MIPLIB 2017 collection (fetched per-instance, ~1065 .mps; 1/family by default)",
                defaultPerFamily = 1,
            ) { sel ->
                CorpusSelection.select(
                    ExternalCollections.miplib2017,
                    CorpusSelection.Layout.Flat("", "mps", familyOf = ::miplibStem),
                    sel,
                    Category.OPTIMIZATION,
                    format = Format.MPS,
                )
            },
            DynamicSuite(
                "miplib2017-csp",
                "Objective-free MIPLIB 2017 feasibility models with source constraints and bounds retained",
                defaultPerFamily = 1,
            ) { sel ->
                CorpusSelection.select(
                    ExternalCollections.miplib2017,
                    CorpusSelection.Layout.Flat("", "mps", familyOf = ::miplibStem),
                    sel,
                    Category.CSP,
                    format = Format.MPS,
                ).map { it.copy(source = ProblemSource.MpsFeasibility(it.source)) }
            },
            DynamicSuite(
                "dimacs-classic",
                "SATLIB classic structured DIMACS CNF — diverse, small Boolean set " +
                    "(aim/jnh/dubois/parity/inductive-inference/pigeon-hole/all-interval)",
            ) { sel ->
                ExternalCollections.dimacsClassic.flatMap { (col, category) ->
                    CorpusSelection.select(
                        col,
                        CorpusSelection.Layout.Flat("", "cnf"),
                        sel,
                        category,
                        format = Format.DIMACS,
                    )
                }
            },
            DynamicSuite(
                "miplib2010",
                "MIPLIB 2010 collection (fetched, ~361 .mps; 1/family by default)",
                defaultPerFamily = 1,
            ) { sel ->
                CorpusSelection.select(
                    ExternalCollections.miplib2010,
                    CorpusSelection.Layout.Flat(
                        "miplib2010-1.1.3/instances/miplib2010",
                        "mps",
                        familyOf = ::miplibStem,
                    ),
                    sel,
                    Category.OPTIMIZATION,
                    format = Format.MPS,
                )
            },
            DynamicSuite("miplib2003", "MIPLIB 2003 (fetched, 60 .mps)") { sel ->
                CorpusSelection.select(
                    ExternalCollections.miplib2003,
                    CorpusSelection.Layout.Flat("", "mps", familyOf = ::miplibStem),
                    sel,
                    Category.OPTIMIZATION,
                    format = Format.MPS,
                )
            },
            DynamicSuite("miplib3", "MIPLIB 3 (fetched, 65 .mps)") { sel ->
                CorpusSelection.select(
                    ExternalCollections.miplib3,
                    CorpusSelection.Layout.Flat("miplib3", "mps", familyOf = ::miplibStem),
                    sel,
                    Category.OPTIMIZATION,
                    format = Format.MPS,
                )
            },
            DynamicSuite(
                "orlib",
                "OR-Library MIP classes, converted to MPS: set covering, airline set partitioning, " +
                    "warehouse location, " +
                    "generalised assignment, multidimensional knapsack, bin packing, aircraft landing (fetched, ~600)",
            ) { sel ->
                ExternalCollections.orLibrary.flatMap { col ->
                    CorpusSelection.select(
                        col,
                        CorpusSelection.Layout.Flat("", "mps", familyOf = { col.id.removePrefix("orlib-") }),
                        sel,
                        Category.OPTIMIZATION,
                        format = Format.MPS,
                    )
                }
            },
            DynamicSuite(
                "satlib",
                "Every SATLIB instance: the structured families and every random-3SAT set, uf=SAT / uuf=UNSAT " +
                    "(fetched, ~6600; one family per set)",
            ) { sel ->
                ExternalCollections.satlib.flatMap { (col, category) ->
                    CorpusSelection.select(
                        col,
                        CorpusSelection.Layout.Flat("", "cnf", familyOf = { col.id.removePrefix("satlib-") }),
                        sel,
                        category,
                        format = Format.DIMACS,
                    )
                }
            },
            DynamicSuite(
                "satcomp-compact-v1",
                "SAT Competition 2022-24 main-track CNF Kissat decides in 5-120 s: 40 pinned instances, " +
                    "one per family, 20 SAT / 20 UNSAT (fetched from GBD, ~13MB)",
            ) { sel ->
                val expected = SatCompCompact.instances.associate { it.relPath to it.expected }
                CorpusSelection.select(
                    ExternalCollections.satcompCompact,
                    CorpusSelection.Layout.Flat("", "cnf"),
                    sel,
                    Category.SAT,
                    format = Format.DIMACS,
                ).map { ref ->
                    val verdict = expected.getValue("${ref.name}.cnf")
                    val category = if (verdict == Expected.Unsat) Category.UNSAT else Category.SAT
                    ref.copy(expected = verdict, category = category)
                }
            },
            DynamicSuite(
                "pb-comp",
                "Pseudo-Boolean Competition 2024 selected OPB set (fetched; 1/family by default)",
                defaultPerFamily = 1,
            ) { sel ->
                // The archive ships both `.opb.xz` (linear + non-linear PB) and `.wbo.xz` (soft
                // constraints); this layout takes the `.opb` instances, `pb-comp-wbo` the `.wbo`.
                CorpusSelection.select(
                    ExternalCollections.pbComp2024,
                    CorpusSelection.Layout.Flat("PB24", "opb", familyOf = { it.substringBeforeLast('/', it) }),
                    sel,
                    Category.OPTIMIZATION,
                    format = Format.OPB,
                )
            },
            DynamicSuite(
                "pb-comp-wbo",
                "Pseudo-Boolean Competition 2024 WBO soft-constraint set (fetched; 1/family)",
                defaultPerFamily = 1,
            ) { sel ->
                CorpusSelection.select(
                    ExternalCollections.pbComp2024,
                    CorpusSelection.Layout.Flat("PB24", "wbo", familyOf = { it.substringBeforeLast('/', it) }),
                    sel,
                    Category.OPTIMIZATION,
                    format = Format.OPB,
                )
            },
            // The `PB25` subdir assumes the PB24 tar layout (`PB<year>/...`); an archive with another
            // shape discovers zero instances.
            DynamicSuite(
                "pb-comp-2025",
                "Pseudo-Boolean Competition 2025 selected OPB set (fetched; 1/family by default)",
                defaultPerFamily = 1,
            ) { sel ->
                CorpusSelection.select(
                    ExternalCollections.pbComp2025,
                    CorpusSelection.Layout.Flat("PB25", "opb", familyOf = { it.substringBeforeLast('/', it) }),
                    sel,
                    Category.OPTIMIZATION,
                    format = Format.OPB,
                )
            },
            DynamicSuite(
                "pb07-opb",
                "PB'07 native pseudo-Boolean OPB benchmarks (fetched, 487 crafted instances; " +
                    "optimization/decision, linear + non-linear)",
            ) { sel ->
                CorpusSelection.select(
                    ExternalCollections.pb07Other,
                    CorpusSelection.Layout.Flat(
                        "normalized-PB07",
                        "opb",
                        familyOf = { it.substringBeforeLast('/', it) },
                    ),
                    sel,
                    Category.OPTIMIZATION,
                    format = Format.OPB,
                )
            },
            DynamicSuite(
                "xcsp3-cop",
                "XCSP3 competition COP aggregate 2022-25 (fetched, 1000; cap with max=)",
            ) { sel ->
                CorpusSelection.select(
                    ExternalCollections.xcsp3Cop,
                    CorpusSelection.Layout.Flat("COP22to25", "xml", familyOf = ::xcspSeries),
                    sel,
                    Category.OPTIMIZATION,
                    format = Format.XCSP3,
                )
            },
            DynamicSuite(
                "xcsp3-csp",
                "XCSP3 competition CSP aggregate 2022-25 (fetched, 800; cap with max=)",
            ) { sel ->
                CorpusSelection.select(
                    ExternalCollections.xcsp3Csp,
                    CorpusSelection.Layout.Flat("CSP22to25", "xml", familyOf = ::xcspSeries),
                    sel,
                    Category.CSP,
                    format = Format.XCSP3,
                )
            },
            DynamicSuite(
                "maxsat-unweighted",
                "MaxSAT Evaluation 2024 exact unweighted track (fetched; 1/family)",
                defaultPerFamily = 1,
            ) { sel ->
                CorpusSelection.select(
                    ExternalCollections.maxsatExactUnweighted,
                    CorpusSelection.Layout.Flat("", "wcnf", familyOf = ::maxsatFamily),
                    sel,
                    Category.OPTIMIZATION,
                    format = Format.WCNF,
                )
            },
            DynamicSuite(
                "maxsat-weighted",
                "MaxSAT Evaluation 2024 exact weighted track (fetched; 1/family)",
                defaultPerFamily = 1,
            ) { sel ->
                CorpusSelection.select(
                    ExternalCollections.maxsatExactWeighted,
                    CorpusSelection.Layout.Flat("", "wcnf", familyOf = ::maxsatFamily),
                    sel,
                    Category.OPTIMIZATION,
                    format = Format.WCNF,
                )
            },
        )
    }

    /**
     * Golomb-ruler feasibility: ordered marks with pairwise differences in `1..maxLen`,
     * constrained all-different. The excess values create slack Hall sets and multiple
     * strongly connected components in the matching graph of Régin's filtering (J.-C. Régin,
     * "A filtering algorithm for constraints of difference in CSPs", AAAI 1994,
     * https://mlanthology.org/aaai/1994/regin1994aaai-filtering), exercising responsible-subset
     * conflict explanations. SAT when `maxLen` is at least the optimum length.
     */
    private fun golomb(m: Int, maxLen: Int): Problem {
        val pairs = buildList { for (i in 0 until m) for (j in i + 1 until m) add(i to j) }
        val nDiffs = pairs.size
        val numInt = m + nDiffs
        val domains = Array(numInt) { idx ->
            when {
                idx == 0 -> IntDomain(0, 0)

                // Remaining marks; mark[0] is pinned to 0 above to break translation symmetry.
                idx < m -> IntDomain(0, maxLen.toLong())

                else -> IntDomain(1, maxLen.toLong())
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
                        weights = longArrayOf(2, 3, 1, 1),
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
                        weights = longArrayOf(1, 2, 3, 4),
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

    private val wcnfCore = suite("wcnf-core", "Curated DIMACS WCNF (MaxSAT)") {
        format = Format.WCNF
        license = "internal"
        vendored("conditional-cost", Category.OPTIMIZATION, Expected.Opt(1L),
            relPath = "conditional-symmetry/conditional-cost.wcnf")
        vendored("maxsat-tiny", Category.OPTIMIZATION, Expected.Opt(1L))
        vendored("cover-dominated", Category.OPTIMIZATION, Expected.Opt(1L))
        vendored("cover-tied", Category.OPTIMIZATION, Expected.Opt(1L))
        vendored("cover-other-reader", Category.OPTIMIZATION, Expected.Opt(1L))
        vendored("weighted-penalty-complements", Category.OPTIMIZATION, Expected.Opt(5L))
        vendored("signed-cost-symmetry", Category.OPTIMIZATION, Expected.Opt(2L),
            relPath = "cost-symmetry/signed-cost.wcnf", license = "Apache-2.0")
    }

    private val opbCore = suite("opb-core", "Curated pseudo-Boolean OPB") {
        format = Format.OPB
        license = "internal"
        vendored("setcover-tiny", Category.PACKING, Expected.Sat)
        vendored("pb-cardinality", Category.SAT, Expected.Sat)
        vendored(
            "sporttournament06",
            Category.OPTIMIZATION,
            Expected.Opt(-6L),
            license = "PB Competition (academic benchmarks)",
        )
        vendored(
            "queens4-soft",
            Category.OPTIMIZATION,
            Expected.Opt(0L),
            relPath = "opb/queens4-soft.wbo",
            license = "PB Competition (academic benchmarks)",
        )
    }

    private val schemaCore = suite("schema-core", "klause JSON schema instances") {
        format = Format.JSON_SCHEMA
        license = "internal"
        vendored("campaign", Category.ASSIGNMENT, Expected.Sat)
        vendored("roster", Category.ASSIGNMENT, Expected.Sat)
    }

    private val smtlibCore = suite("smtlib-core", "Curated SMT-LIB QF_LIA and QF_LRA instances") {
        format = Format.SMTLIB
        license = "internal"
        vendored("lia-basic", Category.CSP, Expected.Sat)
        vendored("lia-opt", Category.OPTIMIZATION, Expected.Opt(7))
        vendored("lia-big-min", Category.OPTIMIZATION, Expected.Sat)
        vendored("lia-big-max", Category.OPTIMIZATION, Expected.Sat)
        vendored("lia-adjacent-min", Category.OPTIMIZATION, Expected.Sat)
        vendored("lia-adjacent-max", Category.OPTIMIZATION, Expected.Sat)
        vendored("lira-rational-min", Category.OPTIMIZATION, Expected.Sat)
        vendored("lira-rational-max", Category.OPTIMIZATION, Expected.Sat)
        vendored("lira-wide-rational", Category.CSP, Expected.Sat)
        vendored("lia-unbounded", Category.OPTIMIZATION, Expected.Sat)
        vendored("lia-parity-unsat", Category.CSP, Expected.Unsat)
        vendored("lia-unsat", Category.UNSAT, Expected.Unsat)
        vendored("lia-disjunction", Category.CSP, Expected.Sat)
        vendored("lra-rational", Category.CSP, Expected.Sat)
        vendored("lia-wide-span", Category.CSP, Expected.Sat)
    }

    /** XCSP3 family key: the series prefix before the first `-`. Competition instance names encode
     *  the series then a `-`-joined parameter tail (`AircraftAssemblyLine-1-178-00-0_c23`), so this
     *  groups the ~76 COP / CSP series for `per-family` sampling instead of treating every
     *  parameterization as its own singleton family. */
    private fun xcspSeries(name: String): String = name.substringBefore('-')

    /** MaxSAT family key: the descriptive prefix before the first `-` of a flat MSE filename
     *  (`causal-discovery-causal_n6_…` → `causal`), grouping parameterizations for `per-family`
     *  sampling instead of treating every instance as its own family. */
    private fun maxsatFamily(name: String): String = name.substringBefore('-')

    /** MIPLIB family key: the instance-name stem of a flat `.mps` extraction — the name lowercased,
     *  stripped of any extension and of a trailing `-<digits>`/`_<digits>` parameterization suffix
     *  (`neos-1122047` → `neos`, `rmatr100-p10` → `rmatr100-p10`). The extraction is flat, so without
     *  this every instance would be its own family and a `per-family` cap would be inert. */
    private fun miplibStem(name: String): String =
        name.substringAfterLast('/').substringBefore('.').replace(PARAM_SUFFIX, "").lowercase()

    private val PARAM_SUFFIX = Regex("""[-_]\d+$""")

    private val xcsp3Core = suite("xcsp3-core", "Curated XCSP3 integer CSP/COP instances") {
        format = Format.XCSP3
        license = "internal"
        vendored("magic-series-tiny", Category.CSP, Expected.Sat, relPath = "xcsp3/magic-series-tiny.xml")
        vendored("sum-opt-tiny", Category.OPTIMIZATION, Expected.Unknown, relPath = "xcsp3/sum-opt-tiny.xml")
        vendored("pair-cover-costs", Category.OPTIMIZATION, Expected.Unknown, relPath = "xcsp3/pair-cover-costs.xml")
        vendored("pair-cover-alias-costs", Category.OPTIMIZATION, Expected.Unknown,
            relPath = "xcsp3/pair-cover-alias-costs.xml")
        vendored("pair-cover-alias-replaced", Category.OPTIMIZATION, Expected.Unknown,
            relPath = "xcsp3/pair-cover-alias-replaced.xml")
        vendored("evidence-latin4", Category.OPTIMIZATION, Expected.Unknown,
            relPath = "finite-repair-evidence/latin4.xml")
        vendored("equality-cost-symmetry", Category.OPTIMIZATION, Expected.Opt(90L),
            relPath = "cost-symmetry/equality-cost.xml", license = "Apache-2.0")
        for (name in listOf("linear-exchange", "linear-three-cycle", "linear-four-cycle", "linear-knapsack",
            "linear-transport", "linear-kernel-weighted", "linear-kernel-ring33")) {
            vendored("evidence-$name", Category.OPTIMIZATION, Expected.Unknown,
                relPath = "finite-repair-evidence/$name.xml")
        }
        vendored("magic-square-3", Category.CSP, Expected.Sat, relPath = "xcsp3/magic-square-3.xml")
        vendored("graph-coloring-tiny", Category.CSP, Expected.Sat, relPath = "xcsp3/graph-coloring-tiny.xml")
        vendored("coupled-cost-k4", Category.OPTIMIZATION, Expected.Opt(136), relPath = "coupled-cost/k4.xml")
        vendored("assignment-exchange", Category.OPTIMIZATION, Expected.Unknown,
            relPath = "finite-repair/assignment-exchange.xml")
        for (name in listOf("resource-exchange", "placement-exchange", "assignment-cycle", "resource-cycle",
            "placement-cycle", "assignment-four-cycle", "assignment-unused")) {
            vendored(name, Category.OPTIMIZATION, Expected.Unknown, relPath = "finite-repair/$name.xml")
        }
        for (name in listOf("overlap-tables-unsat", "overlap-mdd-unsat", "overlap-mdd-sat")) {
            vendored(name, Category.CSP, if (name.endsWith("unsat")) Expected.Unsat else Expected.Sat,
                relPath = "extensional-overlap/$name.xml")
        }
        vendored("cyclic-mod3-unsat", Category.OPTIMIZATION, Expected.Unsat,
            relPath = "extensional-overlap/cyclic-mod3-unsat.xml", license = "Apache-2.0")
        vendored("cyclic-mod3-sat", Category.OPTIMIZATION, Expected.Opt(4L),
            relPath = "extensional-overlap/cyclic-mod3-sat.xml", license = "Apache-2.0")
        for (name in listOf("interior", "hole", "outside-reader")) {
            vendored("table-substitution-$name", Category.OPTIMIZATION,
                Expected.Opt(if (name == "hole") 36L else 35L),
                relPath = "table-substitution/$name.xml", license = "Apache-2.0")
        }
    }

    private val lpComponentSplit = suite("lp-component-split", "Targeted separable and linked dense LP models") {
        license = "internal"
        for ((name, optimum) in listOf("cover-2x3" to 4L, "cover-3x3" to 6L)) {
            vendored("mps-$name", Category.OPTIMIZATION, Expected.Opt(optimum),
                relPath = "lp-component-split/$name.mps", format = Format.MPS)
        }
        for (name in listOf("dense-2x12", "dense-8x24", "linked-8x24")) {
            vendoredMzn("mzn-$name", Category.OPTIMIZATION, Expected.Opt(1), model = "lp-component-split/$name.mzn")
        }
        for (name in listOf("dense-2x12", "dense-8x24")) {
            vendored(
                "mps-$name",
                Category.CSP,
                Expected.Sat,
                relPath = "lp-component-split/$name.mps",
                format = Format.MPS,
            )
        }
    }

    private val mpsCore = suite("mps-core", "Curated MPS (MIP) integer + bounded-float instances") {
        format = Format.MPS
        license = "internal"
        vendored("blend-tiny", Category.OPTIMIZATION, Expected.Opt(9))
        vendored("feasible-tiny", Category.CSP, Expected.Sat)
        vendored("float-tiny", Category.CSP, Expected.Sat)
        vendored("infeasible-tiny", Category.UNSAT, Expected.Unsat)
        vendored("pure-real-min", Category.OPTIMIZATION, Expected.Sat)
        vendored("pure-real-max", Category.OPTIMIZATION, Expected.Sat)
        vendored("pure-real-unsat", Category.UNSAT, Expected.Unsat)
        vendored("pure-real-unbounded", Category.OPTIMIZATION, Expected.Sat)
        vendored("open-real-min", Category.OPTIMIZATION, Expected.Sat)
        vendored("open-real-max", Category.OPTIMIZATION, Expected.Sat)
        vendored("open-rounded-objective", Category.OPTIMIZATION, Expected.Sat)
        vendored("open-rounded-sat", Category.CSP, Expected.Sat)
    }

    /** Parametric pigeonhole and random-3SAT workloads for performance regression tracking. */
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

    private val productAliases = suite("product-aliases", "Repeated product roles and fixed-power chains") {
        license = "internal"
        for (name in listOf("square", "power-chain", "result-aliases", "power-scopes")) {
            vendoredMzn(name, Category.CSP, Expected.Sat, model = "product-aliases/$name.mzn")
        }
    }

    private val mznSmoke = suite("mzn-smoke", "Mandatory MiniZinc smoke models (CI parity)") {
        format = Format.MINIZINC
        license = "internal"
        val base = "klause-mzn-lib/test-models"
        workspace("argmax", "$base/klause-test-argmax.mzn", Category.CSP, Expected.Sat)
        workspace("bin_packing", "$base/klause-test-bin_packing.mzn", Category.PACKING, Expected.Sat)
        workspace("connected", "$base/klause-test-connected.mzn", Category.CSP, Expected.Sat)
        workspace("diffn_nonstrict_k", "$base/klause-test-diffn_nonstrict_k.mzn", Category.PACKING, Expected.Sat)
        workspace("graph_coloring", "$base/klause-test-graph_coloring.mzn", Category.CSP, Expected.Sat)
        workspace("latin_square", "$base/klause-test-latin_square.mzn", Category.CSP, Expected.Sat)
        workspace("magic_square", "$base/klause-test-magic_square.mzn", Category.CSP, Expected.Sat)
        workspace("queens", "$base/klause-test-queens.mzn", Category.CSP, Expected.Sat)
        workspace("send_more_money", "$base/klause-test-send_more_money.mzn", Category.CSP, Expected.Sat)
        workspace("sudoku", "$base/klause-test-sudoku.mzn", Category.CSP, Expected.Sat)
        workspace("zero_one_knapsack", "$base/klause-test-zero_one_knapsack.mzn", Category.PACKING, Expected.Unknown)
    }
}

/**
 * On-demand corpus declarations consumed by `source.CorpusFetcher`, including attribution
 * and storage rationale.
 */
internal object ExternalCollections {
    /**
     * MiniZinc Challenge archive with year directories containing problem families.
     * `mzn-bench` selects across years through
     * [com.eignex.klause.bench.source.CorpusSelection.Layout.MznChallengeAllYears].
     * Recent submissions carry MIT licensing; each collection retains its attribution.
     */
    val minizincChallenge = ExternalCollection(
        id = "mzn-challenge",
        url = "https://github.com/MiniZinc/mzn-challenge.git",
        license = "MIT (recent years); per-problem LICENSE files for older years where known",
        reason = "the actively-maintained per-year MiniZinc Challenge archive",
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
        url = "https://github.com/rasros/hakank.git",
        license = "MIT (Hakan Kjellerstrand)",
        reason = "the minizinc/ models from hakank/hakank; upstream is no longer maintained " +
            "following its author's passing, so fixes for models that no longer flatten under " +
            "current MiniZinc live in this derived copy",
        fetch = FetchMethod.GitClone(depth = 1),
        includeDirs = listOf("minizinc/lib"),
    )
    val miplib2017 = ExternalCollection(
        id = "miplib2017",
        url = "https://miplib.zib.de/downloads/collection.zip",
        license = "MIPLIB 2017 (academic; freely available for research)",
        reason = "1065-instance MIP library; the full collection.zip (~3.5GB) is fetched once, then " +
            "filtered to the <=16MB instances (klause solves the bounded/integer subset)",
        fetch = FetchMethod.Zip,
        maxFileMb = 16,
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

    val all = listOf(minizincChallenge, libminizincTests, hakank, satlibUf20, satlibUuf50)

    // XCSP3 competition library (instance archives, xcsp.org / CRIL).
    // Instances ship as individually `.xml.lzma`-compressed files inside each zip, decompressed
    // on fetch. Per-year full archives cover 2017–2019 (no
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

    /** The curated 2022–2025 main-track aggregates: the `xcsp3-cop`/`xcsp3-csp` dynamic suites select
     *  from these (each ships as `COP22to25/`/`CSP22to25/` dirs of `*.xml.lzma`, decompressed on fetch). */
    val xcsp3Cop = xcspAggregate("COP", 1000)
    val xcsp3Csp = xcspAggregate("CSP", 800)
    val xcsp3Competition = listOf(
        xcsp(2017, 102),
        xcsp(2018, 69),
        xcsp(2019, 136),
        xcsp3Cop,
        xcsp3Csp,
    )

    /** Per-logic `.tar.zst` archives from SMT-LIB non-incremental Zenodo record 15493090. */
    private fun smtlibLogic(logic: String) = "https://zenodo.org/records/15493090/files/$logic.tar.zst?download=1"

    /** SMT-LIB QF_LIA non-incremental benchmark set, extracting to `.smt2` files under
     *  `non-incremental/QF_LIA/<family>/`. */
    val smtlibQfLia = ExternalCollection(
        id = "smtlib-qf_lia",
        url = smtlibLogic("QF_LIA"),
        license = "SMT-LIB (per-family licenses)",
        reason = "large benchmark set (656MB compressed, ~13k instances); fetched rather than vendored",
        fetch = FetchMethod.TarballZst,
    )

    /** SMT-LIB QF_LRA non-incremental benchmark set, same Zenodo release as [smtlibQfLia]. */
    val smtlibQfLra = ExternalCollection(
        id = "smtlib-qf_lra",
        url = smtlibLogic("QF_LRA"),
        license = "SMT-LIB (per-family licenses)",
        reason = "real-arithmetic benchmark set (173MB compressed, ~1.7k instances); fetched rather than vendored",
        fetch = FetchMethod.TarballZst,
    )

    val smtlibQfNra = ExternalCollection(
        id = "smtlib-qf_nra",
        url = smtlibLogic("QF_NRA"),
        license = "SMT-LIB (per-family licenses; preserve source attribution)",
        reason = "210801606-byte compressed archive; fetched, not redistributed; " +
            "logic tag does not certify polynomial membership",
        fetch = FetchMethod.TarballZst,
    )

    /** SMT-LIB QF_LIRA non-incremental benchmark set, same Zenodo release as [smtlibQfLia]. */
    val smtlibQfLira = ExternalCollection(
        id = "smtlib-qf_lira",
        url = smtlibLogic("QF_LIRA"),
        license = "SMT-LIB (per-family licenses)",
        reason = "small mixed int/real benchmark set (0.2MB compressed); fetched rather than vendored",
        fetch = FetchMethod.TarballZst,
    )

    /** SMT-LIB QF_IDL non-incremental benchmark set, same Zenodo release as [smtlibQfLia]. A difference
     *  constraint `x − y ⋈ c` bounds neither of its variables, so a system built only from them can leave
     *  every integer domain open — the shape the deferred bounding has to invent a search box for. */
    val smtlibQfIdl = ExternalCollection(
        id = "smtlib-qf_idl",
        url = smtlibLogic("QF_IDL"),
        license = "SMT-LIB (per-family licenses)",
        reason = "integer difference-logic benchmark set (408MB compressed); fetched rather than vendored",
        fetch = FetchMethod.TarballZst,
    )

    /** SMT-LIB QF_RDL non-incremental benchmark set, same Zenodo release as [smtlibQfLia]. The real
     *  counterpart of [smtlibQfIdl]: its variables are continuous, so they are LP-only columns and never
     *  meet the integer search box at all. Small, so it is the cheap one to sweep. */
    val smtlibQfRdl = ExternalCollection(
        id = "smtlib-qf_rdl",
        url = smtlibLogic("QF_RDL"),
        license = "SMT-LIB (per-family licenses)",
        reason = "real difference-logic benchmark set (9.5MB compressed); fetched rather than vendored",
        fetch = FetchMethod.TarballZst,
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

    /** SATLIB "classic" structured DIMACS families — small, hand-picked benchmarks each with a
     *  distinct structure (artificial random-3SAT, variable-length random, DUBOIS xor-chains, parity
     *  learning, inductive inference, pigeonhole, all-interval series). Each ships as a flat `*.cnf`
     *  tarball; the `dimacs-classic` suite selects them all as a diverse, klause-scale Boolean set. */
    private fun satlibClassic(id: String, path: String) = ExternalCollection(
        id = "satlib-$id",
        url = "https://www.cs.ubc.ca/~hoos/SATLIB/Benchmarks/SAT/$path",
        license = "SATLIB (public benchmarks)",
        reason = "small classic structured DIMACS family; fetched rather than vendored",
        fetch = FetchMethod.Tarball,
    )

    /** The classic families paired with a coarse SAT/UNSAT label (DUBOIS and pigeonhole are wholly
     *  UNSAT, the rest predominantly SAT); the clasp oracle decides each instance's true verdict, so
     *  the label is only the suite's category metadata. */
    val dimacsClassic: List<Pair<ExternalCollection, Category>> = listOf(
        satlibClassic("aim", "DIMACS/AIM/aim.tar.gz") to Category.SAT,
        satlibClassic("jnh", "DIMACS/JNH/jnh.tar.gz") to Category.SAT,
        satlibClassic("dubois", "DIMACS/DUBOIS/dubois.tar.gz") to Category.UNSAT,
        satlibClassic("parity", "DIMACS/PARITY/parity.tar.gz") to Category.SAT,
        satlibClassic("ii", "DIMACS/II/inductive-inference.tar.gz") to Category.SAT,
        satlibClassic("phole", "DIMACS/PHOLE/pigeon-hole.tar.gz") to Category.UNSAT,
        satlibClassic("ais", "AIS/ais.tar.gz") to Category.SAT,
    )

    /** The `satcomp-compact-v1` instances ([SatCompCompact]), each fetched from the Global Benchmark Database by its
     *  content hash and checked against it. The collection id carries the version, so a redrawn list is fetched and
     *  referenced afresh rather than mixed into this one's cache, corpus pack and reference rows. */
    val satcompCompact = ExternalCollection(
        id = "satcomp-compact-v1",
        url = "https://benchmark-database.de",
        license = "SAT Competition benchmarks (public for research; cite GBD and the competition proceedings)",
        reason = "40 pinned instances, ~13MB as .cnf.xz; fetched per instance by content hash rather than vendored",
        fetch = FetchMethod.GbdFiles(SatCompCompact.instances),
    )
    val pbComp2024 = ExternalCollection(
        id = "pb-comp-2024",
        url = "https://www.cril.univ-artois.fr/PB24/benchs/selected-PB24.tar",
        license = "Pseudo-Boolean Competition (academic benchmarks)",
        reason = "255MB PB'24 selected-benchmark set; fetched rather than vendored",
        fetch = FetchMethod.Tar,
    )

    /** PB'25 selected-benchmark set: `.opb.xz` under `PB25/normalized-PB25`. Unlike [pbComp2024] it has no WBO
     *  track. */
    val pbComp2025 = ExternalCollection(
        id = "pb-comp-2025",
        url = "https://www.cril.univ-artois.fr/PB25/benchs/selected-PB25.tar",
        license = "Pseudo-Boolean Competition (academic benchmarks)",
        reason = "357MB PB'25 selected-benchmark set; fetched rather than vendored",
        fetch = FetchMethod.Tar,
    )

    /** PB'07 evaluation "other" benchmarks: 487 native (crafted, not MIP-translated) pseudo-Boolean
     *  instances across the competition categories (optimization/decision, small/big integer, linear
     *  and non-linear), each a `.opb.bz2` under `normalized-PB07/`. Standard OPB headers, klause-scale.
     *  (The archive lists 598, but 111 long factor-mod paths exceed the ustar 100-char name limit.) */
    val pb07Other = ExternalCollection(
        id = "pb07-other",
        url = "http://www.cril.univ-artois.fr/PB07/benchs/PB07-OTHER.tar",
        license = "PB Evaluation (academic benchmarks)",
        reason = "31MB PB'07 native pseudo-Boolean set (487 crafted instances); fetched rather than vendored",
        fetch = FetchMethod.Tar,
    )
    val maxsatExactUnweighted = ExternalCollection(
        id = "mse24-exact-unweighted",
        url = "https://www.cs.helsinki.fi/group/coreo/MSE2024-instances/mse24-exact-unweighted.zip",
        license = "MaxSAT Evaluation (academic benchmarks)",
        reason = "1.9GB MSE'24 exact unweighted track (flat `*.wcnf.xz`); fetched rather than vendored",
        fetch = FetchMethod.Zip,
    )
    val maxsatExactWeighted = ExternalCollection(
        id = "mse24-exact-weighted",
        url = "https://www.cs.helsinki.fi/group/coreo/MSE2024-instances/mse24-exact-weighted.zip",
        license = "MaxSAT Evaluation (academic benchmarks)",
        reason = "4.4GB MSE'24 exact weighted track (flat `*.wcnf.xz`); fetched rather than vendored",
        fetch = FetchMethod.Zip,
    )

    val miplib2010 = ExternalCollection(
        id = "miplib2010",
        url = "https://miplib2010.zib.de/download/miplib2010-1.1.3-complete.tgz",
        license = "MIPLIB 2010 (academic; freely available for research)",
        reason = "361-instance collection, ~1.4GB; fetched once and filtered to the <=16MB instances",
        fetch = FetchMethod.Tarball,
        maxFileMb = 16,
    )
    val miplib2003 = ExternalCollection(
        id = "miplib2003",
        url = "https://miplib2010.zib.de/miplib2003/download/miplib2003.tar",
        license = "MIPLIB 2003 (academic; freely available for research)",
        reason = "60-instance library; fetched rather than vendored",
        fetch = FetchMethod.Tar,
    )
    val miplib3 = ExternalCollection(
        // The versioned cache id distinguishes collections with named files.
        id = "miplib3-mps",
        url = "https://miplib2010.zib.de/miplib3/miplib3.tar.gz",
        license = "MIPLIB 3 (academic; freely available for research)",
        reason = "65-instance classic library; its MPS files have no extension and are named on fetch",
        fetch = FetchMethod.Tarball,
        extensionless = "NAME" to "mps",
    )

    /** OR-Library classes converted to MPS on fetch (see [OrLibrary]), each with the files it is read from. */
    val orLibrary: List<ExternalCollection> = listOf(
        orLibrary(
            "scp",
            "set covering",
            OrLibrary::setCovering,
            (41..49) + 410 + (51..59) + 510 + (61..65),
            listOf("a", "b", "c", "d", "e", "nre", "nrf", "nrg", "nrh"),
            listOf("scpclr10", "scpclr11", "scpclr12", "scpclr13") +
                (6..11).map { "scpcyc" + it.toString().padStart(2, '0') },
        ),
        orLibrary(
            "spp",
            "set partitioning (airline crew)",
            OrLibrary::setPartitioning,
            files = (1..43).map { "sppnw" + it.toString().padStart(2, '0') },
        ),
        orLibrary(
            "cap",
            "capacitated warehouse location",
            OrLibrary::warehouseLocation,
            files = listOf(
                41, 42, 43, 44, 51, 61, 62, 63, 64, 71, 72, 73, 74, 81, 82, 83, 84, 91, 92, 93, 94,
                101, 102, 103, 104, 111, 112, 113, 114, 121, 122, 123, 124, 131, 132, 133, 134,
            ).map { "cap$it" },
        ),
        orLibrary("gap", "generalised assignment", OrLibrary::generalisedAssignment, files = (1..12).map { "gap$it" }),
        orLibrary(
            "mknap",
            "multidimensional knapsack",
            OrLibrary::multidimensionalKnapsack,
            files = (1..9).map { "mknapcb$it" },
        ),
        orLibrary("binpack", "bin packing", OrLibrary::binPacking, files = (1..8).map { "binpack$it" }),
        orLibrary("airland", "aircraft landing", OrLibrary::aircraftLanding, files = (1..13).map { "airland$it" }),
    )

    private fun orLibrary(
        id: String,
        what: String,
        convert: (String, String) -> List<Pair<String, String>>,
        scp: List<Int> = emptyList(),
        scpSets: List<String> = emptyList(),
        files: List<String> = emptyList(),
    ) = ExternalCollection(
        id = "orlib-$id",
        url = "https://people.brunel.ac.uk/~mastjjb/jeb/orlib/files",
        license = "OR-Library (J.E. Beasley; freely available for research)",
        reason = "OR-Library $what, read from its own text format and written as MPS",
        fetch = FetchMethod.Files(
            (scp.map { "scp$it" } + scpSets.flatMap { set -> (1..5).map { "scp$set$it" } } + files).map { "$it.txt" },
            "mps",
            convert,
        ),
    )

    /** Every SATLIB collection with the category its instances share: the structured families, then the random-3SAT
     *  sets, uf20 and the ladder's rungs. */
    val satlib: List<Pair<ExternalCollection, Category>> get() =
        dimacsClassic + (satlibUf20 to Category.SAT) +
            satlibLadder.map { (name, col) -> col to if (name.startsWith("uf")) Category.SAT else Category.UNSAT }

    /** Ordered rungs (low→high vars) of the SAT and UNSAT ladders, keyed by family name. */
    val satlibLadder: Map<String, ExternalCollection> = listOf(
        "uf50-218", "uf75-325", "uf100-430", "uf125-538", "uf150-645",
        "uf175-753", "uf200-860", "uf225-960", "uf250-1065",
        "uuf50-218", "uuf75-325", "uuf100-430", "uuf125-538", "uuf150-645",
        "uuf175-753", "uuf200-860", "uuf225-960", "uuf250-1065",
    ).associateWith { satlibRnd(it) }
}

private fun archiveSuite(
    id: String,
    description: String,
    collection: ExternalCollection,
    subDir: String,
): DynamicSuite {
    val layout = CorpusSelection.Layout.Flat(subDir, "smt2")
    return DynamicSuite(id, description, defaultPerFamily = 1, sourceLayout = ArchiveSourceLayout(collection, layout)) {
        CorpusSelection.select(collection, layout, it, Category.CSP, format = Format.SMTLIB)
    }
}
