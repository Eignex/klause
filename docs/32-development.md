# 32. Development

Part VII is about working on the Kotlin code itself. This chapter covers the module
layout, building and checking the code, a first program against the Kotlin DSL, and
the conventions for tests and source. The [repository modules](02-architecture.md#repository-modules)
and [package boundaries](02-architecture.md#package-boundaries) from chapter 2 are
the map; the chapters in between describe the contract of each component you might
change.

## Modules and building

The Gradle build includes `:klause` (model, DSL, compilation, formats and solving),
`:klause-cli` (the command-line driver of [chapter 31](31-cli.md)) and
`:klause-bench`, a JVM module that is not published. The default build targets JVM
and Linux x64; releases opt into Linux arm64 and macOS arm64 with `-Ptargets.full`.

```sh
./gradlew :klause-cli:installJvmDist
klause-cli/build/install/klause-cli-jvm/bin/klause-cli --help
```

`./gradlew check` compiles and runs the tests and light checks; `./gradlew lintDocs`
runs detekt and Dokka link linting over the Kotlin sources and their KDoc. Kotlin
test tasks report and fail individually through
`kotlin.tests.individualTaskReports=true`. Exact arithmetic and lattice tests belong
to `klause`; native tests run in CI alongside a smoke start of the release CLI
executable.

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

The [schema DSL](../klause/src/commonMain/kotlin/com/eignex/klause/schema) and
[solver APIs](../klause/src/commonMain/kotlin/com/eignex/klause/solver) provide
schema construction, compilation, decoding and solving. Variables can be optional;
constraints lower to reified or aggregation-aware forms. Sets and non-linear float
wrappers compile to supported finite encodings.

The [library API](30-api.md) describes supported extension surfaces, experimental
composition, typed identities and compatible migration.

## Result requirements

Enumeration and sampling operate over compiled finite problems; counting and
near-uniform sampling mechanisms live under
[count](../klause/src/commonMain/kotlin/com/eignex/klause/count).
Select the API and engine for the required result. A local-search witness does
not establish exhaustive search or optimality; an interrupted complete search
can remain undecided. LP result strength and source reconstruction follow the
[certification contract](27-lp-certification.md#proof-packages).

## Tests

Unit tests define focused behavior and subsystem contracts: parsing and lowering,
state ownership, rollback, explanations, exact arithmetic, result publication and
resource limits. Keep a small set of representative examples for each contract
rather than a separate toy solve for every DSL operator, search policy or frontend
feature. Add a unit test when it clarifies a distinct contract; extend an existing
case for a variant of the same behavior.

Use the surrounding framework, layout and assertions. Name classes after the
file under test and name tests in backticks with plain ASCII describing behavior.
Keep one observable behavior per test, using arrange, act, assert. Parameterize
variants of one behavior and separate semantically different behaviors.
Reuse existing fixtures/helpers. JVM tests must stay below 300 ms.

## Source style and boundaries

Prefer clear names and types. Comments explain soundness, performance or lifecycle
reasons rather than narrating code or changes. Public APIs receive KDoc; use
`[Symbol]` links and functional math notation such as `succ(i)`.
Follow the [package boundaries](02-architecture.md#package-boundaries), especially
the closed numerical-engine dependency list.

---

Previous: [31. Command-line interface](31-cli.md) · [Contents](README.md)
