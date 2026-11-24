package io.ludus;

import java.io.File;
import java.io.FileInputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ============================================================================
 * LUDUS LINK — find the practice laptop, and open a session when the run starts
 * ============================================================================
 *
 * {@link LudusLogger} knows how to stream a run. This class is the small piece
 * around it that an FTC opmode needs:
 *
 *   Config      reads /sdcard/FIRST/ludus.properties from the Control Hub
 *   Discovery   finds the laptop running Ludus Collector on the robot WiFi
 *   InitGate    the "press Triangle during INIT" switch, and the START handoff
 *
 * PRACTICE ONLY. FTC rules do not allow this kind of link in an official match.
 * Ludus is OFF at every INIT and stays off unless a driver turns it on, so a
 * robot carrying this code behaves exactly like one that does not until
 * somebody asks for a recording.
 *
 * HOW A RUN HAPPENS
 *   1. INIT. Your opmode calls gate.update(gamepad1.triangle) each init loop.
 *      First press: the robot starts broadcasting "who has a Collector?" on
 *      UDP 8091. The laptop answers; gate.isLinked() turns true and
 *      takeJustLinked() fires once so you can flash a light.
 *   2. START. Your opmode calls gate.open(...) with the channels it wants.
 *      Linked during INIT -> you get a started LudusLogger. Not linked -> null,
 *      and the run is simply not recorded. Nothing is ever attempted mid-match.
 *   3. LOOP. Write channels and call log.flush() once per control loop.
 *   4. STOP. log.stop() drains the last events and segments and closes.
 *
 * NOTHING HERE IS TEAM-SPECIFIC. The only setting a team must provide is its
 * team number, in ludus.properties. See docs/ROBOT.md.
 */
public final class LudusLink {

    private LudusLink() {}

    // ------------------------------------------------------------------
    // Config — where the Collector is, and who we are
    // ------------------------------------------------------------------

    /**
     * Settings read from the Control Hub. Everything has a working default
     * except the team number, which must be set in the properties file.
     */
    public static final class Config {

        /** The settings file on the Control Hub. Put it there with adb or the file manager. */
        public static final String PROPERTIES_PATH = "/sdcard/FIRST/ludus.properties";

        /** false blocks the INIT button entirely, whatever a driver presses. */
        public boolean enabled = true;
        /** "auto" (or empty) searches the robot WiFi; an address asks only that laptop. */
        public String collectorHost = "auto";
        /** The Collector's data port. */
        public int collectorPort = 8080;
        /** Your FTC team number. Required: the Collector only answers its own team. */
        public int teamNumber = 0;
        /** Only checked by the Ludus server on upload. The Collector itself ignores it. */
        public String apiKey = "local";
        /** Shown in the Collector so you can tell two robots apart. */
        public String robotId = "robot";
        /** Where these settings came from, for the Driver Hub line. */
        public String loadedFrom = "built-in defaults";

        /**
         * Load {@link #PROPERTIES_PATH}. A missing file leaves the defaults in
         * place (and so no team number, which {@link #problem()} reports). A
         * file that cannot be parsed turns Ludus off rather than guessing.
         */
        public static Config load() {
            Config c = new Config();
            File f = new File(PROPERTIES_PATH);
            if (!f.exists()) return c;
            try (FileInputStream in = new FileInputStream(f)) {
                Properties p = new Properties();
                p.load(in);
                c.enabled = Boolean.parseBoolean(p.getProperty("ludus.enabled", "" + c.enabled).trim());
                c.collectorHost = p.getProperty("ludus.host", c.collectorHost).trim();
                c.collectorPort = Integer.parseInt(p.getProperty("ludus.port", "" + c.collectorPort).trim());
                c.teamNumber = Integer.parseInt(p.getProperty("ludus.team", "" + c.teamNumber).trim());
                c.apiKey = p.getProperty("ludus.api_key", c.apiKey).trim();
                c.robotId = p.getProperty("ludus.robot_id", c.robotId).trim();
                c.loadedFrom = PROPERTIES_PATH;
            } catch (Exception e) {
                c.enabled = false;
                c.loadedFrom = "bad " + PROPERTIES_PATH + ": " + e.getMessage();
            }
            return c;
        }

        /** True when the robot has enough to search for a laptop. */
        public boolean isUsable() {
            return problem() == null;
        }

        /** Why Ludus cannot be turned on, in words for the Driver Hub, or null when it can. */
        public String problem() {
            if (loadedFrom.startsWith("bad ")) return loadedFrom;
            if (!enabled) return "ludus.enabled=false";
            if (teamNumber <= 0) return "no ludus.team in " + PROPERTIES_PATH;
            return null;
        }

        /** A fixed laptop address, or null for "auto" (search the robot WiFi). */
        public String fixedHost() {
            String h = collectorHost == null ? "" : collectorHost.trim();
            return h.isEmpty() || h.equalsIgnoreCase("auto") ? null : h;
        }
    }

    // ------------------------------------------------------------------
    // Discovery — "who out there is running the Collector?"
    // ------------------------------------------------------------------

    /**
     * Broadcasts on the robot WiFi until a Collector for this team answers,
     * then keeps telling it the robot is still here. Runs on its own daemon
     * thread; nothing here touches hardware or the opmode thread.
     *
     * Wire format (UDP 8091, one JSON object per packet):
     *   robot  -> broadcast  {"ludus":"find","v":1,"team":26089,"robot":"...","opmode":"..."}
     *   laptop -> robot      {"ludus":"here","v":1,"team":26089,"port":8080}
     *   robot  -> laptop     {"ludus":"linked",...}  every second while linked
     *   robot  -> laptop     {"ludus":"bye",...}     turned off, or stopped in INIT
     */
    public static final class Discovery {

        /** The Collector's discovery port. */
        public static final int DEFAULT_PORT = 8091;
        static final long ASK_EVERY_MS = 1000;

        private final Config cfg;
        private final String opmode;
        private final int port;
        private volatile boolean running = true;
        private volatile String host = null;          // the laptop, once it answered
        private volatile int wsPort;
        private volatile boolean justLinked = false;
        private volatile String status = "searching for the laptop...";

        private Discovery(Config cfg, String opmode, int port) {
            this.cfg = cfg;
            this.opmode = opmode;
            this.port = port;
            this.wsPort = cfg.collectorPort;
        }

        /** Start searching. @return null when the config cannot be used (Config.problem() says why). */
        public static Discovery begin(Config cfg, String opmodeName) {
            return begin(cfg, opmodeName, DEFAULT_PORT);
        }

        static Discovery begin(Config cfg, String opmodeName, int port) {
            if (cfg == null || !cfg.isUsable()) return null;
            Discovery d = new Discovery(cfg, opmodeName, port);
            Thread th = new Thread(d::run, "ludus-discovery");
            th.setDaemon(true);
            th.start();
            return d;
        }

        public boolean isLinked() { return host != null; }
        public String host() { return host; }
        public int wsPort() { return wsPort; }
        public String status() { return status; }

        /** True once, right after the laptop answered — for a light flash or a beep. */
        public boolean takeJustLinked() {
            if (!justLinked) return false;
            justLinked = false;
            return true;
        }

        /**
         * Stop searching, or stop the "still here" heartbeat.
         *
         * @param sayBye tell the laptop the robot is not coming (turned off, or stopped in INIT)
         */
        public void stop(boolean sayBye) {
            running = false;
            String h = host;
            if (sayBye && h != null) {
                try (DatagramSocket s = new DatagramSocket()) {
                    send(s, message("bye"), InetAddress.getByName(h));
                } catch (Exception ignored) { }
            }
        }

        String message(String type) {
            return String.format(Locale.US,
                    "{\"ludus\":\"%s\",\"v\":1,\"team\":%d,\"robot\":\"%s\",\"opmode\":\"%s\"}",
                    type, cfg.teamNumber, jsonSafe(cfg.robotId), jsonSafe(opmode));
        }

        private void run() {
            try (DatagramSocket s = new DatagramSocket()) {
                s.setBroadcast(true);
                s.setSoTimeout(250);
                byte[] buf = new byte[512];
                long nextSend = 0;
                while (running) {
                    long now = System.currentTimeMillis();
                    if (now >= nextSend) {
                        if (host == null) sendFind(s);
                        else send(s, message("linked"), InetAddress.getByName(host));
                        nextSend = now + ASK_EVERY_MS;
                    }
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    try {
                        s.receive(p);
                    } catch (SocketTimeoutException e) {
                        continue;
                    }
                    if (host != null || !running) continue;
                    String msg = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8);
                    if (!"here".equals(jsonString(msg, "ludus")) || jsonInt(msg, "team", -1) != cfg.teamNumber) continue;
                    wsPort = jsonInt(msg, "port", cfg.collectorPort);
                    host = p.getAddress().getHostAddress();
                    send(s, message("linked"), p.getAddress());
                    nextSend = System.currentTimeMillis() + ASK_EVERY_MS;
                    status = "LINKED to " + host;
                    justLinked = true;
                }
            } catch (Exception e) {
                if (host == null) status = "search failed: " + e.getMessage();
            }
        }

        /** Broadcast on every WiFi/LAN interface (192.168.43.255 on the Control Hub), or ask a fixed host. */
        private void sendFind(DatagramSocket s) throws Exception {
            String fixed = cfg.fixedHost();
            if (fixed != null) {
                send(s, message("find"), InetAddress.getByName(fixed));
                return;
            }
            List<InetAddress> targets = new ArrayList<>();
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    if (ia.getBroadcast() != null && !targets.contains(ia.getBroadcast())) targets.add(ia.getBroadcast());
                }
            }
            if (targets.isEmpty()) targets.add(InetAddress.getByName("255.255.255.255"));
            for (InetAddress a : targets) {
                try {
                    send(s, message("find"), a);
                } catch (Exception ignored) { }   // one bad interface must not stop the others
            }
        }

        private void send(DatagramSocket s, String msg, InetAddress to) throws Exception {
            byte[] b = msg.getBytes(StandardCharsets.UTF_8);
            s.send(new DatagramPacket(b, b.length, to, port));
        }

        static String jsonSafe(String v) {
            return v == null ? "" : v.replace("\\", "").replace("\"", "");
        }

        static String jsonString(String json, String key) {
            Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
            return m.find() ? m.group(1) : null;
        }

        static int jsonInt(String json, String key, int dflt) {
            Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*(-?\\d+)").matcher(json);
            return m.find() ? Integer.parseInt(m.group(1)) : dflt;
        }
    }

    // ------------------------------------------------------------------
    // Channels — what your opmode wants recorded
    // ------------------------------------------------------------------

    /**
     * Your chance to name the channels for a run. Called once, at START, only
     * when a laptop is actually listening.
     *
     * <pre>
     * log = gate.open(b -&gt; b
     *         .channel(ChannelKind.POSE, "pose")
     *         .channel(ChannelKind.BATTERY, "battery"));
     * </pre>
     */
    public interface Channels {
        void declare(LudusLogger.LoggerBuilder builder);
    }

    // ------------------------------------------------------------------
    // InitGate — the INIT button that turns Ludus on for one run
    // ------------------------------------------------------------------

    /**
     * Ludus is OFF at every INIT. A driver turns it on with a button during
     * INIT; the robot then searches for the laptop. A second press turns it off
     * again. Only a link made during INIT counts: not linked at START means no
     * telemetry for that run, and nothing is attempted once the match is
     * running.
     *
     * One instance per opmode run. Every method is called from the opmode
     * thread.
     */
    public static final class InitGate {

        private final String opmode;
        private Config cfg = null;
        private Discovery discovery = null;
        private boolean lastButton = false;
        private String offStatus = "OFF  (press the Ludus button to turn on)";

        /** @param opmodeName shown in the Collector while the robot searches. */
        public InitGate(String opmodeName) {
            this.opmode = opmodeName == null ? "unknown" : opmodeName;
        }

        /**
         * Call once per init loop with the button's current state. The press
         * edge is detected here, so pass the raw gamepad value.
         */
        public void update(boolean buttonDown) {
            if (buttonDown && !lastButton) toggle();
            lastButton = buttonDown;
        }

        /**
         * True once, right after the laptop answered. Flash a light or play a
         * tone so the drivers know the run will be recorded.
         */
        public boolean takeJustLinked() {
            return discovery != null && discovery.takeJustLinked();
        }

        private void toggle() {
            if (discovery == null) {
                cfg = Config.load();
                discovery = Discovery.begin(cfg, opmode);
                if (discovery == null) offStatus = "OFF - cannot turn on: " + cfg.problem();
            } else {
                discovery.stop(true);
                discovery = null;
                offStatus = "OFF  (press the Ludus button to turn on)";
            }
        }

        public boolean isOn() { return discovery != null; }
        public boolean isLinked() { return discovery != null && discovery.isLinked(); }

        /** One line for the Driver Hub: telemetry.addData("Ludus", gate.status()). */
        public String status() {
            if (discovery == null) return offStatus;
            if (discovery.isLinked()) {
                return "LINKED to " + discovery.host() + " - records from START (press again for off)";
            }
            return discovery.status() + "  (press again to cancel)";
        }

        /**
         * At START: open the session when a laptop answered during INIT.
         *
         * @param channels what to record — called only when a session is really opening
         * @return a started {@link LudusLogger}, or null when this run is not
         *         being recorded. Null is normal: guard your logging with it.
         */
        public LudusLogger open(Channels channels) {
            return open(channels, "UNKNOWN");
        }

        /** As {@link #open(Channels)}, with the alliance recorded alongside the run. */
        public LudusLogger open(Channels channels, String alliance) {
            if (discovery == null) return null;
            Discovery d = discovery;
            discovery = null;
            d.stop(false);
            if (!d.isLinked()) return null;   // INIT-only rule: no laptop, no recording
            if (cfg == null || !cfg.isUsable() || channels == null) return null;

            LudusLogger.SessionConfig sc = new LudusLogger.SessionConfig.Builder()
                    .opmode(opmode)
                    .alliance(alliance == null ? "UNKNOWN" : alliance)
                    .robotId(cfg.robotId)
                    .collectorHost(d.host())
                    .collectorPort(d.wsPort())
                    .teamNumber(cfg.teamNumber)
                    .apiKey(cfg.apiKey)
                    .build();

            LudusLogger.LoggerBuilder b = LudusLogger.create(sc);
            channels.declare(b);
            LudusLogger log = b.build();
            log.start();   // non-blocking: buffers until the Collector answers
            return log;
        }

        /** The opmode was stopped during INIT: let the laptop know the robot is not coming. */
        public void cancel() {
            if (discovery != null) discovery.stop(true);
            discovery = null;
        }
    }
}
