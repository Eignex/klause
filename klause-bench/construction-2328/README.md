# Construction slice reproducer

Issue [2328](https://github.com/Eignex/klause/issues/2328) reports 9400 ms of
construction in a 10-second IHTC solve. `baseline.json` pins main
`5471419aa8c832ee1065468a6861bb203a1ff8c7` after the first-solution changes in
[2359](https://github.com/Eignex/klause/pull/2359) and
[2360](https://github.com/Eignex/klause/pull/2360).

The stack contains the reproducer in [2370](https://github.com/Eignex/klause/pull/2370),
the construction fix in [2375](https://github.com/Eignex/klause/pull/2375), and
these records in [2384](https://github.com/Eignex/klause/pull/2384).
The fix supplies an opening token before creating a handle, charges completed
or cancelled opening work once, and gives search the remaining segment time.
Cancelled partial construction closes its resources and retires the arm;
family selection and deferred improvement admission retain their existing policy.

## Reproduction and identity

Submit specs through klause-lab; each explicitly selects `host=aws`, one
runner and one case at a time. Builds use `:klause-cli:installJvmDist` remotely.
The paired campaigns rotate main and candidate cases, with 10-second deadlines
and default or `param.arms=12` pools. The latter has 13 workers including ALNS.

```sh
/home/rasmus/Workspaces/klause-lab/deploy/lab run klause-bench/construction-2328/baseline.json
/home/rasmus/Workspaces/klause-lab/deploy/lab run klause-bench/construction-2328/validated-head-paired.json
```

Archived `cases.json.gz` contains complete records with CLI distribution and
runtime hashes, executed commit, installed-build fingerprint, input catalog
path, budgets, incumbents and counters. `job.json.gz` contains the spec, exact
remote commands, ordering and exits. `arms.json.gz` identifies resolved refs;
`files.json.gz` identifies retained remote artifacts. Early campaigns also
include per-case `summary.json` and archive hashes.

The lab records `reported-result-v1`: incumbents pass the portfolio's model
checker. No new independent source witness check or reference solve is claimed.
Successful uninstrumented AWS cases retain reports rather than full solver
streams. Profile stdout is a bench summary, not a complete source assignment.
Records omit input content hashes; pinned catalog commits and catalog paths
identify the inputs. Never treat an unproven incumbent as an optimum.

`initMs` is included in segment `ms` and `maxMs`. `initWork` is included in
`work`; main's missing `initWork` is unknown, not zero. Work counts search,
propagation and LP operations at their existing conversion rates, not allocation.
Segment time minus opening time includes search, verification and other overhead.
Worker prefixes identify the `bt`, `ls` and `alns` families. These are deadline
experiments, not measurements of fixed-work throughput.

## Baseline and diagnostic pairs

[Lab 853](http://192.168.50.104:8420/jobs/853) completes 24 baseline cases:
seed 3, three repetitions, four problems, two pools. Both pools find model
incumbents on all three controls in all repetitions. Expanded CoinsGrid proves
2236 in all three; default CoinsGrid remains unproven. Neither IHTC pool finds
an incumbent. Its first backtrack opening takes 2008–2108 ms and the second
about 2500 ms; only those two arms execute. This differs from the historical
9400 ms trigger.

[Lab 856](http://192.168.50.104:8420/jobs/856) completes 48 paired cases of
main and semantic candidate `dbe4070b2201525e46fa48aa4ca6ef7aba736e81`.
IHTC finds no incumbent on either build or pool. Main runs only its two
backtrack arms. Candidate cancels both openings and gives two or three local
search arms turns; their reported move counts remain zero. Candidate opening
costs are about 910–925 ms and 1722–1794 ms. This supports earlier handoff,
without a solution-quality or throughput improvement claim.

All Fortress and CoinsGrid cases retain incumbents; expanded CoinsGrid proves
2236 on both builds in every repetition. CyclicBandwidth is variable: default
main finds 1/3 incumbents versus candidate 3/3, while expanded main finds 2/3
versus candidate 1/3. This diagnostic does not establish absence of regressions.
Main's installed-build fingerprint is
`c5f3896bdc0ec8fd17986a96333426c23c7369322641a064d6e0e9fb8f35e566`;
the diagnostic candidate's is
`25e9391439905e34280559ba26a9dc285ca9cabd631c58820677ba1617d3fdde`.

[Lab 864](http://192.168.50.104:8420/jobs/864) completes a separate JFR
IHTC diagnostic on those builds. Candidate retires both backtrack openings and
runs all five remaining arms, including ALNS. Neither build finds an incumbent.
Use it only to diagnose yielding under instrumentation. The Files page retains
JFR recordings, resource reports and measurement manifests; timing claims use
uninstrumented pairs.

## Updated implementation pair

[Lab 887](http://192.168.50.104:8420/jobs/887) completes the pair of implementation
`5bb52a36d93113dae44819e5dd011d56b09616de` against the baseline: two seeds
(3 and 7), two repetitions, four problems and two pools, for 64 serial cases.
The spec includes preserved public worker entrypoints, stats ABI, one-shot
cancellation verdicts and cleanup when satisfaction cancellation polling throws.
All 64 cases complete. Both pools and builds retain incumbents in all four
repetitions of each control. CyclicBandwidth remains unproven at 52; Fortress
remains unproven at 1139298, except one candidate expanded case improves to
1139297. Expanded CoinsGrid proves 2236 in all four repetitions on both builds;
default CoinsGrid remains unproven, with variable objectives.

Neither IHTC build or pool finds an incumbent. Candidate cancels both openings
in all eight cases, then runs all four local-search arms and ALNS. Main runs
both backtrack arms and zero or one local-search arm; ALNS receives no turn.
Candidate opening times are 826–915 ms and 1492–1555 ms, versus main's
1866–2012 ms and 2398–2723 ms. Candidate backtrack and local-search work are
zero; ALNS records its 5000-unit outer repair allowance, not proof of useful
inner search. No unexpected failures or faults are reported on IHTC.

The installed candidate fingerprint is
`f8564a7a7d7e8d228362b001d728038ac83ffc886adf726d381150ecb0392def`.
The subsequent commit `c9f16cc221d670c3c5d0fcf647b8237347cd96af` changes
test fixture session binding and moves the deadline assertion outside the worker
callback; its production source is identical to this measured revision.
`lab-887/cases.csv` and `stats.json` are generated remotely by the lab;
`cases.json.gz` retains every case's complete provenance and per-arm counters.
`ihtc-family.csv` transcribes the IHTC family totals from those counters, with
setup separate from search and segment overhead. Blank main `btInitWork` means
unknown. Every row has no incumbent; every candidate row has two cancelled
backtrack openings.

Lab 872 was cancelled after 29 completed cases on an intermediate candidate.
Labs 878 and 886 were cancelled before
executing cases after implementation updates. These jobs do not validate the
updated implementation.

## Integration after concurrent changes

[Lab 893](http://192.168.50.104:8420/jobs/893) pairs main
`cfe18e514266b3704b4fbdf33877d6a32b29e8cc` with implementation
`9316cc54e6810a9a1a31b29ecbb3b44b32ece7d3`. This includes the concurrent
Boolean implication lifetime and rational normalization fixes. The explicit AWS
spec `integration-paired.json` uses the same four problems and two pools, seed 3
and one repetition. All 16 cases complete. Both builds and pools retain
incumbents on every control; expanded CoinsGrid proves 2236 on both builds.
Default CoinsGrid is unproven at 5974 on main and 6042 on candidate, so this
single integration pair makes no solution-quality improvement claim.

IHTC finds no incumbent on either build or pool. Candidate cancels both openings
and runs all four local-search arms plus ALNS; the expanded candidate gives SA
a second segment. Main runs both backtrack arms and one local-search arm.
Candidate opening time totals 2335/2369 ms for default/expanded, versus main's
4387/4448 ms. Local-search work is zero; candidate ALNS charges 5000 units of
outer repair allowance. Complete records and remotely generated CSV/statistics
are in `lab-893`. Main and candidate installed-build fingerprints are
`3d5b2a8bc14f8f145b178c8f6df3db07e1e63cea99216d429efef65e6eef1963`
and `b51cf0a1c2854536819a4b26415f8531c7b98cb4f31342bbba0f85bc4c70e106`.

## Limits and validation

Propagation and LP structures are allocated before the first opening guard;
allocation can overrun its segment deadline before cancellation is polled.
Cancelled partial handles cannot be resumed. Opening work can be zero when
allocation consumes the time before propagation begins. Earlier sibling turns
do not imply useful sibling search or an IHTC incumbent. The broader allowance
refactor in [2322](https://github.com/Eignex/klause/issues/2322) is outside this
change; these records do not close the remaining construction allocation limit.

[GitHub CI on 9316cc54e](https://github.com/Eignex/klause/actions/runs/37994351655)
passes JVM, Linux native and lint/docs. It validates cancellation, partial construction, sibling and deferred-arm
fallback, retirement, one opening/search deadline, construction charged once,
resumed solve-wide node allowance and the one-shot unknown cancellation verdict.
Before the remote-only policy, a local baseline CLI install completed, one
focused test attempt failed compilation and another was interrupted with exit
130 before tests. No completed local test validation is claimed. Subsequent
builds, experiments and profiles use AWS klause-lab; all test/lint/docs gates
use GitHub CI. Saved remote reports may be inspected while preparing this archive.
