package server.gnss;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

class GnssConnectionTest {
    private static SerialCaptureService.Settings settings() {
        return new SerialCaptureService.Settings("COM4", 38400, "UBX", "", "", "", false, false, false);
    }

    private static class Port implements SerialConnection {
        volatile boolean open = true;
        public int readBytes(byte[] bytes, int count) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
            }
            return open ? 0 : -1;
        }
        public int writeBytes(byte[] bytes, int count) { return open ? count : -1; }
        public boolean isOpen() { return open; }
        public void closePort() { open = false; }
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(condition.getAsBoolean());
    }

    @Test
    void missingGnssIsDisconnectedAndNeverOpensAnotherPort() {
        try (var connection = new GnssConnection(List::of, ignored -> {
            fail("missing device must not open a port");
            return null;
        })) {
            assertThrows(IllegalArgumentException.class, () -> connection.connect(settings()));
            assertThrows(IllegalArgumentException.class, () -> connection.connect(null));
            assertEquals("DISCONNECTED", connection.status().state());
            assertEquals("UNAVAILABLE", connection.status().timeState());
        }
    }

    @Test
    void manualConnectionSurvivesCaptureAndDisconnectProtectsActiveCapture() throws Exception {
        Port port = new Port();
        try (var connection = new GnssConnection(
                () -> List.of(new SerialCaptureService.DetectedPort("COM4", "GNSS")), ignored -> port)) {
            assertEquals("DISCONNECTED", connection.status().state());
            assertThrows(IllegalStateException.class, () -> connection.acquire(settings()));
            connection.connect(settings());
            await(() -> connection.status().state().equals("CONNECTED"));
            assertThrows(IllegalStateException.class, () -> connection.connect(settings()));
            SerialConnection capture = connection.acquire(settings());
            assertTrue(connection.status().capturing());
            assertThrows(IllegalStateException.class, connection::disconnect);
            assertThrows(IllegalStateException.class, () -> connection.acquire(settings()));
            capture.closePort();
            assertFalse(connection.status().capturing());
            assertTrue(port.open);
            assertEquals("CONNECTED", connection.status().state());
            connection.disconnect();
            assertFalse(port.open);
            assertEquals("UNAVAILABLE", connection.status().timeState());
        }
    }

    @Test
    void utcValidityAndStalenessAreNotClockSynchronization() throws Exception {
        try (var connection = new GnssConnection(
                () -> List.of(new SerialCaptureService.DetectedPort("COM4", "GNSS")), ignored -> new Port())) {
            connection.connect(settings());
            await(() -> connection.status().state().equals("CONNECTED"));
            assertEquals("ACQUIRING", connection.status().timeState());
            byte[] data = new byte[20];
            ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            buffer.putInt(4, 50).putInt(8, -100).putShort(12, (short) 2026);
            data[14] = 9; data[15] = 29; data[16] = 12; data[19] = 7;
            var frame = new UbloxParser().push(UbloxParser.command(1, 0x21, data), 28).getFirst();
            connection.observe(frame, Instant.now(), System.nanoTime());
            assertEquals("VALID", connection.status().timeState());
            assertEquals(Instant.parse("2026-09-29T11:59:59.999999900Z"), connection.status().utc());
            assertEquals(50L, connection.status().accuracyNanos());
            connection.observe(frame, Instant.now(), System.nanoTime() - TimeUnit.SECONDS.toNanos(6));
            assertEquals("STALE", connection.status().timeState());
            data[19] = 3;
            frame = new UbloxParser().push(UbloxParser.command(1, 0x21, data), 28).getFirst();
            connection.observe(frame, Instant.now(), System.nanoTime());
            assertEquals("ACQUIRING", connection.status().timeState());
            assertNull(connection.status().utc());
        }
    }

    @Test
    void unidentifiedDeviceRequiresManualReconnect() throws Exception {
        Port port = new Port();
        AtomicInteger opens = new AtomicInteger();
        try (var connection = new GnssConnection(
                () -> List.of(new SerialCaptureService.DetectedPort("COM4", "GNSS")), ignored -> {
                    opens.incrementAndGet();
                    return port;
                })) {
            connection.connect(settings());
            await(() -> connection.status().state().equals("CONNECTED"));
            port.open = false;
            await(() -> connection.status().state().equals("ERROR"));
            assertEquals(1, opens.get());
            assertEquals("UNAVAILABLE", connection.status().timeState());
        }
    }

    @Test
    void identifiedDeviceReconnectsAndRestartDoesNotAutoConnect() throws Exception {
        Port first = new Port();
        AtomicInteger opens = new AtomicInteger();
        var detector = (java.util.function.Supplier<List<SerialCaptureService.DetectedPort>>) () ->
                List.of(new SerialCaptureService.DetectedPort("COM4", "GNSS", "unique-device"));
        try (var connection = new GnssConnection(detector, ignored -> opens.getAndIncrement() == 0 ? first : new Port())) {
            connection.connect(settings());
            await(() -> connection.status().state().equals("CONNECTED"));
            first.open = false;
            await(() -> opens.get() == 2 && connection.status().state().equals("CONNECTED"));
        }
        try (var restarted = new GnssConnection(detector, ignored -> { fail("restart must not open a port"); return null; })) {
            assertEquals("DISCONNECTED", restarted.status().state());
        }
    }
}
