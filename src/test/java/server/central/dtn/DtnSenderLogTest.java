package server.central.dtn;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import server.central.agent.*;
import server.central.input.InputBufferService;

class DtnSenderLogTest {
    @Test
    void peerStatesRemainVisibleWithoutImportingOldPeerAdapterLogs()
    {
        for (String state : List.of("CALCULATING", "FAILED", "CANCELLED")) {
            var fixture = new Fixture();
            var result = new DtnRemoteResult();
            result.setState(state);
            result.setReceivedAt(Instant.now());
            result.setMessage("수신 처리 실패 원인");
            result.setAdapterLogs(List.of(new DtnRemoteResult.AdapterLog(Instant.now(), "INFO", "receiver detail")));
            when(fixture.link.result(fixture.job.getId())).thenReturn(result);
            ReflectionTestUtils.invokeMethod(fixture.service, "pollReceiver", fixture.job);
            verify(fixture.logs, never()).importAdapter(any(), any());
            verify(fixture.logs).add(eq(fixture.job.getId()), eq("TEST"), eq("INFO"), eq("상대 결과 확인"), eq(false), eq("상대 수신 완료 · 시험 결과 대기"));
            assertEquals("CALCULATING".equals(state) ? "WAITING_DTN" : state, fixture.job.getState());
            if ("FAILED".equals(state)) {
                verify(fixture.logs).add(any(), eq("TEST"), eq("ERROR"), eq("상대 결과 확인"), eq(false), contains("수신 처리 실패 원인"));
            } else if ("CANCELLED".equals(state)) {
                verify(fixture.logs).add(any(), eq("TEST"), eq("INFO"), eq("상대 결과 확인"), eq(false), eq("상대 시험 중지 확인"));
            } else {
                clearInvocations(fixture.logs);
                ReflectionTestUtils.invokeMethod(fixture.service, "pollReceiver", fixture.job);
                verifyNoInteractions(fixture.logs);
            }
        }
    }

    @Test
    void waitingTimeoutStillProducesError()
    {
        var fixture = new Fixture();
        fixture.job.setCreatedAt(Instant.now().minusSeconds(601));
        when(fixture.repository.findByStateIn(any())).thenReturn(List.of(fixture.job));
        fixture.service.tick();
        assertEquals("FAILED", fixture.job.getState());
        verify(fixture.logs).add(any(), eq("TEST"), eq("ERROR"), eq("상대 결과 확인"), eq(false), contains("제한 시간"));
    }

    @Test
    void historicalSenderFilteringPreservesPaginationErrorsAndReceiverDownload()
    {
        var service = mock(DtnService.class);
        var logs = mock(DtnLogService.class);
        var controller = new DtnController(service, new ObjectMapper());
        ReflectionTestUtils.setField(controller, "logs", logs);
        when(service.sendingNode()).thenReturn(true);
        UUID id = UUID.randomUUID();
        List<DtnLogEntry> page = IntStream.rangeClosed(1, 500).mapToObj(i -> entry(id, i, "DTN", "INFO", "receiver detail", true)).toList();
        var failure = entry(id, 501, "시험", "ERROR", "수신 실패", false);
        var oldSummary = entry(id, 502, "송신 최종 요약", "INFO", "Clock Bias 변화 10 s", false);
        var oldComparison = entry(id, 503, "PVT 비교", "INFO", "positionDifferenceMeters", true);
        when(logs.read(id, 0)).thenReturn(page);
        when(logs.read(id, 500)).thenReturn(List.of(failure, oldSummary, oldComparison));
        var response = (Map<?, ?>) controller.logs(id, 0, false).getBody();
        assertEquals(List.of(), response.get("entries"));
        assertEquals(500L, response.get("nextSequence"));
        assertEquals(true, response.get("hasMore"));
        var next = (Map<?, ?>) controller.logs(id, 500, false).getBody();
        assertEquals(List.of(failure), next.get("entries"));
        assertEquals(503L, next.get("nextSequence"));
        String senderText = (String) controller.logs(id, 0, true).getBody();
        assertTrue(senderText.contains("수신 실패"));
        assertFalse(senderText.contains("receiver detail"));
        assertFalse(senderText.contains("Clock Bias"));
        assertFalse(senderText.contains("positionDifferenceMeters"));
        when(service.sendingNode()).thenReturn(false);
        assertTrue(((String) controller.logs(id, 0, true).getBody()).contains("receiver detail"));
    }

    private static DtnLogEntry entry(UUID id, long sequence, String stage, String level, String message, boolean detail)
    {
        var entry = new DtnLogEntry(id, "TEST", Instant.now(), level, stage, detail, message);
        ReflectionTestUtils.setField(entry, "sequence", sequence);
        return entry;
    }

    private static class Fixture {
        final DtnRepository repository = mock(DtnRepository.class);
        final DtnLogService logs = mock(DtnLogService.class);
        final DtnNodeLink link = mock(DtnNodeLink.class);
        final DtnService service = new DtnService(repository, mock(AgentCommandService.class),
                mock(AgentRepository.class), mock(AgentConnectionRegistry.class),
                mock(InputBufferService.class), new ObjectMapper());
        final DtnJob job = new DtnJob();

        Fixture() {
            when(link.sender()).thenReturn(true);
            service.setNodeLink(link);
            ReflectionTestUtils.setField(service, "logs", logs);
            job.setId(UUID.randomUUID());
            job.setState("WAITING_DTN");
            job.setCreatedAt(Instant.now());
            when(repository.findById(job.getId())).thenReturn(Optional.of(job));
        }
    }
}
