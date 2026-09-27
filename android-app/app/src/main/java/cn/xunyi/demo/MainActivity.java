package cn.xunyi.demo;

import android.app.Activity;
import android.app.AlertDialog;
import android.Manifest;
import android.content.pm.PackageManager;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.speech.tts.TextToSpeech;
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
import android.widget.ImageView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.util.Locale;

public final class MainActivity extends Activity {
    private static final int BG=0xFFF7F5F2, WHITE=Color.WHITE, INK=0xFF282622,
            SUB=0xFF514E48, GREEN=0xFF526B58, SAND=0xFFE9C9A9, WARN=0xFFA84D32,
            LINE=0xFFDEDAD2;
    private static final String SAMPLE="我第一次和你爷爷看电影，就是在这里。电影院门口有一棵很大的香樟树。";
    private static final String[] QUESTIONS={"那天和爷爷一起看的是什么电影？", "散场后，你们去了哪里？", "门口那棵香樟树后来还在吗？"};
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
    private JSONArray remoteMemories=new JSONArray();
    private JSONArray remoteRecordings=new JSONArray();
    private JSONObject remoteMemory, remoteRecord, remoteConversation;
    private String selectedMemoryId="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private String selectedCandidateId="";
    private String placeReturn="record";
    private boolean loadingMemories=false;
    private MediaRecorder voiceRecorder;
    private boolean voiceActive=false;
    private long voiceStarted=0;
    private TextView voiceMeter, voiceClock;
    private String voiceSpeaker="elder";
    private String lastVoicePath="";
    private TextToSpeech voiceTts;
    private boolean ttsReady=false;
    private String pendingSpeech="";

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        store=new DemoStore(this);
        backend=new BackendClient(store);
        voiceTts=new TextToSpeech(this,status->{
            if(status==TextToSpeech.SUCCESS && voiceTts!=null){
                ttsReady=voiceTts.setLanguage(Locale.CHINA)>=0;
                if(ttsReady&&!pendingSpeech.isEmpty()){voiceTts.speak(pendingSpeech,TextToSpeech.QUEUE_FLUSH,null,"xunyi-prompt");pendingSpeech="";}
            }
        });
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        show("home");
        loadMemories();
        loadRecordings();
    }
    @Override protected void onDestroy() { stopVoiceCapture(false);releaseAudio();if(voiceTts!=null)voiceTts.shutdown();backend.close(); super.onDestroy(); }
    @Override public void onBackPressed() {
        if(page.equals("home")||page.equals("memories")||page.equals("prompts")||page.equals("mine")) { show("home"); return; }
        if(page.equals("edit")) show("record");
        else if(page.equals("place")) returnFromPlace();
        else if(page.equals("share")) show("memory");
        else if(page.equals("memory")) show("memories");
        else if(page.equals("record")) show("recordings");
        else if(page.equals("recordings")) show("home");
        else if(page.equals("atlas")) show("memories");
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
    private ImageView icon(int resource,int size,String description){
        ImageView view=new ImageView(this);view.setImageResource(resource);view.setContentDescription(description);
        view.setScaleType(ImageView.ScaleType.FIT_CENTER);view.setLayoutParams(lp(size,size));return view;
    }
    private LinearLayout card(LinearLayout to){LinearLayout v=vertical();v.setPadding(dp(20),dp(20),dp(20),dp(20));v.setBackground(shape(WHITE,26));v.setElevation(dp(3));LinearLayout.LayoutParams p=lp(-1,-2);p.bottomMargin=dp(16);to.addView(v,p);return v;}
    private Button button(String title,boolean primary,Runnable action){Button b=new Button(this);b.setText(title);b.setTextSize(18);b.setAllCaps(false);b.setMinHeight(dp(52));b.setPadding(dp(16),dp(8),dp(16),dp(8));b.setTextColor(primary?WHITE:GREEN);b.setBackground(primary?shape(GREEN,18):outline(WHITE,18));b.setOnClickListener(v->action.run());return b;}
    private void addButton(LinearLayout to,String title,boolean primary,Runnable action){to.addView(button(title,primary,action),lp(-1,-2));}
    private void smallAction(LinearLayout to,String title,Runnable action){Button b=button(title,false,action);to.addView(b,lp(-1,-2));}
    private void message(String value){notice=value;render();}
    private void loadMemories(){
        if(loadingMemories)return;loadingMemories=true;
        backend.get("/v1/memories",(data,error)->{
            loadingMemories=false;
            if(data!=null){remoteMemories=data.optJSONArray("memories");if(remoteMemories==null)remoteMemories=new JSONArray();}
            else if(error!=null)notice="记忆暂时没有更新："+error;
            render();
        });
    }
    private void loadRecordings(){
        backend.get("/v1/recordings",(data,error)->{
            if(data!=null){remoteRecordings=data.optJSONArray("recordings");
                if(remoteRecordings==null)remoteRecordings=new JSONArray();render();}
            else if(error!=null){notice="录音暂时没有更新："+error;render();}
        });
    }
    private void openRecord(String id){
        backend.get("/v1/recordings/"+id,(data,error)->{
            if(data==null){message("录音暂时无法打开："+error);return;}
            remoteRecord=data;storePlaceResponse(data);show("record");
        });
    }
    private void openMemory(String id){
        selectedMemoryId=id;
        backend.get("/v1/memories/"+id,(data,error)->{
            if(data==null){message("记忆暂时无法打开："+error);return;}
            remoteMemory=data;show("memory");
        });
    }
    private void openPlace(String recordingId){
        placeReturn="memory";selectedCandidateId="";
        backend.get("/v1/recordings/"+recordingId,(data,error)->{
            if(data==null){message("地点线索暂时无法打开："+error);return;}
            storePlaceResponse(data);show("place");ensureCandidates(data);
        });
    }
    private void ensureCandidates(JSONObject data){
        JSONArray found=data.optJSONArray("candidates");
        if(data.optJSONObject("placeBinding")!=null || (found!=null&&found.length()>0))return;
        String id=data.optString("id"),query=data.optString("placeMention","").replace(" · ","").replace("附近","");
        if(id.isEmpty()||query.length()<2)return;
        JSONObject payload=new JSONObject();
        try{payload.put("query",query);}catch(Exception ignored){}
        backend.post("/v1/recordings/"+id+"/places/search",payload,(result,error)->{
            if(result!=null){storePlaceResponse(result);if(page.equals("place"))render();}
            else if(page.equals("place"))message("地点候选暂时没有加载："+error);
        });
    }
    private void returnFromPlace(){if(placeReturn.equals("memory"))openMemory(selectedMemoryId);
        else if(remoteRecord!=null)openRecord(remoteRecord.optString("id"));else show("recordings");}
    private void map(LinearLayout target,String path){map(target,path,200);}
    private void map(LinearLayout target,String path,int height){
        ImageView image=new ImageView(this);image.setAdjustViewBounds(true);image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setBackground(shape(0xFFEAE7DF,18));
        target.addView(image,lp(-1,height));
        TextView status=label("地图加载中…",16,SUB,false);target.addView(status,lp(-1,-2));
        backend.image(path,(bitmap,error)->{if(bitmap!=null){image.setImageBitmap(bitmap);status.setVisibility(View.GONE);}
            else status.setText(error);});
    }
    private int audioResource(String asset){
        switch(asset){case "memory_1":return R.raw.memory_1;case "memory_2":return R.raw.memory_2;
            case "memory_3":return R.raw.memory_3;case "memory_4":return R.raw.memory_4;default:return 0;}
    }
    private String audioLength(int resource){
        MediaPlayer sample=MediaPlayer.create(this,resource);
        if(sample==null)return "";
        String duration=formatTime(sample.getDuration());sample.release();return duration;
    }
    private void playSource(int resource,Button button){
        try{
            releaseAudio();
            media=MediaPlayer.create(this,resource);
            if(media==null)throw new IllegalStateException("audio unavailable");
            playButton=button;button.setText("正在播放 · 点击暂停");
            media.setOnCompletionListener(m->{button.setText("▶ 再听一次");releaseAudio();});
            media.start();
            button.setOnClickListener(v->{if(media!=null&&media.isPlaying()){media.pause();button.setText("▶ 继续播放");}
                else if(media!=null){media.start();button.setText("正在播放 · 点击暂停");}});
        }catch(Exception ex){message("音频暂时无法播放。");}
    }
    private void show(String next){if(!next.equals("prompts"))stopVoiceCapture(false);releaseAudio();page=next;render();if(next.equals("prompts"))loadConversation();}
    private void loadConversation(){
        String id=store.get("conversation_id","");
        if(id.isEmpty())return;
        backend.get("/v1/conversations/"+id,(data,error)->{if(data!=null){remoteConversation=data;render();}});
    }
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
            case "recordings": recordings();break;
            case "edit": edit();break;
            case "memories": memories();break;
            case "atlas": atlas();break;
            case "memory": memory();break;
            case "share": share();break;
            case "prompts": prompts();break;
            case "mine": mine();break;
            case "connect": connect();break;
            case "place": place();break;
            default: home();
        }
        if(page.equals("home")||page.equals("memories")||page.equals("prompts")||page.equals("mine")) bottomNav();
    }
    private void header(String title,String subtitle){text(body,title,30,INK,true);gap(body,4);text(body,subtitle,16,SUB,false);gap(body,22);}
    private void back(String title,Runnable action){smallAction(body,"‹ 返回",action);gap(body,16);text(body,title,30,INK,true);gap(body,20);}
    private boolean connected(){return store.get("connected",false);}
    private boolean synced(){return store.get("synced",false);}
    private boolean saved(){return store.get("saved",false);}
    private boolean deleted(){return store.get("deleted",false);}
    private String title(){return store.get("title","第一次和爷爷看电影");}
    private String transcript(){return store.get("transcript",SAMPLE);}
    private String story(){return store.get("story","那次和爷爷去旧电影院看电影，门口有一棵很大的香樟树。具体年份尚未确认。");}
    private JSONObject placeData(){try{return new JSONObject(store.get("backend_place_json","{}"));}catch(Exception e){return new JSONObject();}}
    private String placeSummary(){
        JSONObject binding=placeData().optJSONObject("placeBinding");
        if(binding==null)return store.get("place_added",false)?"✓ 旧电影院 · 已添加":"? 地点待确认";
        String source=binding.optString("source","");
        return "✓ "+binding.optString("name","已绑定地点")+" · "+(source.equals("device_gps")?"录音时位置":source.equals("demo_gps")?"示例录音位置":"已核对地图候选");
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
        tab("⌂\n首页","home");tab("▣\n记忆","memories");tab("♡\n共忆","prompts");tab("◇\n我的","mine");
    }
    private void tab(String text,String target){
        TextView t=label(text,17,page.equals(target)?GREEN:SUB,page.equals(target));t.setGravity(Gravity.CENTER);t.setMinHeight(dp(64));
        if(page.equals(target))t.setBackground(shape(0xFFE9EFE9,16));
        nav.addView(t,new LinearLayout.LayoutParams(0,-2,1));t.setOnClickListener(v->show(target));
    }
    private void home(){
        header("寻忆","把熟悉的声音，留在家里");
        LinearLayout device=card(body);device.setBackground(shape(0xFFFFFDFC,30));
        text(device,"声音记忆珠",18,INK,true);gap(device,12);
        text(device,"○ 当前未连接",20,INK,true);gap(device,6);
        text(device,"已保存的记忆和讲述仍可浏览。",16,SUB,false);gap(device,16);
        addButton(device,"浏览家庭记忆",true,()->show("memories"));
        gap(body,4);text(body,"最近记忆",22,INK,true);gap(body,12);
        if(remoteMemories.length()>0){
            JSONObject item=remoteMemories.optJSONObject(0);
            if(item!=null){LinearLayout c=card(body);text(c,item.optString("title"),22,INK,true);gap(c,8);
                text(c,item.optString("story"),18,INK,false);gap(c,8);
                JSONObject place=item.optJSONObject("place");
                text(c,item.optInt("sourceCount")+" 段录音 · "+(place==null?"地点待确认":place.optString("name")),16,SUB,false);gap(c,12);
                addButton(c,"查看记忆",false,()->openMemory(item.optString("id")));}
        }else{LinearLayout c=card(body);text(c,"记忆正在加载",18,INK,false);}
        LinearLayout inbox=card(body);text(inbox,"讲述录音",21,INK,true);gap(inbox,8);
        text(inbox,remoteRecordings.length()+" 段讲述已收录",17,SUB,false);gap(inbox,12);
        smallAction(inbox,"查看全部录音",()->show("recordings"));
    }
    private void recordings(){
        back("讲述录音",()->show("home"));
        text(body,"每段讲述都独立保存，可一起整理成记忆。",17,SUB,false);gap(body,16);
        for(int i=0;i<remoteRecordings.length();i++){
            JSONObject item=remoteRecordings.optJSONObject(i);if(item==null)continue;
            LinearLayout c=card(body);text(c,item.optString("transcript"),18,INK,true);gap(c,8);
            text(c,item.optInt("memoryCount")+" 条关联记忆",16,SUB,false);gap(c,12);
            addButton(c,"查看讲述与地点",false,()->openRecord(item.optString("id")));
        }
        if(remoteRecordings.length()==0){LinearLayout c=card(body);text(c,"录音正在加载",18,INK,false);}
    }
    private void record(){
        back("讲述详情",()->show("recordings"));
        if(remoteRecord==null){text(body,"录音正在加载",18,INK,false);return;}
        int audio=audioResource(remoteRecord.optString("audioAsset"));
        LinearLayout voice=card(body);text(voice,"这段讲述",21,INK,true);gap(voice,10);
        text(voice,remoteRecord.optString("transcript"),18,INK,false);gap(voice,14);
        if(audio!=0){Button play=button("▶ 播放讲述 · "+audioLength(audio),true,()->{});
            play.setOnClickListener(v->playSource(audio,play));voice.addView(play,lp(-1,-2));
            gap(voice,8);text(voice,"示例讲述使用演示配音。",16,SUB,false);
        }else text(voice,"音频尚未同步",16,SUB,false);
        LinearLayout location=card(body);text(location,"地点线索",21,INK,true);gap(location,8);
        JSONObject binding=remoteRecord.optJSONObject("placeBinding");
        if(binding!=null){text(location,binding.optString("name","已确认地点"),18,INK,false);gap(location,10);
            map(location,"/v1/recordings/"+remoteRecord.optString("id")+"/places/map");gap(location,8);}
        else text(location,"还没有确认地点",17,SUB,false);
        gap(location,12);smallAction(location,"在地图上核对地点",()->{
            placeReturn="record";selectedCandidateId="";show("place");ensureCandidates(remoteRecord);
        });
    }
    private void place(){
        back("核对地点",this::returnFromPlace);
        JSONObject data=placeData();String recordingId=data.optString("id",store.get("backend_recording_id",""));
        JSONObject binding=data.optJSONObject("placeBinding");
        JSONArray candidates=data.optJSONArray("candidates");
        if(binding!=null){
            LinearLayout bound=card(body);text(bound,"✓ 已绑定 · "+binding.optString("name"),21,INK,true);gap(bound,8);
            text(bound,binding.optString("evidence",""),17,SUB,false);gap(bound,12);
            map(bound,"/v1/recordings/"+recordingId+"/places/map",300);
        }
        if(candidates!=null&&candidates.length()>0){
            if(selectedCandidateId.isEmpty()){
                JSONObject first=candidates.optJSONObject(0);
                if(first!=null)selectedCandidateId=first.optString("candidateId","");
            }
            text(body,"高德地图 · 地点候选",22,INK,true);gap(body,10);
            LinearLayout mapCard=card(body);
            map(mapCard,"/v1/recordings/"+recordingId+"/places/map",320);
            gap(mapCard,10);
            text(mapCard,"地图上的 A–E 与下方候选对应。",16,SUB,false);
            LinearLayout picks=horizontal();mapCard.addView(picks,lp(-1,-2));
            for(int i=0;i<candidates.length()&&i<5;i++){
                JSONObject item=candidates.optJSONObject(i);if(item==null)continue;
                final String id=item.optString("candidateId","");
                Button pin=button(String.valueOf((char)('A'+i)),id.equals(selectedCandidateId),()->{
                    selectedCandidateId=id;render();
                });
                LinearLayout.LayoutParams pinSize=new LinearLayout.LayoutParams(0,dp(52),1);
                pinSize.rightMargin=dp(5);picks.addView(pin,pinSize);
            }
            JSONObject selected=null;int selectedIndex=0;
            for(int i=0;i<candidates.length();i++){
                JSONObject item=candidates.optJSONObject(i);
                if(item!=null&&item.optString("candidateId").equals(selectedCandidateId)){
                    selected=item;selectedIndex=i;break;
                }
            }
            if(selected!=null){
                final JSONObject chosen=selected;final String candidateId=selectedCandidateId;
                LinearLayout focus=card(body);focus.setBackground(shape(0xFFE9EFE9,26));
                text(focus,"已选择 "+(char)('A'+selectedIndex)+" · "+selected.optString("name"),21,INK,true);gap(focus,8);
                text(focus,selected.optString("city")+selected.optString("district")+" "+selected.optString("address"),17,SUB,false);gap(focus,8);
                text(focus,selected.optString("evidence"),16,SUB,false);gap(focus,14);
                addButton(focus,"确认绑定这个地点",true,()->new AlertDialog.Builder(this)
                        .setTitle("确认故事中的地点？")
                        .setMessage(chosen.optString("name")+"\n"+chosen.optString("address"))
                        .setPositiveButton("确认绑定",(dialog,which)->{
                            JSONObject payload=new JSONObject();
                            try{payload.put("candidateId",candidateId);}catch(Exception ignored){}
                            backend.post("/v1/recordings/"+recordingId+"/places/confirm",payload,(result,error)->{
                                if(result==null){message("绑定未完成："+error);return;}
                                storePlaceResponse(result);loadMemories();message("地点已绑定到这段讲述和关联记忆。");
                            });
                        }).setNegativeButton("再看看",null).show());
            }
            text(body,"切换候选",20,INK,true);gap(body,10);
            for(int i=0;i<candidates.length();i++){
                JSONObject item=candidates.optJSONObject(i);if(item==null)continue;
                String candidateId=item.optString("candidateId","");
                char marker=(char)('A'+i);
                LinearLayout choice=card(body);
                choice.setOnClickListener(v->{selectedCandidateId=candidateId;render();});
                text(choice,marker+" · "+item.optString("name"),19,INK,true);gap(choice,5);
                text(choice,item.optString("city")+item.optString("district")+" "+item.optString("address"),16,SUB,false);gap(choice,10);
                smallAction(choice,candidateId.equals(selectedCandidateId)?"✓ 已选中":"选择这个地点",()->{
                    selectedCandidateId=candidateId;render();
                });
            }
        }else if(binding==null){
            LinearLayout loading=card(body);text(loading,"正在查找附近的地点…",18,INK,false);
        }
        LinearLayout search=card(body);text(search,"换个地名搜索",20,INK,true);gap(search,8);
        EditText mention=input(data.optString("placeMention","").replace(" · ","").replace("附近",""),1);
        mention.setSingleLine(true);mention.setHint("输入地标、街道或建筑");search.addView(mention,lp(-1,-2));gap(search,12);
        addButton(search,"搜索地图候选",false,()->{
            String query=mention.getText().toString().trim();
            if(query.length()<2){mention.setError("请填写至少两个字");return;}
            JSONObject payload=new JSONObject();try{payload.put("query",query);}catch(Exception ignored){}
            backend.post("/v1/recordings/"+recordingId+"/places/search",payload,(result,error)->{
                if(result==null){message("搜索未完成："+error);return;}
                selectedCandidateId="";storePlaceResponse(result);render();
            });
        });
    }
    private void player(LinearLayout parent){
        LinearLayout c=card(parent);text(c,"听录音",21,INK,true);gap(c,6);
        text(c,"示例录音 · 文字和声音分别保存",16,SUB,false);gap(c,14);
        playButton=button("▶ 播放录音",true,this::play);c.addView(playButton,lp(-1,-2));gap(c,12);
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
        } catch(Exception e){releaseAudio();message("播放失败，请稍后重试。录音与文字状态未受影响。 ");}
    }
    private int demoDuration(){MediaPlayer sample=MediaPlayer.create(this,R.raw.demo_voice);if(sample==null)return 1;int n=sample.getDuration();sample.release();return Math.max(1,n);}
    private String formatTime(int ms){int sec=ms/1000;return String.format(java.util.Locale.ROOT,"%02d:%02d",sec/60,sec%60);}
    private void updateProgress(){if(media==null)return;if(progressBar!=null)progressBar.setProgress(media.getCurrentPosition());if(progressText!=null)progressText.setText(formatTime(media.getCurrentPosition())+" / "+formatTime(media.getDuration()));}
    private void tickProgress(){handler.postDelayed(()->{if(media!=null&&media.isPlaying()){updateProgress();tickProgress();}},250);}
    private void releaseAudio(){if(media!=null){media.release();media=null;}if(playButton!=null&&playButton.getText().toString().startsWith("正在播放"))playButton.setText("▶ 播放讲述");progressBar=null;progressText=null;playButton=null;}
    private void editTranscript(){
        EditText field=new EditText(this);field.setText(transcript());field.setTextSize(18);field.setMinLines(3);
        new AlertDialog.Builder(this).setTitle("修改转写文字").setView(field)
                .setPositiveButton("保存修改",(d,w)->{store.put("transcript",field.getText().toString());message("文字已修改，录音仍保留。 ");})
                .setNegativeButton("取消",null).show();
    }
    private void edit(){
        back("整理成记忆",()->show("record"));
        text(body,"根据录音整理 · 可修改",17,SUB,true);gap(body,8);
        text(body,"先核对故事内容，再保存到记忆。",17,SUB,false);gap(body,16);
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
        header("记忆","家人的故事，连同原声一起保存");
        addButton(body,"打开记忆地图",true,()->show("atlas"));gap(body,16);
        EditText search=input("",1);search.setSingleLine(true);search.setHint("搜索记忆");body.addView(search,lp(-1,-2));gap(body,16);
        LinearLayout results=vertical();body.addView(results,lp(-1,-2));
        Runnable refresh=()->{
            results.removeAllViews();int count=0;String query=search.getText().toString().trim();
            for(int i=0;i<remoteMemories.length();i++){
                JSONObject item=remoteMemories.optJSONObject(i);if(item==null)continue;
                String title=item.optString("title"),story=item.optString("story");
                if(!query.isEmpty()&&!(title+story).contains(query))continue;
                count++;
                LinearLayout c=card(results);text(c,title,22,INK,true);gap(c,8);
                JSONObject place=item.optJSONObject("place");
                text(c,(place==null?"地点待确认":place.optString("name"))+" · "+item.optInt("sourceCount")+" 段原声",16,SUB,false);gap(c,8);
                text(c,story,18,INK,false);gap(c,12);
                addButton(c,"查看故事与原声",false,()->openMemory(item.optString("id")));
            }
            if(count==0){LinearLayout c=card(results);text(c,query.isEmpty()?"记忆正在加载":"没有找到相关记忆",18,INK,false);}
        };
        search.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int c,int a){}public void onTextChanged(CharSequence s,int st,int before,int count){refresh.run();}public void afterTextChanged(android.text.Editable e){}});
        refresh.run();
    }
    private void atlas(){
        back("记忆地图",()->show("memories"));
        text(body,"沿着地图，找回一家人的故事",17,SUB,false);gap(body,14);
        LinearLayout mapCard=card(body);
        map(mapCard,"/v1/memories/map",320);gap(mapCard,10);
        text(mapCard,"点击下方 A、B、C，打开对应记忆。",16,SUB,false);
        int markerIndex=0;
        for(int i=0;i<remoteMemories.length()&&i<10;i++){
            JSONObject item=remoteMemories.optJSONObject(i);if(item==null)continue;
            JSONObject place=item.optJSONObject("place");if(place==null)continue;
            char marker=(char)('A'+markerIndex++);
            LinearLayout c=card(body);text(c,marker+" · "+item.optString("title"),20,INK,true);gap(c,7);
            text(c,place.optString("name"),17,SUB,false);gap(c,12);
            addButton(c,"查看这段记忆",false,()->openMemory(item.optString("id")));
        }
    }
    private void memory(){
        back("记忆详情",()->show("memories"));
        if(remoteMemory==null){text(body,"记忆正在加载",18,INK,false);return;}
        text(body,remoteMemory.optString("title"),26,INK,true);gap(body,8);
        JSONArray sources=remoteMemory.optJSONArray("recordings");
        text(body,(sources==null?0:sources.length())+" 段讲述共同组成这条记忆",16,SUB,false);gap(body,16);
        if(sources!=null)for(int i=0;i<sources.length();i++){
            JSONObject source=sources.optJSONObject(i);if(source==null)continue;
            int audio=audioResource(source.optString("audioAsset"));
            LinearLayout c=card(body);text(c,"讲述 "+(i+1)+(audio==0?"":" · "+audioLength(audio)),20,INK,true);gap(c,8);
            text(c,source.optString("transcript"),18,INK,false);gap(c,12);
            if(audio!=0){Button playSourceButton=button("▶ 播放讲述",false,()->{});
                playSourceButton.setOnClickListener(v->playSource(audio,playSourceButton));c.addView(playSourceButton,lp(-1,-2));}
            else text(c,"音频尚未同步",16,SUB,false);
        }
        LinearLayout storyCard=card(body);text(storyCard,"故事",21,INK,true);gap(storyCard,10);
        text(storyCard,remoteMemory.optString("story"),18,INK,false);gap(storyCard,8);
        text(storyCard,"由多段讲述整理，具体时间和地点可继续核对。",16,SUB,false);
        if(remoteMemory.optBoolean("sample")){gap(storyCard,6);text(storyCard,"示例讲述使用演示配音。",16,SUB,false);}
        JSONObject place=remoteMemory.optJSONObject("place");
        if(place!=null){LinearLayout c=card(body);text(c,"记忆地点",21,INK,true);gap(c,8);
            text(c,place.optString("name"),18,INK,false);gap(c,10);
            map(c,"/v1/memories/"+selectedMemoryId+"/map");gap(c,8);
            text(c,place.optString("evidence"),16,SUB,false);gap(c,12);
            if(sources!=null&&sources.length()>0){JSONObject first=sources.optJSONObject(0);
                if(first!=null)smallAction(c,"核对录音中的地点",()->openPlace(first.optString("id")));}}
        addButton(body,"继续共忆",true,()->show("prompts"));
        gap(body,12);smallAction(body,"编辑故事",this::editRemoteMemory);gap(body,10);
        smallAction(body,"分享故事文字",this::shareRemoteMemory);gap(body,10);
        smallAction(body,"删除这条记忆",this::deleteRemoteMemory);
    }
    private void editRemoteMemory(){
        if(remoteMemory==null)return;
        LinearLayout form=vertical();form.setPadding(dp(20),dp(8),dp(20),dp(8));
        EditText titleField=input(remoteMemory.optString("title"),1);titleField.setHint("故事标题");
        EditText storyField=input(remoteMemory.optString("story"),4);storyField.setHint("故事内容");
        form.addView(titleField,lp(-1,-2));gap(form,12);form.addView(storyField,lp(-1,-2));
        new AlertDialog.Builder(this).setTitle("编辑故事").setView(form)
                .setPositiveButton("保存修改",(d,w)->{
                    String title=titleField.getText().toString().trim();
                    if(title.isEmpty()){message("请填写故事标题。");return;}
                    JSONObject payload=new JSONObject();
                    try{payload.put("title",title);payload.put("story",storyField.getText().toString().trim());}catch(Exception ignored){}
                    backend.post("/v1/memories/"+selectedMemoryId+"/edit",payload,(data,error)->{
                        if(data==null){message("修改没有保存："+error);return;}
                        remoteMemory=data;loadMemories();message("故事已保存。");
                    });
                }).setNegativeButton("取消",null).show();
    }
    private void shareRemoteMemory(){
        if(remoteMemory==null)return;
        Intent intent=new Intent(Intent.ACTION_SEND);intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_SUBJECT,remoteMemory.optString("title"));
        intent.putExtra(Intent.EXTRA_TEXT,remoteMemory.optString("title")+"\n\n"+remoteMemory.optString("story"));
        startActivity(Intent.createChooser(intent,"分享故事文字"));
    }
    private void deleteRemoteMemory(){
        if(remoteMemory==null)return;
        new AlertDialog.Builder(this).setTitle("删除这条记忆？")
                .setMessage("整理后的故事会删除，原来的讲述录音仍会保留。")
                .setPositiveButton("删除记忆",(d,w)->backend.delete("/v1/memories/"+selectedMemoryId,(data,error)->{
                    if(data==null){message("删除未完成："+error);return;}
                    remoteMemory=null;loadMemories();loadRecordings();page="memories";message("记忆已删除，讲述录音仍在。");
                })).setNegativeButton("取消",null).show();
    }
    private void sampleBubble(String speaker,String message,boolean assistant){
        LinearLayout bubble=card(body);bubble.setBackground(shape(assistant?0xFFE9EFE9:WHITE,24));
        text(bubble,speaker,16,GREEN,true);gap(bubble,8);text(bubble,message,18,INK,false);
    }
    private void beginVoiceSession(){
        new AlertDialog.Builder(this).setTitle("开始语音共忆")
                .setMessage("请确认在场家人同意录音。声音保存在这台设备上。")
                .setPositiveButton("已同意，开始",(d,w)->{
                    JSONObject payload=new JSONObject();try{payload.put("consent",true);}catch(Exception ignored){}
                    backend.post("/v1/conversations",payload,(data,error)->{
                        if(data==null){message("共忆暂时无法开始："+error);return;}
                        store.put("conversation_id",data.optString("id",""));remoteConversation=null;
                        loadConversation();startVoiceCapture();
                    });
                }).setNegativeButton("取消",null).show();
    }
    private void startVoiceCapture(){
        if(voiceActive)return;
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},301);return;
        }
        try{
            File audioFile=new File(getFilesDir(),"conversation-"+System.currentTimeMillis()+".m4a");
            lastVoicePath=audioFile.getAbsolutePath();
            voiceRecorder=new MediaRecorder();
            voiceRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            voiceRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            voiceRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            voiceRecorder.setOutputFile(audioFile.getAbsolutePath());
            voiceRecorder.prepare();voiceRecorder.start();
            voiceStarted=SystemClock.elapsedRealtime();voiceActive=true;render();updateVoiceMeter();
        }catch(Exception ex){stopVoiceCapture(false);message("麦克风没有启动，请检查录音权限。");}
    }
    @Override public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] grantResults){
        super.onRequestPermissionsResult(requestCode,permissions,grantResults);
        if(requestCode==301){
            if(grantResults.length>0&&grantResults[0]==PackageManager.PERMISSION_GRANTED)startVoiceCapture();
            else message("需要麦克风权限才能开始语音共忆。");
        }
    }
    private void updateVoiceMeter(){
        if(!voiceActive||voiceRecorder==null)return;
        int amplitude=0;try{amplitude=voiceRecorder.getMaxAmplitude();}catch(Exception ignored){}
        int bars=Math.max(1,Math.min(6,amplitude/4500+1));
        StringBuilder waves=new StringBuilder();
        for(int i=0;i<6;i++)waves.append(i<bars?"▆":"▂").append(' ');
        if(voiceMeter!=null)voiceMeter.setText(waves.toString());
        if(voiceClock!=null)voiceClock.setText("● 正在听 · "+formatTime((int)(SystemClock.elapsedRealtime()-voiceStarted)));
        handler.postDelayed(this::updateVoiceMeter,300);
    }
    private void stopVoiceCapture(boolean draft){
        if(!voiceActive&&voiceRecorder==null)return;
        voiceActive=false;
        if(voiceRecorder!=null){
            try{voiceRecorder.stop();}catch(Exception ignored){}
            try{voiceRecorder.reset();voiceRecorder.release();}catch(Exception ignored){}
            voiceRecorder=null;
        }
        voiceMeter=null;voiceClock=null;
        if(draft)showVoiceDraft();
    }
    private void showVoiceDraft(){
        String sample="那天电影散场，我和他在街上走了很久。电影院门口有一棵香樟树。";
        if(remoteMemory!=null){JSONArray sources=remoteMemory.optJSONArray("recordings");
            if(sources!=null&&sources.length()>0&&sources.optJSONObject(0)!=null)
                sample=sources.optJSONObject(0).optString("transcript",sample);}
        EditText draft=input(sample,3);
        LinearLayout form=vertical();form.setPadding(dp(20),dp(4),dp(20),dp(4));form.addView(draft,lp(-1,-2));
        new AlertDialog.Builder(this).setTitle("讲述已录下")
                .setMessage("下方是演示转写，可修改后让寻忆接着提问。")
                .setView(form)
                .setPositiveButton("生成追问",(d,w)->submitTurn(voiceSpeaker,draft.getText().toString()))
                .setNegativeButton("稍后再说",(d,w)->render()).show();
    }
    private void speakPrompt(String question){
        if(ttsReady&&voiceTts!=null)voiceTts.speak(question,TextToSpeech.QUEUE_FLUSH,null,"xunyi-prompt");
        else pendingSpeech=question;
    }
    private void playLastVoice(){
        if(lastVoicePath.isEmpty())return;
        try{
            releaseAudio();media=new MediaPlayer();media.setDataSource(lastVoicePath);media.prepare();
            media.setOnCompletionListener(m->releaseAudio());media.start();
        }catch(Exception ex){releaseAudio();message("这段声音暂时无法回听。");}
    }
    private void prompts(){
        header("共忆","一家人开口讲，寻忆接着问");
        String session=store.get("conversation_id","");
        LinearLayout voice=card(body);voice.setBackground(shape(0xFFE9EFE9,30));
        text(voice,"实时语音共忆",23,INK,true);gap(voice,12);
        LinearLayout rolesVisual=horizontal();voice.addView(rolesVisual,lp(-1,-2));
        rolesVisual.addView(icon(R.drawable.ic_elder,42,"长辈"));
        TextView elderLabel=label("长辈讲述",17,SUB,false);rolesVisual.addView(elderLabel,lp(-2,-2));
        rolesVisual.addView(icon(R.drawable.ic_recording,42,"录音"));
        TextView recordLabel=label("语音记录",17,SUB,false);rolesVisual.addView(recordLabel,lp(-2,-2));gap(voice,16);
        if(session.isEmpty()){
            text(voice,"打开麦克风，听讲述，接着追问故事细节。",18,INK,false);gap(voice,14);
            addButton(voice,"● 开始语音共忆",true,this::beginVoiceSession);
        }else{
            smallAction(voice,"本轮说话人："+(voiceSpeaker.equals("elder")?"长辈":"家人")+" · 点此切换",()->{
                voiceSpeaker=voiceSpeaker.equals("elder")?"family":"elder";render();
            });gap(voice,12);
            voiceClock=label(voiceActive?"● 正在听 · 00:00":"○ 等待讲述",20,voiceActive?WARN:INK,true);
            voice.addView(voiceClock,lp(-1,-2));gap(voice,8);
            voiceMeter=label(voiceActive?"▂ ▂ ▂ ▂ ▂ ▂":"▂ ▂ ▂ ▂ ▂ ▂",28,GREEN,true);
            voice.addView(voiceMeter,lp(-1,-2));gap(voice,14);
            addButton(voice,voiceActive?"■ 结束讲述并生成追问":"● 开始说话",true,
                    ()->{if(voiceActive)stopVoiceCapture(true);else startVoiceCapture();});
            if(!voiceActive&&!lastVoicePath.isEmpty()){gap(voice,10);smallAction(voice,"▶ 回听刚才录下的声音",this::playLastVoice);}
        }
        if(session.isEmpty()){
            text(body,"语音共忆示例",18,INK,true);gap(body,12);
            sampleBubble("长辈","那天电影散场，我和他在街上走了很久。",false);
            sampleBubble("家人","您还记得当时走的是哪条路吗？",false);
            sampleBubble("寻忆","那一路上，还有什么让您记到现在？",true);
        }
        if(remoteConversation!=null&&session.equals(remoteConversation.optString("id"))){
            JSONArray turns=remoteConversation.optJSONArray("turns");
            if(turns!=null)for(int i=0;i<turns.length();i++){
                JSONObject turn=turns.optJSONObject(i);if(turn==null)continue;
                String speaker=turn.optString("speaker");
                LinearLayout bubble=card(body);
                bubble.setBackground(shape(speaker.equals("assistant")?0xFFE9EFE9:WHITE,24));
                text(bubble,speaker.equals("assistant")?"寻忆 · 语音追问":speaker.equals("elder")?"长辈":"家人",16,GREEN,true);gap(bubble,8);
                text(bubble,turn.optString("text"),18,INK,false);
            }
        }
        if(!session.isEmpty()){
            LinearLayout talk=card(body);text(talk,"文字补充",20,INK,true);gap(talk,8);
            text(talk,"也可以补上没说完整的细节。",16,SUB,false);gap(talk,10);
            RadioGroup roles=new RadioGroup(this);roles.setOrientation(RadioGroup.HORIZONTAL);
            RadioButton elder=new RadioButton(this);elder.setId(View.generateViewId());elder.setText("长辈");
            elder.setTextSize(17);elder.setTextColor(INK);elder.setMinHeight(dp(48));
            RadioButton family=new RadioButton(this);family.setId(View.generateViewId());family.setText("家人");
            family.setTextSize(17);family.setTextColor(INK);family.setMinHeight(dp(48));
            roles.addView(elder);roles.addView(family);roles.check(elder.getId());talk.addView(roles,lp(-1,-2));gap(talk,8);
            EditText turn=input("",3);turn.setHint("输入刚才讲述的话");talk.addView(turn,lp(-1,-2));gap(talk,12);
            addButton(talk,"发送文字",false,()->submitTurn(roles.getCheckedRadioButtonId()==family.getId()?"family":"elder",turn.getText().toString()));
        }
        gap(body,3);text(body,"语音转写与模型回答等待官方 SDK 下发后接入；目前追问由演示规则生成。",16,SUB,false);
    }
    private void submitTurn(String speaker,String value){
        String content=value.trim();if(content.isEmpty()){message("请先输入本轮讲述文字。 ");return;}
        String session=store.get("conversation_id","");
        JSONObject payload=new JSONObject();try{payload.put("speaker",speaker);payload.put("text",content);}catch(Exception ignored){}
        backend.post("/v1/conversations/"+session+"/turns",payload,(data,error)->{
            if(error!=null){message("讲述未提交："+error);return;}
            if(speaker.equals("family")){loadConversation();message("家人的补充已记入这次谈话。");return;}
            backend.post("/v1/conversations/"+session+"/prompts/next",new JSONObject(),(prompt,promptError)->{
                if(promptError!=null){message("讲述已保存，但提问暂不可用："+promptError);return;}
                String question=prompt.optString("question",QUESTIONS[questionIndex]);
                store.put("backend_question",question);speakPrompt(question);
                loadConversation();
                message("寻忆接着提出了一个问题。");
            });
        });
    }
    private void share(){
        back("分享范围",()->show("memory"));
        if(store.get("shared",false)){
            LinearLayout c=card(body);text(c,"✓ 已保存分享选择",22,INK,true);gap(c,7);text(c,store.get("share_audio",false)?"范围：整理文字 + 录音":"范围：仅整理文字",17,SUB,false);gap(c,15);
            addButton(c,"撤回分享",false,()->new AlertDialog.Builder(this).setTitle("撤回这条分享？")
                    .setMessage("会清除本机的分享选择，记忆和录音仍会保留。")
                    .setPositiveButton("撤回",(d,w)->{store.put("shared",false);message("分享选择已撤回，记忆和录音仍保留。 ");})
                    .setNegativeButton("取消",null).show());return;
        }
        LinearLayout c=card(body);text(c,"选择要分享的内容",21,INK,true);gap(c,10);
        text(c,"接收对象：女儿",17,SUB,false);gap(c,6);text(c,"默认仅自己可见；请确认分享范围。",16,SUB,false);gap(c,18);
        addButton(c,"仅分享整理文字",false,()->confirmShare(false));gap(c,10);
        addButton(c,"分享文字和录音",true,()->confirmShare(true));
        gap(body,3);text(body,"分享选择目前只保存在本机，家庭账号接入后才能送达对方。",16,SUB,false);
    }
    private void confirmShare(boolean audio){new AlertDialog.Builder(this).setTitle("确认分享给女儿？")
            .setMessage("范围："+(audio?"整理文字和录音":"仅整理文字")+"。家庭账号尚未接入，这次选择只保存在本机。")
            .setPositiveButton("保存分享选择",(d,w)->{store.put("shared",true);store.put("share_audio",audio);message("✓ 分享选择已保存，可以随时撤回。 ");})
            .setNegativeButton("取消",null).show();}
    private void exportText(){Intent i=new Intent(Intent.ACTION_SEND);i.setType("text/plain");i.putExtra(Intent.EXTRA_SUBJECT,title());i.putExtra(Intent.EXTRA_TEXT,title()+"\n\n"+story()+"\n\n转写："+transcript());startActivity(Intent.createChooser(i,"导出记忆文字"));}
    private void confirmDelete(){new AlertDialog.Builder(this).setTitle("彻底删除这条记忆？")
            .setMessage("将删除本机及已连接服务中的这条录音地点数据、共忆文字和整理内容，并清除分享选择。操作无法撤销。")
            .setPositiveButton("彻底删除",(d,w)->deleteRemoteThen(()->{
                store.put("deleted",true);store.put("saved",false);store.put("shared",false);
                store.put("place_added",false);store.put("backend_recording_id","");store.put("backend_place_json","{}");
                store.put("backend_question","");page="home";message("录音及其关联数据已删除。 ");
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
        header("我的","家庭空间与设备");
        LinearLayout family=card(body);
        LinearLayout familyHeader=horizontal();family.addView(familyHeader,lp(-1,-2));
        familyHeader.addView(icon(R.drawable.ic_elder,42,"长辈"));
        familyHeader.addView(label("家人",21,INK,true),lp(-2,-2));gap(family,10);
        text(family,"妈妈 · 女儿 · 爸爸",18,INK,false);gap(family,6);
        text(family,"每条记忆都可以从声音继续讲下去。",16,SUB,false);
        LinearLayout device=card(body);text(device,"声音记忆珠",21,INK,true);gap(device,9);
        text(device,"○ 当前未连接",18,INK,false);gap(device,8);
        text(device,"手机上已有的家庭记忆仍可浏览。",16,SUB,false);gap(device,14);
        smallAction(device,"查看设备",()->show("connect"));
        LinearLayout privacy=card(body);text(privacy,"隐私",21,INK,true);gap(privacy,8);
        text(privacy,"分享前逐条选择接收人和内容。",17,SUB,false);
        TextView version=label("寻忆 0.0.1 alpha",16,SUB,false);
        version.setGravity(Gravity.CENTER);body.addView(version,lp(-1,-2));gap(body,8);
        TextView credit=label("demo制作团队——胡荣杰、余佳欣\n黄红琳、张珈茗然",16,SUB,false);
        credit.setGravity(Gravity.CENTER);body.addView(credit,lp(-1,-2));gap(body,20);
    }
    private void connect(){
        back("声音记忆珠",()->show("mine"));
        LinearLayout c=card(body);text(c,"设备暂未连接",22,INK,true);gap(c,10);
        text(c,"连接记忆珠后，录音会出现在这里。",18,INK,false);gap(c,12);
        text(c,"设备通信等待官方 SDK 下发后进行补充。",16,SUB,false);
    }
}
