package com.example.extblock.policy;

import static com.example.extblock.common.ErrorCode.INVALID_EXTENSION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import com.example.extblock.common.ApiException;

/** DESIGN §6-2 / §12 T2 기준. 같은 입력 표를 frontend/src/normalize.test.ts(F1)도 쓴다. */
class ExtensionNormalizerTest {

	@ParameterizedTest // N1
	@ValueSource(strings = {".SH ", "sh", "..sh", "  .Sh", "\tsh\n", "sh\0"})  // "sh\0": §6-2 trim(Java)은 U+0020 이하 제어문자를 모두 제거
	void normalizesToSh(String raw) {
		assertThat(ExtensionNormalizer.normalize(raw)).isEqualTo("sh");
	}

	// N2 비었음, N3 허용 밖 문자, N4 전각 공백
	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {".", "...", "   ",
			"s h", "s.h", "tar.gz", "ｅｘｅ", "한글", "ex-e", "ex_e", "exe;", "../exe",
			"\u3000sh"})
	void rejects(String raw) {
		assertThatThrownBy(() -> ExtensionNormalizer.normalize(raw))
				.isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(INVALID_EXTENSION));
	}

	@Test // N5 20자 경계
	void length20Boundary() {
		assertThat(ExtensionNormalizer.normalize("a".repeat(20))).hasSize(20);
		assertThatThrownBy(() -> ExtensionNormalizer.normalize("a".repeat(21))).isInstanceOf(ApiException.class);
	}

	@Test // N6 길이는 보정 후 기준
	void lengthCountedAfterCorrection() {
		assertThat(ExtensionNormalizer.normalize("." + "a".repeat(20))).hasSize(20);
		assertThat(ExtensionNormalizer.normalize(" " + "a".repeat(20) + " ")).hasSize(20);
	}

	@Test // N7
	void turkishLocaleSafe() {
		Locale saved = Locale.getDefault();
		try {
			Locale.setDefault(Locale.forLanguageTag("tr"));
			assertThat(ExtensionNormalizer.normalize("INI")).isEqualTo("ini");
		}
		finally {
			Locale.setDefault(saved);
		}
	}

	@Test // N8 Q3 결정: §6-2 순서상 켈빈 기호는 소문자화로 ASCII 'k'가 되어 허용
	void kelvinSignBecomesK() {
		assertThat(ExtensionNormalizer.normalize("K")).isEqualTo("k");
	}

}
