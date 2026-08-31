package com.github.tvbox.osc.ui.dialog;

import android.app.Activity;
import android.content.Context;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.bean.SourceTestResult;
import com.github.tvbox.osc.ui.adapter.SourceSpeedTestAdapter;
import com.github.tvbox.osc.util.FastClickCheckUtil;
import com.github.tvbox.osc.util.SearchHelper;
import com.github.tvbox.osc.util.SourceTester;
import com.owen.tvrecyclerview.widget.TvRecyclerView;
import com.owen.tvrecyclerview.widget.V7LinearLayoutManager;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * 二次开发新增：源测速 / 可用性测试对话框。
 *
 * 使用当前搜索关键词，对全部可搜索源并发测速，展示每个源的状态/耗时/结果数，
 * 自动勾选可用源。用户确认后，仅勾选的源会被持久化用于以后的搜索。
 */
public class SourceSpeedTestDialog extends BaseDialog {

    public interface OnUsableConfirmListener {
        /** 用户确认后回调，返回勾选为可用的源 key 集合。 */
        void onConfirm(HashMap<String, String> usableSources);
    }

    private final List<SourceBean> sourceList;
    private final String keyword;
    private final SourceTester tester = new SourceTester();
    private final SourceSpeedTestAdapter adapter = new SourceSpeedTestAdapter();

    private TextView tvProgress;
    private TextView btnStartTest;
    private OnUsableConfirmListener confirmListener;

    private int total = 0;
    private int done = 0;
    private boolean testing = false;

    public SourceSpeedTestDialog(@NonNull @NotNull Context context, List<SourceBean> sourceList, String keyword) {
        super(context);
        if (context instanceof Activity) {
            setOwnerActivity((Activity) context);
        }
        setCanceledOnTouchOutside(false);
        setCancelable(true);
        this.sourceList = sourceList == null ? new ArrayList<>() : sourceList;
        this.keyword = keyword;
        setContentView(R.layout.dialog_source_speedtest);
        initView();
    }

    public void setOnUsableConfirmListener(OnUsableConfirmListener listener) {
        this.confirmListener = listener;
    }

    private void initView() {
        tvProgress = findViewById(R.id.tvProgress);
        btnStartTest = findViewById(R.id.btnStartTest);
        TextView btnCheckUsable = findViewById(R.id.btnCheckUsable);
        TextView btnCheckAll = findViewById(R.id.btnCheckAll);
        TextView btnConfirm = findViewById(R.id.btnConfirm);
        TvRecyclerView rv = findViewById(R.id.rvSources);

        rv.setHasFixedSize(true);
        rv.setLayoutManager(new V7LinearLayoutManager(getContext()));
        rv.setAdapter(adapter);

        // 初始化为待测列表
        List<SourceTestResult> init = new ArrayList<>();
        for (SourceBean bean : sourceList) {
            if (bean == null || !bean.isSearchable()) continue;
            init.add(new SourceTestResult(bean.getKey(), bean.getName(), bean.getType()));
        }
        adapter.setNewData(init);
        tvProgress.setText("共 " + init.size() + " 个源，关键词：" + (keyword == null ? "" : keyword));

        btnStartTest.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                startTest();
            }
        });

        btnCheckUsable.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                for (SourceTestResult r : adapter.getData()) {
                    r.setUsable(r.getStatus() == SourceTestResult.STATUS_OK);
                }
                adapter.notifyDataSetChanged();
            }
        });

        btnCheckAll.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                boolean allChecked = isAllChecked();
                for (SourceTestResult r : adapter.getData()) {
                    r.setUsable(!allChecked);
                }
                adapter.notifyDataSetChanged();
            }
        });

        btnConfirm.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                confirmAndPersist();
            }
        });
    }

    private boolean isAllChecked() {
        for (SourceTestResult r : adapter.getData()) {
            if (!r.isUsable()) return false;
        }
        return !adapter.getData().isEmpty();
    }

    private void startTest() {
        if (testing) return;
        if (keyword == null || keyword.trim().isEmpty()) {
            Toast.makeText(getContext(), "请先输入搜索关键词", Toast.LENGTH_SHORT).show();
            return;
        }
        testing = true;
        done = 0;
        total = adapter.getData().size();
        btnStartTest.setEnabled(false);
        // 全部标记为测试中
        for (SourceTestResult r : adapter.getData()) {
            r.setStatus(SourceTestResult.STATUS_TESTING);
        }
        adapter.notifyDataSetChanged();
        updateProgress();

        List<SourceBean> targets = new ArrayList<>();
        for (SourceBean bean : sourceList) {
            if (bean == null || !bean.isSearchable()) continue;
            targets.add(bean);
        }
        tester.test(targets, keyword, new SourceTester.TestCallback() {
            @Override
            public void onResult(SourceTestResult result) {
                adapter.updateResult(result);
                done++;
                updateProgress();
            }

            @Override
            public void onFinish(List<SourceTestResult> results) {
                testing = false;
                btnStartTest.setEnabled(true);
                int ok = 0;
                for (SourceTestResult r : results) {
                    if (r.getStatus() == SourceTestResult.STATUS_OK) ok++;
                }
                tvProgress.setText("完成：可用 " + ok + " / 共 " + results.size());
            }
        });
    }

    private void updateProgress() {
        tvProgress.setText("测试中 " + done + " / " + total);
    }

    private void confirmAndPersist() {
        HashMap<String, String> usable = new HashMap<>();
        for (SourceTestResult r : adapter.getData()) {
            if (r.isUsable()) {
                usable.put(r.getSourceKey(), "1");
            }
        }
        if (usable.isEmpty()) {
            Toast.makeText(getContext(), "至少勾选一个可用源", Toast.LENGTH_SHORT).show();
            return;
        }
        // 持久化为当前配置源的可用源集合（同时会更新搜索勾选集合）
        SearchHelper.putUsableSources(usable);
        if (confirmListener != null) {
            confirmListener.onConfirm(usable);
        }
        Toast.makeText(getContext(), "已保存 " + usable.size() + " 个可用源", Toast.LENGTH_SHORT).show();
        dismiss();
    }

    @Override
    public void dismiss() {
        tester.cancel();
        super.dismiss();
    }
}
