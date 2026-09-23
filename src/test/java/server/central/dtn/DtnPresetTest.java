package server.central.dtn;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import server.shared.model.DtnModels.HdtnConfig;
import java.util.concurrent.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DtnPresetTest {
    @Autowired DtnPresetRepository repository;
    @Autowired PlatformTransactionManager transactions;
    DtnPresetService service;

    @BeforeEach void setup() {
        repository.deleteAll();
        service = new DtnPresetService(repository, new ObjectMapper().findAndRegisterModules(),
                Validation.buildDefaultValidatorFactory().getValidator(), transactions);
    }
    private DtnPresetService.Settings settings() {
        HdtnConfig c = new HdtnConfig();
        c.setMaxNumberOfBundlesInPipeline(50); c.setMaxSumOfBundleBytesInPipeline(50000000L);
        c.setMaxBundleSizeBytes(10485760L); c.setTcpclMaxSegmentSizeBytes(20000);
        c.setNeighborDepletedStorageDelaySeconds(10); c.setEnforceBundlePriority(false);
        c.setStorageDeletionPolicy("DELETE_AFTER_FORWARDING"); c.setTotalStorageCapacityBytes(8589934592L);
        c.setMaxLtpReceiveUdpPacketSizeBytes(65536); c.setAcsSendPeriodMilliseconds(1000);
        return new DtnPresetService.Settings("AFS_METADATA", "HDTN", "DTN", true, c);
    }
    @Test void persistsUpdatesAndDetectsStaleVersions() {
        var first = service.save(null, new DtnPresetService.Save("  Default  ", null, settings()));
        assertEquals("Default", service.list().getFirst().name());
        assertEquals(8589934592L, service.list().getFirst().settings().hdtnConfig().getTotalStorageCapacityBytes());
        var renamed = service.save(first.id(), new DtnPresetService.Save("Renamed", first.version(), first.settings()));
        assertTrue(renamed.version() > first.version());
        assertEquals(409, assertThrows(ResponseStatusException.class, () -> service.save(first.id(),
                new DtnPresetService.Save("Stale", first.version(), first.settings()))).getStatusCode().value());
        assertThrows(ResponseStatusException.class, () -> service.delete(first.id(), first.version()));
        service.delete(renamed.id(), renamed.version()); assertTrue(service.list().isEmpty());
    }
    @Test void rejectsBadSettingsDuplicateNamesAndSixthPreset() {
        for (int i = 0; i < 5; i++) service.save(null, new DtnPresetService.Save("Preset" + i, null, settings()));
        assertThrows(ResponseStatusException.class, () -> service.save(null, new DtnPresetService.Save("sixth", null, settings())));
        assertThrows(ResponseStatusException.class, () -> service.save(null, new DtnPresetService.Save("preset0", null, settings())));
        var invalid = settings(); invalid.hdtnConfig().setMaxNumberOfBundlesInPipeline(1);
        assertEquals(400, assertThrows(ResponseStatusException.class, () -> service.save(null,
                new DtnPresetService.Save("bad", null, invalid))).getStatusCode().value());
        assertThrows(ResponseStatusException.class, () -> service.save(null, new DtnPresetService.Save(" ", null, settings())));
        assertEquals(5, repository.count());
    }
    @Test void concurrentCreatesNeverExceedCapacity() throws Exception {
        try (var pool = Executors.newFixedThreadPool(8)) {
            var start = new CountDownLatch(1);
            var futures = new ArrayList<Future<Boolean>>();
            for (int i = 0; i < 12; i++) {
                final int n = i;
                futures.add(pool.submit(() -> {
                    start.await();
                    try { service.save(null, new DtnPresetService.Save("P" + n, null, settings())); return true; }
                    catch (ResponseStatusException e) { assertEquals(409, e.getStatusCode().value()); return false; }
                }));
            }
            start.countDown();
            int saved = 0; for (var future : futures) if (future.get(20, TimeUnit.SECONDS)) saved++;
            assertEquals(5, saved); assertEquals(5, repository.count());
        }
    }
    @Test void apiValidatesWireTypesAndReceiverCannotMutate() throws Exception {
        var controller = new DtnPresetController(service);
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "role", "SENDER");
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller).build();
        var mapper = new ObjectMapper().findAndRegisterModules();
        String body = mapper.writeValueAsString(new DtnPresetService.Save("API", null, settings()));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/lnis/api/v1/dtn/presets")
                .contentType("application/json").content(body)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/lnis/api/v1/dtn/presets")
                .contentType("application/json").content(body.replace(":50,", ":50.5,")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "role", "RECEIVER");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/lnis/api/v1/dtn/presets"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        assertEquals(1, repository.count());
    }

}
