# SMT optimization reporting

Evidence for [#2388](https://github.com/Eignex/klause/issues/2388) and
[PR #2394](https://github.com/Eignex/klause/pull/2394).

The CLI emits model-oriented `; objective=<value>` comments for incumbents and an explicit
`optimizationStatus` at completion. The benchmark requires `sat`, a finite objective and
`optimal` status for optimum credit. Interrupted incumbents and unbounded witnesses retain
their objective and distinct terminal status without finite-optimum credit. Plain `sat`
establishes feasibility only. These metadata comments are independent of `-s`.

## AWS controls

[Job 920](http://192.168.50.104:8420/jobs/920) completed all 40 cases with no execution failures:
the ten existing `smtlib-core,mps-core` fixtures, two revisions and seeds `0,1`.
`aws-spec.json` fixes main at `c82d8f3f7461602340c8dbebffeca2dd0b02db93` and the implementation
at `5beb76bcc6e234a1bcf2701a2f49b881e16deb56`, with explicit `host=aws`, one EC2 instance,
`engine=cp`, one processor and a 5000 ms solve budget. The AWS runner uses two concurrent cases
on a c7i.2xlarge; each problem's arms run on the same instance.

Both seeds produce these outcomes:

| Fixture | Main | Candidate |
| --- | --- | --- |
| `lia-opt` | SAT, objective absent, unproven | Objective 7, optimal status, proven |
| `lia-basic`, `lia-disjunction`, `lia-wide-span`, `lra-rational` | SAT | SAT |
| `feasible-tiny`, `float-tiny` | SAT | SAT |
| `lia-unsat`, `infeasible-tiny` | Proven infeasible | Proven infeasible |
| `blend-tiny` | Proven optimum 9 | Proven optimum 9 |

Feasibility agrees in every pair. The only objective/proof difference is the intended restoration
of `lia-opt`'s catalog optimum 7. Main's installed-build fingerprint is
`0e383191f940212f4a001b113864c056322f361e3eee5780789142f7cb2ac814`; the candidate's is
`d1e520040fc4eb959e1d00e93bd782131176b5655562ffc9bc9081c4f880db87`.
Each arm retains one stable installed CLI/runtime identity across its 20 cases.

`cases.json.gz` retains the complete raw case API response, including installed-build provenance,
source hashes, exact commands and statistics. `results.json` is a readable result projection;
`builds.json` retains one full provenance manifest per arm. The submitted spec, selected problems,
job record, case CSV and setup/job logs are retained alongside them.

The policy is `reported-result-v1`: these controls compare reported outcomes, without independent
source-witness validation or a performance claim. The lab records do not retain raw solver stdout;
SMT `finalWitness` is absent, tracked in [#2397](https://github.com/Eignex/klause/issues/2397).
The existing Long/Double objective channels retain their numeric limits, tracked in
[#2395](https://github.com/Eignex/klause/issues/2395).

## CI validation

CLI regressions cover the disjunctive optimum, model-oriented integer/real objectives, unbounded
descent, interrupted incumbents and unknown stop causes. Benchmark regressions cover explicit
optimum credit, plain sat, distinct nonoptimal statuses, malformed objectives, infeasibility and
cache retention. CI on PR #2394 supplies the JVM, Linux native and lint/docs gates.
CLI and benchmark JVM reports are included in the test artifact, addressing
[#2396](https://github.com/Eignex/klause/issues/2396).

The real-objective direction fixture uses a bounded integer column linked to the real column,
matching the existing finite exact-witness coverage. Pure-real open objective support is
outside this reporting change; [#2260](https://github.com/Eignex/klause/issues/2260) is closed
as not planned.

No local build, test, solve, benchmark or validation script ran. Later changes to tests, CI report
paths and this evidence archive do not change the shipping CLI or benchmark implementation
measured by job 920.
