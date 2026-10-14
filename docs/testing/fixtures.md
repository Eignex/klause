# Fixture provenance

Fixtures remain beside the tests and measurement tools that consume them. Their
source identities, licenses and hashes describe those committed bytes, rather
than the current solver's performance.

## Basis corpus

The version-1 `.kbtrace` corpus lives under
[basis-corpus](../../klause/src/jvmTest/resources/basis-corpus/).
Its [manifest](../../klause/src/jvmTest/resources/basis-corpus/manifest.json)
fixes source repository paths and revisions, redistribution licenses, source and
trace SHA-256, dimensions, operation counts, capture limits and exclusions.
The six traces total 219,993 bytes. Preserve that manifest with the traces.

| Trace | Origin and license | Capture route |
|---|---|---|
| `mps-afiro`, `mps-adlittle` | Public MPS test inputs; exact upstream paths/revision in manifest; MIT | Production CP/MIP relaxation |
| `mzn-graph-coloring` | Repository `klause-mzn-lib/test-models/graph_coloring.mzn` | Production CP/MIP relaxation |
| `mzn-timetabling` | Public FlatZinc example; exact upstream path/revision in manifest; Apache-2.0 | Production CP/MIP relaxation |
| `smt-lia-wide-span`, `smt-lia-unsat` | Self-authored `klause-bench/smoke-corpus/smtlib/` inputs | `SMT_SOURCE_DERIVED` |

Traces store matrix/RHS IEEE bits, headings and observed operations, rather than
serialized factors or native pointers. Public source MPS/FlatZinc inputs are not
copied into the corpus. Regeneration requires the upstream revision and exact
source bytes identified by the manifest. Replay uses the current basis backend;
capture identity is not a current dependency requirement.

SMT source-derived traces project the asserted exact linear state into a floating
engine for the harness. They are not complete production theory event captures.
The manifest records the Sudoku no-LP decline and rational-LRA disjunction
exclusion; inventing a Boolean branch would misrepresent capture state.
See [capture commands and checks](../lp/replay.md).

## Smoke corpus

[smoke-corpus](../../klause-bench/smoke-corpus/) contains small parser and solve
fixtures grouped by format. Larger or non-redistributable collections are fetched
through the catalog, with their license and exclusion policy in the collection
declaration. See [corpus management](../../klause-bench/README.md#catalog-and-corpus).

| Directory | Contents and origin |
|---|---|
| `dimacs` | SATLIB-style small random 3-SAT samples; self-authored pigeonhole, implication-chain, bipartite-colouring and weighted MaxSAT cases |
| `opb` | Self-authored set-cover/cardinality; academic PB/MaxSAT competition samples `sporttournament06.opb` and `queens4-soft.wbo` |
| `schema` | Self-authored campaign and roster schemas; regenerate campaign with `:klause-bench:dumpSchema` |
| `smtlib` | Self-authored linear integer/real satisfaction, optimization, infeasibility, disjunction and exact-rational cases |
| `xcsp3` | Self-authored magic-series, sum optimization, magic-square and graph-colouring cases |
| `mps` | Self-authored integer and continuous optimization, equality feasibility, infeasibility, unboundedness and open decimal witness controls |
| `lp-component-split` | Self-authored dense bounded block systems for the explicit measurement suite |

MiniZinc smoke models live under
[klause-mzn-lib/test-models](../../klause-mzn-lib/test-models/) and are referenced
by the `mzn-smoke` suite. They are not duplicated in the smoke corpus.

The width-`n` component-split matrix is `nI + 11^T` with RHS `2n`, giving exact
solution `x = 1`. The linked MiniZinc control adds a redundant row joining blocks.
Regenerate using
[generate.py](../../klause-bench/scripts/lp-component-split/generate.py).
These fixtures are explicit experiments rather than CI smoke selection.

## Polynomial-real SMT fixtures

[QF_NRA version 1](../../klause-bench/smoke-corpus/qfnra-v1/manifest.json)
contains 37 bounded core, 24 frozen holdout and five separate extension controls.
Every source is original work by Eignex Klause contributors under Apache-2.0;
no third-party corpus bytes are redistributed. The manifest fixes suite/name,
path, original SHA-256, mathematical expectation, membership class, reason,
attribution and license. Mathematical expectations are not executed verification.
Catalog core/holdout expectations retain SAT/UNSAT; extension expectations remain
unknown even when a reference accepts the extension.

Polynomial cases declare only Real/Bool variables and use exact rational/decimal
constants, polynomial arithmetic and Boolean comparisons. Constant nonzero division
is rational coefficient syntax. Symbolic division, mixed integer sorts, negative
powers, transcendental functions and multiple-query sessions have separate identities.
The fetched QF_NRA archive preserves its per-family attribution/licenses and is
not certified as polynomial by its logic tag. See the benchmark README for
[named selection](../../klause-bench/README.md#selection-and-named-sets) and
[reference authority](../../klause-bench/README.md#smt-reference-records).

[Canonical source variants](../../klause-bench/smoke-corpus/qfnra-source-canonical-v1/manifest.json)
retain degree-drop and equal-degree common-root cases with canonical `(- 1)`
constants. Their manifest fixes each variant's source hash, original identity/hash
and exact syntactic transformation under the original Apache-2.0 attribution.
Bare `-1` in the two frozen originals is accepted by recorded Z3 but is a source
name under the dedicated polynomial parser; source loading declines explicitly.
Variants have a separate suite/set; original bytes, identities and memberships
remain frozen. Auditing all 66 originals for unquoted negative numeric atoms found
only these two sources; that narrow audit does not prove every other source form
is admitted or decided.

## Nonlinear integer SMT fixtures

[Nonlinear integer version 1](../../klause-bench/smoke-corpus/nonlinear-integer-v1/manifest.json)
fixes twelve self-authored Apache-2.0 inputs covering bounded products, powers,
Int/Real composition, Boolean correlation, lexical helpers and assumptions,
arbitrary-width arithmetic and open integer search limits. The suite and named
set share these identities. SAT/UNSAT expectations describe source mathematics;
incomplete search can return UNKNOWN, including the integer equation `n*n = 2`.
Every manifest entry pins the exact original bytes, attribution and license.

## XCSP3 reference importer

[`test_solve.py`](../../klause-bench/xcsp3-cpsat/test_solve.py) contains self-authored
constant and variable matrix fixtures, grouped conditions and axis-bound failures.
Its weighted expression-sum, contradiction, unconstrained-variable and wide
objective controls are self-authored Apache-2.0 sources. Full fetched XCSP sources
and saved witness records remain external; their source hashes and independent
checker receipts bind remote regression controls to original bytes.
The reference importer uses column-count strides for row-major matrix selection
and enforces both source index bounds. Source-preserving reference records retain
the installed image/importer identity, complete source assignment and exact integer
objective. Negative extension tuples retain their complement semantics under
source-domain exclusions through explicit conflict clauses.
See the [benchmark reference workflow](../../klause-bench/README.md#klause-lab)
for image preparation, replay and the distinction between source witnesses and
native proof claims.

## MPS source verifier

The MPS source-verifier regressions in
[`test_verify_source_results.py`](../../klause-bench/tools/test_verify_source_results.py)
include a two-row projection of MIPLIB 3 `egout`: flow rows `031` and `033`, their
original cost coefficients and fixed source decimals. Rounded coordinates exercise
the source tolerance contract; the fixture retains no generated solve records.
The complete source is fetched by the catalog/verifier from the
[MIPLIB 3 archive](https://miplib2010.zib.de/miplib3/miplib3.tar.gz), with model hash
`351ed046eb56e07662b3c2dba35800f5d2cee3fac18c1bd7fbc8dea4a0dcd8db`.

## Measurement storage

The self-authored Apache-2.0 [objective-cli-v1 manifest](../../klause-bench/smoke-corpus/objective-cli-v1/manifest.json)
fixes original mixed product, rational, algebraic, strict-limit, unbounded and
infeasible SMT inputs. Affine and zero-square objectives retain the same source
constraints and both directions. The
[CLI publication probe](../../klause-bench/README.md#objective-cli-publication-probe)
checks emitted source claims independently and preserves the original hashes.

The self-authored Apache-2.0 [objective-affine-v1 sources](../../klause-bench/smoke-corpus/objective-affine-v1/manifest.json)
retain `x*x=y`, `-1<=x<=1` and both directions of `Long.MAX_VALUE+y`. Their exact
optima are `9223372036854775807` and `9223372036854775808`, which share a binary64
projection. The manifest fixes source hashes; the
[objective representation probe](../../klause-bench/README.md#objective-representation-probe)
compares public entries without changing original constraints or direction.
Its captured square schema exercises named continuous float compilation; declared
`0<=y<=1` bounds follow from those same original source constraints.

The self-authored Apache-2.0 [objective-scopes-v1 fixtures](../../klause-bench/smoke-corpus/objective-scopes-v1/manifest.json)
retain finite integer domains, AllDifferent, fixed `x=y=1`, `x*x=y` and explicit
Boolean/integer pins. Two scopes change the constant and pins while retaining
separate Boolean, integer and real declaration-zero identities. Their exact costs
are `31/2` and `27/2` in both directions. Schema and condition hashes accompany
the immutable original source hashes; generated execution records stay external.

Keep generated outputs and historical campaign bundles outside the repository.
`klause-bench` contains the active harness, current corpus fixtures, named sets
and reusable tools. Installed CLI solve records remain local under the ignored
`klause-bench/output/<config>/` directories. Preserve source identities, licenses,
effective settings and build/runtime provenance in any external measurement archive.

## FlatZinc witness

[enigma_248_add_or_multiply.fzn](../../klause/src/jvmTest/resources/flatzinc/enigma_248_add_or_multiply.fzn)
is the source model compiled with MiniZinc 2.9.7 and Klause's globals library at
`58ae735e7b527a9d5e1050a0111b78f38b8590f0`. Local-path comments were removed;
all 129 constraints and 90 scalar range bounds remain. The test adds integer
pins without recompiling or simplifying the fixture.

The source is Hakan Kjellerstrand's model, revision
`cfdfb67f9a22836ab6b9de0ee3940c6947742bec`, path
`minizinc/enigma_248_add_or_multiply/enigma_248_add_or_multiply.mzn`.
Source SHA-256 is
`30663fd9b31d5247bee948ab34ded177684300c9b993dcf0c3120fa11b766484`;
fixture SHA-256 is
`fad0d2404c876709934af756c4b83edc1d0f5ea97ce5d9b5f4f743fa4c30f27f`.

The witness in half-pennies is `(2,4,6)`, `(2,3,10)`, `(1,8,9)`, `(1,6,14)`,
`(1,5,24)`. Tests check returned values against every original predicate and
bound with direct arithmetic; the dyadic values are exactly representable in
binary64. The MIT copyright/permission notice remains in the fixture
[README](../../klause/src/jvmTest/resources/flatzinc/README.md).

[voltage_divider.fzn](../../klause-bench/src/test/resources/flatzinc/voltage_divider.fzn)
is Hakan Kjellerstrand's `minizinc/voltage_divider/voltage_divider.mzn` from the
Hakank corpus at revision `cfdfb67f9a22836ab6b9de0ee3940c6947742bec`,
compiled with MiniZinc 2.9.7 and Klause's globals library. The MIT notice remains
in the fixture. Local-path
comments were removed. All 15 original flattened predicates and six scalar range
bounds remain. The test checks exact witness coordinates without solver lowering.
Source SHA-256 is
`ab64d949341580bf5ea6c556e6d708b2285f40593f0de49b881f15869aa4d88e`;
fixture SHA-256 is
`e8353427783116d2980f3b0c05579d493fb072e92c18d38008fec458c3f840f7`.

The self-authored [weighted penalty complement fixture](../../klause-bench/smoke-corpus/dimacs/weighted-penalty-complements.wcnf)
in `wcnf-core` combines nonuniform unit/nonunit costs, a hard clause with repeated
literals, a source alias and a tautological soft clause. Its exact optimum is 5
at `(x1, x2, x3) = (1, 0, 1)`.
