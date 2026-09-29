package server.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:lnis-web;DB_CLOSE_DELAY=-1",
            "spring.jpa.hibernate.ddl-auto=create-drop",
            "lnis.storage.data-directory=${java.io.tmpdir}/lnis-web-tests",
            "lnis.storage.cleanup-delay=PT24H"
        })
@ActiveProfiles("server")
@AutoConfigureMockMvc
class WebPageControllerTest {
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    server.node.LocalNodeLifecycle localRuntime;

    @Autowired private MockMvc mvc;

    @Test
    void developmentExampleIsOffByDefault() throws Exception {
        mvc.perform(get("/lnis/api/v1/dtn/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exampleEnabled").value(false));
        mvc.perform(get("/lnis/api/v1/dtn/example/file")).andExpect(status().isNotFound());
    }

    @Test
    void legacyAssetsRedirectToResolvableModulesAndStyles() throws Exception {
        var aliases =
                java.util.Map.ofEntries(
                        java.util.Map.entry("app.css", "common/app.css"),
                        java.util.Map.entry("dtn.js", "dtn/dtn.js"),
                        java.util.Map.entry("dtn-payload.css", "dtn/dtn-ui.css"),
                        java.util.Map.entry("dtn-receiver.css", "dtn/dtn-ui.css"),
                        java.util.Map.entry("dtn-adapter-health.js", "dtn/dtn-adapter-health.js"));
        for (var alias : aliases.entrySet()) {
            mvc.perform(get("/lnis/assets/" + alias.getKey()))
                    .andExpect(status().isFound())
                    .andExpect(redirectedUrl("/lnis/assets/" + alias.getValue()));
            mvc.perform(get("/lnis/assets/" + alias.getValue())).andExpect(status().isOk());
        }
        mvc.perform(get("/lnis/dtntest/sender/clear")).andExpect(status().isOk());
        mvc.perform(get("/lnis/dtntest/receiver/clear")).andExpect(status().isOk());
    }

    @Test
    void servesNewAndLegacyPagesWithoutNginx() throws Exception {
        mvc.perform(get("/"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/lnis/dtntest/sender"));
        for (String role : java.util.List.of("sender", "receiver")) {
            for (String url :
                    java.util.List.of(
                            "/lnis/afstest/" + role,
                            "/lnis/test/" + role,
                            "/afs-" + role + ".html")) {
                mvc.perform(get(url))
                        .andExpect(status().isFound())
                        .andExpect(redirectedUrl("/lnis/dtntest/" + role));
            }
        }
        for (String url : java.util.List.of("/lnis/data-manager", "/lnis/data-management")) {
            mvc.perform(get(url))
                    .andExpect(status().isOk())
                    .andExpect(forwardedUrl("/data-management.html"));
        }
        mvc.perform(get("/dtn-intro"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/dtn-intro.html"));
        mvc.perform(get("/dtn-intro.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("지연·PVT 비교")));
        mvc.perform(get("/lnis/dtntest/sender")).andExpect(status().isOk());
        mvc.perform(get("/lnis/dtntest/receiver"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/dtn-receiver.html"));
        mvc.perform(get("/dtn-sender.html"))
                .andExpect(status().isOk())
                .andExpect(
                        content()
                                .string(
                                        org.hamcrest.Matchers.containsString(
                                                "/lnis/dtntest/receiver")));
        mvc.perform(get("/dtn-receiver.html"))
                .andExpect(status().isOk())
                .andExpect(
                        content()
                                .string(
                                        org.hamcrest.Matchers.containsString(
                                                "/lnis/dtntest/sender")));
        mvc.perform(get("/lnis/assets/dtn-receiver.js"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/lnis/assets/dtn/dtn-receiver.js"));
        mvc.perform(get("/lnis/assets/dtn/dtn-receiver.js")).andExpect(status().isOk());
        mvc.perform(get("/lnis/api/v1/dtn/tests"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
        for (String asset :
                java.util.List.of(
                        "api.js",
                        "common/api.js",
                        "sender.js",
                        "receiver.js",
                        "afs/sender.js",
                        "afs/receiver.js")) {
            mvc.perform(get("/lnis/assets/" + asset)).andExpect(status().isNotFound());
        }
        for (String api :
                java.util.List.of(
                        "sessions/active",
                        "logs/screen",
                        "node/peer/afs/sessions/" + java.util.UUID.randomUUID())) {
            mvc.perform(get("/lnis/api/v1/" + api)).andExpect(status().isNotFound());
        }
        mvc.perform(get("/lnis/api/v1/discovery")).andExpect(status().isNotFound());
    }
}
