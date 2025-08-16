import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.io.Closeable;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SixGSimulator.java
 * JDK 17+ single-file demo of a "6G-like" network simulator:
 * - Nodes that send/receive messages
 * - Network Slices with QoS targets
 * - Channel with latency, jitter, packet loss, bandwidth caps
 * - Token-bucket style scheduler per slice
 * - AES/GCM encryption for payloads
 * - Metrics collection (throughput, latency, drops)
 *
 * Note: This is a research-style simulator, not a real RF stack.
 */
public class SixGSimulator {

    /* ===========================
     * Domain Models
     * =========================== */
    static final class Message {
        final String from;
        final String to;
        final byte[] ciphertext; // encrypted payload
        final int plaintextBytes; // for metrics
        final Instant createdAt;
        final String sliceId;
        final byte[] iv; // GCM IV for this message

        Message(String from, String to, byte[] ciphertext, int plaintextBytes, String sliceId, byte[] iv) {
            this.from = from;
            this.to = to;
            this.ciphertext = ciphertext;
            this.plaintextBytes = plaintextBytes;
            this.createdAt = Instant.now();
            this.sliceId = sliceId;
            this.iv = iv;
        }
    }

    static final class PlainMessage {
        final String from;
        final String to;
        final String text;
        final String sliceId;

        PlainMessage(String from, String to, String text, String sliceId) {
            this.from = from;
            this.to = to;
            this.text = text;
            this.sliceId = sliceId;
        }

        byte[] toBytes() { return text.getBytes(java.nio.charset.StandardCharsets.UTF_8); }
    }

    /* ===========================
     * Crypto (AES/GCM)
     * =========================== */
    static final class Crypto {
        final SecretKey key;
        final SecureRandom rng = new SecureRandom();

        Crypto() throws Exception {
            KeyGenerator kg = KeyGenerator.getInstance("AES");
            kg.init(256);
            this.key = kg.generateKey();
        }

        byte[] newIV() {
            byte[] iv = new byte[12]; // 96-bit nonce for GCM
            rng.nextBytes(iv);
            return iv;
        }

        byte[] encrypt(byte[] plaintext, byte[] iv) throws Exception {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            GCMParameterSpec spec = new GCMParameterSpec(128, iv);
            cipher.init(Cipher.ENCRYPT_MODE, key, spec);
            return cipher.doFinal(plaintext);
        }

        byte[] decrypt(byte[] ciphertext, byte[] iv) throws Exception {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            GCMParameterSpec spec = new GCMParameterSpec(128, iv);
            cipher.init(Cipher.DECRYPT_MODE, key, spec);
            return cipher.doFinal(ciphertext);
        }
    }

    /* ===========================
     * Metrics
     * =========================== */
    static final class Metrics {
        final AtomicLong bytesSent = new AtomicLong();
        final AtomicLong bytesReceived = new AtomicLong();
        final AtomicLong packetsSent = new AtomicLong();
        final AtomicLong packetsReceived = new AtomicLong();
        final AtomicLong packetsDropped = new AtomicLong();
        final AtomicLong totalLatencyMillis = new AtomicLong();
        final AtomicLong latencySamples = new AtomicLong();

        void recordSend(int plaintextBytes) {
            packetsSent.incrementAndGet();
            bytesSent.addAndGet(plaintextBytes);
        }

        void recordReceive(Message msg, long latencyMs) {
            packetsReceived.incrementAndGet();
            bytesReceived.addAndGet(msg.plaintextBytes);
            totalLatencyMillis.addAndGet(latencyMs);
            latencySamples.incrementAndGet();
        }

        void recordDrop() {
            packetsDropped.incrementAndGet();
        }

        String summary() {
            long sent = bytesSent.get();
            long recv = bytesReceived.get();
            long psent = packetsSent.get();
            long precv = packetsReceived.get();
            long pdropped = packetsDropped.get();
            long lSamp = latencySamples.get();
            double avgLatency = lSamp == 0 ? 0 : (totalLatencyMillis.get() * 1.0 / lSamp);
            return """
                    --- Metrics ---
                    Bytes Sent:      %d
                    Bytes Received:  %d
                    Packets Sent:    %d
                    Packets Recv:    %d
                    Packets Dropped: %d
                    Avg Latency:     %.2f ms
                    """.formatted(sent, recv, psent, precv, pdropped, avgLatency);
        }
    }

    /* ===========================
     * Network Slices
     * =========================== */
    static final class Slice {
        final String id;
        final String description;
        final long targetLatencyMs;     // soft target
        final long bandwidthBps;        // per-slice cap (token bucket rate)
        final long bucketBytes;         // bucket size (burst)

        Slice(String id, String description, long targetLatencyMs, long bandwidthBps, long bucketBytes) {
            this.id = id;
            this.description = description;
            this.targetLatencyMs = targetLatencyMs;
            this.bandwidthBps = bandwidthBps;
            this.bucketBytes = bucketBytes;
        }

        @Override
        public String toString() {
            return "%s (%s) targetLatency=%dms, bw=%dbps, bucket=%dB".formatted(
                    id, description, targetLatencyMs, bandwidthBps, bucketBytes);
        }
    }

    /* ===========================
     * Channel (latency, jitter, loss, bandwidth)
     * =========================== */
    static final class Channel implements Closeable {
        final double baseLatencyMs;
        final double jitterMs;
        final double lossRate;          // 0..1
        final long capacityBps;         // link capacity shared across slices
        final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);
        final SecureRandom rng = new SecureRandom();
        final Metrics metrics;
        final Map<String, SliceBucket> perSliceBuckets = new ConcurrentHashMap<>();
        final AtomicBoolean open = new AtomicBoolean(true);

        Channel(double baseLatencyMs, double jitterMs, double lossRate, long capacityBps, Metrics metrics) {
            this.baseLatencyMs = baseLatencyMs;
            this.jitterMs = jitterMs;
            this.lossRate = lossRate;
            this.capacityBps = capacityBps;
            this.metrics = metrics;
        }

        void registerSlice(Slice slice) {
            perSliceBuckets.putIfAbsent(slice.id, new SliceBucket(slice));
        }

        void transmit(Message msg, Node receiver) {
            if (!open.get()) return;

            // Random drop?
            if (rng.nextDouble() < lossRate) {
                metrics.recordDrop();
                return;
            }

            // Token bucket check per slice
            SliceBucket bucket = perSliceBuckets.get(msg.sliceId);
            if (bucket == null) {
                metrics.recordDrop();
                return;
            }
            if (!bucket.tryConsume(msg.ciphertext.length)) {
                // not enough tokens right now -> drop (or queue; here we drop for simplicity)
                metrics.recordDrop();
                return;
            }

            // Simulate latency + jitter + link speed serialization delay
            double latency = baseLatencyMs + (rng.nextGaussian() * jitterMs);
            if (latency < 0) latency = 0;

            // Serialization delay ~ size / capacity
            double serializationMs = (msg.ciphertext.length * 8_000.0) / capacityBps; // bytes->bits, s->ms
            long delayMs = Math.max(0, (long) Math.round(latency + serializationMs));

            scheduler.schedule(() -> {
                if (!open.get()) return;
                receiver.inbox.offer(msg);
            }, delayMs, TimeUnit.MILLISECONDS);
        }

        @Override
        public void close() {
            open.set(false);
            scheduler.shutdownNow();
        }

        /* ----- Token bucket per-slice ----- */
        static final class SliceBucket {
            final Slice slice;
            final ScheduledExecutorService refillScheduler = Executors.newSingleThreadScheduledExecutor();
            final AtomicLong tokens; // bytes available

            SliceBucket(Slice slice) {
                this.slice = slice;
                this.tokens = new AtomicLong(slice.bucketBytes);
                // Refill at fixed rate
                long refillBytesPerTick = Math.max(1, slice.bandwidthBps / 8 / 20); // 20 ticks/sec
                refillScheduler.scheduleAtFixedRate(
                        () -> tokens.accumulateAndGet(refillBytesPerTick, (cur, add) ->
                                Math.min(slice.bucketBytes, cur + add)),
                        50, 50, TimeUnit.MILLISECONDS);
            }

            boolean tryConsume(int n) {
                while (true) {
                    long cur = tokens.get();
                    if (cur < n) return false;
                    if (tokens.compareAndSet(cur, cur - n)) return true;
                }
            }
        }
    }

    /* ===========================
     * Node
     * =========================== */
    static final class Node implements Runnable, Closeable {
        final String name;
        final BlockingQueue<Message> inbox = new LinkedBlockingQueue<>();
        final Map<String, Node> directory; // name -> node
        final Channel channel;
        final Crypto crypto;
        final Metrics metrics;
        final AtomicBoolean running = new AtomicBoolean(true);

        Node(String name, Map<String, Node> directory, Channel channel, Crypto crypto, Metrics metrics) {
            this.name = name;
            this.directory = directory;
            this.channel = channel;
            this.crypto = crypto;
            this.metrics = metrics;
        }

        void send(PlainMessage pm) throws Exception {
            Node dst = directory.get(pm.to);
            if (dst == null) throw new IllegalArgumentException("Unknown destination: " + pm.to);

            byte[] iv = crypto.newIV();
            byte[] ciphertext = crypto.encrypt(pm.toBytes(), iv);
            Message msg = new Message(pm.from, pm.to, ciphertext, pm.toBytes().length, pm.sliceId, iv);
            metrics.recordSend(pm.toBytes().length);
            channel.transmit(msg, dst);
        }

        @Override
        public void run() {
            try {
                while (running.get()) {
                    Message msg = inbox.poll(100, TimeUnit.MILLISECONDS);
                    if (msg == null) continue;

                    long latencyMs = Duration.between(msg.createdAt, Instant.now()).toMillis();
                    metrics.recordReceive(msg, latencyMs);

                    // Decrypt
                    byte[] plaintext = crypto.decrypt(msg.ciphertext, msg.iv);
                    String text = new String(plaintext, java.nio.charset.StandardCharsets.UTF_8);

                    System.out.printf("[%s] <- (%s) slice=%s latency=%dms : %s%n",
                            name, msg.from, msg.sliceId, latencyMs, text);
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                System.err.println(name + " receiver error: " + e.getMessage());
            }
        }

        @Override
        public void close() {
            running.set(false);
        }
    }

    /* ===========================
     * Demo / Main
     * =========================== */
    public static void main(String[] args) throws Exception {
        // --- Config ---
        // Channel: base 5ms latency, 1.5ms jitter, 0.5% loss, 50 Mbps link
        double baseLatencyMs = 5.0;
        double jitterMs = 1.5;
        double lossRate = 0.005;
        long linkCapacityBps = 50_000_000L;

        // Slices:
        Slice iotSlice = new Slice(
                "slice-iot", "IoT telemetry",
                10,            // target latency
                2_000_000L,    // 2 Mbps
                64 * 1024      // 64KB burst
        );
        Slice xrSlice = new Slice(
                "slice-xr", "XR/AR high-fidelity",
                5,
                20_000_000L,   // 20 Mbps
                512 * 1024     // 512KB burst
        );

        Metrics metrics = new Metrics();
        Crypto crypto = new Crypto();
        Channel channel = new Channel(baseLatencyMs, jitterMs, lossRate, linkCapacityBps, metrics);
        channel.registerSlice(iotSlice);
        channel.registerSlice(xrSlice);

        // Directory of nodes
        Map<String, Node> directory = new ConcurrentHashMap<>();

        Node deviceA = new Node("Device-A", directory, channel, crypto, metrics);
        Node deviceB = new Node("Device-B", directory, channel, crypto, metrics);
        Node edgeServer = new Node("Edge-Server", directory, channel, crypto, metrics);

        directory.put(deviceA.name, deviceA);
        directory.put(deviceB.name, deviceB);
        directory.put(edgeServer.name, edgeServer);

        ExecutorService exec = Executors.newFixedThreadPool(3);
        exec.submit(deviceA);
        exec.submit(deviceB);
        exec.submit(edgeServer);

        System.out.println("=== 6G Simulator Started ===");
        System.out.println("Slices:");
        System.out.println("  - " + iotSlice);
        System.out.println("  - " + xrSlice);
        System.out.println();

        // Traffic generators
        ScheduledExecutorService tg = Executors.newScheduledThreadPool(2);

        // IoT telemetry: small packets from Device-A -> Edge-Server (100 msgs/sec of ~100B)
        Runnable iotTraffic = () -> {
            try {
                String payload = generateTelemetryPayload(100);
                deviceA.send(new PlainMessage("Device-A", "Edge-Server", payload, iotSlice.id));
            } catch (Exception e) {
                // ignore burst failures for demo
            }
        };
        // schedule at ~10ms interval (100/sec)
        tg.scheduleAtFixedRate(iotTraffic, 100, 10, TimeUnit.MILLISECONDS);

        // XR stream: larger packets Device-B -> Device-A (30 msgs/sec of ~10KB)
        Runnable xrTraffic = () -> {
            try {
                String payload = generateXRPayload(10 * 1024);
                deviceB.send(new PlainMessage("Device-B", "Device-A", payload, xrSlice.id));
            } catch (Exception e) {
                // ignore burst failures for demo
            }
        };
        tg.scheduleAtFixedRate(xrTraffic, 200, 33, TimeUnit.MILLISECONDS); // ~30 fps

        // Run simulation for N seconds
        int seconds = 10;
        for (int i = 1; i <= seconds; i++) {
            Thread.sleep(1000);
            if (i % 2 == 0) {
                System.out.println("\n[Tick " + i + "s] Partial metrics:");
                System.out.println(metrics.summary());
            }
        }

        // Shutdown
        tg.shutdownNow();
        deviceA.close();
        deviceB.close();
        edgeServer.close();
        exec.shutdownNow();
        channel.close();

        System.out.println("\n=== Final Metrics ===");
        System.out.println(metrics.summary());
        System.out.println("=== 6G Simulator Finished ===");
    }

    /* ===========================
     * Helpers to generate payloads
     * =========================== */
    static String generateTelemetryPayload(int approxBytes) {
        // Minimal JSON-like telemetry
        String base = """
                {"type":"telemetry","temp":%d,"hum":%d,"ts":"%s"}
                """.trim();
        String s = base.formatted(20 + ThreadLocalRandom.current().nextInt(10),
                40 + ThreadLocalRandom.current().nextInt(20),
                Instant.now().toString());
        // pad to approx size
        return padToBytes(s, approxBytes);
    }

    static String generateXRPayload(int approxBytes) {
        // Mock compressed frame chunk
        ByteBuffer bb = ByteBuffer.allocate(64);
        bb.putLong(System.nanoTime());
        bb.putInt(ThreadLocalRandom.current().nextInt());
        bb.putInt(approxBytes);
        String head = Base64.getEncoder().encodeToString(bb.array());
        String body = "XRFRAME:" + head;
        return padToBytes(body, approxBytes);
    }

    static String padToBytes(String s, int size) {
        int need = Math.max(0, size - s.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        if (need == 0) return s;
        char[] pad = new char[need];
        Arrays.fill(pad, 'X');
        return s + new String(pad);
    }
}
