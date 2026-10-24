package com.example.extblock.common;

import java.util.Map;

/**
 * 의도된 실패(정책 위반·입력 오류 등). GlobalExceptionHandler가 §5-1 형식으로 변환한다.
 */
public class ApiException extends RuntimeException {

	private final ErrorCode code;

	private final Map<String, Object> details;

	public ApiException(ErrorCode code) {
		this(code, code.defaultMessage(), null);
	}

	public ApiException(ErrorCode code, String message, Map<String, Object> details) {
		super(message);
		this.code = code;
		this.details = details;
	}

	public ErrorCode code() {
		return code;
	}

	public Map<String, Object> details() {
		return details;
	}

}
