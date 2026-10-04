package com.universidad.poc.inventory;

import com.universidad.poc.common.FailureSimulator;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestClient;

@SpringBootApplication
public class InventoryApplication {

    public static void main(String[] args) {
        SpringApplication.run(InventoryApplication.class, args);
    }

    @Bean
    FailureSimulator failureSimulator() {
        return new FailureSimulator("inventory-service");
    }

    @Bean
    RestClient restClient() {
        return RestClient.create();
    }
}
