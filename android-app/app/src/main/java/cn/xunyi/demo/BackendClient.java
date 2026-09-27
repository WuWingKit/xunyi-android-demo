package cn.xunyi.demo;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Public-release data source: examples and edits never leave the device. */
final class BackendClient {
    interface Callback { void done(JSONObject data, String error); }
    private static final String STATE_KEY = "public_demo_state_v1";
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final DemoStore store;
    private JSONObject state;

    BackendClient(Context context, DemoStore store) {
        this.store = store;
        try {
            String saved = store.get(STATE_KEY, "");
            if (!saved.isEmpty()) state = new JSONObject(saved);
            else try (InputStream in = context.getAssets().open("public_demo.json")) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096]; int n;
                while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
                state = new JSONObject(out.toString(StandardCharsets.UTF_8.name()));
                // A private-test installation may be upgraded in place. Its session IDs belonged
                // to the removed shared backend and must not be reused by this offline build.
                store.put("conversation_id", "");
                store.put("backend_recording_id", "");
                store.put("backend_place_json", "{}");
                store.put("backend_question", "");
                save();
            }
            migrateSampleCoordinates();
        } catch (Exception ex) { throw new IllegalStateException("Demo data unavailable", ex); }
    }

    private void migrateSampleCoordinates() throws Exception {
        boolean changed = false;
        JSONArray list = memories();
        for (int i = 0; i < list.length(); i++) {
            JSONObject memory = list.optJSONObject(i);
            if (memory == null) continue;
            JSONObject place = memory.optJSONObject("place");
            if (place == null || !"sample_location".equals(place.optString("source")) || hasCoordinates(place)) continue;
            String id = memory.optString("id");
            if (id.startsWith("aaaa")) place.put("mapLongitude", 121.4792).put("mapLatitude", 31.2300);
            else if (id.startsWith("bbbb")) place.put("mapLongitude", 120.6338).put("mapLatitude", 31.3190);
            else if (id.startsWith("cccc")) place.put("mapLongitude", 118.7965).put("mapLatitude", 32.0887);
            else continue;
            place.put("coordinateSystem", "GCJ-02");
            changed = true;
        }
        if (changed) save();
    }

    void get(String path, Callback callback) { request("GET", path, null, callback); }
    void post(String path, JSONObject body, Callback callback) { request("POST", path, body, callback); }
    void delete(String path, Callback callback) { request("DELETE", path, null, callback); }
    void close() { worker.shutdownNow(); }
    private void request(String method, String path, JSONObject body, Callback callback) {
        worker.execute(() -> {
            JSONObject data = null; String error = null;
            try { data = dispatch(method, path, body == null ? new JSONObject() : body); }
            catch (Exception ex) { error = ex.getMessage() == null ? "演示数据暂不可用" : ex.getMessage(); }
            JSONObject result = data; String failure = error;
            main.post(() -> callback.done(result, failure));
        });
    }
    private JSONArray memories() { return state.optJSONArray("memories"); }
    private JSONArray recordings() { return state.optJSONArray("recordings"); }
    private JSONArray conversations() { return state.optJSONArray("conversations"); }
    private void save() { store.put(STATE_KEY, state.toString()); }
    private static JSONObject copy(JSONObject value) throws Exception { return new JSONObject(value.toString()); }
    private static JSONObject find(JSONArray list, String id) {
        if (list != null) for (int i = 0; i < list.length(); i++) {
            JSONObject item = list.optJSONObject(i);
            if (item != null && id.equals(item.optString("id"))) return item;
        }
        return null;
    }
    private static JSONObject required(JSONArray list, String id, String label) throws Exception {
        JSONObject item = find(list, id);
        if (item == null) throw new Exception(label + "不存在或已删除");
        return item;
    }
    private static void remove(JSONArray list, String id) {
        for (int i = list.length() - 1; i >= 0; i--)
            if (id.equals(list.optJSONObject(i).optString("id"))) list.remove(i);
    }
    private static boolean contains(JSONArray list, String id) {
        if (list != null) for (int i = 0; i < list.length(); i++) if (id.equals(list.optString(i))) return true;
        return false;
    }
    private JSONObject memoryDetail(String id) throws Exception {
        JSONObject memory = copy(required(memories(), id, "记忆"));
        JSONArray sources = new JSONArray(), ids = memory.optJSONArray("recordingIds");
        if (ids != null) for (int i = 0; i < ids.length(); i++) {
            JSONObject source = find(recordings(), ids.optString(i));
            if (source != null) sources.put(new JSONObject()
                    .put("id", source.optString("id"))
                    .put("transcript", source.optString("transcript"))
                    .put("audioAsset", source.optString("audioAsset"))
                    .put("createdAt", source.optString("createdAt"))
                    .put("placeBinding", source.opt("placeBinding")));
        }
        return memory.put("recordings", sources);
    }

    private JSONObject dispatch(String method, String path, JSONObject body) throws Exception {
        if ("GET".equals(method) && "/v1/memories".equals(path)) {
            JSONArray list = new JSONArray();
            for (int i = 0; i < memories().length(); i++) {
                JSONObject item = copy(memories().getJSONObject(i));
                JSONArray ids = item.optJSONArray("recordingIds");
                list.put(item.put("sourceCount", ids == null ? 0 : ids.length()));
            }
            return new JSONObject().put("memories", list);
        }
        if ("GET".equals(method) && "/v1/recordings".equals(path)) {
            JSONArray list = new JSONArray();
            for (int i = 0; i < recordings().length(); i++) {
                JSONObject item = copy(recordings().getJSONObject(i)); int count = 0;
                for (int j = 0; j < memories().length(); j++)
                    if (contains(memories().getJSONObject(j).optJSONArray("recordingIds"), item.optString("id"))) count++;
                list.put(item.put("memoryCount", count));
            }
            return new JSONObject().put("recordings", list);
        }
        String[] part = path.split("/");
        if (part.length >= 4 && "v1".equals(part[1]) && "memories".equals(part[2])) {
            String id = part[3];
            if ("GET".equals(method) && part.length == 4) return memoryDetail(id);
            if ("POST".equals(method) && part.length == 5 && "edit".equals(part[4])) {
                JSONObject memory = required(memories(), id, "记忆");
                String title = body.optString("title", memory.optString("title")).trim();
                if (title.isEmpty()) throw new Exception("故事标题不能为空");
                String story = body.optString("story", memory.optString("story"));
                memory.put("title", title.substring(0, Math.min(title.length(), 100)));
                memory.put("story", story.substring(0, Math.min(story.length(), 4000)));
                save(); return memoryDetail(id);
            }
            if ("DELETE".equals(method) && part.length == 4) {
                required(memories(), id, "记忆"); remove(memories(), id); save();
                return new JSONObject().put("deleted", true).put("recordingsRetained", true);
            }
        }
        if (part.length >= 4 && "v1".equals(part[1]) && "recordings".equals(part[2])) {
            String id = part[3];
            if ("GET".equals(method) && part.length == 4) return copy(required(recordings(), id, "讲述"));
            if ("DELETE".equals(method) && part.length == 4) {
                required(recordings(), id, "讲述"); remove(recordings(), id);
                for (int i = 0; i < memories().length(); i++) {
                    JSONArray ids = memories().getJSONObject(i).optJSONArray("recordingIds");
                    if (ids != null) for (int j = ids.length() - 1; j >= 0; j--)
                        if (id.equals(ids.optString(j))) ids.remove(j);
                }
                save(); return new JSONObject().put("deleted", true);
            }
            if ("POST".equals(method) && part.length == 6 && "places".equals(part[4])) {
                JSONObject recording = required(recordings(), id, "讲述");
                if ("search".equals(part[5])) {
                    String query = body.optString("query").trim();
                    if (query.length() < 2) throw new Exception("请至少输入两个字的地点线索");
                    String city = query.contains("苏州") || query.contains("平江") ? "苏州" :
                            query.contains("南京") ? "南京" : "上海";
                    JSONArray options = new JSONArray();
                    for (int i = 0; i < 3; i++) options.put(new JSONObject()
                            .put("candidateId", UUID.randomUUID().toString().replace("-", ""))
                            .put("name", i == 0 ? query : query + (i == 1 ? "周边" : "旧址线索"))
                            .put("city", city).put("district", " · 演示候选")
                            .put("address", "仅供交互演示；真实地点需家人核对")
                            .put("evidence", "离线示例候选，不代表已找到历史地点。"));
                    recording.put("placeMention", query).put("candidates", options);
                    save(); return copy(recording);
                }
                if ("confirm".equals(part[5])) {
                    String choice = body.optString("candidateId");
                    JSONObject selected = null; JSONArray options = recording.optJSONArray("candidates");
                    if (options != null) for (int i = 0; i < options.length(); i++) {
                        JSONObject option = options.optJSONObject(i);
                        if (option != null && choice.equals(option.optString("candidateId"))) selected = option;
                    }
                    if (selected == null) throw new Exception("请先选择地点候选");
                    JSONObject place = copy(selected).put("source", "user_confirmed_demo")
                            .put("evidence", "离线演示候选，经用户选择；真实位置待核对。");
                    recording.put("placeBinding", place);
                    for (int i = 0; i < memories().length(); i++) {
                        JSONObject memory = memories().getJSONObject(i);
                        if (contains(memory.optJSONArray("recordingIds"), id)) memory.put("place", copy(place));
                    }
                    save(); return copy(recording);
                }
            }
        }
        if ("POST".equals(method) && "/v1/conversations".equals(path)) {
            if (!body.optBoolean("consent")) throw new Exception("需先确认在场者同意录音");
            String id = UUID.randomUUID().toString().replace("-", "");
            JSONArray turns = new JSONArray().put(new JSONObject().put("speaker", "assistant")
                    .put("text", "今天想从哪段记忆聊起？").put("source", "demo_rules"));
            conversations().put(new JSONObject().put("id", id).put("turns", turns).put("promptIndex", 0));
            save(); return new JSONObject().put("id", id).put("mode", "demo_rules");
        }
        if (part.length >= 4 && "v1".equals(part[1]) && "conversations".equals(part[2])) {
            String id = part[3]; JSONObject session = required(conversations(), id, "共忆会话");
            if ("GET".equals(method) && part.length == 4) return copy(session);
            if ("DELETE".equals(method) && part.length == 4) {
                remove(conversations(), id); save(); return new JSONObject().put("deleted", true);
            }
            if ("POST".equals(method) && part.length == 5 && "turns".equals(part[4])) {
                String speaker = body.optString("speaker"), text = body.optString("text").trim();
                if (!("elder".equals(speaker) || "family".equals(speaker)) || text.isEmpty())
                    throw new Exception("请填写讲述内容");
                session.getJSONArray("turns").put(new JSONObject().put("speaker", speaker)
                        .put("text", text.substring(0, Math.min(text.length(), 2000))));
                save(); return new JSONObject().put("id", id).put("turnCount", session.getJSONArray("turns").length());
            }
            if ("POST".equals(method) && part.length == 6 && "prompts".equals(part[4]) && "next".equals(part[5])) {
                JSONArray turns = session.getJSONArray("turns"); String last = "";
                for (int i = turns.length() - 1; i >= 0; i--) {
                    JSONObject turn = turns.optJSONObject(i);
                    if (turn != null && "elder".equals(turn.optString("speaker"))) { last = turn.optString("text"); break; }
                }
                if (last.isEmpty()) throw new Exception("需要长辈先完成一轮讲述");
                String[] questions = last.contains("电影") ? new String[]{"那天看完电影后，你们去了哪里？", "电影院附近有什么让你印象深刻？", "你还记得和谁一起去的吗？"} :
                        last.contains("院子") || last.contains("树") ? new String[]{"那个院子里，你最记得什么？", "那棵树会让你想起谁？", "后来那个地方有什么变化？"} :
                        new String[]{"这件事里，你最记得哪一刻？", "后来又发生了什么？", "你还想补充什么细节？"};
                int index = session.optInt("promptIndex", 0); String question = questions[index % questions.length];
                session.put("promptIndex", index + 1);
                turns.put(new JSONObject().put("speaker", "assistant").put("text", question).put("source", "demo_rules"));
                save(); return new JSONObject().put("question", question).put("source", "demo_rules")
                        .put("canIgnore", true).put("canReplace", true);
            }
        }
        throw new Exception("这个演示操作暂不可用");
    }

    /** Official AMap URI API: no key, token, or private family data is bundled. */
    String mapUrl(String path) {
        if ("/v1/memories/map".equals(path)) {
            StringBuilder points = new StringBuilder();
            JSONArray list = memories();
            for (int i = 0; i < list.length() && i < 10; i++) {
                JSONObject memory = list.optJSONObject(i);
                JSONObject place = memory == null ? null : memory.optJSONObject("place");
                if (!hasCoordinates(place)) continue;
                if (points.length() > 0) points.append('|');
                points.append(place.optDouble("mapLongitude")).append(',')
                        .append(place.optDouble("mapLatitude")).append(',')
                        .append((char) ('A' + i)).append('·').append(place.optString("name"));
            }
            return points.length() == 0 ? null : new Uri.Builder().scheme("https")
                    .authority("uri.amap.com").path("/marker")
                    .appendQueryParameter("markers", points.toString())
                    .appendQueryParameter("src", "xunyi-demo")
                    .appendQueryParameter("callnative", "0").build().toString();
        }
        String[] parts = path.split("/");
        JSONObject place = null;
        String query = "";
        if (parts.length > 3 && "memories".equals(parts[2])) {
            JSONObject memory = find(memories(), parts[3]);
            if (memory != null) place = memory.optJSONObject("place");
        } else if (parts.length > 3 && "recordings".equals(parts[2])) {
            JSONObject recording = find(recordings(), parts[3]);
            if (recording != null) {
                place = recording.optJSONObject("placeBinding");
                query = recording.optString("placeMention", "");
            }
        }
        if (hasCoordinates(place)) return new Uri.Builder().scheme("https")
                .authority("uri.amap.com").path("/marker")
                .appendQueryParameter("position", place.optDouble("mapLongitude") + "," + place.optDouble("mapLatitude"))
                .appendQueryParameter("name", place.optString("name"))
                .appendQueryParameter("coordinate", "gaode")
                .appendQueryParameter("src", "xunyi-demo")
                .appendQueryParameter("callnative", "0").build().toString();
        if (query.trim().isEmpty()) return null;
        return new Uri.Builder().scheme("https").authority("uri.amap.com").path("/search")
                .appendQueryParameter("keyword", query.trim())
                .appendQueryParameter("src", "xunyi-demo")
                .appendQueryParameter("callnative", "0").build().toString();
    }
    private static boolean hasCoordinates(JSONObject place) {
        return place != null && place.has("mapLongitude") && place.has("mapLatitude")
                && !place.isNull("mapLongitude") && !place.isNull("mapLatitude")
                && Math.abs(place.optDouble("mapLongitude")) <= 180
                && Math.abs(place.optDouble("mapLatitude")) <= 90;
    }
}
