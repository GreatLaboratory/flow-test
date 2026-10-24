package com.example.extblock.policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.example.extblock.IntegrationTestBase;
import com.jayway.jsonpath.JsonPath;

/** DESIGN §6-3 / §12 T4 + IMPL_NOTES §7-6·7. 시작 시점은 CountDownLatch로 맞춘다. */
class ConcurrencyTest extends IntegrationTestBase {

	@Autowired
	DataSource dataSource;

	/** 모든 작업을 동시에 출발시켜 결과를 모은다 */
	<T> List<T> race(List<Callable<T>> tasks) throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
		try {
			CountDownLatch ready = new CountDownLatch(tasks.size());
			CountDownLatch go = new CountDownLatch(1);
			List<Future<T>> futures = new ArrayList<>();
			for (Callable<T> t : tasks) {
				futures.add(pool.submit(() -> {
					ready.countDown();
					go.await();
					return t.call();
				}));
			}
			ready.await();
			go.countDown();
			List<T> results = new ArrayList<>();
			for (Future<T> f : futures) {
				results.add(f.get(30, TimeUnit.SECONDS));
			}
			return results;
		}
		finally {
			pool.shutdownNow();
		}
	}

	Callable<Integer> add(String name) {
		return () -> json("POST", "/api/extensions/custom", "{\"name\":\"" + name + "\"}").statusCode();
	}

	@Test // C1 199개에서 동시 2개 → 정확히 200
	void limitHoldsUnderRace() throws Exception {
		for (int i = 1; i <= 199; i++) {
			insertCustom("x" + i);
		}
		List<Integer> codes = race(List.of(add("a1"), add("a2")));
		assertThat(codes).containsExactlyInAnyOrder(201, 409);
		assertThat(customCount()).isEqualTo(200);
	}

	@Test // C1' 199개에서 동시 10개 → 여전히 200, 성공 1
	void limitHoldsUnderWiderRace() throws Exception {
		for (int i = 1; i <= 199; i++) {
			insertCustom("x" + i);
		}
		List<Callable<Integer>> tasks = new ArrayList<>();
		for (int i = 0; i < 10; i++) {
			tasks.add(add("b" + i));
		}
		assertThat(race(tasks)).filteredOn(c -> c == 201).hasSize(1);
		assertThat(customCount()).isEqualTo(200);
	}

	@Test // C2 같은 이름 동시 10개 → 1행
	void sameNameRace() throws Exception {
		List<Callable<Integer>> tasks = new ArrayList<>();
		for (int i = 0; i < 10; i++) {
			tasks.add(add("sh"));
		}
		List<Integer> codes = race(tasks);
		assertThat(codes).filteredOn(c -> c == 201).hasSize(1);
		assertThat(codes).filteredOn(c -> c == 409).hasSize(9);
		assertThat(customCount()).isEqualTo(1);
		assertThat(auditActions()).containsExactly("CUSTOM_ADD");
	}

	@Test // C3 같은 고정 동시 PUT true ×5 → 감사 1행
	void sameFixedPutRace() throws Exception {
		long exe = fixedId("exe");
		List<Callable<Integer>> tasks = new ArrayList<>();
		for (int i = 0; i < 5; i++) {
			tasks.add(() -> json("PUT", "/api/extensions/fixed/" + exe, "{\"blocked\":true}").statusCode());
		}
		assertThat(race(tasks)).containsOnly(200);
		assertThat(auditActions()).containsExactly("FIXED_BLOCK");
	}

	@Test // C5 (§7-6) true·false 동시 PUT → 응답·최종 상태·감사가 서로 모순 없음
	void oppositePutRace() throws Exception {
		long exe = fixedId("exe");
		for (int round = 0; round < 20; round++) {
			resetState();
			List<HttpResponse<String>> res = race(List.<Callable<HttpResponse<String>>>of(
					() -> json("PUT", "/api/extensions/fixed/" + exe, "{\"blocked\":true}"),
					() -> json("PUT", "/api/extensions/fixed/" + exe, "{\"blocked\":false}")));
			// 각 응답의 blocked는 자기가 요청한 값(그 시점의 결과 상태)
			assertThat(JsonPath.<Boolean>read(res.get(0).body(), "$.blocked")).isTrue();
			assertThat(JsonPath.<Boolean>read(res.get(1).body(), "$.blocked")).isFalse();
			// 감사를 초기 상태(false)부터 재생하면 최종 DB 상태와 같아야 한다
			boolean replay = false;
			for (String a : auditActions()) {
				assertThat(a).isEqualTo(replay ? "FIXED_UNBLOCK" : "FIXED_BLOCK");
				replay = !replay;
			}
			boolean actual = jdbc.sql("SELECT blocked FROM extension WHERE id = ?").param(exe).query(Boolean.class).single();
			assertThat(actual).as("round %d audit=%s", round, auditActions()).isEqualTo(replay);
		}
	}

	/** 커스텀 추가와 같은 잠금을 다른 커넥션에서 잡고 있는 상태 */
	Connection holdAddLock() throws Exception {
		Connection c = dataSource.getConnection();
		c.setAutoCommit(false);
		try (Statement s = c.createStatement()) {
			s.execute("LOCK TABLE extension IN SHARE ROW EXCLUSIVE MODE");
		}
		return c;
	}

	@Test // C4 잠금 중에도 조회·업로드 판정(findBlockedNames)은 대기하지 않음
	void readsDoNotWaitForAddLock() throws Exception {
		setFixed("exe", true);
		try (Connection lock = holdAddLock()) {
			CompletableFuture<Integer> upload = CompletableFuture.supplyAsync(() -> upload("a.exe", new byte[] { 1 }).statusCode());
			CompletableFuture<Integer> get = CompletableFuture.supplyAsync(() -> json("GET", "/api/extensions", null).statusCode());
			assertThat(upload.get(3, TimeUnit.SECONDS)).isEqualTo(422);
			assertThat(get.get(3, TimeUnit.SECONDS)).isEqualTo(200);
			lock.rollback();
		}
	}

	@Test // C6 (§7-7) 잠금 중 DELETE는 대기했다가 해제 후 정상 처리
	void deleteWaitsForAddLockThenSucceeds() throws Exception {
		insertCustom("sh");
		long id = jdbc.sql("SELECT id FROM extension WHERE name='sh'").query(Long.class).single();
		CompletableFuture<Integer> del;
		try (Connection lock = holdAddLock()) {
			del = CompletableFuture.supplyAsync(() -> json("DELETE", "/api/extensions/custom/" + id, null).statusCode());
			try {
				del.get(700, TimeUnit.MILLISECONDS);
				throw new AssertionError("DELETE가 잠금을 기다리지 않았다");
			}
			catch (TimeoutException expected) {
				// 대기 중
			}
			lock.commit();
		}
		assertThat(del.get(5, TimeUnit.SECONDS)).isEqualTo(204);
		assertThat(customCount()).isZero();
	}

}
