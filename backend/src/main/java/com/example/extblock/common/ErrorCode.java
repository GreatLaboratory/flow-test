package com.example.extblock.common;

import org.springframework.http.HttpStatus;

/**
 * 모든 에러 응답의 code 목록. code는 기계용(프론트 분기), defaultMessage는 사람용(그대로 표시).
 * {name} {limit} {max} {ext} 자리표시자는 ApiException 생성 시 String.replace로 채운다.
 */
public enum ErrorCode {

	INVALID_REQUEST(HttpStatus.BAD_REQUEST, "요청 형식이 올바르지 않습니다."),
	NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 경로를 찾을 수 없습니다."),
	INVALID_EXTENSION(HttpStatus.BAD_REQUEST, "확장자는 영문 소문자와 숫자만, 1~20자로 입력하세요."),
	EXTENSION_NOT_FOUND(HttpStatus.NOT_FOUND, "해당 고정 확장자를 찾을 수 없습니다."),
	FIXED_EXTENSION_CONFLICT(HttpStatus.CONFLICT, "'{name}'은(는) 고정 확장자입니다. 위 체크박스를 사용하세요."),
	DUPLICATE_EXTENSION(HttpStatus.CONFLICT, "'{name}'은(는) 이미 등록되어 있습니다."),
	CUSTOM_LIMIT_EXCEEDED(HttpStatus.CONFLICT, "커스텀 확장자는 최대 {limit}개까지 등록할 수 있습니다."),
	MISSING_FILE(HttpStatus.BAD_REQUEST, "업로드할 파일이 없습니다."),
	TOO_MANY_FILES(HttpStatus.BAD_REQUEST, "한 번에 하나의 파일만 업로드할 수 있습니다."),
	INVALID_FILENAME(HttpStatus.BAD_REQUEST, "파일명이 없거나 255자를 초과합니다."),
	FILE_TOO_LARGE(HttpStatus.CONTENT_TOO_LARGE, "파일 크기는 최대 {max}까지 가능합니다."),
	BLOCKED_EXTENSION(HttpStatus.UNPROCESSABLE_CONTENT, "'.{ext}' 확장자는 업로드가 차단되어 있습니다."),
	BLOCKED_CONTENT(HttpStatus.UNPROCESSABLE_CONTENT, "파일 내용이 실행 파일(PE) 형식이라 차단되었습니다."),
	STORAGE_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "파일 저장에 실패했습니다. 잠시 후 다시 시도하세요."),
	RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "요청이 너무 많습니다. 잠시 후 다시 시도하세요."),
	INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "일시적인 오류가 발생했습니다.");

	private final HttpStatus status;

	private final String defaultMessage;

	ErrorCode(HttpStatus status, String defaultMessage) {
		this.status = status;
		this.defaultMessage = defaultMessage;
	}

	public HttpStatus status() {
		return status;
	}

	public String defaultMessage() {
		return defaultMessage;
	}

}
