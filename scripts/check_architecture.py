#!/usr/bin/env python3
"""Check explicit Kotlin package dependencies without compiling the solver."""

import argparse
from pathlib import Path
import re
import sys

ROOT = "com.eignex.klause"

# Closed lists apply to mechanism packages; forbidden lists express caller separation.
ALLOWED = {
    "util": ("util",),
    "ir": ("ir", "util"),
    "simplex.exact": ("simplex.exact", "util"),
    "simplex.basis": ("simplex.basis", "simplex.exact", "util"),
    "lp.lattice": ("lp.lattice", "util"),
    "lp.engine": ("lp.engine", "util", "simplex.exact", "lp.lattice", "simplex.basis"),
    "arithmetic.difference": ("arithmetic.difference", "ir", "util"),
}
FORBIDDEN = {
    "lowering": ("compile", "schema", "formats"),
    "formats": ("compile", "schema", "theory"),
    "bound": ("lp.engine",),
    "solver.pipeline": ("formats",),
    "theory": ("propagation", "backtrack", "ir.IntDomain", "ir.intdomain"),
}
NAME = r"[A-Za-z_][A-Za-z_0-9]*"
QUALIFIED = re.compile(rf"\b{re.escape(ROOT)}\.(?:{NAME}\.)*(?:{NAME}|\*)")
PACKAGE = re.compile(rf"^\s*package\s+({NAME}(?:\.{NAME})*)", re.MULTILINE)


def within(name, prefix):
    return name == prefix or name.startswith(prefix + ".")


def code_only(text):
    """Mask comments and literal text, retaining line offsets and template expressions."""
    out = list(text)
    size = len(text)

    def mask(start, end):
        for index in range(start, end):
            if out[index] != "\n":
                out[index] = " "

    def code(start, template=False):
        index = start
        braces = 1 if template else 0
        while index < size:
            if text.startswith("//", index):
                end = text.find("\n", index)
                end = size if end < 0 else end
                mask(index, end)
                index = end
            elif text.startswith("/*", index):
                end = index + 2
                depth = 1
                while end < size and depth:
                    if text.startswith("/*", end):
                        depth += 1
                        end += 2
                    elif text.startswith("*/", end):
                        depth -= 1
                        end += 2
                    else:
                        end += 1
                mask(index, end)
                index = end
            elif text[index] in "\"'":
                index = literal(index)
            elif template and text[index] == "{":
                braces += 1
                index += 1
            elif template and text[index] == "}":
                braces -= 1
                if braces == 0:
                    return index
                index += 1
            else:
                index += 1
        return index

    def literal(start):
        delimiter = '"""' if text.startswith('"""', start) else text[start]
        index = start + len(delimiter)
        mask(start, index)
        while index < size:
            if text.startswith(delimiter, index):
                mask(index, index + len(delimiter))
                return index + len(delimiter)
            if delimiter != '"""' and text[index] == "\\":
                mask(index, min(index + 2, size))
                index += 2
            elif delimiter != "'" and text.startswith("${", index):
                mask(index, index + 2)
                index = code(index + 2, template=True)
                mask(index, min(index + 1, size))
                index += 1
            else:
                mask(index, index + 1)
                index += 1
        return index

    code(0)
    return "".join(out)


def violations(text):
    code = code_only(text)
    match = PACKAGE.search(code)
    if not match or not within(match[1], ROOT):
        return []
    owner = match[1][len(ROOT) + 1:]
    results = []
    for dependency in QUALIFIED.finditer(code):
        target = dependency[0][len(ROOT) + 1:]
        if dependency.start() == match.start(1):
            continue
        for package, allowed in ALLOWED.items():
            if within(owner, package) and not any(within(target, prefix) for prefix in allowed):
                results.append((code.count("\n", 0, dependency.start()) + 1, owner, target))
        for package, forbidden in FORBIDDEN.items():
            if within(owner, package) and any(within(target, prefix) for prefix in forbidden):
                results.append((code.count("\n", 0, dependency.start()) + 1, owner, target))
    return results


def sources(root):
    for module in sorted(root.glob("klause*")):
        source_root = module / "src"
        if source_root.is_dir():
            for source in sorted(source_root.glob("*Main/kotlin/**/*.kt")):
                yield source


def check(root):
    paths = list(sources(root))
    if not paths:
        return ["No production Kotlin sources found"]
    return [
        f"{path.relative_to(root)}:{line}: {owner} must not depend on {target}"
        for path in paths
        for line, owner, target in violations(path.read_text())
    ]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    args = parser.parse_args()
    problems = check(args.root)
    if problems:
        print("\n".join(problems), file=sys.stderr)
        return 1
    print("Documented Kotlin package boundaries passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
