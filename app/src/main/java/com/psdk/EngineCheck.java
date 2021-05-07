package com.psdk;

import android.app.Activity;
import android.util.Log;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

public class EngineCheck {
	private static String[] LIBRARIES = new String[] { "libLiteCGSS_engine.so", "LiteRGSS.so" };

	private static native String tryLoadLibrary(Activity activity, String library);

	private static boolean copyLibraryToInternal(Activity activity, String projectLocation, String library) {
		try {
			Log.i("PSDK", activity.getApplicationInfo().dataDir + "/files/" + library);
			Files.copy(Paths.get(projectLocation + "/" + library), Paths.get(activity.getApplicationInfo().dataDir + "/files/" + library), StandardCopyOption.REPLACE_EXISTING);
			return true;
		} catch (IOException exception) {
			Log.e("PSDK", "Error", exception);
			return false;
		}
	}

	public static String tryLoad(Activity activity, String projectLocation) {
		final StringBuilder sb = new StringBuilder();
		for (final String library : LIBRARIES) {
			if (!copyLibraryToInternal(activity, projectLocation, library)) {
				sb.append("Unable to copy library '" + library + "' from project to application data\n");
			}
			String errorLoadLib = tryLoadLibrary(activity, library);
			if (errorLoadLib != null) {
				sb.append(errorLoadLib).append("\n");
			}
		}
		final String result = sb.toString();
		return result.isEmpty() ? null : result;
	}
}
