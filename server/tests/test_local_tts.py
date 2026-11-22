from pathlib import Path
import tempfile
import unittest

from fastapi.testclient import TestClient

from server.app import create_app
from server.config import Settings
from server.tests.fixtures import install_test_voice


class LocalTtsManifestTests(unittest.TestCase):
    def test_manifest_publishes_only_existing_models_and_voice_codes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            settings = Settings(state=root / "state", audio=root / "audio", min_free_bytes=0,
                                zonos_root=root / "zonos")
            settings.prepare()
            settings.zonos_model_path.mkdir(parents=True)
            (settings.zonos_model_path / "backbone-ptq.gguf").write_bytes(b"backbone")
            (settings.zonos_model_path / "zac-keep2.gguf").write_bytes(b"decoder")
            install_test_voice(settings)
            client = TestClient(create_app(settings), headers={"X-Service-Token": settings.token_file.read_text()})

            response = client.get("/api/v1/local-tts/manifest")
            self.assertEqual(200, response.status_code)
            manifest = response.json()
            self.assertEqual(["arm64-v8a"], manifest["abi"])
            names = {item["name"] for item in manifest["files"]}
            self.assertIn("backbone-ptq.gguf", names)
            self.assertIn("zac-keep2.gguf", names)
            self.assertEqual(2, len([name for name in names if name.startswith("voice:")]))
            for item in manifest["files"]:
                self.assertEqual(item["bytes"], len(client.get("/api/v1/local-tts/files/" + item["path"]).content))

            self.assertEqual(404, client.get("/api/v1/local-tts/files/../state/service.sqlite").status_code)


if __name__ == "__main__":
    unittest.main()
