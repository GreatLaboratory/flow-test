package com.example.extblock.common;

import java.io.IOException;
import java.time.Duration;
import java.util.Set;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.example.extblock.config.AppProperties;
import com.github.benmanes.caffeine.cache.Caffeine;

import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.caffeine.Bucket4jCaffeine;
import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * IP당 rate limit. 변경 API(PUT/POST/DELETE /api/extensions/**)와 업로드(POST /api/uploads)만 제한한다.
 * 인증이 없는 공개 화면에서 감사 테이블 증가·디스크 고갈을 늦추는 장치이지 완벽한 차단이 아니다.
 *
 * 주의: request.getParameter*()·getParts()를 호출하지 않는다 — multipart 파싱이 시작되면
 * 한도를 넘은 요청도 10MB를 끝까지 받게 된다. 판단은 메서드·경로·getRemoteAddr()만으로 한다.
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

	private static final Set<String> POLICY_METHODS = Set.of("PUT", "POST", "DELETE");

	// ponytail: 인스턴스 메모리 카운터(서버 1대 전제, 재시작 시 초기화).
	// 다중 서버면 Redis ProxyManager(bucket4j-redis)로 이 필드만 교체 — 카운터를 서버 간 공유.
	private final ProxyManager<String> buckets;

	private final BucketConfiguration policyLimit;

	private final BucketConfiguration uploadLimit;

	private final byte[] rateLimitedBody;

	public RateLimitFilter(AppProperties props, JsonMapper jsonMapper) {
		// 버킷이 가득 찰 때까지 걸리는 시간 + 10분 뒤 IP 항목 제거(가득 찬 버킷 = 새 버킷과 같은 상태라 안전).
		// maximumSize는 IP를 바꿔가는 공격에서 메모리 상한. Caffeine expireAfterAccess는 Bucket4j와 공존 불가(CONSIDERATIONS A-15)
		this.buckets = Bucket4jCaffeine.<String>builderFor(Caffeine.newBuilder().maximumSize(100_000))
			.expirationAfterWrite(
					ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(Duration.ofMinutes(10)))
			.build();
		this.policyLimit = perMinute(props.rateLimit().policyPerMinute());
		this.uploadLimit = perMinute(props.rateLimit().uploadPerMinute());
		this.rateLimitedBody = jsonMapper.writeValueAsBytes(ErrorBody.of(ErrorCode.RATE_LIMITED));
	}

	/** 용량 n, 1분마다 n개를 한 번에 충전 (고정 창과 비슷해 설명이 쉽다). */
	private static BucketConfiguration perMinute(int n) {
		return BucketConfiguration.builder()
			.addLimit(limit -> limit.capacity(n).refillIntervally(n, Duration.ofMinutes(1)))
			.build();
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String bucket = bucketFor(request);
		if (bucket != null) {
			BucketConfiguration limit = bucket.equals("upload") ? uploadLimit : policyLimit;
			String key = bucket + ":" + ClientInfo.from(request).ip();
			if (!buckets.getProxy(key, () -> limit).tryConsume(1)) {
				writeRateLimited(response);
				return;
			}
		}
		chain.doFilter(request, response);
	}

	/**
	 * getServletPath()는 디코딩·정규화되고 ;path-parameter가 제거된 경로다.
	 * getRequestURI()로 비교하면 /api/uploads;x=1 같은 변형이 제한을 빠져나간다.
	 */
	private static String bucketFor(HttpServletRequest request) {
		String method = request.getMethod();
		String path = request.getServletPath();
		if (method.equals("POST") && path.equals("/api/uploads")) {
			return "upload";
		}
		if (POLICY_METHODS.contains(method) && path.startsWith("/api/extensions")) {
			return "policy";
		}
		return null; // GET·정적 파일·actuator는 제한하지 않는다
	}

	/** 필터는 @RestControllerAdvice 밖이라 §5-1 형식 응답을 직접 쓴다. */
	private void writeRateLimited(HttpServletResponse response) throws IOException {
		response.setStatus(ErrorCode.RATE_LIMITED.status().value());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.getOutputStream().write(rateLimitedBody);
	}

}
