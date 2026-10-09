"""Summarize separately recorded CLI JFR samples after `lab fetch JOB DIR`."""

import collections
import hashlib
import json
import pathlib
import subprocess
import sys


def summarize(root):
    for path in sorted(root.glob("cases/*/profile-*/cli.jfr")):
        events = json.loads(subprocess.check_output([
            "jfr", "print", "--json", "--events", "jdk.ExecutionSample", str(path),
        ]))["recording"]["events"]
        leaf = collections.Counter()
        inclusive = collections.Counter()
        callers = collections.Counter()
        for event in events:
            frames = (event["values"].get("stackTrace") or {}).get("frames", [])
            names = [frame["method"]["type"]["name"].replace("/", ".") + "." +
                     frame["method"]["name"] for frame in frames]
            if names:
                leaf[names[0]] += 1
            inclusive.update(set(names))
            for index, name in enumerate(names[:-1]):
                if name.endswith("BigFraction$Companion.of"):
                    callers[names[index + 1]] += 1
        record = json.loads((path.parent / "solve-record.json").read_text())
        yield {
            "problem": record["problem"],
            "buildFingerprint": record.get("buildFingerprint"),
            "jfrSha256": hashlib.sha256(path.read_bytes()).hexdigest(),
            "samples": len(events),
            "leaf": leaf.most_common(30),
            "inclusiveKlause": [(name, count) for name, count in inclusive.most_common()
                                if name.startswith("com.eignex.klause")][:50],
            "issueSymbols": {name: count for name, count in inclusive.items()
                             if any(symbol in name for symbol in (
                                 "projectScalars", "BigFraction", "LiveQfLraSystem.install",
                                 "withPublishedBounds", "Cancellation.invoke"))},
            "fractionFactoryCallers": callers.most_common(),
        }


if __name__ == "__main__":
    print(json.dumps(list(summarize(pathlib.Path(sys.argv[1]))), indent=2))
