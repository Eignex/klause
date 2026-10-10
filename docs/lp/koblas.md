# Koblas integration

Klause owns sparse LU, reach, pivot policy, Forrest–Tomlin updates, checked arithmetic and scratch
lifetime. Koblas supplies validated CSC containers, indexed/vector Level 1 kernels and generic sparse
primitives. There is one floating simplex with exact certification; vendor selection does not change
numerical authority or create another solver.

## Dependency provenance

The [build](../../klause/build.gradle.kts) pins immutable timestamps for both the KMP root and every platform module. A root timestamp
alone is insufficient because its Gradle metadata redirects to mutable platform snapshots. Strict
platform constraints propagate into klause publications.

Koblas coordinates use group `com.eignex`:

| Module | Version | Binary SHA-256 |
|---|---|---|
| koblas | `0.1.1-20261010.042342-251` | `97f5e9c15c444eb704f72fe57db0125ede8fcb2980a10ab4eaa6272590ef1395` |
| koblas-jvm | `0.1.1-20261010.042342-254` | `b8817d1cbb4ee4bd7dc9a905c0ca211053c34634b101bbc9863e8db7c24d13b3` |
| koblas-linuxx64 | `0.1.1-20261010.042342-250` | `7defce305e6ee64aa3e51f30ebc73acd11b1dc80e0cd0469ed352c600da208e8` |
| koblas-linuxarm64 | `0.1.1-20261010.042342-251` | `cf316a2a0858e117577119db2e415463331d081f0d82f533cf3f6fe5b2306bf0` |
| koblas-macosarm64 | `0.1.1-20261010.042148-274` | `18e951b0e52ee7e0680500514f16722e6c2cc55a67763b0e903c1a489c25b018` |

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
| `simplex.basis` | Zero-based sorted unique CSC, owned source/factor copies, explicit stored zeros, retained permutations and fixed-dimension ownership. Each builder owns fixed-size LU scratch; solve-quality checks reuse one dense product buffer. Factor materialization and updates allocate their owned output arrays directly. |
| Sparse slice workflows | Validate windows, indices, capacity and overlap before mutation. Unique scatter/touched support, first-touch order, structural zeros until explicit compaction, positive-zero clearing. Checked scatter delegates to generic primitives; checked dot preserves scalar input order and underflow/nonfinite diagnostics. |
| `lp.engine` | Pricing and column updates use indexed kernels; checked workflows retain solver semantics. Scaling/refinement keep guarded powers of two, exact accumulation and source maps. No dense vendor Level 2/3 call is required. |
| `util.SparseSlices` | Shared validated slice workflows serve the LP engine and basis without adding a solver-layer dependency. Other array/permutation/domain helpers have no koblas consumers. |
| CLI and CI | JDK 25, native-access permission and optional Vector API module. Linux Native tests are separate in CI; release opts into arm64 targets. BLAS libraries are installed by the host, never extracted from klause artifacts. |
| Tests and capture tools | Common basis/LP tests cover ownership and certification. Real traces verify factorization and update observations plus FTRAN/BTRAN residuals. Kernel composition and vendor identity are diagnostic fields, not promises about reduction order. |

Unchecked reductions may reassociate or fuse arithmetic according to the selected implementation.
Klause does not use vendor `iamax` to choose pivots; masked maximum and candidate positions retain
their own finite-value and ordering contract. Exact proof checks, work/cancellation budgets, repair
receipts and scoped replacement ownership remain solver responsibilities.

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
