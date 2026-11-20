package com.streamhub.common.id;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class IdConfig {
    @Bean
    public SnowflakeIdGenerator snowflakeIdGenerator(WorkerIdLease lease) {
        return new SnowflakeIdGenerator(lease, System::currentTimeMillis);
    }
}
