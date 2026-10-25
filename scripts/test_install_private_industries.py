import importlib.util
import io
import json
from pathlib import Path
import tarfile
import tempfile
import unittest


spec = importlib.util.spec_from_file_location("industry_installer", Path(__file__).with_name("install-private-industries.py"))
installer = importlib.util.module_from_spec(spec)
spec.loader.exec_module(installer)


class IndustryInstallerTest(unittest.TestCase):
    def pack(self, base, extra=None):
        base = base.resolve()
        root = base / "schematics"
        root.mkdir()
        (root / "old.schem").write_bytes(b"preserved")
        archive = base / "pack.tar.gz"
        files = []
        with tarfile.open(archive, "w:gz") as output:
            for name in sorted(installer.PATHS):
                data = name.encode()
                member = tarfile.TarInfo(name)
                member.size = len(data)
                output.addfile(member, io.BytesIO(data))
                files.append({"path": name, "bytes": len(data), "sha256": installer.digest(data)})
            if extra is not None:
                member = tarfile.TarInfo(extra)
                member.size = 1
                output.addfile(member, io.BytesIO(b"x"))
        manifest = base / "manifest.json"
        manifest.write_text(json.dumps({"runtimeRoot": str(root), "files": files,
                                       "archiveSha256": installer.digest(archive.read_bytes())}))
        return root, archive, manifest, installer.digest(manifest.read_bytes())

    def test_check_is_read_only_and_apply_is_idempotent(self):
        with tempfile.TemporaryDirectory() as directory:
            root, archive, manifest, sha = self.pack(Path(directory))
            self.assertEqual(installer.install(archive, manifest, sha, root)["newFiles"], 27)
            self.assertEqual(len(list(root.rglob("*.schem"))), 1)
            self.assertEqual(installer.install(archive, manifest, sha, root, True)["newFiles"], 27)
            self.assertEqual(installer.install(archive, manifest, sha, root, True)["newFiles"], 0)
            self.assertEqual((root / "old.schem").read_bytes(), b"preserved")

    def test_all_conflicts_are_checked_before_writing(self):
        with tempfile.TemporaryDirectory() as directory:
            root, archive, manifest, sha = self.pack(Path(directory))
            target = root / sorted(installer.PATHS)[-1]
            target.parent.mkdir(parents=True)
            target.write_bytes(b"different")
            with self.assertRaisesRegex(ValueError, "Existing file conflict"):
                installer.install(archive, manifest, sha, root, True)
            self.assertEqual(len(list(root.rglob("*.schem"))), 2)
            self.assertEqual(target.read_bytes(), b"different")

    def test_extra_traversal_member_is_rejected_without_writing(self):
        with tempfile.TemporaryDirectory() as directory:
            root, archive, manifest, sha = self.pack(Path(directory), "../outside.schem")
            with self.assertRaisesRegex(ValueError, "exact 27"):
                installer.install(archive, manifest, sha, root, True)
            self.assertEqual(len(list(root.rglob("*.schem"))), 1)

    def test_symlink_parent_is_rejected_without_writing(self):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory).resolve()
            root, archive, manifest, sha = self.pack(base)
            outside = base / "outside"
            outside.mkdir()
            (root / "civs").symlink_to(outside, target_is_directory=True)
            with self.assertRaisesRegex(ValueError, "Symlink"):
                installer.install(archive, manifest, sha, root, True)
            self.assertEqual(list(outside.iterdir()), [])
