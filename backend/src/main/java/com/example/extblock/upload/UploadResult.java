package com.example.extblock.upload;

/** 업로드 성공 응답. key는 저장된 UUID — 향후 다운로드 기능의 연결 고리. 클라이언트 Content-Type은 되돌려주지 않는다. */
public record UploadResult(String key, String fileName, long size) {
}
