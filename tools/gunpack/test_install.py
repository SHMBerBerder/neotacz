"""Installer ordering and default-resource isolation; uses a stub, never Gradle/MC."""

from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]


class IndependentInstallerTest(unittest.TestCase):
    def setUp(self):
        temporary_root = ROOT / "build/installer-tests"
        temporary_root.mkdir(parents=True, exist_ok=True)
        self.workspace = tempfile.TemporaryDirectory(prefix="mesh-install-", dir=temporary_root)
        self.root = Path(self.workspace.name)
        tools = self.root / "tools/gunpack"
        tools.mkdir(parents=True)
        shutil.copy2(HERE / "install.sh", tools / "install.sh")
        legacy = self.root / "tools/blender/neotacz_bolt_action_8k"
        legacy.mkdir(parents=True)
        shutil.copy2(ROOT / "tools/blender/neotacz_bolt_action_8k/install-calibration.sh", legacy / "install-calibration.sh")
        self.target = self.root / "game/tacz"
        self.default = self.target / "tacz_default_gun"
        self.default.mkdir(parents=True)
        (self.default / "user-owned.txt").write_text("must not change")
        self.source = self.root / "pack.zip"
        self.source.write_bytes(b"stub-validation-fixture")
        self.stub(0)

    def tearDown(self):
        self.workspace.cleanup()

    def stub(self, status):
        script = self.root / "gradlew"
        script.write_text(f'#!/bin/sh\nprintf "%s\\n" "$@" > validator-args\nexit {status}\n')
        script.chmod(0o755)

    def install(self, *arguments, legacy=False):
        script = "tools/blender/neotacz_bolt_action_8k/install-calibration.sh" if legacy else "tools/gunpack/install.sh"
        return subprocess.run(["sh", str(self.root / script), *map(str, arguments)], cwd=self.root,
                              text=True, capture_output=True, check=False)

    def snapshot(self):
        return {str(path.relative_to(self.target)): path.read_bytes() for path in self.target.rglob("*") if path.is_file()}

    def test_install_writes_only_own_zip_and_checks_target_dependencies(self):
        before = self.snapshot()
        result = self.install(self.source, self.target)
        self.assertEqual(result.returncode, 0, result.stderr)
        after = self.snapshot()
        installed = "bolt_action_mesh-1.0.0-local.zip"
        self.assertEqual(after.pop(installed), self.source.read_bytes())
        self.assertEqual(after, before)
        arguments = (self.root / "validator-args").read_text()
        self.assertIn("validateMeshGunpack", arguments)
        self.assertIn(f"-PmeshDefaultPack={self.default}", arguments)

    def test_failed_validation_leaves_target_unchanged(self):
        self.stub(77)
        before = self.snapshot()
        result = self.install(self.source, self.target)
        self.assertEqual(result.returncode, 77)
        self.assertEqual(self.snapshot(), before)

    def test_missing_arguments_fail_without_build(self):
        result = self.install()
        self.assertEqual(result.returncode, 2)
        self.assertFalse((self.root / "validator-args").exists())

    def test_missing_input_or_default_dependency_directory_fails_without_build(self):
        self.source.unlink()
        self.assertNotEqual(self.install(self.source, self.target).returncode, 0)
        self.assertFalse((self.root / "validator-args").exists())
        self.source.write_bytes(b"fixture")
        shutil.rmtree(self.default)
        self.assertNotEqual(self.install(self.source, self.target).returncode, 0)
        self.assertFalse((self.root / "validator-args").exists())

    def test_legacy_entry_has_identical_zip_only_behavior(self):
        before = self.snapshot()
        self.assertEqual(self.install().returncode, 2)
        self.assertEqual(self.snapshot(), before)
        result = self.install(self.source, self.target, legacy=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual((self.default / "user-owned.txt").read_text(), "must not change")

    def test_already_installed_zip_can_be_revalidated_without_corruption(self):
        self.assertEqual(self.install(self.source, self.target).returncode, 0)
        installed = self.target / "bolt_action_mesh-1.0.0-local.zip"
        before = self.snapshot()
        self.assertEqual(self.install(installed, self.target).returncode, 0)
        self.assertEqual(self.snapshot(), before)

    def test_source_change_after_copy_cannot_replace_validated_bytes(self):
        before = self.source.read_bytes()
        script = self.root / "gradlew"
        script.write_text('#!/bin/sh\nprintf "changed" > pack.zip\nexit 0\n')
        script.chmod(0o755)
        result = self.install(self.source, self.target)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertNotEqual(self.source.read_bytes(), before)
        self.assertEqual((self.target / "bolt_action_mesh-1.0.0-local.zip").read_bytes(), before)


if __name__ == "__main__":
    unittest.main()
