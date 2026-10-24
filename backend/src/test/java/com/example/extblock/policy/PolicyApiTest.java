package com.example.extblock.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import com.example.extblock.IntegrationTestBase;
import com.jayway.jsonpath.JsonPath;

/** DESIGN §5 / §6-3 / §12 T3 기준. */
class PolicyApiTest extends IntegrationTestBase {

	ResultActions addCustom(String name) throws Exception {
		return mvc.perform(post("/api/extensions/custom").contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"" + name.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}"));
	}

	long addCustomId(String name) throws Exception {
		String body = addCustom(name).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		return ((Number) JsonPath.read(body, "$.id")).longValue();
	}

	ResultActions putFixed(long id, String body) throws Exception {
		return mvc.perform(put("/api/extensions/fixed/" + id).contentType(MediaType.APPLICATION_JSON).content(body));
	}

	@Test // P1 기본값 unCheck, 고정은 커스텀 영역에 없음
	void initialState() throws Exception {
		mvc.perform(get("/api/extensions"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.fixed.length()").value(7))
				.andExpect(jsonPath("$.fixed[*].name").value(org.hamcrest.Matchers.containsInAnyOrder(
						"bat", "cmd", "com", "cpl", "exe", "scr", "js")))
				.andExpect(jsonPath("$.fixed[?(@.blocked == true)]").isEmpty())
				.andExpect(jsonPath("$.custom").isEmpty())
				.andExpect(jsonPath("$.customLimit").value(200))
				.andExpect(jsonPath("$.maxFileSize").value(10 * 1024 * 1024));
	}

	@Test // P2 정규화된 값으로 저장·응답, 감사 1행
	void addNormalizes() throws Exception {
		addCustom(".SH ").andExpect(status().isCreated())
				.andExpect(jsonPath("$.name").value("sh"))
				.andExpect(jsonPath("$.id").isNumber())
				.andExpect(jsonPath("$.createdAt").exists());
		mvc.perform(get("/api/extensions")).andExpect(jsonPath("$.custom[0].name").value("sh"));
		assertThat(auditActions()).containsExactly("CUSTOM_ADD");
	}

	@ParameterizedTest // P3 정규화 후 고정과 충돌
	@ValueSource(strings = { "exe", "EXE", " .Exe", "js" })
	void fixedConflict(String raw) throws Exception {
		addCustom(raw).andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("FIXED_EXTENSION_CONFLICT"))
				.andExpect(jsonPath("$.message").isString());
		assertThat(customCount()).isZero();
		assertThat(auditActions()).isEmpty();
	}

	@ParameterizedTest // P4
	@ValueSource(strings = { "sh", "SH", ".sh" })
	void duplicate(String raw) throws Exception {
		addCustomId("sh");
		addCustom(raw).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_EXTENSION"));
		assertThat(customCount()).isEqualTo(1);
		assertThat(auditActions()).containsExactly("CUSTOM_ADD");
	}

	@Test // P5 200번째 201, 201번째 409 (API로 200회)
	void limit200() throws Exception {
		for (int i = 1; i <= 199; i++) {
			addCustom("x" + i).andExpect(status().isCreated());
		}
		addCustom("x200").andExpect(status().isCreated());
		addCustom("x201").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CUSTOM_LIMIT_EXCEEDED"));
		assertThat(customCount()).isEqualTo(200);
	}

	@Test // P6 상한 상태에서도 이름 검사가 먼저 / P7 하나 지우면 다시 추가 / P8 고정은 상한과 무관
	void atLimit() throws Exception {
		for (int i = 1; i <= 199; i++) {
			insertCustom("x" + i);
		}
		long last = addCustomId("x200");
		addCustom("x1").andExpect(jsonPath("$.code").value("DUPLICATE_EXTENSION"));
		addCustom("exe").andExpect(jsonPath("$.code").value("FIXED_EXTENSION_CONFLICT"));
		putFixed(fixedId("exe"), "{\"blocked\":true}").andExpect(status().isOk());

		mvc.perform(delete("/api/extensions/custom/" + last)).andExpect(status().isNoContent());
		addCustom("y1").andExpect(status().isCreated());
	}

	@Test // P9 형식 오류는 400, §5-1 형식
	void badAddRequests() throws Exception {
		addCustom("a".repeat(21)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_EXTENSION"));
		addCustom("s h").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_EXTENSION"));
		// {"name":123}은 Jackson scalar coercion으로 "123"이 되어 201 — 스펙 미규정·무해, 관찰로 기록(VERIFICATION.md)
		for (String body : new String[] { "{", "{}", "{\"name\":null}", "[]" }) {
			var res = mvc.perform(post("/api/extensions/custom").contentType(MediaType.APPLICATION_JSON).content(body))
					.andReturn().getResponse();
			assertThat(res.getStatus()).as(body).isEqualTo(400);
			assertThat(JsonPath.<String>read(res.getContentAsString(), "$.code")).as(body).isIn("INVALID_EXTENSION", "INVALID_REQUEST");
		}
		assertThat(customCount()).isZero();
		assertThat(auditActions()).isEmpty();
	}

	@Test // P10 type·blocked 클라이언트 값 무시
	void clientTypeIgnored() throws Exception {
		mvc.perform(post("/api/extensions/custom").contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"zz\",\"type\":\"FIXED\",\"blocked\":false}")).andExpect(status().isCreated());
		assertThat(jdbc.sql("SELECT type || ':' || blocked FROM extension WHERE name='zz'").query(String.class).single())
				.isEqualTo("CUSTOM:true");
	}

	@Test // P11 같은 상태 반복 → 감사 1행 / P12 false→true→false → 2행, GET에 유지
	void fixedToggleAudit() throws Exception {
		long exe = fixedId("exe");
		putFixed(exe, "{\"blocked\":true}").andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(exe)).andExpect(jsonPath("$.name").value("exe")).andExpect(jsonPath("$.blocked").value(true));
		putFixed(exe, "{\"blocked\":true}").andExpect(status().isOk()).andExpect(jsonPath("$.blocked").value(true));
		assertThat(auditActions()).containsExactly("FIXED_BLOCK");
		mvc.perform(get("/api/extensions")).andExpect(jsonPath("$.fixed[?(@.name=='exe')].blocked").value(true));

		putFixed(exe, "{\"blocked\":false}").andExpect(status().isOk()).andExpect(jsonPath("$.blocked").value(false));
		assertThat(auditActions()).containsExactly("FIXED_BLOCK", "FIXED_UNBLOCK");
		mvc.perform(get("/api/extensions")).andExpect(jsonPath("$.fixed[?(@.name=='exe')].blocked").value(false));
	}

	@Test // P13 커스텀 id / 없는 id → 404
	void putNotFound() throws Exception {
		long sh = addCustomId("sh");
		putFixed(sh, "{\"blocked\":false}").andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("EXTENSION_NOT_FOUND"));
		putFixed(999_999, "{\"blocked\":true}").andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("EXTENSION_NOT_FOUND"));
		assertThat(jdbc.sql("SELECT blocked FROM extension WHERE id = ?").param(sh).query(Boolean.class).single()).isTrue();
	}

	@Test // P14 잘못된 PUT → 400
	void badPut() throws Exception {
		long exe = fixedId("exe");
		for (String body : new String[] { "{}", "{\"blocked\":\"yes\"}", "{\"blocked\":null}", "{" }) {
			putFixed(exe, body).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
		}
		mvc.perform(put("/api/extensions/fixed/abc").contentType(MediaType.APPLICATION_JSON).content("{\"blocked\":true}"))
				.andExpect(status().isBadRequest());
		assertThat(auditActions()).isEmpty();
	}

	@Test // P15 고정 id로 DELETE → 204, 행 유지, 감사 없음
	void deleteFixedIdIgnored() throws Exception {
		mvc.perform(delete("/api/extensions/custom/" + fixedId("exe"))).andExpect(status().isNoContent());
		assertThat(jdbc.sql("SELECT count(*) FROM extension WHERE type='FIXED'").query(Integer.class).single()).isEqualTo(7);
		assertThat(auditActions()).isEmpty();
	}

	@Test // P16 DELETE 멱등
	void deleteIdempotent() throws Exception {
		long sh = addCustomId("sh");
		mvc.perform(delete("/api/extensions/custom/" + sh)).andExpect(status().isNoContent());
		mvc.perform(delete("/api/extensions/custom/" + sh)).andExpect(status().isNoContent());
		assertThat(customCount()).isZero();
		assertThat(auditActions()).containsExactly("CUSTOM_ADD", "CUSTOM_DELETE");
	}

	@Test // P17 오래된 탭
	void staleTabDeleteKeepsNewRow() throws Exception {
		long a = addCustomId("sh");
		mvc.perform(delete("/api/extensions/custom/" + a)).andExpect(status().isNoContent());
		long b = addCustomId("sh");
		mvc.perform(delete("/api/extensions/custom/" + a)).andExpect(status().isNoContent());
		assertThat(jdbc.sql("SELECT id FROM extension WHERE name='sh'").query(Long.class).single()).isEqualTo(b);
	}

	@ParameterizedTest // P18 DB 제약(앱 우회 직접 INSERT)
	@ValueSource(strings = {
			"INSERT INTO extension (name, type, blocked) VALUES ('EXE', 'CUSTOM', true)",
			"INSERT INTO extension (name, type, blocked) VALUES ('zz', 'CUSTOM', false)",
			"INSERT INTO extension (name, type, blocked) VALUES ('aaaaaaaaaaaaaaaaaaaaa', 'CUSTOM', true)",
			"INSERT INTO extension (name, type, blocked) VALUES ('exe', 'CUSTOM', true)",
			"INSERT INTO extension (name, type, blocked) VALUES ('t.gz', 'CUSTOM', true)",
			"INSERT INTO extension (name, type, blocked) VALUES ('zz', 'OTHER', true)" })
	void dbConstraints(String sql) {
		org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.sql(sql).update())
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test // P19 감사 추적 단서, UA 512자 절단
	void auditClientInfo() throws Exception {
		mvc.perform(post("/api/extensions/custom").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"sh\"}")
				.header("User-Agent", "u".repeat(600)).with(r -> { r.setRemoteAddr("10.1.2.3"); return r; }))
				.andExpect(status().isCreated());
		var row = jdbc.sql("SELECT client_ip, user_agent, extension_name FROM policy_audit").query().singleRow();
		assertThat(row.get("client_ip")).isEqualTo("10.1.2.3");
		assertThat((String) row.get("user_agent")).hasSize(512);
		assertThat(row.get("extension_name")).isEqualTo("sh");
	}

	@Test // P20 거부된 요청은 DB·감사 무변화 (400/404/409 혼합)
	void rejectedRequestsLeaveNoTrace() throws Exception {
		addCustom("exe");
		addCustom("s h");
		putFixed(999_999, "{\"blocked\":true}");
		assertThat(customCount()).isZero();
		assertThat(auditActions()).isEmpty();
	}

}
