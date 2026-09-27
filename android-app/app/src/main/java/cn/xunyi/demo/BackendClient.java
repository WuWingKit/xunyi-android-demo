package cn.xunyi.demo;

import android.os.Handler;
import android.os.Looper;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

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

/** Small async HTTPS client for the configured demo service. */
final class BackendClient {
    interface Callback { void done(JSONObject data, String error); }
    interface ImageCallback { void done(Bitmap image, String error); }
    private final ExecutorService executor=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    BackendClient(DemoStore store){}
    boolean configured(){return !DemoCredential.TOKEN.isEmpty();}
    void get(String path,Callback callback){request("GET",path,null,callback);}
    void post(String path,JSONObject payload,Callback callback){request("POST",path,payload,callback);}
    void delete(String path,Callback callback){request("DELETE",path,null,callback);}
    private void request(String method,String path,JSONObject payload,Callback callback){
        final String base=BackendConfig.DEFAULT_URL;
        String token=DemoCredential.TOKEN;
        if(token.isEmpty()){
            main.post(()->callback.done(null,"服务尚未配置。"));return;
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
    void image(String path,ImageCallback callback){
        executor.execute(()->{
            Bitmap bitmap=null;String error=null;HttpURLConnection connection=null;
            try{
                connection=(HttpURLConnection)new URL(BackendConfig.DEFAULT_URL+path).openConnection();
                connection.setConnectTimeout(7000);connection.setReadTimeout(9000);
                connection.setRequestProperty("Authorization","Bearer "+DemoCredential.TOKEN);
                if(connection.getResponseCode()!=200)throw new Exception("image status");
                try(InputStream in=connection.getInputStream()) { bitmap=BitmapFactory.decodeStream(in); }
                if(bitmap==null)throw new Exception("image decode");
            }catch(Exception ex){error="地图暂时无法加载，地点文字仍可查看。";}
            finally{if(connection!=null)connection.disconnect();}
            final Bitmap found=bitmap;final String failure=error;
            main.post(()->callback.done(found,failure));
        });
    }
    void close(){executor.shutdownNow();}
}
