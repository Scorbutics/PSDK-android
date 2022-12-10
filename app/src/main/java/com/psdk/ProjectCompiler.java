package com.psdk;

public class ProjectCompiler {
    public static native int compile(String fifo, String internalWriteablePath, String externalWriteablePath, String psdkLocation);
}
