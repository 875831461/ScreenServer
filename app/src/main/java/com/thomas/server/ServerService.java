package com.thomas.server;

import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ServerService extends Service {
    private ExecutorService mExecutorService;
    private DatagramSocket mServerSocket;


    @Override
    public void onCreate() {
        super.onCreate();
        mExecutorService = Executors.newCachedThreadPool();
        initServerSocket();
    }

    public void receiveBroadcast()throws IOException{
        // 接收数据时需要指定监听的端口号
        byte[] buffer = new byte[1];
        mServerSocket = new DatagramSocket(4567);
        DatagramPacket clientPacket = new DatagramPacket(buffer , buffer.length);
        while (!mServerSocket.isClosed()){
            System.out.println("=================================");
            System.out.println("===== BEGIN ACCEPT MESSAGE =====");
            System.out.println("=================================");
            mServerSocket.receive(clientPacket);
            JSONObject jsonObject = new JSONObject();
            String manufacturer = Build.MANUFACTURER;
            String model = Build.MODEL;
            int api = Build.VERSION.SDK_INT;
            try {
                jsonObject.put("manufacturer",manufacturer);
                jsonObject.put("api",api);
                jsonObject.put("model",model);
                // 这个暂时忽略
                jsonObject.put("code",1);
                byte[] data = jsonObject.toString().getBytes();
                DatagramPacket msg = new DatagramPacket(data, data.length,
                        clientPacket.getSocketAddress());
                msg.setData(data,0,data.length);
                mServerSocket.send(msg);
            } catch (JSONException e) {
                e.printStackTrace();
            }
            System.out.println("=================================");
            System.out.println("====== END ACCEPT MESSAGE ======");
            System.out.println("=================================");
        }
    }

    private void initServerSocket() {
        mExecutorService.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    receiveBroadcast();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        });
    }



    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mServerSocket != null){
            mServerSocket.close();
        }
    }

}
