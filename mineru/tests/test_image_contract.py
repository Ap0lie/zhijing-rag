import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
DOCKERFILE = (ROOT / "mineru" / "Dockerfile").read_text(encoding="utf-8")
COMPOSE = (ROOT / "compose.yaml").read_text(encoding="utf-8")


class MineruImageContractTests(unittest.TestCase):
    def test_image_and_model_inputs_are_immutable(self) -> None:
        self.assertRegex(DOCKERFILE, r"(?m)^FROM .+:[^\s]+@sha256:[0-9a-f]{64}$")
        for name in (
            "MINERU_RELEASE_COMMIT",
            "MINERU_WHEEL_SHA256",
            "MINERU_MODEL_REVISION",
            "MINERU_MODEL_MANIFEST_SHA256",
        ):
            value = re.search(rf"^ARG {name}=([0-9a-f]+)$", DOCKERFILE, re.MULTILINE)
            self.assertIsNotNone(value, name)
            self.assertIn(len(value.group(1)), (40, 64))

    def test_runtime_is_non_root_read_only_and_single_concurrency(self) -> None:
        self.assertIn("USER 10001:10001", DOCKERFILE)
        self.assertIn("MINERU_API_MAX_CONCURRENT_REQUESTS=1", DOCKERFILE)
        self.assertIn('ENTRYPOINT ["mineru-api"]', DOCKERFILE)
        self.assertRegex(COMPOSE, r"(?ms)^  mineru:\n.*?profiles: \[\"mineru\"\].*?user: \"10001:10001\".*?read_only: true")
        self.assertRegex(COMPOSE, r"(?ms)^  mineru:\n.*?cap_drop:\n\s+- ALL")

    def test_parser_worker_uses_the_same_pinned_model_contract(self) -> None:
        docker_revision = re.search(
            r"^ARG MINERU_MODEL_REVISION=([0-9a-f]{40})$", DOCKERFILE, re.MULTILINE
        ).group(1)
        docker_manifest = re.search(
            r"^ARG MINERU_MODEL_MANIFEST_SHA256=([0-9a-f]{64})$", DOCKERFILE, re.MULTILINE
        ).group(1)
        self.assertIn(f"MINERU_MODEL_REVISION: {docker_revision}", COMPOSE)
        self.assertIn(f"MINERU_MODEL_MANIFEST_CHECKSUM: {docker_manifest}", COMPOSE)


if __name__ == "__main__":
    unittest.main()
