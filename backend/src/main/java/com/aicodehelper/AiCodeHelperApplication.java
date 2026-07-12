package com.aicodehelper;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class AiCodeHelperApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiCodeHelperApplication.class, args);
    }
}
