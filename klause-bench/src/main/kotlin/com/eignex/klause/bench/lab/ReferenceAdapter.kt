package com.eignex.klause.bench.lab

import com.eignex.lab.bench.*

/** Portable source planning for lab-owned reference solvers; no installed CLI or compiler is executed. */
fun main(args: Array<String>) {
    val protocolOutput = System.out
    System.setOut(System.err)
    configureLabPaths()
    BenchCli.execute(ReferenceLabSuite, args, stdout = protocolOutput)
}

internal object ReferenceLabSuite : BenchSuite {
    override fun describe() = KlauseLabSuite.describe()

    override fun plan(request: BenchRequest): List<BenchCase> =
        KlauseLabSuite.plan(request.copy(parameters = request.parameters + ("input-only" to "true")))

    override fun run(case: BenchCase, context: BenchContext): BenchResult =
        error("Input-only plans must run through the lab reference backend")
}
