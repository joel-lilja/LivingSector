"""Build/install gate tests. Game behavior stays in the Java integration suites."""
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import build


class BuildWorkflowTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.patch_root = patch.object(build, "ROOT", self.root)
        self.patch_root.start()
        self.addCleanup(self.patch_root.stop)
        self.installed = self.root / "jars" / "LivingSector.jar"
        self.installed.parent.mkdir()
        self.installed.write_bytes(b"previously installed version")
        self.candidate = self.root / "build" / "candidate" / "LivingSector.jar"
        self.candidate.parent.mkdir(parents=True)
        self.candidate.write_bytes(b"new validated version")

    def invoke(self, command):
        with patch.object(sys, "argv", ["build.py", command]), patch.object(build, "test"), patch.object(build.shutil, "which", return_value="available"):
            build.main()

    def test_failed_checks_preserve_installed_jar_and_existing_package(self):
        package = self.root / "dist" / "LivingSector-0.2.0.zip"
        package.parent.mkdir()
        package.write_bytes(b"previous package")
        for command in ("integration", "build", "package"):
            with self.subTest(command=command), patch.object(build, "integration", side_effect=ValueError("scenario failed")):
                with self.assertRaisesRegex(ValueError, "scenario failed"):
                    self.invoke(command)
                self.assertEqual(self.installed.read_bytes(), b"previously installed version")
                self.assertEqual(package.read_bytes(), b"previous package")

    def test_integration_command_never_installs_or_packages(self):
        with patch.object(build, "integration", return_value=self.candidate), patch.object(build, "install") as install, patch.object(build, "package") as package:
            self.invoke("integration")
            install.assert_not_called()
            package.assert_not_called()

    def test_locked_install_preserves_last_working_jar_and_candidate(self):
        with patch.object(Path, "replace", side_effect=PermissionError("file locked")):
            with self.assertRaisesRegex(ValueError, "Fully quit Starsector"):
                build.install(self.candidate)
        self.assertEqual(self.installed.read_bytes(), b"previously installed version")
        self.assertEqual(self.candidate.read_bytes(), b"new validated version")
        self.assertFalse(self.installed.with_suffix(".tmp").exists())

    def test_package_uses_candidate_and_does_not_install(self):
        for name in ("README.md", "LICENSE", "build.py"):
            (self.root / name).write_text("fixture", encoding="utf-8")
        (self.root / "mod_info.json").write_text('{"version":"test"}', encoding="utf-8")
        with patch.object(build, "integration", return_value=self.candidate):
            self.invoke("package")
        with zipfile.ZipFile(self.root / "dist" / "LivingSector-test.zip") as package:
            self.assertEqual(package.read("LivingSector/jars/LivingSector.jar"), self.candidate.read_bytes())
            self.assertFalse(any("build/candidate" in name for name in package.namelist()))
        self.assertEqual(self.installed.read_bytes(), b"previously installed version")


if __name__ == "__main__":
    unittest.main()
