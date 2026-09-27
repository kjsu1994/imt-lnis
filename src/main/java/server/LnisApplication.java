package server;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;

import server.config.CentralModeConfiguration;
import server.config.NodeModeConfiguration;
import server.config.RunMode;

/** 같은 Boot JAR을 중앙 서버, Sender 또는 Receiver로 시작하는 유일한 진입점이다. */
@SpringBootConfiguration
@EnableAutoConfiguration
@Import({CentralModeConfiguration.class, NodeModeConfiguration.class})
public class LnisApplication {
    public static void main(String[] args) throws InterruptedException {
        RunMode.Selection selection = RunMode.select(args);
        RunMode mode = selection.mode();
        ConfigurableApplicationContext context =
                new SpringApplicationBuilder(LnisApplication.class)
                        .web(
                                mode.webEnabled()
                                        ? WebApplicationType.SERVLET
                                        : WebApplicationType.NONE)
                        .profiles(mode.profiles())
                        .run(selection.springArguments());
    }
}
