package server.gnss;

import org.junit.jupiter.api.Test;
import java.net.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class WindowsSerialBridgeTest {
    private static final String TOKEN = "test-only-bridge-token-0123456789012345";
    private static final SerialCaptureService.Settings SETTINGS = new SerialCaptureService.Settings(
        "COM5",38400,"UBX","test","","",false,false,true);
    static final class FakePort implements SerialConnection {
        boolean open=true; byte[] data={1,2,(byte)181,98}; boolean unplugged;
        public int readBytes(byte[] b,int n){if(unplugged)return -1; int count=Math.min(n,data.length);System.arraycopy(data,0,b,0,count);return count;}
        public int writeBytes(byte[] b,int n){data=Arrays.copyOf(b,n);return n;}
        public boolean isOpen(){return open;}
        public void closePort(){open=false;}
    }
    private WindowsSerialBridge server(FakePort fake,long lease) throws Exception {
        var server=new WindowsSerialBridge(new InetSocketAddress("127.0.0.1",0),TOKEN,lease,s->{fake.open=true;return fake;},
            ()->List.of(new SerialCaptureService.DetectedPort("COM5","GNSS USB")));
        server.start();return server;
    }
    private WindowsSerialClient client(WindowsSerialBridge s,String token){return new WindowsSerialClient(URI.create("http://127.0.0.1:"+s.portNumber()),token);}
    @Test void byteTransportPreservesDataAndReturnsPortWithoutEnumeratingClaim() throws Exception {
        var fake=new FakePort();
        try(var server=server(fake,30000)){
            var client=client(server,TOKEN);
            assertEquals("COM5",client.ports().getFirst().name());
            assertFalse(client.request("/health", Map.of()).path("version").asText().isBlank());
            assertFalse(client.request("/health",Map.of()).path("busy").asBoolean());
            assertFalse(client.request("/time",Map.of()).path("ready").asBoolean());
            assertTrue(client.request("/time",Map.of()).hasNonNull("sentAt"));
            var connection=client.open(SETTINGS);
            assertThrows(IllegalStateException.class,()->client.open(SETTINGS));
            byte[] binary={0,(byte)255,(byte)181,98,13,10};
            assertEquals(binary.length,connection.writeBytes(binary,binary.length));
            byte[] received=new byte[20];assertEquals(6,connection.readBytes(received,20));
            assertArrayEquals(binary,Arrays.copyOf(received,6));
            connection.closePort();assertFalse(fake.open);
            assertFalse(client.request("/health",Map.of()).path("busy").asBoolean());
            client.open(SETTINGS).closePort();
        }
    }
    @Test void authenticationAndSessionOwnershipAreRequired() throws Exception {
        var fake=new FakePort();
        try(var server=server(fake,30000)){
            var good=client(server,TOKEN);
            assertThrows(IllegalStateException.class,()->client(server,"wrong-token-012345678901234567890123").ports());
            var connection=good.open(SETTINGS);
            assertThrows(IllegalStateException.class,()->good.request("/close",Map.of("session","wrong")));
            assertTrue(fake.open);connection.closePort();
        }
    }
    @Test void unplugAndAbandonedClientReleasePort() throws Exception {
        var fake=new FakePort();
        try(var server=server(fake,100)){
            var client=client(server,TOKEN);var connection=client.open(SETTINGS);
            fake.unplugged=true;
            assertThrows(IllegalStateException.class,()->connection.readBytes(new byte[10],10));
            assertFalse(fake.open);fake.unplugged=false;
            client.open(SETTINGS);
            long deadline=System.nanoTime()+3_000_000_000L;
            while(client.request("/health",Map.of()).path("busy").asBoolean() && System.nanoTime()<deadline)Thread.sleep(50);
            assertFalse(client.request("/health",Map.of()).path("busy").asBoolean());
        }
    }
    @Test void unavailableBridgeHasActionableError() throws Exception {
        int unused;try(var socket=new java.net.ServerSocket(0)){unused=socket.getLocalPort();}
        var client=new WindowsSerialClient(URI.create("http://127.0.0.1:"+unused),TOKEN);
        assertTrue(assertThrows(IllegalStateException.class,client::ports).getMessage().contains("start-serial-bridge.ps1"));
    }
}
