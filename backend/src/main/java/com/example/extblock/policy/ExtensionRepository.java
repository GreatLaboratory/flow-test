package com.example.extblock.policy;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** extension 테이블 SQL 전부. 정합성 장치(UNIQUE·LOCK·조건부 UPDATE)가 SQL에 그대로 보이도록 JdbcClient를 쓴다. */
@Repository
public class ExtensionRepository {

	private static final String COLUMNS = "id, name, type, blocked, created_at";

	private final JdbcClient jdbc;

	public ExtensionRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public List<ExtensionRow> findAll() {
		return jdbc.sql("SELECT " + COLUMNS + " FROM extension ORDER BY id").query(ExtensionRow.class).list();
	}

	/**
	 * 커스텀 추가 트랜잭션 전용. 자기 자신·UPDATE·DELETE와 충돌하고 SELECT와는 충돌하지 않는다
	 * → 추가끼리는 직렬화되지만 조회와 업로드 판정은 막히지 않는다. 트랜잭션 종료 시 자동 해제.
	 */
	public void lockTable() {
		jdbc.sql("LOCK TABLE extension IN SHARE ROW EXCLUSIVE MODE").update();
	}

	public Optional<ExtensionRow> findByName(String name) {
		return jdbc.sql("SELECT " + COLUMNS + " FROM extension WHERE name = :name")
			.param("name", name)
			.query(ExtensionRow.class)
			.optional();
	}

	public Optional<ExtensionRow> findFixedById(long id) {
		return jdbc.sql("SELECT " + COLUMNS + " FROM extension WHERE id = :id AND type = 'FIXED'")
			.param("id", id)
			.query(ExtensionRow.class)
			.optional();
	}

	public int countCustom() {
		return jdbc.sql("SELECT count(*) FROM extension WHERE type = 'CUSTOM'").query(Integer.class).single();
	}

	/** type·blocked는 클라이언트 값과 무관하게 항상 CUSTOM·true. UNIQUE 위반 시 DuplicateKeyException. */
	public ExtensionRow insertCustom(String name) {
		return jdbc.sql("INSERT INTO extension (name, type, blocked) VALUES (:name, 'CUSTOM', true) RETURNING " + COLUMNS)
			.param("name", name)
			.query(ExtensionRow.class)
			.single();
	}

	/**
	 * 조건부 UPDATE 하나로 원자적 처리. 이미 그 상태이거나 고정 행이 아니면 0을 반환한다.
	 * 동시 요청 둘이 와도 행 잠금 때문에 하나만 1을 받는다 → 감사 기록 중복 없음.
	 */
	public int updateFixedBlocked(long id, boolean blocked) {
		return jdbc.sql("UPDATE extension SET blocked = :blocked WHERE id = :id AND type = 'FIXED' AND blocked <> :blocked")
			.param("id", id)
			.param("blocked", blocked)
			.update();
	}

	/** type = 'CUSTOM' 조건이 조작된 요청으로 고정 행을 지우는 것을 막는다. 삭제된 name을 반환. */
	public Optional<String> deleteCustom(long id) {
		return jdbc.sql("DELETE FROM extension WHERE id = :id AND type = 'CUSTOM' RETURNING name")
			.param("id", id)
			.query(String.class)
			.optional();
	}

	/** 업로드 판정용. ponytail: 캐시 없음(매 업로드 조회). 업로드 폭증 시 메모리 캐시 + 쓰기 시 무효화. */
	public Set<String> findBlockedNames() {
		return jdbc.sql("SELECT name FROM extension WHERE blocked").query(String.class).set();
	}

}
