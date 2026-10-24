package com.example.extblock.policy;

/** name 검증은 ExtensionNormalizer가 담당(null 포함 → INVALID_EXTENSION). */
public record AddCustomRequest(String name) {
}
