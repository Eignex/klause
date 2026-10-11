# Portfolio slice policy

The finite shared schedule retains a base of 5000 node equivalents, maximum of
150000 and growth of 1.5. A smaller base and faster growth each help some measured
inputs and hurt others; the selection does not justify replacing the common
schedule. The [portfolio contract](../solver/portfolio.md#slice-accounting-and-coverage)
owns scheduling, and [resource allowances](../solver/resources.md) owns currencies
and persistent quotas. These defaults are a measured compromise on the selection,
not an optimum for every input, budget or runtime.

## Scope and controls

The controlled comparison used 13 fixed inputs across MiniZinc, XCSP3, MPS,
DIMACS, WCNF and OPB, with two seeds and two repeats per setting (312 runs).
Each run used one processor, a 200000 decision/move-equivalent solve allowance
and a 30-second outer deadline. AWS instances grouped each input's settings and
repeats on the same machine. Two commands could run concurrently per instance. Subprocess time includes
loading and preparation and is not CPU time.

| Setting | Base | Maximum | Growth |
|---|---:|---:|---:|
| Implicit default | 5000 | 150000 | 1.5 |
| Explicit default | 5000 | 150000 | 1.5 |
| Smaller base | 1000 | 150000 | 1.5 |
| Smaller maximum | 5000 | 30000 | 1.5 |
| Faster growth | 5000 | 150000 | 2 |
| Flat | 5000 | 5000 | 1 |

Every record's effective settings, source hashes, executed revision and installed
build identity were checked. The explicit default is an identity control, not a
second policy. The other settings change the named axes; the historical A/B
scripts are outside this measurement. Growth changes both work and elapsed
segment limits. An equal nominal solve allowance does not imply equal completed
work: early proofs, expensive preparation and deadlines can stop below that cap.
Comparisons distinguish equal ledger charges from observations bounded by time.

## Decision evidence

| Input | Observation | Consequence |
|---|---|---|
| Cargo `challenge05_1s_954` | Default returns four source-checked witnesses at cost 11032; the smaller base returns none. All eight runs charge 200000 units. | Retain the 5000 base for initial coverage and preparation. |
| MIPLIB3 `flugpl` | Every setting proves cost 1201500. The smaller base uses roughly 16000–18000 units; the default uses roughly 37000–43000. | A smaller base can reduce work before a proof, but does not dominate. |
| DIMACS `hole9` | Both defaults refute at 10026–10029 units; the smaller base refutes at 2026–2029. | Early refutations need less LS admission work with a smaller base. |
| WCNF `GenHyperTW_grid5` | Faster growth returns four source-checked cost-6 witnesses; the other settings return none at the same 200000-unit charge. | Growth matters to useful preparation and cannot be inferred from raw decisions alone. |
| XCSP3 `GBACP-UD04_c23` | Faster growth and the smaller maximum return cost 7470 on seed 3; default outcomes include 6744. The smaller base improves both seeds. These runs have different completed charges and often reach the deadline. | Neither faster growth nor a smaller maximum earns a general replacement claim. |

The flat and smaller-maximum schedules provide no consistent advantage on this
selection. Keeping 150000 allows useful segments to grow beyond the smaller
ceilings while retaining a finite upper bound. The short budgets do not establish
a globally best maximum. The growth-2 WCNF benefit does not establish a general
benefit across source-checked scheduling and allocation inputs, so 1.5 remains
the common growth rate.

Implicit and explicit defaults resolve identically but some time-limited outcomes
and native counters differ across repeats. Fixed work scheduling therefore does
not make a solve deterministic when elapsed limits, atomic work, preparation or
parallel execution determine its stopping point. Scheduling inputs can spend far
less ledger work in 30 seconds than the smaller SAT/MPS inputs. Existing policy
prices propagation and LP work in slices; the ledger retains its separate CP/LS
currency. The data supports retaining those explicit currencies and elapsed caps;
it does not establish an nnz-based or online node-cost scaling rule.

## Acceptance and limits

MiniZinc and XCSP3 final witnesses were checked against original source constraints
and objectives using the recorded independent checker policies. Boolean witnesses
were checked with exact source arithmetic, and MPS points with original rational
rows, bounds, integrality and objectives. The comparison accepted 229 final
witnesses and independently checked all 72 completed native proof claims: source
refutations or strictly better objective exclusion.
Witness acceptance and proof credit remain separate. Preparation statistics and
LS violation reductions on IHTC do not earn witness credit; only its accepted
source assignments do.

A separate 52-run one-/four-processor check used a 20000-unit solve cap, one seed
and two repeats. Parallel lanes retained unused quota after completion or stopping;
no run exceeded the aggregate cap. The width changes both quota ownership and
available processors and is not a matched-work speed comparison. Private clausal
primal decisions and walk moves charge the ledger without appearing in all
exported arm counters; comparisons use `nodeBudgetSpent` as charge authority.

The source revision is `fdf42c72c1e4eda1d9e4d421fb84fd0cd53318c3`; subsequent
resource documentation edits do not change the measured implementation. The
installed one-processor build fingerprint is
`bc6493388cbee0429550eb7409002b29e86d0a19810db600fb8bb4209786069f`.
The [review evidence](https://github.com/Eignex/klause-lab/releases/tag/active-evidence-resource-scopes-slice-policy)
contains membership, specifications, raw records, build hashes, source checks and
static analysis. The manifest SHA-256 is `f5f9a48fccd58b6e5bb26068de2b5572e1d25481a6ddd82f8b3c1c069e7f576c`.
Source inputs, installed binary bytes, profiles and original subprocess stdout
are outside that snapshot; records retain final witnesses and available output
hashes. The evidence establishes this selection and runtime only.
