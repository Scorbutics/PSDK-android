package com.psdk;

import android.os.Process;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;

interface CompletionTask {
    void onComplete(int returnCode);
}

public abstract class PsdkProcessLauncher {
    private static String FIFO_NAME = "psdk_fifo";

    private final String applicationPath;

    private Thread mainThread;
    private Thread loggingThread;

    public PsdkProcessLauncher(String applicationPath) {
        this.applicationPath = applicationPath;
    }

    public void runAsync(PsdkProcess process, PsdkProcess.InputData processData, CompletionTask onComplete) {
        String fifoFilename = applicationPath + "/" + FIFO_NAME;

        File fifo = new File(fifoFilename);
        if (fifo.exists()) { fifo.delete(); }

        mainThread = new Thread(() -> {
            int returnCode = process.run(fifoFilename, processData);
            onComplete.onComplete(returnCode);
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
        mainThread.start();
        loggingThread.start();
    }

    public boolean isAlive() {
        return mainThread != null && mainThread.isAlive();
    }

    public void killCurrentProcess() {
        Process.sendSignal(Process.myPid(), Process.SIGNAL_KILL);
    }

    public void join() throws InterruptedException {
        if (mainThread != null) {
            mainThread.join();
            mainThread = null;
        }
        if (loggingThread != null) {
            loggingThread.join();
            loggingThread = null;
        }
    }

    protected abstract void accept(String lineMessage);
    protected void onLogError(Exception e) {}
}
