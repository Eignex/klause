# Numerical dependencies

External numerical libraries supply data containers and kernels within Klause's
owned LP engine. They do not select source models, solving routes or result
semantics. This page explains what comes from each dependency, how it is pinned,
and the consumer and runtime obligations at that boundary.

[LP architecture](architecture.md) owns the engine and its working state;
[certification](certification.md) owns result authority.

Klause owns sparse and small dense LU, reach, pivot policy, Forrest–Tomlin updates, checked arithmetic and scratch
lifetime. Koblas supplies validated CSC containers, indexed/vector Level 1 kernels and generic sparse
primitives. There is one floating simplex with exact certification; vendor selection does not change
numerical authority or create another solver.

## Dependency provenance

### Koblas

The [build](../../klause/build.gradle.kts) pins immutable timestamps for both the KMP root and every platform module. A root timestamp
alone is insufficient because its Gradle metadata redirects to mutable platform snapshots. Strict
platform constraints propagate into klause publications.

The pins consume Koblas revision `c001f98d9568324a65b669b2103602cde3868c65`,
published by its [snapshot workflow](https://github.com/Eignex/koblas/actions/runs/38115348971).
Koblas coordinates use group `com.eignex`:

| Module | Version | Binary SHA-256 |
|---|---|---|
| koblas | `0.1.1-20261011.054003-257` | `2e09140ac35d8da98d2e15fd259a73f86ec3237bddc556152c7378cefe55c1e9` |
| koblas-jvm | `0.1.1-20261011.054003-260` | `b12d7927421bedf8f166a5402e8121830486f666afe44cfef33bb4cf87562127` |
| koblas-linuxx64 | `0.1.1-20261011.054003-256` | `a087dbf22d9bcd5b690c0555104999e049af48a2c1c2e4cb2f8ff9adef7dbd0b` |
| koblas-linuxarm64 | `0.1.1-20261011.054003-257` | `54062376e00b9c0840101af0d59d38cb448a95d0191a92dff0115f13a721c57d` |
| koblas-macosarm64 | `0.1.1-20261011.053716-280` | `d4ef605ceef8071ffa883e597eb6a4381cd28d8528db570bd2154acd8cfc8054` |

The build does not consult Maven Local. For source integration, use Gradle's supported composite
substitution with an isolated checkout at the revision under test:

```sh
./gradlew --include-build /path/to/isolated/koblas :klause:jvmTest --max-workers=2
```

Record the checkout revision and the resulting artifact hashes separately from published-artifact
checks. Do not publish over a shared `SNAPSHOT`, or commit machine-specific paths. Dependency updates
must update every platform pin together and repeat provenance and published-metadata verification.

### Kumulant

Kumulant supplies online statistics and adaptive bandits. The library, CLI and benchmark
builds use its timestamped `0.3.4-SNAPSHOT` publication. Contextual-bandit features use
`com.eignex.koblas.DenseVector`; Kumulant exposes Koblas vector types and declares
`koblas:0.1.1-SNAPSHOT` transitively. Klause strictly pins the Koblas root and platforms
listed above so this transitive dependency cannot select different numerical binaries.

Kumulant coordinates use group `com.eignex` and version prefix `0.3.4-20260922.073440-`:

| Module | Build | Binary SHA-256 |
|---|---:|---|
| kumulant | 66 | `81bf47d09d6135b0857fc49510d17fbeac5dd958c5932753656d021bdfb78bae` |
| kumulant-jvm | 66 | `41b110903da23f17f9c0c1b7c4e8722707bc369aadf21bfcd9fd05762a0eb3dc` |
| kumulant-linuxx64 | 66 | `844f4b33057686b7ab47898757ae053f3aa377a508bc93cd0c7fbfc27ffa3a1a` |
| kumulant-linuxarm64 | 66 | `a705527909d08e53a8b1a05f1051da970980a6183ed2aea315ba9cb8fa6f5416` |
| kumulant-macosarm64 | 65 | `f0ce4c7f2f76c8aae200f8b443d4c4d2ad211733ebc540e7854916425ba1678c` |

Kumulant's root metadata also redirects to mutable platform snapshots, so its platform
constraints follow the same publication policy. Verify numerical ownership and transitive
dependencies when updating either library.

## Consumer contracts

| Consumer | Integration contract |
|---|---|
| `simplex.basis` | Zero-based sorted unique CSC, owned source/factor copies, explicit stored zeros, retained permutations and fixed-dimension ownership. Each builder owns fixed-size LU scratch; solve-quality checks reuse one dense product buffer. Factor materialization and updates allocate their owned output arrays directly. The bounded dense LU path uses windowed GER on trailing squares in owner-local matrix/vector buffers and publishes owned CSC factors; arithmetic guards and failed-attempt work remain in Klause. |
| Sparse slice workflows | Validate windows, indices, capacity and overlap before mutation. Unique scatter/touched support, first-touch order, structural zeros until explicit compaction, positive-zero clearing. Checked scatter delegates to generic primitives; checked dot preserves scalar input order and underflow/nonfinite diagnostics. |
| `lp.engine` | Pricing and column updates use indexed kernels; checked workflows retain solver semantics. Scaling/refinement keep guarded powers of two, exact accumulation and source maps. No dense vendor Level 2/3 call is required. |
| `util.SparseSlices` | Shared validated slice workflows serve the LP engine and basis without adding a solver-layer dependency. Other array/permutation/domain helpers have no koblas consumers. |
| CLI and CI | JDK 25, native-access permission and optional Vector API module. Linux Native tests are separate in CI; release opts into arm64 targets. BLAS libraries are installed by the host, never extracted from klause artifacts. |
| Tests and capture tools | Common basis/LP tests cover ownership and certification. Real traces verify factorization and update observations plus FTRAN/BTRAN residuals. Kernel composition and vendor identity are diagnostic fields, not promises about reduction order. |

Unchecked reductions may reassociate or fuse arithmetic according to the selected implementation.
Klause does not use vendor `iamax` to choose pivots; masked maximum and candidate positions retain
their own finite-value and ordering contract. Exact proof checks, work/cancellation budgets, repair
receipts and scoped replacement ownership remain solver responsibilities.

Windowed GER retains the parent matrix leading dimension and validates matrix and vector
ranges before mutation. Its built-in panel kernels update the rectangle directly; the
nonaliasing basis buffers need no vector views or scratch allocation. Each elimination
step uses matching matrix, multiplier and pivot-row offsets. At order six, five calls
cover 55 entries in total. Klause retains product overflow/nonzero-underflow checks and
post-update finite checks around these calls. The `GerWindow` route uses portable panel
kernels, including on host engines with an installed vendor library.

## Runtime support

JVM launchers and Gradle execution tasks enable `jdk.incubator.vector` and
`--enable-native-access=ALL-UNNAMED`. `-Pkoblas.noSimd=true` omits the module for portable-kernel
verification. Embedded JVM callers supply these runtime options themselves.

Koblas selects one immutable platform composition. Its default engines supply portable dense
Level 2/3 arithmetic as well as Level 1 and sparse primitives. Eligible dense calls can use an
installed host library; explicit vendor bindings require that library to be available.
Linux x64 prefers oneMKL or AOCL by CPU vendor; Linux arm64 prefers ArmPL, with installed
OpenBLAS as a fallback. macOS uses system Accelerate. Bindings use LP64 integers and one compute
thread per call. Usual sonames and fixed installer prefixes are checked; `koblas.vendor` exposes
the resolved path/version. Klause has no backend registry or bundled vendor payload.
