package com.psdk;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import android.util.Log;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

public class AppInstall {

	private static String INSTALL_NEEDED = "APP_INSTALL_NEEDED";

	private static void copyAsset(Activity activity, String internalWriteablePath, String filename) throws IOException {
		final InputStream in = activity.getAssets().open(filename);
		File outFile = new File(internalWriteablePath, filename);

		final OutputStream out = new FileOutputStream(outFile);

		byte[] buffer = new byte[1024];
		int read;
		while ((read = in.read(buffer)) != -1) {
			out.write(buffer, 0, read);
		}

		in.close();
		out.flush();
		out.close();
	}

	public static String unpackExtraAssetsIfNeeded(Activity activity, SharedPreferences preferences) {
		if (preferences.getBoolean(INSTALL_NEEDED, true)) {
			final String internalWriteablePath = activity.getFilesDir().getAbsolutePath();

			try {
				final InputStream appInternalData = activity.getAssets().open("app-internal.zip");
				UnzipUtility.unzip(appInternalData, internalWriteablePath);

				copyAsset(activity, internalWriteablePath, "ruby_physfs_patch.rb");

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

	public static boolean requestPermissionsIfNeeded(Activity activity, int requestCode, int acceptAllRequestCode) {
		final List<String> quickPermissions = new ArrayList<>();
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
			quickPermissions.add(Manifest.permission.READ_EXTERNAL_STORAGE);
			if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
				quickPermissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
			}
		}

		boolean allAccess = requestPermissionsNeeded(activity, quickPermissions.toArray(new String[0]), requestCode);
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
			if (!Environment.isExternalStorageManager()) {
				AppInstall.requestActivityPermissions(activity, acceptAllRequestCode);
				allAccess = false;
			}
		}
		return allAccess;
	}

	private static List<String> checkNotGrantedPermissions(Activity activity, String[] permissions) {
		final List<String> notGrantedPermissions = new ArrayList<>();
		for (final String permission : permissions) {
			if (ContextCompat.checkSelfPermission(activity, permission) != PackageManager.PERMISSION_GRANTED) {
				ActivityCompat.shouldShowRequestPermissionRationale(activity, permission);
				notGrantedPermissions.add(permission);
			}
		}
		return notGrantedPermissions;
	}

	private static boolean requestPermissionsNeeded(Activity activity, String[] permissions, int requestCode) {
		List<String> notGrantedPermissions = checkNotGrantedPermissions(activity, permissions);
		if (!notGrantedPermissions.isEmpty()) {
			ActivityCompat.requestPermissions(activity, notGrantedPermissions.toArray(new String[0]), requestCode);
			return false;
		}
		return true;
	}

	public static void requestActivityPermissions(Activity activity, int requestCode) {
		try {
			Uri uri = Uri.parse("package:" + BuildConfig.APPLICATION_ID);
			Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, uri);
			activity.startActivityForResult(intent, requestCode);
		} catch (Exception ex) {
			Intent intent = new Intent();
			intent.setAction(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
			activity.startActivityForResult(intent, requestCode);
		}
	}
}
