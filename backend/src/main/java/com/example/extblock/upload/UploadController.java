package com.example.extblock.upload;

import java.io.IOException;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;

import com.example.extblock.common.ApiException;
import com.example.extblock.common.ClientInfo;
import com.example.extblock.common.ErrorCode;

@RestController
public class UploadController {

	private final UploadService service;

	public UploadController(UploadService service) {
		this.service = service;
	}

	/** 파트 "file" 정확히 1개. 여러 개를 허용하면 "부분 성공 vs 전체 실패" 정책 질문이 생긴다. */
	@PostMapping("/api/uploads")
	@ResponseStatus(HttpStatus.CREATED)
	public UploadResult upload(HttpServletRequest request) throws IOException {
		List<MultipartFile> files = (request instanceof MultipartHttpServletRequest multipart)
				? multipart.getFiles("file") : List.of(); // multipart가 아닌 요청 = 파일 없음
		if (files.isEmpty()) {
			throw new ApiException(ErrorCode.MISSING_FILE);
		}
		if (files.size() > 1) {
			throw new ApiException(ErrorCode.TOO_MANY_FILES);
		}
		return service.upload(files.get(0), ClientInfo.from(request));
	}

}
