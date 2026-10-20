"""Artifact linkage regressions using real javac-produced class files."""

import importlib.util
import subprocess
import tempfile
import unittest
import zipfile
from pathlib import Path


spec = importlib.util.spec_from_file_location("verify_jar", Path(__file__).with_name("verify-jar.py"))
verifier = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verifier)


class ArtifactLinkageTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.workspace = tempfile.TemporaryDirectory()
        cls.root = Path(cls.workspace.name)
        sources = {
            "ru/arc/paper/api/ArcVisualPacketBudget": "public interface ArcVisualPacketBudget {}",
            "ru/arc/paper/api/VisualPacketAdmission": "public enum VisualPacketAdmission { ALLOWED }",
            "ru/arc/paper/packet/PaperVisualPacketRuntime": "public class PaperVisualPacketRuntime {}",
            "ru/arc/paper/packet/PaperVisualPackets": """public class PaperVisualPackets {
                private ru.arc.paper.api.ArcVisualPacketBudget budget;
                public ru.arc.paper.api.VisualPacketAdmission admission;
            }""",
            "ru/arc/paper/display/PaperPacketDisplays": """public class PaperPacketDisplays {
                private ru.arc.paper.packet.PaperVisualPackets transport;
            }""",
            "ru/arc/civs/CivsPreviewRenderer": """public class CivsPreviewRenderer {
                private ru.arc.paper.display.PaperPacketDisplays displays;
            }""",
            "kotlin/collections/CollectionsKt": "public class CollectionsKt {}",
            "net/citizensnpcs/api/npc/NPC": "public interface NPC {}",
            "ru/arc/civs/ForbiddenResident": """public class ForbiddenResident {
                private net.citizensnpcs.api.npc.NPC actor;
            }""",
        }
        files = []
        for name, body in sources.items():
            source = cls.root / (name + ".java")
            source.parent.mkdir(parents=True, exist_ok=True)
            source.write_text("package " + name.rsplit("/", 1)[0].replace("/", ".") + ";\n" + body)
            files.append(str(source))
        output = cls.root / "classes"
        subprocess.run(["javac", "-d", str(output), *files], check=True, capture_output=True, text=True)
        cls.classes = {str(path.relative_to(output)): path.read_bytes() for path in output.rglob("*.class")}

    @classmethod
    def tearDownClass(cls):
        cls.workspace.cleanup()

    def archives(self, missing=(), duplicate_api=False, missing_runtime=False,
                 citizens_reference=False, citizens_dependency=False):
        feature = self.root / "ArcCivs.jar"
        host = self.root / "ArcCore.jar"
        with zipfile.ZipFile(feature, "w") as archive:
            archive.writestr("plugin.yml", "name: ArcCivs\ndepend: [ArcCore, packetevents, WorldEdit"
                             + (", Citizens" if citizens_dependency else "") + "]\n")
            for name, data in self.classes.items():
                if name.startswith("net/citizensnpcs/") or (
                        name == "ru/arc/civs/ForbiddenResident.class" and not citizens_reference):
                    continue
                if not name.startswith("ru/arc/paper/api/") and name != verifier.HOST_VISUAL_RUNTIME:
                    archive.writestr(name, data)
            if duplicate_api:
                api = "ru/arc/paper/api/ArcVisualPacketBudget.class"
                archive.writestr(api, self.classes[api])
        with zipfile.ZipFile(host, "w") as archive:
            archive.writestr("plugin.yml", "name: ArcCore\nversion: '0.1.8'\n")
            for name, data in self.classes.items():
                if name in missing or (missing_runtime and name == verifier.HOST_VISUAL_RUNTIME):
                    continue
                if name.startswith("ru/arc/paper/api/") or name == verifier.HOST_VISUAL_RUNTIME:
                    archive.writestr(name, data)
        return feature, host

    def test_standalone_feature_links_only_to_its_declared_host(self):
        verifier.verify(*self.archives())

    def test_missing_budget_type_reproduces_the_native_startup_linkage_failure(self):
        missing = "ru/arc/paper/api/ArcVisualPacketBudget.class"
        with self.assertRaisesRegex(ValueError, "ArcCore host API linkage is incomplete:.*ArcVisualPacketBudget.*PaperVisualPackets"):
            verifier.verify(*self.archives(missing=(missing,)))

    def test_missing_admission_enum_and_provider_are_not_hidden_by_api_presence(self):
        missing = "ru/arc/paper/api/VisualPacketAdmission.class"
        with self.assertRaisesRegex(ValueError, "host API linkage is incomplete:.*VisualPacketAdmission"):
            verifier.verify(*self.archives(missing=(missing,)))
        with self.assertRaisesRegex(ValueError, "host is missing the shared visual provider"):
            verifier.verify(*self.archives(missing_runtime=True))

    def test_a_private_api_copy_cannot_satisfy_the_host_contract(self):
        with self.assertRaisesRegex(ValueError, "API/provider classes were shaded:.*ArcVisualPacketBudget"):
            verifier.verify(*self.archives(duplicate_api=True))

    def test_citizens_descriptor_and_bytecode_dependencies_are_rejected(self):
        with self.assertRaisesRegex(ValueError, "must not declare a Citizens dependency"):
            verifier.verify(*self.archives(citizens_dependency=True))
        with self.assertRaisesRegex(ValueError, "Citizens linkage remains"):
            verifier.verify(*self.archives(citizens_reference=True))


if __name__ == "__main__":
    unittest.main()
