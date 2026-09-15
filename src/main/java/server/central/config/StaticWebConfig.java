package server.central.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;

/** 기존 Nginx의 정적 asset 경로와 1시간 cache 정책을 Spring MVC로 대체한다. */
@Configuration
public class StaticWebConfig implements WebMvcConfigurer {
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry)
    {
        registry.addResourceHandler("/lnis/assets/**")
                .addResourceLocations("classpath:/static/assets/")
                .setCachePeriod(3600);
    }
    /** Keep bookmarked/cached asset URLs working after physical folder separation. */
    @Override
    public void addViewControllers(ViewControllerRegistry registry)
    {
        registry.addRedirectViewController("/lnis/assets/api.js", "/lnis/assets/common/api.js");
        registry.addRedirectViewController("/lnis/assets/app.css", "/lnis/assets/common/app.css");
        registry.addRedirectViewController("/lnis/assets/dtn-adapter-health.js", "/lnis/assets/dtn/dtn-adapter-health.js");
        registry.addRedirectViewController("/lnis/assets/dtn-log.js", "/lnis/assets/dtn/dtn-log.js");
        registry.addRedirectViewController("/lnis/assets/dtn-observations.js", "/lnis/assets/dtn/dtn-observations.js");
        registry.addRedirectViewController("/lnis/assets/dtn-payload.css", "/lnis/assets/dtn/dtn-ui.css");
        registry.addRedirectViewController("/lnis/assets/dtn-payload.js", "/lnis/assets/dtn/dtn-payload.js");
        registry.addRedirectViewController("/lnis/assets/dtn-receiver.css", "/lnis/assets/dtn/dtn-ui.css");
        registry.addRedirectViewController("/lnis/assets/dtn-receiver.js", "/lnis/assets/dtn/dtn-receiver.js");
        registry.addRedirectViewController("/lnis/assets/dtn-ui.css", "/lnis/assets/dtn/dtn-ui.css");
        registry.addRedirectViewController("/lnis/assets/dtn.js", "/lnis/assets/dtn/dtn.js");
        registry.addRedirectViewController("/lnis/assets/event-log.js", "/lnis/assets/afs/event-log.js");
        registry.addRedirectViewController("/lnis/assets/frame-evidence.js", "/lnis/assets/afs/frame-evidence.js");
        registry.addRedirectViewController("/lnis/assets/help.js", "/lnis/assets/afs/help.js");
        registry.addRedirectViewController("/lnis/assets/node-connection.js", "/lnis/assets/common/node-connection.js");
        registry.addRedirectViewController("/lnis/assets/notice.css", "/lnis/assets/afs/notice.css");
        registry.addRedirectViewController("/lnis/assets/receiver.js", "/lnis/assets/afs/receiver.js");
        registry.addRedirectViewController("/lnis/assets/sender.js", "/lnis/assets/afs/sender.js");
    }
}

