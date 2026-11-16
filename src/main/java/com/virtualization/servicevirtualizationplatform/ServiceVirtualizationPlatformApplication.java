package com.virtualization.servicevirtualizationplatform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ServiceVirtualizationPlatformApplication {

    public static void main(String[] args) {
        SpringApplication.run(ServiceVirtualizationPlatformApplication.class, args);
    }

}
