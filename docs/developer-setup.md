# Developer setup and entry points

Klause is a Kotlin Multiplatform constraint solver for Booleans, finite-domain
integers and linear reals. It provides backtracking, local search, adaptive
large-neighbourhood search and portfolios, with exact certification of LP claims.
It can run as a Kotlin/Java library, a JVM CLI or a native executable. This guide
provides development smoke commands and model entry points; read the
[architecture contract](architecture.md) before changing subsystem boundaries.

## Build from source

```sh
./gradlew :klause-cli:installJvmDist
klause-cli/build/install/klause-cli-jvm/bin/klause-cli --help
```

The launcher requires JDK 25 or newer. Gradle provisions its compilation toolchain;
the shell that invokes the installed launcher must also select a suitable JDK.
See [CLI setup](cli.md#build-and-run) for runtime options and native builds.

Solve a committed instance:

```sh
klause-cli/build/install/klause-cli-jvm/bin/klause-cli \
  -e cp -t 10000 -s klause-bench/smoke-corpus/smtlib/lia-wide-span.smt2
```

Use the [MiniZinc integration](minizinc.md) for `.mzn` source models. The CLI reads
FlatZinc, XCSP3, SMT-LIB, MPS, DIMACS, OPB/WBO and WCNF directly.
[Input semantics](formats.md) explains the result guarantees and exact mode.

## Kotlin DSL

Declare a schema, compile it and decode solutions:

```kotlin
class Order : VariableSchema() {
    val a by intVar(min = 1, max = 3)
    val b by intVar(min = 1, max = 3)
    val c by intVar(min = 1, max = 3)
    val unique by constraint { allDifferent(a, b, c) }
}

val schema = Order()
val compiled = schema.compile()
for (sample in BacktrackSolver(compiled).enumerate().take(5)) {
    println(compiled.decode(schema.a, sample))
}
```

The [schema DSL](../klause/src/commonMain/kotlin/com/eignex/klause/schema/) and
[solver APIs](../klause/src/commonMain/kotlin/com/eignex/klause/solver/) provide
schema construction, compilation, decoding and solving. Variables can be optional;
constraints lower to reified or aggregation-aware forms. Sets and non-linear float
wrappers compile to supported finite encodings.

## Result requirements

Enumeration and sampling operate over compiled finite problems; counting and
near-uniform sampling mechanisms live under
[count](../klause/src/commonMain/kotlin/com/eignex/klause/count/).
Select the API and engine for the required result. A local-search witness does
not establish exhaustive search or optimality; an interrupted complete search
can remain undecided. LP result strength and source reconstruction follow the
[certification contract](lp/certification.md#proof-packages).
