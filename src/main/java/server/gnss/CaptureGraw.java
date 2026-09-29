package server.gnss;

import server.pvt.NativePvtCodec;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/** Standalone real-device capture using the same one-epoch selection as the web UI. */
public final class CaptureGraw {
    private CaptureGraw() {}

    public static void main(String[] args) throws Exception {
        try (var capture = new SerialCaptureService()) {
            if (args.length == 0 || args[0].equals("--ports")) {
                capture.ports().forEach(p -> System.out.println(p.name() + " · " + p.description()));
                return;
            }
            if (args.length != 3) throw new IllegalArgumentException(
                    "Usage: captureGraw --args=\"COM5 38400 data/capture.graw\" or --args=\"--ports\"");
            Path output = Path.of(args[2]).toAbsolutePath();
            Path raw = Path.of(output + ".ubx"), partial = Path.of(output + ".part");
            if (Files.exists(output) || Files.exists(raw) || Files.exists(partial)) {
                throw new IllegalArgumentException("출력 파일이 이미 있습니다. 새 이름을 지정하세요: " + output);
            }
            Files.createDirectories(output.getParent());
            var done = new CountDownLatch(1);
            var error = new AtomicReference<Throwable>();
            var selection = new SingleEpochCapture(records -> {
                try (var codec = new NativePvtCodec(Path.of(System.getProperty(
                        "lnis.native.candidate", "native/bin/win-x64")))) {
                    var pvt = codec.calculate(records);
                    return pvt.size() == 1 && pvt.getFirst().isPositionValid()
                            && pvt.getFirst().isVelocityValid();
                }
            });
            boolean success = false;
            try (var rawStream = Files.newOutputStream(raw, StandardOpenOption.CREATE_NEW);
                    var graw = Files.newOutputStream(partial, StandardOpenOption.CREATE_NEW)) {
                try {
                    capture.start(new SerialCaptureService.Settings(args[0], Integer.parseInt(args[1]),
                                    "UBX", "Real single epoch", "", "", false, false, true),
                            chunk -> {
                                try { graw.write(chunk.canonical()); }
                                catch (IOException e) { throw new UncheckedIOException(e); }
                            },
                            failed -> { error.set(failed); done.countDown(); }, selection, done::countDown,
                            progress -> System.out.println(progress.stage() + " · " + progress.message()
                                    + " · " + progress.counters()),
                            bytes -> {
                                try { rawStream.write(bytes); }
                                catch (IOException e) { throw new UncheckedIOException(e); }
                            });
                    done.await();
                    if (error.get() != null) throw new IllegalStateException(error.get().getMessage(), error.get());
                    success = true;
                } finally { capture.stopAndAwait(); }
            } finally {
                if (!success) Files.deleteIfExists(partial);
                System.out.println("Raw diagnostic stream: " + raw);
            }
            Files.move(partial, output);
            System.out.println("1 epoch GRAW: " + output);
        }
    }
}
