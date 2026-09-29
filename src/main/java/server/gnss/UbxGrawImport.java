package server.gnss;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Import real UBX without inventing measurements or requiring a position fix. */
public final class UbxGrawImport {
    private UbxGrawImport() {}

    /** 웹 업로드는 사용자가 선택하지 않은 에폭을 임의로 잘라내지 않는다. */
    public static byte[] convertAll(byte[] raw, Instant archiveTime, String source) {
        return convert(raw, 0, archiveTime, source);
    }

    public static byte[] convert(byte[] raw, int epochCount, Instant archiveTime, String source) {
        if (epochCount < 0 || epochCount > 1000) throw new IllegalArgumentException("Epoch count must be 0..1000");
        var parser = new UbloxParser();
        List<GrawCodec.Message> messages = new ArrayList<>();
        List<Integer> epochPositions = new ArrayList<>();
        String model = "", firmware = "";
        int retainedBytes = 0;
        for (int offset = 0; offset < raw.length; offset += 8192) {
            byte[] block = java.util.Arrays.copyOfRange(raw, offset, Math.min(raw.length, offset + 8192));
            for (var frame : parser.push(block, block.length)) {
                if (frame.messageClass() == 10 && frame.messageId() == 4 && frame.payload().length >= 40) {
                    for (int pos = 40; pos + 30 <= frame.payload().length; pos += 30) {
                        String value = new String(frame.payload(), pos, 30, StandardCharsets.US_ASCII).split("\u0000", 2)[0].trim();
                        if (value.startsWith("MOD=")) model = value.substring(4);
                        if (value.startsWith("FWVER=")) firmware = value.substring(6);
                    }
                }
                var message = UbloxParser.toCanonical(frame);
                if (message == null) continue;
                if (message instanceof GrawCodec.ObservationEpoch epoch) {
                    if (epoch.observations().isEmpty()) continue;
                    if (!epochPositions.isEmpty()) {
                        var previous = (GrawCodec.ObservationEpoch) messages.get(epochPositions.getLast());
                        if (previous.week() == epoch.week() && previous.receiverTowSeconds() == epoch.receiverTowSeconds()) continue;
                    }
                    epochPositions.add(messages.size());
                }
                if (epochCount == 0) {
                    retainedBytes += 4 + 66 + (message instanceof GrawCodec.ObservationEpoch epoch
                            ? 15 + 31 * epoch.observations().size()
                            : 7 + 4 * ((GrawCodec.NavigationUpdate) message).words().size());
                    if (retainedBytes > server.common.DtnModels.MAX_INPUT_BYTES || epochPositions.size() > 1000) {
                        throw new IllegalArgumentException("UBX 관측 구간이 너무 깁니다. 1000 Epoch 이하, 변환 결과 1 MiB 이하의 짧은 파일을 선택하세요.");
                    }
                }
                messages.add(message);
            }
        }
        if (epochPositions.isEmpty()) throw new IllegalArgumentException("UBX 파일에 사용 가능한 RAWX 관측값이 없습니다.");
        if (epochCount == 0) epochCount = epochPositions.size();
        if (epochPositions.size() < epochCount) throw new IllegalArgumentException(
                "Not enough nonempty real epochs: " + epochPositions.size() + " / " + epochCount);
        int first = epochPositions.get(epochPositions.size() - epochCount), last = epochPositions.getLast();
        List<GrawCodec.Message> selected = new ArrayList<>();
        selected.add(new GrawCodec.ReceiverMetadata(model, firmware, "UBX archive", 0,
                "REAL UBX import: " + source + "; capturedAt is archive timestamp, not per-frame host arrival time"));
        for (int i = 0; i <= last; i++) {
            var message = messages.get(i);
            if (!(message instanceof GrawCodec.ObservationEpoch) || i >= first) selected.add(message);
        }
        var output = new ByteArrayOutputStream();
        UUID testId = UUID.randomUUID(); long sequence = 0;
        for (var message : selected) {
            byte[] record = GrawCodec.encode(new GrawCodec.Envelope(testId, UUID.randomUUID(), sequence++, archiveTime, message));
            output.writeBytes(ByteBuffer.allocate(4).putInt(record.length).array());
            output.writeBytes(record);
        }
        if (output.size() > server.common.DtnModels.MAX_INPUT_BYTES) throw new IllegalArgumentException("GRAW exceeds 1 MiB");
        return output.toByteArray();
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("Usage: source.ubx output.graw epochCount");
        Path source = Path.of(args[0]), destination = Path.of(args[1]);
        if (Files.size(source) > 64 * 1024 * 1024) throw new IllegalArgumentException("Archive exceeds 64 MiB");
        byte[] bytes = convert(Files.readAllBytes(source), Integer.parseInt(args[2]),
                Files.getLastModifiedTime(source).toInstant(), source.getFileName().toString());
        Files.write(destination, bytes, StandardOpenOption.CREATE_NEW);
        System.out.println("Real GRAW: " + destination + " · epochs=" + args[2] + " · bytes=" + bytes.length);
    }
}
