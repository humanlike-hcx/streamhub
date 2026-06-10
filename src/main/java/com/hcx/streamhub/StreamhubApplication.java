package com.hcx.streamhub;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class StreamhubApplication {

	public static void main(String[] args) {
		SpringApplication.run(StreamhubApplication.class, args);
	}

}
