package com.psdk;

import android.content.Context;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

public class PSDKScript {
    private static native int exec(String scriptContent, String fifo, String internalWriteablePath, String externalWriteablePath, String psdkLocation);
    public static int execute(Context context, String assetScriptName, String fifo, String internalWriteablePath, String externalWriteablePath, String psdkLocation) throws IOException {
        BufferedReader asset = new BufferedReader(new InputStreamReader(context.getAssets().open(assetScriptName)));

        StringBuffer scriptContent = new StringBuffer();
        String s;
        while ((s = asset.readLine()) != null) {
            scriptContent.append(s + "\n");
        }
        return exec(scriptContent.toString(), fifo, internalWriteablePath, externalWriteablePath, psdkLocation);
    }
}
