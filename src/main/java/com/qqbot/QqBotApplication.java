package com.qqbot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.PropertySource;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@PropertySource(value = "file:./.env", encoding = "UTF-8", ignoreResourceNotFound = true)
@ConfigurationPropertiesScan
@EnableScheduling
public class QqBotApplication {

    public static void main(String[] args) {
        SpringApplication.run(QqBotApplication.class, args);
    }
}
