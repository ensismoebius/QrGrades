package org.dedira.qrnotas.dialogs;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.widget.ImageView;
import android.widget.TextView;

import org.dedira.qrnotas.R;
import org.dedira.qrnotas.model.Student;
import org.dedira.qrnotas.util.QrCode;

public class QrCodeDialog extends Dialog {
    private final Context mContext;
    private final Student student;

    public QrCodeDialog(Context context, Student student) {
        super(context);
        mContext = context;
        this.student = student;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        LayoutInflater inflater = (LayoutInflater) mContext.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        View inflateView = inflater.inflate(R.layout.dialog_qrcode, null);
        setContentView(inflateView);

        Window window = getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }

        Bitmap qrCodeBitmap = QrCode.generateQRCode(student.id);

        TextView txtName = inflateView.findViewById(R.id.txtQrDialogName);
        ImageView imgQr = inflateView.findViewById(R.id.imgQrDialog);
        txtName.setText(student.name);
        imgQr.setImageBitmap(qrCodeBitmap);

        imgQr.setOnClickListener(v -> QrCode.share(mContext, qrCodeBitmap));
        inflateView.findViewById(R.id.btnQrDialogClose).setOnClickListener(v -> dismiss());
    }
}
