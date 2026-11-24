package io.ludus;

/*
 * LudusLogger.java — Single-file telemetry client for FTC robots.
 *
 * Drop this file into your FTC project and use it to stream live telemetry
 * (channel data, events, and segments) to the Ludus Collector app over WiFi.
 * The Collector runs on a laptop on the same network and handles storage +
 * upload to the Ludus server.
 *
 * Zero external dependencies beyond OkHttp (already bundled in the FTC SDK).
 * MessagePack encoding is implemented inline so no additional libraries are needed.
 *
 * ============================================================================
 * USAGE — CALLBACK MODE (recommended)
 * ============================================================================
 * Use the onTick callback for high-frequency data (motors, pose, sensors).
 * A background thread fires the callback at 50 Hz and sends the frame
 * automatically. Events and segments can be called from any thread at any time.
 *
 *   LudusLogger log = LudusLogger.create(
 *       new LudusLogger.SessionConfig.Builder()
 *           .opmode("AutoBlueRight")
 *           .alliance("BLUE")
 *           .robotId("gladius-2026")
 *           .collectorHost("192.168.43.1")   // laptop running Collector
 *           .teamNumber(4567)
 *           .apiKey("tk_4567_...")
 *           .build()
 *   )
 *       // Register channels — kind determines the expected values.
 *       // Labels must match [a-z0-9_]+ (lowercase letters, digits, underscores).
 *       .channel(LudusLogger.ChannelKind.POSE, "pose")
 *       .channel(LudusLogger.ChannelKind.DRIVE_POWER, "front_left")
 *       .channel(LudusLogger.ChannelKind.DRIVE_POWER, "front_right")
 *       .channel(LudusLogger.ChannelKind.BATTERY, "battery")
 *
 *       // 50 Hz callback — runs on a background thread
 *       .onTick(snapshot -> {
 *           // Write channel values every tick (motors, pose, sensors)
 *           snapshot.channel("pose").write(robot.x, robot.y, robot.heading);
 *           snapshot.channel("front_left").write(fl.getPower());
 *           snapshot.channel("front_right").write(fr.getPower());
 *           snapshot.channel("battery").write(hardwareMap.voltageSensor
 *               .iterator().next().getVoltage());
 *       })
 *       .build();
 *
 *   // In your opmode init:
 *   log.start();   // returns IMMEDIATELY — connects + registers in the background.
 *                  // The robot never stalls waiting for the laptop. Frames are
 *                  // buffered (bounded) until registration completes, then flushed
 *                  // in order. If the Collector is unreachable or rejects the
 *                  // session, the logger goes dead quietly and your opmode runs on.
 *
 *   // Optional: block until the Collector accepts the session (e.g. during init):
 *   if (!log.awaitConnection(2000)) {
 *       telemetry.addLine("Ludus: Collector not reachable — running without logging");
 *   }
 *
 *   // Events — call from ANY thread, at any time (queued and sent on next tick).
 *   // Names must match [a-z0-9_]+. Each event carries its own timestamp.
 *   log.event("shot_fired");                                   // success (default)
 *   log.event("intake_engaged", true);                         // explicit success
 *   log.event("intake_engaged", false);                        // failure
 *   log.event("shot_fired", Map.of("target", "high"));         // with payload
 *   log.event("shot_fired", false, Map.of("reason", "missed"));// failed + payload
 *
 *   // Segments — call from ANY thread (queued and sent on next tick).
 *   // Names must match [a-z0-9_]+ — validation errors throw at segmentStart.
 *   log.segmentStart("score_preload");      // opens a segment
 *     log.segmentStart("approach");          // nested — server computes path
 *     log.segmentEnd();                      // closes innermost ("approach")
 *   log.segmentEnd();                        // closes "score_preload"
 *   log.segmentEnd(true);                    // abort — marks segment as aborted
 *   log.segmentEnd(Map.of("duration_s", 1.5)); // with metrics
 *
 *   // When the opmode ends:
 *   log.stop();                              // flushes remaining data, closes connection
 *
 *   // Diagnostics: if the Collector stalls or disconnects, frames/events are
 *   // dropped (oldest first) rather than growing the heap. Check:
 *   //   log.getDroppedFrames() / getDroppedEvents() / getDroppedSegments()
 *
 *   // Safety net: starting a new LudusLogger automatically stops any previous
 *   // instance that was never stop()ped (e.g. a crashed opmode).
 *
 * ============================================================================
 * USAGE — MANUAL MODE (no background thread)
 * ============================================================================
 * If you prefer to control when frames are sent (e.g. inside your own loop),
 * omit the onTick callback. Call channel(...).write() then flush() yourself.
 * Events and segments still work identically. start() is non-blocking here too.
 *
 *   LudusLogger log = LudusLogger.create(config)
 *       .channel(LudusLogger.ChannelKind.POSE, "pose")
 *       .channel(LudusLogger.ChannelKind.BATTERY, "battery")
 *       .build();
 *
 *   log.start();
 *   while (opModeIsActive()) {
 *       log.channel("pose").write(robot.x, robot.y, robot.heading);
 *       log.channel("battery").write(voltage);
 *       log.flush();   // encodes + queues one tick frame (sent once registered)
 *
 *       // Events and segments work the same as callback mode
 *       if (scored) log.event("scored", Map.of("basket", "high"));
 *       log.segmentStart("cycle_1");
 *       // ... later ...
 *       log.segmentEnd();
 *   }
 *   log.stop();
 *
 * ============================================================================
 */

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/**
 * Streams telemetry data to the Ludus Collector over WebSocket.
 * <p>
 * Supports two usage models:
 * <ul>
 *   <li><b>Callback mode</b> — supply an {@code onTick} callback; a 50 Hz background
 *       thread invokes it, encodes the frame, and sends it automatically.</li>
 *   <li><b>Manual mode</b> — omit {@code onTick}; write channel values and call
 *       {@link #flush()} yourself from your loop.</li>
 * </ul>
 * <p>
 * {@link #start()} is non-blocking: the WebSocket connect + registration happen in
 * the background while frames buffer (bounded) and are flushed in order once the
 * Collector accepts the session. Use {@link #awaitConnection(long)} if you explicitly
 * want to wait.
 * <p>
 * Events ({@link #event}) and segments ({@link #segmentStart}, {@link #segmentEnd})
 * are thread-safe and may be called from any thread in either mode. They are queued
 * internally (with hard bounds — see {@link #getDroppedEvents()}) and drained into
 * the next outgoing tick frame.
 * <p>
 * Library-internal errors never propagate into user code; they are reported through
 * the {@link Listener}. The only exceptions thrown at the call site are documented
 * {@link IllegalArgumentException}s for programmer errors (bad names, wrong value
 * counts, unknown channels).
 */
public class LudusLogger {

    // =========================================================================
    // Tunables
    // =========================================================================

    /** Max encoded frames parked while the socket is unready/stalled (~5 s at 50 Hz). */
    private static final int MAX_PENDING_FRAMES = 250;
    /** Max queued events while waiting for the next frame drain. */
    private static final int MAX_QUEUED_EVENTS = 1000;
    /** Max queued segment start/end operations while waiting for the next frame drain. */
    private static final int MAX_QUEUED_SEGMENT_OPS = 500;
    /** Park frames locally once OkHttp's outbound buffer exceeds this (well below its 16 MiB kill limit). */
    private static final long MAX_SOCKET_QUEUE_BYTES = 1024L * 1024L;
    /** How long the background registration may take before the logger goes dead. */
    private static final long REGISTRATION_TIMEOUT_MS = 10_000L;

    // =========================================================================
    // Public types
    // =========================================================================

    /**
     * Channel kinds matching the server's RegisterHandler.KindFields.
     * Each kind implies a fixed number of values per write:
     * <ul>
     *   <li>POSE — x, y, heading (3 values)</li>
     *   <li>TARGET_POSE — x, y, heading (3 values)</li>
     *   <li>DRIVE_POWER — power (1 value)</li>
     *   <li>MOTOR_POWER — power (1 value)</li>
     *   <li>SERVO_POSITION — position (1 value)</li>
     *   <li>SENSOR_ANALOG — value (1 value)</li>
     *   <li>SENSOR_DIGITAL — value (1 value)</li>
     *   <li>VELOCITY — vx, vy, omega (3 values)</li>
     *   <li>BATTERY — voltage (1 value)</li>
     *   <li>CUSTOM_FLOAT — value (1 value)</li>
     *   <li>CUSTOM_VEC2 — x, y (2 values)</li>
     *   <li>CUSTOM_VEC3 — x, y, z (3 values)</li>
     * </ul>
     */
    public enum ChannelKind {
        POSE("POSE", 3),
        TARGET_POSE("TARGET_POSE", 3),
        DRIVE_POWER("DRIVE_POWER", 1),
        MOTOR_POWER("MOTOR_POWER", 1),
        SERVO_POSITION("SERVO_POSITION", 1),
        SENSOR_ANALOG("SENSOR_ANALOG", 1),
        SENSOR_DIGITAL("SENSOR_DIGITAL", 1),
        VELOCITY("VELOCITY", 3),
        BATTERY("BATTERY", 1),
        CUSTOM_FLOAT("CUSTOM_FLOAT", 1),
        CUSTOM_VEC2("CUSTOM_VEC2", 2),
        CUSTOM_VEC3("CUSTOM_VEC3", 3);

        final String wire;
        final int valueCount;

        ChannelKind(String wire, int valueCount) {
            this.wire = wire;
            this.valueCount = valueCount;
        }

        /** Returns the wire-format string sent in the register message. */
        public String wireString() {
            return wire;
        }

        /** Returns the number of values expected per {@link Channel#write(double...)}. */
        public int valueCount() {
            return valueCount;
        }
    }

    /** Immutable session configuration. Build via {@link SessionConfig.Builder}. */
    public static final class SessionConfig {
        final String opmode;
        final String alliance;
        final String robotId;
        final String collectorHost;
        final int collectorPort;
        final int teamNumber;
        final String apiKey;

        private SessionConfig(Builder b) {
            this.opmode = b.opmode;
            this.alliance = b.alliance;
            this.robotId = b.robotId;
            this.collectorHost = b.collectorHost;
            this.collectorPort = b.collectorPort;
            this.teamNumber = b.teamNumber;
            this.apiKey = b.apiKey;
        }

        public static final class Builder {
            private String opmode = "unknown";
            private String alliance = "UNKNOWN";
            private String robotId = "robot";
            private String collectorHost = "192.168.43.1";
            private int collectorPort = 8080;
            private int teamNumber = 0;
            private String apiKey = "";

            /** OpMode name (e.g. "AutoBlueRight"). */
            public Builder opmode(String opmode) { this.opmode = opmode; return this; }

            /** Alliance color: "RED" or "BLUE". */
            public Builder alliance(String alliance) { this.alliance = alliance; return this; }

            /** Unique robot identifier (e.g. "gladius-2026"). */
            public Builder robotId(String robotId) { this.robotId = robotId; return this; }

            /** IP or hostname of the laptop running Ludus Collector. Default: 192.168.43.1 */
            public Builder collectorHost(String host) { this.collectorHost = host; return this; }

            /** Collector WebSocket port. Default: 8080 */
            public Builder collectorPort(int port) { this.collectorPort = port; return this; }

            /** FTC team number. */
            public Builder teamNumber(int teamNumber) { this.teamNumber = teamNumber; return this; }

            /** Ludus API key (starts with "tk_"). */
            public Builder apiKey(String apiKey) { this.apiKey = apiKey; return this; }

            public SessionConfig build() { return new SessionConfig(this); }
        }
    }

    /**
     * A writable handle for a single channel within a tick frame.
     * Call {@link #write(double...)} to buffer values for the current frame.
     */
    public static final class Channel {
        final String label;
        final ChannelKind kind;
        double[] values;

        Channel(String label, ChannelKind kind) {
            this.label = label;
            this.kind = kind;
        }

        /**
         * Buffer values for this channel in the current tick frame.
         * The number of values must match the channel kind:
         * <ul>
         *   <li>POSE / TARGET_POSE / VELOCITY / CUSTOM_VEC3 — 3 values</li>
         *   <li>CUSTOM_VEC2 — 2 values</li>
         *   <li>All others — 1 value</li>
         * </ul>
         * The values are copied, so the caller's array may be reused.
         *
         * @throws IllegalArgumentException if the value count does not match the kind
         */
        public void write(double... vals) {
            int got = (vals == null) ? 0 : vals.length;
            if (got != kind.valueCount) {
                throw new IllegalArgumentException(
                        "Channel \"" + label + "\" (" + kind.name() + ") expects "
                        + kind.valueCount + " value(s) per write, got " + got);
            }
            this.values = Arrays.copyOf(vals, vals.length);
        }
    }

    /**
     * Snapshot passed to the onTick callback. Provides access to channels for
     * writing values during the current tick.
     */
    public static final class Snapshot {
        private final Map<String, Channel> channels;

        Snapshot(Map<String, Channel> channels) {
            this.channels = channels;
        }

        /**
         * Returns the channel with the given label for writing.
         * @throws IllegalArgumentException if the label was not registered
         */
        public Channel channel(String label) {
            Channel ch = channels.get(label);
            if (ch == null) {
                throw new IllegalArgumentException("Unknown channel: " + label);
            }
            return ch;
        }
    }

    /** Callback interface for the 50 Hz background tick. */
    public interface TickCallback {
        void onTick(Snapshot snapshot);
    }

    /** Listener for logger lifecycle events. All methods have default no-op implementations. */
    public interface Listener {
        /** Called when the WebSocket connection is established and registration succeeds. */
        default void onRegistered(String sessionId) {}

        /** Called when registration is rejected by the collector/server. */
        default void onRegistrationRejected(String reason) {}

        /** Called when the WebSocket connection is lost. */
        default void onDisconnected() {}

        /** Called when any error occurs (connection, encoding, etc.). */
        default void onError(String message, Throwable cause) {}

        /**
         * Called the first time outgoing tick frames are dropped due to backpressure
         * (Collector stalled or unreachable). The running total is available at any
         * time via {@link LudusLogger#getDroppedFrames()}.
         */
        default void onFramesDropped(long totalDropped) {}
    }

    // =========================================================================
    // Builder
    // =========================================================================

    /** Entry point — creates a builder for a new LudusLogger. */
    public static LoggerBuilder create(SessionConfig config) {
        return new LoggerBuilder(config);
    }

    public static final class LoggerBuilder {
        private final SessionConfig config;
        private final List<Channel> channels = new ArrayList<>();
        private TickCallback tickCallback;
        private Listener listener;

        LoggerBuilder(SessionConfig config) {
            this.config = config;
        }

        /**
         * Register a telemetry channel.
         * @param kind   the channel kind (determines expected value count)
         * @param label  unique label for this channel (e.g. "front_left", "pose");
         *               must match {@code [a-z0-9_]+}
         * @throws IllegalArgumentException if the label is not a valid name
         */
        public LoggerBuilder channel(ChannelKind kind, String label) {
            validateName(label, "Channel label");
            channels.add(new Channel(label, kind));
            return this;
        }

        /**
         * Set the 50 Hz tick callback. When provided, a background daemon thread
         * will invoke this callback at 50 Hz, automatically encoding and sending
         * each tick frame. If omitted, use manual mode (write + flush).
         */
        public LoggerBuilder onTick(TickCallback callback) {
            this.tickCallback = callback;
            return this;
        }

        /** Set a listener for lifecycle events (connected, rejected, errors). */
        public LoggerBuilder listener(Listener listener) {
            this.listener = listener;
            return this;
        }

        public LudusLogger build() {
            return new LudusLogger(config, channels, tickCallback, listener);
        }
    }

    // =========================================================================
    // Internal event / segment queue types
    // =========================================================================

    private static final class QueuedEvent {
        final String name;
        final int success; // 1 = success, 0 = failure
        final Map<String, Object> payload; // nullable
        final long tsNanos; // System.nanoTime() at event() call time

        QueuedEvent(String name, int success, Map<String, Object> payload, long tsNanos) {
            this.name = name;
            this.success = success;
            this.payload = payload;
            this.tsNanos = tsNanos;
        }
    }

    private static final class QueuedSegment {
        final String action; // "start" or "end"
        final String name;   // segment name (resolved from stack for "end")
        final boolean aborted;
        final Map<String, Double> metrics; // nullable

        QueuedSegment(String action, String name, boolean aborted, Map<String, Double> metrics) {
            this.action = action;
            this.name = name;
            this.aborted = aborted;
            this.metrics = metrics;
        }
    }

    // =========================================================================
    // Instance state
    // =========================================================================

    /**
     * Lifecycle: IDLE → (start) → CONNECTING → (registered) → REGISTERED
     * → (stop) → STOPPED, or → (rejection / connection failure / timeout) → DEAD.
     * STOPPED and DEAD are terminal; a logger instance is single-use.
     */
    private enum State { IDLE, CONNECTING, REGISTERED, STOPPED, DEAD }

    /** The most recently started instance — used to stop a forgotten previous logger. */
    private static final AtomicReference<LudusLogger> activeInstance = new AtomicReference<>();

    private final SessionConfig config;
    private final Map<String, Channel> channelMap = new LinkedHashMap<>();
    private final List<Channel> channelList;
    private final TickCallback tickCallback;
    private final Listener listener;

    private final ConcurrentLinkedQueue<QueuedEvent> eventQueue = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<QueuedSegment> segmentQueue = new ConcurrentLinkedQueue<>();
    // Segment name stack for segmentEnd() to resolve the innermost open segment.
    // Used strictly as a stack via addLast/pollLast — atomic, no copy/rebuild.
    private final ConcurrentLinkedDeque<String> segmentStack = new ConcurrentLinkedDeque<>();

    // Encoded frames waiting for the socket (bounded; oldest dropped on overflow).
    private final Object frameLock = new Object();
    private final ArrayDeque<byte[]> pendingFrames = new ArrayDeque<>();

    private final AtomicInteger tickCounter = new AtomicInteger(0);
    private final AtomicReference<State> state = new AtomicReference<>(State.IDLE);
    private final AtomicBoolean stopInitiated = new AtomicBoolean(false);
    private final CountDownLatch registrationLatch = new CountDownLatch(1);

    private final AtomicLong droppedFrames = new AtomicLong(0);
    private final AtomicLong droppedEvents = new AtomicLong(0);
    private final AtomicLong droppedSegments = new AtomicLong(0);

    private volatile long startTimeNanos;
    private volatile String sessionId;

    private volatile OkHttpClient httpClient;
    private volatile WebSocket webSocket;
    private volatile ScheduledExecutorService tickExecutor;
    private volatile ScheduledFuture<?> tickFuture;

    // =========================================================================
    // Constructor
    // =========================================================================

    private LudusLogger(SessionConfig config, List<Channel> channels,
                        TickCallback tickCallback, Listener listener) {
        this.config = config;
        this.channelList = new ArrayList<>(channels);
        for (Channel ch : channels) {
            channelMap.put(ch.label, ch);
        }
        this.tickCallback = tickCallback;
        this.listener = listener != null ? listener : new Listener() {};
    }

    // =========================================================================
    // Public API — lifecycle
    // =========================================================================

    /**
     * Starts the logger and returns immediately — the WebSocket connect and
     * registration happen in the background so the OpMode thread never stalls,
     * even if the Collector laptop is off.
     * <p>
     * Tick frames produced before registration completes are buffered (bounded)
     * and flushed in order once the Collector accepts the session. If registration
     * is rejected, times out, or the connection fails, the logger transitions to a
     * dead state: it stops accepting data, releases its resources, and reports once
     * via the {@link Listener}.
     * <p>
     * If a previous LudusLogger instance was never {@link #stop()}ped (e.g. a
     * crashed OpMode), it is stopped automatically and a warning is reported via
     * this instance's listener.
     * <p>
     * Use {@link #awaitConnection(long)} if you explicitly want to block until
     * registration completes. A logger instance is single-use: calling start()
     * again after stop() has no effect.
     */
    public void start() {
        if (!state.compareAndSet(State.IDLE, State.CONNECTING)) return; // already started

        // Stop any previous still-running instance (forgotten stop() in the last OpMode)
        // so its 50 Hz daemon thread doesn't survive into this session.
        LudusLogger prev = activeInstance.getAndSet(this);
        if (prev != null && prev != this) {
            State prevState = prev.state.get();
            if (prevState == State.CONNECTING || prevState == State.REGISTERED) {
                notifyError("A previous LudusLogger instance was still running — stopping it now. "
                        + "Call stop() at the end of your OpMode.", null);
                try {
                    prev.stop();
                } catch (Exception ignored) {
                    // never let a broken previous instance break this one
                }
            }
        }

        startTimeNanos = System.nanoTime();
        tickCounter.set(0);

        httpClient = new OkHttpClient.Builder()
                .readTimeout(0, TimeUnit.MILLISECONDS) // no read timeout for WS
                .build();

        // Executor is created before the socket so a fast connection failure
        // can always clean it up. It drives the 50 Hz tick (callback mode) and
        // the registration timeout (both modes).
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "LudusLogger-tick");
            t.setDaemon(true);
            return t;
        });
        tickExecutor = executor;

        if (tickCallback != null) {
            tickFuture = executor.scheduleAtFixedRate(this::tickOnce, 0, 20, TimeUnit.MILLISECONDS);
        }

        executor.schedule(() -> {
            if (state.get() == State.CONNECTING) {
                enterDeadState("Registration timed out after " + REGISTRATION_TIMEOUT_MS
                        + " ms — is the Collector running?", null);
            }
        }, REGISTRATION_TIMEOUT_MS, TimeUnit.MILLISECONDS);

        String url = "ws://" + config.collectorHost + ":" + config.collectorPort + "/ws";
        Request request = new Request.Builder().url(url).build();

        webSocket = httpClient.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket ws, Response response) {
                // Send register message as JSON text
                try {
                    ws.send(buildRegisterJson());
                } catch (Exception e) {
                    notifyError("Failed to send register message", e);
                }
            }

            @Override
            public void onMessage(WebSocket ws, String text) {
                try {
                    handleTextMessage(text);
                } catch (Exception e) {
                    notifyError("Failed to handle collector message", e);
                }
            }

            @Override
            public void onFailure(WebSocket ws, Throwable t, Response response) {
                // Reports once; no-op if already stopped or dead.
                enterDeadState("WebSocket failure", t);
            }

            @Override
            public void onClosed(WebSocket ws, int code, String reason) {
                try {
                    listener.onDisconnected();
                } catch (Exception ignored) {
                }
                enterDeadState(null, null); // quiet — expected after stop()
            }
        });
    }

    /**
     * Blocks until registration completes (or fails), up to the given timeout.
     * Entirely optional — {@link #start()} does not block.
     *
     * @param timeoutMs maximum time to wait, in milliseconds
     * @return true if the Collector accepted the session, false on timeout,
     *         rejection, or connection failure
     */
    public boolean awaitConnection(long timeoutMs) {
        try {
            registrationLatch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return state.get() == State.REGISTERED;
    }

    /**
     * Flushes any remaining queued data and closes the WebSocket connection.
     * In callback mode, also stops the 50 Hz background thread (before the final
     * flush, so the last events/segments are drained into one final frame and
     * actually sent).
     */
    public void stop() {
        State s = state.get();
        if (s != State.CONNECTING && s != State.REGISTERED) return; // never started, dead, or stopped
        if (!stopInitiated.compareAndSet(false, true)) return;      // stop already in progress

        // 1. Stop the tick thread first so no frame is built concurrently with
        //    the final flush.
        ScheduledFuture<?> tf = tickFuture;
        tickFuture = null;
        if (tf != null) tf.cancel(false);
        ScheduledExecutorService ex = tickExecutor;
        tickExecutor = null;
        if (ex != null) {
            ex.shutdown();
            try {
                ex.awaitTermination(200, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        // 2. Final flush — runs BEFORE the state flip so the frame is actually
        //    built, queued, and pushed to the socket. Drains all remaining
        //    events/segments plus any frames parked by backpressure.
        try {
            sendCurrentFrame();
        } catch (Exception e) {
            notifyError("Final flush error", e);
        }

        // 3. Flip to STOPPED — from here no new data is accepted or queued.
        while (true) {
            State cur = state.get();
            if (cur == State.STOPPED || cur == State.DEAD) break;
            if (state.compareAndSet(cur, State.STOPPED)) break;
        }
        activeInstance.compareAndSet(this, null);

        // 4. Close the transport. OkHttp delivers messages enqueued before
        //    close() ahead of the close frame, so the final frame goes out.
        WebSocket ws = webSocket;
        webSocket = null;
        if (ws != null) {
            try {
                ws.close(1000, "session ended");
            } catch (Exception ignored) {
            }
        }
        OkHttpClient client = httpClient;
        httpClient = null;
        if (client != null) {
            try {
                client.dispatcher().executorService().shutdown();
            } catch (Exception ignored) {
            }
        }

        eventQueue.clear();
        segmentQueue.clear();
        segmentStack.clear();
        synchronized (frameLock) {
            pendingFrames.clear();
        }
        registrationLatch.countDown(); // release any awaitConnection() waiters
    }

    // =========================================================================
    // Public API — channels (manual mode)
    // =========================================================================

    /**
     * Returns the channel with the given label for writing values.
     * In manual mode, call this then {@link Channel#write(double...)} followed
     * by {@link #flush()}.
     *
     * @throws IllegalArgumentException if the label was not registered
     */
    public Channel channel(String label) {
        Channel ch = channelMap.get(label);
        if (ch == null) {
            throw new IllegalArgumentException("Unknown channel: " + label);
        }
        return ch;
    }

    /**
     * Manual mode: encodes the current frame (channels + queued events/segments)
     * and queues it for sending over the WebSocket. Increments the tick counter.
     * If registration hasn't completed yet, the frame is buffered (bounded) and
     * flushed once the Collector accepts the session.
     * <p>
     * In callback mode this is called automatically — you do not need to call it.
     */
    public void flush() {
        sendCurrentFrame();
    }

    // =========================================================================
    // Public API — events
    // =========================================================================

    /**
     * Record a successful event with no payload.
     * Thread-safe — may be called from any thread. The event's timestamp is
     * captured now, not at frame-send time.
     *
     * @param name event name (e.g. "shot_fired", "intake_engaged"); must match [a-z0-9_]+
     * @throws IllegalArgumentException if the name is not a valid name
     */
    public void event(String name) {
        event(name, true, null);
    }

    /**
     * Record an event with explicit success/failure and no payload.
     * Thread-safe — may be called from any thread.
     *
     * @param name    event name; must match [a-z0-9_]+
     * @param success true for success, false for failure
     * @throws IllegalArgumentException if the name is not a valid name
     */
    public void event(String name, boolean success) {
        event(name, success, null);
    }

    /**
     * Record a successful event with a payload.
     * Thread-safe — may be called from any thread.
     *
     * @param name    event name; must match [a-z0-9_]+
     * @param payload arbitrary key-value data (values should be String, Number, or Boolean)
     * @throws IllegalArgumentException if the name is not a valid name
     */
    public void event(String name, Map<String, Object> payload) {
        event(name, true, payload);
    }

    /**
     * Record an event with explicit success/failure and a payload.
     * Thread-safe — may be called from any thread. If the logger has been stopped
     * or its connection is dead, the event is discarded (never queued unbounded).
     *
     * @param name    event name; must match [a-z0-9_]+
     * @param success true for success, false for failure
     * @param payload arbitrary key-value data
     * @throws IllegalArgumentException if the name is not a valid name
     */
    public void event(String name, boolean success, Map<String, Object> payload) {
        validateName(name, "Event name");
        if (!acceptingData()) return;
        eventQueue.add(new QueuedEvent(name, success ? 1 : 0, payload, System.nanoTime()));
        while (eventQueue.size() > MAX_QUEUED_EVENTS) {
            if (eventQueue.poll() == null) break;
            droppedEvents.incrementAndGet();
        }
    }

    // =========================================================================
    // Public API — segments
    // =========================================================================

    /**
     * Open a named segment. Segments can be nested — the server computes the
     * hierarchy from start/end ordering.
     * Thread-safe — may be called from any thread.
     *
     * @param name segment name (e.g. "score_preload", "approach"); must match
     *             [a-z0-9_]+ — lowercase, no colons (path separator), no '#'
     *             (instance suffix)
     * @throws IllegalArgumentException if the name is not a valid segment name
     */
    public void segmentStart(String name) {
        validateName(name, "Segment name");
        if (!acceptingData()) return;
        segmentStack.addLast(name);
        addSegmentOp(new QueuedSegment("start", name, false, null));
    }

    /**
     * Close the innermost open segment normally (not aborted, no metrics).
     * Thread-safe — may be called from any thread.
     */
    public void segmentEnd() {
        segmentEnd(false, null);
    }

    /**
     * Close the innermost open segment, optionally marking it as aborted.
     * Thread-safe — may be called from any thread.
     *
     * @param aborted true to mark the segment as aborted
     */
    public void segmentEnd(boolean aborted) {
        segmentEnd(aborted, null);
    }

    /**
     * Close the innermost open segment with metrics (not aborted).
     * Thread-safe — may be called from any thread.
     *
     * @param metrics key-value metrics (e.g. Map.of("duration_s", 1.5))
     */
    public void segmentEnd(Map<String, Double> metrics) {
        segmentEnd(false, metrics);
    }

    /**
     * Close the innermost open segment with full control over abort and metrics.
     * Thread-safe — may be called from any thread. If the logger has been stopped
     * or its connection is dead, the call is discarded.
     *
     * @param aborted true to mark the segment as aborted
     * @param metrics optional key-value metrics (nullable)
     */
    public void segmentEnd(boolean aborted, Map<String, Double> metrics) {
        if (!acceptingData()) return;
        // Pop the innermost segment name from the stack (atomic).
        String name = segmentStack.pollLast();
        if (name == null) name = "unknown";
        addSegmentOp(new QueuedSegment("end", name, aborted, metrics));
    }

    private void addSegmentOp(QueuedSegment op) {
        segmentQueue.add(op);
        while (segmentQueue.size() > MAX_QUEUED_SEGMENT_OPS) {
            if (segmentQueue.poll() == null) break;
            droppedSegments.incrementAndGet();
        }
    }

    // =========================================================================
    // Public API — diagnostics
    // =========================================================================

    /** Total encoded tick frames dropped due to backpressure (Collector stalled/unreachable). */
    public long getDroppedFrames() {
        return droppedFrames.get();
    }

    /** Total events dropped because the event queue overflowed its hard bound. */
    public long getDroppedEvents() {
        return droppedEvents.get();
    }

    /** Total segment start/end operations dropped because the segment queue overflowed. */
    public long getDroppedSegments() {
        return droppedSegments.get();
    }

    // =========================================================================
    // Internal — lifecycle helpers
    // =========================================================================

    /** True while user data (events, segments, frames) is still accepted. */
    private boolean acceptingData() {
        State s = state.get();
        return s == State.IDLE || s == State.CONNECTING || s == State.REGISTERED;
    }

    /**
     * Terminal failure path: rejection, connection failure, or registration
     * timeout. Runs exactly once — releases the socket, the OkHttp dispatcher,
     * and the tick thread, clears all queues, and (if a message is given)
     * reports once via the listener. No-op if already stopped or dead.
     */
    private void enterDeadState(String errorMessage, Throwable cause) {
        while (true) {
            State cur = state.get();
            if (cur == State.STOPPED || cur == State.DEAD || cur == State.IDLE) return;
            if (state.compareAndSet(cur, State.DEAD)) break;
        }

        ScheduledFuture<?> tf = tickFuture;
        tickFuture = null;
        if (tf != null) tf.cancel(false);
        ScheduledExecutorService ex = tickExecutor;
        tickExecutor = null;
        if (ex != null) {
            // No await — this may run on the executor's own thread or an OkHttp thread.
            ex.shutdown();
        }

        WebSocket ws = webSocket;
        webSocket = null;
        if (ws != null) {
            try {
                ws.cancel(); // immediate release; a late "registered" can't create a ghost session
            } catch (Exception ignored) {
            }
        }
        OkHttpClient client = httpClient;
        httpClient = null;
        if (client != null) {
            try {
                client.dispatcher().executorService().shutdown();
            } catch (Exception ignored) {
            }
        }

        eventQueue.clear();
        segmentQueue.clear();
        segmentStack.clear();
        synchronized (frameLock) {
            pendingFrames.clear();
        }

        activeInstance.compareAndSet(this, null);
        registrationLatch.countDown(); // release any awaitConnection() waiters
        if (errorMessage != null) {
            notifyError(errorMessage, cause);
        }
    }

    /** Handles JSON text messages from the collector (registration handshake). */
    private void handleTextMessage(String text) {
        String type = extractJsonString(text, "type");
        if ("registered".equals(type)) {
            // Ignore late arrivals after timeout/failure — the socket is already
            // cancelled, so no ghost session lingers on our side.
            if (state.compareAndSet(State.CONNECTING, State.REGISTERED)) {
                sessionId = extractJsonString(text, "session_id");
                registrationLatch.countDown();
                try {
                    listener.onRegistered(sessionId);
                } catch (Exception ignored) {
                }
                drainFrames(); // flush frames buffered while connecting, in order
            }
        } else if ("register_rejected".equals(type)) {
            String reason = extractJsonString(text, "reason");
            try {
                listener.onRegistrationRejected(reason != null ? reason : "unknown");
            } catch (Exception ignored) {
            }
            enterDeadState(null, null); // rejection already reported above
        } else if ("error".equals(type)) {
            String message = extractJsonString(text, "message");
            if (state.get() == State.CONNECTING) {
                // Fatal during handshake
                enterDeadState(message != null ? message : "Unknown error", null);
            } else {
                notifyError(message != null ? message : "Unknown error", null);
            }
        }
        // Other message types (e.g. quota info) are ignored.
    }

    /** Reports an error via the listener, never letting a listener exception escape. */
    private void notifyError(String message, Throwable cause) {
        try {
            listener.onError(message, cause);
        } catch (Exception ignored) {
        }
    }

    // =========================================================================
    // Internal — tick loop
    // =========================================================================

    /** Called by the scheduled executor at 50 Hz in callback mode. */
    private void tickOnce() {
        State s = state.get();
        if (s != State.CONNECTING && s != State.REGISTERED) return;
        try {
            // Clear previous channel values
            for (Channel ch : channelList) {
                ch.values = null;
            }
            // Let the user write new values
            tickCallback.onTick(new Snapshot(channelMap));
            // Encode and queue for sending
            sendCurrentFrame();
        } catch (Exception e) {
            notifyError("Tick callback error", e);
        }
    }

    // =========================================================================
    // Internal — frame encoding and sending
    // =========================================================================

    /** Relative milliseconds since session start, from the monotonic clock —
     *  immune to Driver Station wall-clock time syncs mid-session. */
    private long relativeMs(long nanos) {
        long ms = (nanos - startTimeNanos) / 1_000_000L;
        return ms < 0 ? 0 : ms;
    }

    /** Encodes the current channels + drained event/segment queues into a
     *  MessagePack binary frame and queues it for sending over the WebSocket. */
    private void sendCurrentFrame() {
        State s = state.get();
        if (s != State.CONNECTING && s != State.REGISTERED) return;

        // Drain events and segments
        List<QueuedEvent> events = new ArrayList<>();
        QueuedEvent evt;
        while ((evt = eventQueue.poll()) != null) events.add(evt);

        List<QueuedSegment> segments = new ArrayList<>();
        QueuedSegment seg;
        while ((seg = segmentQueue.poll()) != null) segments.add(seg);

        // Collect channels that have data
        Map<String, double[]> channelData = new LinkedHashMap<>();
        for (Channel ch : channelList) {
            if (ch.values != null) {
                channelData.put(ch.label, ch.values);
            }
        }

        int tick = tickCounter.getAndIncrement();
        long tsMs = relativeMs(System.nanoTime());

        // Count top-level keys: tick, ts_ms, channels always present
        int mapSize = 3;
        if (!events.isEmpty()) mapSize++;
        if (!segments.isEmpty()) mapSize++;

        try {
            MsgPacker p = new MsgPacker();
            p.packMapHeader(mapSize);

            // tick
            p.packString("tick");
            p.packInt(tick);

            // ts_ms
            p.packString("ts_ms");
            p.packLong(tsMs);

            // channels
            p.packString("channels");
            p.packMapHeader(channelData.size());
            for (Map.Entry<String, double[]> entry : channelData.entrySet()) {
                p.packString(entry.getKey());
                double[] vals = entry.getValue();
                p.packArrayHeader(vals.length);
                for (double v : vals) {
                    p.packDouble(v);
                }
            }

            // events (omitted when empty to save bandwidth)
            if (!events.isEmpty()) {
                p.packString("events");
                p.packArrayHeader(events.size());
                for (QueuedEvent e : events) {
                    int evtMapSize = 3; // name + success + ts_ms
                    if (e.payload != null && !e.payload.isEmpty()) evtMapSize++;
                    p.packMapHeader(evtMapSize);

                    p.packString("name");
                    p.packString(e.name);

                    p.packString("success");
                    p.packInt(e.success);

                    // Captured at event() call time; same clock/origin as the
                    // frame-level ts_ms (relative ms since session start).
                    p.packString("ts_ms");
                    p.packLong(relativeMs(e.tsNanos));

                    if (e.payload != null && !e.payload.isEmpty()) {
                        p.packString("payload");
                        p.packMap(e.payload);
                    }
                }
            }

            // segments (omitted when empty to save bandwidth)
            if (!segments.isEmpty()) {
                p.packString("segments");
                p.packArrayHeader(segments.size());
                for (QueuedSegment sg : segments) {
                    int segMapSize = 3; // action + name + aborted
                    if (sg.metrics != null && !sg.metrics.isEmpty()) segMapSize++;
                    p.packMapHeader(segMapSize);

                    p.packString("action");
                    p.packString(sg.action);

                    p.packString("name");
                    p.packString(sg.name);

                    p.packString("aborted");
                    p.packBool(sg.aborted);

                    if (sg.metrics != null && !sg.metrics.isEmpty()) {
                        p.packString("metrics");
                        p.packMapHeader(sg.metrics.size());
                        for (Map.Entry<String, Double> m : sg.metrics.entrySet()) {
                            p.packString(m.getKey());
                            p.packDouble(m.getValue());
                        }
                    }
                }
            }

            enqueueFrame(p.toByteArray());
        } catch (Exception e) {
            notifyError("Frame encoding error", e);
        }
    }

    /**
     * Parks an encoded frame in the bounded pending queue (dropping the OLDEST
     * frame on overflow) and attempts to drain the queue to the socket.
     */
    private void enqueueFrame(byte[] frame) {
        long droppedTotal = 0;
        synchronized (frameLock) {
            pendingFrames.addLast(frame);
            while (pendingFrames.size() > MAX_PENDING_FRAMES) {
                pendingFrames.pollFirst();
                droppedTotal = droppedFrames.incrementAndGet();
            }
        }
        if (droppedTotal == 1) {
            try {
                listener.onFramesDropped(droppedTotal);
            } catch (Exception ignored) {
            }
        }
        drainFrames();
    }

    /**
     * Sends pending frames in order while the connection is registered and
     * OkHttp's outbound buffer has room. Frames stay parked (bounded) whenever
     * the socket isn't ready or the collector stalls — this prevents OkHttp's
     * 16 MiB buffer limit from killing the connection.
     */
    private void drainFrames() {
        if (state.get() != State.REGISTERED) return;
        WebSocket ws = webSocket;
        if (ws == null) return;
        synchronized (frameLock) {
            while (!pendingFrames.isEmpty()) {
                if (ws.queueSize() > MAX_SOCKET_QUEUE_BYTES) break; // park until the socket catches up
                byte[] frame = pendingFrames.peekFirst();
                boolean sent;
                try {
                    sent = ws.send(ByteString.of(frame));
                } catch (Exception e) {
                    sent = false;
                }
                if (!sent) break; // socket closing/failed — keep frames parked (bounded)
                pendingFrames.pollFirst();
            }
        }
    }

    // =========================================================================
    // Internal — register message
    // =========================================================================

    /** Builds the JSON register message sent as the first WebSocket text frame. */
    private String buildRegisterJson() {
        StringBuilder sb = new StringBuilder(512);
        sb.append('{');
        jsonKV(sb, "type", "register"); sb.append(',');
        jsonKV(sb, "team_number", config.teamNumber); sb.append(',');
        jsonKV(sb, "api_key", config.apiKey); sb.append(',');
        jsonKV(sb, "robot_id", config.robotId); sb.append(',');
        jsonKV(sb, "role", "producer"); sb.append(',');

        // session object
        sb.append("\"session\":{");
        jsonKV(sb, "opmode", config.opmode); sb.append(',');
        jsonKV(sb, "alliance", config.alliance); sb.append(',');
        sb.append("\"started_at\":null");
        sb.append("},");

        // channels array
        sb.append("\"channels\":[");
        for (int i = 0; i < channelList.size(); i++) {
            if (i > 0) sb.append(',');
            Channel ch = channelList.get(i);
            sb.append("{\"label\":\"").append(escapeJson(ch.label))
              .append("\",\"kind\":\"").append(ch.kind.wire).append("\"}");
        }
        sb.append(']');

        sb.append('}');
        return sb.toString();
    }

    // =========================================================================
    // Internal — helpers
    // =========================================================================

    /**
     * Validates a segment/event/channel name against the naming rules:
     * {@code [a-z0-9_]+} — lowercase letters, digits, underscores; no colons
     * (path separator), no '#' (instance suffix), no spaces or uppercase.
     *
     * @throws IllegalArgumentException with a helpful message on violation
     */
    static void validateName(String name, String what) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException(what + " must not be null or empty");
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_';
            if (!ok) {
                throw new IllegalArgumentException(
                        what + " \"" + name + "\" contains invalid character '" + c
                        + "' — names must match [a-z0-9_]+ (lowercase letters, digits, "
                        + "underscores; no spaces, uppercase, colons, or '#')");
            }
        }
    }

    /**
     * Simple JSON string extraction — finds {@code "key": "value"} in a JSON
     * string, tolerating whitespace around the colon and handling basic escape
     * sequences in the value. Returns null if the key is absent or its value is
     * not a string. Dependency-free by design.
     */
    private static String extractJsonString(String json, String key) {
        String needle = "\"" + key + "\"";
        int idx = json.indexOf(needle);
        while (idx >= 0) {
            int i = idx + needle.length();
            while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
            if (i < json.length() && json.charAt(i) == ':') {
                i++;
                while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
                if (i < json.length() && json.charAt(i) == '"') {
                    StringBuilder sb = new StringBuilder();
                    for (int j = i + 1; j < json.length(); j++) {
                        char c = json.charAt(j);
                        if (c == '\\' && j + 1 < json.length()) {
                            char esc = json.charAt(++j);
                            switch (esc) {
                                case 'n': sb.append('\n'); break;
                                case 'r': sb.append('\r'); break;
                                case 't': sb.append('\t'); break;
                                default:  sb.append(esc); // covers \" \\ \/
                            }
                        } else if (c == '"') {
                            return sb.toString();
                        } else {
                            sb.append(c);
                        }
                    }
                    return null; // unterminated string
                }
                // Key found but value isn't a string — fall through to next occurrence.
            }
            idx = json.indexOf(needle, idx + needle.length());
        }
        return null;
    }

    private static void jsonKV(StringBuilder sb, String key, String value) {
        sb.append('"').append(key).append("\":\"").append(escapeJson(value)).append('"');
    }

    private static void jsonKV(StringBuilder sb, String key, int value) {
        sb.append('"').append(key).append("\":").append(value);
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n");  break;
                case '\r': sb.append("\\r");  break;
                case '\t': sb.append("\\t");  break;
                default:   sb.append(c);
            }
        }
        return sb.toString();
    }

    // =========================================================================
    // Inline MessagePack encoder
    // =========================================================================

    /**
     * Minimal MessagePack encoder implementing only the subset needed for
     * Ludus tick frames. This avoids requiring an external msgpack library.
     * <p>
     * Supported types: fixmap/map16, fixstr/str8/str16, fixint/int8/int16/int32/int64,
     * float64, fixarray/array16, nil, bool.
     *
     * @see <a href="https://github.com/msgpack/msgpack/blob/master/spec.md">MessagePack spec</a>
     */
    static final class MsgPacker {
        private final ByteArrayOutputStream buf = new ByteArrayOutputStream(1024);

        byte[] toByteArray() {
            return buf.toByteArray();
        }

        // -- Map headers --

        void packMapHeader(int n) {
            if (n <= 15) {
                // fixmap: 1000xxxx
                buf.write(0x80 | n);
            } else if (n <= 0xFFFF) {
                // map 16: 0xde + 2 bytes
                buf.write(0xDE);
                buf.write((n >> 8) & 0xFF);
                buf.write(n & 0xFF);
            } else {
                // map 32: 0xdf + 4 bytes
                buf.write(0xDF);
                writeInt32(n);
            }
        }

        // -- Array headers --

        void packArrayHeader(int n) {
            if (n <= 15) {
                // fixarray: 1001xxxx
                buf.write(0x90 | n);
            } else if (n <= 0xFFFF) {
                // array 16: 0xdc + 2 bytes
                buf.write(0xDC);
                buf.write((n >> 8) & 0xFF);
                buf.write(n & 0xFF);
            } else {
                // array 32: 0xdd + 4 bytes
                buf.write(0xDD);
                writeInt32(n);
            }
        }

        // -- Strings --

        void packString(String s) {
            byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
            int len = bytes.length;
            if (len <= 31) {
                // fixstr: 101xxxxx
                buf.write(0xA0 | len);
            } else if (len <= 0xFF) {
                // str 8: 0xd9 + 1 byte length
                buf.write(0xD9);
                buf.write(len);
            } else if (len <= 0xFFFF) {
                // str 16: 0xda + 2 byte length
                buf.write(0xDA);
                buf.write((len >> 8) & 0xFF);
                buf.write(len & 0xFF);
            } else {
                // str 32: 0xdb + 4 byte length
                buf.write(0xDB);
                writeInt32(len);
            }
            buf.write(bytes, 0, len);
        }

        // -- Integers --

        void packInt(int n) {
            if (n >= 0) {
                if (n <= 127) {
                    // positive fixint: 0xxxxxxx
                    buf.write(n);
                } else if (n <= 0xFF) {
                    // uint 8
                    buf.write(0xCC);
                    buf.write(n);
                } else if (n <= 0xFFFF) {
                    // uint 16
                    buf.write(0xCD);
                    buf.write((n >> 8) & 0xFF);
                    buf.write(n & 0xFF);
                } else {
                    // uint 32
                    buf.write(0xCE);
                    writeInt32(n);
                }
            } else {
                if (n >= -32) {
                    // negative fixint: 111xxxxx
                    buf.write(n & 0xFF);
                } else if (n >= -128) {
                    // int 8
                    buf.write(0xD0);
                    buf.write(n & 0xFF);
                } else if (n >= -32768) {
                    // int 16
                    buf.write(0xD1);
                    buf.write((n >> 8) & 0xFF);
                    buf.write(n & 0xFF);
                } else {
                    // int 32
                    buf.write(0xD2);
                    writeInt32(n);
                }
            }
        }

        void packLong(long n) {
            if (n >= Integer.MIN_VALUE && n <= Integer.MAX_VALUE) {
                packInt((int) n);
            } else if (n >= 0) {
                // uint 64
                buf.write(0xCF);
                writeInt64(n);
            } else {
                // int 64
                buf.write(0xD3);
                writeInt64(n);
            }
        }

        // -- Float64 --

        void packDouble(double d) {
            // float 64: 0xcb + 8 bytes (IEEE 754)
            buf.write(0xCB);
            long bits = Double.doubleToLongBits(d);
            writeInt64(bits);
        }

        // -- Bool --

        void packBool(boolean b) {
            buf.write(b ? 0xC3 : 0xC2);
        }

        // -- Nil --

        void packNil() {
            buf.write(0xC0);
        }

        // -- Recursive map (for event payloads) --

        void packMap(Map<String, Object> map) {
            packMapHeader(map.size());
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                packString(entry.getKey());
                packValue(entry.getValue());
            }
        }

        /** Pack an arbitrary value (String, Number, Boolean, or null). */
        private void packValue(Object v) {
            if (v == null) {
                packNil();
            } else if (v instanceof String) {
                packString((String) v);
            } else if (v instanceof Integer) {
                packInt((Integer) v);
            } else if (v instanceof Long) {
                packLong((Long) v);
            } else if (v instanceof Double) {
                packDouble((Double) v);
            } else if (v instanceof Float) {
                packDouble(((Float) v).doubleValue());
            } else if (v instanceof Boolean) {
                packBool((Boolean) v);
            } else if (v instanceof Number) {
                packDouble(((Number) v).doubleValue());
            } else {
                // Fallback: convert to string
                packString(v.toString());
            }
        }

        // -- Raw byte writers --

        private void writeInt32(int n) {
            buf.write((n >> 24) & 0xFF);
            buf.write((n >> 16) & 0xFF);
            buf.write((n >> 8) & 0xFF);
            buf.write(n & 0xFF);
        }

        private void writeInt64(long n) {
            buf.write((int) ((n >> 56) & 0xFF));
            buf.write((int) ((n >> 48) & 0xFF));
            buf.write((int) ((n >> 40) & 0xFF));
            buf.write((int) ((n >> 32) & 0xFF));
            buf.write((int) ((n >> 24) & 0xFF));
            buf.write((int) ((n >> 16) & 0xFF));
            buf.write((int) ((n >> 8) & 0xFF));
            buf.write((int) (n & 0xFF));
        }
    }
}
