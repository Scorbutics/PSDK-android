package com.psdk;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;

public class ProjectCompiler {
    public static String FIFO = "psdk_fifo";

    private static native int compileGame();

    public static void compile() throws IOException {
        compileGame();
        BufferedReader in = new BufferedReader(new FileReader(FIFO));

        while (in.ready()) {
            System.out.println(in.readLine());
        }
        in.close();

    }
}
