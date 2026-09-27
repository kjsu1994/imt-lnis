package server.node;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;

import server.common.AgentProtocol.CommandType;
import server.common.LnisModels.AgentRole;

import java.nio.file.Path;
import java.util.UUID;

/** 구 ACK/소켓 테스트 대신 직접 호출과 수명주기 보호를 검증한다. */
class LocalRuntimeTest {
    @Test
    void directCallPreservesImmediateFailure() {
        var local = mock(LocalNodeLifecycle.class);
        var runtime = mock(AgentRuntime.class);
        var service = new AgentCommandService(local);
        UUID id = UUID.randomUUID();
        when(local.runtime("sender-1")).thenReturn(runtime);
        assertNotNull(service.command("sender-1", id, CommandType.STOP_CAPTURE, null));
        verify(runtime).execute(id, CommandType.STOP_CAPTURE, null);
        var rejected = new IllegalStateException("busy");
        doThrow(rejected).when(runtime).execute(id, CommandType.STOP_CAPTURE, null);
        assertSame(
                rejected,
                assertThrows(
                        IllegalStateException.class,
                        () -> service.command("sender-1", id, CommandType.STOP_CAPTURE, null)));
    }

    @Test
    void rejectsWrongIdentityAndUnavailableRuntime() {
        var local =
                new LocalNodeLifecycle(
                        new AgentConfig("sender-1", AgentRole.SENDER, Path.of("native")),
                        new AgentConnectionRegistry(),
                        mock(AgentMessageService.class),
                        mock(AgentRepository.class));
        assertThrows(IllegalArgumentException.class, () -> local.runtime("receiver-1"));
        assertThrows(IllegalStateException.class, () -> local.runtime("sender-1"));
        local.close();
        local.close();
        assertFalse(local.getAsBoolean());
    }

    @Test
    void runtimeClosesOnceAndRejectsFurtherWork() {
        var codec = mock(server.afs.NativeAfsCodec.class);
        var runtime =
                new AgentRuntime(
                        new AgentConfig("sender-1", AgentRole.SENDER, Path.of("native")),
                        codec,
                        mock(AgentMessageService.class));
        runtime.close();
        runtime.close();
        verify(codec).close();
        assertThrows(
                IllegalStateException.class,
                () -> runtime.execute(null, CommandType.LIST_PORTS, null));
        assertThrows(IllegalStateException.class, runtime::worker);
    }
}
