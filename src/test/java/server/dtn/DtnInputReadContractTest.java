package server.dtn;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import server.common.ApiExceptionHandler;
import server.common.DtnModels;
import server.gnss.*;
import server.iq.IqController;
import server.iq.IqService;

import java.util.Optional;
import java.util.UUID;

class DtnInputReadContractTest {
    private final UUID id = UUID.randomUUID();
    private final InputBufferRepository repository = mock(InputBufferRepository.class);
    private final InputBufferEntity input = mock(InputBufferEntity.class);
    private final InputBufferService inputs =
            new InputBufferService(repository, mock(DtnRepository.class));

    private void prepare(long size, long chunks) {
        when(repository.find(id)).thenReturn(Optional.of(input));
        when(input.inputId()).thenReturn(id);
        when(input.complete()).thenReturn(true);
        when(input.receivedSize()).thenReturn(size);
        when(input.chunkCount()).thenReturn(chunks);
    }

    private byte[] senderInput() {
        var service = new DtnService(mock(DtnRepository.class),
                mock(server.node.AgentCommandService.class),
                mock(server.node.AgentRepository.class),
                mock(server.node.AgentConnectionRegistry.class), inputs,
                new com.fasterxml.jackson.databind.ObjectMapper());
        return org.springframework.test.util.ReflectionTestUtils.invokeMethod(service, "readInput", id);
    }

    private MockMvc mvc() {
        return MockMvcBuilders.standaloneSetup(
                        new DtnInputViewController(
                                inputs, new com.fasterxml.jackson.databind.ObjectMapper()),
                        new IqController(mock(IqService.class), mock(DtnService.class), inputs))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private void rejectsBoth(String message) throws Exception {
        MockMvc mvc = mvc();
        mvc.perform(get("/lnis/api/v1/dtn/inputs/" + id + "/observations"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(message));
        mvc.perform(
                        post("/lnis/api/v1/dtn/iq")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"inputId\":\"" + id + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(message));
    }

    @Test
    void joinsChunksInOrderAndAcceptsExactLimit() {
        prepare(DtnModels.MAX_INPUT_BYTES, 2);
        byte[] first = new byte[DtnModels.MAX_INPUT_BYTES - 1];
        first[0] = 12;
        when(repository.getChunk(id, 0)).thenReturn(first);
        when(repository.getChunk(id, 1)).thenReturn(new byte[] {34});
        byte[] result = senderInput();
        assertEquals(DtnModels.MAX_INPUT_BYTES, result.length);
        assertEquals(12, result[0]);
        assertEquals(34, result[result.length - 1]);
        var order = inOrder(repository);
        order.verify(repository).getChunk(id, 0);
        order.verify(repository).getChunk(id, 1);
    }

    @Test
    void preservesMissingChunkResponse() throws Exception {
        prepare(10, 1);
        rejectsBoth("Input chunk not found");
        assertEquals("Input chunk not found",
                assertThrows(IllegalArgumentException.class, this::senderInput).getMessage());
    }

    @Test
    void rejectsOversizedActualDataEvenWhenMetadataFits() throws Exception {
        prepare(1, 3);
        when(repository.getChunk(id, 0)).thenReturn(new byte[DtnModels.MAX_INPUT_BYTES]);
        when(repository.getChunk(id, 1)).thenReturn(new byte[] {1});
        rejectsBoth("입력 크기 초과");
        assertEquals("입력 크기 불일치",
                assertThrows(IllegalStateException.class, this::senderInput).getMessage());
        verify(repository, never()).getChunk(id, 2);
    }

    @Test
    void preservesMetadataMismatchResponse() throws Exception {
        prepare(2, 1);
        when(repository.getChunk(id, 0)).thenReturn(new byte[] {1});
        rejectsBoth("입력 크기 불일치");
        assertEquals("입력 크기 불일치",
                assertThrows(IllegalStateException.class, this::senderInput).getMessage());
    }

    @Test
    void preservesFeatureSpecificIncompleteMessages() throws Exception {
        prepare(10, 1);
        when(input.complete()).thenReturn(false);
        assertEquals("수집 종료 후 1 MiB 이하의 입력을 확정하세요.",
                assertThrows(IllegalArgumentException.class, this::senderInput).getMessage());
        MockMvc mvc = mvc();
        mvc.perform(get("/lnis/api/v1/dtn/inputs/" + id + "/observations"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("완료된 1 MiB 이하 GRAW 입력이 필요합니다."));
        mvc.perform(
                        post("/lnis/api/v1/dtn/iq")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"inputId\":\"" + id + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("완료된 1 MiB 이하 GNSS 입력이 필요합니다."));
        verify(repository, never()).getChunk(any(), anyLong());
    }
}
