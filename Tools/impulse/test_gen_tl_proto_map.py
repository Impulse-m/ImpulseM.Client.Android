import gzip
import json
import tempfile
import unittest
from pathlib import Path
from typing import Any, Dict, List, Set, Tuple

import gen_tl_proto_map


EXCLUDED_CONSTRUCTORS: List[str] = ["boolFalse", "boolTrue", "true", "vector"]


class GenTlProtoMapTest(unittest.TestCase):
    schema: Dict[str, Any]
    mapping: Dict[str, Any]
    reportText: str
    gzBytes: bytes
    tmp: tempfile.TemporaryDirectory

    @classmethod
    def setUpClass(cls) -> None:
        cls.schema = json.loads(gen_tl_proto_map.SCHEMA_PATH.read_text(encoding="utf-8"))
        cls.tmp = tempfile.TemporaryDirectory()
        outJson: Path = Path(cls.tmp.name) / "map.json.gz"
        outMd: Path = Path(cls.tmp.name) / "coverage.md"
        gen_tl_proto_map.generate(outJson, outMd)
        cls.gzBytes = outJson.read_bytes()
        cls.mapping = json.loads(gzip.decompress(cls.gzBytes).decode("utf-8"))
        cls.reportText = outMd.read_text(encoding="utf-8")

    @classmethod
    def tearDownClass(cls) -> None:
        cls.tmp.cleanup()

    def constructor(self, predicate: str) -> Dict[str, Any]:
        for c in self.mapping["constructors"]:
            if c["predicate"] == predicate:
                return c
        self.fail("constructor not mapped: " + predicate)

    def method(self, name: str) -> Dict[str, Any]:
        for m in self.mapping["methods"]:
            if m["method"] == name:
                return m
        self.fail("method not mapped: " + name)

    def param(self, entry: Dict[str, Any], name: str) -> Dict[str, Any]:
        for p in entry["params"]:
            if p["name"] == name:
                return p
        self.fail("param not found: " + name)

    def test_constructor_coverage(self) -> None:
        total: int = len(self.schema["constructors"])
        excluded: int = len([c for c in self.schema["constructors"] if c["predicate"] in EXCLUDED_CONSTRUCTORS])
        self.assertEqual(len(self.mapping["constructors"]), total - excluded)
        self.assertIn(len(self.mapping["constructors"]), (1653, 1654))
        mapped: Set[int] = {c["id"] for c in self.mapping["constructors"]}
        for c in self.schema["constructors"]:
            if c["predicate"] not in EXCLUDED_CONSTRUCTORS:
                self.assertIn(int(c["id"]), mapped, c["predicate"])

    def test_method_coverage(self) -> None:
        dotted = [m for m in self.schema["methods"] if "." in m["method"]]
        self.assertEqual(len(self.schema["methods"]) - len(dotted), 11)
        self.assertEqual(len(self.mapping["methods"]), len(dotted))
        mapped: Set[str] = {m["method"] for m in self.mapping["methods"]}
        for m in dotted:
            self.assertIn(m["method"], mapped)

    def test_known_shapes(self) -> None:
        message = self.constructor("message")
        self.assertEqual(message["proto"], "MessageValue")
        self.assertEqual(message["arm"], 2)
        self.assertEqual(self.param(message, "id")["field"], 16)
        self.assertEqual(self.param(message, "peer_id")["field"], 20)
        out = self.param(message, "out")
        self.assertEqual(out["kind"], "true")
        self.assertEqual(out["flag"], ["flags", 1])
        self.assertEqual(out["field"], 1)

        code = self.constructor("codeSettings")
        self.assertEqual(code["proto"], "CodeSettings")
        self.assertIsNone(code["arm"])
        token = self.param(code, "token")
        self.assertEqual(token["flag"], ["flags", 8])
        self.assertEqual(token["field"], 8)
        sandbox = self.param(code, "app_sandbox")
        self.assertEqual(sandbox["kind"], "Bool")
        self.assertEqual(sandbox["flag"], ["flags", 8])
        self.assertEqual(sandbox["field"], 9)
        logout = self.param(code, "logout_tokens")
        self.assertEqual(logout["kind"], "vector")
        self.assertEqual(logout["elem"]["kind"], "bytes")
        self.assertEqual(logout["field"], 7)

        getUsers = self.method("users.getUsers")
        self.assertEqual(getUsers["path"], "/v1.users.Users/GetUsers")
        self.assertEqual(getUsers["result"]["kind"], "list")
        self.assertEqual(getUsers["result"]["elem"], {"kind": "object", "type": "User"})

        self.assertEqual(self.method("account.updateStatus")["result"], {"kind": "bool"})
        self.assertEqual(self.method("account.getContactSignUpNotification")["result"], {"kind": "bool_overlay"})

        auth = self.constructor("auth.authorization")
        captures = {c["name"]: c["field"] for c in auth["capture"]}
        self.assertEqual(captures, {"session_token": 6, "refresh_token": 7})

    def test_phone_call_captures(self) -> None:
        call = self.constructor("phone.phoneCall")
        captures: Dict[str, int] = {c["name"]: c["field"] for c in call["capture"]}
        self.assertEqual(captures, {"livekit_url": 3, "livekit_token": 4})

    def test_bot_callback_answer_derived(self) -> None:
        answer = self.constructor("messages.botCallbackAnswer")
        hasUrl = self.param(answer, "has_url")
        self.assertEqual(hasUrl["derived_from"], "url")
        self.assertNotIn("field", hasUrl)

    def test_guard_rejects_unlisted_extra_field(self) -> None:
        message: Dict[str, Any] = {
            "fields": {"id": (1, "int32", ""), "sneaky": (2, "int32", "")},
            "oneof": {},
            "file": "x.proto",
            "name": "Fake",
        }
        tlParams: List[Dict[str, str]] = [{"name": "id", "type": "int"}]
        with self.assertRaises(ValueError):
            gen_tl_proto_map.map_params(tlParams, message, "test", [], False, set())
        # Listed in overlays: tolerated on a request, but not on a response unless it is a capture field.
        listed: Set[Any] = {("x.proto", "Fake", "sneaky")}
        ignored: List[str] = []
        gen_tl_proto_map.map_params(tlParams, message, "test", ignored, False, listed)
        self.assertEqual(len(ignored), 1)
        with self.assertRaises(ValueError):
            gen_tl_proto_map.map_params(tlParams, message, "test", [], True, listed)

    def test_derived_params_have_no_field(self) -> None:
        for entry in self.mapping["constructors"] + self.mapping["methods"]:
            for p in entry["params"]:
                if "derived_from" in p:
                    self.assertNotIn("field", p)
                elif p["kind"] != "flags":
                    self.assertIn("field", p)
        self.assertIn("derived_from", self.reportText)

    def legacyEntry(self, kind: str, legacyId: int) -> Dict[str, Any]:
        signed: int = legacyId - (1 << 32) if legacyId >= (1 << 31) else legacyId
        for e in self.mapping["legacy"][kind]:
            if e["id"] == signed:
                return e
        self.fail("legacy %s not found: %x" % (kind, legacyId))

    def test_legacy_critical_methods(self) -> None:
        expected: Dict[int, Tuple[str, int]] = {
            0x25939651: ("updates.getDifference", 0x19c2f763),
            0x4222fa74: ("messages.getMessages", 0x63c66506),
            0x93d7b347: ("channels.getMessages", 0xad8c9a23),
            0x519bc2b1: ("messages.uploadMedia", 0x14967978),
            0x80eee427: ("auth.signUp", 0xaac7b717),
        }
        for legacyId, (name, targetId) in expected.items():
            entry: Dict[str, Any] = self.legacyEntry("methods", legacyId)
            self.assertEqual(entry["name"], name)
            self.assertEqual(entry["target_id"] & 0xffffffff, targetId, name)
            self.assertEqual(entry["class"], "upgrade", name)
            self.assertTrue(entry["supported"], name)
            self.assertEqual(self.method(name)["id"], entry["target_id"])

    def test_legacy_name_mismatch_resolved_by_id(self) -> None:
        self.assertEqual(self.legacyEntry("methods", 0x418d4e0b)["name"], "account.deleteAccount")
        self.assertEqual(self.legacyEntry("methods", 0x8d9d742b)["name"], "account.getTheme")

    def test_legacy_alias_classification(self) -> None:
        self.assertEqual(self.legacyEntry("methods", 0x6c50051c)["class"], "alias")
        self.assertEqual(self.legacyEntry("methods", 0x24b524c5)["class"], "alias")

    def test_legacy_nested_constructors(self) -> None:
        video: Dict[str, Any] = self.legacyEntry("constructors", 0x0ef02ce6)
        self.assertEqual(video["name"], "documentAttributeVideo")
        self.assertEqual(video["target_id"] & 0xffffffff, 0x43c57c48)
        self.assertEqual(video["class"], "upgrade")
        self.assertEqual([p["name"] for p in video["params"] if p["name"] == "duration"], ["duration"])
        self.assertEqual(self.legacyEntry("constructors", 0xffa0a496)["target_id"] & 0xffffffff, 0x32da9e9c)

    def test_legacy_never_shadows_current_ids(self) -> None:
        current: Set[int] = {c["id"] for c in self.mapping["constructors"]} | {m["id"] for m in self.mapping["methods"]}
        for kind in ("constructors", "methods"):
            for e in self.mapping["legacy"][kind]:
                self.assertNotIn(e["id"], current)
                self.assertIn(e["target_id"], current)
        ids: List[int] = [e["id"] for e in self.mapping["legacy"]["constructors"]]
        self.assertEqual(ids, sorted(ids))

    def test_legacy_theme_settings_object_becomes_vector(self) -> None:
        for legacyId in (0x8432c21f, 0x5cb367d5):
            entry: Dict[str, Any] = self.legacyEntry("methods", legacyId)
            self.assertTrue(entry["supported"], entry["name"])

    def test_legacy_classification_rules(self) -> None:
        alias, problem = gen_tl_proto_map.classify_params(
            [{"kind": "int", "name": "a"}],
            [{"kind": "int", "name": "a"}],
            set(),
        )
        self.assertEqual((alias, problem), ("alias", None))
        cls, problem = gen_tl_proto_map.classify_params(
            [{"kind": "string", "name": "a"}],
            [{"kind": "int", "name": "a"}],
            set(),
        )
        self.assertEqual(cls, "upgrade")
        self.assertIn("string -> int", problem)
        cls, problem = gen_tl_proto_map.classify_params(
            [],
            [{"kind": "object", "name": "x", "type": "Foo"}],
            set(),
        )
        self.assertIn("zero-param", problem)
        cls, problem = gen_tl_proto_map.classify_params(
            [{"kind": "vector", "name": "gone", "elem": {"kind": "object", "type": "Vanished", "name": ""}}],
            [],
            set(),
            {"Foo"},
        )
        self.assertIn("Vanished no longer exists", problem)

    def test_deterministic(self) -> None:
        second: Path = Path(self.tmp.name) / "map2.json.gz"
        gen_tl_proto_map.generate(second, Path(self.tmp.name) / "coverage2.md")
        self.assertEqual(second.read_bytes(), self.gzBytes)

    def test_coverage_report_written(self) -> None:
        self.assertIn(str(len(self.mapping["constructors"])), self.reportText)
        self.assertIn(str(len(self.mapping["methods"])), self.reportText)
        for name in EXCLUDED_CONSTRUCTORS:
            self.assertIn(name, self.reportText)
        for m in self.schema["methods"]:
            if "." not in m["method"]:
                self.assertIn(m["method"], self.reportText)


if __name__ == "__main__":
    unittest.main()
