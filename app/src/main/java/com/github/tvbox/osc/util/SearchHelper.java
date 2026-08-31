package com.github.tvbox.osc.util;

import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.ui.activity.SearchActivity;
import com.orhanobut.hawk.Hawk;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.Iterator;

public class SearchHelper {

    public static HashMap<String, String> getSourcesForSearch() {
        HashMap<String, String> mCheckSources;
        try {
            String api = Hawk.get(HawkConfig.API_URL, "");
            if(api.isEmpty())return null;
            HashMap<String, HashMap<String, String>> mCheckSourcesForApi = Hawk.get(HawkConfig.SOURCES_FOR_SEARCH, new HashMap<>());
            mCheckSources = mCheckSourcesForApi.get(api);
        } catch (Exception e) {
            return null;
        }
        if (mCheckSources == null || mCheckSources.isEmpty()) {
            mCheckSources = getSources();
        }
//        else {
//            HashMap<String, String> newSources = getSources();
//            for (Map.Entry<String, String> entry : newSources.entrySet()) {
//                String newKey = entry.getKey();
//                String newValue = entry.getValue();
//                if (!mCheckSources.containsKey(newKey)) {
//                    mCheckSources.put(newKey, newValue);
//                }
//            }
//            Iterator<Map.Entry<String, String>> iterator = mCheckSources.entrySet().iterator();
//            while (iterator.hasNext()) {
//                Map.Entry<String, String> oldEntry = iterator.next();
//                String oldKey = oldEntry.getKey();
//                if (!newSources.containsKey(oldKey)) {
//                    iterator.remove();
//                }
//            }
//        }
        return mCheckSources;
    }

    public static void putCheckedSources(HashMap<String, String> mCheckSources,boolean isAll) {
        String api = Hawk.get(HawkConfig.API_URL, "");
        if (api.isEmpty()) {
            return;
        }
        HashMap<String, HashMap<String, String>> mCheckSourcesForApi = Hawk.get(HawkConfig.SOURCES_FOR_SEARCH,null);

        if(isAll){
            if (mCheckSourcesForApi == null) return;
            if (mCheckSourcesForApi.containsKey(api)) mCheckSourcesForApi.remove(api);
        }else {
            if (mCheckSourcesForApi == null) mCheckSourcesForApi = new HashMap<>();
            mCheckSourcesForApi.put(api, mCheckSources);
        }
        SearchActivity.setCheckedSourcesForSearch(mCheckSources);
        Hawk.put(HawkConfig.SOURCES_FOR_SEARCH, mCheckSourcesForApi);
    }

    public static HashMap<String, String> getSources(){
        HashMap<String, String> mCheckSources = new HashMap<>();
        for (SourceBean bean : ApiConfig.get().getSourceBeanList()) {
            if (!bean.isSearchable()) {
                continue;
            }
            mCheckSources.put(bean.getKey(), "1");
        }
        return mCheckSources;
    }

    /**
     * 二次开发新增：读取当前配置源下"经测速勾选为可用"的源集合。
     * 返回 null 表示没有做过筛选（即全部源都参与搜索）。
     */
    public static HashMap<String, String> getUsableSources() {
        try {
            String api = Hawk.get(HawkConfig.API_URL, "");
            if (api.isEmpty()) return null;
            HashMap<String, HashMap<String, String>> all = Hawk.get(HawkConfig.USABLE_SOURCES, new HashMap<>());
            HashMap<String, String> usable = all.get(api);
            if (usable == null || usable.isEmpty()) return null;
            return usable;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 二次开发新增：保存当前配置源下勾选为可用的源集合。
     * usable 为空/为 null 时清除该配置的记录（表示不再筛选，全部参与）。
     */
    public static void putUsableSources(HashMap<String, String> usable) {
        String api = Hawk.get(HawkConfig.API_URL, "");
        if (api.isEmpty()) return;
        HashMap<String, HashMap<String, String>> all = Hawk.get(HawkConfig.USABLE_SOURCES, null);
        if (usable == null || usable.isEmpty()) {
            if (all == null) return;
            all.remove(api);
        } else {
            if (all == null) all = new HashMap<>();
            all.put(api, usable);
        }
        // 让搜索时的过滤集合同步更新为可用源
        SearchActivity.setCheckedSourcesForSearch(usable);
        Hawk.put(HawkConfig.USABLE_SOURCES, all);
        // 同步写入原有的搜索勾选存储，保证两处逻辑一致
        putCheckedSources(usable == null ? new HashMap<>() : usable, usable == null || usable.isEmpty());
    }

    public static List<String> splitWords(String text) {
        List<String> result = new ArrayList<>();
        result.add(text);
        String[] parts = text.split("\\W+");
        if (parts.length > 1) {
            result.addAll(Arrays.asList(parts));
        }
        return result;
    }

}
