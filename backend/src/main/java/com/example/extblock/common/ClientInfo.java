package com.example.extblock.common;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 감사·로그·rate limit용 "추적 단서"(식별 아님).
 * ip는 getRemoteAddr()만 사용한다. X-Forwarded-For는 직접 파싱하지 않는다 —
 * server.forward-headers-strategy=native(Tomcat RemoteIpValve)가 신뢰 프록시 값만 반영해 교정한다.
 */
public record ClientInfo(String ip, String userAgent) {

	private static final int MAX_IP = 45; // policy_audit.client_ip VARCHAR(45)

	private static final int MAX_USER_AGENT = 512; // policy_audit.user_agent VARCHAR(512)

	public static ClientInfo from(HttpServletRequest req) {
		return new ClientInfo(truncate(req.getRemoteAddr(), MAX_IP), truncate(req.getHeader("User-Agent"), MAX_USER_AGENT));
	}

	private static String truncate(String value, int max) {
		return (value == null || value.length() <= max) ? value : value.substring(0, max);
	}

}
