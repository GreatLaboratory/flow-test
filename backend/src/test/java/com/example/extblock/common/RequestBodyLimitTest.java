package com.example.extblock.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.web.filter.FormContentFilter;

import com.example.extblock.IntegrationTestBase;
import com.jayway.jsonpath.JsonPath;

/**
 * SECURITY_REVIEW S1: 업로드 외 요청 본문은 16KB(16384바이트)에서 끊는다. 실제 Tomcat에 보낸다.
 * 크기를 수백 MB로 키운 OOM 재현은 테스트가 아니라 SECURITY_REVIEW §9의 jar 공격 기록에 있다.
 */
class RequestBodyLimitTest extends IntegrationTestBase {

	static final int LIMIT = 16 * 1024;

	@Autowired
	ApplicationContext context;

	/** 정확히 size바이트인 정상 JSON {"name":"sh"   ...   } — 공백은 Jackson이 끝까지 읽어야 하는 본문이다 */
	static String paddedJson(int size) {
		String head = "{\"name\":\"sh\"";
		return head + " ".repeat(size - head.length() - 1) + "}";
	}

	static String code(HttpResponse<String> res) {
		return JsonPath.read(res.body(), "$.code");
	}

	@Test // R1 PUT/DELETE urlencoded 본문을 rate limit보다 먼저 통째로 읽던 필터가 없다
	void formContentFilterDisabled() {
		assertThat(context.getBeansOfType(FormContentFilter.class)).isEmpty();
	}

	@Test // R2 경계: 정확히 16384바이트는 정상 처리
	void bodyAtLimitAccepted() {
		HttpResponse<String> res = json("POST", "/api/extensions/custom", paddedJson(LIMIT));
		assertThat(res.statusCode()).isEqualTo(201);
		assertThat(customCount()).isEqualTo(1);
	}

	@Test // R3 경계: 16385바이트는 400, 아무것도 저장되지 않음
	void bodyOverLimitRejected() {
		HttpResponse<String> res = json("POST", "/api/extensions/custom", paddedJson(LIMIT + 1));
		assertThat(res.statusCode()).isEqualTo(400);
		assertThat(code(res)).isEqualTo("INVALID_REQUEST");
		assertThat(customCount()).isZero();
	}

	@Test // R4 Content-Length 없는 chunked 본문도 실제 읽은 양으로 끊는다
	void chunkedBodyOverLimitRejected() {
		byte[] body = paddedJson(1024 * 1024).getBytes(StandardCharsets.UTF_8);
		HttpResponse<String> res = send(HttpRequest.newBuilder(uri("/api/extensions/custom"))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(body)))
				.build());
		assertThat(res.statusCode()).isEqualTo(400);
		assertThat(code(res)).isEqualTo("INVALID_REQUEST");
		assertThat(customCount()).isZero();
	}

	@Test // R5 PUT도 같은 상한 (고정 체크 변경), 상태 변하지 않음
	void putOverLimitRejected() {
		String body = "{\"blocked\":true" + " ".repeat(LIMIT) + "}";
		HttpResponse<String> res = json("PUT", "/api/extensions/fixed/" + fixedId("exe"), body);
		assertThat(res.statusCode()).isEqualTo(400);
		assertThat(auditActions()).isEmpty();
	}

	@Test // R6 업로드는 이 상한 밖(multipart 한도 10MB가 담당)
	void uploadNotLimitedByBodyCap() {
		assertThat(upload("big.bin", new byte[LIMIT * 8]).statusCode()).isEqualTo(201);
	}

}
