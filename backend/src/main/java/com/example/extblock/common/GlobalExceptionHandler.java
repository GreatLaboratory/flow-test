package com.example.extblock.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.example.extblock.config.AppProperties;

/**
 * 모든 예외를 §5-1 형식 {code, message, details}로 변환한다.
 * 스택 트레이스는 로그에만 남기고 응답 본문에는 절대 넣지 않는다.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	private final AppProperties props;

	public GlobalExceptionHandler(AppProperties props) {
		this.props = props;
	}

	@ExceptionHandler(ApiException.class)
	ResponseEntity<ErrorBody> handleApi(ApiException e) {
		return respond(new ErrorBody(e.code(), e.getMessage(), e.details()));
	}

	@ExceptionHandler(MaxUploadSizeExceededException.class)
	ResponseEntity<ErrorBody> handleTooLarge(MaxUploadSizeExceededException e) {
		String max = props.upload().maxFileSize().toMegabytes() + "MB";
		ErrorCode code = ErrorCode.FILE_TOO_LARGE;
		return respond(new ErrorBody(code, code.defaultMessage().replace("{max}", max), null));
	}

	@ExceptionHandler(Exception.class)
	ResponseEntity<ErrorBody> handleOther(Exception e) {
		if (e instanceof NoResourceFoundException) {
			return respond(ErrorBody.of(ErrorCode.NOT_FOUND));
		}
		if (isClientError(e)) {
			// 잘못된 JSON, 지원하지 않는 메서드/Content-Type, 경로 변수 타입 불일치 등.
			// 의도적 단순화: 405·415도 INVALID_REQUEST(400) 하나로 통일 (CONSIDERATIONS A-3)
			return respond(ErrorBody.of(ErrorCode.INVALID_REQUEST));
		}
		log.error("unhandled exception", e);
		return respond(ErrorBody.of(ErrorCode.INTERNAL_ERROR));
	}

	/** Spring MVC가 던지는 클라이언트 측 오류인가. */
	private static boolean isClientError(Exception e) {
		return (e instanceof ErrorResponse er && er.getStatusCode().is4xxClientError())
				|| e instanceof HttpMessageNotReadableException
				|| e instanceof TypeMismatchException;
	}

	private static ResponseEntity<ErrorBody> respond(ErrorBody body) {
		return ResponseEntity.status(body.code().status()).body(body);
	}

}
