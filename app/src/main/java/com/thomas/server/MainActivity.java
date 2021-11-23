package com.thomas.server;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.databinding.DataBindingUtil;

import android.content.Intent;
import android.graphics.PixelFormat;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.DisplayMetrics;
import android.util.SparseArray;
import android.view.SurfaceHolder;
import android.view.WindowManager;
import android.widget.Toast;

import com.thomas.server.databinding.ActivityMainBinding;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 硬解码的方式对手机要求可能较高，低于API21卡顿?
 */
public class MainActivity extends AppCompatActivity {

    private ActivityMainBinding binding;
    private ServerSocket mServerSocket ;
    private ExecutorService mExecutorService;
    private SurfaceHolder mSurfaceHolder;
    private MediaCodec mediaCodec;
    private int mCount = 0;
    // 存放连接的客户端
    private SparseArray<Socket> mClientSockets;

    private Handler mHandler = new Handler(new Handler.Callback() {
        @Override
        public boolean handleMessage(@NonNull Message msg) {
            switch (msg.what){
                case 0:
                    Toast.makeText(getApplicationContext(), R.string.client_close, Toast.LENGTH_LONG).show();
                    break;
                case 1:
                    Toast.makeText(getApplicationContext(), R.string.timeout, Toast.LENGTH_LONG).show();
                    break;
                case 2:
                    Toast.makeText(getApplicationContext(), R.string.server_close, Toast.LENGTH_LONG).show();
                    break;
            }
            return false;
        }
    });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 保持屏幕常亮
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        binding = DataBindingUtil.setContentView(this,R.layout.activity_main);
        mClientSockets = new SparseArray<>();
        initSurfaceHolder();
        initAcceptClientServer();
        Intent intent = new Intent(this,ServerService.class);
        startService(intent);
    }

    private void initAcceptClientServer() {
        mExecutorService = Executors.newSingleThreadExecutor();
        mExecutorService.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    mServerSocket = new ServerSocket(6789);
                    while (!mServerSocket.isClosed()){
                        Socket socketClient = mServerSocket.accept();
                        try {
                            // 这里我先实现只连接一台
                            Socket lastSocket = mClientSockets.get(1);
                            if (lastSocket != null){
                                lastSocket.close();
                                mClientSockets.remove(1);
                            }
                        }catch (IOException e){
                            System.out.println("last socket IOException" + e.getMessage());
                        }
                        mClientSockets.put(1,socketClient);
                        acceptClient(socketClient);
                    }
                } catch (IOException e) {
                    System.out.println("accept socket IOException\t" + e.getMessage());
                }
            }

        });
    }


    private void initSurfaceHolder() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        binding.surfaceView.getHolder().setFixedSize(dm.widthPixels, dm.heightPixels);
        //binding.surfaceView.getHolder().setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
        mSurfaceHolder = binding.surfaceView.getHolder();
        // 优化花屏
        mSurfaceHolder.setFormat(PixelFormat.RGBX_8888);
        mSurfaceHolder.addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(@NonNull SurfaceHolder holder) {
                createMediaCodec(holder);
            }

            @Override
            public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {

            }

            @Override
            public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
                if(mediaCodec != null) {
                    mediaCodec.reset();
                    mediaCodec.stop();
                    mediaCodec.release();
                    mediaCodec = null;
                }
            }
        });
    }

    private void createMediaCodec(SurfaceHolder holder) {
        try {
            //通过多媒体格式名创建一个可用的解码器
            mediaCodec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            //初始化编码器
            MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 720, 1280);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
            // 指定解码后的帧格式解码器将编码的帧解码为这种指定的格式,YUV420Flexible是几乎所有解码器都支持的
            // format.setInteger(MediaFormat.KEY_FRAME_RATE, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            // format   如果为解码器，此处表示输入数据的格式；如果为编码器，此处表示输出数据的格式。
            // surface   指定一个surface，可用作decode的输出渲染。
            // crypto    如果需要给媒体数据加密，此处指定一个crypto类.
            // flags  如果正在配置的对象是用作编码器，此处加上CONFIGURE_FLAG_ENCODE 标签。
            mediaCodec.configure(format, holder.getSurface(), null, 0);
            mediaCodec.start();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }


    /**
     *  accept for client
     */
    private void acceptClient(final Socket socketClient) {
        InputStream inputStream = null;
        try {
            socketClient.setKeepAlive(true);
            // 根据实际情况设置你要的超时时间
            socketClient.setSoTimeout(10000);
            // 实际要读取的帧大小
            int frameSize;
            inputStream = socketClient.getInputStream();
            while (!socketClient.isClosed()){
                byte[] bytes = new byte[8];
                int len = inputStream.read(bytes);
                if (len < 0){
                    break;
                }
                if (len > 0 ) {
                    // 这里是我定义的包头数据
                    if (bytes[0] == -1 && bytes[1] == -1 && bytes[2] == -1 && bytes[3] == -1){
                        frameSize = (bytes[7] & 0xFF |
                                (bytes[6] & 0xFF) << 8 |
                                (bytes[5] & 0xFF) << 16 |
                                (bytes[4] & 0xFF) << 24);
                        byte[] buff = readFrameByte(inputStream, frameSize);
                        showOnFrame(buff);
                    }else {
                        System.out.println("do something");
                    }

                }
            }
        } catch (IOException e) {
            if (e instanceof SocketTimeoutException){
                mHandler.sendEmptyMessage(1);
            }else if (e instanceof SocketException){
                mHandler.sendEmptyMessage(2);
            }else {
                System.out.println("IOException\t" + e.getMessage());
            }
        }finally {
            try {
                if (inputStream != null){
                    if (!socketClient.isClosed()){
                        mHandler.sendEmptyMessage(0);
                    }
                    inputStream.close();
                }
                socketClient.close();
            } catch (IOException ex) {
                ex.printStackTrace();
            }

        }
    }

    /**
     * @param data 帧数据
     */
    @SuppressWarnings("deprecation")
    public void showOnFrame(byte[] data) {
        if (data == null)
            return;
        int length = data.length;
        // 拿到输入缓冲区,用于传送数据进行编码
        ByteBuffer[] inputBuffers = mediaCodec.getInputBuffers();
        //返回一个填充了有效数据的input buffer的索引，如果没有可用的buffer则返回-1.当timeoutUs==0时，该方法立即返回；当timeoutUs<0时，
        // 无限期地等待一个可用的input buffer;当timeoutUs>0时，至多等待timeoutUs微妙
        //  <-1时,无限期地等待一个可用的input buffer  会出现:一直等待导致加载异常, 甚至会吃掉网络通道, 没有任何异常出现...
        //  (调试中大部分是因为sps和pps没有写入到解码器, 保证图像信息的参数写入解码器很重要)
        int inputBufferIndex = mediaCodec.dequeueInputBuffer(-1);
        // 当输入缓冲区有效时,就是>=0
        if (inputBufferIndex >= 0) {
            ByteBuffer inputBuffer = inputBuffers[inputBufferIndex];
            inputBuffer.clear();
            // 往输入缓冲区写入数据,关键点
            inputBuffer.put(data, 0, length);
            mediaCodec.queueInputBuffer(inputBufferIndex, 0, length, (mCount * 1000), 1);
            mCount++;
        }

        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        // 拿到输出缓冲区的索引
        int outputBufferIndex = mediaCodec.dequeueOutputBuffer(bufferInfo, 0);
        while (outputBufferIndex >= 0) {
            mediaCodec.releaseOutputBuffer(outputBufferIndex, true);//显示并释放资源
            outputBufferIndex = mediaCodec.dequeueOutputBuffer(bufferInfo, 0);//再次获取数据，如果没有数据输出则outIndex=-1 循环结束
        }
    }

    /**
     * 读取下一次的帧数据
     * @param inputStream 输入流
     * @param readSize 要读取的大小
     * @return byte
     * @throws IOException 异常信息
     */
    private byte[] readFrameByte(InputStream inputStream, int readSize) throws IOException {
        // waiting for real frame
        byte[] buff = new byte[readSize];
        int len = 0;
        int eachLength;
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        while (len < readSize) {
            eachLength = inputStream.read(buff);
            if (eachLength != -1) {
                len += eachLength;
                byteArrayOutputStream.write(buff, 0, eachLength);
            } else {
                // something happen
                mHandler.sendEmptyMessage(0);
                break;
            }
            if (len < readSize) {
                buff = new byte[readSize - len];
            }
        }
        byte[] b = byteArrayOutputStream.toByteArray();
        byteArrayOutputStream.close();
        return b;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Intent intent = new Intent(this,ServerService.class);
        stopService(intent);
        mHandler.removeCallbacksAndMessages(null);
        if (mServerSocket != null){
            try {
                mServerSocket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        mExecutorService.shutdownNow();
        // 也可以在这里做对MediaCodec的释放，但是会自动调用surfaceDestroyed
        mSurfaceHolder.getSurface().release();
        mSurfaceHolder = null;
        clearSocketClient();
    }

    private void clearSocketClient() {
        if (mClientSockets.size() > 0){
            Socket socket = mClientSockets.get(1);
            try {
                socket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        mClientSockets.clear();
    }

}