package server.gnss;

import com.fazecast.jSerialComm.SerialPort;
import java.util.*;

/** Byte transport only; receiver configuration and parsing stay in SerialCaptureService. */
interface SerialConnection {
    int readBytes(byte[] buffer, int length);
    int writeBytes(byte[] buffer, int length);
    boolean isOpen();
    void closePort();

    static List<SerialCaptureService.DetectedPort> localPorts() {
        return Arrays.stream(SerialPort.getCommPorts()).map(p ->
            new SerialCaptureService.DetectedPort(p.getSystemPortName(), p.getDescriptivePortName()
                + " · " + p.getPortDescription() + (p.getVendorID() < 0 ? "" :
                String.format(" · VID:%04X PID:%04X", p.getVendorID(), p.getProductID()))))
            .sorted(Comparator.comparing(SerialCaptureService.DetectedPort::name)).toList();
    }

    static SerialConnection local(SerialCaptureService.Settings settings) {
        var p = SerialPort.getCommPort(settings.portName());
        p.setBaudRate(settings.baudRate());
        p.setNumDataBits(8);
        p.setNumStopBits(SerialPort.ONE_STOP_BIT);
        p.setParity(SerialPort.NO_PARITY);
        p.setFlowControl(SerialPort.FLOW_CONTROL_DISABLED);
        p.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 500, 1000);
        if (!p.openPort()) throw new IllegalStateException(settings.portName()
            + " 포트를 열 수 없습니다. 장치 연결 및 사용 상태를 확인하고 유센터 연결을 해제하세요.");
        if (settings.dtrEnabled()) p.setDTR(); else p.clearDTR();
        if (settings.rtsEnabled()) p.setRTS(); else p.clearRTS();
        return new SerialConnection() {
            public int readBytes(byte[] b, int n) { return p.readBytes(b, n); }
            public int writeBytes(byte[] b, int n) { return p.writeBytes(b, n); }
            public boolean isOpen() { return p.isOpen(); }
            public void closePort() { p.closePort(); }
        };
    }
}
