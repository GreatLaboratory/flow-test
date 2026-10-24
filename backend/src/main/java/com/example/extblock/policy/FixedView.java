package com.example.extblock.policy;

public record FixedView(long id, String name, boolean blocked) {

	static FixedView from(ExtensionRow row) {
		return new FixedView(row.id(), row.name(), row.blocked());
	}

}
