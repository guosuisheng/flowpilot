package ai.flow.android;

import ai.flow.android.sensor.CameraManager;
import ai.flow.android.sensor.SensorManager;
import ai.flow.android.vision.ONNXModelRunner;
import ai.flow.android.vision.SNPEModelRunner;
import ai.flow.android.vision.THNEEDModelRunner;
import ai.flow.app.FlowUI;
import ai.flow.common.ParamsInterface;
import ai.flow.common.Path;
import ai.flow.common.transformations.Camera;
import ai.flow.common.transformations.Model;
import ai.flow.common.utils;
import ai.flow.hardware.HardwareManager;
import ai.flow.launcher.Launcher;
import ai.flow.modeld.*;
import ai.flow.sensor.SensorInterface;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.os.Process;
import android.os.*;
import android.provider.Settings;
import android.system.ErrnoException;
import android.system.Os;
import android.telephony.TelephonyManager;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Toast;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentTransaction;
import com.badlogic.gdx.backends.android.AndroidApplicationConfiguration;
import com.badlogic.gdx.backends.android.AndroidFragmentApplication;
import com.termux.shared.termux.TermuxConstants;

import org.acra.ACRA;
import org.acra.ErrorReporter;
import org.jetbrains.annotations.NotNull;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.*;


/** Launches the main android flowpilot application. */
public class AndroidLauncher extends FragmentActivity implements AndroidFragmentApplication.Callbacks {
	public static Map<String, SensorInterface> sensors;
	public static Context appContext;
	public static ParamsInterface params;

	public void LoadIntrinsicsFromFile() {
		File file = new File(Path.getFlowPilotRoot(), utils.F2 ? "camerainfo.medium.txt" : "camerainfo.big.txt");
		if (file.exists() == false) return;
		try {
			BufferedReader br = new BufferedReader(new FileReader(file));
			String line;

			float[] numbers = new float[5];
			int i = 0;

			while ((line = br.readLine()) != null && i < 5) {
				numbers[i] = Float.parseFloat(line);
				i++;
			}
			br.close();

			Camera.FocalX = numbers[0];
			Camera.FocalY = numbers[1];
			Camera.CenterX = numbers[2];
			Camera.CenterY = numbers[3];
			Camera.UseCameraID = (int)numbers[4];

			// recalculate values using loaded new stuff
			Camera.actual_cam_focal_length = (Camera.FocalX + Camera.FocalY) * 0.5f;
			Camera.digital_zoom_apply = Camera.actual_cam_focal_length / (utils.F2 ? Model.MEDMODEL_F2_FL : Model.MEDMODEL_FL);
			Camera.OffsetX = Camera.CenterX - (Camera.frameSize[0]*0.5f);
			Camera.OffsetY = Camera.CenterY - (Camera.frameSize[1]*0.5f);

			Camera.CameraIntrinsics = new float[] {
					Camera.FocalX, 0.0f, Camera.frameSize[0] * 0.5f + Camera.OffsetX * Camera.digital_zoom_apply,
					0.0f, Camera.FocalY, Camera.frameSize[1] * 0.5f + Camera.OffsetY * Camera.digital_zoom_apply,
					0.0f,   0.0f, 1.0f
			};

			Camera.cam_intrinsics = Nd4j.createFromArray(new float[][]{
					{ Camera.CameraIntrinsics[0],  0.0f,  Camera.CameraIntrinsics[2]},
					{0.0f,  Camera.CameraIntrinsics[4],  Camera.CameraIntrinsics[5]},
					{0.0f,  0.0f,  1.0f}
			});

		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	@SuppressLint("HardwareIds")
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		appContext = getApplicationContext();

		// set environment variables from intent extras.
		Bundle bundle = getIntent().getExtras();
		if (bundle != null) {
			for (String key : bundle.keySet()) {
				if (bundle.get(key) == null)
					continue;
				try {
					Os.setenv(key, (String)bundle.get(key), true);
				} catch (Exception ignored) {
				}
			}
		}

		try {
			Os.setenv("USE_GPU", "1", true);
		} catch (ErrnoException e) {
			throw new RuntimeException(e);
		}

		Window activity = getWindow();
		HardwareManager androidHardwareManager = new AndroidHardwareManager(activity);
		androidHardwareManager.enableScreenWakeLock(true);
		activity.setSustainedPerformanceMode(true);
		activity.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

		params = ParamsInterface.getInstance();

		TelephonyManager telephonyManager = (TelephonyManager)getSystemService(Context.TELEPHONY_SERVICE);
		String dongleID = "";
		if (telephonyManager != null) {
			dongleID = Settings.Secure.getString(appContext.getContentResolver(), Settings.Secure.ANDROID_ID);
		}

		// populate device specific info.
		params.put("DongleId", dongleID);
		params.put("DeviceManufacturer", Build.MANUFACTURER);
		params.put("DeviceModel", Build.MODEL);

		utils.F2 = !params.getBool("F3");

		// get camera intrinsics from file if they exist
		LoadIntrinsicsFromFile();

		AndroidApplicationConfiguration configuration = new AndroidApplicationConfiguration();
		CameraManager cameraManager, cameraManagerWide = null;
		SensorManager sensorManager = new SensorManager(appContext, 100);
		cameraManager = new CameraManager(getApplication().getApplicationContext(), utils.F2 || Camera.FORCE_TELE_CAM_F3 ? Camera.CAMERA_TYPE_ROAD : Camera.CAMERA_TYPE_WIDE);
		CameraManager finalCameraManager = cameraManager; // stupid java
		sensors = new HashMap<String, SensorInterface>() {{
			put("roadCamera", finalCameraManager);
			put("wideRoadCamera", finalCameraManager); // use same camera until we move away from wide camera-only mode.
			put("motionSensors", sensorManager);
		}};

		int pid = Process.myPid();

		String modelPath = Path.getModelDir();

		ModelRunner model = null;
		boolean useGPU = true; // always use gpus on android phones.
		switch (utils.Runner) {
			case SNPE:
				model = new SNPEModelRunner(getApplication(), modelPath, useGPU);
				break;
			case TNN:
				model = new TNNModelRunner(modelPath, useGPU);
				break;
			case ONNX:
				model = new ONNXModelRunner(modelPath, useGPU);
				break;
			case THNEED:
				model = new THNEEDModelRunner(modelPath, getApplication());
				break;
			case EXTERNAL_TINYGRAD:
				// start the special model parser
				/*Intent intent = new Intent();
				intent.setClassName(TermuxConstants.TERMUX_PACKAGE_NAME, TermuxConstants.TERMUX_APP.RUN_COMMAND_SERVICE_NAME);
				intent.setAction(TermuxConstants.TERMUX_APP.RUN_COMMAND_SERVICE.ACTION_RUN_COMMAND);
				intent.putExtra(TermuxConstants.TERMUX_APP.RUN_COMMAND_SERVICE.EXTRA_COMMAND_PATH, "/data/data/com.termux/files/usr/bin/bash");
				intent.putExtra(TermuxConstants.TERMUX_APP.RUN_COMMAND_SERVICE.EXTRA_ARGUMENTS, new String[]{"run"});
				intent.putExtra(TermuxConstants.TERMUX_APP.RUN_COMMAND_SERVICE.EXTRA_WORKDIR, "/data/data/com.termux/files/home/flowpilot_env_root/root/flowpilot/thneedrunner");
				intent.putExtra(TermuxConstants.TERMUX_APP.RUN_COMMAND_SERVICE.EXTRA_BACKGROUND, true);
				intent.putExtra(TermuxConstants.TERMUX_APP.RUN_COMMAND_SERVICE.EXTRA_SESSION_ACTION, "0");
				intent.putExtra(TermuxConstants.TERMUX_APP.RUN_COMMAND_SERVICE.EXTRA_COMMAND_LABEL, "run thneedrunner");
				startService(intent);*/
				break;
		}

		ModelExecutor modelExecutor;
		if (utils.Runner == utils.USE_MODEL_RUNNER.EXTERNAL_TINYGRAD)
			modelExecutor = new ModelExecutorExternal();
		else
			modelExecutor = utils.F2 ? new ModelExecutorF2(model) : new ModelExecutorF3(model);
		Launcher launcher = new Launcher(sensors, modelExecutor);

		ErrorReporter ACRAreporter = ACRA.getErrorReporter();
		ACRAreporter.putCustomData("DongleId", dongleID);
		ACRAreporter.putCustomData("AndroidAppVersion", ai.flow.app.BuildConfig.VERSION_NAME);
		ACRAreporter.putCustomData("FlowpilotVersion", params.getString("Version"));
		ACRAreporter.putCustomData("VersionMisMatch", checkVersionMisMatch().toString());

		ACRAreporter.putCustomData("GitCommit", params.getString("GitCommit"));
		ACRAreporter.putCustomData("GitBranch", params.getString("GitBranch"));
		ACRAreporter.putCustomData("GitRemote", params.getString("GitRemote"));

		MainFragment fragment = new MainFragment(new FlowUI(launcher, androidHardwareManager, pid));
		cameraManager.setLifeCycleFragment(fragment);
		if (cameraManagerWide != null) cameraManagerWide.setLifeCycleFragment(fragment);
		FragmentTransaction trans = getSupportFragmentManager().beginTransaction();
		trans.replace(android.R.id.content, fragment);
		trans.commit();
	}

	public static class MainFragment extends AndroidFragmentApplication {
		FlowUI flowUI;

		MainFragment(FlowUI flowUI) {
			this.flowUI = flowUI;
		}

		@Override
		public View onCreateView(@NotNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
			return initializeForView(flowUI);
		}
	}

	@Override
	protected void attachBaseContext(Context base) {
		super.attachBaseContext(base);
	}

	private Boolean checkVersionMisMatch() {
		// check version mismatch between android app and github repo project.
		if (!params.getString("Version").equals(ai.flow.app.BuildConfig.VERSION_NAME)) {
			Toast.makeText(appContext, "WARNING: App version mismatch detected. Make sure you are using compatible versions of apk and github repo.", Toast.LENGTH_LONG).show();
			return true;
		}
		return false;
	}

	@Override
	public void exit() {
	}

	@Override
	public void onBackPressed() {
		return;
	}
}

