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
            """)

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
            "sdkStatus": SDK_NOTE,
        }

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
                item["evidence"] = "高德当前 POI 名称和地址与讲述线索可供核对；不能证明历史地点。"
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
        with self._db() as db:
            db.execute("INSERT INTO conversations VALUES (?,?,?,?,?)", (sid, recording_id, "[]", 0, now()))
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
        return {"question": questions[index], "source": "demo_rules", "canIgnore": True,
                "canReplace": True, "sdkStatus": SDK_NOTE,
                "evidence": "仅根据本次主动提交的讲述文字选择开放问题；没有调用大模型。"}

    def delete_conversation(self, session_id):
        with self._db() as db:
            cursor = db.execute("DELETE FROM conversations WHERE id=?", (session_id,))
            if cursor.rowcount == 0:
                raise KeyError("conversation not found")
        return {"deleted": True, "conversationId": session_id}
