"""Ensure public networking test fixtures do not exempt deployment addresses."""
import importlib.util
import pathlib
import unittest

spec = importlib.util.spec_from_file_location('scanner', pathlib.Path(__file__).with_name('check-public-content.py'))
scanner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(scanner)


class PublicContentTest(unittest.TestCase):
    def test_rfc_fixture_exception_is_limited_to_exact_files_and_values(self):
        known = '.'.join(map(str, [10, 0, 0, 1]))
        other = '.'.join(map(str, [10, 23, 45, 67]))
        for path in scanner.RFC1918_FIXTURE_FILES:
            self.assertEqual(scanner.findings(path, known), [])
            self.assertTrue(scanner.findings(path, other))
        self.assertTrue(scanner.findings('docs/SETUP.md', known))


if __name__ == '__main__':
    unittest.main()
