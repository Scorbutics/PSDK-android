package com.psdk;

import android.content.Context;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

public class PsdkProcess {

    static class InputData {
        private String internalWriteablePath;
        private String externalWriteablePath;
        private String psdkLocation;

        public InputData(String internalWriteablePath, String externalWriteablePath, String psdkLocation) {
            this.internalWriteablePath = internalWriteablePath;
            this.externalWriteablePath = externalWriteablePath;
            this.psdkLocation = psdkLocation;
        }
    }

    private final String assetScriptName;
    private final String scriptContent;

    PsdkProcess(Context context, String assetScriptName) throws IOException {
        this.assetScriptName = assetScriptName;
        this.scriptContent = readFromAssets(context, assetScriptName);
    }

    private static native int exec(String scriptContent, String fifo, String internalWriteablePath, String externalWriteablePath, String psdkLocation);

    public int run(String fifo, InputData processData) {
        return exec(scriptContent, fifo, processData.internalWriteablePath, processData.externalWriteablePath, processData.psdkLocation);
    }

    public static String readFromAssets(Context context, String assetScriptName) throws IOException {
        BufferedReader asset = new BufferedReader(new InputStreamReader(context.getAssets().open(assetScriptName)));

        StringBuffer scriptContent = new StringBuffer();
        String s;
        while ((s = asset.readLine()) != null) {
            scriptContent.append(s + "\n");
        }
        return scriptContent.toString();
    }
}
