package com.psdk;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;

public abstract class PsdkProcessLauncher {
    private static String FIFO_NAME = "psdk_fifo";

    private final String applicationPath;


    public PsdkProcessLauncher(String applicationPath) {
        this.applicationPath = applicationPath;
    }

    public void run(PsdkProcess process) {
        String fifoFilename = applicationPath + "/" + FIFO_NAME;

        File fifo = new File(fifoFilename);
        if (fifo.exists()) { fifo.delete(); }

        Thread compilerThread = new Thread(() -> {
            int returnCode = process.run(fifoFilename);
            onComplete(returnCode);
        });
        Thread loggingThread = new Thread(() -> {
            try {
                while (!fifo.exists()) {  }
                BufferedReader in = new BufferedReader(new FileReader(fifo));
                String msg;
                while ((msg = in.readLine()) != null) {
                    accept(msg);
                }
                in.close();
            } catch (IOException e) {
                e.printStackTrace();
                onLogError(e);
                throw new RuntimeException(e);
            }
        });
        compilerThread.start();
        loggingThread.start();
    }

    protected abstract void accept(String lineMessage);
    protected void onComplete(int returnCode) {}
    protected void onLogError(Exception e) {}
}
