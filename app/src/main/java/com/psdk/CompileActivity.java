package com.psdk;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

public class CompileActivity extends Activity {
    private PsdkProcessLauncher m_psdkProcessLauncher;
    private String m_applicationPath;
    private String m_internalWriteablePath;
    private String m_executionLocation;
    private String m_archiveLocation;
    private String m_outputArchiveLocation;

    private static final String SCRIPT = "compile.rb";

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.compiler);
        m_applicationPath = getApplicationInfo().dataDir;
        m_internalWriteablePath = getFilesDir().getPath();
        m_executionLocation = getIntent().getStringExtra("EXECUTION_LOCATION");
        m_archiveLocation = getIntent().getStringExtra("ARCHIVE_LOCATION");
        m_outputArchiveLocation = getIntent().getStringExtra("OUTPUT_ARCHIVE_LOCATION");

        TextView compilationLog = findViewById(R.id.compilationLog);
        ScrollView compilationScrollView = findViewById(R.id.compilationScrollView);
        compilationLog.setSelected(true);
        TextView compilationEndState = findViewById(R.id.compilationEndState);

        final Activity self = this;
        m_psdkProcessLauncher = new PsdkProcessLauncher(m_applicationPath) {
            @Override
            protected void accept(String lineMessage) {
                runOnUiThread(() -> {
                    compilationLog.append(lineMessage);
                    compilationLog.append("\n");
                    compilationScrollView.fullScroll(View.FOCUS_DOWN);
                });
            }

            @Override
            protected void onComplete(int returnCode) {
                if (returnCode == 0) {
                    compilationEndState.setText("Compilation success !");

                    try {
                        ZipUtility.zip(m_executionLocation + "/Release", m_outputArchiveLocation);
                        //removeRecursivelyDirectory(m_executionLocation + "/Release");

                        final Intent mainIntent = new Intent(self, MainActivity.class);
                        startActivity(mainIntent);
                    } catch (Exception e) {
                        compilationEndState.setText("Unable to build the final archive: " + e.getLocalizedMessage());
                        return;
                    }

                } else {
                    compilationEndState.setText("Compilation failure");
                }

            }
        };

        try {
            m_psdkProcessLauncher.run(new PsdkProcess(this, SCRIPT), buildPsdkProcessData());
        } catch (Exception ex) {
            Toast.makeText(getApplicationContext(), ex.getLocalizedMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void removeRecursivelyDirectory(String directoryPath) throws IOException {
        Path directory = Paths.get(directoryPath);
        Files.walkFileTree(directory, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    @Override
    public void onBackPressed() {
        m_psdkProcessLauncher.killCurrentProcess();
    }

    private PsdkProcess.InputData buildPsdkProcessData() {
        return new PsdkProcess.InputData(m_internalWriteablePath, m_executionLocation, m_archiveLocation);
    }
}
