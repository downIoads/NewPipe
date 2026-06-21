package org.schabi.newpipe.fragments.list.channel;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.PreferenceManager;

import com.evernote.android.state.State;

import org.schabi.newpipe.R;
import org.schabi.newpipe.databinding.PlaylistControlBinding;
import org.schabi.newpipe.error.UserAction;
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.ListExtractor;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo;
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler;
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandlerFactory;
import org.schabi.newpipe.extractor.linkhandler.ReadyChannelTabListLinkHandler;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.fragments.list.BaseListInfoFragment;
import org.schabi.newpipe.fragments.list.playlist.PlaylistControlViewHolder;
import org.schabi.newpipe.player.playqueue.ChannelTabPlayQueue;
import org.schabi.newpipe.player.playqueue.PlayQueue;
import org.schabi.newpipe.util.ChannelTabHelper;
import org.schabi.newpipe.util.ExtractorHelper;
import org.schabi.newpipe.util.PlayButtonHelper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

public class ChannelTabFragment extends BaseListInfoFragment<InfoItem, ChannelTabInfo>
        implements PlaylistControlViewHolder {

    // SharedPreferences key under which the chosen sort mode for the channel "Playlists" tab is
    // persisted (mirrors the per-playlist sort behaviour in PlaylistFragment).
    private static final String SORT_MODE_PREF_KEY = "channel_playlists_sort_mode";

    // Performance logging tag for the eager "load all playlists then sort" path.
    // adb logcat -s ChannelPlaylistSort
    private static final String PLSORT_TAG = "ChannelPlaylistSort";

    /**
     * Sort modes available for the channel "Playlists" tab.
     */
    public enum SortMode { DEFAULT, ALPHABETICAL }

    // states must be protected and not private for State being able to access them
    @State
    protected ListLinkHandler tabHandler;
    @State
    protected String channelName;

    private PlaylistControlBinding playlistControlBinding;

    private SortMode currentSortMode = SortMode.DEFAULT;
    // The items as delivered by the extractor (default order), used to re-sort without re-fetching.
    private final List<InfoItem> originalOrderItems = new ArrayList<>();
    // Worker for the eager "load every remaining playlist page" pass triggered by a non-default
    // sort, kept separate from the base class' currentWorker so the two never clobber each other.
    @Nullable
    private Disposable bulkLoadWorker;
    private boolean bulkLoading;
    private long bulkLoadStartMs;
    private int bulkLoadPageCount;

    @NonNull
    public static ChannelTabFragment getInstance(final int serviceId,
                                                 final ListLinkHandler tabHandler,
                                                 final String channelName) {
        final ChannelTabFragment instance = new ChannelTabFragment();
        instance.serviceId = serviceId;
        instance.tabHandler = tabHandler;
        instance.channelName = channelName;
        return instance;
    }

    public ChannelTabFragment() {
        super(UserAction.REQUESTED_CHANNEL);
    }

    /*//////////////////////////////////////////////////////////////////////////
    // LifeCycle
    //////////////////////////////////////////////////////////////////////////*/

    @Override
    public void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(false);
        // The Sort menu item lives in the parent ChannelFragment's menu; here we only keep track of
        // the chosen mode and apply it to the loaded items.
        currentSortMode = loadSortMode();
    }

    @Override
    public View onCreateView(@NonNull final LayoutInflater inflater,
                             @Nullable final ViewGroup container,
                             @Nullable final Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_channel_tab, container, false);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        playlistControlBinding = null;
        cancelBulkLoad();
    }

    @Override
    protected Supplier<View> getListHeaderSupplier() {
        if (ChannelTabHelper.isStreamsTab(tabHandler)) {
            playlistControlBinding = PlaylistControlBinding
                    .inflate(activity.getLayoutInflater(), itemsList, false);
            return playlistControlBinding::getRoot;
        }
        return null;
    }

    @Override
    protected Single<ChannelTabInfo> loadResult(final boolean forceLoad) {
        return ExtractorHelper.getChannelTab(serviceId, tabHandler, forceLoad);
    }

    @Override
    protected Single<ListExtractor.InfoItemsPage<InfoItem>> loadMoreItemsLogic() {
        return ExtractorHelper.getMoreChannelTabItems(serviceId, tabHandler, currentNextPage);
    }

    @Override
    public void setTitle(final String title) {
        // The channel name is displayed as title in the toolbar.
        // The title is always a description of the content of the tab fragment.
        // It should be unique for each channel because multiple channel tabs
        // can be added to the main page. Therefore, the channel name is used.
        // Using the title variable would cause the title to be the same for all channel tabs.
        super.setTitle(channelName);
    }

    @Override
    public void handleResult(@NonNull final ChannelTabInfo result) {
        super.handleResult(result);

        // FIXME this is a really hacky workaround, to avoid storing useless data in the fragment
        //  state. The problem is, `ReadyChannelTabListLinkHandler` might contain raw JSON data that
        //  uses a lot of memory (e.g. ~800KB for YouTube). While 800KB doesn't seem much, if
        //  you combine just a couple of channel tab fragments you easily go over the 1MB
        //  save&restore transaction limit, and get `TransactionTooLargeException`s. A proper
        //  solution would require rethinking about `ReadyChannelTabListLinkHandler`s.
        if (tabHandler instanceof ReadyChannelTabListLinkHandler) {
            try {
                // once `handleResult` is called, the parsed data was already saved to cache, so
                // we can discard any raw data in ReadyChannelTabListLinkHandler and create a
                // link handler with identical properties, but without any raw data
                final ListLinkHandlerFactory channelTabLHFactory = result.getService()
                        .getChannelTabLHFactory();
                if (channelTabLHFactory != null) {
                    // some services do not not have a ChannelTabLHFactory
                    tabHandler = channelTabLHFactory.fromQuery(tabHandler.getId(),
                            tabHandler.getContentFilters(), tabHandler.getSortFilter());
                }
            } catch (final ParsingException e) {
                // silently ignore the error, as the app can continue to function normally
                Log.w(TAG, "Could not recreate channel tab handler", e);
            }
        }

        if (playlistControlBinding != null) {
            // PlaylistControls should be visible only if there is some item in
            // infoListAdapter other than header
            if (infoListAdapter.getItemCount() > 1) {
                playlistControlBinding.getRoot().setVisibility(View.VISIBLE);
            } else {
                playlistControlBinding.getRoot().setVisibility(View.GONE);
            }

            PlayButtonHelper.initPlaylistControlClickListener(
                    activity, playlistControlBinding, this);
        }

        if (isPlaylistsTab()) {
            // The base class just populated the adapter in the extractor's default order; remember
            // it so we can switch back to "Default", then apply the currently selected sort mode.
            originalOrderItems.clear();
            originalOrderItems.addAll(infoListAdapter.getItemsList());
            if (currentSortMode != SortMode.DEFAULT) {
                // Sort what we already have for instant feedback, then pull in every remaining
                // page so the whole playlist list ends up sorted (not just the first page).
                applyCurrentSort();
                loadAllRemainingThenSort();
            }
        }
    }

    @Override
    public void handleNextItems(final ListExtractor.InfoItemsPage<InfoItem> result) {
        super.handleNextItems(result);

        if (isPlaylistsTab()) {
            // Keep the default-order backing list in sync with the newly paged-in items, and
            // re-apply the sort so the freshly added playlists land in the right place.
            originalOrderItems.addAll(result.getItems());
            if (currentSortMode != SortMode.DEFAULT) {
                applyCurrentSort();
            }
        }
    }

    @Override
    public PlayQueue getPlayQueue() {
        final List<StreamInfoItem> streamItems = infoListAdapter.getItemsList().stream()
                .filter(StreamInfoItem.class::isInstance)
                .map(StreamInfoItem.class::cast)
                .collect(Collectors.toList());

        return new ChannelTabPlayQueue(currentInfo.getServiceId(), tabHandler,
                currentInfo.getNextPage(), streamItems, 0);
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Sorting (used by the parent ChannelFragment's "Sort" menu item)
    //////////////////////////////////////////////////////////////////////////*/

    /**
     * @return whether this tab is the channel's "Playlists" tab, the only tab for which sorting is
     * offered.
     */
    public boolean isPlaylistsTab() {
        final List<String> contentFilters = tabHandler.getContentFilters();
        return !contentFilters.isEmpty() && ChannelTabs.PLAYLISTS.equals(contentFilters.get(0));
    }

    /**
     * Show the "Sort" dialog letting the user pick between the extractor's default order and an
     * ascending alphabetical order. Called by the parent {@link ChannelFragment}.
     */
    public void showSortDialog() {
        final CharSequence[] labels = {
                getString(R.string.playlist_sort_default),
                getString(R.string.playlist_sort_alphabetical)
        };
        final SortMode[] modes = {SortMode.DEFAULT, SortMode.ALPHABETICAL};

        int checked = 0;
        for (int i = 0; i < modes.length; i++) {
            if (modes[i] == currentSortMode) {
                checked = i;
                break;
            }
        }

        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.sort)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    final SortMode picked = modes[which];
                    if (picked != currentSortMode) {
                        currentSortMode = picked;
                        saveSortMode(picked);
                        applyCurrentSort();
                        if (picked != SortMode.DEFAULT) {
                            // Make sure the sort spans every playlist, not just the loaded page.
                            loadAllRemainingThenSort();
                        }
                    }
                    dialog.dismiss();
                })
                .setNegativeButton(R.string.cancel, (d, w) -> d.cancel())
                .show();
    }

    private void applyCurrentSort() {
        if (infoListAdapter == null) {
            return;
        }
        final List<InfoItem> sorted = new ArrayList<>(originalOrderItems);
        if (currentSortMode == SortMode.ALPHABETICAL) {
            sorted.sort(Comparator.comparing(
                    (InfoItem item) -> item.getName() == null ? "" : item.getName(),
                    ChannelTabFragment::compareTitleDigitsLettersOther));
        }
        infoListAdapter.clearStreamItemList();
        infoListAdapter.addInfoItemList(sorted);
    }

    /**
     * Eagerly fetch every remaining page of the channel's playlist listing, then sort the whole
     * set. Only the lightweight listing (playlist name + video count) is downloaded here; no
     * individual playlist/video details are fetched, so this stays cheap even for channels with
     * hundreds of playlists. Pages must be fetched sequentially because each page token depends on
     * the previous response.
     */
    private void loadAllRemainingThenSort() {
        if (bulkLoading) {
            // already fetching everything; the in-flight pass will sort once finished
            return;
        }
        if (!Page.isValid(currentNextPage)) {
            Log.i(PLSORT_TAG, "loadAllRemaining: nothing more to load, total="
                    + originalOrderItems.size());
            return;
        }

        // Stop the base class' lazy loadMore from racing us over the same pages.
        if (currentWorker != null) {
            currentWorker.dispose();
        }
        isLoading.set(true);
        bulkLoading = true;
        bulkLoadStartMs = SystemClock.elapsedRealtime();
        bulkLoadPageCount = 0;
        showListFooter(true);
        Log.i(PLSORT_TAG, "loadAllRemaining: start, alreadyLoaded=" + originalOrderItems.size());
        fetchRemainingPage();
    }

    private void fetchRemainingPage() {
        if (!Page.isValid(currentNextPage)) {
            finishBulkLoad();
            return;
        }

        final long pageStartMs = SystemClock.elapsedRealtime();
        bulkLoadWorker = ExtractorHelper
                .getMoreChannelTabItems(serviceId, tabHandler, currentNextPage)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(itemsPage -> {
                    bulkLoadPageCount++;
                    currentNextPage = itemsPage.getNextPage();
                    originalOrderItems.addAll(itemsPage.getItems());
                    Log.i(PLSORT_TAG, "page #" + bulkLoadPageCount + " items="
                            + itemsPage.getItems().size() + " total=" + originalOrderItems.size()
                            + " hasMore=" + Page.isValid(currentNextPage)
                            + " pageMs=" + (SystemClock.elapsedRealtime() - pageStartMs));
                    fetchRemainingPage();
                }, throwable -> {
                    Log.e(PLSORT_TAG, "bulk page load failed after " + bulkLoadPageCount
                            + " pages; sorting what we have", throwable);
                    finishBulkLoad();
                });
    }

    private void finishBulkLoad() {
        bulkLoading = false;
        isLoading.set(false);
        showListFooter(Page.isValid(currentNextPage));
        applyCurrentSort();
        Log.i(PLSORT_TAG, "loadAllRemaining: done pages=" + bulkLoadPageCount
                + " total=" + originalOrderItems.size()
                + " totalMs=" + (SystemClock.elapsedRealtime() - bulkLoadStartMs));
    }

    private void cancelBulkLoad() {
        if (bulkLoadWorker != null) {
            bulkLoadWorker.dispose();
            bulkLoadWorker = null;
        }
        bulkLoading = false;
    }

    private SortMode loadSortMode() {
        final SharedPreferences prefs =
                PreferenceManager.getDefaultSharedPreferences(requireContext());
        final String stored = prefs.getString(SORT_MODE_PREF_KEY, null);
        if (stored == null) {
            return SortMode.DEFAULT;
        }
        try {
            return SortMode.valueOf(stored);
        } catch (final IllegalArgumentException e) {
            return SortMode.DEFAULT;
        }
    }

    private void saveSortMode(final SortMode mode) {
        PreferenceManager.getDefaultSharedPreferences(requireContext())
                .edit()
                .putString(SORT_MODE_PREF_KEY, mode.name())
                .apply();
    }

    // Orders titles as: digits (0-9), then letters (case-insensitive), then anything else.
    // Mirrors the alphabetical sort used within a playlist (PlaylistFragment).
    private static int compareTitleDigitsLettersOther(final String a, final String b) {
        final int len = Math.min(a.length(), b.length());
        for (int i = 0; i < len; i++) {
            final char ca = a.charAt(i);
            final char cb = b.charAt(i);
            final int catA = charCategory(ca);
            final int catB = charCategory(cb);
            if (catA != catB) {
                return Integer.compare(catA, catB);
            }
            final char la = Character.toLowerCase(ca);
            final char lb = Character.toLowerCase(cb);
            if (la != lb) {
                return Character.compare(la, lb);
            }
        }
        return Integer.compare(a.length(), b.length());
    }

    private static int charCategory(final char c) {
        if (c >= '0' && c <= '9') {
            return 0;
        }
        if (Character.isLetter(c)) {
            return 1;
        }
        return 2;
    }
}
