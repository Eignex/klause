# Reference solver for DIMACS (SAT/CSP) and OPB (pseudo-boolean, incl. optimization). clasp reads both
# formats natively and is a strong Potassco solver, so one container covers both — the JVM bench runs it
# per instance (memory-capped, like the vizier/xcsp3 containers) and parses its `s`/`o` output. No host
# install: clasp is apt-installed inside the image.
FROM debian:stable-slim
RUN apt-get update && apt-get install -y --no-install-recommends clasp && rm -rf /var/lib/apt/lists/*
# clasp reads the instance on stdin, so the JVM pipes it in (`docker run -i … --time-limit=N`); no bind
# mount. DIMACS goes in verbatim; OPB gets its `* #variable=` problem line synthesized first.
ENTRYPOINT ["clasp"]
