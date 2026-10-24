package com.example.extblock.policy;

import java.time.OffsetDateTime;

/** extension 테이블 한 행. type은 'FIXED' 또는 'CUSTOM'. */
public record ExtensionRow(long id, String name, String type, boolean blocked, OffsetDateTime createdAt) {

	public boolean isFixed() {
		return "FIXED".equals(type);
	}

}
