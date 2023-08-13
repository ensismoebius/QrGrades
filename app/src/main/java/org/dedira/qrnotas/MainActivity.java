package org.dedira.qrnotas;

import android.content.DialogInterface;
import android.os.Bundle;
import android.widget.Button;

import androidx.activity.result.ActivityResultLauncher;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;

public class MainActivity extends AppCompatActivity {

    private Button btnScan;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        this.btnScan = this.findViewById(R.id.btnScan);
        this.btnScan.setOnClickListener(v -> this.scanCode());
    }

    private void scanCode(){
        ScanOptions options = new ScanOptions();
        options.setPrompt("Raise the volume to activate the flash");
        options.setBeepEnabled(true);
        options.setCaptureActivity(CaptureActivity.class);

        barLauncher.launch(options);

    }

    ActivityResultLauncher<ScanOptions> barLauncher = registerForActivityResult(
            new ScanContract(), result -> {
                if(result.getContents() != null){
                    AlertDialog.Builder builder=  new AlertDialog.Builder(
                            MainActivity.this
                    );
                    builder.setTitle("Result");
                    builder.setMessage(result.getContents());
                    builder.setPositiveButton("Ok", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                             dialog.dismiss();
                        }
                    }).show();
                }
            }
    );
}