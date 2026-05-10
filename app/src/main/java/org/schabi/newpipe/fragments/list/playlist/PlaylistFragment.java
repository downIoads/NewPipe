package org.schabi.newpipe.fragments.list.playlist;

import static org.schabi.newpipe.extractor.utils.Utils.isBlank;
import static org.schabi.newpipe.ktx.ViewUtils.animate;
import static org.schabi.newpipe.ktx.ViewUtils.animateHideRecyclerViewAllowingScrolling;
import static org.schabi.newpipe.util.ServiceHelper.getServiceById;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.preference.PreferenceManager;

import com.google.android.material.shape.CornerFamily;
import com.google.android.material.shape.ShapeAppearanceModel;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.schabi.newpipe.NewPipeDatabase;
import org.schabi.newpipe.R;
import org.schabi.newpipe.database.playlist.model.PlaylistRemoteEntity;
import org.schabi.newpipe.database.stream.model.StreamEntity;
import org.schabi.newpipe.databinding.PlaylistControlBinding;
import org.schabi.newpipe.databinding.PlaylistHeaderBinding;
import org.schabi.newpipe.error.ErrorInfo;
import org.schabi.newpipe.error.ErrorUtil;
import org.schabi.newpipe.error.UserAction;
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.ListExtractor;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.playlist.PlaylistInfo;
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper;
import org.schabi.newpipe.extractor.stream.Description;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.fragments.list.BaseListInfoFragment;
import org.schabi.newpipe.info_list.dialog.InfoItemDialog;
import org.schabi.newpipe.info_list.dialog.StreamDialogDefaultEntry;
import org.schabi.newpipe.local.dialog.PlaylistDialog;
import org.schabi.newpipe.local.playlist.RemotePlaylistManager;
import org.schabi.newpipe.player.playqueue.PlayQueue;
import org.schabi.newpipe.player.playqueue.PlaylistPlayQueue;
import org.schabi.newpipe.util.DependentPreferenceHelper;
import org.schabi.newpipe.util.ExtractorHelper;
import org.schabi.newpipe.util.Localization;
import org.schabi.newpipe.util.NavigationHelper;
import org.schabi.newpipe.util.PlayButtonHelper;
import org.schabi.newpipe.util.external_communication.ShareUtils;
import org.schabi.newpipe.util.image.PicassoHelper;
import org.schabi.newpipe.util.text.TextEllipsizer;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.concurrent.TimeUnit;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.disposables.Disposable;

public class PlaylistFragment extends BaseListInfoFragment<StreamInfoItem, PlaylistInfo>
        implements PlaylistControlViewHolder {

    private static final String PICASSO_PLAYLIST_TAG = "PICASSO_PLAYLIST_TAG";
    private static final String SORT_MODE_PREF_KEY = "remote_playlist_sort_mode";
    private static final String PLSORT_TAG = "PlaylistSortDebug";

    private enum SortMode {
        DEFAULT, NEWEST_FIRST, OLDEST_FIRST, ALPHABETICAL
    }

    private CompositeDisposable disposables;
    private Subscription bookmarkReactor;
    private AtomicBoolean isBookmarkButtonReady;

    private RemotePlaylistManager remotePlaylistManager;
    private PlaylistRemoteEntity playlistEntity;
    private Disposable progressRefreshDisposable;

    private SortMode currentSortMode = SortMode.DEFAULT;
    private final List<StreamInfoItem> originalOrderItems = new ArrayList<>();

    /*//////////////////////////////////////////////////////////////////////////
    // Views
    //////////////////////////////////////////////////////////////////////////*/

    private PlaylistHeaderBinding headerBinding;
    private PlaylistControlBinding playlistControlBinding;

    private MenuItem playlistBookmarkButton;

    private long streamCount;
    private long playlistOverallDurationSeconds;

    public static PlaylistFragment getInstance(final int serviceId, final String url,
                                               final String name) {
        final PlaylistFragment instance = new PlaylistFragment();
        instance.setInitialData(serviceId, url, name);
        return instance;
    }

    public PlaylistFragment() {
        super(UserAction.REQUESTED_PLAYLIST);
    }

    /*//////////////////////////////////////////////////////////////////////////
    // LifeCycle
    //////////////////////////////////////////////////////////////////////////*/

    @Override
    public void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        disposables = new CompositeDisposable();
        isBookmarkButtonReady = new AtomicBoolean(false);
        remotePlaylistManager = new RemotePlaylistManager(NewPipeDatabase
                .getInstance(requireContext()));
        currentSortMode = loadSortMode();
        Log.i(PLSORT_TAG, "onCreate frag=" + TAG + " url=" + url
                + " serviceId=" + serviceId + " sortMode=" + currentSortMode);
    }

    @Override
    public View onCreateView(@NonNull final LayoutInflater inflater,
                             @Nullable final ViewGroup container,
                             @Nullable final Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_playlist, container, false);
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Init
    //////////////////////////////////////////////////////////////////////////*/

    @Override
    protected Supplier<View> getListHeaderSupplier() {
        headerBinding = PlaylistHeaderBinding
                .inflate(activity.getLayoutInflater(), itemsList, false);
        playlistControlBinding = headerBinding.playlistControl;

        return headerBinding::getRoot;
    }

    @Override
    protected void initViews(final View rootView, final Bundle savedInstanceState) {
        super.initViews(rootView, savedInstanceState);

        // Is mini variant still relevant?
        // Only the remote playlist screen uses it now
        infoListAdapter.setUseMiniVariant(true);
    }

    private PlayQueue getPlayQueueStartingAt(final StreamInfoItem infoItem) {
        return getPlayQueue(Math.max(infoListAdapter.getItemsList().indexOf(infoItem), 0));
    }

    @Override
    protected void showInfoItemDialog(final StreamInfoItem item) {
        final Context context = getContext();
        try {
            final InfoItemDialog.Builder dialogBuilder =
                    new InfoItemDialog.Builder(getActivity(), context, this, item);

            dialogBuilder
                    .setAction(
                            StreamDialogDefaultEntry.START_HERE_ON_BACKGROUND,
                            (f, infoItem) -> NavigationHelper.playOnBackgroundPlayer(
                                    context, getPlayQueueStartingAt(infoItem), true))
                    .create()
                    .show();
        } catch (final IllegalArgumentException e) {
            InfoItemDialog.Builder.reportErrorDuringInitialization(e, item);
        }
    }

    @Override
    public void onCreateOptionsMenu(@NonNull final Menu menu,
                                    @NonNull final MenuInflater inflater) {
        if (DEBUG) {
            Log.d(TAG, "onCreateOptionsMenu() called with: "
                    + "menu = [" + menu + "], inflater = [" + inflater + "]");
        }
        super.onCreateOptionsMenu(menu, inflater);
        inflater.inflate(R.menu.menu_playlist, menu);

        playlistBookmarkButton = menu.findItem(R.id.menu_item_bookmark);
        updateBookmarkButtons();
    }

    @Override
    public void onDestroyView() {
        stopProgressRefresh();
        headerBinding = null;
        playlistControlBinding = null;

        super.onDestroyView();
        if (isBookmarkButtonReady != null) {
            isBookmarkButtonReady.set(false);
        }

        if (disposables != null) {
            disposables.clear();
        }
        if (bookmarkReactor != null) {
            bookmarkReactor.cancel();
        }

        bookmarkReactor = null;
    }

    @Override
    public void onResume() {
        super.onResume();
        startProgressRefresh();
    }

    @Override
    public void onPause() {
        stopProgressRefresh();
        super.onPause();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        if (disposables != null) {
            disposables.dispose();
        }

        disposables = null;
        remotePlaylistManager = null;
        playlistEntity = null;
        isBookmarkButtonReady = null;
    }

    /*//////////////////////////////////////////////////////////////////////////
    // State Saving
    //////////////////////////////////////////////////////////////////////////*/

    @Override
    public void writeTo(final Queue<Object> objectsToSave) {
        super.writeTo(objectsToSave);
        objectsToSave.add(new ArrayList<>(originalOrderItems));
        Log.i(PLSORT_TAG, "writeTo originalOrderItems.size=" + originalOrderItems.size()
                + " adapter.size=" + (infoListAdapter == null
                ? "null" : infoListAdapter.getItemsList().size()));
    }

    @Override
    @SuppressWarnings("unchecked")
    public void readFrom(@NonNull final Queue<Object> savedObjects) throws Exception {
        super.readFrom(savedObjects);
        final Object saved = savedObjects.poll();
        originalOrderItems.clear();
        if (saved instanceof List) {
            originalOrderItems.addAll((List<StreamInfoItem>) saved);
        }
        Log.i(PLSORT_TAG, "readFrom savedType="
                + (saved == null ? "null" : saved.getClass().getSimpleName())
                + " originalOrderItems.size=" + originalOrderItems.size()
                + " adapter.size=" + (infoListAdapter == null
                ? "null" : infoListAdapter.getItemsList().size()));
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Load and handle
    //////////////////////////////////////////////////////////////////////////*/

    @Override
    public void startLoading(final boolean forceLoad) {
        Log.i(PLSORT_TAG, "startLoading forceLoad=" + forceLoad + " url=" + url
                + " sortMode=" + currentSortMode);
        originalOrderItems.clear();
        super.startLoading(forceLoad);
    }

    @Override
    protected Single<ListExtractor.InfoItemsPage<StreamInfoItem>> loadMoreItemsLogic() {
        return ExtractorHelper.getMorePlaylistItems(serviceId, url, currentNextPage);
    }

    @Override
    protected Single<PlaylistInfo> loadResult(final boolean forceLoad) {
        return ExtractorHelper.getPlaylistInfo(serviceId, url, forceLoad);
    }

    @Override
    public boolean onOptionsItemSelected(final MenuItem item) {
        switch (item.getItemId()) {
            case R.id.menu_item_sort:
                showSortDialog();
                break;
            case R.id.action_settings:
                NavigationHelper.openSettings(requireContext());
                break;
            case R.id.menu_item_openInBrowser:
                ShareUtils.openUrlInBrowser(requireContext(), url);
                break;
            case R.id.menu_item_share:
                ShareUtils.copyToClipboard(requireContext(), url);
                break;
            case R.id.menu_item_bookmark:
                onBookmarkClicked();
                break;
            case R.id.menu_item_append_playlist:
                if (currentInfo != null) {
                    disposables.add(PlaylistDialog.createCorrespondingDialog(
                            getContext(),
                            getPlayQueue()
                                    .getStreams()
                                    .stream()
                                    .map(StreamEntity::new)
                                    .collect(Collectors.toList()),
                            dialog -> dialog.show(getFM(), TAG)
                    ));
                }
                break;
            default:
                return super.onOptionsItemSelected(item);
        }
        return true;
    }


    /*//////////////////////////////////////////////////////////////////////////
    // Contract
    //////////////////////////////////////////////////////////////////////////*/

    @Override
    public void showLoading() {
        super.showLoading();
        animate(headerBinding.getRoot(), false, 200);
        animateHideRecyclerViewAllowingScrolling(itemsList);

        PicassoHelper.cancelTag(PICASSO_PLAYLIST_TAG);
        animate(headerBinding.uploaderLayout, false, 200);
    }

    @Override
    public void showEmptyState() {
        Log.w(PLSORT_TAG, "showEmptyState called! adapter.size="
                + (infoListAdapter == null ? "null"
                        : String.valueOf(infoListAdapter.getItemsList().size()))
                + " originalOrderItems.size=" + originalOrderItems.size()
                + " sortMode=" + currentSortMode
                + " currentInfo=" + (currentInfo == null ? "null"
                        : ("relatedItems=" + currentInfo.getRelatedItems().size()
                                + " streamCount=" + currentInfo.getStreamCount()
                                + " nextPage=" + currentInfo.getNextPage()))
                + " url=" + url, new Throwable("showEmptyState stack"));
        super.showEmptyState();
    }

    @Override
    public void handleNextItems(final ListExtractor.InfoItemsPage result) {
        Log.i(PLSORT_TAG, "handleNextItems pre items=" + result.getItems().size()
                + " hasNextPage=" + result.hasNextPage()
                + " originalOrderItems.size=" + originalOrderItems.size()
                + " adapter.size=" + infoListAdapter.getItemsList().size()
                + " errors=" + result.getErrors().size());
        if (!result.getErrors().isEmpty()) {
            Log.w(PLSORT_TAG, "handleNextItems errors=" + result.getErrors());
        }
        super.handleNextItems(result);
        @SuppressWarnings("unchecked") final List<StreamInfoItem> newItems =
                (List<StreamInfoItem>) result.getItems();
        originalOrderItems.addAll(newItems);
        applyCurrentSort();
        setStreamCountAndOverallDuration(newItems, !result.hasNextPage());
        Log.i(PLSORT_TAG, "handleNextItems post originalOrderItems.size="
                + originalOrderItems.size()
                + " adapter.size=" + infoListAdapter.getItemsList().size());
    }

    @Override
    public void handleResult(@NonNull final PlaylistInfo result) {
        final boolean wasEmptyBeforeSuper = infoListAdapter.getItemsList().isEmpty();
        Log.i(PLSORT_TAG, "handleResult pre wasEmptyBeforeSuper=" + wasEmptyBeforeSuper
                + " relatedItems=" + result.getRelatedItems().size()
                + " nextPage=" + result.getNextPage()
                + " streamCount=" + result.getStreamCount()
                + " sortMode=" + currentSortMode
                + " originalOrderItems.size=" + originalOrderItems.size()
                + " errors=" + result.getErrors().size());
        if (!result.getErrors().isEmpty()) {
            Log.w(PLSORT_TAG, "handleResult errors=" + result.getErrors());
        }
        super.handleResult(result);
        Log.i(PLSORT_TAG, "handleResult mid (after super) adapter.size="
                + infoListAdapter.getItemsList().size()
                + " hasMoreItems=" + hasMoreItems());
        if (wasEmptyBeforeSuper) {
            originalOrderItems.clear();
            originalOrderItems.addAll(result.getRelatedItems());
            applyCurrentSort();
        }
        Log.i(PLSORT_TAG, "handleResult post adapter.size="
                + infoListAdapter.getItemsList().size()
                + " originalOrderItems.size=" + originalOrderItems.size());

        animate(headerBinding.getRoot(), true, 100);
        animate(headerBinding.uploaderLayout, true, 300);
        headerBinding.uploaderLayout.setOnClickListener(null);
        // If we have an uploader put them into the UI
        if (!TextUtils.isEmpty(result.getUploaderName())) {
            headerBinding.uploaderName.setText(result.getUploaderName());
            if (!TextUtils.isEmpty(result.getUploaderUrl())) {
                headerBinding.uploaderLayout.setOnClickListener(v -> {
                    try {
                        NavigationHelper.openChannelFragment(getFM(), result.getServiceId(),
                                result.getUploaderUrl(), result.getUploaderName());
                    } catch (final Exception e) {
                        ErrorUtil.showUiErrorSnackbar(this, "Opening channel fragment", e);
                    }
                });
            }
        } else { // Otherwise say we have no uploader
            headerBinding.uploaderName.setText(R.string.playlist_no_uploader);
        }

        playlistControlBinding.getRoot().setVisibility(View.VISIBLE);

        if (result.getServiceId() == ServiceList.YouTube.getServiceId()
                && (YoutubeParsingHelper.isYoutubeMixId(result.getId())
                || YoutubeParsingHelper.isYoutubeMusicMixId(result.getId()))) {
            // this is an auto-generated playlist (e.g. Youtube mix), so a radio is shown
            final ShapeAppearanceModel model = ShapeAppearanceModel.builder()
                    .setAllCorners(CornerFamily.ROUNDED, 0f)
                    .build(); // this turns the image back into a square
            headerBinding.uploaderAvatarView.setShapeAppearanceModel(model);
            headerBinding.uploaderAvatarView.setStrokeColor(AppCompatResources
                    .getColorStateList(requireContext(), R.color.transparent_background_color));
            headerBinding.uploaderAvatarView.setImageDrawable(
                    AppCompatResources.getDrawable(requireContext(),
                    R.drawable.ic_radio)
            );
        } else {
            PicassoHelper.loadAvatar(result.getUploaderAvatars()).tag(PICASSO_PLAYLIST_TAG)
                    .into(headerBinding.uploaderAvatarView);
        }

        streamCount = result.getStreamCount();
        setStreamCountAndOverallDuration(result.getRelatedItems(), !result.hasNextPage());

        final Description description = result.getDescription();
        if (description != null && description != Description.EMPTY_DESCRIPTION
                && !isBlank(description.getContent())) {
            final TextEllipsizer ellipsizer = new TextEllipsizer(
                    headerBinding.playlistDescription, 5, getServiceById(result.getServiceId()));
            ellipsizer.setStateChangeListener(isEllipsized ->
                headerBinding.playlistDescriptionReadMore.setText(
                        Boolean.TRUE.equals(isEllipsized) ? R.string.show_more : R.string.show_less
                ));
            ellipsizer.setOnContentChanged(canBeEllipsized -> {
                headerBinding.playlistDescriptionReadMore.setVisibility(
                        Boolean.TRUE.equals(canBeEllipsized) ? View.VISIBLE : View.GONE);
                if (Boolean.TRUE.equals(canBeEllipsized)) {
                    ellipsizer.ellipsize();
                }
            });
            ellipsizer.setContent(description);
            headerBinding.playlistDescriptionReadMore.setOnClickListener(v -> ellipsizer.toggle());
            headerBinding.playlistDescription.setOnClickListener(v -> ellipsizer.toggle());
        } else {
            headerBinding.playlistDescription.setVisibility(View.GONE);
            headerBinding.playlistDescriptionReadMore.setVisibility(View.GONE);
        }

        if (!result.getErrors().isEmpty()) {
            showSnackBarError(new ErrorInfo(result.getErrors(), UserAction.REQUESTED_PLAYLIST,
                    result.getUrl(), result));
        }

        remotePlaylistManager.getPlaylist(result)
                .flatMap(lists -> getUpdateProcessor(lists, result), (lists, id) -> lists)
                .onBackpressureLatest()
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(getPlaylistBookmarkSubscriber());

        PlayButtonHelper.initPlaylistControlClickListener(activity, playlistControlBinding, this);
    }

    public PlayQueue getPlayQueue() {
        return getPlayQueue(0);
    }

    private PlayQueue getPlayQueue(final int index) {
        final List<StreamInfoItem> infoItems = new ArrayList<>();
        for (final InfoItem i : infoListAdapter.getItemsList()) {
            if (i instanceof StreamInfoItem) {
                infoItems.add((StreamInfoItem) i);
            }
        }
        return new PlaylistPlayQueue(
                currentInfo.getServiceId(),
                currentInfo.getUrl(),
                currentInfo.getNextPage(),
                infoItems,
                index
        );
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Utils
    //////////////////////////////////////////////////////////////////////////*/

    private Flowable<Integer> getUpdateProcessor(
            @NonNull final List<PlaylistRemoteEntity> playlists,
            @NonNull final PlaylistInfo result) {
        final Flowable<Integer> noItemToUpdate = Flowable.just(/*noItemToUpdate=*/-1);
        if (playlists.isEmpty()) {
            return noItemToUpdate;
        }

        final PlaylistRemoteEntity playlistRemoteEntity = playlists.get(0);
        if (playlistRemoteEntity.isIdenticalTo(result)) {
            return noItemToUpdate;
        }

        return remotePlaylistManager.onUpdate(playlists.get(0).getUid(), result).toFlowable();
    }

    private Subscriber<List<PlaylistRemoteEntity>> getPlaylistBookmarkSubscriber() {
        return new Subscriber<>() {
            @Override
            public void onSubscribe(final Subscription s) {
                if (bookmarkReactor != null) {
                    bookmarkReactor.cancel();
                }
                bookmarkReactor = s;
                bookmarkReactor.request(1);
            }

            @Override
            public void onNext(final List<PlaylistRemoteEntity> playlist) {
                playlistEntity = playlist.isEmpty() ? null : playlist.get(0);

                updateBookmarkButtons();
                isBookmarkButtonReady.set(true);

                if (bookmarkReactor != null) {
                    bookmarkReactor.request(1);
                }
            }

            @Override
            public void onError(final Throwable throwable) {
                showError(new ErrorInfo(throwable, UserAction.REQUESTED_BOOKMARK,
                        "Get playlist bookmarks"));
            }

            @Override
            public void onComplete() { }
        };
    }

    @Override
    public void setTitle(final String title) {
        super.setTitle(title);
        if (headerBinding != null) {
            headerBinding.playlistTitleView.setText(title);
        }
    }

    private void onBookmarkClicked() {
        if (isBookmarkButtonReady == null || !isBookmarkButtonReady.get()
                || remotePlaylistManager == null) {
            return;
        }

        final Disposable action;

        if (currentInfo != null && playlistEntity == null) {
            action = remotePlaylistManager.onBookmark(currentInfo)
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(ignored -> { /* Do nothing */ }, throwable ->
                            showError(new ErrorInfo(throwable, UserAction.REQUESTED_BOOKMARK,
                                    "Adding playlist bookmark")));
        } else if (playlistEntity != null) {
            action = remotePlaylistManager.deletePlaylist(playlistEntity.getUid())
                    .observeOn(AndroidSchedulers.mainThread())
                    .doFinally(() -> playlistEntity = null)
                    .subscribe(ignored -> { /* Do nothing */ }, throwable ->
                            showError(new ErrorInfo(throwable, UserAction.REQUESTED_BOOKMARK,
                                    "Deleting playlist bookmark")));
        } else {
            action = Disposable.empty();
        }

        disposables.add(action);
    }

    private void updateBookmarkButtons() {
        if (playlistBookmarkButton == null || activity == null) {
            return;
        }

        final int drawable = playlistEntity == null
                ? R.drawable.ic_playlist_add : R.drawable.ic_playlist_add_check;

        final int titleRes = playlistEntity == null
                ? R.string.bookmark_playlist : R.string.unbookmark_playlist;

        playlistBookmarkButton.setIcon(drawable);
        playlistBookmarkButton.setTitle(titleRes);
    }

    private void setStreamCountAndOverallDuration(final List<StreamInfoItem> list,
                                                  final boolean isDurationComplete) {
        if (activity != null && headerBinding != null) {
            playlistOverallDurationSeconds += list.stream()
                    .mapToLong(x -> x.getDuration())
                    .sum();
            headerBinding.playlistStreamCount.setText(
                Localization.concatenateStrings(
                    Localization.localizeStreamCount(activity, streamCount),
                    Localization.getDurationString(playlistOverallDurationSeconds,
                            isDurationComplete, true))
            );
        }
    }

    private void startProgressRefresh() {
        if (progressRefreshDisposable != null && !progressRefreshDisposable.isDisposed()) {
            return;
        }
        if (itemsList == null || infoListAdapter == null
                || !DependentPreferenceHelper.getPositionsInListsEnabled(requireContext())) {
            return;
        }
        progressRefreshDisposable = Observable.interval(0, 2, TimeUnit.SECONDS)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(ignore -> refreshVisibleProgressIndicators(),
                        throwable -> { /* no-op */ });
    }

    private void stopProgressRefresh() {
        if (progressRefreshDisposable != null) {
            progressRefreshDisposable.dispose();
            progressRefreshDisposable = null;
        }
    }

    private void refreshVisibleProgressIndicators() {
        if (itemsList == null || infoListAdapter == null) {
            return;
        }
        infoListAdapter.updateVisibleItemStates(itemsList);
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Sorting
    //////////////////////////////////////////////////////////////////////////*/

    private void showSortDialog() {
        final CharSequence[] labels = {
                getString(R.string.playlist_sort_default),
                getString(R.string.playlist_sort_newest_first),
                getString(R.string.playlist_sort_oldest_first),
                getString(R.string.playlist_sort_alphabetical)
        };
        final SortMode[] modes = {
                SortMode.DEFAULT,
                SortMode.NEWEST_FIRST,
                SortMode.OLDEST_FIRST,
                SortMode.ALPHABETICAL
        };

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
                    }
                    dialog.dismiss();
                })
                .setNegativeButton(R.string.cancel, (d, w) -> d.cancel())
                .show();
    }

    private void applyCurrentSort() {
        Log.i(PLSORT_TAG, "applyCurrentSort mode=" + currentSortMode
                + " adapterNull=" + (infoListAdapter == null)
                + " originalOrderItems.size=" + originalOrderItems.size()
                + " adapter.size=" + (infoListAdapter == null ? "null"
                        : String.valueOf(infoListAdapter.getItemsList().size())));
        if (infoListAdapter == null) {
            return;
        }
        final List<StreamInfoItem> sorted = new ArrayList<>(originalOrderItems);
        switch (currentSortMode) {
            case NEWEST_FIRST:
                sorted.sort(uploadDateComparator().reversed());
                break;
            case OLDEST_FIRST:
                sorted.sort(uploadDateComparator());
                break;
            case ALPHABETICAL:
                sorted.sort(Comparator.comparing(
                        (StreamInfoItem item) -> item.getName() == null ? "" : item.getName(),
                        PlaylistFragment::compareTitleDigitsLettersOther));
                break;
            case DEFAULT:
            default:
                break;
        }
        infoListAdapter.clearStreamItemList();
        infoListAdapter.addInfoItemList(sorted);
        Log.i(PLSORT_TAG, "applyCurrentSort done sorted.size=" + sorted.size()
                + " adapter.size=" + infoListAdapter.getItemsList().size());
    }

    private static Comparator<StreamInfoItem> uploadDateComparator() {
        return Comparator.comparing(
                (StreamInfoItem item) -> item.getUploadDate() == null
                        ? null : item.getUploadDate().offsetDateTime(),
                Comparator.nullsLast(Comparator.<OffsetDateTime>naturalOrder()));
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
