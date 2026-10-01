import unittest
from typing import Any, Dict, List, Set

import scan_client_constructors


class ClientConstructorGuardTest(unittest.TestCase):
    result: Dict[str, Any]

    @classmethod
    def setUpClass(cls) -> None:
        cls.result = scan_client_constructors.scan()

    def test_no_unhandled_classes(self) -> None:
        self.assertEqual(self.result["unhandled"], [])
        self.assertGreater(self.result["checked"], 1000)

    def test_allow_list_has_no_stale_entries(self) -> None:
        self.assertEqual(self.result["stale"], [])

    def test_legacy_requests_are_covered_by_the_table(self) -> None:
        current, supported, unsupported = scan_client_constructors.load_handled_ids()
        for legacyId in (0x25939651, 0x4222fa74, 0x93d7b347, 0x519bc2b1, 0x80eee427, 0x6c50051c, 0x0ef02ce6, 0xffa0a496):
            self.assertNotIn(legacyId, current)
            self.assertIn(legacyId, supported)

    def test_guard_reports_a_class_that_nothing_handles(self) -> None:
        saved: List[Any] = scan_client_constructors.ALLOW_LIST
        scan_client_constructors.ALLOW_LIST = []
        try:
            result: Dict[str, Any] = scan_client_constructors.scan()
        finally:
            scan_client_constructors.ALLOW_LIST = saved
        names: Set[str] = {e["name"] for e in result["unhandled"]}
        self.assertIn("TL_messages_getStatsURL", names)
        self.assertIn("TL_message_secret", names)

    def test_unsupported_legacy_id_is_not_treated_as_handled(self) -> None:
        current, supported, unsupported = scan_client_constructors.load_handled_ids()
        self.assertTrue(unsupported)
        self.assertFalse(set(unsupported) & supported)


if __name__ == "__main__":
    unittest.main()
