package com.example.extblock;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;
import org.springframework.util.unit.DataSize;

import com.example.extblock.config.AppProperties;

import jakarta.servlet.MultipartConfigElement;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ExtblockApplication {

	public static void main(String[] args) {
		SpringApplication.run(ExtblockApplication.class, args);
	}

	/**
	 * 요청 한도 = 파일 한도 + 1MB(multipart 경계·헤더 여유). yml은 덧셈을 못 해 빈으로 둔다.
	 * 파일 한도만 바꾸면 요청 한도가 따라온다(VERIFICATION B2). -1(무제한)은 쓰지 않는다:
	 * Tomcat max-part-count 기본 50이라 요청당 임시 디스크가 최대 50 x 파일 한도까지 커진다.
	 */
	@Bean
	MultipartConfigElement multipartConfigElement(AppProperties props) {
		long maxFile = props.upload().maxFileSize().toBytes();
		return new MultipartConfigElement("", maxFile, maxFile + DataSize.ofMegabytes(1).toBytes(), 0);
	}

}
