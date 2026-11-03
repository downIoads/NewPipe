package org.schabi.newpipe.local.playlist;

import androidx.annotation.Nullable;

import org.schabi.newpipe.database.AppDatabase;
import org.schabi.newpipe.database.playlist.PlaylistDuplicatesEntry;
import org.schabi.newpipe.database.playlist.PlaylistMetadataEntry;
import org.schabi.newpipe.database.playlist.PlaylistStreamEntry;
import org.schabi.newpipe.database.playlist.dao.PlaylistDAO;
import org.schabi.newpipe.database.playlist.dao.PlaylistStreamDAO;
import org.schabi.newpipe.database.playlist.model.PlaylistEntity;
import org.schabi.newpipe.database.playlist.model.PlaylistStreamEntity;
import org.schabi.newpipe.database.stream.dao.StreamDAO;
import org.schabi.newpipe.database.stream.model.StreamEntity;
import org.schabi.newpipe.extractor.exceptions.ContentNotYetAvailableException;
import org.schabi.newpipe.extractor.stream.ContentAvailability;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.util.ExtractorHelper;
import org.schabi.newpipe.util.StreamTypeUtil;

import java.util.ArrayList;
import java.util.List;

import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.schedulers.Schedulers;

public class LocalPlaylistManager {
    private static final long THUMBNAIL_ID_LEAVE_UNCHANGED = -2;

    private final AppDatabase database;
    private final StreamDAO streamTable;
    private final PlaylistDAO playlistTable;
    private final PlaylistStreamDAO playlistStreamTable;

    public LocalPlaylistManager(final AppDatabase db) {
        database = db;
        streamTable = db.streamDAO();
        playlistTable = db.playlistDAO();
        playlistStreamTable = db.playlistStreamDAO();
    }

    public Maybe<List<Long>> createPlaylist(final String name, final List<StreamEntity> streams) {
        // Disallow creation of empty playlists
        if (streams.isEmpty() || PlaylistEntity.isWatchLaterName(name)) {
            return Maybe.empty();
        }

        // Save to the database directly.
        // Make sure the new playlist is always on the top of bookmark.
        // The index will be reassigned to non-negative number in BookmarkFragment.
        return Maybe.fromCallable(() -> database.runInTransaction(() -> {
                    final List<Long> streamIds = streamTable.upsertAll(streams);
                    final PlaylistEntity newPlaylist = new PlaylistEntity(name, false,
                            streamIds.get(0), -1);

                    return insertJoinEntities(playlistTable.insert(newPlaylist),
                            streamIds, 0);
                }
        )).subscribeOn(Schedulers.io());
    }

    public Maybe<List<Long>> appendToPlaylist(final long playlistId,
                                              final List<StreamEntity> streams) {
        return playlistStreamTable.getMaximumIndexOf(playlistId)
                .firstElement()
                .map(maxJoinIndex -> database.runInTransaction(() -> {
                            final List<Long> streamIds = streamTable.upsertAll(streams);
                            return insertJoinEntities(playlistId, streamIds, maxJoinIndex + 1);
                        }
                )).subscribeOn(Schedulers.io());
    }

    private List<Long> insertJoinEntities(final long playlistId, final List<Long> streamIds,
                                          final int indexOffset) {

        final List<PlaylistStreamEntity> joinEntities = new ArrayList<>(streamIds.size());

        for (int index = 0; index < streamIds.size(); index++) {
            joinEntities.add(new PlaylistStreamEntity(playlistId, streamIds.get(index),
                    index + indexOffset));
        }
        return playlistStreamTable.insertAll(joinEntities);
    }

    public Completable updateJoin(final long playlistId, final List<Long> streamIds) {
        final List<PlaylistStreamEntity> joinEntities = new ArrayList<>(streamIds.size());
        for (int i = 0; i < streamIds.size(); i++) {
            joinEntities.add(new PlaylistStreamEntity(playlistId, streamIds.get(i), i));
        }

        return Completable.fromRunnable(() -> database.runInTransaction(() -> {
            playlistStreamTable.deleteBatch(playlistId);
            playlistStreamTable.insertAll(joinEntities);
        })).subscribeOn(Schedulers.io());
    }

    public Completable updatePlaylists(final List<PlaylistMetadataEntry> updateItems,
                                       final List<Long> deletedItems) {
        final List<PlaylistEntity> items = new ArrayList<>(updateItems.size());
        for (final PlaylistMetadataEntry item : updateItems) {
            items.add(new PlaylistEntity(item));
        }
        return Completable.fromRunnable(() -> database.runInTransaction(() -> {
            final long watchLaterId = ensureWatchLaterPlaylistInTransaction();
            for (final Long uid : deletedItems) {
                playlistTable.deletePlaylist(uid);
            }
            for (final PlaylistEntity item : items) {
                if (item.getUid() == watchLaterId) {
                    item.setName(PlaylistEntity.WATCH_LATER_NAME);
                    playlistTable.upsertPlaylist(item);
                } else if (!PlaylistEntity.isWatchLaterName(item.getName())) {
                    playlistTable.upsertPlaylist(item);
                }
            }
        })).subscribeOn(Schedulers.io());
    }

    /**
     * Ensure the reserved, empty-capable Watch Later playlist exists.
     *
     * @return completion after the playlist was found or created
     */
    public Completable ensureWatchLaterPlaylist() {
        return Completable.fromAction(() -> database.runInTransaction(() ->
                ensureWatchLaterPlaylistInTransaction()))
                .subscribeOn(Schedulers.io());
    }

    private long ensureWatchLaterPlaylistInTransaction() {
        final Long existingId = playlistTable.getPlaylistIdByName(
                PlaylistEntity.WATCH_LATER_NAME);
        if (existingId != null) {
            return existingId;
        }

        return playlistTable.insert(new PlaylistEntity(PlaylistEntity.WATCH_LATER_NAME,
                false, PlaylistEntity.DEFAULT_THUMBNAIL_ID, -1));
    }

    public Flowable<List<PlaylistStreamEntry>> getDistinctPlaylistStreams(final long playlistId) {
        return playlistStreamTable
                .getStreamsWithoutDuplicates(playlistId).subscribeOn(Schedulers.io());
    }

    /**
     * Get playlists with attached information about how many times the provided stream is already
     * contained in each playlist.
     *
     * @param streamUrl the stream url for which to check for duplicates
     * @return a list of {@link PlaylistDuplicatesEntry}
     */
    public Flowable<List<PlaylistDuplicatesEntry>> getPlaylistDuplicates(final String streamUrl) {
        return ensureWatchLaterPlaylist()
                .andThen(playlistStreamTable.getPlaylistDuplicatesMetadata(streamUrl))
                .subscribeOn(Schedulers.io());
    }

    public Flowable<List<PlaylistMetadataEntry>> getPlaylists() {
        return ensureWatchLaterPlaylist()
                .andThen(playlistStreamTable.getPlaylistMetadata())
                .subscribeOn(Schedulers.io());
    }

    public Flowable<List<PlaylistStreamEntry>> getPlaylistStreams(final long playlistId) {
        return playlistStreamTable.getOrderedStreamsOf(playlistId).subscribeOn(Schedulers.io());
    }

    /**
     * Refresh streams last known to be live and probe ambiguous durationless videos once, leaving
     * cached rows usable when offline. A durationless video with unknown availability can be an
     * upcoming livestream imported from the fast RSS feed. Once classified, it is no longer
     * probed.
     *
     * @param playlistId the playlist to refresh
     * @return completion after all relevant streams have been checked
     */
    public Completable refreshLiveStreams(final long playlistId) {
        return getDistinctPlaylistStreams(playlistId).firstOrError()
                .flattenAsFlowable(streams -> streams)
                .map(PlaylistStreamEntry::getStreamEntity)
                .filter(stream -> StreamTypeUtil.isLiveStream(stream.getStreamType())
                        || isUnknownDurationlessVideo(stream))
                .flatMapCompletable(this::refreshPlaylistStream, false, 2);
    }

    private Completable refreshPlaylistStream(final StreamEntity stream) {
        final boolean canBeUpcoming = isUnknownDurationlessVideo(stream);
        return ExtractorHelper.getStreamInfo(stream.getServiceId(), stream.getUrl(), true)
                .flatMapCompletable(info -> Completable.fromAction(() ->
                        streamTable.updateLiveMetadata(stream.getUid(), info.getStreamType(),
                                info.getViewCount(), info.getDuration(),
                                info.getContentAvailability())))
                .onErrorResumeNext(error -> {
                    if (canBeUpcoming && error instanceof ContentNotYetAvailableException) {
                        return Completable.fromAction(() -> streamTable.updateContentAvailability(
                                stream.getUid(), ContentAvailability.UPCOMING));
                    }
                    return Completable.complete();
                })
                .subscribeOn(Schedulers.io());
    }

    private static boolean isUnknownDurationlessVideo(final StreamEntity stream) {
        return stream.getStreamType() == StreamType.VIDEO_STREAM
                && stream.getDuration() < 0
                && stream.getContentAvailability() == ContentAvailability.UNKNOWN;
    }

    public Maybe<Integer> renamePlaylist(final long playlistId, final String name) {
        if (PlaylistEntity.isWatchLaterName(name)) {
            return Maybe.empty();
        }
        return modifyPlaylist(playlistId, name, THUMBNAIL_ID_LEAVE_UNCHANGED, false);
    }

    public Maybe<Integer> changePlaylistThumbnail(final long playlistId,
                                                  final long thumbnailStreamId,
                                                  final boolean isPermanent) {
        return modifyPlaylist(playlistId, null, thumbnailStreamId, isPermanent);
    }

    public long getPlaylistThumbnailStreamId(final long playlistId) {
        return playlistTable.getPlaylist(playlistId).blockingFirst().get(0).getThumbnailStreamId();
    }

    public boolean getIsPlaylistThumbnailPermanent(final long playlistId) {
        return playlistTable.getPlaylist(playlistId).blockingFirst().get(0)
                .isThumbnailPermanent();
    }

    public long getAutomaticPlaylistThumbnailStreamId(final long playlistId) {
        final long streamId = playlistStreamTable.getAutomaticThumbnailStreamId(playlistId)
                .blockingFirst();
        if (streamId < 0) {
            return PlaylistEntity.DEFAULT_THUMBNAIL_ID;
        }
        return streamId;
    }

    private Maybe<Integer> modifyPlaylist(final long playlistId,
                                          @Nullable final String name,
                                          final long thumbnailStreamId,
                                          final boolean isPermanent) {
        return playlistTable.getPlaylist(playlistId)
                .firstElement()
                .filter(playlistEntities -> !playlistEntities.isEmpty())
                .filter(playlistEntities -> name == null || !PlaylistEntity.isWatchLaterName(
                        playlistEntities.get(0).getName()))
                .map(playlistEntities -> {
                    final PlaylistEntity playlist = playlistEntities.get(0);
                    if (name != null) {
                        playlist.setName(name);
                    }
                    if (thumbnailStreamId != THUMBNAIL_ID_LEAVE_UNCHANGED) {
                        playlist.setThumbnailStreamId(thumbnailStreamId);
                        playlist.setThumbnailPermanent(isPermanent);
                    }
                    return playlistTable.update(playlist);
                }).subscribeOn(Schedulers.io());
    }

    public Maybe<Boolean> hasPlaylists() {
        return playlistTable.getCount()
                .firstElement()
                .map(count -> count > 0)
                .subscribeOn(Schedulers.io());
    }
}
