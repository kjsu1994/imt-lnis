package server.config;

import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 중앙 서버 모드에서만 REST, WebSocket, JPA와 정리 스케줄러를 활성화한다. */
@Configuration(proxyBeanMethods = false)
@Profile("server")
@EnableScheduling
@ComponentScan(basePackages = "server")
@EntityScan(basePackages = "server")
@EnableJpaRepositories(basePackages = "server")
public class CentralModeConfiguration {}
