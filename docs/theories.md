# Theory services

A theory service enforces a fragment of the shared source model. Search supplies
Boolean choices or arithmetic decisions; the theory checks their consequences and
contributes authoritative coordinates or conflicts. Frontends select no concrete
theory, and finite propagation retains Bool+Int state only.

Difference, linear integer, linear real, linear mixed, nonlinear integer and polynomial real arithmetic
are peer contracts. Each page describes its scope, composition, result authority,
state/resources and implementation. Shared mechanisms can serve several theories;
a separate semantic contract does not require a separate implementation package.

## Theory directory

| Theory | Source fragment | Main mechanism |
|---|---|---|
| [Difference logic](theories/difference.md) | Admitted integer or real difference constraints and Boolean guards | Graph potentials and negative cycles |
| [Linear integer arithmetic](theories/linear-integer.md) | Linear integer constraints with Boolean structure | Exact linear checks, integer reductions and branching |
| [Linear real arithmetic](theories/linear-real.md) | Linear real constraints with Boolean structure | Exact rational linear checks; a graph for admitted difference fragments |
| [Linear mixed arithmetic](theories/linear-mixed.md) | Linear integer/real constraints with Boolean structure | Exact linear checks with integrality-preserving search |
| [Nonlinear integer arithmetic](theories/nonlinear-integer.md) | Polynomial Int/Real/Bool satisfaction | Incomplete integer search and repairs with exact real completion |
| [Polynomial real arithmetic](theories/polynomial-real.md) | Polynomial real constraints with Boolean structure, optionally beside finite factors | Exact algebraic completion and CAD |
| [Transcendental real arithmetic](theories/transcendental.md) | Exact exp/log/sin/cos constraints with Boolean and mixed numeric declarations | Certified enclosures, symbolic checks and incomplete traversal/neighborhoods |

These are implemented fragments, not a requirement that every source or operation
use the same algorithm. [Input semantics](formats.md) owns frontend admission and
current route limits. [Solver vision](vision.md) requires common strategy breadth
and distinguishes proof authority from mathematical completeness.

## Extension roles and completeness

`Theory<A>` checks one source fragment in a batch under a complete Boolean assignment
and active semantic bounds. `TheorySearchComponent<A>` bridges that service into
shared decisions, propagation, complete checks and retraction. A decided batch answer
is cached only while its conditions remain active; unresolved answers are not cached.

`SearchComponent` is the common incremental participant contract for finite propagation
and arithmetic. Incremental arithmetic implementations need not implement `Theory`.
Optional branching, blocking and conflict capabilities extend that lifecycle.
`TheoryComponent` remains a deprecated compatibility marker with no extra behavior.
Arithmetic components retain the dependency boundary against finite CP domains.

Participation and completeness are separate. A batch service can be sound and incomplete;
its legacy `TheoryCheck.Cancelled` answer leaves the fragment undecided under cancellation,
limits or incomplete checking. The bridge maps it to an indeterminate component answer.
An indeterminate required leaf cannot justify source exhaustion. Fragment feasibility,
conditional infeasibility and global proof authority retain their distinct scopes.

## Shared composition contract

The target [factor and ownership contract](vision.md#high-level-factors-and-composed-reasoning)
allows finite propagation and theory encoding for the same source factor.
[Encoder foundation #2850](https://github.com/Eignex/klause/issues/2850) tracks this
extension; the composition rules below describe current execution.

The pipeline selects ownership from the source model. A theory-owned integer is not
materialized as a guessed finite domain. Real coordinates remain arithmetic-owned.
When a model also contains finite factors, each engine projection retains the part
it can enforce and completion checks the remaining source constraints.

[Search](solver/search.md) owns decisions, levels, retraction and shared reasons.
Its neutral `SourceBoundAtom` describes exact source-coordinate predicates without
linear-theory or LP dependencies. Arithmetic adapters interpret the same normalized
meaning; linear row conversion remains in the linear adapter. Shared Boolean naming
alone supplies no agreement between independently chosen arithmetic witnesses.
Explicit registered arithmetic peers use
[coordinate agreement](solver/search.md#arithmetic-coordinate-agreement) to exchange
admitted exact source predicates and check one authoritative typed point. Rational
linear peers implement that protocol; unsupported combinations retain combined
ownership or return indeterminate. Fragment participation is separate from procedure
completeness and global claim authority.
[Local search](solver/local-search.md#continuous-candidate-completion) owns candidate
generation and requests completion before publication. [Portfolios](solver/portfolio.md)
shares only accepted witnesses and justified deductions under the same source and
objective scope. A contradiction in a pinned candidate or repair neighbourhood is
not a global refutation.

## Results and proof authority

An exact witness retains its authoritative integer, rational or algebraic values
and passes the required source checks. Refutation requires a completed proof over
the relevant source scope and alternatives. A theory relaxation point is not a
witness for omitted integrality or finite factors.

Optimization has its own admission and proof conditions. Feasibility support alone
does not imply support for an objective, an attained optimum or unboundedness.
Theory pages state those limits; [LP certification](lp/certification.md) owns linear
numerical evidence. Current certificates remain in-memory attestations;
[independent proof export](vision.md#independently-checkable-proof-files) is a vision
requirement across theories.

## State and resources

Source descriptions can be shared read-only; live query, arithmetic and traversal
state belongs to an invocation or retained worker. Retraction invalidates evidence
whose premises disappear. Published coordinates outlive the producing workspace.

[Sessions](solver/sessions.md) owns caller scopes and operation lifetimes.
[Resource allowances](solver/resources.md) owns containing ledgers. Each theory
retains its documented native units; cancellation and resource exhaustion cannot
be turned into refutation or hidden by reopening a worker.
