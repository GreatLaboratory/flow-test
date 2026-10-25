package com.example.extblock.upload;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import org.junit.jupiter.api.Test;

import com.example.extblock.IntegrationTestBase;
import com.jayway.jsonpath.JsonPath;

/**
 * DESIGN §5 / §6-4 / §6-5 / §12 T5 기준. 실제 Tomcat에 multipart를 보낸다(Spring 파서·크기 제한 포함).
 */
class UploadApiTest extends IntegrationTestBase {

	static final byte[] TEXT = "hello".getBytes(StandardCharsets.UTF_8);
	static final byte[] PE = { 'M', 'Z', (byte) 0x90, 0, 3, 0 };
	static final String UUID_RE = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

	static String code(HttpResponse<String> res) {
		return JsonPath.read(res.body(), "$.code");
	}

	/** 헬퍼가 만들 수 없는 multipart(filename*, 깨진 경계 등)를 그대로 보낸다 */
	HttpResponse<String> raw(String contentType, String body) {
		return send(HttpRequest.newBuilder(uri("/api/uploads"))
				.header("Content-Type", contentType)
				.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
				.build());
	}

	static String filePart(String disposition) {
		return "--B\r\nContent-Disposition: form-data; name=\"file\"; " + disposition + "\r\n\r\nhello\r\n--B--\r\n";
	}

	@Test // U1 기본 unCheck → exe도 성공
	void defaultPolicyAllowsExe() {
		assertThat(upload("a.exe", TEXT).statusCode()).isEqualTo(201);
	}

	@Test // U2 체크 즉시 차단, 해제 즉시 허용(캐시 없음) — API로 정책 변경
	void policyChangeAppliesImmediately() {
		long exe = fixedId("exe");
		json("PUT", "/api/extensions/fixed/" + exe, "{\"blocked\":true}");
		HttpResponse<String> blocked = upload("a.exe", TEXT);
		assertThat(blocked.statusCode()).isEqualTo(422);
		assertThat(code(blocked)).isEqualTo("BLOCKED_EXTENSION");

		json("PUT", "/api/extensions/fixed/" + exe, "{\"blocked\":false}");
		assertThat(upload("a.exe", TEXT).statusCode()).isEqualTo(201);
	}

	@Test // U3 커스텀도 실제 업로드에 강제
	void customExtensionEnforced() {
		HttpResponse<String> add = json("POST", "/api/extensions/custom", "{\"name\":\"sh\"}");
		assertThat(add.statusCode()).isEqualTo(201);
		assertThat(upload("x.SH", TEXT).statusCode()).isEqualTo(422);

		long id = ((Number) JsonPath.read(add.body(), "$.id")).longValue();
		json("DELETE", "/api/extensions/custom/" + id, null);
		assertThat(upload("x.SH", TEXT).statusCode()).isEqualTo(201);
	}

	@Test // U4 MIME 스푸핑: exe를 image/jpeg로
	void contentTypeDoesNotUnblock() {
		setFixed("exe", true);
		HttpResponse<String> res = upload(new Part("file", "a.exe", "image/jpeg", TEXT));
		assertThat(res.statusCode()).isEqualTo(422);
		assertThat(res.body()).doesNotContain("image/jpeg");
	}

	@Test // U5 반대 방향: 정상 파일 + 실행 파일 Content-Type
	void contentTypeDoesNotBlock() {
		setFixed("exe", true);
		HttpResponse<String> res = upload(new Part("file", "a.txt", "application/x-msdownload", TEXT));
		assertThat(res.statusCode()).isEqualTo(201);
		assertThat(res.body()).doesNotContain("x-msdownload");
		assertThat(res.headers().map().toString()).doesNotContain("x-msdownload");
	}

	@Test // U6 MZ 위장 jpg, 저장 안 됨
	void mzContentBlocked() throws IOException {
		setFixed("exe", true);
		HttpResponse<String> res = upload("fake.jpg", PE);
		assertThat(res.statusCode()).isEqualTo(422);
		assertThat(code(res)).isEqualTo("BLOCKED_CONTENT");
		assertThat(storedFiles()).isEmpty();
	}

	@Test // U7 성공 응답·저장 형태·size 일치
	void successStoresUuidFile() throws IOException {
		byte[] bytes = new byte[12_345];
		bytes[0] = 7;
		HttpResponse<String> res = upload("report.pdf", bytes);
		assertThat(res.statusCode()).isEqualTo(201);
		String key = JsonPath.read(res.body(), "$.key");
		assertThat(key).matches(UUID_RE);
		assertThat((String) JsonPath.read(res.body(), "$.fileName")).isEqualTo("report.pdf");
		assertThat(((Number) JsonPath.read(res.body(), "$.size")).longValue()).isEqualTo(12_345);
		assertThat(storedFiles()).containsExactly(STORAGE.resolve(key));
		assertThat(Files.readAllBytes(STORAGE.resolve(key))).isEqualTo(bytes);
	}

	@Test // U8 경로 조작 파일명: 저장 폴더 밖에 아무것도 안 생김, 응답은 basename
	void pathTraversalFilename() throws IOException {
		Path parent = STORAGE.getParent();
		long before;
		try (var s = Files.list(parent)) {
			before = s.count();
		}
		for (String name : new String[] { "../../evil.txt", "..\\..\\evil.txt", "/etc/passwd" }) {
			HttpResponse<String> res = upload(name, TEXT);
			assertThat(res.statusCode()).as(name).isEqualTo(201);
			String fileName = JsonPath.read(res.body(), "$.fileName");
			assertThat(fileName).as(name).doesNotContain("/").doesNotContain("\\");
		}
		assertThat(storedFiles()).hasSize(3).allSatisfy(p -> assertThat(p.getFileName().toString()).matches(UUID_RE));
		try (var s = Files.list(parent)) {
			assertThat(s.count()).isEqualTo(before);
		}
		assertThat(parent.resolve("evil.txt")).doesNotExist();
	}

	@Test // U8b (§7-5) 실제 파서에서 C:\fakepath\a.exe → 차단, details.fileName = a.exe
	void fakepathBlockedWithBasename() {
		setFixed("exe", true);
		HttpResponse<String> res = upload("C:\\fakepath\\a.exe", TEXT);
		assertThat(res.statusCode()).isEqualTo(422);
		assertThat((String) JsonPath.read(res.body(), "$.details.fileName")).isEqualTo("a.exe");
	}

	@Test // U9 차단 파일은 저장 폴더에 남지 않음
	void blockedNotStored() throws IOException {
		setFixed("exe", true);
		upload("a.exe.txt", TEXT);
		upload("a.EXE", TEXT);
		assertThat(storedFiles()).isEmpty();
	}

	@Test // U10 개수·파트 이름·multipart 아님
	void fileCountAndShape() {
		HttpResponse<String> two = upload(Part.file("a.txt", TEXT), Part.file("b.txt", TEXT));
		assertThat(two.statusCode()).isEqualTo(400);
		assertThat(code(two)).isEqualTo("TOO_MANY_FILES");

		HttpResponse<String> noPart = upload(new Part("other", null, null, TEXT));
		assertThat(noPart.statusCode()).isEqualTo(400);
		assertThat(code(noPart)).isEqualTo("MISSING_FILE");

		HttpResponse<String> wrongName = upload(new Part("upload", "a.txt", "text/plain", TEXT));
		assertThat(code(wrongName)).isEqualTo("MISSING_FILE");

		HttpResponse<String> textField = upload(new Part("file", null, null, TEXT));
		assertThat(code(textField)).isEqualTo("MISSING_FILE");

		HttpResponse<String> notMultipart = json("POST", "/api/uploads", "{}");
		assertThat(notMultipart.statusCode()).isEqualTo(400);
		assertThat(code(notMultipart)).isEqualTo("MISSING_FILE");
	}

	@Test // U11 filename="" → INVALID_FILENAME (§6-1 1단계, MISSING_FILE과 구분) / U12 256자 / J13b 점뿐
	void invalidFilenames() throws IOException {
		var soft = new org.assertj.core.api.SoftAssertions();
		for (String name : new String[] { "", "a".repeat(252) + ".txt", "..", "...", ". ." }) {
			HttpResponse<String> res = upload(name, TEXT);
			soft.assertThat(res.statusCode()).as("[" + name + "]").isEqualTo(400);
		}
		soft.assertThat(storedFiles()).isEmpty();
		soft.assertAll();
	}

	@Test // U13 저장 폴더 쓰기 불가 → 500 STORAGE_FAILED, 부분 파일 없음
	void storageFailure() throws IOException {
		var original = Files.getPosixFilePermissions(STORAGE);
		Files.setPosixFilePermissions(STORAGE, PosixFilePermissions.fromString("r-xr-xr-x"));
		try {
			HttpResponse<String> res = upload("a.txt", TEXT);
			assertThat(res.statusCode()).isEqualTo(500);
			assertThat(code(res)).isEqualTo("STORAGE_FAILED");
			assertThat(res.body()).doesNotContain("Exception").doesNotContain("at com.");
		}
		finally {
			Files.setPosixFilePermissions(STORAGE, original);
		}
		assertThat(storedFiles()).isEmpty();
	}

	@Test // U14 0바이트·1바이트
	void tinyFiles() {
		setFixed("exe", true);
		assertThat(upload("empty.txt", new byte[0]).statusCode()).isEqualTo(201);
		assertThat(upload("m.txt", new byte[] { 'M' }).statusCode()).isEqualTo(201);
	}

	@Test // U15 10MB 경계
	void sizeBoundary() throws IOException {
		assertThat(upload("ok.bin", new byte[10 * 1024 * 1024]).statusCode()).isEqualTo(201);
		HttpResponse<String> big = upload("big.bin", new byte[10 * 1024 * 1024 + 1]);
		assertThat(big.statusCode()).isEqualTo(413);
		assertThat(code(big)).isEqualTo("FILE_TOO_LARGE");
		assertThat(storedFiles()).hasSize(1);
	}

	@Test // U16 §5-1 형식, U17 차단 응답 details
	void blockedResponseShape() {
		setFixed("exe", true);
		setFixed("bat", true);
		HttpResponse<String> res = upload(new Part("file", "a.exe.bat", "text/x-weird-type", TEXT));
		assertThat(res.statusCode()).isEqualTo(422);
		assertThat(res.headers().firstValue("Content-Type")).hasValueSatisfying(v -> assertThat(v).startsWith("application/json"));
		assertThat(code(res)).isEqualTo("BLOCKED_EXTENSION");
		assertThat((String) JsonPath.read(res.body(), "$.details.fileName")).isEqualTo("a.exe.bat");
		java.util.List<String> matched = JsonPath.read(res.body(), "$.details.matched");
		assertThat(matched).containsExactlyInAnyOrder("exe", "bat");
		String message = JsonPath.read(res.body(), "$.message");
		assertThat(message).contains("." + matched.get(0));
		assertThat(res.body() + res.headers().map()).doesNotContain("x-weird-type");
	}

	// U19 (S3) filename*은 Tomcat이 디코딩하지 않아 Tomcat의 NUL 검사를 지나 Spring까지 온다 → 판정이 막아야 함
	@Test
	void controlCharsViaFilenameStarRejected() throws IOException {
		setFixed("exe", true);
		for (String enc : new String[] { "a.exe%00", "a.exe%7F", "a.exe%01" }) {
			HttpResponse<String> res = raw("multipart/form-data; boundary=B", filePart("filename*=UTF-8''" + enc));
			assertThat(res.statusCode()).as(enc).isEqualTo(400);
			assertThat(code(res)).as(enc).isEqualTo("INVALID_FILENAME_CHAR");
			assertThat((String) JsonPath.read(res.body(), "$.message")).contains("허용되지 않는 문자");
		}
		assertThat(storedFiles()).isEmpty();
	}

	// U20 (S2) 깨진 multipart는 클라이언트 오류 400 (이전: 500 + ERROR 스택 로그)
	@Test
	void malformedMultipartIs400() {
		String[][] cases = {
				{ "multipart/form-data; boundary=B", "--B\r\nContent-Disposition: form-data; name=\"file\"; filename=\"a.txt\"\r\n\r\nabc" }, // 끝 경계 없음
				{ "multipart/form-data", "x" }, // boundary 없음
				{ "multipart/form-data; boundary=B", filePart("filename=\"a.exe\u0000.jpg\"") }, // Tomcat InvalidFileNameException
		};
		for (String[] c : cases) {
			HttpResponse<String> res = raw(c[0], c[1]);
			assertThat(res.statusCode()).as(c[1]).isEqualTo(400);
			assertThat(code(res)).as(c[1]).isEqualTo("INVALID_REQUEST");
		}
	}

	@Test // U21 대소문자 접기: 실제 파서(UTF-8 파일명)에서 a.ſcr 차단, 정상 한글·다중 점 파일명 통과
	void caseFoldHomoglyphViaRealParser() {
		setFixed("scr", true);
		assertThat(upload("a.\u017Fcr", TEXT).statusCode()).isEqualTo(422);
		assertThat(upload("홍길동 이력서.v2.pdf", TEXT).statusCode()).isEqualTo(201);
		assertThat(upload("report.pdf", TEXT).statusCode()).isEqualTo(201);
	}

}
