# External campaign evidence

The [campaign evidence workflow](../../.github/workflows/campaign-evidence.yml)
runs explicitly dispatched campaign checkers on GitHub CI without committing
measurement bundles. Stage temporary campaigns under
`/home/rasmus/Workspaces/klause-evidence/campaigns/<name>/`.

Prepare an xz-compressed tar archive containing `check.py`, the raw checking
inputs, frozen experiment specifications and required checking scripts. The
workflow accepts its base64 encoding as the `payload` input; GitHub's dispatch
input-size limit applies. Extraction rejects unsafe archive paths. The checker
runs from the repository root against the selected checkout and writes results
inside `campaign/`.

Keep source/build identities, selection, settings and raw records in the bundle.
The checker must distinguish reported outcomes from independent source/proof
validation and reject incomplete comparisons before interpreting timings.
The workflow uploads inputs, checking code and results even when checking fails.
Artifacts expire after 90 days; preserve measurement archives separately.
Promote reusable harness tools and fixtures into `klause-bench`; keep campaign-only
scripts and generated results in the external staging directory.
