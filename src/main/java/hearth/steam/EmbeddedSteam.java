package hearth.steam;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Only Steam's encrypted reliable messages and a bounded localhost TCP bridge. */
public final class EmbeddedSteam {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ScheduledExecutorService API = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "hearth-steam"); thread.setDaemon(true); return thread;
    });
    private static final ArrayBlockingQueue<Packet> OUTBOUND = new ArrayBlockingQueue<>(256);
    private static final Map<Key, Stream> STREAMS = new ConcurrentHashMap<>();
    private static final Map<Long, Long> PENDING = new HashMap<>(); // Only touched on the SDK thread.
    private static final int CHANNEL = 480;
    private static SteamNative nativeApi;
    private static SteamNetworkingMessagesTransport transport;
    private static Packet sending;
    private static long sendStarted;
    private static int hostPort;
    private static byte[] hostToken;
    private static volatile String address;

    private record Key(long peer, int id) {}
    private record Packet(Key key, byte[] bytes) {}

    static {
        API.scheduleWithFixedDelay(EmbeddedSteam::tick, 10, 10, TimeUnit.MILLISECONDS);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { API.submit(EmbeddedSteam::shutdown).get(5, TimeUnit.SECONDS); }
            catch (Exception ignored) {}
            API.shutdownNow();
        }, "hearth-steam-stop"));
    }

    public static String hostAddress() { return address; }

    /** Called off the Minecraft thread, after its LAN listener is open. */
    public static String host(Path gameDir, int port) throws Exception {
        return API.submit(() -> {
            initialize(gameDir);
            hostPort = port;
            hostToken = new byte[16]; RANDOM.nextBytes(hostToken);
            address = "s-" + Long.toUnsignedString(nativeApi.steamId, 36) + "-" + new BigInteger(1, hostToken).toString(36) + ".steam";
            return address;
        }).get(45, TimeUnit.SECONDS);
    }

    public static void stopHost() {
        try { API.execute(() -> {
            address = null; hostPort = 0;
            if (hostToken != null) Arrays.fill(hostToken, (byte) 0);
            hostToken = null;
            for (var stream : STREAMS.values()) if (stream.hostSide) stream.close(true);
        }); } catch (RejectedExecutionException ignored) { } // JVM shutdown must still save the world.
    }

    /** Returns a one-use loopback endpoint which Minecraft connects to normally. */
    public static String connect(Path gameDir, String target) throws Exception {
        var parts = java.util.regex.Pattern.compile("^s-([0-9a-z]{1,13})-([0-9a-z]{1,25})\\.steam$").matcher(target);
        if (!parts.matches()) throw new IOException("Invalid Steam world address");
        long peer = Long.parseUnsignedLong(parts.group(1), 36);
        var number = new BigInteger(parts.group(2), 36);
        if (peer == 0 || number.bitLength() > 128) throw new IOException("Invalid Steam world address");
        byte[] raw = number.toByteArray(), token = new byte[16];
        int count = Math.min(raw.length, 16); System.arraycopy(raw, raw.length - count, token, 16 - count, count);
        API.submit(() -> { initialize(gameDir); return null; }).get(45, TimeUnit.SECONDS);
        InetAddress loopback = InetAddress.getByAddress(new byte[]{127, 0, 0, 1});
        ServerSocket listener = new ServerSocket();
        try { listener.bind(new InetSocketAddress(loopback, 0), 1); listener.setSoTimeout(30_000); }
        catch (IOException e) { listener.close(); throw e; }
        Thread.ofVirtual().name("hearth-steam-accept").start(() -> {
            try (listener) {
                Socket socket = listener.accept();
                int id; do { id = RANDOM.nextInt(); } while (id == 0 || STREAMS.containsKey(new Key(peer, id)));
                Stream stream;
                try { stream = new Stream(new Key(peer, id), socket, false); }
                catch (IOException e) { socket.close(); throw e; }
                if (STREAMS.size() >= 16) { socket.close(); return; }
                STREAMS.put(stream.key, stream);
                try { send(stream.key, SteamFrame.OPEN, token); stream.start(); }
                catch (IOException e) { stream.close(true); }
            } catch (IOException e) { org.slf4j.LoggerFactory.getLogger("hearth").debug("Steam loopback endpoint closed: {}", e.getClass().getSimpleName()); }
        });
        return "127.0.0.1:" + listener.getLocalPort();
    }

    private static void initialize(Path gameDir) throws IOException {
        if (nativeApi != null) return;
        nativeApi = new SteamNative(gameDir);
        try {
            transport = SteamNetworkingMessagesTransport.open(nativeApi.library, new SteamNetworkingMessagesTransport.SessionListener() {
                @Override public void onSessionRequest(long peer) {
                    boolean known = STREAMS.keySet().stream().anyMatch(key -> key.peer() == peer);
                    if ((hostPort == 0 && !known) || PENDING.size() >= 64) { transport.closePeer(peer); return; }
                    if (transport.accept(peer)) PENDING.put(peer, System.nanoTime());
                }
                @Override public void onSessionFailed(long peer, int reason, String detail) {
                    for (var stream : STREAMS.values()) if (stream.key.peer() == peer) stream.close(false);
                    PENDING.remove(peer);
                    transport.closePeer(peer);
                }
            });
        } catch (IOException | RuntimeException e) { shutdown(); throw e; }
    }

    private static void tick() {
        if (nativeApi == null) return;
        try {
            nativeApi.api.SteamAPI_RunCallbacks();
            if (nativeApi.api.SteamAPI_IsSteamRunning() == 0) throw new IOException("Steam disconnected");
            for (int n = 0; n < 64; n++) {
                int size = transport.availablePacketSize(CHANNEL);
                if (size == 0) break;
                if (size < SteamFrame.HEADER || size > SteamFrame.CHUNK + SteamFrame.HEADER) { transport.discardPendingMessage(); continue; }
                ByteBuffer buffer = ByteBuffer.allocate(size);
                var received = transport.receive(buffer, CHANNEL);
                buffer.flip(); var frame = SteamFrame.decode(buffer);
                if (frame != null) receive(received.remoteSteamId(), frame);
            }
            for (int n = 0; n < 64; n++) {
                if (sending == null) { sending = OUTBOUND.poll(); sendStarted = System.nanoTime(); }
                if (sending == null) break;
                ByteBuffer bytes = ByteBuffer.allocateDirect(sending.bytes().length).put(sending.bytes()); bytes.flip();
                int result = transport.sendResult(sending.key().peer(), bytes, CHANNEL);
                if (result == 1) { sending = null; continue; }
                if (result == 25 && System.nanoTime() - sendStarted < TimeUnit.SECONDS.toNanos(30)) break;
                var stream = STREAMS.get(sending.key()); if (stream != null) stream.close(false);
                sending = null;
            }
            long now = System.nanoTime();
            PENDING.entrySet().removeIf(peer -> {
                if (now - peer.getValue() < TimeUnit.SECONDS.toNanos(15)) return false;
                if (STREAMS.keySet().stream().noneMatch(key -> key.peer() == peer.getKey())) transport.closePeer(peer.getKey());
                return true;
            });
            for (var stream : STREAMS.values()) if (now - stream.lastActivity > TimeUnit.MINUTES.toNanos(2)
                    || stream.finAt != 0 && now - stream.finAt > TimeUnit.SECONDS.toNanos(3)) stream.close(true);
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger("hearth").warn("Steam relay stopped: {}", e.getClass().getSimpleName());
            shutdown();
        }
    }

    private static void receive(long peer, SteamFrame frame) throws IOException {
        Key key = new Key(peer, frame.id());
        Stream stream = STREAMS.get(key);
        if (frame.type() == SteamFrame.OPEN) {
            if (stream != null || hostPort == 0 || hostToken == null || !MessageDigest.isEqual(hostToken, frame.data())
                    || STREAMS.size() >= 16 || STREAMS.keySet().stream().filter(k -> k.peer() == peer).count() >= 2) return;
            Socket socket = new Socket();
            try { socket.connect(new InetSocketAddress("127.0.0.1", hostPort), 1000); }
            catch (IOException e) { socket.close(); send(key, SteamFrame.RESET, new byte[0]); return; }
            try { stream = new Stream(key, socket, true); }
            catch (IOException e) { socket.close(); throw e; }
            STREAMS.put(key, stream); PENDING.remove(peer);
            send(key, SteamFrame.ACK, new byte[3]); stream.ready.countDown(); stream.start();
            return;
        }
        if (stream == null) return;
        stream.lastActivity = System.nanoTime();
        switch (frame.type()) {
            case SteamFrame.ACK -> { stream.ready.countDown(); send(key, SteamFrame.READY, new byte[0]); }
            case SteamFrame.DATA -> { if (!stream.inbound.offer(frame.data())) stream.close(true); }
            case SteamFrame.FIN -> { stream.finAt = System.nanoTime(); if (!stream.inbound.offer(new byte[0])) stream.close(true); }
            case SteamFrame.RESET -> stream.close(false);
            default -> { }
        }
    }

    private static void send(Key key, byte type, byte[] data) throws IOException {
        if (!OUTBOUND.offer(new Packet(key, new SteamFrame(type, key.id(), data).encode()))) throw new IOException("Steam send queue full");
    }

    private static void shutdown() {
        address = null; hostToken = null; hostPort = 0;
        for (var stream : STREAMS.values()) stream.close(false);
        STREAMS.clear(); OUTBOUND.clear(); sending = null; PENDING.clear();
        if (transport != null) { transport.close(); transport = null; }
        if (nativeApi != null) { nativeApi.close(); nativeApi = null; }
    }

    private static final class Stream {
        final Key key; final Socket socket; final boolean hostSide;
        final CountDownLatch ready = new CountDownLatch(1);
        // ponytail: at most 16 streams, each with a 2 MiB inbound ceiling; no unbounded chunk backlog.
        final ArrayBlockingQueue<byte[]> inbound = new ArrayBlockingQueue<>(64);
        final AtomicBoolean closed = new AtomicBoolean();
        volatile long lastActivity = System.nanoTime(), finAt;
        volatile boolean sentFin, receivedFin;
        Stream(Key key, Socket socket, boolean hostSide) throws IOException { this.key = key; this.socket = socket; this.hostSide = hostSide; socket.setTcpNoDelay(true); socket.setKeepAlive(true); }
        void start() {
            Thread.ofVirtual().name("hearth-steam-read").start(() -> {
                try {
                    if (!ready.await(60, TimeUnit.SECONDS)) throw new IOException("Steam handshake timeout");
                    byte[] buffer = new byte[SteamFrame.CHUNK]; int size;
                    while (!closed.get() && (size = socket.getInputStream().read(buffer)) != -1) {
                        lastActivity = System.nanoTime();
                        Packet packet = new Packet(key, new SteamFrame(SteamFrame.DATA, key.id(), Arrays.copyOf(buffer, size)).encode());
                        if (!OUTBOUND.offer(packet, 30, TimeUnit.SECONDS)) throw new IOException("Steam send queue full");
                    }
                    if (!closed.get()) { send(key, SteamFrame.FIN, new byte[0]); sentFin = true; finAt = System.nanoTime(); if (receivedFin) close(false); }
                } catch (Exception e) { close(true); }
            });
            Thread.ofVirtual().name("hearth-steam-write").start(() -> {
                try {
                    while (!closed.get()) {
                        byte[] bytes = inbound.take();
                        if (bytes.length == 0) { socket.shutdownOutput(); receivedFin = true; if (sentFin) close(false); return; }
                        socket.getOutputStream().write(bytes);
                    }
                } catch (Exception e) { close(true); }
            });
        }
        void close(boolean reset) {
            if (!closed.compareAndSet(false, true)) return;
            STREAMS.remove(key, this); ready.countDown(); inbound.clear(); inbound.offer(new byte[0]);
            try { socket.close(); } catch (IOException ignored) {}
            if (reset) try { send(key, SteamFrame.RESET, new byte[0]); } catch (IOException ignored) {}
        }
    }
}
