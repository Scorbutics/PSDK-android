package com.psdk;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.Toast;

import java.io.File;

public class MainActivity extends android.app.Activity {
    private static final int CHOOSE_FILE_REQUESTCODE = 8777;

    private String m_gameRbLocation;
    private boolean m_badGameRbLocation = true;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
        Log.i("psdk-android", "On Create .....");

        setGameRbLocationValue(Environment.getExternalStorageDirectory().getAbsolutePath() + "/PSDK/Game.rb", true);

        final Button clickButton = (Button) findViewById(R.id.startGame);
        clickButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (m_badGameRbLocation) {
                    invalidGameRbMessage();
                    return;
                }
                final Intent switchActivityIntent = new Intent(MainActivity.this, android.app.NativeActivity.class);
                switchActivityIntent.putExtra("PSDK_LOCATION", m_gameRbLocation.substring(0, m_gameRbLocation.lastIndexOf(File.separator)));
                MainActivity.this.startActivity(switchActivityIntent);
            }
        });

        final Button locatePsdkButton = (Button) findViewById(R.id.locatePSDK);
        locatePsdkButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openFile("*/*");
            }
        });

        final EditText psdkLocation = (EditText) findViewById(R.id.psdkLocation);
        psdkLocation.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                setGameRbLocationValue(psdkLocation.getText().toString(), false);
            }
            @Override
            public void afterTextChanged(Editable s) {}
        });
    }

    private void setGameRbLocationValue(String gameRbLocation, boolean triggerEvent) {
        m_gameRbLocation = gameRbLocation;
        final EditText psdkLocation = (EditText) findViewById(R.id.psdkLocation);
        if (triggerEvent) {
            psdkLocation.setText(m_gameRbLocation);
        }
        m_badGameRbLocation = checkFilepathValid(m_gameRbLocation) == null;
        psdkLocation.setBackgroundResource(m_badGameRbLocation ? R.drawable.edterr : R.drawable.edtnormal);
    }

    public void openFile(String mimeType) {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType(mimeType);
        intent.addCategory(Intent.CATEGORY_OPENABLE);

        // special intent for Samsung file manager
        Intent sIntent = new Intent("com.sec.android.app.myfiles.PICK_DATA");
        // if you want any file type, you can skip next line
        sIntent.putExtra("CONTENT_TYPE", mimeType);
        sIntent.addCategory(Intent.CATEGORY_DEFAULT);

        Intent chooserIntent;
        if (getPackageManager().resolveActivity(sIntent, 0) != null){
            // it is device with Samsung file manager
            chooserIntent = Intent.createChooser(sIntent, "Open file");
            chooserIntent.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[] { intent});
        } else {
            chooserIntent = Intent.createChooser(intent, "Open file");
        }

        try {
            startActivityForResult(chooserIntent, CHOOSE_FILE_REQUESTCODE);
        } catch (android.content.ActivityNotFoundException ex) {
            Toast.makeText(getApplicationContext(), "No suitable File Manager was found.", Toast.LENGTH_LONG).show();
        }
    }

    private String getRealPathFromURI(Uri uri) {
        final Context context = getApplicationContext();
        String filePath = "";
        String wholeID = DocumentsContract.getDocumentId(uri);

        // Split at colon, use second item in the array
        String id = wholeID.split(":")[1];

        String[] column = { MediaStore.Images.Media.DATA };

        // where id is equal to
        String sel = MediaStore.Images.Media._ID + "=?";

        Cursor cursor = context.getContentResolver().query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                column, sel, new String[]{ id }, null);

        int columnIndex = cursor.getColumnIndex(column[0]);

        if (cursor.moveToFirst()) {
            filePath = cursor.getString(columnIndex);
        }
        cursor.close();
        return filePath;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != CHOOSE_FILE_REQUESTCODE || resultCode != RESULT_OK) {
            return;
        }

        final File file = checkFilepathValid(getRealPathFromURI(data.getData()));
        if (file == null) {
            invalidGameRbMessage();
            return;
        }

        setGameRbLocationValue(file.getAbsolutePath(), true);
    }

    private File checkFilepathValid(final String filepath) {
        final File finalFile = new File(filepath);
        if (!finalFile.exists()) {
            return null;
        }
        final String absPath = finalFile.getAbsolutePath();
        final int sep = absPath.lastIndexOf(File.separator);
        final String filename = absPath.substring(sep + 1);
        if ("Game.rb".equals(filename)) {
            return finalFile;
        }
        return null;
    }

    private void invalidGameRbMessage() {
        Toast.makeText(getApplicationContext(), "You must select the Game.rb file at the root of your PSDK project", Toast.LENGTH_LONG).show();
    }
}
