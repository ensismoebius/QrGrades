package org.dedira.qrnotas.dialogs;

import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;

import org.dedira.qrnotas.R;

public class dialog_loading extends Dialog {
    private final Context mContext;

    public dialog_loading(Context context) {
        super(context);
        mContext = context;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        LayoutInflater inflater = (LayoutInflater) mContext.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        View inflateView = inflater.inflate(R.layout.dialog_loading, this.findViewById(R.id.wait_dialog_conteiner));
        setContentView(inflateView);
    }

}