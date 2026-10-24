package com.example.extblock.config;

import java.nio.file.Path;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * 한도·경로 설정의 유일한 출처. 환경변수(APP_UPLOAD_MAX_FILE_SIZE 등)로 덮어쓴다.
 * customLimit, maxFileSize는 GET /api/extensions 응답으로 프론트에 전달된다.
 */
@ConfigurationProperties("app")
public record AppProperties(Upload upload, Policy policy, RateLimit rateLimit) {

	public record Upload(DataSize maxFileSize, Path storageDir) {
	}

	public record Policy(int customLimit) {
	}

	public record RateLimit(int policyPerMinute, int uploadPerMinute) {
	}

}
