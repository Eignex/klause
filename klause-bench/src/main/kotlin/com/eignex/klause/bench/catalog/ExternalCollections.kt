package com.eignex.klause.bench.catalog

import com.eignex.klause.bench.source.OrLibrary

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
        reason = "1065-instance MIP library; the ZIP directory selects the <=16MB instances, " +
            "and only selected original members are fetched",
        fetch = FetchMethod.SelectiveZip("mps"),
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
    val satcompXor = ExternalCollection(
        id = "satcomp-xor-v1",
        url = "https://benchmark-database.de",
        license = "SAT Competition benchmarks (public for research; cite GBD and the competition proceedings)",
        reason = "published small graph parity encodings; fetched by pinned content hash rather than vendored",
        fetch = FetchMethod.GbdFiles(SatCompXor.instances),
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

    val maxsatLibMaxcut = ExternalCollection(
        id = "maxsat-lib-weighted-maxcut",
        url = "https://www.cs.toronto.edu/maxsat-lib/maxsat-instances/downloads/weighted/maxcut_wt.zip",
        license = "MaxSAT Evaluation (academic benchmarks)",
        reason = "438KiB MaxSAT LIB weighted MaxCut family; fetched rather than vendored",
        fetch = FetchMethod.Zip,
    )

    val maxsatHaplotyping = ExternalCollection(
        id = "mse24-weighted-haplotyping-pinned-v1",
        url = maxsatExactWeighted.url,
        license = maxsatExactWeighted.license,
        reason = "one original weighted source selected from the MSE'24 ZIP without fetching unrelated instances",
        fetch = FetchMethod.ZipMember(
            "haplotyping-pedigrees_wt-ped2.B.recomb1-0.01-3.wcnf.xz",
            "ecb87265f5a647882414411190438a41faf8a8c8cf442a8c92e0cb940c146e9f",
        ),
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
