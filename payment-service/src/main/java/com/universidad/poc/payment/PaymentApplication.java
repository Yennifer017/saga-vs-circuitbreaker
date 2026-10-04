package com.universidad.poc.payment;

import com.universidad.poc.common.FailureSimulator;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestClient;

@SpringBootApplication
public class PaymentApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaymentApplication.class, args);
    }

    @Bean
    FailureSimulator failureSimulator() {
        return new FailureSimulator("payment-service");
    }

    @Bean
    RestClient restClient() {
        return RestClient.create();
    }
}
