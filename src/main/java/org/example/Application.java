package org.example;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import org.example.config.CorsConfig;
import org.example.controller.*;
import org.example.service.*;

@SpringBootApplication
@Import({
    PingController.class,
    UploadController.class,
    TableController.class,
    QueryController.class,
    S3Service.class,
    DynamoService.class,
    SqsService.class,
    CorsConfig.class
})
public class Application {

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
