import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from service import InputError, Service, mentioned_place


class FakeAmap:
    def convert_gps(self, lon, lat):
        return lon + 0.01, lat + 0.01

    def reverse(self, lon, lat):
        return "上海市黄浦区测试地址"

    def search(self, query, region=""):
        return [{"poiId": "poi-1", "name": query, "address": "测试路 1 号", "city": "上海市",
                 "district": "黄浦区", "longitude": 121.4, "latitude": 31.2, "coordinateSystem": "GCJ-02"}]


class ServiceTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.service = Service(str(Path(self.temp.name) / "test.db"), FakeAmap())

    def tearDown(self):
        self.temp.cleanup()

    def test_gps_is_bound_from_recording_sample(self):
        record = self.service.create_recording({"transcript": "我们在旧电影院见面", "placeMention": "旧电影院",
            "gps": {"longitude": 121.47, "latitude": 31.23, "sampledAt": "2026-09-26T08:42:00+08:00"}})
        self.assertEqual(record["placeBinding"]["source"], "device_gps")
        self.assertEqual(record["placeBinding"]["longitude"], 121.47)
        self.assertEqual(record["candidates"], [])

    def test_search_requires_confirmation_and_rejects_forged_candidate(self):
        record = self.service.create_recording({"transcript": "那是在旧电影院", "placeMention": "旧电影院"})
        self.assertIsNone(record["placeBinding"])
        candidate_id = record["candidates"][0]["candidateId"]
        with self.assertRaises(InputError):
            self.service.confirm_place(record["id"], "not-real")
        bound = self.service.confirm_place(record["id"], candidate_id)
        self.assertEqual(bound["placeBinding"]["source"], "user_confirmed_amap")

    def test_no_mention_does_not_bind_gps(self):
        record = self.service.create_recording({"transcript": "今天天气很好", "gps": {
            "longitude": 121.47, "latitude": 31.23, "sampledAt": "2026-09-26T08:42:00+08:00"}})
        self.assertIsNone(record["placeBinding"])

    def test_updated_spoken_place_is_used_for_existing_recording(self):
        record = self.service.create_recording({"transcript": "那是在旧电影院", "placeMention": "旧电影院"})
        searched = self.service.search_places(record["id"], "上海人民广场")
        self.assertEqual(searched["placeMention"], "上海人民广场")
        bound = self.service.bind_gps(record["id"], {
            "longitude": 121.47, "latitude": 31.23, "sampledAt": "2026-09-26T08:42:00+08:00"}, "上海外滩")
        self.assertEqual(bound["placeMention"], "上海外滩")
        self.assertEqual(bound["placeBinding"]["name"], "上海外滩")

    def test_conversation_needs_consent_and_elder_turn(self):
        with self.assertRaises(InputError):
            self.service.create_conversation({"consent": False})
        session = self.service.create_conversation({"consent": True})
        with self.assertRaises(InputError):
            self.service.next_prompt(session["id"])
        self.service.add_turn(session["id"], {"speaker": "elder", "text": "那天去看电影"})
        first = self.service.next_prompt(session["id"])
        second = self.service.next_prompt(session["id"])
        self.assertNotEqual(first["question"], second["question"])
        self.assertEqual(first["source"], "demo_rules")

    def test_delete_recording_removes_candidates_and_linked_conversation(self):
        record = self.service.create_recording({"transcript": "那是在旧电影院", "placeMention": "旧电影院"})
        session = self.service.create_conversation({"consent": True, "recordingId": record["id"]})
        self.assertTrue(self.service.delete_recording(record["id"])["deleted"])
        with self.assertRaises(KeyError):
            self.service.recording(record["id"])
        with self.assertRaises(KeyError):
            self.service.next_prompt(session["id"])


if __name__ == "__main__":
    unittest.main()
