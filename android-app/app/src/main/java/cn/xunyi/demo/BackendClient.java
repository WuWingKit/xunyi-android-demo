package cn.xunyi.demo;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.UnknownHostException;
import java.net.SocketTimeoutException;
import java.net.URL;
import javax.net.ssl.SSLException;
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
    boolean configured(){return !store.get("backend_token","").isEmpty();}
    void post(String path,JSONObject payload,Callback callback){request("POST",path,payload,callback);}
    void delete(String path,Callback callback){request("DELETE",path,null,callback);}
    private void request(String method,String path,JSONObject payload,Callback callback){
        String savedBase=store.get("backend_url",BackendConfig.DEFAULT_URL);
        final String base=savedBase.isEmpty()?BackendConfig.DEFAULT_URL:savedBase;
        String token=store.get("backend_token","");
        if(!BackendConfig.valid(base)||token.isEmpty()){
            main.post(()->callback.done(null,"请先在“我的”填写服务访问令牌。"));return;
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
            }catch(UnknownHostException ex){error="网络无法解析服务地址，请检查模拟器网络后重试。";}
            catch(SocketTimeoutException ex){error="连接超时，请稍后重试。";}
            catch(SSLException ex){error="安全连接失败，请检查设备时间和服务证书。";}
            catch(Exception ex){error="无法连接服务，请检查网络后重试。";}
            finally{if(connection!=null)connection.disconnect();}
            final JSONObject data=result;final String failure=error;
            main.post(()->callback.done(data,failure));
        });
    }
    void close(){executor.shutdownNow();}
}
