package server.agent.gnss;

import com.fazecast.jSerialComm.SerialPort;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import server.shared.codec.GrawCodec;

/**
 * Windows COM 포트에서 GNSS 데이터를 읽어 canonical GRAW 청크로 변환한다.
 *
 * <p>전용 virtual thread에서 serial byte를 읽고 선택한 protocol parser에 전달한다. raw serial과 canonical GRAW를 함께
 * 계수하지만 서버에는 시험에 사용할 canonical 레코드만 별도 필드로 전달한다. stop 또는 close 시 포트를 닫고 가능한 경우 u-blox 임시 설정을 원래 값으로
 * 복원한다.
 */
public final class SerialCaptureService implements AutoCloseable {
  /** 서버의 수집 명령에서 전달받아 실제 Windows 직렬 포트에 적용하는 설정이다. */
  @lombok.Value
  @lombok.AllArgsConstructor
  @lombok.Builder
  @lombok.extern.jackson.Jacksonized
  @lombok.experimental.Accessors(fluent = true)
  @com.fasterxml.jackson.annotation.JsonAutoDetect(
      fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY)
  public static class Settings {
    /** 열어야 할 Windows 직렬 포트 이름이다. */
    String portName;

    /** GNSS 장비와 통신할 전송 속도이며 단위는 baud다. */
    int baudRate;

    /** {@code ubx}, {@code raw-only}, {@code lnis-canonical-v1} 중 수집 해석 방식이다. */
    String protocolId;

    /** GRAW 수신기 메타데이터에 기록할 사용자 지정 수집 이름이다. */
    String sessionName;

    /** GRAW 메타데이터에 기록할 수신기 모델명이다. */
    String receiverModel;

    /** GRAW 메타데이터에 기록할 수신기 펌웨어 버전이다. */
    String firmwareVersion;

    /** 포트를 연 뒤 DTR 제어선을 활성화할지 여부다. */
    boolean dtrEnabled;

    /** 포트를 연 뒤 RTS 제어선을 활성화할지 여부다. */
    boolean rtsEnabled;

    boolean singleEpoch;
  }

  /** 메모리 상한을 위해 수집 데이터를 약 1 MiB 단위로 서버에 전달하는 청크다. */
  @lombok.Value
  @lombok.AllArgsConstructor
  @lombok.Builder
  @lombok.extern.jackson.Jacksonized
  @lombok.experimental.Accessors(fluent = true)
  @com.fasterxml.jackson.annotation.JsonAutoDetect(
      fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY)
  public static class CaptureChunk {
    /** 0부터 시작하며 서버가 Redis 청크 키 순서를 결정하는 번호다. */
    long index;

    /** COM 포트에서 그대로 읽은 원시 직렬 바이트다. */
    byte[] rawSerial;

    /** 시험 입력으로 사용할 길이-prefix canonical GRAW 바이트다. */
    byte[] canonical;

    /** 수집 시작 후 COM 포트에서 읽은 원시 바이트의 누적 합계다. */
    long bytesRead;

    /** canonical GRAW로 변환해 생성한 레코드 누적 합계다. */
    long records;
  }

  private final AtomicBoolean running = new AtomicBoolean();
  private volatile SerialPort port;
  private volatile Thread worker;
  private final List<byte[]> restoreCommands = new ArrayList<>();

  public List<String> portNames() {
    return Arrays.stream(SerialPort.getCommPorts())
        .map(SerialPort::getSystemPortName)
        .sorted()
        .toList();
  }

  public synchronized void start(
      Settings settings, Consumer<CaptureChunk> chunks, Consumer<Throwable> failure) {
    start(settings, chunks, failure, null, () -> {});
  }

  public synchronized void start(Settings settings, Consumer<CaptureChunk> chunks,
      Consumer<Throwable> failure, SingleEpochCapture selection, Runnable completed) {
    if (settings.singleEpoch && (selection == null || !"ubx".equalsIgnoreCase(settings.protocolId)))
      throw new IllegalArgumentException("한 시점 수집은 UBX만 지원합니다.");
    if (!running.compareAndSet(false, true))
      throw new IllegalStateException("Capture is already running");
    // 포트를 완전히 구성한 뒤 worker를 시작해 reader가 반쯤 적용된 직렬 설정을 보지 않게 한다.
    try {
    port = SerialPort.getCommPort(settings.portName);
    port.setBaudRate(settings.baudRate);
    port.setNumDataBits(8);
    port.setNumStopBits(SerialPort.ONE_STOP_BIT);
    port.setParity(SerialPort.NO_PARITY);
    port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 1000, 1000);
    if (!port.openPort()) {
      running.set(false);
      throw new IllegalStateException("Unable to open " + settings.portName);
    }
    if (settings.dtrEnabled) port.setDTR();
    else port.clearDTR();
    if (settings.rtsEnabled) port.setRTS();
    else port.clearRTS();
    if ("ubx".equalsIgnoreCase(settings.protocolId)) configureUbloxTemporarily();
    worker =
        Thread.ofPlatform().name("lnis-gnss-capture").start(() -> run(settings, chunks, failure, selection, completed));
    } catch (RuntimeException error) {
      if (port != null) port.closePort();
      running.set(false);
      throw error;
    }
  }

  private void run(Settings settings, Consumer<CaptureChunk> chunks, Consumer<Throwable> failure,
      SingleEpochCapture selection, Runnable completed) {
    UbloxParser ubx = new UbloxParser();
    UUID testId = UUID.randomUUID();
    long sequence = 0, bytesRead = 0, records = 0, chunkIndex = 0;
    ByteArrayOutputStream rawChunk = new ByteArrayOutputStream(1024 * 1024),
        canonicalChunk = new ByteArrayOutputStream(1024 * 1024);
    boolean selected = false;
    Throwable failed = null;
    long deadline = System.nanoTime() + 120_000_000_000L;
    try {
      // raw-only를 제외한 파일의 첫 record에는 나중에 수집 환경을 추적할 메타데이터를 넣는다.
      if (!"raw-only".equalsIgnoreCase(settings.protocolId)) {
        byte[] metadata =
            GrawCodec.encode(
                new GrawCodec.Envelope(
                    testId,
                    UUID.randomUUID(),
                    sequence++,
                    Instant.now(),
                    new GrawCodec.ReceiverMetadata(
                        settings.receiverModel,
                        settings.firmwareVersion,
                        settings.portName,
                        settings.baudRate,
                        settings.sessionName)));
        if (selection != null) selection.accept(metadata);
        else writeRecord(canonicalChunk, metadata);
        records++;
      }
      byte[] buffer = new byte[8192];
      while (running.get()) {
        if (selection != null && System.nanoTime() >= deadline)
          throw new IllegalStateException("120초 안에 유효한 지구 PVT를 얻지 못했습니다. RAWX/SFRBX 출력과 안테나 수신 상태를 확인하세요.");
        int count = port.readBytes(buffer, buffer.length);
        if (count < 0) {
          throw new IllegalStateException("Serial read failed");
        }
        if (count == 0) {
          continue;
        }
        if (selection == null) rawChunk.write(buffer, 0, count);
        bytesRead += count;
        // ubx는 검증·변환하고 canonical-v1은 이미 변환된 입력이므로 그대로 누적한다.
        if ("ubx".equalsIgnoreCase(settings.protocolId))
          for (var frame : ubx.push(buffer, count)) {
            var message = UbloxParser.toCanonical(frame);
            if (message != null) {
              byte[] record = GrawCodec.encode(
                      new GrawCodec.Envelope(
                          testId, UUID.randomUUID(), sequence++, Instant.now(), message));
              if (selection != null) {
                var chosen = selection.accept(record);
                if (chosen != null) {
                  for (byte[] item : chosen) writeRecord(canonicalChunk, item);
                  records = chosen.size();
                  selected = true;
                  running.set(false);
                  break;
                }
              } else writeRecord(canonicalChunk, record);
              records++;
            }
          }
        else if ("lnis-canonical-v1".equalsIgnoreCase(settings.protocolId))
          canonicalChunk.write(buffer, 0, count);
        // raw/canonical 중 하나라도 상한에 도달하면 둘을 함께 비워 같은 시점의 진단 자료를 전달한다.
        if (rawChunk.size() >= 1024 * 1024 || canonicalChunk.size() >= 1024 * 1024)
          chunks.accept(
              new CaptureChunk(
                  chunkIndex++, drain(rawChunk), drain(canonicalChunk), bytesRead, records));
      }
      if (selection != null && !selected) throw new IllegalStateException("한 시점 수집이 중단되었습니다.");
      if (rawChunk.size() > 0 || canonicalChunk.size() > 0) {
        chunks.accept(
            new CaptureChunk(
                chunkIndex, drain(rawChunk), drain(canonicalChunk), bytesRead, records));
      }
    } catch (Throwable error) {
      failed = error;
    } finally {
      try { restoreUbloxConfiguration(); }
      catch (Throwable error) { if (failed == null) failed = error; }
      finally { if (port != null) port.closePort(); running.set(false); }
    }
    if (failed != null) failure.accept(failed);
    else if (selected) {
      try { completed.run(); }
      catch (Throwable error) { failure.accept(error); }
    }
  }

  private static void writeRecord(ByteArrayOutputStream out, byte[] record) {
    out.writeBytes(
        ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(record.length).array());
    out.writeBytes(record);
  }

  private static byte[] drain(ByteArrayOutputStream out) {
    byte[] bytes = out.toByteArray();
    out.reset();
    return bytes;
  }

  public synchronized void stop() {
    running.set(false);
  }

  /** DTN 입력 확정 전 마지막 청크 전달과 COM 정리가 끝났는지 확인한다. */
  public void stopAndAwait() throws InterruptedException {
    stop();
    Thread current = worker;
    if (current != null && current != Thread.currentThread()) {
      current.join(10000);
      if (current.isAlive()) throw new IllegalStateException("GNSS 수집 종료 대기 시간 초과");
    }
  }

  /** RAWX/SFRBX 출력률을 임시 활성화하고 조회에 성공한 원래 설정은 종료 시 복원하도록 보관한다. */
  private void configureUbloxTemporarily() {
    restoreCommands.clear();
    write(UbloxParser.command(0x0A, 0x04, new byte[0]));
    for (int[] message : List.of(new int[] {0x02, 0x15}, new int[] {0x02, 0x13})) {
      byte[] original = pollMessageRate(message[0], message[1]);
      if (original != null) {
        restoreCommands.add(UbloxParser.command(0x06, 0x01, original));
        byte[] enabled = original.clone();
        if (enabled.length >= 8) {
          enabled[3] = 1;
          enabled[5] = 1;
        } else if (enabled.length >= 3) {
          enabled[2] = 1;
        }
        write(UbloxParser.command(0x06, 0x01, enabled));
      } else
        write(
            UbloxParser.command(0x06, 0x01, new byte[] {(byte) message[0], (byte) message[1], 1}));
    }
  }

  private byte[] pollMessageRate(int messageClass, int messageId) {
    write(UbloxParser.command(0x06, 0x01, new byte[] {(byte) messageClass, (byte) messageId}));
    UbloxParser parser = new UbloxParser();
    long deadline = System.nanoTime() + 1_500_000_000L;
    byte[] buffer = new byte[2048];
    while (System.nanoTime() < deadline) {
      int count = port.readBytes(buffer, buffer.length);
      if (count <= 0) {
        continue;
      }
      for (var frame : parser.push(buffer, count)) {
        if (frame.messageClass() == 0x06
            && frame.messageId() == 0x01
            && frame.payload().length >= 3
            && (frame.payload()[0] & 0xff) == messageClass
            && (frame.payload()[1] & 0xff) == messageId) {
          return frame.payload();
        }
      }
    }
    return null;
  }

  private void restoreUbloxConfiguration() {
    if (port == null || !port.isOpen()) return;
    for (byte[] command : restoreCommands) write(command);
    restoreCommands.clear();
  }

  private void write(byte[] value) {
    if (port.writeBytes(value, value.length) != value.length) {
      throw new IllegalStateException("Unable to configure u-blox receiver");
    }
  }

  @Override
  public void close() {
    stop();
  }
}
