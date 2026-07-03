/*
 * QrGrades — track student grades/points, scan QR codes to award points, and optionally
 * expose the same data to a browser on the local network.
 * Copyright (C) 2026 André Furlan
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

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
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import org.dedira.qrnotas.R;
import org.dedira.qrnotas.model.Student;
import org.dedira.qrnotas.util.QrCode;

/**
 * Dialog that displays a single student's personal QR code so it can be
 * printed, shown on another screen, or shared/sent elsewhere.
 * <p>
 * Scanning this QR code with the app's scanner (see {@link org.dedira.qrnotas.services.QrScanTileService}
 * or the in-app scanner) is how the student is identified when awarding points.
 * This dialog itself is purely a "display and share" screen — it generates the
 * QR image from the student's id and lets the user tap it to share/export it
 * via the normal Android share sheet.
 */
public class QrCodeDialog extends Dialog {
    private final Context mContext;
    private final Student student;

    public QrCodeDialog(Context context, Student student) {
        super(context);
        mContext = context;
        this.student = student;
    }

    /**
     * Called by the Android framework right before this dialog's window is
     * first shown. Inflates the layout, generates the QR code bitmap for the
     * student, and wires up the tap-to-share and close actions.
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Remove the default dialog title bar; the layout has its own title text view.
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        // Turn dialog_qrcode.xml into real View objects to show in this dialog's window.
        LayoutInflater inflater = (LayoutInflater) mContext.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        View inflateView = inflater.inflate(R.layout.dialog_qrcode, new FrameLayout(mContext), false);
        setContentView(inflateView);

        Window window = getWindow();
        if (window != null) {
            // Transparent window background so only the layout's own card/shape is visible.
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }

        // Render the student's unique id as a scannable QR code image.
        Bitmap qrCodeBitmap = QrCode.generateQRCode(student.id);

        TextView txtName = inflateView.findViewById(R.id.txtQrDialogName);
        ImageView imgQr = inflateView.findViewById(R.id.imgQrDialog);
        txtName.setText(student.name);
        imgQr.setImageBitmap(qrCodeBitmap);

        // Tapping the QR image lets the user share/export it (e.g. save, send via
        // messaging apps) through the standard Android share mechanism.
        imgQr.setOnClickListener(v -> QrCode.share(mContext, qrCodeBitmap));
        inflateView.findViewById(R.id.btnQrDialogClose).setOnClickListener(v -> dismiss());
    }
}
