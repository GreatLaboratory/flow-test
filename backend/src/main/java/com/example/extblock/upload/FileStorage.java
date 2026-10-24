package com.example.extblock.upload;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.example.extblock.common.ApiException;
import com.example.extblock.common.ErrorCode;
import com.example.extblock.config.AppProperties;

/**
 * 저장 경계. 파일명은 UUID(확장자 없음), 원본 파일명은 경로에 절대 쓰지 않는다. 저장된 파일은 어떤 경로로도 서빙하지 않는다.
 * ponytail: 로컬 디스크(휘발성, 단일 서버). 외부 스토리지(S3/R2) 전환 시 save() 본문만 교체 — 구현체가 1개라 인터페이스 없음.
 */
@Component
public class FileStorage {

	private static final Logger log = LoggerFactory.getLogger(FileStorage.class);

	private final Path storageDir;

	public FileStorage(AppProperties props) throws IOException {
		this.storageDir = props.upload().storageDir();
		Files.createDirectories(storageDir);
	}

	public String save(InputStream in) {
		String key = UUID.randomUUID().toString();
		Path target = storageDir.resolve(key);
		try (in) {
			Files.copy(in, target);
		}
		catch (IOException e) {
			log.error("upload storage failed key={}", key, e);
			deletePartial(target); // 쓰다 만 파일이 쌓이지 않게
			throw new ApiException(ErrorCode.STORAGE_FAILED);
		}
		return key;
	}

	private static void deletePartial(Path target) {
		try {
			Files.deleteIfExists(target);
		}
		catch (IOException e) {
			log.error("failed to delete partial upload {}", target, e); // 원래 실패(STORAGE_FAILED)를 가리지 않도록 삼킨다
		}
	}

}
