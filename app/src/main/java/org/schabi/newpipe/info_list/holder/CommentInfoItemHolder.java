package org.schabi.newpipe.info_list.holder;

import static org.schabi.newpipe.util.ServiceHelper.getServiceById;
import static org.schabi.newpipe.util.text.TouchUtils.getOffsetForHorizontalLine;

import android.text.Spanned;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.text.style.URLSpan;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.fragment.app.FragmentActivity;

import org.schabi.newpipe.R;
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.comments.CommentsInfoItem;
import org.schabi.newpipe.extractor.stream.Description;
import org.schabi.newpipe.info_list.InfoItemBuilder;
import org.schabi.newpipe.local.history.HistoryRecordManager;
import org.schabi.newpipe.translation.TranslationManager;
import org.schabi.newpipe.util.DeviceUtils;
import org.schabi.newpipe.util.Localization;
import org.schabi.newpipe.util.NavigationHelper;
import org.schabi.newpipe.util.external_communication.ShareUtils;
import org.schabi.newpipe.util.image.ImageStrategy;
import org.schabi.newpipe.util.image.PicassoHelper;
import org.schabi.newpipe.util.text.TextEllipsizer;

import android.widget.Toast;

public class CommentInfoItemHolder extends InfoItemHolder {

    private static final int COMMENT_DEFAULT_LINES = 5;
    private final int commentHorizontalPadding;
    private final int commentVerticalPadding;

    private final RelativeLayout itemRoot;
    private final ImageView itemThumbnailView;
    private final TextView itemContentView;
    private final ImageView itemThumbsUpView;
    private final TextView itemLikesCountView;
    private final TextView itemTitleView;
    private final ImageView itemHeartView;
    private final ImageView itemPinnedView;
    private final Button repliesButton;
    private final TextView translateButton;

    @NonNull
    private final TextEllipsizer textEllipsizer;

    public CommentInfoItemHolder(final InfoItemBuilder infoItemBuilder,
                                 final ViewGroup parent) {
        super(infoItemBuilder, R.layout.list_comment_item, parent);

        itemRoot = itemView.findViewById(R.id.itemRoot);
        itemThumbnailView = itemView.findViewById(R.id.itemThumbnailView);
        itemContentView = itemView.findViewById(R.id.itemCommentContentView);
        itemThumbsUpView = itemView.findViewById(R.id.detail_thumbs_up_img_view);
        itemLikesCountView = itemView.findViewById(R.id.detail_thumbs_up_count_view);
        itemTitleView = itemView.findViewById(R.id.itemTitleView);
        itemHeartView = itemView.findViewById(R.id.detail_heart_image_view);
        itemPinnedView = itemView.findViewById(R.id.detail_pinned_view);
        repliesButton = itemView.findViewById(R.id.replies_button);
        translateButton = itemView.findViewById(R.id.translate_button);

        commentHorizontalPadding = (int) infoItemBuilder.getContext()
                .getResources().getDimension(R.dimen.comments_horizontal_padding);
        commentVerticalPadding = (int) infoItemBuilder.getContext()
                .getResources().getDimension(R.dimen.comments_vertical_padding);

        textEllipsizer = new TextEllipsizer(itemContentView, COMMENT_DEFAULT_LINES, null);
        textEllipsizer.setStateChangeListener(isEllipsized -> {
            if (Boolean.TRUE.equals(isEllipsized)) {
                denyLinkFocus();
            } else {
                determineMovementMethod();
            }
        });
    }

    @Override
    public void updateFromItem(final InfoItem infoItem,
                               final HistoryRecordManager historyRecordManager) {
        if (!(infoItem instanceof CommentsInfoItem)) {
            return;
        }
        final CommentsInfoItem item = (CommentsInfoItem) infoItem;


        // load the author avatar
        PicassoHelper.loadAvatar(item.getUploaderAvatars()).into(itemThumbnailView);
        if (ImageStrategy.shouldLoadImages()) {
            itemThumbnailView.setVisibility(View.VISIBLE);
            itemRoot.setPadding(commentVerticalPadding, commentVerticalPadding,
                    commentVerticalPadding, commentVerticalPadding);
        } else {
            itemThumbnailView.setVisibility(View.GONE);
            itemRoot.setPadding(commentHorizontalPadding, commentVerticalPadding,
                    commentHorizontalPadding, commentVerticalPadding);
        }
        itemThumbnailView.setOnClickListener(view -> openCommentAuthor(item));

        // setup the top row, with pinned icon, author name and comment date
        itemPinnedView.setVisibility(item.isPinned() ? View.VISIBLE : View.GONE);
        final String uploaderName = Localization.localizeUserName(item.getUploaderName());
        itemTitleView.setText(Localization.concatenateStrings(
                uploaderName,
                Localization.relativeTimeOrTextual(
                        itemBuilder.getContext(),
                        item.getUploadDate(),
                        item.getTextualUploadDate())));

        // setup bottom row, with likes, heart and replies button
        itemLikesCountView.setText(
                Localization.likeCount(itemBuilder.getContext(), item.getLikeCount()));

        itemHeartView.setVisibility(item.isHeartedByUploader() ? View.VISIBLE : View.GONE);

        final boolean hasReplies = item.getReplies() != null;
        repliesButton.setOnClickListener(hasReplies ? v -> openCommentReplies(item) : null);
        repliesButton.setVisibility(hasReplies ? View.VISIBLE : View.GONE);
        repliesButton.setText(hasReplies
                ? Localization.replyCount(itemBuilder.getContext(), item.getReplyCount()) : "");
        ((RelativeLayout.LayoutParams) itemThumbsUpView.getLayoutParams()).topMargin =
                hasReplies ? 0 : DeviceUtils.dpToPx(6, itemBuilder.getContext());


        // setup comment content and click listeners to expand/ellipsize it
        textEllipsizer.setStreamingService(getServiceById(item.getServiceId()));
        textEllipsizer.setStreamUrl(item.getUrl());
        textEllipsizer.setContent(item.getCommentText());
        textEllipsizer.ellipsize();

        setupTranslateButton(item);

        //noinspection ClickableViewAccessibility
        itemContentView.setOnTouchListener((v, event) -> {
            final CharSequence text = itemContentView.getText();
            if (text instanceof Spanned buffer) {
                final int action = event.getAction();

                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_DOWN) {
                    final int offset = getOffsetForHorizontalLine(itemContentView, event);
                    final var links = buffer.getSpans(offset, offset, ClickableSpan.class);

                    if (links.length != 0) {
                        if (action == MotionEvent.ACTION_UP) {
                            links[0].onClick(itemContentView);
                        }
                        // we handle events that intersect links, so return true
                        return true;
                    }
                }
            }
            return false;
        });

        itemView.setOnClickListener(view -> {
            textEllipsizer.toggle();
            if (itemBuilder.getOnCommentsSelectedListener() != null) {
                itemBuilder.getOnCommentsSelectedListener().selected(item);
            }
        });

        itemView.setOnLongClickListener(view -> {
            if (DeviceUtils.isTv(itemBuilder.getContext())) {
                openCommentAuthor(item);
            } else {
                final CharSequence text = itemContentView.getText();
                if (text != null) {
                    ShareUtils.copyToClipboard(itemBuilder.getContext(), text.toString());
                }
            }
            return true;
        });
    }

    private void openCommentAuthor(@NonNull final CommentsInfoItem item) {
        NavigationHelper.openCommentAuthorIfPresent((FragmentActivity) itemBuilder.getContext(),
                item);
    }

    private void openCommentReplies(@NonNull final CommentsInfoItem item) {
        NavigationHelper.openCommentRepliesFragment((FragmentActivity) itemBuilder.getContext(),
                item);
    }

    private void allowLinkFocus() {
        itemContentView.setMovementMethod(LinkMovementMethod.getInstance());
    }

    private void denyLinkFocus() {
        itemContentView.setMovementMethod(null);
    }

    private boolean shouldFocusLinks() {
        if (itemView.isInTouchMode()) {
            return false;
        }

        final URLSpan[] urls = itemContentView.getUrls();

        return urls != null && urls.length != 0;
    }

    private void determineMovementMethod() {
        if (shouldFocusLinks()) {
            allowLinkFocus();
        } else {
            denyLinkFocus();
        }
    }

    private static String translationKey(@NonNull final CommentsInfoItem item) {
        // Comments on the same video share item.getUrl() (it's the video URL),
        // so we MUST key on the per-comment id to avoid translation results
        // bleeding between comments. Fall back to a content hash when the
        // service didn't provide a stable id.
        final String id = item.getCommentId();
        if (id != null && !id.isEmpty()) {
            return id;
        }
        final String url = item.getUrl() != null ? item.getUrl() : "";
        final String text = item.getCommentText() != null
                ? item.getCommentText().getContent() : "";
        return url + "#" + text.hashCode();
    }

    private void setupTranslateButton(@NonNull final CommentsInfoItem item) {
        if (!TranslationManager.isEnabled(itemBuilder.getContext())) {
            translateButton.setVisibility(View.GONE);
            translateButton.setOnClickListener(null);
            return;
        }
        translateButton.setVisibility(View.VISIBLE);

        final TranslationManager mgr =
                TranslationManager.getInstance(itemBuilder.getContext());
        final String key = translationKey(item);

        // Restore prior toggle state across RecyclerView rebinds.
        final String cached = mgr.getCachedTranslation(key);
        if (mgr.isShowingTranslation(key) && cached != null) {
            textEllipsizer.setContent(new Description(cached, Description.PLAIN_TEXT));
            textEllipsizer.expand();
            translateButton.setText(R.string.translation_action_show_original);
        } else {
            translateButton.setText(R.string.translation_action_translate);
        }
        translateButton.setEnabled(true);

        translateButton.setOnClickListener(v -> onTranslateClicked(item, mgr, key));

        // Auto-translate if we're inside a CommentRepliesFragment whose parent
        // was translated, the comment isn't already translated or in flight,
        // and we still have an auto-translate slot available.
        if (mgr.isAutoTranslateActive()
                && cached == null
                && !mgr.isShowingTranslation(key)
                && !mgr.isInFlight(key)
                && mgr.tryConsumeAutoTranslateSlot()) {
            onTranslateClicked(item, mgr, key);
        } else if (mgr.isInFlight(key)) {
            // A translation is already running for this comment — reflect it
            // in the button while we wait.
            translateButton.setEnabled(false);
            translateButton.setText(R.string.translation_action_translating);
        }
    }

    private void onTranslateClicked(@NonNull final CommentsInfoItem item,
                                    @NonNull final TranslationManager mgr,
                                    @NonNull final String key) {
        final String modelUri =
                TranslationManager.getModelUriString(itemBuilder.getContext());
        if (modelUri == null || modelUri.isEmpty()) {
            Toast.makeText(itemBuilder.getContext(),
                    R.string.translation_no_model, Toast.LENGTH_LONG).show();
            return;
        }

        // Toggle back to the original.
        if (mgr.isShowingTranslation(key)) {
            mgr.setShowingTranslation(key, false);
            textEllipsizer.setContent(item.getCommentText());
            textEllipsizer.ellipsize();
            translateButton.setText(R.string.translation_action_translate);
            return;
        }

        // If we already translated it once before, just swap.
        final String cached = mgr.getCachedTranslation(key);
        if (cached != null) {
            mgr.setShowingTranslation(key, true);
            textEllipsizer.setContent(new Description(cached, Description.PLAIN_TEXT));
            textEllipsizer.expand();
            translateButton.setText(R.string.translation_action_show_original);
            return;
        }

        // First-time translation: kick off async work.
        translateButton.setEnabled(false);
        translateButton.setText(R.string.translation_action_translating);
        mgr.markInFlight(key);

        final String src = item.getCommentText() != null
                ? item.getCommentText().getContent() : "";

        mgr.translateAsync(src, new TranslationManager.Callback() {
            @Override
            public void onSuccess(@NonNull final String translated) {
                final String currentKey = translationKey(item);
                android.util.Log.i("CommentTranslate",
                        "onSuccess: translatedLen=" + translated.length()
                                + " bindKey=" + key + " currentKey=" + currentKey
                                + " match=" + key.equals(currentKey));
                mgr.markDone(key);
                mgr.rememberTranslation(key, translated);
                mgr.setShowingTranslation(key, true);
                if (!key.equals(currentKey)) {
                    android.util.Log.w("CommentTranslate",
                            "onSuccess: holder rebound, not applying to UI");
                    return;
                }
                translateButton.setEnabled(true);
                textEllipsizer.setContent(
                        new Description(translated, Description.PLAIN_TEXT));
                textEllipsizer.expand();
                translateButton.setText(R.string.translation_action_show_original);
                android.util.Log.i("CommentTranslate",
                        "onSuccess: UI updated to translated text");
            }

            @Override
            public void onFailure(@NonNull final String error) {
                android.util.Log.w("CommentTranslate", "onFailure: " + error);
                mgr.markDone(key);
                translateButton.setEnabled(true);
                translateButton.setText(R.string.translation_action_translate);
                Toast.makeText(itemBuilder.getContext(),
                        itemBuilder.getContext().getString(
                                R.string.translation_failed, error),
                        Toast.LENGTH_LONG).show();
            }
        });
    }
}
