package com.universidad.poc.eventbus;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestClient;

/**
 * Event Bus minimo (broker HTTP publish/subscribe).
 */
@SpringBootApplication
public class EventBusApplication {

    public static void main(String[] args) {
        SpringApplication.run(EventBusApplication.class, args);
    }

    @Bean
    RestClient restClient() {
        return RestClient.create();
    }
}
