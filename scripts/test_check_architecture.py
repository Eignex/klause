"""Regression checks for the architecture dependency gate."""

from pathlib import Path
import tempfile
import unittest

from check_architecture import ALLOWED, FORBIDDEN, check, violations


class CheckArchitectureTest(unittest.TestCase):
    def test_closed_packages_reject_upward_dependencies(self):
        for package in ALLOWED:
            with self.subTest(package=package):
                self.assertTrue(violations(
                    f"package com.eignex.klause.{package}\n"
                    "import com.eignex.klause.formats.flatzinc.FlatZincProgram\n"
                ))

    def test_closed_packages_accept_each_permitted_package(self):
        for package, allowed in ALLOWED.items():
            for dependency in allowed:
                with self.subTest(package=package, dependency=dependency):
                    self.assertEqual([], violations(
                        f"package com.eignex.klause.{package}\n"
                        f"import com.eignex.klause.{dependency}.SomeType\n"
                    ))

    def test_caller_boundaries_reject_each_forbidden_package(self):
        for package, forbidden in FORBIDDEN.items():
            for dependency in forbidden:
                with self.subTest(package=package, dependency=dependency):
                    self.assertTrue(violations(
                        f"package com.eignex.klause.{package}.nested\n"
                        f"import com.eignex.klause.{dependency}.SomeType\n"
                    ))

    def test_aliases_wildcards_and_qualified_calls_cannot_hide_dependencies(self):
        for source in (
            "import com.eignex.klause.propagation.Session as S",
            "import com.eignex.klause.propagation.*",
            "val s = com.eignex.klause.propagation.Session()",
            'val s = "${com.eignex.klause.propagation.Session()}"',
            'val s = """${com.eignex.klause.propagation.Session()}"""',
        ):
            with self.subTest(source=source):
                self.assertEqual(2, violations("package com.eignex.klause.lp.engine\n" + source)[0][0])

    def test_shared_search_predicates_cannot_depend_on_linear_adapters(self):
        for dependency in ("theory.qflra.SourceBoundAtom", "lp.engine.ExactLpRow", "lp.*"):
            with self.subTest(dependency=dependency):
                self.assertTrue(violations(
                    "package com.eignex.klause.solver.search\n"
                    f"import com.eignex.klause.{dependency}\n"
                ))

    def test_predicate_consumers_use_the_neutral_boundary(self):
        for owner in ("lp.bounding", "solver.pipeline"):
            with self.subTest(owner=owner):
                self.assertTrue(violations(
                    f"package com.eignex.klause.{owner}\n"
                    "import com.eignex.klause.theory.qflra.SourceBoundAtom\n"
                ))
                self.assertEqual([], violations(
                    f"package com.eignex.klause.{owner}\n"
                    "import com.eignex.klause.solver.search.SourceBoundAtom\n"
                ))

    def test_wildcard_imports_cannot_hide_a_forbidden_symbol(self):
        self.assertTrue(violations("""package com.eignex.klause.theory.qflra
import com.eignex.klause.ir.*
"""))
        self.assertEqual([], violations("""package com.eignex.klause.theory.qflra
import com.eignex.klause.ir.Lit
"""))

    def test_comments_and_literal_text_are_not_dependencies(self):
        self.assertEqual([], violations('''package com.eignex.klause.lp.engine
// com.eignex.klause.propagation.Session
/* nested /* com.eignex.klause.formats.Parser */ comment */
val s = "com.eignex.klause.propagation.Session"
val t = """com.eignex.klause.formats.Parser"""
'''))

    def test_package_prefix_requires_a_segment_boundary(self):
        self.assertTrue(violations("""package com.eignex.klause.lp.engine
import com.eignex.klause.utilPolicy.P
"""))

    def test_platform_main_sources_are_checked_in_every_module(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for module, source_set in (("klause", "jvmMain"), ("klause-cli", "nativeMain")):
                path = root / module / "src" / source_set / "kotlin" / "Bad.kt"
                path.parent.mkdir(parents=True)
                path.write_text("package com.eignex.klause.lp.engine\n"
                                "import com.eignex.klause.ir.Problem\n")
            self.assertEqual(2, len(check(root)))

    def test_missing_source_tree_fails_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            self.assertTrue(check(Path(directory)))


if __name__ == "__main__":
    unittest.main()
