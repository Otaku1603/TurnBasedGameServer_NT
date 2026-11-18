package com.game.nt;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 模块化单体启动类：一级子包即领域模块，模块间只经公开 API 或领域事件通信。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
@EnableAsync
public class NtApplication {

    public static void main(String[] args) {
        SpringApplication.run(NtApplication.class, args);
    }
}
