package com.example.mycam;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.Manifest;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;

public class MainActivity extends AppCompatActivity {
    private final String TAG = "MainActivity";
    private TextureView mTextureView;
    private Camera2Wrapper mCamera2Wrapper;
    private AvcEncoder mAvcEncoder;
    private ExecutorService threadPool = null;
    private Socket socket;
    private int mWidth = 1280;
    private int mHeight = 720;
    private int mFrameRate = 30;
    private int mQP = 0;
    private int mBitrate = 2500000;

    private Button myBtn01 = null; // 按钮btn_connect
    private Button myBtn02 = null; // 按钮btn_trans
    private Button myBtn03 = null; // 按钮btn_switch
    private TextView myEdit01 = null;  // 编辑框edit_ip
    private static String serverIP = "192.168.123.20";
    private static final int serverPort = 6010;

    private boolean isSending = false; // 是否在发送视频中
    private boolean isConnecting = false; // 是否在连接中
    private boolean isTransmitting = false;
    private long mFrames = 0;
    private byte[] mH264Data = new byte[mWidth * mHeight * 3];

    private DataOutputStream dos;
    Thread socketThread = null;
    BlockingQueue<byte[]> queue = new LinkedBlockingQueue<>();
    private long totalSize = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        //禁止屏幕休眠
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        Log.i(TAG, "onCreate! ");

        String[] permissions = new String[]{
                Manifest.permission.CAMERA,
                Manifest.permission.INTERNET,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                Manifest.permission.READ_EXTERNAL_STORAGE};
        // Android 6.0相机动态权限检查
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, permissions, 1);
        }
        //创建Camera2Wrapper对象
        mTextureView = findViewById(R.id.preview_view);
        mCamera2Wrapper = new Camera2Wrapper(this);
        mCamera2Wrapper.initTexture(mTextureView);
        //创建初始的编码器mAvcEncoder
        mAvcEncoder = new AvcEncoder(mWidth, mHeight, mFrameRate, mQP, mBitrate);

        //创建一个线程池，用于socket的使用与释放
        threadPool = Executors.newFixedThreadPool(100);

        myBtn01 = findViewById(R.id.btn_connect);
        myBtn02 = findViewById(R.id.btn_trans);
        myBtn03 = findViewById(R.id.btn_switch);

        myBtn03.setOnClickListener(new Button.OnClickListener() {
            @Override
            public void onClick(View v) {
                mCamera2Wrapper.switchCamera();
                displayToast("摄像头切换完成！");
            }
        });

        // 获得输入框的输入
        myEdit01 = findViewById(R.id.input_IP);
        // 打印输入框的输入
        Log.i(TAG, "初始输入框的内容: " + myEdit01.getText().toString() + "   serverIP: " + serverIP);

        mCamera2Wrapper.setImageDataListener(new Camera2Wrapper.ImageDataListener() {
            @Override
            public void OnImageDataListener(byte[] data) {
                //Log.d(TAG, "OnImageDataListener start!");
                if (isTransmitting && mAvcEncoder != null){
                    ++mFrames;
                    int ret = mAvcEncoder.offerEncoder(data, mH264Data);
                    if (ret > 0) {
                        byte[] sendBuf = new byte[ret];
                        System.arraycopy(mH264Data, 0, sendBuf, 0, ret);
                        queue.add(sendBuf);
                        Log.i(TAG, "队列长度: " + queue.size());
                    }
                    Log.d(TAG, "OnImageDataListener: mFrames = " + mFrames + "  ret = " + ret + "  totalSize = " + totalSize);
                }
                //Log.d(TAG, "OnImageDataListener end!");
            }
        });


        myBtn01.setOnClickListener(new Button.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (isTransmitting){
                    isTransmitting = false;
                    myBtn01.setText("开始传输");
                    try {
                        if (socket != null && !socket.isClosed()) {
                            socket.close();
                        }
                        socket = null;
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                    if (socketThread != null && socketThread.isAlive()) {
                        Log.i(TAG, "socketThread " + socketThread.getName() + socketThread.getId() + " is alive!");
                        socketThread.interrupt();
                    }
                    socketThread = null;
                    displayToast("连接断开，传输终止!");
                    Log.i(TAG, "myBtn01 停止传输: " + serverIP + ":" + serverPort);
                }
                else {
                    String inputIP = myEdit01.getText().toString();
                    if (inputIP.length() > 0 && checkValidIP(inputIP)) {
                        serverIP = inputIP;
                        // 打印输入框的输入
                        Log.i(TAG, "输入框输入的IP: " + inputIP + "serverIP: " + serverIP);
                    } else if (inputIP.length() > 0 && !checkValidIP(inputIP)) {
                        displayToast("请输入正确的IP地址!");
                        // 打印输入框的输入
                        Log.w(TAG, "输入框输入的IP: " + inputIP + "serverIP: " + serverIP);
                        return;
                    }

                    if (socketThread != null && socketThread.isAlive()) {
                        socketThread.interrupt();
                    }
                    socketThread = null;
                    socketThread = new Thread(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                socket = new Socket(serverIP, serverPort);
                                dos = new DataOutputStream(socket.getOutputStream());
                                isTransmitting = true;
                                //输出缓冲区大小
                                Log.i(TAG, "SendBufferSize(): " + socket.getSendBufferSize() + " ReceiveBufferSize(): " + socket.getReceiveBufferSize());
                                Log.i(TAG, "myBtn01 连接成功: " + serverIP + ":" + serverPort);
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    if (socket == null || socket.isClosed()) {
                                        displayToast("连接失败，请重试!");
                                        myBtn01.setText("开始传输");
                                    } else {
                                        displayToast("连接成功,开始传输!");
                                        isTransmitting = true;
                                        myBtn01.setText("结束传输");
                                    }
                                }
                            });

                            while (isTransmitting) {
                                if (!queue.isEmpty()) {
                                    byte[] imageData = queue.poll();
                                    try {
                                        assert imageData != null;
                                        //获得当前时间
                                        long startTime = System.currentTimeMillis();
                                        dos.writeInt(imageData.length);
                                        Log.d(TAG, "视频大小发送时间: " + (System.currentTimeMillis() - startTime));
                                        dos.flush();
                                        // 发送视频流数据
                                        dos.write(imageData);
                                        Log.d(TAG, "视频流发送时间: " + (System.currentTimeMillis() - startTime));
                                        dos.flush();
                                        long endTime = System.currentTimeMillis();
                                        Log.i(TAG, "队列长度: " + queue.size() + " H264帧大小: " + imageData.length + " 总耗时: " + (endTime - startTime) + "ms");
                                        totalSize += imageData.length;
                                        Log.d(TAG, "totalSize:" + totalSize);
                                    } catch (IOException e) {
                                        e.printStackTrace();
                                    }
                                }
                            }
                            queue.clear();
                        }
                    }, "VideoTransThread");
                    socketThread.start();
                }
            } // 创建监听
        });

        myBtn02.setOnClickListener(new Button.OnClickListener() {
            @Override
            public void onClick(View v) {
                changeConfig(1280, 720, 0, 20);
                displayToast("配置切换完成！");
            } // 创建监听
        });


    }

    // 检查IP地址是否合法
    private boolean checkValidIP(String inputIP) {
/**
 * String ip = "([1-9]|[1-9]\\d|1\\d{2}|2[0-4]\\d|25[0-5])(\\.(\\d|[1-9]\\d|1\\d{2}|2[0-4]\\d|25[0-5])){3}";
 * Pattern pattern = Pattern.compile(ip);
 * Matcher matcher = pattern.matcher(ipAddress);
 * return matcher.matches();*/
        String[] ip = inputIP.split("\\.");
        if (ip.length != 4) {
            return false;
        }
        for (int i = 0; i < 4; i++) {
            int num = Integer.parseInt(ip[i]);
            if (num < 0 || num > 255) {
                return false;
            }
        }
        return true;
    }

    //显示Toast函数
    private void displayToast(String s) {
        //Looper.prepare();
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
        //Looper.loop();
    }

    /**
     * 改变相机和编码器的参数
     */
    public void changeConfig(int width, int height, int qp, int frameRate) {
        // TODO: 改变相机的各项参数
        mWidth = width;
        mHeight = height;
        mFrameRate = frameRate;
        mQP = qp;
        //mBitrate = mWidth * mHeight * mFrameRate;
        mCamera2Wrapper.closeCamera();
        mCamera2Wrapper.SetCameraParams(width, height, frameRate);
        mAvcEncoder.close();
        mAvcEncoder = null;
        mAvcEncoder = new AvcEncoder(width, height, frameRate, qp, mBitrate);
        mCamera2Wrapper.openCamera();
    }


    /**
     * 创建菜单
     */
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, 0, 0, "系统设置");
        menu.add(0, 1, 1, "关于程序");
        menu.add(0, 2, 2, "退出程序");
        return super.onCreateOptionsMenu(menu);
    }

    /**
     * 菜单选中时发生的相应事件
     */
    public boolean onOptionsItemSelected(MenuItem item) {
        super.onOptionsItemSelected(item);//获取菜单
        switch (item.getItemId())//菜单序号
        {
            case 0://系统设置
            {
                Intent intent = new Intent(Settings.ACTION_WIRELESS_SETTINGS);
                startActivity(intent);
            }
            break;
            case 1://关于程序
            {
                new AlertDialog.Builder(this)
                        .setTitle("关于本程序")
                        .setMessage("本程序默认服务器端口为6010,故可能导致端口冲突;\n" +
                                "本程序由西安交通大学计算机学院徐亮编写。\n" +
                                "Email:brightxu18@163.com")
                        .setPositiveButton
                                ("我知道了",
                                        new DialogInterface.OnClickListener() {
                                            @Override
                                            public void onClick(DialogInterface dialog, int which) {
                                            }
                                        }
                                )
                        .show();
            }
            break;
            case 2://退出程序
            {
                //杀掉线程强制退出
                android.os.Process.killProcess(android.os.Process.myPid());
            }
            break;
        }
        return true;
    }

}