package xyz.gianlu.librespot.player.playback;

import javazoom.jl.decoder.BitstreamException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.gianlu.librespot.common.Utils;
import xyz.gianlu.librespot.core.EventService.PlaybackMetrics;
import xyz.gianlu.librespot.core.Session;
import xyz.gianlu.librespot.mercury.MercuryClient;
import xyz.gianlu.librespot.mercury.model.EpisodeId;
import xyz.gianlu.librespot.mercury.model.PlayableId;
import xyz.gianlu.librespot.mercury.model.TrackId;
import xyz.gianlu.librespot.player.ContentRestrictedException;
import xyz.gianlu.librespot.player.StateWrapper;
import xyz.gianlu.librespot.player.TrackOrEpisode;
import xyz.gianlu.librespot.player.codecs.Codec;
import xyz.gianlu.librespot.player.codecs.Mp3Codec;
import xyz.gianlu.librespot.player.codecs.VorbisCodec;
import xyz.gianlu.librespot.player.codecs.VorbisOnlyAudioQuality;
import xyz.gianlu.librespot.player.crossfade.CrossfadeController;
import xyz.gianlu.librespot.player.feeders.HaltListener;
import xyz.gianlu.librespot.player.feeders.PlayableContentFeeder;
import xyz.gianlu.librespot.player.feeders.cdn.CdnManager;
import xyz.gianlu.librespot.player.mixing.MixingLine;

import javax.sound.sampled.AudioFormat;
import java.io.Closeable;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.Comparator;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

/**
 * An object representing one single content/track/episode associated with its playback ID. This is responsible for IO operations,
 * decoding, metrics, crossfade and instant notifications.
 *
 * @author devgianlu
 */
class PlayerQueueEntry extends PlayerQueue.Entry implements Closeable, Runnable, HaltListener {
    static final int INSTANT_PRELOAD = 1;
    static final int INSTANT_START_NEXT = 2;
    static final int INSTANT_END = 3;
    private static final Logger LOGGER = LogManager.getLogger(PlayerQueueEntry.class);
    final PlayableId playable;
    final String playbackId;
    private final boolean preloaded;
    private final Listener listener;
    private final Object playbackLock = new Object();
    private final TreeMap<Integer, Integer> notifyInstants = new TreeMap<>(Comparator.comparingInt(o -> o));
    private final Session session;
    private final AudioFormat format;
    CrossfadeController crossfade;
    PlaybackMetrics.Reason endReason = PlaybackMetrics.Reason.END_PLAY;
    private Codec codec;
    private TrackOrEpisode metadata;
    private volatile boolean closed = false;
    private volatile MixingLine.MixingOutput output;
    private long playbackHaltedAt = 0;
    private volatile int seekTime = -1;
    private boolean retried = false;
    private PlayableContentFeeder.Metrics contentMetrics;

    PlayerQueueEntry(@NotNull Session session, @NotNull AudioFormat format, @NotNull PlayableId playable, boolean preloaded, @NotNull Listener listener) {
        this.session = session;
        this.format = format;
        this.playbackId = StateWrapper.generatePlaybackId(session.random());
        this.playable = playable;
        this.preloaded = preloaded;
        this.listener = listener;

        LOGGER.trace("Created new {}.", this);
    }

    @NotNull
    PlayerQueueEntry retrySelf(boolean preloaded) {
        if (retried) throw new IllegalStateException();

        PlayerQueueEntry retry = new PlayerQueueEntry(session, format, playable, preloaded, listener);
        retry.retried = true;
        return retry;
    }

    /**
     * Loads the content described by this entry.
     *
     * @throws ContentRestrictedException If the content cannot be retrieved because of restrictions (this condition won't change with a retry).
     */
    private void load(boolean preload) throws IOException, Codec.CodecException, MercuryClient.MercuryException, CdnManager.CdnException, ContentRestrictedException {
        PlayableContentFeeder.LoadedStream stream = session.contentFeeder().load(playable, new VorbisOnlyAudioQuality(session.conf().preferredQuality()), preload, this);
        metadata = new TrackOrEpisode(stream.track, stream.episode);
        contentMetrics = stream.metrics;

        if (playable instanceof EpisodeId && stream.episode != null) {
            LOGGER.info("Loaded episode. {name: '{}', uri: {}, id: {}}", stream.episode.getName(), playable.toSpotifyUri(), playbackId);
        } else if (playable instanceof TrackId && stream.track != null) {
            LOGGER.info("Loaded track. {name: '{}', artists: '{}', uri: {}, id: {}}", stream.track.getName(),
                    Utils.artistsToString(stream.track.getArtistList()), playable.toSpotifyUri(), playbackId);
        }

        crossfade = new CrossfadeController(playbackId, metadata.duration(), listener.metadataFor(playable), session.conf());
        if (crossfade.hasAnyFadeOut() || session.conf().preloadEnabled())
            notifyInstant(INSTANT_PRELOAD, (int) (crossfade.fadeOutStartTimeMin() - TimeUnit.SECONDS.toMillis(20)));

        switch (stream.in.codec()) {
            case VORBIS:
                codec = new VorbisCodec(format, stream.in, stream.normalizationData, session.conf(), metadata.duration());
                break;
            case MP3:
                try {
                    codec = new Mp3Codec(format, stream.in, stream.normalizationData, session.conf(), metadata.duration());
                } catch (BitstreamException ex) {
                    throw new IOException(ex);
                }
                break;
            default:
                throw new UnsupportedEncodingException(stream.in.codec().toString());
        }

        LOGGER.trace("Loaded {} codec. {of: {}, format: {}, playbackId: {}}", stream.in.codec(), stream.in.describe(), codec.getAudioFormat(), playbackId);
    }

    /**
     * Gets the metadata associated with this entry.
     *
     * @return A {@link TrackOrEpisode} object or {@code null} if not loaded yet
     */
    @Nullable
    public TrackOrEpisode metadata() {
        return metadata;
    }

    /**
     * Returns the metrics for this entry.
     *
     * @return A {@link PlayerMetrics} object
     */
    @NotNull
    PlayerMetrics metrics() {
        return new PlayerMetrics(contentMetrics, crossfade, codec);
    }

    /**
     * Returns the current position.
     *
     * @return The current position of the player or {@code -1} if not ready.
     * @throws Codec.CannotGetTimeException If the time is unavailable for the codec being used.
     */
    int getTime() throws Codec.CannotGetTimeException {
        return codec == null ? -1 : codec.time();
    }

    /**
     * Returns the current position.
     *
     * @return The current position of the player or {@code -1} if not available.
     * @see PlayerQueueEntry#getTime()
     */
    int getTimeNoThrow() {
        try {
            return getTime();
        } catch (Codec.CannotGetTimeException e) {
            return -1;
        }
    }

    /**
     * Seeks to the specified position.
     *
     * @param pos The time in milliseconds
     */
    void seek(int pos) {
        seekTime = pos;
        if (output != null) output.stream().emptyBuffer();
    }

    /**
     * Sets the output to {@param output}. As soon as this method returns the entry will start playing.
     *
     * @throws IllegalStateException If the output is already set. Will also clear {@param output}.
     */
    void setOutput(@NotNull MixingLine.MixingOutput output) {
        if (closed || this.output != null) {
            output.clear();
            throw new IllegalStateException("Cannot set output for " + this);
        }

        synchronized (playbackLock) {
            this.output = output;
            playbackLock.notifyAll();
        }

        this.output.toggle(true);
    }

    /**
     * Removes the output. As soon as this method is called the entry will stop playing.
     */
    private void clearOutput() {
        if (output != null) {
            MixingLine.MixingOutput tmp = output;
            output = null;

            tmp.toggle(false);
            tmp.clear();

            LOGGER.debug("{} has been removed from output.", this);
        }

        synchronized (playbackLock) {
            playbackLock.notifyAll();
        }
    }

    /**
     * Instructs to notify when this time instant is reached.
     *
     * @param callbackId The callback ID
     * @param when       The time in milliseconds
     */
    void notifyInstant(int callbackId, int when) {
        if (codec != null) {
            try {
                int time = codec.time();
                if (time >= when) {
                    listener.instantReached(this, callbackId, time);
                    return;
                }
            } catch (Codec.CannotGetTimeException ex) {
                return;
            }
        }

        notifyInstants.put(when, callbackId);
    }

    @Override
    public void run() {
        listener.startedLoading(this);

        try {
            load(preloaded);
        } catch (IOException | ContentRestrictedException | CdnManager.CdnException | MercuryClient.MercuryException | Codec.CodecException ex) {
            close();
            listener.loadingError(this, ex, retried);
            LOGGER.trace("%{} terminated at loading.", this, ex);
            return;
        }

        if (seekTime != -1) {
            codec.seek(seekTime);
            seekTime = -1;
        }

        listener.finishedLoading(this, metadata);

        boolean canGetTime = true;
        while (!closed) {
            if (output == null) {
                synchronized (playbackLock) {
                    try {
                        playbackLock.wait();
                    } catch (InterruptedException ex) {
                        break;
                    }
                }

                if (output == null) continue;
            }

            if (closed) break;

            if (seekTime != -1) {
                codec.seek(seekTime);
                seekTime = -1;
            }

            if (canGetTime) {
                try {
                    int time = codec.time();
                    if (!notifyInstants.isEmpty()) checkInstants(time);
                    if (output == null)
                        continue;

                    output.gain(crossfade.getGain(time));
                } catch (Codec.CannotGetTimeException ex) {
                    canGetTime = false;
                }
            }

            try {
                if (codec.writeSomeTo(output.stream()) == -1) {
                    try {
                        int time = codec.time();
                        LOGGER.debug("Player time offset is {}. {id: {}}", metadata.duration() - time, playbackId);
                    } catch (Codec.CannotGetTimeException ignored) {
                    }

                    close();
                    break;
                }
            } catch (IOException | Codec.CodecException ex) {
                if (!closed) {
                    close();
                    listener.playbackError(this, ex);
                    return;
                }

                break;
            }
        }

        listener.playbackEnded(this);
        LOGGER.trace("{} terminated.", this);
    }

    private void checkInstants(int time) {
        int key = notifyInstants.firstKey();
        if (time >= key) {
            int callbackId = notifyInstants.remove(key);
            listener.instantReached(this, callbackId, time);
            if (!notifyInstants.isEmpty()) checkInstants(time);
        }
    }

    /**
     * Close this entry if it's not attached to an output.
     *
     * @return Whether it has been closed
     */
    boolean closeIfUseless() {
        if (output == null) {
            close();
            return true;
        }

        return false;
    }

    @Override
    public void close() {
        closed = true;
        clearOutput();
    }

    @Override
    public void streamReadHalted(int chunk, long time) {
        playbackHaltedAt = time;
        listener.playbackHalted(this, chunk);
    }

    @Override
    public void streamReadResumed(int chunk, long time) {
        if (playbackHaltedAt == 0) return;

        int duration = (int) (time - playbackHaltedAt);
        listener.playbackResumed(this, chunk, duration);
    }

    @Override
    public String toString() {
        return "PlayerQueueEntry{" + playbackId + "}";
    }

    interface Listener {
        /**
         * An error occurred during playback.
         *
         * @param entry The {@link PlayerQueueEntry} that called this
         * @param ex    The exception thrown
         */
        void playbackError(@NotNull PlayerQueueEntry entry, @NotNull Exception ex);

        /**
         * The playback of the current entry ended.
         *
         * @param entry The {@link PlayerQueueEntry} that called this
         */
        void playbackEnded(@NotNull PlayerQueueEntry entry);

        /**
         * The playback halted while trying to receive a chunk.
         *
         * @param entry The {@link PlayerQueueEntry} that called this
         * @param chunk The chunk that is being retrieved
         */
        void playbackHalted(@NotNull PlayerQueueEntry entry, int chunk);

        /**
         * The playback resumed from halt.
         *
         * @param entry The {@link PlayerQueueEntry} that called this
         * @param chunk The chunk that was being retrieved
         * @param diff  The time taken to retrieve the chunk
         */
        void playbackResumed(@NotNull PlayerQueueEntry entry, int chunk, int diff);

        /**
         * Notify that a previously request instant has been reached. This is called from the runner, be careful.
         *
         * @param entry      The {@link PlayerQueueEntry} that called this
         * @param callbackId The callback ID for the instant
         * @param exactTime  The exact time the instant was reached
         */
        void instantReached(@NotNull PlayerQueueEntry entry, int callbackId, int exactTime);

        /**
         * The track started loading.
         *
         * @param entry The {@link PlayerQueueEntry} that called this
         */
        void startedLoading(@NotNull PlayerQueueEntry entry);

        /**
         * The track failed loading.
         *
         * @param entry   The {@link PlayerQueueEntry} that called this
         * @param ex      The exception thrown
         * @param retried Whether this is the second time an error occurs
         */
        void loadingError(@NotNull PlayerQueueEntry entry, @NotNull Exception ex, boolean retried);

        /**
         * The track finished loading.
         *
         * @param entry    The {@link PlayerQueueEntry} that called this
         * @param metadata The {@link TrackOrEpisode} object
         */
        void finishedLoading(@NotNull PlayerQueueEntry entry, @NotNull TrackOrEpisode metadata);

        /**
         * Get the metadata for this content.
         *
         * @param playable The content
         * @return A map containing all the metadata related
         */
        @NotNull
        Map<String, String> metadataFor(@NotNull PlayableId playable);
    }
}
