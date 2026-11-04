package com.eignex.klause.bench.catalog

import com.eignex.klause.bench.catalog.Expected.Sat

internal object SatCompXor {
    val instances: List<GbdInstance> = listOf(
        pin("50a6d53dfb7c3f6de126290275f800b9", "mod2-rand3bip-sat-200-2.shuffled-as.sat05-2144"),
        pin("312f3a5066497d26a734834aa1c8c4eb", "mod2c-rand3bip-sat-200-1.shuffled-as.sat05-2458"),
        pin("19a1b125613fa7e1de828967cd93573a", "mod2-rand3bip-sat-210-3.shuffled-as.sat05-2160"),
        pin("292fd16f1ef46f416a806ffa096e9565", "mod2c-rand3bip-sat-210-3.shuffled-as.sat05-2475"),
        pin("9b002d0c312bbddf9726286b87aa40ab", "mod2-rand3bip-sat-220-1.shuffled-as.sat05-2173"),
        pin("1b0780e82e41f6ca9c6c2f37b5350789", "mod2c-rand3bip-sat-220-1.shuffled-as.sat05-2488"),
        pin("126c4744d487fa4d3fe280ff2105e9ea", "mod2-rand3bip-sat-230-1.shuffled-as.sat05-2188"),
        pin("0462a0427297f8979588174b502bd044", "mod2c-rand3bip-sat-230-1.shuffled-as.sat05-2503"),
        pin("1567f5d6c1cbef6de37f8acbc54fb171", "mod2-rand3bip-sat-240-3.shuffled-as.sat05-2205"),
        pin("5e2d07ffde107945ffb862f9ad6e4fb7", "mod2c-rand3bip-sat-240-3.shuffled-as.sat05-2520"),
        pin("75429ff7acb5acb597abe01aa745c2a6", "mod2-rand3bip-sat-250-3.shuffled-as.sat05-2220"),
        pin("73063c6257a4ffc29f5bea7028403a64", "mod2c-rand3bip-sat-250-2.shuffled-as.sat05-2534"),
        pin("0d3bdfd1b4eb3fb16eeb2d8437db66a2", "mod2-rand3bip-sat-260-3.shuffled-as.sat05-2235"),
        pin("2563a9a0463e58e8a5823bc612cacbc9", "mod2-rand3bip-sat-270-3.shuffled-as.sat05-2250"),
        pin("193056422a264b6951428ede578b93eb", "mod2-rand3bip-sat-280-1.shuffled-as.sat05-2263"),
        pin("1e42a3eadf23a720b9905e58a364101e", "mod2-rand3bip-sat-290-2.shuffled-as.sat05-2279"),
        pin("9b07f26e517e145e6861109093da7b50", "mod2-rand3bip-sat-300-3.shuffled-as.sat05-2295"),
    )

    private fun pin(hash: String, name: String) = GbdInstance(hash, "random-graph-xorsat", name, Sat)
}
