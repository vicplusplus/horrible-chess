package com.horriblechess;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class HorribleChessApplication {
    public static void main(String[] args) {
        SpringApplication.run(HorribleChessApplication.class, args);
    }
}
