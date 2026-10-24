package com.example.extblock.policy;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.example.extblock.common.ClientInfo;

/** policy_audit은 append-only. 정책 변경과 같은 트랜잭션에서만 호출한다. */
@Repository
public class PolicyAuditRepository {

	private final JdbcClient jdbc;

	public PolicyAuditRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public void insert(AuditAction action, String extensionName, ClientInfo client) {
		jdbc.sql("""
				INSERT INTO policy_audit (action, extension_name, client_ip, user_agent)
				VALUES (:action, :name, :ip, :userAgent)""")
			.param("action", action.name())
			.param("name", extensionName)
			.param("ip", client.ip())
			.param("userAgent", client.userAgent())
			.update();
	}

}
