package com.leash;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class LeashApplication {
    public static void main(String[] args) {
        SpringApplication.run(LeashApplication.class, args);
    }

    @Bean
    ObjectMapper objectMapper() {
        return Json.MAPPER;
    }
}
