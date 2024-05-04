package com.psdk;

import static android.content.pm.PackageManager.PERMISSION_GRANTED;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.CheckedTextView;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class MainActivity extends android.app.Activity {
	static {
		System.loadLibrary("jni");
	}

	enum Mode {
		START_GAME,
		COMPILE
	}

	private static final int CHOOSE_FILE_REQUESTCODE = 8777;
	private static final int START_GAME_REQUESTCODE = 8700;
	private static final int COMPILE_GAME_REQUESTCODE = 8000;
	private static final int ACCEPT_PERMISSIONS_REQUESTCODE = 8007;
	private static final int ACTIVITY_ACCEPT_ALL_PERMISSIONS_REQUESTCODE = 8070;

	private Mode m_mode = Mode.COMPILE;
	private String m_permissionErrorMessage;
	private String m_archiveLocation;
	private String m_badArchiveLocation;
	private SharedPreferences m_projectPreferences;
	private boolean m_noExternalPermissions = false;
	private static final String PROJECT_KEY = "PROJECT";
	private static final String PROJECT_LOCATION_STRING = "location";

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		if (m_projectPreferences == null) {
			m_projectPreferences = getSharedPreferences(PROJECT_KEY, MODE_PRIVATE);
		}
		if (isTaskRoot()) {
			final String errorUnpackAssets = AppInstall.unpackExtraAssetsIfNeeded(this, m_projectPreferences);
			if (errorUnpackAssets != null) {
				unableToUnpackAssetsMessage(errorUnpackAssets);
			}

			// We do not really need those permissions...
			// So why always keep asking for them ?
			if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
				m_noExternalPermissions = !AppInstall.requestPermissionsIfNeeded(this, ACCEPT_PERMISSIONS_REQUESTCODE, ACTIVITY_ACCEPT_ALL_PERMISSIONS_REQUESTCODE);
			}
		}
		loadScreen();
	}

	private void shareApplicationOutput(String appPath) {
		String path = appPath == null ? getFullAppLocation() : appPath;
		if (path == null) { return; }
		Intent share = new Intent(Intent.ACTION_SEND);
		share.setType("image/jpeg");
		Uri finalApp = FileProvider.getUriForFile(
				MainActivity.this,
				"com.psdk.starter.provider",
				new File(path));
		share.putExtra(Intent.EXTRA_STREAM, finalApp);

		startActivity(Intent.createChooser(share, "Share App"));
	}

	private void setArchiveLocationValue(String location, boolean triggerEvent) {
		m_archiveLocation = location != null ? location.trim() : null;
		final EditText psdkLocation = (EditText) findViewById(R.id.psdkLocation);
		if (triggerEvent) {
			psdkLocation.setText(m_archiveLocation);
		}
		m_badArchiveLocation = checkFilepathValid(m_archiveLocation);
		boolean validState = lockScreenIfInvalidState();
		if (!validState) {
			final TextView lastErrorLog = (TextView) findViewById(R.id.projectLastError);
			try {
				final byte[] encoded = Files.readAllBytes(Paths.get(getApplicationInfo().dataDir + "/Release/Error.log"));
				lastErrorLog.setText(new String(encoded, StandardCharsets.UTF_8));
			} catch (IOException e) {
				// File does not exist
				lastErrorLog.setText("No log");
			}

			final TextView projectVersion = (TextView) findViewById(R.id.projectVersion);
			if (m_mode == Mode.COMPILE) {
				projectVersion.setText("Unknown (game uncompiled)");
			} else {
				try {
					final byte[] encoded = Files.readAllBytes(Paths.get(getApplicationInfo().dataDir + "/Release/pokemonsdk/version.txt"));
					long versionNumeric = 0;
					try {
						versionNumeric = Long.valueOf(new String(encoded, StandardCharsets.UTF_8).trim());
					} catch (NumberFormatException nfe) {
						projectVersion.setText("INVALID");
					}
					final long majorVersion = versionNumeric >> 8;
					final String versionStr = String.valueOf(majorVersion) + "." + String.valueOf(versionNumeric - (majorVersion << 8) - 256);
					projectVersion.setText(versionStr);
				} catch (IOException e) {
					projectVersion.setText("INVALID");
				}
			}
		} else {
			SharedPreferences.Editor edit = m_projectPreferences.edit();
			edit.putString(PROJECT_LOCATION_STRING, m_archiveLocation);
			edit.apply();
		}

		final TextView projectEngineHealth = findViewById(R.id.projectEngineHealth);
		projectEngineHealth.setText(isValidState()  ? "" : m_permissionErrorMessage + " " + m_badArchiveLocation);
		projectEngineHealth.setBackgroundResource(!isValidState() ? R.drawable.edterr : R.drawable.edtnormal);
	}

	private boolean lockScreenIfInvalidState() {
		boolean validState = isValidState();
		final EditText psdkLocation = (EditText) findViewById(R.id.psdkLocation);
		psdkLocation.setBackgroundResource(!validState ? R.drawable.edterr : R.drawable.edtnormal);
		final CheckedTextView psdkLocationValid = (CheckedTextView) findViewById(R.id.psdkLocationValid);
		psdkLocationValid.setChecked(validState);
		final Button clickButton = (Button) findViewById(R.id.startGame);
		clickButton.setEnabled(validState);
		final LinearLayout projectInfoLayout = (LinearLayout) findViewById(R.id.informationLayout);
		projectInfoLayout.setVisibility(!validState ? View.INVISIBLE : View.VISIBLE);
		final LinearLayout errorLogLayout = (LinearLayout) findViewById(R.id.compilationLogLayout);
		errorLogLayout.setVisibility(!validState ? View.INVISIBLE : View.VISIBLE);
		final LinearLayout lastEngineDebugLogLayout = (LinearLayout) findViewById(R.id.lastEngineDebugLogLayout);
		lastEngineDebugLogLayout.setVisibility(!validState ? View.INVISIBLE : View.VISIBLE);
		return validState;
	}

	public void openFile(String mimeType) {
		Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
		intent.setType(mimeType);
		intent.addCategory(Intent.CATEGORY_OPENABLE);
		intent.setType("*/*");
		Intent chooseFile = Intent.createChooser(intent, "Choose a file");

		try {
			startActivityForResult(chooseFile, CHOOSE_FILE_REQUESTCODE);
		} catch (android.content.ActivityNotFoundException ex) {
			Toast.makeText(getApplicationContext(), "No suitable File Manager was found.", Toast.LENGTH_LONG).show();
		}
	}

	@Override
	public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
		super.onRequestPermissionsResult(requestCode, permissions, grantResults);
		switch (requestCode) {
			case ACCEPT_PERMISSIONS_REQUESTCODE:
				if (Arrays.stream(grantResults).anyMatch(i -> i != PERMISSION_GRANTED)) {
					m_permissionErrorMessage = "You must have read and write to external storage permissions in order to use PSDK";
				}
				AppInstall.requestActivityPermissions(this, ACTIVITY_ACCEPT_ALL_PERMISSIONS_REQUESTCODE);
				break;
			default:
				break;
		}
	}

	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent data) {
		final TextView lastErrorLog = (TextView) findViewById(R.id.projectLastError);
		switch (requestCode) {
			case ACTIVITY_ACCEPT_ALL_PERMISSIONS_REQUESTCODE:
				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
					if (!Environment.isExternalStorageManager()) {
						m_permissionErrorMessage = "You must have all file access permissions in order to use PSDK";
					}
				}
				loadScreen();
				break;
			case CHOOSE_FILE_REQUESTCODE:
				if (resultCode != RESULT_OK) {
					return;
				}
				final String path = new PathUtil(getApplicationContext()).getPathFromUri(data.getData());
				m_mode = Mode.COMPILE;
				final Button startButton = findViewById(R.id.startGame);
				startButton.setText(m_mode == Mode.START_GAME ? "start" : "compile");
				setArchiveLocationValue(path, true);
				break;
			case COMPILE_GAME_REQUESTCODE:
			case START_GAME_REQUESTCODE:
				if (resultCode != RESULT_OK) {
					lastErrorLog.setText("Error starting game activity, code : " + resultCode);
					return;
				}
				break;
			default:
				break;
		}
	}

	private boolean isValidState() {
		return m_permissionErrorMessage == null && m_badArchiveLocation == null;
	}

	private void loadScreen() {
		setContentView(R.layout.main);
		if (m_projectPreferences == null) {
			throw new NullPointerException("Bad application initialization: unable to get valid project preferences");
		}
		final String psdkLocation = m_projectPreferences.getString(PROJECT_LOCATION_STRING,
						Environment.getExternalStorageDirectory().getAbsolutePath() + "/PSDK/");
		setArchiveLocationValue(psdkLocation, true);

		m_mode = computeCurrentGameState();
		final Button startButton = findViewById(R.id.startGame);
		startButton.setText(m_mode == Mode.START_GAME ? "start" : "compile");
		startButton.setOnClickListener(v -> {
			if (m_mode == Mode.COMPILE) {
				final Intent compileIntent = new Intent(this, CompileActivity.class);
				compileIntent.putExtra("EXECUTION_LOCATION", getExecutionLocation());
				compileIntent.putExtra("ARCHIVE_LOCATION", m_archiveLocation);
				compileIntent.putExtra("OUTPUT_ARCHIVE_LOCATION", getFullAppLocation());
				startActivityForResult(compileIntent, COMPILE_GAME_REQUESTCODE);
				return;
			}

			final Intent switchActivityIntent = new Intent(MainActivity.this, android.app.NativeActivity.class);
			switchActivityIntent.putExtra("EXECUTION_LOCATION", getExecutionLocation());
			switchActivityIntent.putExtra("INTERNAL_STORAGE_LOCATION", getFilesDir().getPath());
			switchActivityIntent.putExtra("EXTERNAL_STORAGE_LOCATION", getExternalFilesDir(null).getPath());
			final String outputFilename = getExecutionLocation() + "/last_stdout.log";
			switchActivityIntent.putExtra("OUTPUT_FILENAME", outputFilename);

			try {
				FileWriter fw = new FileWriter(outputFilename, false);
				fw.flush();
				final String startScript = PsdkProcess.readFromAssets(this, "start.rb");
				switchActivityIntent.putExtra("START_SCRIPT", startScript);
				MainActivity.this.startActivityForResult(switchActivityIntent, START_GAME_REQUESTCODE);
			} catch (Exception e) {
				Toast.makeText(getApplicationContext(), e.getLocalizedMessage(), Toast.LENGTH_LONG).show();
			}
		});
		startButton.setEnabled(isValidState());

		final Button locatePsdkButton = (Button) findViewById(R.id.locatePSDK);
		locatePsdkButton.setOnClickListener(v -> openFile("*/*"));

		final EditText psdkLocationText = (EditText) findViewById(R.id.psdkLocation);
		psdkLocationText.addTextChangedListener(new TextWatcher() {
			@Override
			public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
			@Override
			public void onTextChanged(CharSequence s, int start, int before, int count) {
				setArchiveLocationValue(psdkLocationText.getText().toString(), false);
			}
			@Override
			public void afterTextChanged(Editable s) {}
		});

		final StringBuilder sb = new StringBuilder();
		for (final String abi : Build.SUPPORTED_ABIS) {
			sb.append(abi + "/ ");
		}
		final TextView abiVersion = (TextView) findViewById(R.id.deviceAbiVersion);
		abiVersion.setText(sb.toString());

		final TextView rubyVersion = (TextView) findViewById(R.id.engineRubyVersion);
		rubyVersion.setText(RubyInfo.getRubyVersion());

		final TextView rubyPlatform = (TextView) findViewById(R.id.engineRubyPlatform);
		rubyPlatform.setText(RubyInfo.getRubyPlatform());

		final TextView lastEngineDebugLogs = (TextView) findViewById(R.id.lastEngineDebugLogs);
		Path lastStdoutLog = Paths.get(getExecutionLocation() + "/last_stdout.log");
		if (Files.exists(lastStdoutLog)) {
			try {
				lastEngineDebugLogs.setText(new String(Files.readAllBytes(lastStdoutLog), StandardCharsets.UTF_8));
			} catch (Exception exception) {
				lastEngineDebugLogs.setText("Unable to read last stdout log: " + exception.getLocalizedMessage());
			}
		} else {
			lastEngineDebugLogs.setText("No log");
		}

		final TextView shareApplication = (TextView) findViewById(R.id.shareApplication);
		final File app = new File(getFullAppLocation());
		if (app.exists()) {
			shareApplication.setVisibility(View.VISIBLE);
			shareApplication.setOnClickListener(v -> shareApplicationOutput(getFullAppLocation()));
		} else {
			lastEngineDebugLogs.setText("No log");
		}

	}

	private Mode computeCurrentGameState() {
		Path path = Paths.get(getApplicationInfo().dataDir + "/Release");
		return Files.exists(path) ? Mode.START_GAME : Mode.COMPILE;
	}

	private String getExecutionLocation() {
		return getApplicationInfo().dataDir;
	}

	private String getFullAppLocation() {
		return m_archiveLocation != null ? m_archiveLocation.substring(0, m_archiveLocation.lastIndexOf('/')) + "/game-compiled.zip" : null;
	}

	private String checkFilepathValid(final String filepath) {
		if (filepath == null) {
			return "File path not provided";
		}
		final File finalFile = new File(filepath);
		if (!finalFile.exists() || !finalFile.canRead()) {
			return "Error : file at filepath " + filepath + " does not exist or not readable";
		}

		final String absPath = finalFile.getAbsolutePath();
		final int sep = absPath.lastIndexOf(File.separator);
		final String filename = absPath.substring(sep + 1).trim();
		if (!filename.endsWith(".psa")) {
			return "Error : selected file at filepath " + filepath + " is not a 'psa' file : '" + filename + "'";
		}

		try {
			BufferedInputStream bufIn = new BufferedInputStream(new FileInputStream(finalFile));
			bufIn.mark(512);
			ZipInputStream zipIn = new ZipInputStream(bufIn);
			boolean foundSpecial = false;
			ZipEntry entry;
			while ((entry = zipIn.getNextEntry()) != null) {
				if ("pokemonsdk/version.txt".equals(entry.getName())) {
					foundSpecial = true;
					break;
				}
			}

			if (!foundSpecial) {
				return "pokemonsdk folder not found in the archive";
			}
			return null;
		} catch (IOException e) {
			return "Error while reading the archive: " + e.getLocalizedMessage();
		}
	}

	private void unableToUnpackAssetsMessage(final String error) {
		Toast.makeText(getApplicationContext(), "Unable to unpack application assets : " + error, Toast.LENGTH_LONG).show();
	}

}
