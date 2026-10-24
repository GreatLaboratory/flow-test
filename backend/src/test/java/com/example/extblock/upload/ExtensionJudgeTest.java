package com.example.extblock.upload;

import static com.example.extblock.common.ErrorCode.BLOCKED_CONTENT;
import static com.example.extblock.common.ErrorCode.BLOCKED_EXTENSION;
import static com.example.extblock.common.ErrorCode.INVALID_FILENAME;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * DESIGN §6-1 / §12 T1 기준. 기대값은 스펙에서 역산했다(코드 아님).
 * 케이스 ID(J1..)는 docs/VERIFICATION.md 목록과 대응.
 */
class ExtensionJudgeTest {

	static final Set<String> BLOCKED = Set.of("exe", "sh", "bat", "gz", "com");
	static final byte[] NONE = new byte[0];
	static final byte[] MZ = {'M', 'Z'};

	static Verdict judge(String name) {
		return ExtensionJudge.judge(name, NONE, BLOCKED);
	}

	// J1 대소문자, J2 이중확장자, J10 끝의 점·공백, J11 경로 포함, J18 RTL
	@ParameterizedTest
	@ValueSource(strings = {
			"a.exe", "a.EXE", "a.ExE", "a.txt.exe",
			"a.exe.txt", "a.EXE.txt",
			"file.exe.", "file.exe ", "file.exe. .", "file.exe...",
			"C:\\fakepath\\a.exe", "../../a.exe", "..\\..\\a.exe",
			"inv\u202Egpj.exe",
			".EXE"})
	void blocksExe(String name) {
		Verdict v = judge(name);
		assertThat(v.code()).isEqualTo(BLOCKED_EXTENSION);
		assertThat(v.matched()).containsExactly("exe");
	}

	@Test // J11 판정·표시용 basename
	void baseNameStripsPath() {
		assertThat(judge("C:\\fakepath\\a.exe").baseName()).isEqualTo("a.exe");
		assertThat(judge("../../a.exe").baseName()).isEqualTo("a.exe");
	}

	@ParameterizedTest // J3
	@ValueSource(strings = {"archive.tar.gz", "A.TAR.GZ"})
	void blocksTarGz(String name) {
		assertThat(judge(name).matched()).containsExactly("gz");
	}

	@Test // J4
	void multipleMatchesAllReported() {
		Verdict v = judge("a.exe.bat");
		assertThat(v.code()).isEqualTo(BLOCKED_EXTENSION);
		assertThat(v.matched()).containsExactlyInAnyOrder("exe", "bat");
	}

	@Test // J5
	void repeatedSegmentReportedOnce() {
		assertThat(judge("a.exe.exe").matched()).containsExactly("exe");
	}

	@Test // J6 감수한 오탐
	void exampleComPdfIsBlocked() {
		assertThat(judge("example.com.pdf").matched()).containsExactly("com");
	}

	// J7 첫 구간 제외, J8 .env, J9 확장자 없음, J12 경로 속 점
	@ParameterizedTest
	@ValueSource(strings = {"exe.txt", "js.pdf", "com.example.txt", ".env", "Makefile", "README",
			"dir.v2/readme", "../etc/passwd"})
	void allows(String name) {
		assertThat(judge(name).allowed()).isTrue();
	}

	@Test // J8
	void dotBatIsBlocked() {
		assertThat(judge(".bat").matched()).containsExactly("bat");
	}

	// J13 basename 빔, J14 null/빈, J13b 점·공백뿐(Q1 결정: 파일명 없음과 같음)
	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {"/", "a.exe/", "a.exe\\", ".", "..", "...", ". .", "   "})
	void invalidFilename(String name) {
		assertThat(judge(name).code()).isEqualTo(INVALID_FILENAME);
	}

	@Test // J15
	void length255AllowedAnd256Rejected() {
		assertThat(judge("a".repeat(251) + ".txt").allowed()).isTrue();
		assertThat(judge("a".repeat(252) + ".txt").code()).isEqualTo(INVALID_FILENAME);
	}

	@Test // J16 길이는 basename 기준
	void longDirectoryDoesNotCount() {
		assertThat(judge("d/".repeat(200) + "a".repeat(251) + ".txt").allowed()).isTrue();
	}

	@Test // J17 문자 수 기준(보조 평면 문자 1자)
	void supplementaryCharsCountAsOne() {
		String emoji = "\uD83D\uDE00";
		assertThat(judge(emoji.repeat(251) + ".txt").allowed()).isTrue();
		assertThat(judge(emoji.repeat(252) + ".txt").code()).isEqualTo(INVALID_FILENAME);
	}

	@Test // J19
	void mzDisguisedAsJpg() {
		assertThat(ExtensionJudge.judge("report.jpg", MZ, BLOCKED).code()).isEqualTo(BLOCKED_CONTENT);
	}

	@ParameterizedTest // J20 PE 계열 하나만 막혀 있어도
	@ValueSource(strings = {"scr", "cpl", "com", "exe"})
	void mzBlockedWhenAnyPeFamilyBlocked(String pe) {
		assertThat(ExtensionJudge.judge("report.jpg", MZ, Set.of(pe)).code()).isEqualTo(BLOCKED_CONTENT);
	}

	@Test // J21
	void mzAllowedWhenNoPeFamilyBlocked() {
		assertThat(ExtensionJudge.judge("report.jpg", MZ, Set.of("bat", "js", "sh")).allowed()).isTrue();
	}

	@ParameterizedTest // J22 확장자 없음 / J23 점 파일 + MZ
	@ValueSource(strings = {"Makefile", ".env"})
	void mzWithoutExtension(String name) {
		assertThat(ExtensionJudge.judge(name, MZ, BLOCKED).code()).isEqualTo(BLOCKED_CONTENT);
	}

	@Test // J24 PE 시그니처는 정확히 'M','Z'
	void nonMzHeadsAllowedWithoutException() {
		for (byte[] head : new byte[][] {{'m', 'z'}, {'Z', 'M'}, {'M'}, {}}) {
			assertThat(ExtensionJudge.judge("report.jpg", head, BLOCKED).allowed()).isTrue();
		}
	}

	@Test // J25 확장자 사유 우선
	void extensionWinsOverContent() {
		assertThat(ExtensionJudge.judge("a.exe", MZ, BLOCKED).code()).isEqualTo(BLOCKED_EXTENSION);
	}

	@Test // J26 앞 2바이트만 본다
	void onlyFirstTwoBytesMatter() {
		byte[] head = {'M', 'Z', 0, 1, 2};
		assertThat(ExtensionJudge.judge("a.txt", head, BLOCKED).code()).isEqualTo(BLOCKED_CONTENT);
	}

	@Test // J27 소문자화가 기본 로케일을 타면 'I' → 'ı'
	void turkishLocaleDoesNotBypass() {
		Locale saved = Locale.getDefault();
		try {
			Locale.setDefault(Locale.forLanguageTag("tr"));
			assertThat(ExtensionJudge.judge("a.MSI", NONE, Set.of("msi")).code()).isEqualTo(BLOCKED_EXTENSION);
		}
		finally {
			Locale.setDefault(saved);
		}
	}

	@Test // J28 끝의 '.'·공백만 정리(§6-1). 탭은 위협 모델 밖, 개행은 \z 처리
	void controlCharTail() {
		assertThat(judge("a.exe\t").allowed()).isTrue();
		assertThat(judge("file.exe.\n").code()).isEqualTo(BLOCKED_EXTENSION);
	}

}
