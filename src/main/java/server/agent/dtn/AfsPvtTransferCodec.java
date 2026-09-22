package server.agent.dtn;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.*;
import server.agent.codec.NativeAfsCodec;
import server.shared.codec.AfsPvtFrameCodec;
import server.shared.codec.GrawCodec;
import server.shared.codec.Hashing;
import server.shared.model.DtnModels;
import server.shared.model.DtnModels.*;

/** v4 keeps original metadata for evidence, while PVT input comes only from decoded frames. */
final class AfsPvtTransferCodec {
    record Restored(List<byte[]> source, List<byte[]> calculation, int frameCount) {}

    private AfsPvtTransferCodec() {}

    static void prepare(Transfer transfer, List<byte[]> records, NativeAfsCodec codec) {
        var payloads = AfsPvtFrameCodec.select(records);
        if (payloads.stream().noneMatch(p -> p.navigation() != null))
            throw new IllegalArgumentException("GPS LNAV subframe 1·2·3이 필요합니다. 관측값만 있으면 GNSS RAW 시험을 사용하세요.");
        if (payloads.size() > 20000) throw new IllegalArgumentException("AFS 계산 프레임 수 초과");
        List<Frame> frames = new ArrayList<>();
        for (var payload : payloads) {
            var blocks = AfsPvtFrameCodec.encode(payload);
            Frame frame = new Frame();
            frame.setIndex(frames.size());
            frame.setPrn(payload.prn());
            frame.setWeek(payload.week());
            frame.setAfsItow((int)(payload.tow() / 1200));
            frame.setToi((int)(payload.tow() % 1200 / 12));
            frame.setFrameBase64(Base64.getEncoder().encodeToString(
                    codec.encode(frame.getToi(), blocks.sb2(), blocks.sb3(), blocks.sb4())));
            frames.add(frame);
        }
        transfer.setFrames(frames);
        transfer.setMetadata(new AfsMetadata(records.stream().map(GrawCodec::decode)
                .map(AfsMetadataCodec::record).toList()));
        AfsMetadataCodec.group(transfer);
        transfer.setSchemaVersion(4);
        transfer.setFormat(AfsPvtFrameCodec.FORMAT);
    }

    static Restored restore(Transfer transfer, NativeAfsCodec codec) {
        if (transfer.getSchemaVersion() != 4 || !AfsPvtFrameCodec.FORMAT.equals(transfer.getFormat())
                || !DtnModels.PROFILE.equals(transfer.getProfile()) || !"AFS_METADATA".equals(transfer.getTestType())
                || transfer.getFile() != null || transfer.getGrawBase64() != null)
            throw new IllegalArgumentException("AFS v4 전송 형식 오류");
        Transfer flat = AfsMetadataCodec.ungroup(transfer);
        List<byte[]> source = new ArrayList<>();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (var record : ((AfsMetadata)flat.getMetadata()).records()) {
            byte[] data = GrawCodec.encode(AfsMetadataCodec.envelope(record));
            if ((long)bytes.size() + 4 + data.length > DtnModels.MAX_INPUT_BYTES)
                throw new IllegalArgumentException("AFS 원본 복원 크기 초과");
            // Decode again to apply the canonical GRAW field validation before use.
            GrawCodec.decode(data);
            source.add(data);
            bytes.writeBytes(ByteBuffer.allocate(4).putInt(data.length).array());
            bytes.writeBytes(data);
        }
        if (!Hashing.hex(Hashing.sha256Digest().digest(bytes.toByteArray())).equals(transfer.getSourceSha256()))
            throw new IllegalArgumentException("원본 metadata GRAW SHA-256 불일치");
        var expected = AfsPvtFrameCodec.select(source);
        if (flat.getFrames().size() != expected.size()) throw new IllegalArgumentException("계산 프레임 누락/중복");
        List<AfsPvtFrameCodec.Payload> decoded = new ArrayList<>();
        for (int i = 0; i < flat.getFrames().size(); i++) {
            Frame frame = flat.getFrames().get(i);
            var original = expected.get(i);
            if (frame.getIndex() != i || !Objects.equals(frame.getPrn(), original.prn())
                    || frame.getWeek() != original.week() || frame.getAfsItow() != (int)(original.tow() / 1200)
                    || frame.getToi() != (int)(original.tow() % 1200 / 12)
                    || frame.getNavigationRecordIndices() != null
                    || frame.getFrameBase64() == null || frame.getFrameBase64().length() != 1000)
                throw new IllegalArgumentException("AFS 프레임 식별/시각 오류");
            var bits = codec.decode(frame.getToi(), Base64.getDecoder().decode(frame.getFrameBase64()));
            if (!bits.sb2Valid() || !bits.sb3Valid() || !bits.sb4Valid())
                throw new IllegalArgumentException("AFS CRC 오류");
            var payload = AfsPvtFrameCodec.decode(bits.sb2(), bits.sb3(), bits.sb4());
            var check = AfsPvtFrameCodec.encode(original);
            if (!Arrays.equals(bits.sb2(), check.sb2()) || !Arrays.equals(bits.sb3(), check.sb3())
                    || !Arrays.equals(bits.sb4(), check.sb4()))
                throw new IllegalArgumentException("AFS 계산 입력과 원본 metadata 불일치");
            decoded.add(payload);
        }
        return new Restored(List.copyOf(source), AfsPvtFrameCodec.records(decoded), decoded.size());
    }
}
