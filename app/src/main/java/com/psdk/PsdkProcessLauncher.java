package com.psdk;

import android.os.Process;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;

public abstract class PsdkProcessLauncher {
    private static String FIFO_NAME = "psdk_fifo";

    private final String applicationPath;

    private Thread compilerThread;
    private Thread loggingThread;

    public PsdkProcessLauncher(String applicationPath) {
        this.applicationPath = applicationPath;
    }

    public void run(PsdkProcess process, PsdkProcess.InputData processData) {
        String fifoFilename = applicationPath + "/" + FIFO_NAME;

        File fifo = new File(fifoFilename);
        if (fifo.exists()) { fifo.delete(); }

        compilerThread = new Thread(() -> {
            int returnCode = process.run(fifoFilename, processData);
            onComplete(returnCode);
        });
        loggingThread = new Thread(() -> {
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

    public boolean isAlive() {
        return compilerThread != null && compilerThread.isAlive();
    }

    public void killCurrentProcess() {
        Process.sendSignal(Process.myPid(), Process.SIGNAL_KILL);
    }

    public void join() throws InterruptedException {
        if (compilerThread != null) {
            compilerThread.join();
        }
        if (loggingThread != null) {
            loggingThread.join();
        }
    }

    protected abstract void accept(String lineMessage);
    protected void onComplete(int returnCode) {}
    protected void onLogError(Exception e) {}
}
