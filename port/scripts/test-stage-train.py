import importlib.util
import tempfile
import unittest
from pathlib import Path
from unittest import mock

SCRIPT = Path(__file__).with_name("stage-train.py")
SPEC = importlib.util.spec_from_file_location("stage_train", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class StageTrainTest(unittest.TestCase):
    def test_ready_discovers_only_clients(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "READY.txt").write_text(
                "alpha server x/singleplayer-server.wasm hash\n"
                "alpha client x/classes.wasm hash\n"
                "beta client y/classes.wasm hash\n", encoding="utf-8")
            self.assertEqual(MODULE.profiles_from_ready(root), ["alpha", "beta"])

    def test_prepare_input_uses_current_launcher(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            frozen = root / "frozen"
            output = root / "input"
            frozen.mkdir()
            (frozen / "classes.js").write_text("// fixture", encoding="utf-8")
            (frozen / "vanilla-assets.pack.gz").write_bytes(b"fixture")
            (frozen / "classes.js.build.json").write_bytes(b'{"fixture":true}\n')
            output.mkdir()
            (output / "stale").write_bytes(b"preserve")
            with mock.patch.object(MODULE, "run") as run:
                MODULE.prepare_input("fixture", frozen, frozen, output)
            self.assertEqual((output / "classes.js").read_bytes(), b"// fixture")
            self.assertEqual((output / "classes.js.build.json").read_bytes(), b'{"fixture":true}\n')
            self.assertEqual((output / "vanilla-assets.pack.gz").read_bytes(), b"fixture")
            self.assertEqual((output / "index.html").read_bytes(),
                             (MODULE.ROOT / "port/web/launcher/index.template.html").read_bytes())
            self.assertFalse((output / "stale").exists())
            self.assertEqual((root / "input.previous-1/stale").read_bytes(), b"preserve")
            self.assertEqual(run.call_args.args[0][-1], "fixture")

    def test_stale_server_requires_ready_gate(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for role, file in (("client", "classes.wasm"), ("server", "singleplayer-server.wasm")):
                artifact = root / "dist" / f"wasm-fixture-{role}" / file
                artifact.parent.mkdir(parents=True)
                artifact.write_bytes(b"fixture")
            (root / "READY.txt").write_text("fixture client x/classes.wasm hash\n", encoding="utf-8")
            with self.assertRaisesRegex(RuntimeError, "no READY.txt server gate"):
                MODULE.wasm_paths(root, "fixture")

    def test_wasm_only_site_and_portable_use_acceptance_shell(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            client, server = root / "client", root / "server"
            staged = root / "staged"
            with mock.patch.object(MODULE, "wasm_paths", return_value=(client, server)), \
                    mock.patch.object(MODULE, "prepare_input", return_value=root / "input"), \
                    mock.patch.object(MODULE, "run") as run:
                MODULE.stage_profile(root, "fixture", None, staged, root / "scratch")
            commands = [call.args[0] for call in run.call_args_list]
            self.assertEqual(len(commands), 3)
            self.assertTrue(all("--no-js-fallback" in command for command in commands))
            self.assertEqual(commands[-1][-2:], ["--site-shell", str(staged / "acceptance-fixture")])


if __name__ == "__main__":
    unittest.main()
