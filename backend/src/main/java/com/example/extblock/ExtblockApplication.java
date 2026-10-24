package com.example.extblock;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ExtblockApplication {

	public static void main(String[] args) {
		SpringApplication.run(ExtblockApplication.class, args);
	}

}
