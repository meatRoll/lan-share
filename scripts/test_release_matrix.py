import unittest
from release_matrix import matrix

BASE = """lanshare.phone.versionName=0.5.6
lanshare.phone.versionCode=47
lanshare.tvLegacy.versionName=0.5.8
lanshare.tvLegacy.versionCode=49
lanshare.tvModern.versionName=0.5.11
lanshare.tvModern.versionCode=52
"""

class MatrixTests(unittest.TestCase):
    def test_unchanged(self):
        self.assertEqual(matrix(BASE, BASE), {"include": []})

    def test_one_platform(self):
        updated = BASE.replace("0.5.11", "0.5.12").replace("versionCode=52", "versionCode=53")
        self.assertEqual([x["platform"] for x in matrix(BASE, updated)["include"]], ["tvModern"])

    def test_first_push(self):
        self.assertEqual(len(matrix("", BASE)["include"]), 3)

    def test_reject_nonincreasing_code(self):
        with self.assertRaises(ValueError):
            matrix(BASE, BASE.replace("0.5.11", "0.5.12"))

    def test_reject_code_only(self):
        with self.assertRaises(ValueError):
            matrix(BASE, BASE.replace("versionCode=52", "versionCode=53"))

    def test_feature_prerelease(self):
        updated = BASE.replace("0.5.11", "0.5.11_feat.1").replace("versionCode=52", "versionCode=53")
        self.assertEqual(matrix(BASE, updated)["include"][0]["version"], "0.5.11_feat.1")

    def test_accept_fix_and_feature_test_versions(self):
        for suffix in ("_fix.1", "_feat.2"):
            updated = BASE.replace("0.5.11", "0.5.11" + suffix).replace("versionCode=52", "versionCode=53")
            self.assertEqual(matrix(BASE, updated)["include"][0]["version"], "0.5.11" + suffix)

    def test_reject_invalid_suffix(self):
        for suffix in ("_fix.0", "_feat.x", "_debug.1", "-test"):
            with self.assertRaises(ValueError):
                matrix(BASE, BASE.replace("0.5.11", "0.5.12" + suffix).replace("versionCode=52", "versionCode=53"))

if __name__ == '__main__':
    unittest.main()
