package com.example.extblock.policy;

import java.util.Locale;
import java.util.regex.Pattern;

import com.example.extblock.common.ApiException;
import com.example.extblock.common.ErrorCode;

/**
 * 커스텀 확장자 입력 정규화 (서버 기준 검증). 프론트 normalize.ts는 같은 규칙의 UX용 사본이다.
 * 의도가 분명한 것(공백·앞의 점·대문자)은 보정하고, 나머지는 거부한다.
 */
public final class ExtensionNormalizer {

	// DB CHECK 제약 extension_name_format과 같은 규칙. ASCII만 허용하므로 "20자"가 VARCHAR(20)과 정확히 일치
	private static final Pattern VALID = Pattern.compile("^[a-z0-9]{1,20}$");

	private ExtensionNormalizer() {
	}

	public static String normalize(String raw) {
		if (raw == null) {
			throw new ApiException(ErrorCode.INVALID_EXTENSION);
		}
		String name = raw.trim()
			.replaceFirst("^\\.+", "") // ".sh", "..sh" → "sh"
			.toLowerCase(Locale.ROOT); // 터키어 로케일의 "I" → "ı" 문제 회피
		if (!VALID.matcher(name).matches()) {
			throw new ApiException(ErrorCode.INVALID_EXTENSION);
		}
		return name;
	}

}
