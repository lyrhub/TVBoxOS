package com.github.tvbox.osc.ui.adapter;

import android.graphics.Color;
import android.view.View;
import android.widget.CheckBox;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.SourceTestResult;

import java.util.ArrayList;

/**
 * 二次开发新增：源测速结果列表适配器。
 * 每行展示源名称、测试状态、耗时，并提供一个"可用"复选框。
 */
public class SourceSpeedTestAdapter extends BaseQuickAdapter<SourceTestResult, BaseViewHolder> {

    public SourceSpeedTestAdapter() {
        super(R.layout.item_dialog_source_speedtest, new ArrayList<>());
    }

    @Override
    protected void convert(@NonNull BaseViewHolder holder, SourceTestResult item) {
        TextView name = holder.getView(R.id.tvSourceName);
        TextView status = holder.getView(R.id.tvStatus);
        TextView latency = holder.getView(R.id.tvLatency);
        CheckBox usable = holder.getView(R.id.cbUsable);

        name.setText(item.getSourceName());
        status.setText(item.statusText());
        status.setTextColor(statusColor(item.getStatus()));

        String latencyText = item.latencyText();
        if (item.getResultCount() >= 0 && item.getStatus() == SourceTestResult.STATUS_OK) {
            latencyText = latencyText + " / " + item.getResultCount() + "条";
        }
        latency.setText(latencyText);

        usable.setOnCheckedChangeListener(null);
        usable.setChecked(item.isUsable());
        usable.setOnCheckedChangeListener((buttonView, isChecked) -> item.setUsable(isChecked));

        // 整行点击切换勾选
        holder.itemView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean next = !item.isUsable();
                item.setUsable(next);
                usable.setChecked(next);
            }
        });
    }

    private int statusColor(int status) {
        switch (status) {
            case SourceTestResult.STATUS_OK:
                return Color.parseColor("#4CAF50");
            case SourceTestResult.STATUS_EMPTY:
                return Color.parseColor("#FFC107");
            case SourceTestResult.STATUS_TESTING:
                return Color.parseColor("#03A9F4");
            case SourceTestResult.STATUS_FAIL:
            case SourceTestResult.STATUS_TIMEOUT:
                return Color.parseColor("#F44336");
            case SourceTestResult.STATUS_PENDING:
            default:
                return Color.parseColor("#80FFFFFF");
        }
    }

    public void updateResult(SourceTestResult result) {
        if (result == null) return;
        for (int i = 0; i < getData().size(); i++) {
            if (getData().get(i).getSourceKey().equals(result.getSourceKey())) {
                getData().set(i, result);
                notifyItemChanged(i);
                return;
            }
        }
    }
}
