"""XunYi demo domain rules. No ASR or LLM is represented as live output."""
import json
import re
import sqlite3
import time
import uuid
from contextlib import contextmanager
from datetime import datetime, timezone
from pathlib import Path

from amap import AmapError


SDK_NOTE = "等待官方 SDK 下发后进行补充"


class InputError(ValueError):
    pass


def now():
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def mentioned_place(transcript: str, explicit: str = ""):
    """Conservative demo extractor; SDK will replace this rule."""
    if explicit.strip():
        return explicit.strip()[:80], "explicit"
    patterns = [r"(?:在|到了|去过|经过)([^，。！？\s]{2,16}(?:电影院|公园|学校|老院子|车站|街|广场|医院|商场))",
                r"([^，。！？\s]{2,16}(?:电影院|公园|学校|老院子|车站|街|广场|医院|商场))"]
    for pattern in patterns:
        found = re.search(pattern, transcript)
        if found:
            return found.group(1).strip(), "demo_rule"
    return "", "none"


def checked_gps(gps):
    if gps is None:
        return None
    if not isinstance(gps, dict):
        raise InputError("gps must be an object")
    try:
        longitude = float(gps["longitude"])
        latitude = float(gps["latitude"])
    except (KeyError, TypeError, ValueError) as exc:
        raise InputError("gps requires numeric longitude and latitude") from exc
    if not (-180 <= longitude <= 180 and -90 <= latitude <= 90):
        raise InputError("gps coordinates out of range")
    if gps.get("coordinateSystem", "WGS84").upper() != "WGS84":
        raise InputError("device gps must be WGS84")
    sampled_at = str(gps.get("sampledAt", ""))
    source = str(gps.get("source", "device"))
    if source not in ("device", "demo_sample", "demo_manual"):
        raise InputError("gps.source must be device, demo_sample or demo_manual")
    if not sampled_at:
        raise InputError("gps.sampledAt is required to show recording-time evidence")
    try:
        datetime.fromisoformat(sampled_at.replace("Z", "+00:00"))
    except ValueError as exc:
        raise InputError("gps.sampledAt must be ISO-8601") from exc
    return {"longitude": longitude, "latitude": latitude, "coordinateSystem": "WGS84", "sampledAt": sampled_at, "source": source}


class Service:
    def __init__(self, database: str, amap):
        self.database = str(database)
        self.amap = amap
        Path(self.database).parent.mkdir(parents=True, exist_ok=True)
        with self._db() as db:
            db.executescript("""
                CREATE TABLE IF NOT EXISTS recordings (
                    id TEXT PRIMARY KEY, transcript TEXT NOT NULL, place_mention TEXT NOT NULL,
                    mention_source TEXT NOT NULL, gps_json TEXT, binding_json TEXT,
                    created_at TEXT NOT NULL
                );
                CREATE TABLE IF NOT EXISTS candidates (
                    id TEXT PRIMARY KEY, recording_id TEXT NOT NULL, place_json TEXT NOT NULL,
                    query TEXT NOT NULL, created_at TEXT NOT NULL,
                    FOREIGN KEY(recording_id) REFERENCES recordings(id) ON DELETE CASCADE
                );
                CREATE TABLE IF NOT EXISTS conversations (
                    id TEXT PRIMARY KEY, recording_id TEXT, turns_json TEXT NOT NULL,
                    prompt_index INTEGER NOT NULL DEFAULT 0, created_at TEXT NOT NULL
                );
                CREATE TABLE IF NOT EXISTS memories (
                    id TEXT PRIMARY KEY, title TEXT NOT NULL, story TEXT NOT NULL,
                    place_json TEXT, created_at TEXT NOT NULL, sample INTEGER NOT NULL DEFAULT 0
                );
                CREATE TABLE IF NOT EXISTS memory_recordings (
                    memory_id TEXT NOT NULL, recording_id TEXT NOT NULL, sort_order INTEGER NOT NULL,
                    confirmed_at TEXT NOT NULL,
                    PRIMARY KEY(memory_id, recording_id),
                    FOREIGN KEY(memory_id) REFERENCES memories(id) ON DELETE CASCADE,
                    FOREIGN KEY(recording_id) REFERENCES recordings(id) ON DELETE CASCADE
                );
                CREATE TABLE IF NOT EXISTS seed_tombstones (
                    id TEXT PRIMARY KEY
                );
            """)
            self._seed(db)

    def _seed(self, db):
        """Fixed IDs make seed insertion idempotent and preserve edited user data."""
        samples = [
            ("a" * 32, "和爷爷看电影的那个傍晚", "妈妈记得第一次和爷爷去看电影。散场后，他们沿着街边慢慢走，电影院门口的香樟树是她最清楚的画面。哪一年、是哪家影院，还可以和家人一起核对。", "上海 · 人民广场附近", 121.4792, 31.2300,
             [("1" * 32, "第一次和你爷爷看电影，是个傍晚。电影院门口有一棵很大的香樟树。", "00:09"),
              ("2" * 32, "电影散场以后，我们沿着街走了很久。我还记得他给我买了一包热栗子。", "00:11")]),
            ("b" * 32, "外婆院子里的石榴树", "外婆说，老院子里的石榴树每到秋天都会结满果子。孩子们放学回来，总要先跑到树下看一眼。", "苏州 · 平江路附近", 120.6338, 31.3190,
             [("3" * 32, "外婆家院子里有一棵石榴树，秋天的时候，果子红得很。", "00:08")]),
            ("c" * 32, "第一次坐火车去远方", "爸爸回忆第一次独自坐火车，站台上的广播和窗外掠过的灯光，至今记得。车站仍待家人核对。", "南京 · 南京站附近", 118.7965, 32.0887,
             [("4" * 32, "第一次一个人坐火车，我在站台上听着广播，心里又紧张又高兴。", "00:08")]),
        ]
        for mid, title, story, place_name, lon, lat, recordings in samples:
            if db.execute("SELECT 1 FROM seed_tombstones WHERE id=?", (mid,)).fetchone():
                continue
            place = {"name": place_name, "mapLongitude": lon, "mapLatitude": lat,
                     "coordinateSystem": "GCJ-02", "source": "sample_location",
                     "evidence": "家庭记忆中的区域线索；具体旧址待核对。"}
            db.execute("INSERT OR IGNORE INTO memories VALUES (?,?,?,?,?,1)",
                       (mid, title, story, json.dumps(place, ensure_ascii=False), "2026-09-20T12:00:00+08:00"))
            for order, (rid, transcript, duration) in enumerate(recordings):
                db.execute("INSERT OR IGNORE INTO recordings VALUES (?,?,?,?,?,?,?)",
                           (rid, transcript, place_name, "sample", None, None, "2026-09-20T08:42:00+08:00"))
                db.execute("INSERT OR IGNORE INTO memory_recordings VALUES (?,?,?,?)",
                           (mid, rid, order, "2026-09-20T12:00:00+08:00"))

    def memories(self):
        with self._db() as db:
            rows = db.execute("""SELECT m.*, count(mr.recording_id) AS source_count FROM memories m
                LEFT JOIN memory_recordings mr ON mr.memory_id=m.id GROUP BY m.id ORDER BY m.created_at DESC""").fetchall()
        return [{"id": r["id"], "title": r["title"], "story": r["story"],
                 "place": json.loads(r["place_json"]) if r["place_json"] else None,
                 "sourceCount": r["source_count"], "createdAt": r["created_at"],
                 "sample": bool(r["sample"])} for r in rows]

    def memory(self, memory_id):
        with self._db() as db:
            row = db.execute("SELECT * FROM memories WHERE id=?", (memory_id,)).fetchone()
            if not row: raise KeyError("memory not found")
            sources = db.execute("""SELECT r.* FROM memory_recordings mr JOIN recordings r
                ON r.id=mr.recording_id WHERE mr.memory_id=? ORDER BY mr.sort_order""", (memory_id,)).fetchall()
        return {"id": row["id"], "title": row["title"], "story": row["story"],
                "place": json.loads(row["place_json"]) if row["place_json"] else None,
                "createdAt": row["created_at"], "sample": bool(row["sample"]),
                "recordings": [{"id": r["id"], "transcript": r["transcript"],
                                "audioAsset": {"1" * 32: "memory_1", "2" * 32: "memory_2",
                                               "3" * 32: "memory_3", "4" * 32: "memory_4"}.get(r["id"], ""),
                                "createdAt": r["created_at"], "placeBinding": json.loads(r["binding_json"]) if r["binding_json"] else None}
                               for r in sources]}

    def link_recording(self, memory_id, recording_id):
        self.memory(memory_id)
        self.recording(recording_id)
        with self._db() as db:
            order = db.execute("SELECT count(*) FROM memory_recordings WHERE memory_id=?", (memory_id,)).fetchone()[0]
            db.execute("INSERT OR IGNORE INTO memory_recordings VALUES (?,?,?,?)", (memory_id, recording_id, order, now()))
        return self.memory(memory_id)

    def update_memory(self, memory_id, payload):
        previous = self.memory(memory_id)
        title = str(payload.get("title", previous["title"])).strip()[:100]
        story = str(payload.get("story", previous["story"])).strip()[:4000]
        if not title:
            raise InputError("memory title is required")
        with self._db() as db:
            db.execute("UPDATE memories SET title=?,story=? WHERE id=?", (title, story, memory_id))
        return self.memory(memory_id)

    def delete_memory(self, memory_id):
        memory = self.memory(memory_id)
        with self._db() as db:
            if memory["sample"]:
                db.execute("INSERT OR IGNORE INTO seed_tombstones VALUES (?)", (memory_id,))
            db.execute("DELETE FROM memories WHERE id=?", (memory_id,))
        return {"deleted": True, "memoryId": memory_id, "recordingsRetained": True}

    def conversation(self, session_id):
        with self._db() as db:
            row = db.execute("SELECT * FROM conversations WHERE id=?", (session_id,)).fetchone()
            if not row: raise KeyError("conversation not found")
        return {"id": session_id, "turns": json.loads(row["turns_json"]), "sdkStatus": SDK_NOTE}

    def memory_map(self, memory_id):
        place = self.memory(memory_id)["place"]
        if not place: raise InputError("memory has no location")
        return self.amap.static_map([(place["mapLongitude"], place["mapLatitude"])])

    def recording_map(self, recording_id, candidate_id=""):
        record = self.recording(recording_id)
        if candidate_id:
            found = [c for c in record["candidates"] if c["candidateId"] == candidate_id]
            if not found: raise InputError("candidate not found")
            points = [(found[0]["longitude"], found[0]["latitude"])]
        else:
            binding = record["placeBinding"]
            if binding and binding.get("mapLongitude") is not None:
                points = [(binding["mapLongitude"], binding["mapLatitude"])]
            else:
                points = [(c["longitude"], c["latitude"]) for c in record["candidates"]]
        if not points: raise InputError("no map location available")
        return self.amap.static_map(points)

    @contextmanager
    def _db(self):
        db = sqlite3.connect(self.database, timeout=10)
        db.row_factory = sqlite3.Row
        db.execute("PRAGMA foreign_keys=ON")
        try:
            yield db
            db.commit()
        except Exception:
            db.rollback()
            raise
        finally:
            db.close()

    def recording(self, recording_id):
        with self._db() as db:
            row = db.execute("SELECT * FROM recordings WHERE id=?", (recording_id,)).fetchone()
            if not row:
                raise KeyError("recording not found")
            candidates = db.execute("SELECT id,place_json,query FROM candidates WHERE recording_id=? ORDER BY created_at DESC LIMIT 5", (recording_id,)).fetchall()
        return {
            "id": row["id"], "transcript": row["transcript"],
            "placeMention": row["place_mention"], "mentionSource": row["mention_source"],
            "gps": json.loads(row["gps_json"]) if row["gps_json"] else None,
            "placeBinding": json.loads(row["binding_json"]) if row["binding_json"] else None,
            "candidates": [{"candidateId": c["id"], "query": c["query"], **json.loads(c["place_json"])} for c in candidates],
            "audioAsset": {"1" * 32: "memory_1", "2" * 32: "memory_2",
                           "3" * 32: "memory_3", "4" * 32: "memory_4"}.get(row["id"], ""),
            "sdkStatus": SDK_NOTE,
        }

    def recordings(self):
        with self._db() as db:
            rows = db.execute("""SELECT r.id,r.transcript,r.created_at,r.binding_json,
                count(mr.memory_id) AS memory_count FROM recordings r
                LEFT JOIN memory_recordings mr ON mr.recording_id=r.id
                GROUP BY r.id ORDER BY count(mr.memory_id) DESC,r.created_at DESC,r.id""").fetchall()
        return [{"id": r["id"], "transcript": r["transcript"], "createdAt": r["created_at"],
                 "memoryCount": r["memory_count"], "placeBinding": json.loads(r["binding_json"]) if r["binding_json"] else None}
                for r in rows]

    def create_recording(self, payload):
        transcript = str(payload.get("transcript", "")).strip()[:4000]
        explicit = str(payload.get("placeMention", ""))
        mention, mention_source = mentioned_place(transcript, explicit)
        gps = checked_gps(payload.get("gps"))
        if not transcript and not mention:
            raise InputError("transcript or placeMention is required")
        rid = uuid.uuid4().hex
        binding = None
        if mention and gps:
            binding = self._gps_binding(mention, gps)
        with self._db() as db:
            db.execute("INSERT INTO recordings VALUES (?,?,?,?,?,?,?)", (
                rid, transcript, mention, mention_source,
                json.dumps(gps, ensure_ascii=False) if gps else None,
                json.dumps(binding, ensure_ascii=False) if binding else None, now(),
            ))
        if mention and not gps:
            try:
                self.search_places(rid, mention, str(payload.get("region", "")))
            except AmapError:
                pass  # recording is safe; UI can retry search
        return self.recording(rid)

    def _gps_binding(self, mention, gps):
        binding = {
            "source": "device_gps" if gps["source"] == "device" else "demo_gps", "status": "bound", "name": mention,
            "longitude": gps["longitude"], "latitude": gps["latitude"],
            "coordinateSystem": "WGS84", "sampledAt": gps["sampledAt"],
            "evidence": "讲述中提到地点；坐标来自这段录音随附的记忆珠 GPS 样本。" if gps["source"] == "device" else "讲述中提到地点；坐标来自这段示例录音的位置记录。",
            "mapAddress": "", "mapLongitude": None, "mapLatitude": None,
        }
        try:
            map_lon, map_lat = self.amap.convert_gps(gps["longitude"], gps["latitude"])
            binding["mapLongitude"], binding["mapLatitude"] = map_lon, map_lat
            binding["mapAddress"] = self.amap.reverse(map_lon, map_lat)
        except AmapError:
            binding["mapStatus"] = "unavailable; original GPS retained"
        return binding

    @staticmethod
    def _memory_place(binding):
        longitude = binding.get("mapLongitude", binding.get("longitude"))
        latitude = binding.get("mapLatitude", binding.get("latitude"))
        if longitude is None or latitude is None:
            return None
        return {"name": binding.get("name", "已确认地点"), "mapLongitude": longitude,
                "mapLatitude": latitude, "coordinateSystem": "GCJ-02",
                "source": binding.get("source", ""), "evidence": binding.get("evidence", "由家人确认的地点。")}

    def _update_linked_memory_places(self, db, recording_id, binding):
        place = self._memory_place(binding)
        if place:
            db.execute("""UPDATE memories SET place_json=? WHERE id IN
                (SELECT memory_id FROM memory_recordings WHERE recording_id=?)""",
                (json.dumps(place, ensure_ascii=False), recording_id))

    def bind_gps(self, recording_id, gps_payload, place_mention=None):
        record = self.recording(recording_id)
        mention = str(place_mention).strip()[:80] if place_mention is not None else record["placeMention"]
        if not mention:
            raise InputError("a spoken place mention is required before GPS binding")
        gps = checked_gps(gps_payload)
        if gps is None:
            raise InputError("gps is required")
        binding = self._gps_binding(mention, gps)
        with self._db() as db:
            db.execute("UPDATE recordings SET place_mention=?, mention_source=?, gps_json=?, binding_json=? WHERE id=?", (
                mention, "explicit" if place_mention is not None else record["mentionSource"],
                json.dumps(gps, ensure_ascii=False), json.dumps(binding, ensure_ascii=False), recording_id))
            db.execute("DELETE FROM candidates WHERE recording_id=?", (recording_id,))
            self._update_linked_memory_places(db, recording_id, binding)
        return self.recording(recording_id)

    def search_places(self, recording_id, query, region=""):
        self.recording(recording_id)
        query = str(query).strip()[:80]
        region = str(region).strip()[:40]
        if len(query) < 2:
            raise InputError("search query needs at least two characters")
        found = self.amap.search(query, region)
        with self._db() as db:
            db.execute("UPDATE recordings SET place_mention=?, mention_source=? WHERE id=?", (query, "explicit", recording_id))
            db.execute("DELETE FROM candidates WHERE recording_id=?", (recording_id,))
            for item in found:
                item["evidence"] = "名称和地址与讲述线索相近，请核对是否为故事中的地点。"
                db.execute("INSERT INTO candidates VALUES (?,?,?,?,?)", (
                    uuid.uuid4().hex, recording_id, json.dumps(item, ensure_ascii=False), query, now(),
                ))
        return self.recording(recording_id)

    def confirm_place(self, recording_id, candidate_id):
        self.recording(recording_id)
        with self._db() as db:
            row = db.execute("SELECT place_json,query FROM candidates WHERE id=? AND recording_id=?", (candidate_id, recording_id)).fetchone()
            if not row:
                raise InputError("candidate not found for this recording")
            place = json.loads(row["place_json"])
            binding = {**place, "source": "user_confirmed_amap", "status": "bound",
                       "confirmedAt": now(), "query": row["query"]}
            db.execute("UPDATE recordings SET binding_json=? WHERE id=?", (json.dumps(binding, ensure_ascii=False), recording_id))
            self._update_linked_memory_places(db, recording_id, binding)
        return self.recording(recording_id)

    def delete_recording(self, recording_id):
        self.recording(recording_id)
        with self._db() as db:
            db.execute("DELETE FROM conversations WHERE recording_id=?", (recording_id,))
            db.execute("DELETE FROM recordings WHERE id=?", (recording_id,))
        return {"deleted": True, "recordingId": recording_id}

    def create_conversation(self, payload):
        if payload.get("consent") is not True:
            raise InputError("explicit participant consent is required")
        recording_id = payload.get("recordingId")
        if recording_id:
            self.recording(str(recording_id))
        sid = uuid.uuid4().hex
        opening = [{"speaker": "assistant", "text": "今天想从哪段记忆聊起？", "at": now(), "source": "demo_rules"}]
        with self._db() as db:
            db.execute("INSERT INTO conversations VALUES (?,?,?,?,?)", (sid, recording_id, json.dumps(opening, ensure_ascii=False), 0, now()))
        return {"id": sid, "mode": "demo_rules", "sdkStatus": SDK_NOTE,
                "message": "共忆会话已开始。仅保存本次主动提交的文字轮次，不接收实时音频。"}

    def add_turn(self, session_id, payload):
        speaker = payload.get("speaker")
        text = str(payload.get("text", "")).strip()[:2000]
        if speaker not in ("elder", "family") or not text:
            raise InputError("speaker must be elder or family and text must be nonempty")
        with self._db() as db:
            row = db.execute("SELECT turns_json FROM conversations WHERE id=?", (session_id,)).fetchone()
            if not row:
                raise KeyError("conversation not found")
            turns = json.loads(row["turns_json"])
            turns.append({"speaker": speaker, "text": text, "at": now()})
            db.execute("UPDATE conversations SET turns_json=? WHERE id=?", (json.dumps(turns, ensure_ascii=False), session_id))
        return {"id": session_id, "turnCount": len(turns), "sdkStatus": SDK_NOTE}

    def next_prompt(self, session_id):
        with self._db() as db:
            row = db.execute("SELECT turns_json,prompt_index FROM conversations WHERE id=?", (session_id,)).fetchone()
            if not row:
                raise KeyError("conversation not found")
            turns = json.loads(row["turns_json"])
            elder = [t["text"] for t in turns if t["speaker"] == "elder"]
            if not elder:
                raise InputError("an elder's completed turn is needed before suggesting a question")
            last = elder[-1]
            if "电影" in last:
                questions = ["那天看完电影后，你们去了哪里？", "电影院附近有什么让你印象深刻？", "你还记得那天和谁一起去的吗？"]
            elif "院子" in last or "树" in last:
                questions = ["那个院子里，你最记得什么？", "那棵树会让你想起谁？", "后来那个地方有什么变化？"]
            else:
                questions = ["这件事里，你最记得哪一刻？", "后来又发生了什么？", "你还想补充什么细节？"]
            index = row["prompt_index"] % len(questions)
            db.execute("UPDATE conversations SET prompt_index=? WHERE id=?", (row["prompt_index"] + 1, session_id))
            turns.append({"speaker": "assistant", "text": questions[index], "at": now(), "source": "demo_rules"})
            db.execute("UPDATE conversations SET turns_json=? WHERE id=?", (json.dumps(turns, ensure_ascii=False), session_id))
        return {"question": questions[index], "source": "demo_rules", "canIgnore": True,
                "canReplace": True, "sdkStatus": SDK_NOTE,
                "evidence": "仅根据本次主动提交的讲述文字选择开放问题；没有调用大模型。"}

    def delete_conversation(self, session_id):
        with self._db() as db:
            cursor = db.execute("DELETE FROM conversations WHERE id=?", (session_id,))
            if cursor.rowcount == 0:
                raise KeyError("conversation not found")
        return {"deleted": True, "conversationId": session_id}
