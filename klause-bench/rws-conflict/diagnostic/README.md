# Pre-policy diagnostics

These local builds and 10-second solves completed before the remote-only execution policy arrived.
They ran on source base `5471419aa8c832ee1065468a6861bb203a1ff8c7`; the second build applied only
the retained tracing patch. Both builds used `./gradlew :klause-cli:installJvmDist`.

Both actual solves used this command, redirecting to `main.out` or `trace.out` respectively:

```sh
JAVA_HOME=/home/rasmus/.gradle/jdks/eclipse_adoptium-25-amd64-linux.2 \
flock /tmp/klause-rws-diagnostic.lock \
klause-cli/build/install/klause-cli-jvm/bin/klause-cli \
  -e cp -p 1 -r 1 -t 10000 -s --lp off --param arms=1 \
  --param bt-arm=conflictDriven --param node-limit=100000 \
  /home/rasmus/.cache/klause-bench/corpus/smtlib-qf_lia/non-incremental/QF_LIA/RWS/Example_2.txt.smt2
```

The first Java 22 attempt failed with UnsupportedClassVersionError before solving. The successful
baseline installed jars were overwritten by the tracing build before a fingerprint was captured;
their exact executed binary identity is unavailable. The tracing installation remained untouched
after the policy change, and its jar/launcher hashes were read later without running it again.
Neither diagnostic is accepted as a timing comparison: the tracing changes work and output, and
local contention was not audited. The exclusive file lock covers only this diagnostic command.
No local workload was interrupted at the policy change, and no further local build, solve or test ran.
