package server.gnss;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class UbloxCaptureConfigurationTest {
    private static class Receiver implements UbloxCaptureConfiguration.Exchange {
        final int port;
        final int[][] rates = {{0, 7, 0, 0, 0, 0}, {0, 4, 0, 2, 0, 0}};
        boolean failSecond;
        int writes;
        Receiver(int port) { this.port = port; }
        public List<UbloxParser.UbxFrame> exchange(byte[] command) {
            var f = new UbloxParser().push(command, command.length).getFirst();
            assertEquals(6, f.messageClass());
            if (f.messageId() == 0) {
                byte[] payload = new byte[20]; payload[0] = (byte) port; payload[14] = 1;
                return List.of(new UbloxParser.UbxFrame(6, 0, payload));
            }
            assertEquals(1, f.messageId(), "No persistent config commands");
            int index = f.payload()[1] == 0x15 ? 0 : 1;
            if (f.payload().length == 3) {
                writes++;
                if (failSecond && index == 1 && f.payload()[2] == 1) {
                    return List.of(new UbloxParser.UbxFrame(5, 0, new byte[] {6, 1}));
                }
                rates[index][port] = Byte.toUnsignedInt(f.payload()[2]);
                return List.of(new UbloxParser.UbxFrame(5, 1, new byte[] {6, 1}));
            }
            byte[] payload = new byte[8]; payload[0] = 2; payload[1] = f.payload()[1];
            for (int i = 0; i < 6; i++) payload[i + 2] = (byte) rates[index][i];
            return List.of(new UbloxParser.UbxFrame(6, 1, payload));
        }
    }

    @Test void configuresAndRestoresOnlySelectedUsbOrUart() {
        for (int port : new int[] {1, 3}) {
            var receiver = new Receiver(port);
            var config = new UbloxCaptureConfiguration(receiver);
            config.configure();
            assertEquals(1, receiver.rates[0][port]);
            assertEquals(1, receiver.rates[1][port]);
            assertEquals(port == 1 ? 0 : 7, receiver.rates[0][port == 1 ? 3 : 1]);
            config.restore();
            assertArrayEquals(new int[] {0,7,0,0,0,0}, receiver.rates[0]);
            assertArrayEquals(new int[] {0,4,0,2,0,0}, receiver.rates[1]);
        }
    }

    @Test void partialFailureCanBeRolledBack() {
        var receiver = new Receiver(3); receiver.failSecond = true;
        var config = new UbloxCaptureConfiguration(receiver);
        assertThrows(IllegalStateException.class, config::configure);
        config.restore();
        assertEquals(0, receiver.rates[0][3]);
        assertEquals(2, receiver.rates[1][3]);
    }

    @Test void missingReadbackNeverChangesSettings() {
        var config = new UbloxCaptureConfiguration(request -> List.of());
        assertThrows(IllegalStateException.class, config::configure);
        assertDoesNotThrow(config::restore);
    }

    @Test void delayedAcknowledgementCannotCompleteAMessageRatePoll() {
        var poll = UbloxParser.command(6, 1, new byte[] {2, 0x15});
        assertFalse(SerialCaptureService.matchesReply(poll, new UbloxParser.UbxFrame(5, 1, new byte[] {6, 1})));
        assertFalse(SerialCaptureService.matchesReply(poll, new UbloxParser.UbxFrame(6, 1, new byte[] {2, 0x13, 1})));
        assertTrue(SerialCaptureService.matchesReply(poll, new UbloxParser.UbxFrame(6, 1, new byte[] {2, 0x15, 1})));
        var set = UbloxParser.command(6, 1, new byte[] {2, 0x15, 1});
        assertTrue(SerialCaptureService.matchesReply(set, new UbloxParser.UbxFrame(5, 1, new byte[] {6, 1})));
    }
}
