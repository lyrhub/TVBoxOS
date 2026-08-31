package com.github.tvbox.osc.util;

import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import com.github.catvod.crawler.Spider;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.bean.SourceTestResult;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 二次开发新增：源测速 / 可用性测试引擎。
 *
 * 用给定的关键词，对一组源并发发起搜索请求，测量每个源的响应耗时、返回结果数，
 * 并判定其状态（可用 / 无结果 / 失败 / 超时）。结果通过回调在主线程返回，用于 UI 展示与勾选。
 */
public class SourceTester {

    /** 单个源测试超时（毫秒）。 */
    private static final long PER_SOURCE_TIMEOUT_MS = 8000L;
    /** 并发线程数。 */
    private static final int CONCURRENCY = 8;
    private static final Pattern XML_VIDEO = Pattern.compile("<video>", Pattern.CASE_INSENSITIVE);

    public interface TestCallback {
        /** 某个源测试完成时回调（主线程）。 */
        void onResult(SourceTestResult result);

        /** 全部完成时回调（主线程）。 */
        void onFinish(List<SourceTestResult> results);
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private ExecutorService pool;
    private volatile boolean cancelled = false;

    /**
     * 对给定的源列表用关键词并发测速。
     *
     * @param sources  待测源（通常为可搜索源）
     * @param keyword  搜索关键词
     * @param callback 进度与完成回调
     */
    public void test(final List<SourceBean> sources, final String keyword, final TestCallback callback) {
        cancelled = false;
        final List<SourceTestResult> results = new ArrayList<>();
        if (sources == null || sources.isEmpty()) {
            postFinish(callback, results);
            return;
        }
        final List<SourceBean> targets = new ArrayList<>();
        for (SourceBean bean : sources) {
            if (bean == null || !bean.isSearchable()) continue;
            targets.add(bean);
            results.add(new SourceTestResult(bean.getKey(), bean.getName(), bean.getType()));
        }
        if (targets.isEmpty()) {
            postFinish(callback, results);
            return;
        }
        final AtomicInteger remaining = new AtomicInteger(targets.size());
        pool = new ThreadPoolExecutor(CONCURRENCY, CONCURRENCY, 30L, TimeUnit.SECONDS,
                new java.util.concurrent.LinkedBlockingQueue<Runnable>());
        for (int i = 0; i < targets.size(); i++) {
            final SourceBean bean = targets.get(i);
            final SourceTestResult result = results.get(i);
            pool.execute(new Runnable() {
                @Override
                public void run() {
                    if (cancelled) {
                        finishOne(callback, result, results, remaining);
                        return;
                    }
                    runSingleTest(bean, keyword, result);
                    finishOne(callback, result, results, remaining);
                }
            });
        }
    }

    public void cancel() {
        cancelled = true;
        if (pool != null) {
            pool.shutdownNow();
            pool = null;
        }
    }

    private void finishOne(TestCallback callback, SourceTestResult result,
                           List<SourceTestResult> results, AtomicInteger remaining) {
        postResult(callback, result);
        if (remaining.decrementAndGet() <= 0) {
            postFinish(callback, results);
            if (pool != null) {
                pool.shutdown();
                pool = null;
            }
        }
    }

    /** 对单个源执行一次带计时的搜索请求。 */
    private void runSingleTest(SourceBean bean, String keyword, SourceTestResult result) {
        int type = bean.getType();
        long start = System.currentTimeMillis();
        try {
            if (type == 3) {
                testSpider(bean, keyword, result, start);
            } else if (type == 0 || type == 1 || type == 4) {
                testHttp(bean, keyword, result, start);
            } else {
                result.setStatus(SourceTestResult.STATUS_FAIL);
            }
        } catch (Throwable th) {
            long cost = System.currentTimeMillis() - start;
            result.setLatencyMs(cost);
            if (isTimeout(th)) {
                result.setStatus(SourceTestResult.STATUS_TIMEOUT);
            } else {
                result.setStatus(SourceTestResult.STATUS_FAIL);
            }
        }
        result.setUsable(result.getStatus() == SourceTestResult.STATUS_OK);
    }

    /** 通过 CSP Spider（type 3）测试，带超时控制。 */
    private void testSpider(final SourceBean bean, final String keyword,
                            SourceTestResult result, long start) {
        ExecutorService single = Executors.newSingleThreadExecutor();
        Future<String> future = single.submit(() -> {
            Spider sp = ApiConfig.get().getCSP(bean);
            if (sp == null) return "";
            return sp.searchContent(keyword, false);
        });
        try {
            String json = future.get(PER_SOURCE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            long cost = System.currentTimeMillis() - start;
            result.setLatencyMs(cost);
            applyBody(result, json);
        } catch (java.util.concurrent.TimeoutException e) {
            future.cancel(true);
            result.setLatencyMs(System.currentTimeMillis() - start);
            result.setStatus(SourceTestResult.STATUS_TIMEOUT);
        } catch (Throwable th) {
            result.setLatencyMs(System.currentTimeMillis() - start);
            result.setStatus(SourceTestResult.STATUS_FAIL);
        } finally {
            single.shutdownNow();
        }
    }

    /** 通过 HTTP（type 0 xml / 1 json / 4 扩展）测试。 */
    private void testHttp(SourceBean bean, String keyword, SourceTestResult result, long start) throws Exception {
        HttpUrl base = HttpUrl.parse(bean.getApi());
        if (base == null) {
            result.setStatus(SourceTestResult.STATUS_FAIL);
            return;
        }
        HttpUrl.Builder ub = base.newBuilder();
        int type = bean.getType();
        if (type == 1 || type == 4) {
            ub.addQueryParameter("ac", "detail");
        }
        if (type == 4) {
            String wd = keyword;
            try {
                wd = URLEncoder.encode(keyword, "UTF-8");
            } catch (Exception ignore) {
            }
            ub.addQueryParameter("wd", wd);
            String ext = bean.getExt();
            if (!TextUtils.isEmpty(ext)) ub.addQueryParameter("extend", ext);
        } else {
            ub.addQueryParameter("wd", keyword);
        }

        OkHttpClient client = com.github.catvod.net.OkHttp.client(PER_SOURCE_TIMEOUT_MS);
        Request request = new Request.Builder().url(ub.build()).build();
        try (Response response = client.newCall(request).execute()) {
            long cost = System.currentTimeMillis() - start;
            result.setLatencyMs(cost);
            if (!response.isSuccessful()) {
                result.setStatus(SourceTestResult.STATUS_FAIL);
                return;
            }
            String body = response.body() != null ? response.body().string() : "";
            applyBody(result, body);
        }
    }

    /** 依据响应体内容判定状态与结果数。 */
    private void applyBody(SourceTestResult result, String body) {
        if (TextUtils.isEmpty(body)) {
            result.setResultCount(0);
            result.setStatus(SourceTestResult.STATUS_EMPTY);
            return;
        }
        int count = countResults(body);
        result.setResultCount(count);
        result.setStatus(count > 0 ? SourceTestResult.STATUS_OK : SourceTestResult.STATUS_EMPTY);
    }

    /** 粗略统计返回的影片条目数：JSON 走 list/videoList，XML 数 &lt;video&gt; 标签。 */
    private int countResults(String body) {
        String trimmed = body.trim();
        // JSON 优先
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            try {
                JsonElement root = JsonParser.parseString(trimmed);
                if (root.isJsonArray()) return root.getAsJsonArray().size();
                if (root.isJsonObject()) {
                    JsonObject obj = root.getAsJsonObject();
                    JsonArray arr = firstArray(obj, "list", "data", "videoList", "video");
                    if (arr != null) return arr.size();
                }
            } catch (Throwable ignore) {
                // 落到 XML 计数
            }
        }
        // XML 计数
        Matcher m = XML_VIDEO.matcher(body);
        int count = 0;
        while (m.find()) count++;
        return count;
    }

    private JsonArray firstArray(JsonObject obj, String... keys) {
        for (String key : keys) {
            if (obj.has(key) && obj.get(key).isJsonArray()) {
                return obj.getAsJsonArray(key);
            }
        }
        return null;
    }

    private boolean isTimeout(Throwable th) {
        while (th != null) {
            if (th instanceof java.net.SocketTimeoutException
                    || th instanceof java.util.concurrent.TimeoutException
                    || th instanceof java.io.InterruptedIOException) {
                return true;
            }
            th = th.getCause();
        }
        return false;
    }

    private void postResult(final TestCallback callback, final SourceTestResult result) {
        if (callback == null) return;
        mainHandler.post(() -> callback.onResult(result));
    }

    private void postFinish(final TestCallback callback, final List<SourceTestResult> results) {
        if (callback == null) return;
        mainHandler.post(() -> callback.onFinish(results));
    }
}
