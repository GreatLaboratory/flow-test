package com.example.extblock.policy;

import java.time.OffsetDateTime;

public record CustomView(long id, String name, OffsetDateTime createdAt) {

	static CustomView from(ExtensionRow row) {
		return new CustomView(row.id(), row.name(), row.createdAt());
	}

}
