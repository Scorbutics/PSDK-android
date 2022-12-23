package com.psdk;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public class ZipUtility {

    static class ZipException extends RuntimeException {
        public ZipException(Exception e) {
            super(e);
        }
    }

    public static void zip(String sourceDirectoryPath, String destinationFilePath) {
        File f = new File(destinationFilePath);
        ZipOutputStream out;
        try {
            out = new ZipOutputStream(new FileOutputStream(f));
        } catch (FileNotFoundException fnfe) {
            throw new ZipException(fnfe);
        }

        List<String> allFiles = getFileList(new File(sourceDirectoryPath));
        try {
            for (String file: allFiles) {
                String relativeFilePath = file.split(sourceDirectoryPath)[1];
                if (relativeFilePath.startsWith("/")) { relativeFilePath = relativeFilePath.substring(1); }
                out.putNextEntry(new ZipEntry(relativeFilePath));

                // Transfer bytes from the input file to the output ZIP file
                try (FileInputStream fis = new FileInputStream(file)) {
                    byte[] buffer = new byte[1024];
                    int length;
                    while ((length = fis.read(buffer)) > 0) {
                        out.write(buffer, 0, length);
                    }
                }
                out.closeEntry();
            }
            out.close();
        } catch (IOException ex) {
            throw new ZipException(ex);
        }
    }

    public static void addFilesToExistingZip(InputStream zipFileInput, String zipOutName, InputStream[] ins, String[] names) throws IOException, IllegalAccessException {
        if (ins.length != names.length) {
            throw new IllegalAccessException("There must be as many input streams as names");
        }

        ZipInputStream zin = new ZipInputStream(zipFileInput);
        ZipOutputStream out = new ZipOutputStream(new FileOutputStream(zipOutName));

        byte[] buf = new byte[1024];
        ZipEntry entry = zin.getNextEntry();
        while (entry != null) {
            out.putNextEntry(new ZipEntry(entry.getName()));
            int len;
            while ((len = zin.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
            out.closeEntry();
            entry = zin.getNextEntry();
        }
        zin.close();

        // Compress the files
        for (int i = 0; i < ins.length; i++) {
            InputStream in = ins[i];
            // Add ZIP entry to output stream.
            out.putNextEntry(new ZipEntry(names[i]));
            // Transfer bytes from the file to the ZIP file
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
            // Complete the entry
            out.closeEntry();
            in.close();
        }
        // Complete the ZIP file
        out.close();
    }

    private static List<String> getFileList(File directory) {
        List<String> filenames = new ArrayList<>();
        addFileList(filenames, directory);
        return filenames;
    }

    private static void addFileList(List<String> filenames, File directory) {
        File[] files = directory.listFiles();
        if (files != null && files.length > 0) {
            for (File file : files) {
                if (file.isFile()) {
                    filenames.add(file.getAbsolutePath());
                } else {
                    addFileList(filenames, file);
                }
            }
        }
    }
}
