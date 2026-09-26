package cn.xunyi.demo;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;

public final class MainActivity extends Activity {
    private static final int BG=0xFFF7F5F2, WHITE=Color.WHITE, INK=0xFF282622,
            SUB=0xFF514E48, GREEN=0xFF526B58, SAND=0xFFE9C9A9, WARN=0xFFA84D32,
            LINE=0xFFDEDAD2;
    private static final String SAMPLE="我第一次和你爷爷看电影，就是在这里。电影院门口有一棵很大的香樟树。";
    private static final String[] QUESTIONS={"后来雨停了吗？", "散场后，你们去了哪里？", "那天有什么声音让你一直记得？"};
    private final Handler handler=new Handler(Looper.getMainLooper());
    private DemoStore store;
    private BackendClient backend;
    private LinearLayout root, body, nav;
    private String page="home", filter="全部";
    private boolean syncing=false, recordingDemo=false;
    private int syncProgress=0, questionIndex=0;
    private MediaPlayer media;
    private SeekBar progressBar;
    private TextView progressText;
    private Button playButton;
    private String notice="";

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        store=new DemoStore(this);
        backend=new BackendClient(store);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        show("home");
    }
    @Override protected void onDestroy() { releaseAudio();backend.close(); super.onDestroy(); }
    @Override public void onBackPressed() {
        if(page.equals("home")||page.equals("memories")||page.equals("mine")) { show("home"); return; }
        if(page.equals("edit")) show("record");
        else if(page.equals("place")) show("record");
        else if(page.equals("share")||page.equals("prompts")) show("memory");
        else if(page.equals("memory")) show("memories");
        else show("home");
    }
    private int dp(float n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private GradientDrawable shape(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private GradientDrawable outline(int color,int radius){GradientDrawable d=shape(color,radius);d.setStroke(dp(1),LINE);return d;}
    private LinearLayout vertical(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);return v;}
    private LinearLayout horizontal(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.HORIZONTAL);v.setGravity(Gravity.CENTER_VERTICAL);return v;}
    private LinearLayout.LayoutParams lp(int w,int h){return new LinearLayout.LayoutParams(w<0?w:dp(w),h<0?h:dp(h));}
    private void gap(LinearLayout to,int height){View v=new View(this);to.addView(v,lp(1,height));}
    private TextView label(String text,int size,int color,boolean bold){TextView v=new TextView(this);v.setText(text);v.setTextSize(size);v.setTextColor(color);v.setLineSpacing(dp(5),1.1f);if(bold)v.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return v;}
    private void text(LinearLayout to,String value,int size,int color,boolean bold){to.addView(label(value,size,color,bold),lp(-1,-2));}
    private LinearLayout card(LinearLayout to){LinearLayout v=vertical();v.setPadding(dp(20),dp(20),dp(20),dp(20));v.setBackground(shape(WHITE,26));v.setElevation(dp(3));LinearLayout.LayoutParams p=lp(-1,-2);p.bottomMargin=dp(16);to.addView(v,p);return v;}
    private Button button(String title,boolean primary,Runnable action){Button b=new Button(this);b.setText(title);b.setTextSize(18);b.setAllCaps(false);b.setMinHeight(dp(52));b.setPadding(dp(16),dp(8),dp(16),dp(8));b.setTextColor(primary?WHITE:GREEN);b.setBackground(primary?shape(GREEN,18):outline(WHITE,18));b.setOnClickListener(v->action.run());return b;}
    private void addButton(LinearLayout to,String title,boolean primary,Runnable action){to.addView(button(title,primary,action),lp(-1,-2));}
    private void smallAction(LinearLayout to,String title,Runnable action){Button b=button(title,false,action);to.addView(b,lp(-1,-2));}
    private void message(String value){notice=value;render();}
    private void show(String next){releaseAudio();page=next;render();}
    private void render(){
        root=vertical();root.setBackgroundColor(BG);
        root.setOnApplyWindowInsetsListener((view,insets)->{
            android.graphics.Insets safe=insets.getInsets(WindowInsets.Type.statusBars()|WindowInsets.Type.navigationBars());
            view.setPadding(0,safe.top,0,safe.bottom);
            return insets;
        });
        setContentView(root);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(false);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        body=vertical();body.setPadding(dp(20),dp(22),dp(20),dp(24));scroll.addView(body);
        if(!notice.isEmpty()){LinearLayout n=card(body);n.setBackground(shape(0xFFF1E6D9,20));text(n,notice,16,INK,false);notice="";}
        switch(page){
            case "home": home();break;
            case "record": record();break;
            case "edit": edit();break;
            case "memories": memories();break;
            case "memory": memory();break;
            case "share": share();break;
            case "prompts": prompts();break;
            case "mine": mine();break;
            case "connect": connect();break;
            case "place": place();break;
            default: home();
        }
        if(page.equals("home")||page.equals("memories")||page.equals("mine")) bottomNav();
    }
    private void header(String title,String subtitle){text(body,title,30,INK,true);gap(body,4);text(body,subtitle,16,SUB,false);gap(body,22);}
    private void back(String title,Runnable action){smallAction(body,"‹ 返回",action);gap(body,16);text(body,title,30,INK,true);gap(body,20);}
    private boolean connected(){return store.get("connected",true);}
    private boolean synced(){return store.get("synced",false);}
    private boolean saved(){return store.get("saved",false);}
    private boolean deleted(){return store.get("deleted",false);}
    private String title(){return store.get("title","第一次和爷爷看电影");}
    private String transcript(){return store.get("transcript",SAMPLE);}
    private String story(){return store.get("story","那次和爷爷去旧电影院看电影，门口有一棵很大的香樟树。具体年份尚未确认。");}
    private JSONObject placeData(){try{return new JSONObject(store.get("backend_place_json","{}"));}catch(Exception e){return new JSONObject();}}
    private String placeSummary(){
        JSONObject binding=placeData().optJSONObject("placeBinding");
        if(binding==null)return store.get("place_added",false)?"✓ 本次手动添加：旧电影院（本机演示）":"? 地点未绑定。可使用录音豆 GPS 或搜索候选后确认。";
        String source=binding.optString("source","");
        return "✓ "+binding.optString("name","已绑定地点")+" · "+(source.equals("device_gps")?"录音豆 GPS":source.equals("demo_gps")?"演示 GPS":"高德候选，经用户确认");
    }
    private void storePlaceResponse(JSONObject data){
        if(data==null)return;
        store.put("backend_place_json",data.toString());
        String id=data.optString("id","");if(!id.isEmpty())store.put("backend_recording_id",id);
        if(data.optJSONObject("placeBinding")!=null)store.put("place_added",true);
    }
    private void bottomNav(){
        nav=horizontal();nav.setPadding(dp(8),dp(6),dp(8),dp(8));nav.setBackground(outline(WHITE,22));
        root.addView(nav,lp(-1,-2));
        tab("◉\n录音","home");tab("▣\n记忆","memories");tab("◇\n我的","mine");
    }
    private void tab(String text,String target){
        TextView t=label(text,17,page.equals(target)?GREEN:SUB,page.equals(target));t.setGravity(Gravity.CENTER);t.setMinHeight(dp(64));
        if(page.equals(target))t.setBackground(shape(0xFFE9EFE9,16));
        nav.addView(t,new LinearLayout.LayoutParams(0,-2,1));t.setOnClickListener(v->show(target));
    }
    private void home(){
        header("录音","先听见真实的声音，再决定如何整理");
        LinearLayout hero=card(body);hero.setBackground(shape(0xFFFFFDFC,30));
        text(hero,"声音记忆珠 · 演示设备",18,INK,true);gap(hero,12);
        if(!connected()){
            text(hero,"○ 记忆珠未连接",20,WARN,true);gap(hero,6);
            text(hero,"请打开记忆珠并靠近手机。当前为连接流程演示。",16,SUB,false);gap(hero,18);
            addButton(hero,"连接记忆珠",true,()->show("connect"));
        } else if(syncing){
            text(hero,"↻ 正在同步 · "+syncProgress+"%",20,GREEN,true);gap(hero,8);
            text(hero,"演示录音正在从记忆珠传到手机",16,SUB,false);gap(hero,12);
            View track=new View(this);track.setBackground(shape(LINE,6));hero.addView(track,lp(-1,8));gap(hero,16);
            smallAction(hero,"模拟同步失败",this::failSync);
        } else if(!synced()&&!deleted()){
            text(hero,"● 已连接 · 1 条录音待同步",20,INK,true);gap(hero,8);
            text(hero,store.get("sync_failed",false)?"! 上次同步未完成。录音仍保存在记忆珠中，请靠近手机重试。":"录音仍保存在记忆珠中，尚未进入手机。",16,SUB,false);gap(hero,18);
            addButton(hero,store.get("sync_failed",false)?"重新同步":"同步录音",true,this::startSync);
        } else {
            text(hero,"✓ 已连接 · 录音已同步",20,GREEN,true);gap(hero,8);
            text(hero,"已同步内容保存在本机的演示数据中。",16,SUB,false);gap(hero,18);
            if(!deleted()) addButton(hero,"查看最近录音",true,()->show("record"));
        }
        gap(body,4);text(body,"最近录音",22,INK,true);gap(body,12);
        if(!synced()||deleted()){
            LinearLayout c=card(body);text(c,"这里会保存记忆珠录下的声音。",18,INK,true);gap(c,8);text(c,"按下记忆珠按钮录音后，连接手机即可同步。",16,SUB,false);
        } else {
            LinearLayout c=card(body);text(c,"今天 08:42  ·  18 秒",16,SUB,false);gap(c,8);
            text(c,transcript(),18,INK,true);gap(c,8);text(c,"✓ 已同步至手机 · 仅自己可见",16,GREEN,false);gap(c,16);
            addButton(c,"查看这段录音",false,()->show("record"));
        }
        LinearLayout info=card(body);text(info,"演示说明",18,INK,true);gap(info,8);text(info,"挂件录音与蓝牙同步为模拟状态；真实硬件和官方 SDK 接入后替换。",16,SUB,false);
    }
    private void startSync(){if(syncing)return;syncing=true;syncProgress=0;store.put("sync_failed",false);render();advanceSync();}
    private void advanceSync(){handler.postDelayed(()->{if(!syncing)return;syncProgress+=25;if(syncProgress>=100){syncing=false;syncProgress=100;store.put("synced",true);message("✓ 同步完成。录音已出现在手机中，可以回听。 ");}else{render();advanceSync();}},450);}
    private void failSync(){syncing=false;store.put("sync_failed",true);message("! 同步未完成。录音仍保存在记忆珠中，靠近手机后点“重新同步”。");}
    private void record(){
        back("录音详情",()->show("home"));
        text(body,"今天 08:42 · 18 秒",16,SUB,false);gap(body,8);
        text(body,"一段在旧电影院想起的话",23,INK,true);gap(body,16);
        player(body);
        LinearLayout c=card(body);text(c,"语音转写",21,INK,true);gap(c,6);text(c,"演示文案 · 等待官方语音识别 SDK 接入",16,WARN,false);gap(c,12);
        text(c,transcript(),18,INK,false);gap(c,14);smallAction(c,"修改转写文字",()->editTranscript());
        LinearLayout place=card(body);text(place,"地点线索",20,INK,true);gap(place,8);
        text(place,placeSummary(),17,SUB,false);gap(place,12);
        smallAction(place,"查看地点依据或搜索确认",()->show("place"));
        if(!saved()){addButton(body,"整理成记忆",true,()->show("edit"));gap(body,10);smallAction(body,"只保存原声，稍后整理",()->message("原声已保留在“录音”。整理和分享都可以稍后进行。"));}
        else addButton(body,"查看已保存的记忆",true,()->show("memory"));
    }
    private void place(){
        back("地点绑定",()->show("record"));
        text(body,"先看依据，再决定地点",17,SUB,false);gap(body,16);
        JSONObject data=placeData();JSONObject binding=data.optJSONObject("placeBinding");
        if(binding!=null){
            LinearLayout bound=card(body);text(bound,placeSummary(),21,GREEN,true);gap(bound,8);
            text(bound,binding.optString("evidence",""),17,INK,false);gap(bound,8);
            if(!binding.optString("mapAddress","").isEmpty())text(bound,"高德当前地址："+binding.optString("mapAddress"),16,SUB,false);
            text(bound,"坐标："+binding.optString("longitude")+", "+binding.optString("latitude")+" · "+binding.optString("coordinateSystem"),16,SUB,false);
        }
        LinearLayout search=card(body);text(search,"讲述中提到的地点",20,INK,true);gap(search,8);
        EditText mention=input(data.optString("placeMention","旧电影院"),1);mention.setSingleLine(true);search.addView(mention,lp(-1,-2));gap(search,8);
        text(search,"搜索结果是高德当前地图候选，不能单独证明当年故事发生地。",16,SUB,false);gap(search,14);
        addButton(search,"搜索地点候选",true,()->{
            String query=mention.getText().toString().trim();if(query.length()<2){mention.setError("请填写至少两个字");return;}
            String id=store.get("backend_recording_id","");
            if(id.isEmpty()){
                JSONObject payload=new JSONObject();try{payload.put("transcript",transcript());payload.put("placeMention",query);}catch(Exception ignored){}
                backend.post("/v1/recordings",payload,(result,error)->{
                    if(error!=null){message("地点搜索未完成："+error+" 录音仍在本机。");return;}
                    storePlaceResponse(result);message("已找到候选，请核对名称和地址后确认绑定。 ");
                });
            }else{
                JSONObject payload=new JSONObject();try{payload.put("query",query);}catch(Exception ignored){}
                backend.post("/v1/recordings/"+id+"/places/search",payload,(result,error)->{
                    if(error!=null){message("地点搜索未完成："+error+" 原有录音与绑定未改变。");return;}
                    storePlaceResponse(result);message("候选已更新，请确认具体地点。 ");
                });
            }
        });
        JSONArray candidates=data.optJSONArray("candidates");
        if(candidates!=null&&candidates.length()>0){
            text(body,"待确认候选",22,INK,true);gap(body,10);
            for(int i=0;i<candidates.length();i++){
                JSONObject item=candidates.optJSONObject(i);if(item==null)continue;
                LinearLayout c=card(body);text(c,item.optString("name","未知地点"),20,INK,true);gap(c,7);
                text(c,item.optString("city","")+item.optString("district","")+" "+item.optString("address",""),16,SUB,false);gap(c,7);
                text(c,item.optString("evidence",""),16,SUB,false);gap(c,12);
                String candidateId=item.optString("candidateId","");
                smallAction(c,"确认绑定此地点",()->new AlertDialog.Builder(this).setTitle("确认这是故事中的地点？")
                        .setMessage(item.optString("name")+"\n"+item.optString("address")+"\n请依据讲述内容核对。")
                        .setPositiveButton("确认绑定",(dialog,which)->{
                            JSONObject payload=new JSONObject();try{payload.put("candidateId",candidateId);}catch(Exception ignored){}
                            backend.post("/v1/recordings/"+store.get("backend_recording_id","")+"/places/confirm",payload,(result,error)->{
                                if(error!=null){message("绑定未完成："+error);return;}
                                storePlaceResponse(result);message("✓ 地点已由你确认并绑定。 ");
                            });
                        }).setNegativeButton("再看看",null).show());
            }
        }
        LinearLayout gpsCard=card(body);text(gpsCard,"记忆珠 GPS 路径",20,INK,true);gap(gpsCard,8);
        text(gpsCard,"正式版读取录音豆同次录音的 GPS。当前可手动输入 WGS84 坐标演示自动绑定，绝不冒充真实设备采集。",16,SUB,false);gap(gpsCard,12);
        EditText lon=input("",1);lon.setSingleLine(true);lon.setHint("经度，例如 121.473700");lon.setInputType(android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL|android.text.InputType.TYPE_NUMBER_FLAG_SIGNED);gpsCard.addView(lon,lp(-1,-2));gap(gpsCard,8);
        EditText lat=input("",1);lat.setSingleLine(true);lat.setHint("纬度，例如 31.230400");lat.setInputType(android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL|android.text.InputType.TYPE_NUMBER_FLAG_SIGNED);gpsCard.addView(lat,lp(-1,-2));gap(gpsCard,12);
        smallAction(gpsCard,"用演示 GPS 绑定",()->{
            double longitude,latitude;try{longitude=Double.parseDouble(lon.getText().toString());latitude=Double.parseDouble(lat.getText().toString());}
            catch(Exception e){lon.setError("请填写有效经纬度");return;}
            String query=mention.getText().toString().trim();if(query.length()<2){mention.setError("请先填写讲述地点");return;}
            JSONObject gps=new JSONObject(),payload=new JSONObject();
            try{gps.put("longitude",longitude);gps.put("latitude",latitude);gps.put("coordinateSystem","WGS84");gps.put("source","demo_manual");
                gps.put("sampledAt",java.time.OffsetDateTime.now().toString());payload.put("gps",gps);payload.put("placeMention",query);}catch(Exception ignored){}
            String id=store.get("backend_recording_id","");
            String path;
            if(id.isEmpty()){try{payload.put("transcript",transcript());payload.put("placeMention",query);}catch(Exception ignored){}path="/v1/recordings";}
            else path="/v1/recordings/"+id+"/places/gps";
            backend.post(path,payload,(result,error)->{
                if(error!=null){message("GPS 演示绑定未完成："+error+" 本机录音未受影响。");return;}
                storePlaceResponse(result);message("✓ 已按演示 GPS 坐标绑定，并标明了坐标来源。 ");
            });
        });
    }
    private void player(LinearLayout parent){
        LinearLayout c=card(parent);text(c,"原声优先",21,INK,true);gap(c,6);
        text(c,"当前播放的是演示配音，非真实录音。接入设备后替换为独立原声文件。",16,SUB,false);gap(c,14);
        playButton=button("▶ 播放演示配音",true,this::play);c.addView(playButton,lp(-1,-2));gap(c,12);
        progressBar=new SeekBar(this);progressBar.setMinHeight(dp(48));c.addView(progressBar,lp(-1,48));
        progressText=label("00:00 / "+formatTime(demoDuration()),16,SUB,false);c.addView(progressText,lp(-1,-2));
        progressBar.setMax(demoDuration());
        progressBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onStartTrackingTouch(SeekBar s){} public void onStopTrackingTouch(SeekBar s){}
            public void onProgressChanged(SeekBar s,int value,boolean user){if(user&&media!=null){media.seekTo(value);updateProgress();}}
        });
    }
    private void play(){
        try {
            if(media==null){media=MediaPlayer.create(this,R.raw.demo_voice);if(media==null)throw new IllegalStateException("no audio decoder");
                media.setOnCompletionListener(m->{m.seekTo(0);updateProgress();if(playButton!=null)playButton.setText("▶ 再听一次");});}
            if(media.isPlaying()){media.pause();if(playButton!=null)playButton.setText("▶ 继续播放");}
            else {media.start();if(playButton!=null)playButton.setText("Ⅱ 暂停播放");tickProgress();}
        } catch(Exception e){releaseAudio();message("演示配音播放失败，请稍后重试。录音与文字状态未受影响。 ");}
    }
    private int demoDuration(){MediaPlayer sample=MediaPlayer.create(this,R.raw.demo_voice);if(sample==null)return 1;int n=sample.getDuration();sample.release();return Math.max(1,n);}
    private String formatTime(int ms){int sec=ms/1000;return String.format(java.util.Locale.ROOT,"%02d:%02d",sec/60,sec%60);}
    private void updateProgress(){if(media==null)return;if(progressBar!=null)progressBar.setProgress(media.getCurrentPosition());if(progressText!=null)progressText.setText(formatTime(media.getCurrentPosition())+" / "+formatTime(media.getDuration()));}
    private void tickProgress(){handler.postDelayed(()->{if(media!=null&&media.isPlaying()){updateProgress();tickProgress();}},250);}
    private void releaseAudio(){if(media!=null){media.release();media=null;}progressBar=null;progressText=null;playButton=null;}
    private void editTranscript(){
        EditText field=new EditText(this);field.setText(transcript());field.setTextSize(18);field.setMinLines(3);
        new AlertDialog.Builder(this).setTitle("修改转写文字").setView(field)
                .setPositiveButton("保存修改",(d,w)->{store.put("transcript",field.getText().toString());message("转写文字已修改，演示音频未被覆盖。 ");})
                .setNegativeButton("取消",null).show();
    }
    private void edit(){
        back("整理成记忆",()->show("record"));
        text(body,"AI 整理演示 · 可修改",16,WARN,true);gap(body,8);
        text(body,"官方语音与大模型 SDK 尚未提供。以下草稿和线索是预设演示内容，请用户核对后保存。",17,SUB,false);gap(body,16);
        LinearLayout c=card(body);text(c,"故事标题",18,INK,true);gap(c,6);
        EditText titleInput=input(title(),1);c.addView(titleInput,lp(-1,-2));gap(c,16);
        text(c,"故事内容",18,INK,true);gap(c,6);EditText storyInput=input(story(),4);c.addView(storyInput,lp(-1,-2));gap(c,8);
        text(c,"可删减或留空；原声会单独保留。",16,SUB,false);gap(c,18);
        text(c,"线索待确认",18,INK,true);gap(c,8);
        text(c,"? 人物：爷爷 · 事件：看电影",17,SUB,false);gap(c,5);
        text(c,placeSummary(),17,SUB,false);gap(c,16);
        addButton(c,"保存记忆",true,()->{
            if(titleInput.getText().toString().trim().isEmpty()){titleInput.setError("请填写标题");return;}
            store.put("title",titleInput.getText().toString().trim());store.put("story",storyInput.getText().toString().trim());store.put("saved",true);
            page="memory";message("✓ 已保存到“记忆”，仅自己可见。分享需要单独选择。 ");
        });
    }
    private EditText input(String value,int minLines){EditText e=new EditText(this);e.setText(value);e.setTextSize(18);e.setTextColor(INK);e.setMinLines(minLines);e.setPadding(dp(12),dp(10),dp(12),dp(10));e.setBackground(outline(WHITE,16));return e;}
    private void memories(){
        header("记忆","只显示你主动整理保存的故事");
        EditText search=input("",1);search.setSingleLine(true);search.setHint("搜索标题或内容");body.addView(search,lp(-1,-2));gap(body,12);
        LinearLayout filters=horizontal();body.addView(filters,lp(-1,-2));
        for(String f:new String[]{"全部","人物","地点"}){
            TextView chip=label(f,16,filter.equals(f)?WHITE:GREEN,true);chip.setGravity(Gravity.CENTER);chip.setMinHeight(dp(48));chip.setPadding(dp(12),dp(4),dp(12),dp(4));chip.setBackground(shape(filter.equals(f)?GREEN:WHITE,20));
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(48),1);p.rightMargin=dp(8);filters.addView(chip,p);chip.setOnClickListener(v->{filter=f;show("memories");});
        }
        gap(body,18);
        LinearLayout results=vertical();body.addView(results,lp(-1,-2));
        Runnable refresh=()->renderMemoryResults(results,search.getText().toString());
        search.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int c,int a){}public void onTextChanged(CharSequence s,int st,int before,int count){refresh.run();}public void afterTextChanged(android.text.Editable e){}});
        refresh.run();
    }
    private void renderMemoryResults(LinearLayout results,String query){
        results.removeAllViews();
        if(!saved()||deleted()){LinearLayout c=card(results);text(c,"还没有整理好的记忆",20,INK,true);gap(c,8);text(c,"先在“录音”中听原声，再决定是否整理。",17,SUB,false);return;}
        boolean match=query.trim().isEmpty()||(title()+" "+story()).contains(query.trim());
        if(filter.equals("地点")&&!store.get("place_added",false))match=false;
        if(!match){LinearLayout c=card(results);text(c,"没有找到符合条件的记忆",18,INK,true);return;}
        LinearLayout c=card(results);text(c,title(),22,INK,true);gap(c,7);text(c,"今天 08:42 · 原声 18 秒",16,SUB,false);gap(c,8);
        text(c,story(),18,INK,false);gap(c,10);text(c,store.get("shared",false)?"✓ 已分享给女儿":"◎ 仅自己可见",16,GREEN,false);gap(c,14);
        addButton(c,"查看故事与演示配音",false,()->show("memory"));
    }
    private void memory(){
        back("记忆详情",()->show("memories"));text(body,title(),25,INK,true);gap(body,6);text(body,"今天 08:42 · 仅由你确认后保存",16,SUB,false);gap(body,17);
        player(body);
        LinearLayout c=card(body);text(c,"故事内容",21,INK,true);gap(c,8);text(c,story().isEmpty()?"仅保留原声，尚未添加故事文字。":story(),18,INK,false);gap(c,10);
        text(c,"原声与整理文字分开保存",16,SUB,false);
        LinearLayout clue=card(body);text(clue,"人物、事件与地点",21,INK,true);gap(clue,8);
        text(clue,"? 爷爷 · 看电影：演示线索，请核对",17,SUB,false);gap(clue,5);
        text(clue,placeSummary(),17,SUB,false);gap(clue,12);
        smallAction(clue,"查看地点绑定依据",()->show("place"));
        LinearLayout source=card(body);text(source,"这段故事从哪里来",21,INK,true);gap(source,8);
        text(source,"原声 1 · 今天 08:42 · 18 秒",17,INK,false);gap(source,5);
        text(source,"当前只有一段演示录音。新片段关联须由用户确认，不能自动覆盖原声。",16,SUB,false);
        gap(body,5);addButton(body,"与家人共忆",true,()->show("prompts"));gap(body,12);
        smallAction(body,"编辑记忆",()->show("edit"));gap(body,10);
        smallAction(body,store.get("shared",false)?"查看分享与撤回":"分享给家人",()->show("share"));gap(body,10);
        smallAction(body,"导出文字",this::exportText);gap(body,10);
        smallAction(body,"彻底删除",this::confirmDelete);
    }
    private void prompts(){
        back("一起共忆",()->show("memory"));
        LinearLayout c=card(body);text(c,"先听完她的讲述",22,INK,true);gap(c,10);
        text(c,"录音豆作为第三方参与家庭谈话。当前只接收参与者主动输入的文字，实时收音、说话人识别和大模型提问等待官方 SDK 下发后进行补充。",17,SUB,false);gap(c,14);
        smallAction(c,"回听演示配音",this::play);
        LinearLayout q=card(body);text(q,"可忽略的开放问题",18,SUB,true);gap(q,10);
        String question=store.get("backend_question",QUESTIONS[questionIndex]);
        text(q,"“"+question+"”",23,INK,true);gap(q,8);
        text(q,store.get("backend_question","").isEmpty()?"预设演示问题":"后端规则演示问题 · 等待官方 SDK 下发后进行补充",16,WARN,false);gap(q,14);
        smallAction(q,"换一个问题",()->{
            String session=store.get("conversation_id","");
            if(session.isEmpty()){questionIndex=(questionIndex+1)%QUESTIONS.length;render();return;}
            backend.post("/v1/conversations/"+session+"/prompts/next",new JSONObject(),(data,error)->{
                if(error!=null){message("暂时无法换题："+error);return;}
                store.put("backend_question",data.optString("question",QUESTIONS[questionIndex]));render();
            });
        });
        String session=store.get("conversation_id","");
        LinearLayout talk=card(body);text(talk,"家庭谈话演示",21,INK,true);gap(talk,8);
        if(session.isEmpty()){
            text(talk,"参与者同意后才能开始。系统不会在后台自动监听。",17,SUB,false);gap(talk,14);
            addButton(talk,"同意并开始谈话演示",true,()->new AlertDialog.Builder(this).setTitle("开始这次共忆？")
                    .setMessage("请先取得在场参与者同意。当前仅发送你主动输入的文字，不采集实时音频。")
                    .setPositiveButton("已同意，开始",(d,w)->{
                        JSONObject payload=new JSONObject();try{payload.put("consent",true);String rid=store.get("backend_recording_id","");if(!rid.isEmpty())payload.put("recordingId",rid);}catch(Exception ignored){}
                        backend.post("/v1/conversations",payload,(data,error)->{
                            if(error!=null){message("谈话未开始："+error);return;}
                            store.put("conversation_id",data.optString("id",""));message("共忆演示已开始。请主动输入长辈或家人的话。 ");
                        });
                    }).setNegativeButton("取消",null).show());
        }else{
            text(talk,"✓ 本次谈话已开始。请主动输入一轮话语。",17,GREEN,false);gap(talk,10);
            EditText turn=input("",3);turn.setHint("输入刚才讲述的话");talk.addView(turn,lp(-1,-2));gap(talk,12);
            addButton(talk,"提交长辈讲述并获取提问",true,()->submitTurn("elder",turn.getText().toString()));gap(talk,10);
            smallAction(talk,"提交家人补充",()->submitTurn("family",turn.getText().toString()));
        }
        gap(body,3);text(body,"问题由家人决定是否问出口；系统不会替长辈回答。",17,SUB,false);
    }
    private void submitTurn(String speaker,String value){
        String content=value.trim();if(content.isEmpty()){message("请先输入本轮讲述文字。 ");return;}
        String session=store.get("conversation_id","");
        JSONObject payload=new JSONObject();try{payload.put("speaker",speaker);payload.put("text",content);}catch(Exception ignored){}
        backend.post("/v1/conversations/"+session+"/turns",payload,(data,error)->{
            if(error!=null){message("讲述未提交："+error);return;}
            if(speaker.equals("family")){message("家人的补充已记入这次演示谈话。 ");return;}
            backend.post("/v1/conversations/"+session+"/prompts/next",new JSONObject(),(prompt,promptError)->{
                if(promptError!=null){message("讲述已保存，但提问暂不可用："+promptError);return;}
                store.put("backend_question",prompt.optString("question",QUESTIONS[questionIndex]));
                message("长辈讲述已保存。下方是可忽略的开放问题。 ");
            });
        });
    }
    private void share(){
        back("分享范围",()->show("memory"));
        if(store.get("shared",false)){
            LinearLayout c=card(body);text(c,"✓ 已分享给女儿",22,INK,true);gap(c,7);text(c,store.get("share_audio",false)?"范围：整理文字 + 演示配音":"范围：仅整理文字",17,SUB,false);gap(c,15);
            addButton(c,"撤回分享",false,()->new AlertDialog.Builder(this).setTitle("撤回这条分享？")
                    .setMessage("女儿将不能再通过此分享查看内容，本机的记忆和原声不会删除。")
                    .setPositiveButton("撤回",(d,w)->{store.put("shared",false);message("分享已撤回，本机记忆仍安全保存。 ");})
                    .setNegativeButton("取消",null).show());return;
        }
        LinearLayout c=card(body);text(c,"选择要分享的内容",21,INK,true);gap(c,10);
        text(c,"接收对象：女儿（演示联系人）",17,SUB,false);gap(c,6);text(c,"默认仅自己可见；请确认分享范围。",16,SUB,false);gap(c,18);
        addButton(c,"仅分享整理文字",false,()->confirmShare(false));gap(c,10);
        addButton(c,"分享文字和演示配音",true,()->confirmShare(true));
        gap(body,3);text(body,"真实家庭账号和权限控制需在后端接入后启用。当前分享仅保存在本机演示状态中。",16,WARN,false);
    }
    private void confirmShare(boolean audio){new AlertDialog.Builder(this).setTitle("确认分享给女儿？")
            .setMessage("范围："+(audio?"整理文字和演示配音":"仅整理文字")+"。这是本机演示，不会发送到外部。")
            .setPositiveButton("确认分享",(d,w)->{store.put("shared",true);store.put("share_audio",audio);message("✓ 已更新本机分享演示状态，可随时撤回。 ");})
            .setNegativeButton("取消",null).show();}
    private void exportText(){Intent i=new Intent(Intent.ACTION_SEND);i.setType("text/plain");i.putExtra(Intent.EXTRA_SUBJECT,title());i.putExtra(Intent.EXTRA_TEXT,title()+"\n\n"+story()+"\n\n转写："+transcript()+"\n\n演示内容；真实原声需接入设备后导出。");startActivity(Intent.createChooser(i,"导出记忆文字"));}
    private void confirmDelete(){new AlertDialog.Builder(this).setTitle("彻底删除这条演示记忆？")
            .setMessage("将删除本机及已连接服务中的这条录音地点数据、共忆文字和整理内容，并撤回本机分享演示状态。操作无法撤销。")
            .setPositiveButton("彻底删除",(d,w)->deleteRemoteThen(()->{
                store.put("deleted",true);store.put("saved",false);store.put("shared",false);
                store.put("place_added",false);store.put("backend_recording_id","");store.put("backend_place_json","{}");
                store.put("backend_question","");page="home";message("演示录音及其关联数据已删除。 ");
            }))
            .setNegativeButton("取消",null).show();}
    private void deleteRemoteThen(Runnable success){
        String session=store.get("conversation_id","");
        if(!session.isEmpty()){
            backend.delete("/v1/conversations/"+session,(data,error)->{
                if(error!=null&&!error.contains("not found")){message("服务器删除未完成："+error+" 本机数据仍保留，请重试。 ");return;}
                store.put("conversation_id","");deleteRemoteRecordingThen(success);
            });
        }else deleteRemoteRecordingThen(success);
    }
    private void deleteRemoteRecordingThen(Runnable success){
        String id=store.get("backend_recording_id","");
        if(id.isEmpty()){success.run();return;}
        backend.delete("/v1/recordings/"+id,(data,error)->{
            if(error!=null&&!error.contains("not found")){message("服务器删除未完成："+error+" 本机数据仍保留，请重试。 ");return;}
            store.put("backend_recording_id","");success.run();
        });
    }
    private void mine(){
        header("我的","设备、地点与隐私由你掌控");
        LinearLayout device=card(body);text(device,"记忆珠连接",21,INK,true);gap(device,8);
        text(device,connected()?"✓ 演示设备已连接":"○ 演示设备未连接",17,SUB,false);gap(device,14);
        smallAction(device,connected()?"管理连接":"连接记忆珠",()->show("connect"));
        LinearLayout location=card(body);text(location,"地点记录",21,INK,true);gap(location,8);
        text(location,store.get("location_pref",false)?"录音后按次添加":"关闭（默认）",18,INK,true);gap(location,6);
        text(location,"不会持续追踪行动轨迹；每段录音单独决定。",16,SUB,false);gap(location,14);
        smallAction(location,store.get("location_pref",false)?"关闭地点入口":"允许按次添加地点",()->{store.put("location_pref",!store.get("location_pref",false));message("地点设置已更新。每段录音仍需单独添加。 ");});
        LinearLayout privacy=card(body);text(privacy,"隐私与数据",21,INK,true);gap(privacy,8);
        text(privacy,"录音默认仅自己可见，分享需要逐条确认。",17,SUB,false);gap(privacy,14);
        smallAction(privacy,"重置演示数据",()->new AlertDialog.Builder(this).setTitle("重置演示？")
                .setMessage("会先删除本次演示在服务器上的地点与共忆数据，再清除本机状态并恢复初始待同步录音。连接配置会保留。")
                .setPositiveButton("重置",(d,w)->deleteRemoteThen(()->{store.reset();page="home";message("演示已重置。 ");}))
                .setNegativeButton("取消",null).show());
        LinearLayout server=card(body);text(server,"后端服务",21,INK,true);gap(server,8);
        text(server,"地点搜索与谈话演示可连接 HTTPS 服务。令牌只存在本机应用私有空间；录音配音文件不会上传。",16,SUB,false);gap(server,12);
        text(server,"服务地址",17,INK,true);gap(server,6);
        EditText endpoint=input(store.get("backend_url",""),1);endpoint.setSingleLine(true);endpoint.setHint("https://api.example.com/xunyi");server.addView(endpoint,lp(-1,-2));gap(server,12);
        text(server,"访问令牌",17,INK,true);gap(server,6);
        EditText token=input(store.get("backend_token",""),1);token.setSingleLine(true);token.setHint("粘贴服务访问令牌");
        token.setTransformationMethod(android.text.method.PasswordTransformationMethod.getInstance());server.addView(token,lp(-1,-2));gap(server,12);
        smallAction(server,"保存连接配置",()->{
            String url=endpoint.getText().toString().trim();String secret=token.getText().toString().trim();
            if(!BackendConfig.valid(url)||url.isEmpty()){endpoint.setError("请输入 HTTPS 地址");return;}
            if(secret.length()<32){token.setError("请输入有效访问令牌");return;}
            store.put("backend_url",url);store.put("backend_token",secret);
            message("连接配置已保存在本机。地点和谈话操作会使用此服务。 ");
        });
    }
    private void connect(){
        back("连接记忆珠",()->show("mine"));
        LinearLayout c=card(body);text(c,"将记忆珠放在手机旁边",22,INK,true);gap(c,10);
        text(c,"这里演示连接流程。真实设备搜索、蓝牙权限和绑定等待硬件协议提供。",17,SUB,false);gap(c,18);
        addButton(c,connected()?"模拟断开连接":"模拟连接成功",true,()->{if(connected())new AlertDialog.Builder(this).setTitle("断开演示设备？")
                    .setMessage("已同步到本机的演示内容不会删除。")
                    .setPositiveButton("断开",(d,w)->{store.put("connected",false);page="home";message("演示设备已断开；本机内容仍保留。 ");})
                    .setNegativeButton("取消",null).show();
                else{store.put("connected",true);page="home";message("✓ 演示设备已连接。 ");}});
        gap(c,14);
        text(c,recordingDemo?"● 正在录音（挂件操作演示）":"挂件按键录音演示",18,recordingDemo?WARN:INK,true);gap(c,8);
        text(c,"此操作不调用手机麦克风，也不会生成真实录音。",16,SUB,false);gap(c,12);
        smallAction(c,recordingDemo?"结束模拟录音":"模拟按下挂件录音键",()->{
            recordingDemo=!recordingDemo;
            if(recordingDemo)message("● 挂件正在录音（演示状态）。再次点击结束。 ");
            else message("✓ 模拟录音已结束。真实硬件接入后将离线保存在记忆珠中。 ");
        });
    }
}
