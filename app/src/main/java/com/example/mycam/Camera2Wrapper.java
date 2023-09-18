package com.example.mycam;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.Semaphore;

/**
 * @Author bright
 * @Date 2023/4/2 10:58 AM
 * @ClassName Camera2Wrapper
 * Camera2 两路预览：
 * 1、使用TextureView预览，直接输出。
 * 2、使用ImageReader获取数据，输出格式为ImageFormat.YUV_420_888，java端转化为NV21，然后发送给Python端。
 */
public class Camera2Wrapper {
    private static final String TAG = "Camera2Wrapper";
    private final Activity mContext;
    private ImageReader mImageReader;
    private TextureView mTextureView;
    private final Handler mCameraHandler;
    private CameraDevice mCameraDevice;
    private CaptureRequest.Builder mPreviewRequestBuilder;
    private ImageDataListener mImageDataListener;
    private String mCameraId = String.valueOf(CameraCharacteristics.LENS_FACING_FRONT);
    private final Semaphore mCameraLock = new Semaphore(1);
    private final ArrayList<Range<Integer>> mFpsRanges = new ArrayList<>();
    private Size mPreviewSize = new Size(1280, 720);
    private Range<Integer> mFrameRate = new Range<>(30, 30);

    //相机被打开时的回调
    private final CameraDevice.StateCallback mCameraDeviceStateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            Log.i(TAG, "CameraDevice.StateCallback onOpened");
            mCameraDevice = camera;
            createCameraPreviewSession();
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            Log.e(TAG, "CameraDevice.StateCallback onDisconnected");
            camera.close();
            mCameraDevice = null;
        }

        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
            Log.e(TAG, "CameraDevice.StateCallback onError openDevice error:" + error);
            camera.close();
            mCameraDevice = null;
        }
    };

    // 相机预览会话创建成功时的回调
    private final CameraCaptureSession.StateCallback mCaptureSessionStateCallback = new CameraCaptureSession.StateCallback() {
        @Override
        public void onConfigured(CameraCaptureSession session) {
            Log.i(TAG, "CameraCaptureSession.StateCallback onConfigured");
            if (null == mCameraDevice) {
                return;
            }
            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            //mPreviewRequestBuilder.set(CaptureRequest.JPEG_ORIENTATION, 90);
            //mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, mFpsRanges.get(0));
            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, mFrameRate);
            CaptureRequest request = mPreviewRequestBuilder.build();
            try {
                // Finally, we start displaying the camera preview.
                session.setRepeatingRequest(request, new CameraCaptureSession.CaptureCallback() {
                    @Override
                    public void onCaptureStarted(@NonNull CameraCaptureSession session,
                                                 @NonNull CaptureRequest request, long timestamp, long frameNumber) {
                        super.onCaptureStarted(session, request, timestamp, frameNumber);
                    }
                }, mCameraHandler);
            } catch (CameraAccessException e) {
                e.printStackTrace();
            }
        }

        @Override
        public void onConfigureFailed(CameraCaptureSession session) {
            Log.e(TAG, "CameraCaptureSession.StateCallback onConfigureFailed");
        }
    };

    /**
     * Camera2Wrapper构造函数
     */
    public Camera2Wrapper(Activity context) {
        this.mContext = context;
        //创建了一个Thread来供Camera运行使用，使用HandlerThread而不使用Thread是因为HandlerThread给我们创建了Looper，不用我们自己创建了。
        HandlerThread mCameraThread = new HandlerThread("camera");
        mCameraThread.start();
        this.mCameraHandler = new Handler(mCameraThread.getLooper());
    }

    /**
     * 设置预览view
     *
     * @param textureView 需要预览的TextureView
     */
    public void initTexture(@NonNull TextureView textureView) {
        mTextureView = textureView;
        textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                Log.i(TAG, "onSurfaceTextureAvailable surfaceSize->" + width + "x" + height);
                openCamera();
            }
            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {}
            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {return false;}
            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture surface) {}
        });
    }

    /**
     * surface ready的时候开启Camera
     *
     */
    public void openCamera() {
        Log.i(TAG, "openCamera start");
        setCameraConfig();
        CameraManager cameraManager = (CameraManager) mContext.getSystemService(Context.CAMERA_SERVICE);
        mImageReader = ImageReader.newInstance(mPreviewSize.getWidth(), mPreviewSize.getHeight(),
                ImageFormat.YUV_420_888, 2);
/*        mImageReader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
            @Override
            public void onImageAvailable(ImageReader reader) {
                Log.i(TAG, "onImageAvailable");
                Image readImage = reader.acquireNextImage();
                byte[] data = ImageUtil.getBytesFromImageAsType(readImage, 1);
                readImage.close();
                // TODO：用MediaCodec处理data图片数据，将其转为视频流然后用RTP协议发送出去

            }
        }, mCameraHandler);*/
        mImageReader.setOnImageAvailableListener(new RTPOnImageAvailableListener(), mCameraHandler);
        String[] permission = new String[]{Manifest.permission.CAMERA};
        // Android 6.0相机动态权限检查
        if (ContextCompat.checkSelfPermission(mContext, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(mContext, permission, 1);
        }
        try {
            cameraManager.openCamera(mCameraId, mCameraDeviceStateCallback, mCameraHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    /**
     * 设置相机参数（分辨率、帧率）
     *
     */
    public void setCameraConfig() {
        Log.i(TAG, "setCameraConfig start");
        CameraManager cameraManager = (CameraManager) mContext.getSystemService(Context.CAMERA_SERVICE);
        try {
            CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(mCameraId);
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map != null) {
                Size[] sizeMap = map.getOutputSizes(SurfaceTexture.class);
                Size optimalSize = getOptimalSize(sizeMap, mPreviewSize.getWidth(), mPreviewSize.getHeight());
                if (optimalSize != null && optimalSize.getWidth() > 0 && optimalSize.getHeight() > 0) {
                    mPreviewSize = optimalSize;
                }
                StringBuilder sizes = new StringBuilder();
                for (Size size : sizeMap) {
                    sizes.append(size.getWidth()).append("x").append(size.getHeight()).append("  ");
                }
                Log.i(TAG, "setCameraConfig size->" + sizes);
                Log.i(TAG, "setCameraConfig preview->" + mPreviewSize.toString());
            }
            Range<Integer>[] fpsRanges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
            Log.i(TAG, "setCameraConfig fpsRanges->" + Arrays.toString(fpsRanges));
            mFpsRanges.addAll(Arrays.asList(fpsRanges));
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }


    //选择sizeMap中大于并且接近width和height的size
    private Size getOptimalSize(Size[] sizeMap, int screenWidth, int screenHeight) {
        Log.i(TAG, "getOptimalSize start!");
        final double ASPECT_TOLERANCE = 0.1;
        double targetRatio = (double) screenWidth / screenHeight;
        if (sizeMap == null || sizeMap.length == 0){
            return null;
        }
        Size optimalSize = null;
        double minDiff = Double.MAX_VALUE;
        for (Size size : sizeMap) {
            double ratio = (double) size.getWidth() / size.getHeight();
            if (Math.abs(ratio - targetRatio) > ASPECT_TOLERANCE) {
                continue;
            }
            if (Math.abs(size.getHeight() - screenHeight) < minDiff) {
                optimalSize = size;
                minDiff = Math.abs(size.getHeight() - screenHeight);
            }
        }
        if (optimalSize == null) {
            minDiff = Double.MAX_VALUE;
            for (Size size : sizeMap) {
                if (Math.abs(size.getHeight() - screenHeight) < minDiff) {
                    optimalSize = size;
                    minDiff = Math.abs(size.getHeight() - screenHeight);
                }
            }
        }
        Log.i(TAG, "getOptimalSize: " + optimalSize + ", Input size: " + screenWidth + "x" + screenHeight);
        return optimalSize;
    }

    /**
     * 关闭Camera
     */
    public void closeCamera() {
        Log.i(TAG, "closeCamera");

        if (mCameraDevice != null) {
            mCameraDevice.close();
            mCameraDevice = null;
        }

        if (mImageReader != null) {
            mImageReader.close();
            mImageReader = null;
        }
    }

    /**
     * Creates a new {@link CameraCaptureSession} for camera preview.
     */
    private void createCameraPreviewSession() {
        try {
            SurfaceTexture surfaceTexture = mTextureView.getSurfaceTexture();
            surfaceTexture.setDefaultBufferSize(mPreviewSize.getWidth(), mPreviewSize.getHeight());
            Surface previewSurface = new Surface(surfaceTexture);
            Surface imageSurface = mImageReader.getSurface();
            // We set up a CaptureRequest.Builder with the output Surface.
            mPreviewRequestBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            //如果需要多个surface可以add多个
            mPreviewRequestBuilder.addTarget(imageSurface);
            mPreviewRequestBuilder.addTarget(previewSurface);

            mCameraDevice.createCaptureSession(Arrays.asList(previewSurface, imageSurface),
                    mCaptureSessionStateCallback, mCameraHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    /**
     * 切换摄像头
     */
    public void switchCamera() {
        //获取摄像头的管理者
        CameraManager cameraManager = (CameraManager) mContext.getSystemService(Context.CAMERA_SERVICE);
        try {
            for (String id : cameraManager.getCameraIdList()) {
                CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(id);
                //获取是前置还是后置摄像头
                Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                Log.i(TAG, "switchCamera: " + id + ", facing: " + facing);
                if (mCameraId.equals(String.valueOf(CameraCharacteristics.LENS_FACING_BACK)) &&
                        facing == CameraCharacteristics.LENS_FACING_BACK) {
                    mCameraId = String.valueOf(CameraCharacteristics.LENS_FACING_FRONT);
                    break;
                }
                else if (mCameraId.equals(String.valueOf(CameraCharacteristics.LENS_FACING_FRONT)) &&
                        facing == CameraCharacteristics.LENS_FACING_FRONT) {
                    mCameraId = String.valueOf(CameraCharacteristics.LENS_FACING_BACK);
                    break;
                }
            }
            closeCamera();
            openCamera();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 改变相机的参数
     */
    public void SetCameraParams(int width, int height, int frameRate){
        mPreviewSize = new Size(width, height);
        mFrameRate = new Range<>(frameRate, frameRate);
    }


    /**
     * 有图像数据可用时回调
     */
    private class RTPOnImageAvailableListener implements ImageReader.OnImageAvailableListener{

        @Override
        public void onImageAvailable(ImageReader reader) {
            Log.d(TAG, "onImageAvailable");
            Image readImage = reader.acquireNextImage();
            // 将Image转换成byte数组
            byte[] data = ImageUtil.getBytesFromImageAsType(readImage, 1);
            readImage.close();
            mImageDataListener.OnImageDataListener(data);
        }
    }

    public void setImageDataListener(ImageDataListener listener) {
        this.mImageDataListener = listener;
    }

    public interface ImageDataListener{
        void OnImageDataListener(byte[] reader);
    }
}
