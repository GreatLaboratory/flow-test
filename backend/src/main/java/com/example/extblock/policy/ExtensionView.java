package com.example.extblock.policy;

import java.util.List;

/** GET /api/extensions 응답. 한도 값(customLimit, maxFileSize 바이트)을 서버가 내려줘 프론트 하드코딩을 없앤다. */
public record ExtensionView(List<FixedView> fixed, List<CustomView> custom, int customLimit, long maxFileSize) {
}
