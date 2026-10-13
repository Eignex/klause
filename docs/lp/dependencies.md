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

The pins consume Koblas revision `74cf0237084597cce8eeef47ddd9c3613c30cf8e`,
published by its [snapshot workflow](https://github.com/Eignex/koblas/actions/runs/38446077479).
Koblas coordinates use group `com.eignex`:

| Module | Version | Binary SHA-256 |
|---|---|---|
| koblas | `0.1.1-20261013.182449-263` | `4bb78bbe9448b937e2bf88601e22c9414c5d8000b5ec75024be2d9c8444161b5` |
| koblas-jvm | `0.1.1-20261013.182449-266` | `36c06138be124ab6df809e7f9dde8f5dad4efd890585fc1350724b26662bd57d` |
| koblas-linuxx64 | `0.1.1-20261013.182449-262` | `27020e001be9346a675c0246b053e10f71d68e2577a139c3d99715d94b3c6492` |
| koblas-linuxarm64 | `0.1.1-20261013.182449-263` | `26224afb64a272b51e723bacca20e1b5e01b20473cb42d3f6b66c454eabd1004` |
| koblas-macosarm64 | `0.1.1-20261013.182206-286` | `8534ca098a41e671db582d14f664770895ec9b84b845d85f72ce80e12cadc36f` |

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
the immutable Koblas root and platform pins listed above transitively. Klause also
strictly pins those coordinates so direct and transitive consumers select the same
numerical binaries.

The pins consume Kumulant revision `9df19df5c48e2cce2caf0a612e51e39430903352`,
published by its [snapshot workflow](https://github.com/Eignex/kumulant/actions/runs/38456722493).
Kumulant coordinates use group `com.eignex` and version prefix `0.3.4-20261013.194059-`:

| Module | Build | Binary SHA-256 |
|---|---:|---|
| kumulant | 67 | `81bf47d09d6135b0857fc49510d17fbeac5dd958c5932753656d021bdfb78bae` |
| kumulant-jvm | 67 | `0b8e8e18088fca26f6045879083cbc11bd1a571dffe36b8cf0a90a1f1fab4c23` |
| kumulant-linuxx64 | 67 | `844f4b33057686b7ab47898757ae053f3aa377a508bc93cd0c7fbfc27ffa3a1a` |
| kumulant-linuxarm64 | 67 | `a705527909d08e53a8b1a05f1051da970980a6183ed2aea315ba9cb8fa6f5416` |
| kumulant-macosarm64 | 66 | `f0ce4c7f2f76c8aae200f8b443d4c4d2ad211733ebc540e7854916425ba1678c` |

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
