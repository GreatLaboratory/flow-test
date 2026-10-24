package com.example.extblock;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Postgres 컨테이너 1개를 모든 통합 테스트가 공유한다(H2는 LOCK TABLE 모드 미지원 — DESIGN §2).
 * 업로드는 Spring multipart 파서·크기 제한이 실제로 동작해야 하므로 MockMvc가 아니라 실제 Tomcat(RANDOM_PORT)에 HTTP로 보낸다.
 * rate limit은 보안 세션 몫이라 한도를 크게 올려 둔다(200개 상한 테스트가 30회/분에 걸리지 않게).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"app.rate-limit.policy-per-minute=1000000",
		"app.rate-limit.upload-per-minute=1000000" })
@AutoConfigureMockMvc
public abstract class IntegrationTestBase {

	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17");

	protected static final Path STORAGE;

	static {
		POSTGRES.start();
		try {
			STORAGE = Files.createTempDirectory("extblock-uploads");
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	@DynamicPropertySource
	static void props(DynamicPropertyRegistry r) {
		r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
		r.add("spring.datasource.username", POSTGRES::getUsername);
		r.add("spring.datasource.password", POSTGRES::getPassword);
		r.add("app.upload.storage-dir", STORAGE::toString);
	}

	protected final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

	@Autowired
	protected MockMvc mvc;

	@Autowired
	protected JdbcClient jdbc;

	@LocalServerPort
	protected int port;

	/** db/reset.sql + 감사 비우기 + 저장 폴더 비우기 */
	@BeforeEach
	protected void resetState() throws IOException {
		jdbc.sql("DELETE FROM extension WHERE type = 'CUSTOM'").update();
		jdbc.sql("UPDATE extension SET blocked = false WHERE type = 'FIXED'").update();
		jdbc.sql("DELETE FROM policy_audit").update();
		try (Stream<Path> files = Files.list(STORAGE)) {
			for (Path p : files.toList()) {
				Files.delete(p);
			}
		}
	}

	// ---- 헬퍼 ----

	protected long fixedId(String name) {
		return jdbc.sql("SELECT id FROM extension WHERE name = ? AND type = 'FIXED'").param(name).query(Long.class).single();
	}

	protected void setFixed(String name, boolean blocked) {
		jdbc.sql("UPDATE extension SET blocked = ? WHERE name = ?").params(blocked, name).update();
	}

	protected void insertCustom(String name) {
		jdbc.sql("INSERT INTO extension (name, type, blocked) VALUES (?, 'CUSTOM', true)").param(name).update();
	}

	protected int customCount() {
		return jdbc.sql("SELECT count(*) FROM extension WHERE type = 'CUSTOM'").query(Integer.class).single();
	}

	protected List<String> auditActions() {
		return jdbc.sql("SELECT action FROM policy_audit ORDER BY id").query(String.class).list();
	}

	protected List<Path> storedFiles() throws IOException {
		try (Stream<Path> files = Files.list(STORAGE)) {
			return files.toList();
		}
	}

	protected URI uri(String path) {
		return URI.create("http://localhost:" + port + path);
	}

	protected HttpResponse<String> send(HttpRequest req) {
		try {
			return http.send(req, HttpResponse.BodyHandlers.ofString());
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(e);
		}
	}

	protected HttpResponse<String> json(String method, String path, String body) {
		return send(HttpRequest.newBuilder(uri(path))
				.header("Content-Type", "application/json")
				.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
				.build());
	}

	/** multipart 파트 하나. filename == null이면 파일이 아닌 텍스트 필드 */
	public record Part(String name, String filename, String contentType, byte[] bytes) {
		public static Part file(String filename, byte[] bytes) {
			return new Part("file", filename, "application/octet-stream", bytes);
		}
	}

	protected HttpResponse<String> upload(Part... parts) {
		String boundary = "----test" + UUID.randomUUID();
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		for (Part p : parts) {
			StringBuilder h = new StringBuilder("--").append(boundary).append("\r\n")
					.append("Content-Disposition: form-data; name=\"").append(p.name()).append('"');
			if (p.filename() != null) {
				h.append("; filename=\"").append(p.filename()).append('"');
			}
			h.append("\r\n");
			if (p.contentType() != null) {
				h.append("Content-Type: ").append(p.contentType()).append("\r\n");
			}
			h.append("\r\n");
			out.writeBytes(h.toString().getBytes(StandardCharsets.UTF_8));
			out.writeBytes(p.bytes());
			out.writeBytes("\r\n".getBytes(StandardCharsets.UTF_8));
		}
		out.writeBytes(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
		return send(HttpRequest.newBuilder(uri("/api/uploads"))
				.header("Content-Type", "multipart/form-data; boundary=" + boundary)
				.POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray()))
				.build());
	}

	protected HttpResponse<String> upload(String filename, byte[] bytes) {
		return upload(Part.file(filename, bytes));
	}

}
