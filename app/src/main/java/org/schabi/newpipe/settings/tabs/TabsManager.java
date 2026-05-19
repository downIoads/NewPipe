package org.schabi.newpipe.settings.tabs;

import android.content.Context;
import android.content.SharedPreferences;
import android.widget.Toast;

import androidx.preference.PreferenceManager;

import org.schabi.newpipe.R;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public final class TabsManager {
    private final SharedPreferences sharedPreferences;
    private final String savedTabsKey;
    private final String showHistoryTabKey;
    private final Context context;
    private SavedTabsChangeListener savedTabsChangeListener;
    private SharedPreferences.OnSharedPreferenceChangeListener preferenceChangeListener;

    private TabsManager(final Context context) {
        this.context = context;
        this.sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
        this.savedTabsKey = context.getString(R.string.saved_tabs_key);
        this.showHistoryTabKey = context.getString(R.string.show_history_tab_key);
    }

    public static TabsManager getManager(final Context context) {
        return new TabsManager(context);
    }

    public List<Tab> getTabs() {
        return applyHistoryTabSetting(getStoredTabs());
    }

    public List<Tab> getStoredTabs() {
        final String savedJson = sharedPreferences.getString(savedTabsKey, null);
        try {
            return TabsJsonHelper.getTabsFromJson(savedJson);
        } catch (final TabsJsonHelper.InvalidJsonException e) {
            Toast.makeText(context, R.string.saved_tabs_invalid_json, Toast.LENGTH_SHORT).show();
            return getDefaultTabs();
        }
    }

    public void saveTabs(final List<Tab> tabList) {
        final List<Tab> tabsToSave = sharedPreferences.contains(showHistoryTabKey)
                ? removeHistoryTabs(tabList)
                : tabList;
        final String jsonToSave = TabsJsonHelper.getJsonToSave(tabsToSave);
        sharedPreferences.edit().putString(savedTabsKey, jsonToSave).apply();
    }

    public void resetTabs() {
        sharedPreferences.edit().remove(savedTabsKey).apply();
    }

    public List<Tab> getDefaultTabs() {
        return TabsJsonHelper.getDefaultTabs();
    }

    private List<Tab> applyHistoryTabSetting(final List<Tab> tabs) {
        final List<Tab> tabsWithoutHistory = removeHistoryTabs(tabs);
        if (!sharedPreferences.getBoolean(showHistoryTabKey, false)) {
            return tabsWithoutHistory;
        }

        final List<Tab> tabsWithHistory = new ArrayList<>(tabsWithoutHistory);
        tabsWithHistory.add(getHistoryTabPosition(tabsWithHistory), Tab.Type.HISTORY.getTab());
        return tabsWithHistory;
    }

    private static List<Tab> removeHistoryTabs(final List<Tab> tabs) {
        if (tabs == null) {
            return null;
        }

        return tabs.stream()
                .filter(tab -> tab.getTabId() != Tab.HistoryTab.ID)
                .collect(Collectors.toList());
    }

    private static int getHistoryTabPosition(final List<Tab> tabs) {
        for (int i = 0; i < tabs.size(); i++) {
            if (tabs.get(i).getTabId() == Tab.BookmarksTab.ID) {
                return i;
            }
        }

        for (int i = 0; i < tabs.size(); i++) {
            if (tabs.get(i).getTabId() == Tab.SubscriptionsTab.ID) {
                return i + 1;
            }
        }

        return tabs.size();
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Listener
    //////////////////////////////////////////////////////////////////////////*/

    public void setSavedTabsListener(final SavedTabsChangeListener listener) {
        if (preferenceChangeListener != null) {
            sharedPreferences.unregisterOnSharedPreferenceChangeListener(preferenceChangeListener);
        }
        savedTabsChangeListener = listener;
        preferenceChangeListener = getPreferenceChangeListener();
        sharedPreferences.registerOnSharedPreferenceChangeListener(preferenceChangeListener);
    }

    public void unsetSavedTabsListener() {
        if (preferenceChangeListener != null) {
            sharedPreferences.unregisterOnSharedPreferenceChangeListener(preferenceChangeListener);
        }
        preferenceChangeListener = null;
        savedTabsChangeListener = null;
    }

    private SharedPreferences.OnSharedPreferenceChangeListener getPreferenceChangeListener() {
        return (sp, key) -> {
            if ((savedTabsKey.equals(key) || showHistoryTabKey.equals(key))
                    && savedTabsChangeListener != null) {
                savedTabsChangeListener.onTabsChanged();
            }
        };
    }

    public interface SavedTabsChangeListener {
        void onTabsChanged();
    }
}
