import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from service import InputError, Service, mentioned_place


class FakeAmap:
    def static_map(self, points):
        self.last_points = points
        return b"\x89PNG\r\n\x1a\nexample"
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

    def test_demo_sample_evidence_is_clearly_labeled(self):
        record = self.service.create_recording({"transcript": "我们在旧电影院见面", "placeMention": "旧电影院",
            "gps": {"longitude": 121.47, "latitude": 31.23, "sampledAt": "2026-09-26T08:42:00+08:00", "source": "demo_sample"}})
        self.assertEqual(record["placeBinding"]["source"], "demo_gps")
        self.assertIn("示例录音", record["placeBinding"]["evidence"])

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

    def test_seed_memories_have_multiple_recordings_and_map(self):
        memories = self.service.memories()
        self.assertEqual(len(memories), 3)
        first = self.service.memory("a" * 32)
        self.assertEqual(len(first["recordings"]), 2)
        self.assertEqual(len(self.service.recordings()), 4)
        self.assertNotEqual(first["recordings"][0]["id"], first["recordings"][1]["id"])
        self.assertTrue(self.service.memory_map(first["id"]).startswith(b"\x89PNG"))
        self.assertEqual(len(self.service.memories()), 3)

    def test_conversation_history_includes_assistant_prompt(self):
        session = self.service.create_conversation({"consent": True})
        self.service.add_turn(session["id"], {"speaker": "elder", "text": "看电影"})
        question = self.service.next_prompt(session["id"])["question"]
        turns = self.service.conversation(session["id"])["turns"]
        self.assertEqual([t["speaker"] for t in turns], ["assistant", "elder", "assistant"])
        self.assertEqual(turns[-1]["text"], question)

    def test_confirmed_recording_place_updates_linked_memory(self):
        recording_id = "1" * 32
        record = self.service.search_places(recording_id, "上海人民广场")
        self.service.confirm_place(recording_id, record["candidates"][0]["candidateId"])
        place = self.service.memory("a" * 32)["place"]
        self.assertEqual(place["name"], "上海人民广场")
        self.assertEqual(place["mapLongitude"], 121.4)

    def test_edit_and_delete_memory_keep_recordings_and_tombstone_seed(self):
        mid = "a" * 32
        edited = self.service.update_memory(mid, {"title": "新的标题", "story": "修改后的故事"})
        self.assertEqual(edited["title"], "新的标题")
        self.assertEqual(edited["story"], "修改后的故事")
        self.assertTrue(self.service.delete_memory(mid)["recordingsRetained"])
        self.assertEqual(len(self.service.recordings()), 4)
        again = Service(self.service.database, FakeAmap())
        with self.assertRaises(KeyError):
            again.memory(mid)


if __name__ == "__main__":
    unittest.main()
