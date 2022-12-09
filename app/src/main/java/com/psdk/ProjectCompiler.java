package com.psdk;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;

public class ProjectCompiler {
    static {
        System.loadLibrary("jni-psdk-service");
    }
    public static String FIFO_NAME = "psdk_fifo";

    private static native int compileGame(String fifo, String internalWriteablePath, String externalWriteablePath, String psdkLocation);

    public static void compile(String applicationPath, String internalWriteablePath, String externalWriteablePath, String psdkLocation) throws IOException, InterruptedException {
        StringBuffer sb = new StringBuffer();
        String fifo = applicationPath + "/" + FIFO_NAME;
        Thread thread = new Thread() {
            @Override
            public void run() {
                compileGame(fifo, internalWriteablePath, externalWriteablePath, psdkLocation);
            }
        };
        File file = new File(fifo);
        if (file.exists()) {
            file.delete();
        }
        thread.start();

        Thread threadLog = new Thread() {
            public void run() {
                try {
                    while (!file.exists()) {  }
                    BufferedReader in = new BufferedReader(new FileReader(fifo));
                    String msg;
                    while ((msg = in.readLine()) != null) {
                        System.out.println(msg);
                        // TODO append this into a String roundbuffer and display it inside the app
                        //sb.append(msg);
                    }
                    in.close();
                } catch (IOException e) {
                    sb.append(e.getLocalizedMessage());
                }
            }
        };
        threadLog.start();

        //thread.join();
        //threadLog.join();
    }
}
