package com.psdk;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class CompileActivity extends Activity {
    private PsdkProcessLauncher m_psdkProcessLauncher;
    private String m_applicationPath;
    private String m_internalWriteablePath;
    private String m_externalWriteablePath;
    private String m_psdkLocation;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.compiler);
        m_applicationPath = getApplicationInfo().dataDir;
        m_internalWriteablePath = getFilesDir().getPath();
        m_externalWriteablePath = getExternalFilesDir(null).getPath();
        m_psdkLocation = getIntent().getStringExtra("PSDK_LOCATION");

        Button backToMainActivity = findViewById(R.id.backToMainActivity);
        backToMainActivity.setOnClickListener(v -> {
            final Intent mainIntent = new Intent(this, MainActivity.class);
            startActivity(mainIntent);
        });

        TextView compilationLog = findViewById(R.id.compilationLog);
        ScrollView compilationScrollView = findViewById(R.id.compilationScrollView);
        compilationLog.setSelected(true);
        TextView compilationEndState = findViewById(R.id.compilationEndState);
        m_psdkProcessLauncher = new PsdkProcessLauncher(m_applicationPath) {
            @Override
            protected void accept(String lineMessage) {
                runOnUiThread(() -> {
                    if (!lineMessage.isEmpty()) {
                        compilationLog.append(lineMessage);
                        compilationLog.append("\n");
                        compilationScrollView.fullScroll(View.FOCUS_DOWN);
                    }
                });
            }

            @Override
            protected void onComplete(int returnCode) {
                if (returnCode == 0) {
                    compilationEndState.setText("Compilation success !");
                } else {
                    compilationEndState.setText("Compilation failure");
                }

            }
        };

        try {
            m_psdkProcessLauncher.run(fifoFilename -> ProjectCompiler.compile(fifoFilename, m_internalWriteablePath, m_externalWriteablePath, m_psdkLocation));
        } catch (Exception ex) {
            Toast.makeText(getApplicationContext(), ex.getLocalizedMessage(), Toast.LENGTH_LONG).show();
        }
    }
}
