package com.universidad.poc.shipping;

import com.universidad.poc.common.FailureSimulator;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestClient;

@SpringBootApplication
public class ShippingApplication {

    public static void main(String[] args) {
        SpringApplication.run(ShippingApplication.class, args);
    }

    @Bean
    FailureSimulator failureSimulator() {
        return new FailureSimulator("shipping-service");
    }

    @Bean
    RestClient restClient() {
        return RestClient.create();
    }
}
