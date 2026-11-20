package com.streamhub;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 启动入口。
 * EnableScheduling：workerId 续期、在线人数推送要用定时任务。
 * ConfigurationPropertiesScan：把 application.yml 里 streamhub.* 绑定到 StreamhubProperties。
 */
@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
public class StreamhubApplication {
    public static void main(String[] args) {
        SpringApplication.run(StreamhubApplication.class, args);
    }
}
