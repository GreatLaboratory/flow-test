package com.example.extblock.upload;

import java.util.List;

import com.example.extblock.common.ErrorCode;

/**
 * 업로드 판정 결과. code == null 이면 허용.
 * baseName: 경로를 뗀 파일명(응답·로그용), matched: 차단에 걸린 확장자들.
 */
public record Verdict(ErrorCode code, String baseName, List<String> matched) {

	public boolean allowed() {
		return code == null;
	}

}
