package com.eignex.klause.bench.catalog

import com.eignex.klause.bench.catalog.Expected.Sat
import com.eignex.klause.bench.catalog.Expected.Unsat

/**
 * `satcomp-compact-v1`: 40 SAT Competition main-track instances (2022-2024) from the Global Benchmark Database,
 * pinned by content hash, as `klause-bench/scripts/satcomp-compact.sh` drew them: Kissat (the organisers' entry)
 * decided each in 5-120 s, at most 2 MB as `.cnf.xz`, at most 1M variables and 5M clauses, one family per instance
 * (40 of the 50 eligible), 20 SAT and 20 UNSAT. The result is the competition's verified one. A redraw is a new
 * version: the problem names, and the lab's trend lines over them, would otherwise come to mean other instances.
 */
internal object SatCompCompact {
    val instances: List<GbdInstance> = listOf(
        pin("c1b30b4e03c3024ad9d084e29e79aa46", "algorithm-equivalence-checking", "BubbleVsPancakeSort_6_6", Unsat),
        pin("8b31606e10656ff7eb2936262b647443", "argumentation", "stable-300-0.1-20-98765432130020", Sat),
        pin("7083b70c1976162e2693d7a493717ffd", "battleship", "battleship-14-26-sat", Sat),
        pin("1a6a635f5b959e191ec705b2f236568a", "bitvector", "square.2.0.i.smt2-cvc4", Unsat),
        pin("18babbd2835b8a37c6299f8b0301c7c9", "brent-equations", "brent_67_0.05", Sat),
        pin("5e04f38ff11cee5d75fb98e12f6a14fc", "circuit-equivalence-checking", "sqrt-mitern172", Unsat),
        pin("cb2e8b7fada420c5046f587ea754d052", "clique-formulas", "clique_n2_k10", Unsat),
        pin("28f29fe949422ec88892e18073de065c", "coloring", "5col100_15_6.shuffled", Unsat),
        pin("0876c518e5653369e20fb1ee0bb8db40", "crafted-cec", "mp1-klieber2017s-0500-023-t12", Sat),
        pin("1b02025658c2a23e1fd3ce0a9c3633cb", "cryptography-ascon", "asconhashv12_opt64_H8_M2-I61h2mH5_m2_6.c", Sat),
        pin("9dcbf221b8d2bb01f30bcca283f3608d", "erdos-discrepancy", "EDP3-11000", Sat),
        pin("19e2c3a0865c8c1b4543d11213bebe5f", "hamiltonian", "x9-09024.sat", Unsat),
        pin("a1158a2d8c8a1c6da02c2c4fcb32337f", "hardware-model-checking", "6s158_Iter2", Unsat),
        pin("a130a3f95891daed1bfd71c77bd2d8a1", "hardware-verification", "SAT_dat.k80", Unsat),
        pin("72c0d81e16d91bcaed808efcde2e5069", "heule-folkman", "Folkman-175-1251868", Sat),
        pin("50019e4419d48196bb4b95933a8b5030", "heule-nol", "noL-11-14", Sat),
        pin("2d0c041c0fe72dc32527bfbf34f63e61", "hgen", "170223547", Sat),
        pin("0dc468413b65339fad7e4f981bf2fb1e", "independent-set-reconfiguration", "reconf20_61_myciel4_2", Unsat),
        pin("48e9f29a92930f1b367efc5754cb679b", "maxsat-optimum", "af-synthesis_stb_50_80_7_sat", Sat),
        pin("5c5fa629c2c62723eb2d3388db264521", "multiplier-circuits", "Wallace_Bits_Fast_3", Sat),
        pin("112b0ac22b2a567cd3badfed3a5497fd", "mutilated-chessboard", "mchess16-mixed-35percent-blocked", Unsat),
        pin("44092fcc83a5cba81419e82cfd18602c", "pigeon-hole", "php-010-009.shuffled-as.sat05-1185", Unsat),
        pin("0191a0bf3412ed407ba669f8d69081ee", "planning", "mrpp_6x6_18_20", Sat),
        pin("6e1b970d637fca70d08b251766edade3", "polynomial-multiplication", "Nb8T61", Sat),
        pin("2a53e9c6a25a50d753a94ead66065826", "popularity-similarity", "mp1-ps_5000_21250_3_0_0.8_0_1.50_6", Unsat),
        pin("c76f3c6c2e2eae85fc5f3d499f3db88d", "puzzle", "mp1-blockpuzzle_5x10_s7_free4", Unsat),
        pin("8530c911b75ab1b276042043d118a875", "quantum-kochen-specker", "constraints_16_0.4_1", Unsat),
        pin("70ef2b6bdc4101a0e35cf3d165571fe3", "quasigroup-completion", "qwh.50.1250.shuffled-as.sat03-1655", Sat),
        pin("0fa9521ff633b27be11525a7b0f7d8b6", "random-modularity", "jgiraldezlevy.2200.9086.08.40.41", Sat),
        pin("3ed56242f55e3653dbceeb4a70221787", "rbsat", "rbsat-v945c61409gyes9-sc2009", Sat),
        pin("1ea68d0008f01c50a1e4fec77dd0775e", "reg-n", "REGRandom-K4-L1-Seed30", Unsat),
        pin("07e6413459f92b613498a719125b6239", "scheduling", "j3037_10_mdd_bm1", Unsat),
        pin("177a4ad8fd76ad8240387998112c3168", "school-timetabling", "SC23_Timetable_C_481_E_49_Cl_32_D_6_T_50", Sat),
        pin("0e89b0f96c99f1a6aaaf66c3ae805919", "set-covering", "SCPC-500-15", Unsat),
        pin("26114429758a203d651f4df87c94de4c", "sgen", "sgen1-unsat-97-100.cnf.mis-72.debugged", Unsat),
        pin("8bcd3f3e269fa3c9516667b626d61602", "social-golfer", "TableSymModel_output_8_4_7.bul_", Sat),
        pin("b51583e32432c5778c7e3e996c3bfeba", "software-verification", "sqrt_ineq_3.c", Sat),
        pin("1009c791cee542cdf19651fe25e6881a", "summle", "summle_X4053_steps8_I1-2-2-4-4-8-25-100", Sat),
        pin("65138afdf7eb054be5131f2b0de84369", "test-configuration", "satch2ways12u", Unsat),
        pin("01d142c43f3ce9a8c5ef7a1ecdbb6cba", "tseitin-formulas", "urquhart3_25bis.shuffled", Unsat),
    )

    private fun pin(hash: String, family: String, name: String, expected: Expected) =
        GbdInstance(hash, family, name, expected)
}
