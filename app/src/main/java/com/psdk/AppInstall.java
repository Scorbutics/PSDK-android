package com.psdk;

import android.Manifest;
import android.app.Activity;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.util.Log;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

public class AppInstall {

	private static String INSTALL_NEEDED = "APP_INSTALL_NEEDED";

	public static String unpackExtraAssetsIfNeeded(Activity activity, SharedPreferences preferences) {
		if (preferences.getBoolean(INSTALL_NEEDED, true)) {
			final String internalWriteablePath = activity.getFilesDir().getAbsolutePath();

			try {
				final InputStream appInternalData = activity.getAssets().open("app_internal.zip");
				UnzipUtility.unzip(appInternalData, internalWriteablePath);

				SharedPreferences.Editor edit = preferences.edit();
				edit.putBoolean(INSTALL_NEEDED, false);
				edit.commit();
			} catch (IOException exception) {
				Log.e("PSDK", "Error", exception);
				return exception.getMessage();
			}
		}
		return null;
	}

	private static void copyAssetFile(Activity activity, String filename, String externalWriteablePath) throws IOException {
		final InputStream rubyStarter = activity.getAssets().open(filename);
		byte[] buffer = new byte[rubyStarter.available()];
		rubyStarter.read(buffer);
		File targetFile = new File(externalWriteablePath + "/" + filename);
		OutputStream outStream = new FileOutputStream(targetFile);
		outStream.write(buffer);
	}

	public static boolean requestPermissionsIfNeeded(Activity activity, int requestCode) {
		return requestPermissionsNeeded(activity, new String[] { Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE }, requestCode);
	}

	private static boolean requestPermissionsNeeded(Activity activity, String[] permissions, int requestCode) {
		final List<String> notGrantedPermissions = new ArrayList<>();
		for (final String permission : permissions) {
			if (ContextCompat.checkSelfPermission(activity, permission) != PackageManager.PERMISSION_GRANTED) {
				notGrantedPermissions.add(permission);
			}
		}
		if (!notGrantedPermissions.isEmpty()) {
			ActivityCompat.requestPermissions(activity, notGrantedPermissions.toArray(new String[0]), requestCode);
			return false;
		}
		return true;
	}
}
