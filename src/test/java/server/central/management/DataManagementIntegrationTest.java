package server.central.management;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import jakarta.persistence.EntityManager;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import server.central.dtn.*;
import server.central.input.*;
import server.central.config.StorageCleanupService;
import server.shared.model.LnisModels.*;
import static server.central.management.DataManagementService.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:data-management;DB_CLOSE_DELAY=-1","spring.jpa.hibernate.ddl-auto=create-drop","lnis.storage.cleanup-delay=PT24H"})
@ActiveProfiles("server") @AutoConfigureMockMvc
class DataManagementIntegrationTest {
    static final Path directory=temp();
    static Path temp(){try{return Files.createTempDirectory("lnis-management-test-");}catch(Exception e){throw new RuntimeException(e);}}
    @DynamicPropertySource static void paths(DynamicPropertyRegistry p){p.add("lnis.storage.data-directory",()->directory.toString());p.add("lnis.iq.directory",()->directory.resolve("iq").toString());}
    @Autowired DataManagementService management;
    @Autowired DataManagementGuard guard;
    @Autowired DtnRepository jobs;
    @Autowired DtnService dtn;
    @Autowired DtnReceiptService receipts;
    @Autowired DtnLogService logs;
    @Autowired InputBufferService inputs;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired StorageCleanupService oldCleanup;
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager tm;
    @Autowired MockMvc mvc;
    @MockitoSpyBean GrawFileStorage files;
    final Instant old=Instant.now().minus(Duration.ofDays(100));
    void tx(Runnable action){new TransactionTemplate(tm).executeWithoutResult(s->action.run());}
    DtnJob job(UUID input){DtnJob j=new DtnJob();j.setId(UUID.randomUUID());j.setInputId(input);j.setState("COMPLETED");j.setTestType("AFS_METADATA");j.setCreatedAt(old);j.setUpdatedAt(old);return jobs.saveAndFlush(j);}
    Key key(DtnJob job){return new Key(Kind.DTN,job.getId());}
    Operation remove(Key key){return management.execute(management.preview(List.of(key)).token(),"TEST");}
    @BeforeEach void clear(){tx(()->{for(String entity:List.of("DtnReceipt","DtnLogEntry","DtnJob","ManagementEntry"))em.createQuery("delete from "+entity).executeUpdate();});}

    @Test void defaultsKeepDtnAndArchiveTablesWithoutExposingBodies() throws Exception {
        var j=job(null);j.setSentJson("PRIVATE_PAYLOAD_MARKER");jobs.saveAndFlush(j);
        UUID afs=UUID.randomUUID();
        jdbc.execute("create table if not exists test_session (session_id uuid primary key, request_json clob)");
        jdbc.update("insert into test_session(session_id,request_json) values (?,?)",afs,"archived-afs");
        assertFalse(management.settings().tests().enabled());oldCleanup.cleanup();management.cleanup();
        assertTrue(jobs.existsById(j.getId()));
        mvc.perform(get("/lnis/api/v1/data-management/items").param("kind","DTN")).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("PRIVATE_PAYLOAD_MARKER"))));
        mvc.perform(get("/lnis/api/v1/data-management/items").param("kind","AFS")).andExpect(status().isGone());
        assertThrows(RuntimeException.class,()->management.preview(List.of(new Key(Kind.AFS,afs))));
        assertFalse(((Map<?,?>)management.summary().get("counts")).containsKey("AFS"));
        assertEquals("archived-afs",jdbc.queryForObject("select request_json from test_session where session_id=?",String.class,afs));
    }
    @Test void oldAfsManagementHistoryIsReadableButCannotBeReplayed() throws Exception {
        var json=new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
        var key=new Key(Kind.AFS,UUID.randomUUID());
        var row=new Row(key,"legacy","COMPLETED",old,old,0,false,"");
        var preview=new Preview(UUID.randomUUID(),"sender",Instant.now().plusSeconds(300),
                List.of(new PlanItem(row,List.of(),0,0,0,"")));
        var operation=new Operation(UUID.randomUUID(),old,"MANUAL",List.of(new Result(key,"FAILED","old failure")));
        String operationJson=json.writeValueAsString(operation), previewJson=json.writeValueAsString(preview);
        tx(()->{
            em.persist(new ManagementEntry("OP:"+operation.id(),"OP",operationJson));
            em.persist(new ManagementEntry("PREVIEW:"+preview.token(),"PREVIEW",previewJson));
        });
        assertEquals(key,management.history().getFirst().results().getFirst().key());
        assertThrows(RuntimeException.class,()->management.retry(operation.id()));
        assertThrows(RuntimeException.class,()->management.execute(preview.token(),"TEST"));
        assertFalse(guard.deleted("AFS",key.id()));
    }
    @Test void sharedInputPinsLateCallbacksAndPendingTrialsAreProtected() throws Exception {
        var input=inputs.create("shared.graw",3,InputKind.GRAW_UPLOAD);inputs.append(input.inputId(),0,new byte[]{1,2,3});
        var first=job(input.inputId());var second=job(input.inputId());
        assertTrue(management.preview(List.of(key(first))).items().getFirst().related().isEmpty());
        management.pin(key(first),true);assertThrows(RuntimeException.class,()->inputs.remove(input.inputId()));assertEquals("FAILED",remove(key(first)).results().getFirst().status());
        management.pin(key(first),false);assertEquals("DELETED",remove(key(first)).results().getFirst().status());
        assertNotNull(inputs.get(input.inputId()));
        second.setCancelPending(true);jobs.saveAndFlush(second);
        assertThrows(RuntimeException.class,()->remove(key(second)));
        second.setCancelPending(false);jobs.saveAndFlush(second);
        var body=("{\"testId\":\""+first.getId()+"\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertThrows(RuntimeException.class,()->receipts.capture(body,"application/json",false));
        assertTrue(management.preview(List.of(key(second))).items().getFirst().related().contains(new Key(Kind.INPUT,input.inputId())));
        assertEquals("DELETED",remove(key(second)).results().getFirst().status());
        assertThrows(RuntimeException.class,()->inputs.get(input.inputId()));
    }
    @Test void failedFileDeletionCanBeRetriedWithoutDeletingTrialEarly() {
        var input=inputs.create("retry.graw",3,InputKind.GRAW_UPLOAD);inputs.append(input.inputId(),0,new byte[]{1,2,3});
        var j=job(input.inputId());logs.add(j.getId(),"TEST","TEST",false,"retained until delete succeeds");
        doThrow(new IllegalStateException("simulated filesystem failure")).when(files).delete(input.inputId());
        var failed=remove(key(j));assertEquals("FAILED",failed.results().getFirst().status());assertTrue(jobs.existsById(j.getId()));assertNotNull(inputs.get(input.inputId()));
        doCallRealMethod().when(files).delete(input.inputId());
        var retried=management.execute(management.retry(failed.id()).token(),"TEST_RETRY");assertEquals("DELETED",retried.results().getFirst().status());
        assertFalse(jobs.existsById(j.getId()));assertTrue(logs.read(j.getId(),0).isEmpty());
        assertTrue(guard.deleted("DTN",j.getId()));
    }
    @Test void policyNeedsPreviewPersistsAndOnlyDeletesExpiredUnpinnedData() throws Exception {
        var expired=job(null);var fixed=job(null);management.pin(key(fixed),true);
        var recent=job(null);recent.setUpdatedAt(Instant.now());jobs.saveAndFlush(recent);
        var proposed=new Settings(new Policy(true,30),new Policy(false,30),new Policy(false,30));
        var preview=management.previewPolicy(proposed);
        assertTrue(preview.preview().items().stream().anyMatch(i->i.row().key().equals(key(expired))));
        assertFalse(preview.preview().items().stream().anyMatch(i->i.row().key().equals(key(fixed)) || i.row().key().equals(key(recent))));
        management.savePolicy(preview.preview().token());em.clear();assertEquals(proposed,management.settings());
        management.cleanup();assertFalse(jobs.existsById(expired.getId()));assertTrue(jobs.existsById(fixed.getId()));assertTrue(jobs.existsById(recent.getId()));
        assertThrows(IllegalArgumentException.class,()->new Policy(true,0));
        assertThrows(RuntimeException.class,()->management.execute(UUID.randomUUID(),"TEST"));
        Files.createDirectories(directory.resolve("iq"));UUID id=UUID.randomUUID();Path bin=directory.resolve("iq").resolve(id+".bin");Files.write(bin,new byte[]{1,2,3});
        assertTrue(management.list(Kind.IQ,0,id.toString(),"",null,null).items().stream().anyMatch(r->r.bytes()==3));
        management.pin(new Key(Kind.IQ,id),true);assertThrows(RuntimeException.class,()->dtn.deleteIq(id));management.pin(new Key(Kind.IQ,id),false);
        assertEquals("DELETED",remove(new Key(Kind.IQ,id)).results().getFirst().status());assertFalse(Files.exists(bin));
    }
    @Test void receiverAllowsOnlyDedicatedManagementMutations() throws Exception {
        var properties=new server.central.node.NodeProperties(new org.springframework.mock.env.MockEnvironment().withProperty("lnis.node.role","receiver"));
        var filter=new server.central.node.NodeRoleFilter(properties);
        var response=new org.springframework.mock.web.MockHttpServletResponse();
        var reached=new java.util.concurrent.atomic.AtomicBoolean();
        filter.doFilter(new org.springframework.mock.web.MockHttpServletRequest("POST","/lnis/api/v1/data-management/preview"),response,(a,b)->reached.set(true));
        assertTrue(reached.get());
        reached.set(false);response=new org.springframework.mock.web.MockHttpServletResponse();
        filter.doFilter(new org.springframework.mock.web.MockHttpServletRequest("POST","/lnis/api/v1/dtn/tests"),response,(a,b)->reached.set(true));
        assertFalse(reached.get());assertEquals(409,response.getStatus());
    }
    @Test void deletionWaitsForOngoingRequestAndDeletesLinkedReceiptTogether() throws Exception {
        var j=job(null);
        receipts.capture(("{\"testId\":\""+j.getId()+"\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8),"application/json",false);
        var preview=management.preview(List.of(key(j)));assertEquals(1,preview.items().getFirst().receipts());
        var held=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        try(var executor=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var reader=executor.submit(()->{var lock=guard.gate.readLock();lock.lock();try{held.countDown();release.await();}finally{lock.unlock();}return null;});
            assertTrue(held.await(3,java.util.concurrent.TimeUnit.SECONDS));
            var deletion=executor.submit(()->management.execute(preview.token(),"TEST"));
            try {assertThrows(java.util.concurrent.TimeoutException.class,()->deletion.get(100,java.util.concurrent.TimeUnit.MILLISECONDS));}
            finally {release.countDown();}
            assertEquals("DELETED",deletion.get(10,java.util.concurrent.TimeUnit.SECONDS).results().getFirst().status());reader.get();
        }finally {release.countDown();}
        assertEquals(0,management.list(Kind.RECEIPT,0,"","",null,null).total());
    }

}
