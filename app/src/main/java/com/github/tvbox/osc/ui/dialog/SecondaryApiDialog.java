package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.util.HawkConfig;
import com.orhanobut.hawk.Hawk;

import org.jetbrains.annotations.NotNull;

/**
 * 二次开发新增：设置"第二配置源"的对话框。
 * 保存后写入 {@link HawkConfig#SECONDARY_API_URL}，其站点/解析会在配置加载时合并进主源。
 */
public class SecondaryApiDialog extends BaseDialog {

    public interface OnSecondaryApiListener {
        /** 用户保存或清除第二配置源后回调。secondaryUrl 为空字符串表示清除。 */
        void onChange(String secondaryUrl);
    }

    private final EditText inputSecondaryApi;
    private OnSecondaryApiListener listener;

    public SecondaryApiDialog(@NonNull @NotNull Context context) {
        super(context);
        setContentView(R.layout.dialog_secondary_api);
        setCanceledOnTouchOutside(false);
        inputSecondaryApi = findViewById(R.id.inputSecondaryApi);
        TextView btnClear = findViewById(R.id.btnClearSecondary);
        TextView btnConfirm = findViewById(R.id.btnConfirmSecondary);

        inputSecondaryApi.setText(Hawk.get(HawkConfig.SECONDARY_API_URL, ""));

        btnConfirm.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String url = inputSecondaryApi.getText().toString().trim();
                if (url.isEmpty()) {
                    Toast.makeText(getContext(), "地址为空，如需取消合并请点击清除", Toast.LENGTH_SHORT).show();
                    return;
                }
                Hawk.put(HawkConfig.SECONDARY_API_URL, url);
                if (listener != null) listener.onChange(url);
                dismiss();
            }
        });

        btnClear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Hawk.put(HawkConfig.SECONDARY_API_URL, "");
                inputSecondaryApi.setText("");
                if (listener != null) listener.onChange("");
                dismiss();
            }
        });
    }

    public void setOnSecondaryApiListener(OnSecondaryApiListener listener) {
        this.listener = listener;
    }
}
