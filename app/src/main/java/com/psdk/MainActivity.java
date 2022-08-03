package com.psdk;

import android.content.Intent;
import android.content.SharedPreferences;
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

import org.w3c.dom.Text;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class MainActivity extends android.app.Activity {
	static {
		System.loadLibrary("ruby-info");
	}
	private static final int CHOOSE_FILE_REQUESTCODE = 8777;
	private static final int START_GAME_REQUESTCODE = 8700;
	private static final int ACCEPT_PERMISSIONS_REQUESTCODE = 8007;

	private String m_gameRbLocation;
	private boolean m_badGameRbLocation = true;
	private SharedPreferences m_projectPreferences;
	private static final String PROJECT_KEY = "PROJECT";
	private static final String PROJECT_LOCATION_STRING = "location";

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		m_projectPreferences = getSharedPreferences(PROJECT_KEY, MODE_PRIVATE);
		final String errorUnpackAssets = AppInstall.unpackExtraAssetsIfNeeded(this, m_projectPreferences);
		if (errorUnpackAssets != null) {
			unableToUnpackAssetsMessage(errorUnpackAssets);
		}
		if (AppInstall.requestPermissionsIfNeeded(this, ACCEPT_PERMISSIONS_REQUESTCODE)) {
			loadScreen();
		}
	}

	private void cannotLoadProjectMessage(String errorMessage) {
		final TextView projectEngineHealth = (TextView) findViewById(R.id.projectEngineHealth);
		projectEngineHealth.setText(errorMessage);
		Toast.makeText(getApplicationContext(), "Unable to load project : " + errorMessage, Toast.LENGTH_LONG).show();
	}

	private String getSelectedPsdkFolderLocation() {
		return m_gameRbLocation.substring(0, m_gameRbLocation.lastIndexOf(File.separator));
	}

	private void setGameRbLocationValue(String gameRbLocation, boolean triggerEvent) {
		m_gameRbLocation = gameRbLocation != null ? gameRbLocation.trim() : null;
		final EditText psdkLocation = (EditText) findViewById(R.id.psdkLocation);
		if (triggerEvent) {
			psdkLocation.setText(m_gameRbLocation);
		}
		m_badGameRbLocation = checkFilepathValid(m_gameRbLocation) == null;
		psdkLocation.setBackgroundResource(m_badGameRbLocation ? R.drawable.edterr : R.drawable.edtnormal);
		final CheckedTextView psdkLocationValid = (CheckedTextView) findViewById(R.id.psdkLocationValid);
		psdkLocationValid.setChecked(!m_badGameRbLocation);
		final Button clickButton = (Button) findViewById(R.id.startGame);
		clickButton.setEnabled(!m_badGameRbLocation);
		final LinearLayout projectInfoLayout = (LinearLayout) findViewById(R.id.informationLayout);
		projectInfoLayout.setVisibility(m_badGameRbLocation ? View.INVISIBLE : View.VISIBLE);
		final LinearLayout errorLogLayout = (LinearLayout) findViewById(R.id.errorLogLayout);
		errorLogLayout.setVisibility(m_badGameRbLocation ? View.INVISIBLE : View.VISIBLE);
		if (!m_badGameRbLocation) {
			final String psdkFolder = getSelectedPsdkFolderLocation();
			final TextView lastErrorLog = (TextView) findViewById(R.id.projectLastError);
			try {
				final byte[] encoded = Files.readAllBytes(Paths.get(psdkFolder + "/Error.log"));
				lastErrorLog.setText(new String(encoded, StandardCharsets.UTF_8));
			} catch (IOException e) {
				// File does not exist
				lastErrorLog.setText("No log");
			}

			final TextView projectVersion = (TextView) findViewById(R.id.projectVersion);
			try {
				final byte[] encoded = Files.readAllBytes(Paths.get(psdkFolder + "/pokemonsdk/version.txt"));
				final long versionNumeric = Long.valueOf(new String(encoded, StandardCharsets.UTF_8));
				final long majorVersion = versionNumeric >> 8;
				final String versionStr = String.valueOf(majorVersion) + "." + String.valueOf(versionNumeric - (majorVersion << 8) - 256);
				projectVersion.setText(versionStr);
			} catch (IOException e) {
				projectVersion.setText("INVALID");
			}

		}

	}

	public void openFile(String mimeType) {
		Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
		intent.setType(mimeType);
		intent.addCategory(Intent.CATEGORY_OPENABLE);

		// special intent for Samsung file manager
		Intent sIntent = new Intent("com.sec.android.app.myfiles.PICK_DATA");
		// if you want any file type, you can skip next line
		sIntent.putExtra("CONTENT_TYPE", mimeType);
		sIntent.addCategory(Intent.CATEGORY_DEFAULT);

		Intent chooserIntent;
		if (getPackageManager().resolveActivity(sIntent, 0) != null){
			// it is device with Samsung file manager
			chooserIntent = Intent.createChooser(sIntent, "Open file");
			chooserIntent.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[] { intent});
		} else {
			chooserIntent = Intent.createChooser(intent, "Open file");
		}

		try {
			startActivityForResult(chooserIntent, CHOOSE_FILE_REQUESTCODE);
		} catch (android.content.ActivityNotFoundException ex) {
			Toast.makeText(getApplicationContext(), "No suitable File Manager was found.", Toast.LENGTH_LONG).show();
		}
	}

	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent data) {
		switch (requestCode) {
			case ACCEPT_PERMISSIONS_REQUESTCODE:
				if (resultCode == RESULT_CANCELED) {
					loadScreen();
				}
				break;
			case CHOOSE_FILE_REQUESTCODE:
				if (resultCode != RESULT_OK) {
					return;
				}
				final String path = new PathUtil(getApplicationContext()).getPathFromUri(data.getData());
				setGameRbLocationValue(path, true);
				break;
			case START_GAME_REQUESTCODE:
				final TextView lastErrorLog = (TextView) findViewById(R.id.projectLastError);
				if (resultCode != RESULT_OK) {
					lastErrorLog.setText("Error starting game activity, code : " + resultCode);
					return;
				}
				break;
			default:
				break;
		}
	}

	private void loadScreen() {
		setContentView(R.layout.main);
		final String psdkLocation = m_projectPreferences.getString(PROJECT_LOCATION_STRING,
						Environment.getExternalStorageDirectory().getAbsolutePath() + "/PSDK/Game.rb");
		setGameRbLocationValue(psdkLocation, true);

		final Button clickButton = (Button) findViewById(R.id.startGame);
		clickButton.setOnClickListener(v -> {
			if (m_badGameRbLocation) {
				invalidGameRbMessage();
				return;
			}

			SharedPreferences.Editor edit = m_projectPreferences.edit();
			edit.putString(PROJECT_LOCATION_STRING, m_gameRbLocation);
			edit.commit();
			final Intent switchActivityIntent = new Intent(MainActivity.this, android.app.NativeActivity.class);
			switchActivityIntent.putExtra("PSDK_LOCATION", getSelectedPsdkFolderLocation());
			try {
				try {
					FileWriter fw = new FileWriter(getExternalFilesDir(null).getAbsolutePath() + "/last_stdout.log", false);
					fw.flush();
				} catch (Exception e) {
					Toast.makeText(getApplicationContext(), e.getLocalizedMessage(), Toast.LENGTH_LONG).show();
				}
				MainActivity.this.startActivityForResult(switchActivityIntent, START_GAME_REQUESTCODE);
				} catch (android.content.ActivityNotFoundException ex) {
					Toast.makeText(getApplicationContext(), ex.getLocalizedMessage(), Toast.LENGTH_LONG).show();
				}
		});

		final Button locatePsdkButton = (Button) findViewById(R.id.locatePSDK);
		locatePsdkButton.setOnClickListener(v -> openFile("*/*"));

		final EditText psdkLocationText = (EditText) findViewById(R.id.psdkLocation);
		psdkLocationText.addTextChangedListener(new TextWatcher() {
			@Override
			public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
			@Override
			public void onTextChanged(CharSequence s, int start, int before, int count) {
				setGameRbLocationValue(psdkLocationText.getText().toString(), false);
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
		try {
			lastEngineDebugLogs.setText(new String(Files.readAllBytes(Paths.get(getExternalFilesDir(null).getAbsolutePath() + "/last_stdout.log")), StandardCharsets.UTF_8));
		} catch (Exception exception) {
			lastEngineDebugLogs.setText(exception.getLocalizedMessage());
		}
	}

	private File checkFilepathValid(final String filepath) {
		if (filepath == null) {
			return null;
		}
		final File finalFile = new File(filepath);
		if (!finalFile.exists()) {
			System.out.println("Error : file at filepath " + filepath + " does not exist");
			return null;
		}
		final String absPath = finalFile.getAbsolutePath();
		final int sep = absPath.lastIndexOf(File.separator);
		final String filename = absPath.substring(sep + 1).trim();
		if ("Game.rb".equals(filename)) {
			return finalFile;
		}
		System.out.println("Error : selected file at filepath " + filepath + " is not 'Game.rb', but '" + filename + "'");
		return null;
	}

	private void invalidGameRbMessage() {
		Toast.makeText(getApplicationContext(), "You must select the Game.rb file at the root of your PSDK project", Toast.LENGTH_LONG).show();
	}

	private void invalidProjectCompatibilityMessage() {
		Toast.makeText(getApplicationContext(), "Your PSDK project is incompatible with the current application engine", Toast.LENGTH_LONG).show();
	}

	private void unableToUnpackAssetsMessage(final String error) {
		Toast.makeText(getApplicationContext(), "Unable to unpack application assets : " + error, Toast.LENGTH_LONG).show();
	}

}
