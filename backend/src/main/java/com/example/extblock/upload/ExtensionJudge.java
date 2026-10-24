package com.example.extblock.upload;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import com.example.extblock.common.ErrorCode;

/**
 * 업로드 차단 판정 (§6-1). DB·IO 없는 순수 함수 — 서버 판정의 유일한 기준.
 * ponytail: 블랙리스트 판정. 화이트리스트 전환 시 이 클래스 내부만 교체.
 */
public final class ExtensionJudge {

	/** 앞 2바이트가 'MZ'인 PE 포맷 확장자. 텍스트인 bat·cmd·js는 시그니처가 없어 내용 검증 불가. */
	public static final Set<String> PE_FAMILY = Set.of("exe", "com", "scr", "cpl");

	private static final int MAX_NAME_LENGTH = 255;

	// 끝의 '.'·' ' 반복 (Windows는 file.exe. 을 file.exe 로 저장). \z = 문자열 진짜 끝
	private static final Pattern TRAILING_DOTS_SPACES = Pattern.compile("[. ]+\\z");

	private ExtensionJudge() {
	}

	public static Verdict judge(String fileName, byte[] head, Set<String> blocked) {
		if (fileName == null || fileName.isEmpty()) {
			return new Verdict(ErrorCode.INVALID_FILENAME, fileName, List.of());
		}

		// 경로 포함 파일명(C:\fakepath\a.exe, ../../a.exe) → 마지막 '/' 또는 '\' 뒤만 사용
		int lastSeparator = Math.max(fileName.lastIndexOf('/'), fileName.lastIndexOf('\\'));
		String baseName = fileName.substring(lastSeparator + 1);
		if (baseName.isEmpty() || baseName.codePointCount(0, baseName.length()) > MAX_NAME_LENGTH) {
			return new Verdict(ErrorCode.INVALID_FILENAME, baseName, List.of());
		}

		// 첫 구간(파일명 본체)을 제외한 모든 구간을 확장자로 본다: a.exe.txt → {exe, txt}, .env → {env}, exe.txt → {txt}
		String trimmed = TRAILING_DOTS_SPACES.matcher(baseName).replaceAll("");
		List<String> matched = Arrays.stream(trimmed.split("\\."))
			.skip(1)
			.map(segment -> segment.toLowerCase(Locale.ROOT))
			.filter(blocked::contains)
			.distinct()
			.toList();
		if (!matched.isEmpty()) {
			return new Verdict(ErrorCode.BLOCKED_EXTENSION, baseName, matched); // 확장자 사유가 이해하기 쉬워 우선
		}

		// 내용이 PE(MZ)인데 PE 계열 확장자 중 하나라도 차단 중이면 위장으로 보고 차단
		if (isPortableExecutable(head)) {
			List<String> blockedPe = PE_FAMILY.stream().filter(blocked::contains).sorted().toList();
			if (!blockedPe.isEmpty()) {
				return new Verdict(ErrorCode.BLOCKED_CONTENT, baseName, blockedPe);
			}
		}

		return new Verdict(null, baseName, List.of());
	}

	private static boolean isPortableExecutable(byte[] head) {
		return head != null && head.length >= 2 && head[0] == 'M' && head[1] == 'Z';
	}

}
