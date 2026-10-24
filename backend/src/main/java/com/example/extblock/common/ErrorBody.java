package com.example.extblock.common;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

/** 공통 에러 응답 형식 (§5-1). details는 있을 때만 직렬화. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorBody(ErrorCode code, String message, Map<String, Object> details) {

	public static ErrorBody of(ErrorCode code) {
		return new ErrorBody(code, code.defaultMessage(), null);
	}

}
