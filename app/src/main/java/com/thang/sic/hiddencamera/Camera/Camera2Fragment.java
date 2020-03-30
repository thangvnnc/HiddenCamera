package com.thang.sic.hiddencamera.Camera;


import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.content.Context;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.app.Fragment;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.annotation.NonNull;
import android.support.v13.app.FragmentCompat;
import android.support.v4.app.ActivityCompat;
import android.util.Log;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.LayoutInflater;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.Toast;

import com.thang.sic.hiddencamera.R;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * A simple {@link Fragment} subclass.
 */
public class Camera2Fragment extends Fragment
{
    private AutoFitTextureView _mTextureView        = null;
    private Button _btnVideo                        = null;
    private Boolean _mIsRecordingVideo              = false;
    private CameraDevice _mCameraDevice             = null;
    private Size _mPreviewSize                      = null;
    private Size _mVideoSize                        = null;
    private CameraCaptureSession _mPreviewSession   = null;
    private MediaRecorder _mMediaRecorder           = null;
    private HandlerThread _mBackgroundThread        = null;
    private Handler _mBackgroundHandler             = null;
    private Integer _mSensorOrientation             = null;
    private CaptureRequest.Builder _mPreviewBuilder = null;
    private String _mNextVideoAbsolutePath          = null;
    private Button _btnFlash                        = null;
    CameraManager _cameraManager                    = null;
    private Boolean _status                         = false;

    private Semaphore _mCameraOpenCloseLock         = new Semaphore(1);

    private static final int SENSOR_ORIENTATION_DEFAULT_DEGREES = 90;
    private static final int SENSOR_ORIENTATION_INVERSE_DEGREES = 270;
    private static final SparseIntArray DEFAULT_ORIENTATIONS    = new SparseIntArray();
    private static final SparseIntArray INVERSE_ORIENTATIONS    = new SparseIntArray();
    private static final String FRAGMENT_DIALOG                 = "dialog";
    private static final int REQUEST_VIDEO_PERMISSIONS          = 1;
    private static final String TAG                             = "Camera2Fragment";

    static {
        DEFAULT_ORIENTATIONS.append(Surface.ROTATION_0, 90);
        DEFAULT_ORIENTATIONS.append(Surface.ROTATION_90, 0);
        DEFAULT_ORIENTATIONS.append(Surface.ROTATION_180, 270);
        DEFAULT_ORIENTATIONS.append(Surface.ROTATION_270, 180);
    }

    static {
        INVERSE_ORIENTATIONS.append(Surface.ROTATION_0, 270);
        INVERSE_ORIENTATIONS.append(Surface.ROTATION_90, 180);
        INVERSE_ORIENTATIONS.append(Surface.ROTATION_180, 90);
        INVERSE_ORIENTATIONS.append(Surface.ROTATION_270, 0);
    }

    private static final String[] VIDEO_PERMISSIONS =
    {
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO,
    };

    // Tạo instance singleton CameraFragment
    public static Camera2Fragment newInstance()
    {
        return new Camera2Fragment();
    }

    // Khởi tạo view
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState)
    {
        return inflater.inflate(R.layout.fragment_camera2, container, false);
    }

    // Khi tạo xong view fragment
    @Override
    public void onViewCreated(View view, Bundle savedInstanceState)
    {
        _mTextureView           = (AutoFitTextureView)view.findViewById(R.id.texture);
        _btnVideo               = (Button)view.findViewById(R.id.btnVideo);
        _btnVideo.setOnClickListener(click_btnvideo);
        _btnFlash               = (Button)view.findViewById(R.id.btnFlash);
        _btnFlash.setOnClickListener(click_btnflash);
    }

    @Override
    public void onResume()
    {
        super.onResume();
        startBackgroundThread();
        if (_mTextureView.isAvailable())
        {
            openCamera(_mTextureView.getWidth(), _mTextureView.getHeight());
        }
        else
        {
            _mTextureView.setSurfaceTextureListener(_mSurfaceTextureListener);
        }
    }

    @Override
    public void onPause()
    {
        closeCamera();
        stopBackgroundThread();
        super.onPause();
    }

    private void stopBackgroundThread()
    {
        _mBackgroundThread.quitSafely();
        try
        {
            _mBackgroundThread.join();
            _mBackgroundThread = null;
            _mBackgroundHandler = null;
        }
        catch (InterruptedException e)
        {
            e.printStackTrace();
        }
    }

    private void closeCamera()
    {
        try
        {
            _mCameraOpenCloseLock.acquire();
            closePreviewSession();
            if (null != _mCameraDevice)
            {
                _mCameraDevice.close();
                _mCameraDevice = null;
            }
            if (null != _mMediaRecorder)
            {
                _mMediaRecorder.release();
                _mMediaRecorder = null;
            }
        }
        catch (InterruptedException e)
        {
            throw new RuntimeException("Interrupted while trying to lock camera closing.");
        }
        finally
        {
            _mCameraOpenCloseLock.release();
        }
    }

    public static class ConfirmationDialog extends DialogFragment
    {
        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState)
        {
            final Fragment parent = getParentFragment();
            return new AlertDialog.Builder(getActivity())
                .setMessage(R.string.permission_request)
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener()
                {
                    @Override
                    public void onClick(DialogInterface dialog, int which)
                    {
                        FragmentCompat.requestPermissions(parent, VIDEO_PERMISSIONS,
                            REQUEST_VIDEO_PERMISSIONS);
                    }
                })
                .setNegativeButton(android.R.string.cancel,
                    new DialogInterface.OnClickListener()
                    {
                        @Override
                        public void onClick(DialogInterface dialog, int which)
                        {
                            parent.getActivity().finish();
                        }
                    })
                .create();
        }
    }

    // Hàm lắng nghe sự kiện surfaceTextureListener
    private TextureView.SurfaceTextureListener _mSurfaceTextureListener
        = new TextureView.SurfaceTextureListener()
    {
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture,
                                              int width, int height)
        {
            openCamera(width, height);
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surfaceTexture,
                                                int width, int height)
        {
            configureTransform(width, height);
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture)
        {
            if (_mMediaRecorder != null)
            {
                _mMediaRecorder.stop();
                _mMediaRecorder.release();
                _mMediaRecorder = null;
            }
            return true;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture)
        {
        }

    };

    private void configureTransform(int viewWidth, int viewHeight)
    {
        Activity activity = getActivity();
        if (null == _mTextureView || null == _mPreviewSize || null == activity)
        {
            return;
        }
        int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();
        Matrix matrix = new Matrix();
        RectF viewRect = new RectF(0, 0, viewWidth, viewHeight);
        RectF bufferRect = new RectF(0, 0, _mPreviewSize.getHeight(), _mPreviewSize.getWidth());
        float centerX = viewRect.centerX();
        float centerY = viewRect.centerY();
        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation)
        {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);
            float scale = Math.max(
                (float) viewHeight / _mPreviewSize.getHeight(),
                (float) viewWidth / _mPreviewSize.getWidth());
            matrix.postScale(scale, scale, centerX, centerY);
            matrix.postRotate(90 * (rotation - 2), centerX, centerY);
        }
        _mTextureView.setTransform(matrix);
    }

    @SuppressWarnings("MissingPermission")
    private void openCamera(int width, int height)
    {
        if (!hasPermissionsGranted(VIDEO_PERMISSIONS))
        {
            requestVideoPermissions();
            return;
        }
        final Activity activity = getActivity();
        if (null == activity || activity.isFinishing())
        {
            return;
        }
        _cameraManager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
        try
        {
            Log.d(TAG, "tryAcquire");
            if (!_mCameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS))
            {
                throw new RuntimeException("Time out waiting to lock camera opening.");
            }
            String cameraId = _cameraManager.getCameraIdList()[0];

            // Choose the sizes for camera preview and video recording
            CameraCharacteristics characteristics = _cameraManager.getCameraCharacteristics(cameraId);
            StreamConfigurationMap map = characteristics
                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            _mSensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
            if (map == null)
            {
                throw new RuntimeException("Cannot get available preview/video sizes");
            }
            _mVideoSize = chooseVideoSize(map.getOutputSizes(MediaRecorder.class));
            _mPreviewSize = chooseOptimalSize(map.getOutputSizes(SurfaceTexture.class),
                width, height, _mVideoSize);

            int orientation = getResources().getConfiguration().orientation;
            if (orientation == Configuration.ORIENTATION_LANDSCAPE)
            {
                _mTextureView.setAspectRatio(_mPreviewSize.getWidth(), _mPreviewSize.getHeight());
            }
            else
            {
                _mTextureView.setAspectRatio(_mPreviewSize.getHeight(), _mPreviewSize.getWidth());
            }
            configureTransform(width, height);
            _mMediaRecorder = new MediaRecorder();
            _cameraManager.openCamera(cameraId, _mStateCallback, null);
        }
        catch (CameraAccessException e)
        {
            Toast.makeText(activity, "Cannot access the camera.", Toast.LENGTH_SHORT).show();
            activity.finish();
        }
        catch (NullPointerException e)
        {
            // Currently an NPE is thrown when the Camera2API is used but not supported on the
            // device this code runs.
            ErrorDialog.newInstance(getString(R.string.camera_error))
                .show(getChildFragmentManager(), FRAGMENT_DIALOG);
        }
        catch (InterruptedException e)
        {
            throw new RuntimeException("Interrupted while trying to lock camera opening.");
        }
    }
    public static class ErrorDialog extends DialogFragment
    {
        private static final String ARG_MESSAGE = "message";

        public static ErrorDialog newInstance(String message)
        {
            ErrorDialog dialog = new ErrorDialog();
            Bundle args = new Bundle();
            args.putString(ARG_MESSAGE, message);
            dialog.setArguments(args);
            return dialog;
        }

        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState)
        {
            final Activity activity = getActivity();
            return new AlertDialog.Builder(activity)
                .setMessage(getArguments().getString(ARG_MESSAGE))
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener()
                {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int i)
                    {
                        activity.finish();
                    }
                }).create();
        }
    }

    static class CompareSizesByArea implements Comparator<Size>
    {
        @Override
        public int compare(Size lhs, Size rhs)
        {
            // We cast here to ensure the multiplications won't overflow
            return Long.signum((long) lhs.getWidth() * lhs.getHeight() -
                (long) rhs.getWidth() * rhs.getHeight());
        }

    }

    private Size chooseOptimalSize(Size[] choices, int width, int height, Size aspectRatio)
    {
        // Collect the supported resolutions that are at least as big as the preview Surface
        List<Size> bigEnough = new ArrayList<>();
        int w = aspectRatio.getWidth();
        int h = aspectRatio.getHeight();
        for (Size option : choices)
        {
            if (option.getHeight() == option.getWidth() * h / w &&
                option.getWidth() >= width && option.getHeight() >= height)
            {
                bigEnough.add(option);
            }
        }

        // Pick the smallest of those, assuming we found any
        if (bigEnough.size() > 0)
        {
            return Collections.min(bigEnough, new CompareSizesByArea());
        }
        else
        {
            Log.e(TAG, "Couldn't find any suitable preview size");
            return choices[0];
        }
    }

    private Size chooseVideoSize(Size[] choices)
    {
        for (Size size : choices)
        {
            if (size.getWidth() == size.getHeight() * 4 / 3 && size.getWidth() <= 1080)
            {
                return size;
            }
        }
        Log.e(TAG, "Couldn't find any suitable video size");
        return choices[choices.length - 1];
    }

    private CameraDevice.StateCallback _mStateCallback = new CameraDevice.StateCallback()
    {

        @Override
        public void onOpened(@NonNull CameraDevice cameraDevice)
        {
            _mCameraDevice = cameraDevice;
            startPreview();
            _mCameraOpenCloseLock.release();
            if (null != _mTextureView)
            {
                configureTransform(_mTextureView.getWidth(), _mTextureView.getHeight());
            }
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice cameraDevice)
        {
            _mCameraOpenCloseLock.release();
            cameraDevice.close();
            _mCameraDevice = null;
        }

        @Override
        public void onError(@NonNull CameraDevice cameraDevice, int error)
        {
            _mCameraOpenCloseLock.release();
            cameraDevice.close();
            _mCameraDevice = null;
            Activity activity = getActivity();
            if (null != activity)
            {
                activity.finish();
            }
        }

    };

    private void startPreview()
    {
        if (null == _mCameraDevice || !_mTextureView.isAvailable() || null == _mPreviewSize)
        {
            return;
        }
        try
        {
            closePreviewSession();
            SurfaceTexture texture = _mTextureView.getSurfaceTexture();
            assert texture != null;
            texture.setDefaultBufferSize(_mPreviewSize.getWidth(), _mPreviewSize.getHeight());
            _mPreviewBuilder = _mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);

            List<Surface> surfaces = new ArrayList<>();

            Surface previewSurface = new Surface(texture);
            _mPreviewBuilder.addTarget(previewSurface);
            surfaces.add(previewSurface);

//            Surface receiveSurface = _mImageReader.getSurface();
//            _mPreviewBuilder.addTarget(receiveSurface);
//            _mImageReader.setOnImageAvailableListener(mOnImageAvailableListener, _mBackgroundHandler);
//            surfaces.add(receiveSurface);

            _mCameraDevice.createCaptureSession(surfaces,
                new CameraCaptureSession.StateCallback()
                {
                    @Override
                    public void onConfigured(@NonNull CameraCaptureSession session)
                    {
                        _mPreviewSession = session;
                        updatePreview();
                    }

                    @Override
                    public void onConfigureFailed(@NonNull CameraCaptureSession session)
                    {
                        Activity activity = getActivity();
                        if (null != activity)
                        {
                            Toast.makeText(activity, "Failed", Toast.LENGTH_SHORT).show();
                        }
                    }
                }, _mBackgroundHandler);
        }
        catch (CameraAccessException e)
        {
            e.printStackTrace();
        }
    }

    private void updatePreview()
    {
        if (null == _mCameraDevice)
        {
            return;
        }
        try
        {
            setUpCaptureRequestBuilder(_mPreviewBuilder);
            HandlerThread thread = new HandlerThread("CameraPreview");
            thread.start();
            _mPreviewSession.setRepeatingRequest(_mPreviewBuilder.build(), null, _mBackgroundHandler);
        }
        catch (CameraAccessException e)
        {
            e.printStackTrace();
        }
    }

    private void setUpCaptureRequestBuilder(CaptureRequest.Builder builder)
    {
        builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
    }

    private void requestVideoPermissions()
    {
        if (shouldShowRequestPermissionRationale(VIDEO_PERMISSIONS))
        {
            new ConfirmationDialog().show(getChildFragmentManager(), FRAGMENT_DIALOG);
        }
        else
        {
            FragmentCompat.requestPermissions(this, VIDEO_PERMISSIONS, REQUEST_VIDEO_PERMISSIONS);
        }
    }

    private boolean shouldShowRequestPermissionRationale(String[] permissions)
    {
        for (String permission : permissions)
        {
            if (FragmentCompat.shouldShowRequestPermissionRationale(this, permission))
            {
                return true;
            }
        }
        return false;
    }

    private boolean hasPermissionsGranted(String[] permissions)
    {
        for (String permission : permissions)
        {
            if (ActivityCompat.checkSelfPermission(getActivity(), permission) != PackageManager.PERMISSION_GRANTED)
            {
                return false;
            }
        }
        return true;
    }

    private View.OnClickListener click_btnflash = new View.OnClickListener()
    {
        @Override
        public void onClick(View v)
        {
        }
    };

    // Sự kiện click vào nút video
    private View.OnClickListener click_btnvideo = new View.OnClickListener()
    {
        @Override
        public void onClick(View v)
        {
            // Kiểm tra trạng thái đang quay video
            if (_mIsRecordingVideo == false)
            {
                // Bắt đầu ghi
                startRecordingVideo();
            }
            else
            {
                // Dừng ghi video
                stopRecordingVideo();
            }
        }
    };

    /**
     * Khởi động ghi video
     */
    private void startRecordingVideo()
    {
        // Kiểm tra null
        if (null == _mCameraDevice || !_mTextureView.isAvailable() || null == _mPreviewSize)
        {
            return;
        }

        try
        {
            closePreviewSession();
            setUpMediaRecorder();
            SurfaceTexture texture = _mTextureView.getSurfaceTexture();
            assert texture != null;
            texture.setDefaultBufferSize(_mPreviewSize.getWidth(), _mPreviewSize.getHeight());
            _mPreviewBuilder = _mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            List<Surface> surfaces = new ArrayList<>();

            // Set up Surface for the camera preview
            Surface previewSurface = new Surface(texture);
            surfaces.add(previewSurface);
            _mPreviewBuilder.addTarget(previewSurface);

            // Set up Surface for the MediaRecorder
            Surface recorderSurface = _mMediaRecorder.getSurface();
            surfaces.add(recorderSurface);
            _mPreviewBuilder.addTarget(recorderSurface);

//            Surface receiveSurface = _mImageReader.getSurface();
//            _mPreviewBuilder.addTarget(receiveSurface);
//            _mImageReader.setOnImageAvailableListener(mOnImageAvailableListener, _mBackgroundHandler);

            //Make list of surfaces to give to camera
//            surfaces.add(receiveSurface);

            // Start a capture session
            // Once the session starts, we can update the UI and start recording
            _mCameraDevice.createCaptureSession(surfaces, new CameraCaptureSession.StateCallback()
            {

                @Override
                public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession)
                {
                    _mPreviewSession = cameraCaptureSession;
                    updatePreview();
                    getActivity().runOnUiThread(new Runnable()
                    {
                        @Override
                        public void run()
                        {
                            // UI
                            _btnVideo.setText(R.string.stop);
                            _mIsRecordingVideo = true;

                            // Start recording
                            _mMediaRecorder.start();
                        }
                    });
                }

                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession)
                {
                    Activity activity = getActivity();
                    if (null != activity)
                    {
                        Toast.makeText(activity, "Failed", Toast.LENGTH_SHORT).show();
                    }
                }
            }, _mBackgroundHandler);
        }
        catch (CameraAccessException | IOException e)
        {
            e.printStackTrace();
        }
    }

    // Hàm thiết lập quay video
    private void setUpMediaRecorder() throws IOException
    {
        final Activity activity = getActivity();
        if (null == activity)
        {
            return;
        }
        _mMediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        _mMediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        _mMediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        if (_mNextVideoAbsolutePath == null || _mNextVideoAbsolutePath.isEmpty())
        {
            _mNextVideoAbsolutePath = getVideoFilePath(getActivity());
        }
        _mMediaRecorder.setOutputFile(_mNextVideoAbsolutePath);
        _mMediaRecorder.setVideoEncodingBitRate(10000000);
        _mMediaRecorder.setVideoFrameRate(30);
        _mMediaRecorder.setVideoSize(_mVideoSize.getWidth(), _mVideoSize.getHeight());
        _mMediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
        _mMediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
        int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();
        switch (_mSensorOrientation)
        {
            case SENSOR_ORIENTATION_DEFAULT_DEGREES:
                _mMediaRecorder.setOrientationHint(DEFAULT_ORIENTATIONS.get(rotation));
                break;
            case SENSOR_ORIENTATION_INVERSE_DEGREES:
                _mMediaRecorder.setOrientationHint(INVERSE_ORIENTATIONS.get(rotation));
                break;
        }
        _mMediaRecorder.prepare();
    }

    private String getVideoFilePath(Context context)
    {
        final File dir = context.getExternalFilesDir(null);
        return (dir == null ? "" : (dir.getAbsolutePath() + "/"))
            + System.currentTimeMillis() + ".data";
    }

    private void stopRecordingVideo()
    {
        // UI
        _mIsRecordingVideo = false;
        _btnVideo.setText(R.string.record);
        // Stop recording
        _mMediaRecorder.stop();
        _mMediaRecorder.reset();

//        Activity activity = getActivity();
//        if (null != activity)
//        {
//            Toast.makeText(activity, "Video saved: " + _mNextVideoAbsolutePath,
//                Toast.LENGTH_SHORT).show();
//            Log.d(TAG, "Video saved: " + _mNextVideoAbsolutePath);
//        }
        _mNextVideoAbsolutePath = null;
        startPreview();
    }

    private void closePreviewSession()
    {
        if (_mPreviewSession != null)
        {
            _mPreviewSession.close();
            _mPreviewSession = null;
        }
    }

    private void startBackgroundThread()
    {
        _mBackgroundThread = new HandlerThread("CameraBackground");
        _mBackgroundThread.start();
        _mBackgroundHandler = new Handler(_mBackgroundThread.getLooper());
    }

    private ImageReader _mImageReader = ImageReader.newInstance(1280, 720, ImageFormat.YV12, 30);

    private final ImageReader.OnImageAvailableListener mOnImageAvailableListener = new ImageReader.OnImageAvailableListener()
    {
        @Override
        public void onImageAvailable(ImageReader reader)
        {
            Image image = null;
            try
            {
                image = reader.acquireLatestImage();
                if (image != null)
                {
                    ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                    byte[] bytes = new byte[buffer.remaining()];
                    buffer.get(bytes);
                    String encoded = null;
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
                    {
                        encoded = Base64.getEncoder().encodeToString(bytes);
                    }
                    else
                    {
                        encoded = android.util.Base64.encodeToString(bytes, android.util.Base64.DEFAULT);
                    }
                    image.close();
                    Log.v("Receive base64: ", encoded.length()+"");
                }
            }
            catch (Exception e)
            {
                Log.w(TAG, e.getMessage());
            }
        }
    };
}
