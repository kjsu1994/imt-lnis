package server.agent.runtime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import server.agent.codec.NativeAfsCodec;
import server.agent.config.AgentConfig;
import server.agent.transport.AfsSessionService;
import server.shared.model.AgentProtocol.*;
import server.shared.model.LnisModels.*;

class AgentRuntimeCancellationTest {
  @Test void lateResultAndCancellationCannotReleaseNewSession() {
    var config = AgentConfig.builder().agentId("receiver").role(AgentRole.RECEIVER)
        .nativeDirectory(Path.of("unused")).build();
    var json = new ObjectMapper().findAndRegisterModules();
    try (var runtime = new AgentRuntime(config, mock(NativeAfsCodec.class))) {
      var sent = new ArrayList<Envelope>();
      runtime.outbound(sent::add);
      UUID oldId = UUID.randomUUID(), nextId = UUID.randomUUID();
      var args = json.valueToTree(new AfsSessionService.SessionCommand("sender", "receiver",
          UUID.randomUUID(), new AfsSettings(1),
          new TestOptions(TestType.TEST_A_NORMAL, 0, 0, 0, Map.of())));
      java.util.function.BiConsumer<UUID, CommandType> command = (id, type) -> runtime.handle(
          Envelope.of(MessageType.COMMAND, "receiver", AgentRole.RECEIVER, id,
              json.valueToTree(new Command(type, args))));
      command.accept(oldId, CommandType.ARM_RECEIVER);
      assertEquals(AgentState.BUSY, runtime.state());
      command.accept(oldId, CommandType.CANCEL_SESSION);
      int beforeLateFrame = sent.size();
      runtime.handle(Envelope.of(MessageType.AFS_TRANSFER_BATCH, "receiver", AgentRole.RECEIVER,
          oldId, json.createObjectNode()));
      assertEquals(AgentState.READY, runtime.state());
      assertEquals(beforeLateFrame, sent.size(), "Late frames must be ignored without an error");
      command.accept(nextId, CommandType.ARM_RECEIVER);
      beforeLateFrame = sent.size();
      runtime.handle(Envelope.of(MessageType.AFS_TRANSFER_COMPLETE, "receiver", AgentRole.RECEIVER,
          oldId, json.createObjectNode()));
      assertEquals(beforeLateFrame, sent.size());
      ReflectionTestUtils.invokeMethod(runtime, "completeRole", oldId, new Object());
      command.accept(oldId, CommandType.CANCEL_SESSION);
      assertEquals(AgentState.BUSY, runtime.state());
      assertTrue(sent.stream().noneMatch(e -> e.type() == MessageType.ROLE_RESULT));
      command.accept(nextId, CommandType.CANCEL_SESSION);
      assertEquals(AgentState.READY, runtime.state());
    }
  }
}
