package com.github.tvbox.osc.bean;

/**
 * 二次开发新增：单个源在一次"搜索关键词测速"中的结果。
 * 记录该源是否可用、响应耗时、返回结果数，用于展示与勾选可用源。
 */
public class SourceTestResult {

    public static final int STATUS_PENDING = 0;   // 等待测试
    public static final int STATUS_TESTING = 1;   // 测试中
    public static final int STATUS_OK = 2;        // 可用（有结果）
    public static final int STATUS_EMPTY = 3;     // 连通但无结果
    public static final int STATUS_FAIL = 4;      // 失败/错误
    public static final int STATUS_TIMEOUT = 5;   // 超时

    private String sourceKey;
    private String sourceName;
    private int type;
    private int status = STATUS_PENDING;
    private long latencyMs = -1;   // 响应耗时，毫秒；-1 表示未知
    private int resultCount = -1;  // 返回条目数；-1 表示未知
    private boolean usable;        // 是否被判定/勾选为可用

    public SourceTestResult() {
    }

    public SourceTestResult(String sourceKey, String sourceName, int type) {
        this.sourceKey = sourceKey;
        this.sourceName = sourceName;
        this.type = type;
    }

    public String getSourceKey() {
        return sourceKey == null ? "" : sourceKey;
    }

    public void setSourceKey(String sourceKey) {
        this.sourceKey = sourceKey;
    }

    public String getSourceName() {
        return sourceName == null ? "" : sourceName;
    }

    public void setSourceName(String sourceName) {
        this.sourceName = sourceName;
    }

    public int getType() {
        return type;
    }

    public void setType(int type) {
        this.type = type;
    }

    public int getStatus() {
        return status;
    }

    public void setStatus(int status) {
        this.status = status;
    }

    public long getLatencyMs() {
        return latencyMs;
    }

    public void setLatencyMs(long latencyMs) {
        this.latencyMs = latencyMs;
    }

    public int getResultCount() {
        return resultCount;
    }

    public void setResultCount(int resultCount) {
        this.resultCount = resultCount;
    }

    public boolean isUsable() {
        return usable;
    }

    public void setUsable(boolean usable) {
        this.usable = usable;
    }

    /** 是否连通（可用或连通但无结果都算连通）。 */
    public boolean isReachable() {
        return status == STATUS_OK || status == STATUS_EMPTY;
    }

    /** 状态的可读文案。 */
    public String statusText() {
        switch (status) {
            case STATUS_TESTING:
                return "测试中";
            case STATUS_OK:
                return "可用";
            case STATUS_EMPTY:
                return "无结果";
            case STATUS_FAIL:
                return "失败";
            case STATUS_TIMEOUT:
                return "超时";
            case STATUS_PENDING:
            default:
                return "待测";
        }
    }

    /** 耗时的可读文案。 */
    public String latencyText() {
        if (latencyMs < 0) return "-";
        return latencyMs + "ms";
    }
}
