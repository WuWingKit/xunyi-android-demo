package cn.xunyi.demo;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Small async HTTPS client. Server credentials are entered on-device, never compiled into the APK. */
final class BackendClient {
    interface Callback { void done(JSONObject data, String error); }
    private final DemoStore store;
    private final ExecutorService executor=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    BackendClient(DemoStore store){this.store=store;}
    boolean configured(){return !store.get("backend_url","").isEmpty()&&!store.get("backend_token","").isEmpty();}
    void post(String path,JSONObject payload,Callback callback){request("POST",path,payload,callback);}
    void delete(String path,Callback callback){request("DELETE",path,null,callback);}
    private void request(String method,String path,JSONObject payload,Callback callback){
        String base=store.get("backend_url","");
        String token=store.get("backend_token","");
        if(!BackendConfig.valid(base)||base.isEmpty()||token.isEmpty()){
            main.post(()->callback.done(null,"请先在“我的”配置 HTTPS 服务地址和访问令牌。"));return;
        }
        executor.execute(()->{
            JSONObject result=null;String error=null;HttpURLConnection connection=null;
            try{
                URL url=new URL(base.replaceAll("/+$","")+path);
                connection=(HttpURLConnection)url.openConnection();
                connection.setRequestMethod(method);connection.setConnectTimeout(7000);connection.setReadTimeout(9000);
                connection.setRequestProperty("Content-Type","application/json; charset=utf-8");
                connection.setRequestProperty("Authorization","Bearer "+token);
                if(payload!=null){
                    connection.setDoOutput(true);
                    byte[] bytes=payload.toString().getBytes(StandardCharsets.UTF_8);
                    try(OutputStream out=connection.getOutputStream()){out.write(bytes);}
                }
                int code=connection.getResponseCode();
                InputStream stream=code<400?connection.getInputStream():connection.getErrorStream();
                ByteArrayOutputStream buffer=new ByteArrayOutputStream();
                if(stream!=null)try(InputStream in=stream){byte[] chunk=new byte[4096];int n;while((n=in.read(chunk))!=-1){buffer.write(chunk,0,n);if(buffer.size()>131072)throw new Exception("response too large");}}
                result=new JSONObject(buffer.toString(StandardCharsets.UTF_8.name()));
                if(code>=400)error=result.optString("error","服务返回错误 "+code);
            }catch(Exception ex){error="服务器暂不可用，请检查网络、地址和令牌后重试。";}
            finally{if(connection!=null)connection.disconnect();}
            final JSONObject data=result;final String failure=error;
            main.post(()->callback.done(data,failure));
        });
    }
    void close(){executor.shutdownNow();}
}
