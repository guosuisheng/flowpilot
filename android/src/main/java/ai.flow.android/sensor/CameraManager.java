package ai.flow.android.sensor;

import ai.flow.common.ParamsInterface;
import ai.flow.common.Path;
import ai.flow.common.transformations.Camera;
import ai.flow.common.transformations.Model;
import ai.flow.common.utils;
import ai.flow.definitions.Definitions;
import ai.flow.modeld.ModelExecutorF3;
import ai.flow.modeld.messages.MsgFrameData;
import ai.flow.sensor.SensorInterface;
import ai.flow.sensor.messages.MsgFrameBuffer;
import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.MeteringRectangle;
import android.hardware.camera2.params.RggbChannelVector;
import android.icu.number.Scale;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.util.Range;
import android.util.Size;
import android.view.Surface;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.annotation.RequiresApi;
import androidx.camera.camera2.interop.Camera2Interop;
import androidx.camera.core.*;
import androidx.camera.core.impl.UseCaseConfigFactory;
import androidx.camera.core.internal.utils.ImageUtil;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import com.google.common.util.concurrent.ListenableFuture;

import io.github.crow_misia.libyuv.AbgrBuffer;
import io.github.crow_misia.libyuv.ArgbBuffer;
import io.github.crow_misia.libyuv.FilterMode;
import io.github.crow_misia.libyuv.Nv21Buffer;
import messaging.ZMQPubHandler;
import org.capnproto.PrimitiveList;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;
import org.opencv.core.Core;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static ai.flow.android.sensor.Utils.fillYUVBuffer;
import static ai.flow.common.BufferUtils.byteToFloat;
import static ai.flow.common.transformations.Camera.CAMERA_TYPE_ROAD;

public class CameraManager extends SensorInterface {

    private ImageAnalysis.Analyzer myAnalyzer, roadAnalyzer = null;
    //public static List<CameraManager> Managers = new ArrayList<>();
    public ProcessCameraProvider cameraProvider;
    public ExecutorService threadpool;
    public String frameDataTopic, frameBufferTopic, intName;
    public ZMQPubHandler ph;
    public boolean running = false;
    public int W = Camera.frameSize[0];
    public int H = Camera.frameSize[1];
    public MsgFrameData msgFrameData, msgFrameRoadData;
    public MsgFrameBuffer msgFrameBuffer, msgFrameRoadBuffer;
    public PrimitiveList.Float.Builder K;
    public int frameID = 0;
    public boolean recording = false;
    public Context context;
    public ParamsInterface params = ParamsInterface.getInstance();
    public Fragment lifeCycleFragment;
    int cameraType;
    CameraControl cameraControl;
    SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd--HH-mm-ss.SSS");
    ByteBuffer yuvBuffer, yuvRoadBuffer;
    String videoFileName, vidFilePath, videoLockPath;
    File lockFile;

    public CameraSelector getCameraSelector(boolean  wide){
        if (wide) {
            List<CameraInfo> availableCamerasInfo = cameraProvider.getAvailableCameraInfos();
            android.hardware.camera2.CameraManager cameraService = (android.hardware.camera2.CameraManager) context.getSystemService(Context.CAMERA_SERVICE);

            float minFocalLen = Float.MAX_VALUE;
            String wideAngleCameraId = null;

            try {
                String[] cameraIds = cameraService.getCameraIdList();
                for (String id : cameraIds) {
                    CameraCharacteristics characteristics = cameraService.getCameraCharacteristics(id);
                    float focal_length = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)[0];
                    boolean backCamera = CameraCharacteristics.LENS_FACING_BACK == characteristics.get(CameraCharacteristics.LENS_FACING);
                    if ((focal_length < minFocalLen) && backCamera) {
                        minFocalLen = focal_length;
                        wideAngleCameraId = id;
                    }
                }
            } catch (CameraAccessException e) {
                e.printStackTrace();
            }
            if (params.exists("WideCameraID")){
                wideAngleCameraId = params.getString("WideCameraID");
                System.out.println("Using camera ID provided by 'WideCameraID' param, ID: " + wideAngleCameraId);
            }
            return availableCamerasInfo.get(Integer.parseInt(wideAngleCameraId)).getCameraSelector();
        }
        else
            return new CameraSelector.Builder().requireLensFacing(CameraSelector.LENS_FACING_BACK).build();
    }

    public CameraManager(Context context, int cameraType){
        msgFrameData = new MsgFrameData(cameraType);
        K = msgFrameData.intrinsics;
        System.loadLibrary(Core.NATIVE_LIBRARY_NAME);
        df.setTimeZone(TimeZone.getTimeZone("UTC"));
        this.context = context;
        this.cameraType = cameraType;
        this.threadpool = Executors.newCachedThreadPool();

        if (utils.SimulateRoadCamera) {
            WideToRoad = new Matrix();
            WideToRoad.setScale(1f + ScaleAmount, 1f + ScaleAmount);
            WideToRoad.postTranslate(-W * 0.5f * ScaleAmount, -H * 0.5f * ScaleAmount);
            nv21buf = Nv21Buffer.Factory.allocate(W, H);
            nv21buf_small = Nv21Buffer.Factory.allocate(Math.round(W / (1f + ScaleAmount)), Math.round(H / (1f + ScaleAmount)));
            abgrbuf = AbgrBuffer.Factory.allocate(nv21buf_small.getWidth(), nv21buf_small.getHeight());
            float[] rawvals = new float[9];
            WideToRoad.getValues(rawvals);
            ModelExecutorF3.wideToRoad = Nd4j.createFromArray(new float[][]{
                    {rawvals[0],  0.0f,  rawvals[2]},
                    {0.0f,  rawvals[4],  rawvals[5]},
                    {0.0f,  0.0f,  1.0f}
            });
            msgFrameRoadData = new MsgFrameData(CAMERA_TYPE_ROAD);
            msgFrameRoadBuffer = new MsgFrameBuffer(W * H * 3/2, CAMERA_TYPE_ROAD);
            yuvRoadBuffer = msgFrameRoadBuffer.frameBuffer.getImage().asByteBuffer();
            msgFrameRoadBuffer.frameBuffer.setEncoding(Definitions.FrameBuffer.Encoding.YUV);
            msgFrameRoadBuffer.frameBuffer.setFrameHeight(H);
            msgFrameRoadBuffer.frameBuffer.setFrameWidth(W);
        }

        if (cameraType == Camera.CAMERA_TYPE_WIDE){
            this.frameDataTopic = "wideRoadCameraState";
            this.frameBufferTopic = "wideRoadCameraBuffer";
            this.intName = "WideCameraMatrix";
        } else if (cameraType == CAMERA_TYPE_ROAD) {
            this.frameDataTopic = "roadCameraState";
            this.frameBufferTopic = "roadCameraBuffer";
            this.intName = "CameraMatrix";
        }

        msgFrameBuffer = new MsgFrameBuffer(W * H * 3/2, cameraType);
        yuvBuffer = msgFrameBuffer.frameBuffer.getImage().asByteBuffer();
        msgFrameBuffer.frameBuffer.setEncoding(Definitions.FrameBuffer.Encoding.YUV);
        msgFrameBuffer.frameBuffer.setFrameHeight(H);
        msgFrameBuffer.frameBuffer.setFrameWidth(W);

        ph = new ZMQPubHandler();

        if (utils.SimulateRoadCamera)
            ph.createPublishers(Arrays.asList("wideRoadCameraState", "roadCameraState", "roadCameraBuffer", "wideRoadCameraBuffer"));
        else
            ph.createPublishers(Arrays.asList(frameDataTopic, frameBufferTopic));

        loadIntrinsics();
    }

    public void loadIntrinsics(){
        if (params.exists(intName)) {
            float[] cameraMatrix = byteToFloat(params.getBytes(intName));
            updateProperty("intrinsics", cameraMatrix);
        }
    }

    public void setIntrinsics(float[] intrinsics){
        K.set(0, intrinsics[0]);
        K.set(2, intrinsics[2]);
        K.set(4,intrinsics[4]);
        K.set(5, intrinsics[5]);
        K.set(8, 1f);

        if (utils.SimulateRoadCamera) {
            INDArray thisIntrinsics = Nd4j.createFromArray(new float[][]{
                    {intrinsics[0],  0.0f,  intrinsics[2]},
                    {0.0f,  intrinsics[4],  intrinsics[5]},
                    {0.0f,  0.0f,  1.0f}
            });
            INDArray transformed = ModelExecutorF3.wideToRoad.mmul(thisIntrinsics);
            msgFrameRoadData.intrinsics.set(0, transformed.getFloat(0));
            msgFrameRoadData.intrinsics.set(2, transformed.getFloat(2));
            msgFrameRoadData.intrinsics.set(4, transformed.getFloat(4));
            msgFrameRoadData.intrinsics.set(5, transformed.getFloat(5));
            msgFrameRoadData.intrinsics.set(8, 1f);
        }
    }

    public void setLifeCycleFragment(Fragment lifeCycleFragment){
        this.lifeCycleFragment = lifeCycleFragment;
    }

    public static android.graphics.Matrix WideToRoad;
    Nv21Buffer nv21buf, nv21buf_small;
    AbgrBuffer abgrbuf;
    Rect cropRect;

    private final float ScaleAmount = 0.425f;

    public void SimulateRoadCamera(ImageProxy image) {
        int cropW = Math.round(W / (1f + ScaleAmount));
        int cropH = Math.round(H / (1f + ScaleAmount));
        int Woffset = (W - cropW) / 2;
        int Hoffset = (H - cropH) / 2;
        Rect r = new Rect(Woffset, Hoffset, Woffset + cropW, Hoffset + cropH);
        try {
            Bitmap b = image.toBitmap();
            Bitmap cropped = Bitmap.createBitmap(b, Woffset, Hoffset, cropW, cropH);
            cropped.copyPixelsToBuffer(abgrbuf.asBuffer());
            abgrbuf.convertTo(nv21buf_small);
            nv21buf_small.scale(nv21buf, FilterMode.NONE);
            yuvRoadBuffer.rewind();
            yuvRoadBuffer.put(nv21buf.asBuffer());

            msgFrameRoadBuffer.frameBuffer.setYWidth(W);
            msgFrameRoadBuffer.frameBuffer.setYHeight(H);
            msgFrameRoadBuffer.frameBuffer.setYPixelStride(msgFrameBuffer.frameBuffer.getYPixelStride());
            msgFrameRoadBuffer.frameBuffer.setUvWidth(W / 2);
            msgFrameRoadBuffer.frameBuffer.setUvHeight(H / 2);
            msgFrameRoadBuffer.frameBuffer.setUvPixelStride(image.getPlanes()[1].getPixelStride());
            msgFrameRoadBuffer.frameBuffer.setUOffset(W * H);
            if (image.getPlanes()[1].getPixelStride() == 2)
                msgFrameRoadBuffer.frameBuffer.setVOffset(W * H + 1);
            else
                msgFrameRoadBuffer.frameBuffer.setVOffset(W * H + W * H / 4);
            msgFrameRoadBuffer.frameBuffer.setStride(msgFrameBuffer.frameBuffer.getStride());

            msgFrameRoadData.frameData.setFrameId(frameID);

            ph.publishBuffer("roadCameraState", msgFrameRoadData.serialize(true));
            ph.publishBuffer("roadCameraBuffer", msgFrameRoadBuffer.serialize(true));

            image.close();
        } catch (Exception e) {
            System.out.println(e.toString());
        }
    }

    public void start() {
        if (running)
            return;
        running = true;

        CameraManager myCamManager = this;
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(context);
        cameraProviderFuture.addListener(new Runnable() {
            @Override
            public void run() {
                try {
                    cameraProvider = cameraProviderFuture.get();
                    myAnalyzer = new ImageAnalysis.Analyzer() {
                        @OptIn(markerClass = ExperimentalGetImage.class) @RequiresApi(api = Build.VERSION_CODES.N)
                        @Override
                        public void analyze(@NonNull ImageProxy image) {

                            // don't need an image right now, skip this
                            if (ModelExecutorF3.NeedImage == false) {
                                image.close();
                                frameID += 1;
                                return;
                            }

                            fillYUVBuffer(image, yuvBuffer);

                            ImageProxy.PlaneProxy yPlane = image.getPlanes()[0];

                            msgFrameBuffer.frameBuffer.setYWidth(W);
                            msgFrameBuffer.frameBuffer.setYHeight(H);
                            msgFrameBuffer.frameBuffer.setYPixelStride(yPlane.getPixelStride());
                            msgFrameBuffer.frameBuffer.setUvWidth(W /2);
                            msgFrameBuffer.frameBuffer.setUvHeight(H /2);
                            msgFrameBuffer.frameBuffer.setUvPixelStride(image.getPlanes()[1].getPixelStride());
                            msgFrameBuffer.frameBuffer.setUOffset(W * H);
                            if (image.getPlanes()[1].getPixelStride() == 2)
                                msgFrameBuffer.frameBuffer.setVOffset(W * H +1);
                            else
                                msgFrameBuffer.frameBuffer.setVOffset(W * H + W * H /4);
                            msgFrameBuffer.frameBuffer.setStride(yPlane.getRowStride());

                            msgFrameData.frameData.setFrameId(frameID);

                            ModelExecutorF3.SetLatestCameraData(msgFrameData.frameData.asReader(),
                                                                msgFrameBuffer.frameBuffer.asReader());

                            // this isn't critical, so don't hold up image analysis to do
                            threadpool.submit(() -> {
                                ph.publishBuffer(frameDataTopic, msgFrameData.serialize(true));
                                ph.publishBuffer(frameBufferTopic, msgFrameBuffer.serialize(true));
                            });

                            if (utils.SimulateRoadCamera)
                                SimulateRoadCamera(image);

                            image.close();
                            frameID += 1;
                        }
                    };

                    //if (utils.WideCameraOnly)
                    bindUseCases(cameraProvider);
                    /*else {
                        Managers.add(myCamManager);
                        if (Managers.size() == 2)
                            bindUseCasesGroup(Managers, cameraProvider);
                    }*/
                } catch (ExecutionException | InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }, ContextCompat.getMainExecutor(context));
    }

    /*@SuppressLint({"RestrictedApi", "UnsafeOptInUsageError"})
    private static void bindUseCasesGroup(List<CameraManager> managers, ProcessCameraProvider cameraProvider) {
        List<ConcurrentCamera.SingleCameraConfig> configs = new ArrayList<>();
        for (int i=0; i<managers.size(); i++) {
            CameraManager cm = managers.get(i);
            ImageAnalysis.Builder builder = new ImageAnalysis.Builder();
            builder.setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST);
            builder.setDefaultResolution(new Size(cm.W, cm.H));
            builder.setMaxResolution(new Size(cm.W, cm.H));
            builder.setTargetResolution(new Size(cm.W, cm.H));
            Camera2Interop.Extender<ImageAnalysis> ext = new Camera2Interop.Extender<>(builder);
            ext.setCaptureRequestOption(CaptureRequest.CONTROL_AE_REGIONS, new MeteringRectangle[]{
                    new MeteringRectangle(1, 1, cm.W - 2, cm.H - 2, 500)
            });
            ext.setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, new Range<>(21, 40));
            ImageAnalysis imageAnalysis = builder.build();
            imageAnalysis.setAnalyzer(ContextCompat.getMainExecutor(cm.context), cm.myAnalyzer);
            ConcurrentCamera.SingleCameraConfig camconfig = new ConcurrentCamera.SingleCameraConfig(
                    cm.getCameraSelector(cm.cameraType == Camera.CAMERA_TYPE_WIDE),
                    new UseCaseGroup.Builder()
                            .addUseCase(imageAnalysis)
                            .build(),
                    cm.lifeCycleFragment.getViewLifecycleOwner());
            configs.add(camconfig);
        }
        ConcurrentCamera concurrentCamera = cameraProvider.bindToLifecycle(configs);
        List<androidx.camera.core.Camera> cams = concurrentCamera.getCameras();
        for (int i=0; i<cams.size(); i++) {
            CameraControl cc = cams.get(i).getCameraControl();
            cc.cancelFocusAndMetering();
        }
    }*/

    @SuppressLint({"RestrictedApi", "UnsafeOptInUsageError"})
    private void bindUseCases(@NonNull ProcessCameraProvider cameraProvider) {
        ImageAnalysis.Builder builder = new ImageAnalysis.Builder();
        builder.setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST);
        Size ims = new Size(W, H);
        builder.setDefaultResolution(ims);
        builder.setMaxResolution(ims);
        builder.setTargetResolution(ims);
        Camera2Interop.Extender<ImageAnalysis> ext = new Camera2Interop.Extender<>(builder);
        // try to box just the road area for metering
        ext.setCaptureRequestOption(CaptureRequest.CONTROL_AE_REGIONS, new MeteringRectangle[]{
                new MeteringRectangle((int)Math.floor(W * 0.05f), (int)Math.floor(H * 0.25f),
                                      (int)Math.floor(W * 0.9f),  (int)Math.floor(H * 0.70f), 500)
        });
        ext.setCaptureRequestOption(CaptureRequest.COLOR_CORRECTION_GAINS, new RggbChannelVector(1.75f, 1.75f, 1.75f, 1.75f));
        ext.setCaptureRequestOption(CaptureRequest.COLOR_CORRECTION_MODE, CameraMetadata.COLOR_CORRECTION_MODE_FAST);
        ext.setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, new Range<>(20, 20));
        ImageAnalysis imageAnalysis = builder.build();
        imageAnalysis.setAnalyzer(ContextCompat.getMainExecutor(context), myAnalyzer);

        // f3 uses wide camera.
        CameraSelector cameraSelector = getCameraSelector(cameraType == Camera.CAMERA_TYPE_WIDE);

        androidx.camera.core.Camera camera = cameraProvider.bindToLifecycle(lifeCycleFragment.getViewLifecycleOwner(), cameraSelector,
                imageAnalysis);

        cameraControl = camera.getCameraControl();

        // disable autofocus
        cameraControl.cancelFocusAndMetering();
    }

    @Override
    public void updateProperty(String property, float[] value) {
        if (property.equals("intrinsics")){
            assert value.length == 9 : "invalid intrinsic matrix buffer length";
            setIntrinsics(value);
        }
    }

    public boolean isRunning() {
        return running;
    }

    @SuppressLint("RestrictedApi")
    @Override
    public void stop() {
        // TODO: add pause/resume functionality
        if (!running)
            return;
        cameraProvider.unbindAll();
        running = false;
    }

    @Override
    public void dispose(){
        stop();
        ph.releaseAll();
    }
}
