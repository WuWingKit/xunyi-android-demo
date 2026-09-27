package cn.xunyi.demo;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
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
    interface ImageCallback { void done(Bitmap image, String error); }
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
        } catch (Exception ex) { throw new IllegalStateException("Demo data unavailable", ex); }
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

    void image(String path, ImageCallback callback) {
        worker.execute(() -> {
            Bitmap bitmap = null; String error = null;
            try { bitmap = schematicMap(path); } catch (Exception ex) { error = "地点示意图暂不可用"; }
            Bitmap result = bitmap; String failure = error;
            main.post(() -> callback.done(result, failure));
        });
    }
    private Bitmap schematicMap(String path) {
        Bitmap bitmap = Bitmap.createBitmap(900, 560, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap); canvas.drawColor(Color.rgb(245, 242, 233));
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.rgb(193, 219, 225)); paint.setStrokeWidth(56);
        canvas.drawLine(0, 155, 900, 340, paint);
        paint.setColor(Color.rgb(231, 220, 199)); paint.setStrokeWidth(11);
        for (int i = -2; i < 7; i++) {
            canvas.drawLine(i * 160, 0, i * 160 + 250, 560, paint);
            canvas.drawLine(0, i * 130, 900, i * 130 + 75, paint);
        }
        paint.setColor(Color.rgb(55, 78, 61)); paint.setTextSize(31); paint.setFakeBoldText(true);
        canvas.drawText("记忆地点示意图", 30, 55, paint);
        paint.setTextSize(21); paint.setFakeBoldText(false);
        canvas.drawText("离线演示 · 非导航地图 · 地点需人工核对", 30, 87, paint);
        if ("/v1/memories/map".equals(path)) {
            marker(canvas, 175, 260, "A", "上海");
            marker(canvas, 445, 375, "B", "苏州");
            marker(canvas, 715, 235, "C", "南京");
        } else if (path.contains("/v1/recordings/") && path.contains("?selected=")) {
            String[] segments = path.split("/");
            JSONObject record = segments.length > 3 ? find(recordings(), segments[3]) : null;
            JSONArray choices = record == null ? null : record.optJSONArray("candidates");
            String selected = path.substring(path.indexOf("?selected=") + 10);
            int total = choices == null ? 0 : Math.min(choices.length(), 5);
            for (int i = 0; i < total; i++) {
                JSONObject choice = choices.optJSONObject(i);
                float x = 180 + i * (total == 1 ? 0 : 540f / (total - 1));
                float y = i % 2 == 0 ? 285 : 370;
                marker(canvas, x, y, String.valueOf((char)('A' + i)),
                        choice != null && selected.equals(choice.optString("candidateId")) ? "当前选择" : "候选");
            }
        } else {
            String city = path.contains("bbbb") || path.contains("3333") ? "苏州" :
                    path.contains("cccc") || path.contains("4444") ? "南京" : "上海";
            marker(canvas, 450, 292, "A", city);
        }
        return bitmap;
    }
    private static void marker(Canvas canvas, float x, float y, String letter, String city) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.rgb(166, 82, 56)); canvas.drawCircle(x, y, 26, paint);
        paint.setColor(Color.WHITE); paint.setTextSize(25); paint.setFakeBoldText(true);
        canvas.drawText(letter, x - 9, y + 9, paint);
        paint.setColor(Color.rgb(44, 54, 45)); paint.setTextSize(26);
        canvas.drawText(city, x - 30, y + 61, paint);
    }
}
