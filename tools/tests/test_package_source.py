import shutil
import subprocess
import tarfile
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).resolve().parents[1] / "package-source.py"


class SourcePackageTest(unittest.TestCase):
    def test_archive_contains_source_and_weights_without_published_artifacts(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            files = {
                "app/build.gradle.kts": 'android { defaultConfig { versionName = "0.1.1" } }',
                "app/src/main/Main.kt": "fun main() = Unit\n",
                "engine/src/main/assets/pikafish.nnue": "matching-test-weights",
                "dist/build-manifest.json": '{"version": "0.1.1"}',
                "dist/previous.apk": "old-binary",
                "dist/xiangqi-assistant-0.1.0-source.tar.gz": "previous-source-archive",
                "dist/SHA256SUMS.txt": "old-checksum",
            }
            for name, content in files.items():
                path = root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(content)
            (root / "tools").mkdir()
            shutil.copyfile(SCRIPT, root / "tools/package-source.py")
            subprocess.run(["git", "init", "-q", str(root)], check=True)
            subprocess.run(["git", "-C", str(root), "add", "app", "tools", "dist"], check=True)
            completed = subprocess.run(
                ["python3", str(root / "tools/package-source.py")],
                check=True, capture_output=True, text=True,
            )
            target = Path(completed.stdout.splitlines()[0])
            with tarfile.open(target) as archive:
                relative = {name.split("/", 1)[1] for name in archive.getnames()}
                self.assertEqual({
                    "app/build.gradle.kts", "app/src/main/Main.kt", "tools/package-source.py",
                    "engine/src/main/assets/pikafish.nnue", "dist/build-manifest.json",
                }, relative)
                self.assertEqual(
                    b"matching-test-weights",
                    archive.extractfile("JJ-chess-0.1.1/engine/src/main/assets/pikafish.nnue").read(),
                )
            self.assertEqual("xiangqi-assistant-0.1.1-source.tar.gz", target.name)


if __name__ == "__main__":
    unittest.main()
