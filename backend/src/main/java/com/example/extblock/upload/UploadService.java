package com.example.extblock.upload;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.example.extblock.common.ApiException;
import com.example.extblock.common.ClientInfo;
import com.example.extblock.common.ErrorCode;
import com.example.extblock.policy.ExtensionRepository;

/**
 * 업로드 흐름 (§6-4). 판정이 끝나기 전에는 최종 저장 위치에 쓰지 않는다.
 * 클라이언트가 보낸 Content-Type은 읽지 않는다 — 조작 가능한 값이라 검증이 아니라 무시가 정답.
 */
@Service
public class UploadService {

	private static final Logger log = LoggerFactory.getLogger(UploadService.class);

	private final ExtensionRepository extensions;

	private final FileStorage storage;

	public UploadService(ExtensionRepository extensions, FileStorage storage) {
		this.extensions = extensions;
		this.storage = storage;
	}

	public UploadResult upload(MultipartFile file, ClientInfo client) throws IOException {
		String fileName = file.getOriginalFilename();
		Verdict verdict = ExtensionJudge.judge(fileName, readHead(file), extensions.findBlockedNames());

		if (!verdict.allowed()) {
			logUpload("BLOCKED", verdict, file, client, null, fileName);
			throw toException(verdict);
		}

		// getInputStream()은 호출마다 새 스트림 → 앞 2바이트를 읽은 스트림과 별개로 처음부터 저장
		String key = storage.save(file.getInputStream());
		logUpload("ALLOWED", verdict, file, client, key, fileName);
		return new UploadResult(key, verdict.baseName(), file.getSize());
	}

	/** MZ 판정용 앞 2바이트. 파일이 짧으면 0~1바이트가 반환된다(판정에서 예외 없이 처리). */
	private static byte[] readHead(MultipartFile file) throws IOException {
		try (InputStream in = file.getInputStream()) {
			return in.readNBytes(2);
		}
	}

	private static ApiException toException(Verdict verdict) {
		ErrorCode code = verdict.code();
		if (code == ErrorCode.INVALID_FILENAME || code == ErrorCode.INVALID_FILENAME_CHAR) {
			return new ApiException(code);
		}
		// 여러 확장자가 걸리면 message에는 첫 번째, details.matched에 전체
		String message = code.defaultMessage().replace("{ext}", verdict.matched().get(0));
		return new ApiException(code, message, Map.of("fileName", verdict.baseName(), "matched", verdict.matched()));
	}

	private static void logUpload(String result, Verdict verdict, MultipartFile file, ClientInfo client, String key,
			String fileName) {
		log.atInfo()
			.addKeyValue("event", "upload")
			.addKeyValue("result", result)
			.addKeyValue("code", verdict.code())
			.addKeyValue("matched", verdict.matched())
			.addKeyValue("size", file.getSize())
			.addKeyValue("ip", client.ip())
			.addKeyValue("key", key)
			.addKeyValue("fileName", fileName)
			.log("upload");
	}

}
