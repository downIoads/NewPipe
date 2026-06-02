package org.schabi.newpipe.fragments.list.comments;

import android.os.Bundle;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.schabi.newpipe.R;
import org.schabi.newpipe.error.UserAction;
import org.schabi.newpipe.extractor.ListExtractor;
import org.schabi.newpipe.extractor.comments.CommentsInfo;
import org.schabi.newpipe.extractor.comments.CommentsInfoItem;
import org.schabi.newpipe.fragments.list.BaseListInfoFragment;
import org.schabi.newpipe.info_list.ItemViewMode;
import org.schabi.newpipe.ktx.ViewUtils;
import org.schabi.newpipe.util.ExtractorHelper;
import org.schabi.newpipe.util.InfoCache;
import org.schabi.newpipe.util.PersistentPlayerLogger;

import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.disposables.CompositeDisposable;

public class CommentsFragment extends BaseListInfoFragment<CommentsInfoItem, CommentsInfo> {
    private final CompositeDisposable disposables = new CompositeDisposable();

    private TextView emptyStateDesc;
    private long commentsLoadStartMs = -1L;

    public static CommentsFragment getInstance(final int serviceId, final String url,
                                               final String name) {
        final CommentsFragment instance = new CommentsFragment();
        instance.setInitialData(serviceId, url, name);
        return instance;
    }

    public CommentsFragment() {
        super(UserAction.REQUESTED_COMMENTS);
    }

    @Override
    protected void initViews(final View rootView, final Bundle savedInstanceState) {
        super.initViews(rootView, savedInstanceState);

        emptyStateDesc = rootView.findViewById(R.id.empty_state_desc);
    }

    /*//////////////////////////////////////////////////////////////////////////
    // LifeCycle
    //////////////////////////////////////////////////////////////////////////*/

    @Override
    public View onCreateView(@NonNull final LayoutInflater inflater,
                             @Nullable final ViewGroup container,
                             @Nullable final Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_comments, container, false);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        disposables.clear();
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Load and handle
    //////////////////////////////////////////////////////////////////////////*/

    @Override
    protected Single<ListExtractor.InfoItemsPage<CommentsInfoItem>> loadMoreItemsLogic() {
        return ExtractorHelper.getMoreCommentItems(serviceId, currentInfo, currentNextPage);
    }

    @Override
    protected Single<CommentsInfo> loadResult(final boolean forceLoad) {
        return ExtractorHelper.getCommentsInfo(serviceId, url, forceLoad);
    }

    @Override
    public void startLoading(final boolean forceLoad) {
        commentsLoadStartMs = SystemClock.elapsedRealtime();
        PersistentPlayerLogger.log(getContext(), "CommentsLoadTrace +0ms start"
                + " forceLoad=" + forceLoad
                + " cached=" + ExtractorHelper.isCached(serviceId, url, InfoCache.Type.COMMENTS)
                + " url=" + url);
        super.startLoading(forceLoad);
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Contract
    //////////////////////////////////////////////////////////////////////////*/

    @Override
    public void handleResult(@NonNull final CommentsInfo result) {
        super.handleResult(result);
        final long elapsedMs = commentsLoadStartMs < 0
                ? -1
                : SystemClock.elapsedRealtime() - commentsLoadStartMs;
        PersistentPlayerLogger.log(getContext(), "CommentsLoadTrace +" + elapsedMs + "ms loaded"
                + " items=" + result.getRelatedItems().size()
                + " hasNext=" + (result.getNextPage() != null)
                + " disabled=" + result.isCommentsDisabled()
                + " url=" + url);

        emptyStateDesc.setText(
                result.isCommentsDisabled()
                        ? R.string.comments_are_disabled
                        : R.string.no_comments);

        ViewUtils.slideUp(requireView(), 120, 150, 0.06f);
        disposables.clear();
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Utils
    //////////////////////////////////////////////////////////////////////////*/

    @Override
    public void setTitle(final String title) { }

    @Override
    public void onCreateOptionsMenu(@NonNull final Menu menu,
                                    @NonNull final MenuInflater inflater) { }

    @Override
    protected ItemViewMode getItemViewMode() {
        return ItemViewMode.LIST;
    }

    /**
     * Best-effort restore of the comments list scroll position to {@code comment}
     * (the root comment whose replies thread the user just left).
     *
     * <p>Returning from a comment-replies thread via the back gesture re-expands the
     * video-detail bottom sheet, and the {@code STATE_EXPANDED} callback can fire this
     * before the ViewPager has re-attached this tab. {@code infoListAdapter} is created
     * lazily in {@link org.schabi.newpipe.fragments.list.BaseListFragment#onAttach}, so it
     * (and {@code itemsList}) may still be {@code null} here. Previously this dereferenced
     * the null adapter and crashed (NPE in getItemsList()); now we guard and fall back to
     * the top of the list. This method must never throw.</p>
     *
     * @param comment the root comment to scroll back to
     * @return {@code true} only if the exact previous position was restored; {@code false}
     *         if we fell back (tab not ready yet, or the comment is no longer in the list).
     */
    public boolean scrollToComment(final CommentsInfoItem comment) {
        if (infoListAdapter == null || itemsList == null) {
            // Comments tab not attached yet -> we cannot scroll. The ViewPager will show
            // this tab at the top once it attaches, which is the desired fallback.
            Log.w(TAG, "scrollToComment: comments tab not ready (infoListAdapter="
                    + infoListAdapter + ", itemsList=" + itemsList
                    + "); falling back to top of comment section");
            return false;
        }

        final int size = infoListAdapter.getItemsList().size();
        final int position = infoListAdapter.getItemsList().indexOf(comment);
        if (position < 0) {
            // The root comment is no longer in the loaded list (e.g. list was reloaded);
            // fall back to the top of the comment section as requested.
            Log.d(TAG, "scrollToComment: root comment not found among " + size
                    + " loaded items; scrolling to top as fallback");
            itemsList.scrollToPosition(0);
            return false;
        }

        Log.d(TAG, "scrollToComment: restoring position " + position + " of " + size
                + " loaded items");
        itemsList.scrollToPosition(position);
        return true;
    }

    public void updateStream(final int newServiceId,
                             @NonNull final String newUrl,
                             @NonNull final String newName,
                             final boolean forceLoad) {
        if (serviceId == newServiceId && TextUtils.equals(url, newUrl)) {
            return;
        }

        setInitialData(newServiceId, newUrl, newName);
        PersistentPlayerLogger.log(getContext(), "CommentsLoadTrace updateStream"
                + " forceLoad=" + forceLoad
                + " newUrl=" + newUrl);
        currentInfo = null;
        currentNextPage = null;
        if (currentWorker != null) {
            currentWorker.dispose();
            currentWorker = null;
        }
        if (getView() != null) {
            startLoading(forceLoad);
        }
    }
}
