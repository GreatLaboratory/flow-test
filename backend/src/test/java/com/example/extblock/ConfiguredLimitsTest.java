package com.example.extblock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;

import java.net.http.HttpResponse;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.example.extblock.policy.ExtensionRepository;
import com.jayway.jsonpath.JsonPath;

/**
 * 한도를 설정으로 바꾼 별도 컨텍스트. DESIGN §9 "한도 값은 AppProperties 한 곳" 기준.
 * P21 GET이 설정값을 따름 / P22 (§7-9) 이미 초과한 상태 / U18 (§7-10, A-22) 파일 크기 한도 변경 / P23 (§7-8) UNIQUE 최종 방어선
 */
@TestPropertySource(properties = { "app.policy.custom-limit=3", "app.upload.max-file-size=12MB" })
class ConfiguredLimitsTest extends IntegrationTestBase {

	@MockitoSpyBean
	ExtensionRepository repository;

	@Test // P21
	void getExposesConfiguredLimits() {
		HttpResponse<String> res = json("GET", "/api/extensions", null);
		assertThat((Integer) JsonPath.read(res.body(), "$.customLimit")).isEqualTo(3);
		assertThat(((Number) JsonPath.read(res.body(), "$.maxFileSize")).longValue()).isEqualTo(12L * 1024 * 1024);
	}

	@Test // P22 한도를 낮췄는데 이미 5개 → 추가 409, 삭제 204
	void loweredLimitWithExistingRows() {
		for (int i = 1; i <= 5; i++) {
			insertCustom("x" + i);
		}
		HttpResponse<String> add = json("POST", "/api/extensions/custom", "{\"name\":\"y\"}");
		assertThat(add.statusCode()).isEqualTo(409);
		assertThat((String) JsonPath.read(add.body(), "$.code")).isEqualTo("CUSTOM_LIMIT_EXCEEDED");
		long id = jdbc.sql("SELECT id FROM extension WHERE name='x1'").query(Long.class).single();
		assertThat(json("DELETE", "/api/extensions/custom/" + id, null).statusCode()).isEqualTo(204);
		assertThat(customCount()).isEqualTo(4);
	}

	@Test // U18 한도 12MB면 11.5MB는 성공해야 한다(메시지·maxFileSize·실제 거절 지점 일치)
	void raisedFileSizeLimitIsHonored() {
		HttpResponse<String> res = upload("big.bin", new byte[11 * 1024 * 1024 + 512 * 1024]);
		assertThat(res.statusCode()).as(res.body()).isEqualTo(201);
	}

	@Test // U18' 12MB+1은 거절. 응답이 도달하면 413 + 12MB 안내, 아니면 Tomcat 연결 끊김(§6-4가 예고한 현상)
	void overRaisedLimitRejected() throws java.io.IOException {
		HttpResponse<String> res;
		try {
			res = upload("big.bin", new byte[12 * 1024 * 1024 + 1]);
		}
		catch (java.io.UncheckedIOException connectionAborted) {
			assertThat(storedFiles()).isEmpty();
			return;
		}
		assertThat(res.statusCode()).isEqualTo(413);
		assertThat((String) JsonPath.read(res.body(), "$.message")).contains("12MB");
	}

	@Test // P23 사전 조회가 놓친 중복도 UNIQUE → 409 (잠금 아래에선 API로 도달 불가해 spy로 재현)
	void uniqueConstraintIsLastLine() {
		insertCustom("sh");
		doReturn(Optional.empty()).when(repository).findByName(eq("sh"));
		HttpResponse<String> res = json("POST", "/api/extensions/custom", "{\"name\":\"sh\"}");
		assertThat(res.statusCode()).isEqualTo(409);
		assertThat((String) JsonPath.read(res.body(), "$.code")).isEqualTo("DUPLICATE_EXTENSION");
		assertThat(auditActions()).isEmpty();
	}

}
