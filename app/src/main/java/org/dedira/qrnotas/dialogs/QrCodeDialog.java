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

import android.app.AlertDialog;
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
import android.widget.Toast;

import org.dedira.qrnotas.R;
import org.dedira.qrnotas.model.Student;
import org.dedira.qrnotas.util.Database;
import org.dedira.qrnotas.util.QrCode;

/**
 * Dialog that displays a single student's personal QR code so it can be
 * printed, shown on another screen, or shared/sent elsewhere.
 * <p>
 * Scanning this QR code with the app's scanner (see {@link org.dedira.qrnotas.services.QrScanTileService}
 * or the in-app scanner) is how the student is identified when awarding points.
 * This dialog is mostly a "display and share" screen — it generates the QR image from
 * the student's current code ({@link Student#qrCode()}) and lets the user tap it to
 * share/export it via the normal Android share sheet. Students lose their printed codes
 * often, so it can also issue a new code: the old one stops working at once.
 */
public class QrCodeDialog extends Dialog {
    private final Context mContext;
    private final Database database;
    private final Student student;
    private Bitmap qrCodeBitmap;
    private ImageView imgQr;

    public QrCodeDialog(Context context, Database database, Student student) {
        super(context);
        mContext = context;
        this.database = database;
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

        // Render the student's current code (their id, until a new one is issued) as a scannable QR image.
        this.qrCodeBitmap = QrCode.generateQRCode(student.qrCode());

        TextView txtName = inflateView.findViewById(R.id.txtQrDialogName);
        this.imgQr = inflateView.findViewById(R.id.imgQrDialog);
        txtName.setText(student.name);
        imgQr.setImageBitmap(qrCodeBitmap);

        // Tapping the QR image lets the user share/export it (e.g. save, send via
        // messaging apps) through the standard Android share mechanism. The lambda reads the
        // field, so it always shares the code currently on screen, even after a new one was issued.
        imgQr.setOnClickListener(v -> QrCode.share(mContext, qrCodeBitmap));
        inflateView.findViewById(R.id.btnQrDialogRegenerate).setOnClickListener(v -> confirmRegenerate());
        inflateView.findViewById(R.id.btnQrDialogClose).setOnClickListener(v -> dismiss());
    }

    /** Asks first: the printed code stops working the moment the new one is issued. */
    private void confirmRegenerate() {
        new AlertDialog.Builder(mContext)
                .setTitle(R.string.regenerate_qr_title)
                .setMessage(mContext.getString(R.string.regenerate_qr_message, student.name))
                .setPositiveButton(R.string.regenerate_qr_confirm, (dialog, which) -> regenerate())
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void regenerate() {
        database.regenerateQrToken(student.id, (success, updated) -> {
            if (!success || updated == null) {
                Toast.makeText(mContext, R.string.regenerate_qr_failed, Toast.LENGTH_SHORT).show();
                return;
            }
            // The caller's Student object follows, so the list behind this dialog is up to date without a reload.
            student.qrToken = updated.qrToken;
            qrCodeBitmap = QrCode.generateQRCode(student.qrCode());
            imgQr.setImageBitmap(qrCodeBitmap);
            Toast.makeText(mContext, mContext.getString(R.string.regenerate_qr_done, student.name), Toast.LENGTH_LONG).show();
        });
    }
}
