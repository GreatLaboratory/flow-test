# DESIGN — 파일 업로드 확장자 차단

> 계획 세션 산출물. 이후 코드/테스트/보안/배포/마감 세션은 이 문서를 **기준 스펙**으로 삼는다.
> 각 결정의 상세 근거와 "구현/제외/다르게 처리" 판단은 `CONSIDERATIONS.md`에 있다. 이 문서는 **무엇을 어떻게 만드는가**에 집중한다.
>
> **원칙**: "작지만 설명되는" 결과물. 코드 한 줄도 설명 못 하면 안 된다. 추상화는 두 번째 구현체가 생길 때 만든다.

---

## 0. 목표와 범위

**목표**: 확장자 차단 정책(고정 7개 + 커스텀 최대 200개)을 관리하는 화면과, 그 정책을 **서버에서 강제**하는 실제 파일 업로드를 구현·배포한다.

| 범위 안 | 범위 밖 (근거는 CONSIDERATIONS.md) |
|---|---|
| 고정 확장자 체크/해제 (DB 저장) | 다운로드/파일 목록 기능 |
| 커스텀 확장자 추가/삭제 (20자, 200개, 중복·충돌 방지) | 인증/로그인 |
| 업로드 + 서버 판정 (확장자 + MZ 시그니처) | 압축 파일 내부 검사, 백신 스캔 |
| 정책 변경 감사 테이블 | 다중 파일 업로드, 진행률 표시 |
| IP 기준 rate limit | 화이트리스트 모드, 사용자별 정책 |
| 배포 (Render + Neon, A-37) | 실시간 동기화(WebSocket/polling) |

## 1. 위협 모델 (모든 판단의 기준)

- 서버는 **Linux 컨테이너**. 업로드 파일은 **UUID 이름으로 저장**하고 **어떤 경로로도 실행·서빙하지 않는다.**
- 공격자는 임의의 multipart 요청(파일명, Content-Type, 바이트, 헤더)을 조작할 수 있다.
- 관리 화면은 **인증 없이 공개**된다(의도적 결정). 대신 변경은 감사 테이블에 영속 기록되고 rate limit으로 남용 비용을 높인다.
- 이 환경에서 성립하지 않는 위협(Windows/IIS 전용 공격 등)은 막지 않고, 왜 해당 없는지 문서로 남긴다.

---

## 2. 기술 스택과 근거

| 영역 | 선택 | 근거 | 기각한 대안 |
|---|---|---|---|
| 백엔드 | **Spring Boot 4.1.x / Java 21 / Gradle** | 트랜잭션 처리의 강점. 서비스가 확장될수록 데이터 정합성이 중요해진다. 3.5는 2026-06-30 OSS 지원 종료, 4.0은 2026-12-31 종료 → 면접 시점에 지원되는 4.1 | Node+TS(정규식 공유 이점은 있으나 트랜잭션·잠금 직접 관리), FastAPI |
| DB | **PostgreSQL** | `LOCK TABLE ... IN SHARE ROW EXCLUSIVE MODE`가 트랜잭션 안에서 그대로 동작 (200개 상한 race 방어) | MySQL: `LOCK TABLES`가 진행 중인 트랜잭션을 **암묵적으로 커밋** → `@Transactional`과 공존 불가 |
| 데이터 접근 | **JdbcClient** (Spring 6.1+) | 정합성 장치(UNIQUE·LOCK·조건부 UPDATE·감사)가 모두 SQL 수준이라 SQL이 코드에 보이는 쪽이 설명에 유리. 트랜잭션은 `@Transactional` 그대로 | JPA: LOCK은 native query 필요, `save()`의 UNIQUE 위반이 flush/커밋 시점에 터지는 함정 |
| 마이그레이션 | **Flyway** | 버전 관리되는 SQL = table schema 제출물의 원본. 향후 확장(`scope` 컬럼 등)이 그대로 V3 파일이 됨 | `schema.sql`: 변경 이력 없음 |
| 테스트 DB | **Testcontainers (Postgres)** | H2는 `LOCK TABLE ... IN SHARE ROW EXCLUSIVE MODE`를 지원하지 않음. 운영과 같은 DB여야 동시성 테스트가 의미 있음 | H2 |
| 프론트 | **React + Vite + TypeScript**, Spring static으로 같은 출처 서빙 | React에 익숙함. **JSX 자동 이스케이프로 XSS 방어가 기본값.** 같은 출처 → CORS 없음, 배포 1개 | Vanilla JS(XSS 수동 관리), Thymeleaf, 별도 배포(CORS + 배포 2개) |
| Rate limit | **Bucket4j core + Caffeine `ProxyManager`** | 저장소 경계(`ProxyManager`)를 라이브러리가 제공 → Redis 전환 시 ProxyManager만 교체. Caffeine 만료로 IP 키 메모리 누수 방지 | 직접 구현(`ConcurrentHashMap` 키 무한 증가), starter(Boot 4 호환 미확인) |
| 로그 | **Spring Boot 내장 structured logging** (`logging.structured.format.console`) | JSON 한 줄 출력이 프레임워크 기본 기능. 개행 이스케이프로 로그 인젝션 해결 | logback 커스텀 인코더 |
| 헬스체크 | **Spring Boot Actuator** (`health`만 노출) | 이미 있는 기능. DB 연결까지 확인 | 직접 구현 |
| 배포 | ~~Railway Hobby~~ → **Render 무료(웹, Docker) + Neon 무료(Postgres)** (배포 세션 변경, CONSIDERATIONS A-37) | 면접 당일 접속 = 필수 제출물. Railway는 10/21 보안 사고 후 신규 가입이 막혀 사용 불가. Render 무료 DB는 30일 만료라 DB만 Neon. 대가: 15분 무요청 시 슬립(첫 접속 1~2분) | Vercel(요청 본문 약 4.5MB 한도·로컬 디스크 없음·메모리 rate limit 무의미 → **설계상 탈락**), Render 무료(Postgres 30일 만료), Lightsail(직접 운영 부담), 클라우드타입(디스크 임시) |

> ⚠️ **AI 활용 주의**: Boot 4는 Jackson 3, starter 모듈화 등 3.x와 다르다. AI가 Boot 3 시절 패턴을 낼 수 있으므로 코드 세션은 의존성 좌표·패키지명을 공식 문서로 검증한다.

---

## 3. 시스템 구성

```
 Browser ──HTTPS──▶ Cloudflare ─▶ Render LB ──▶ [ Spring Boot container ]   (Render free, singapore)
                                              ├─ static/   (React build, same origin)
                                              ├─ /api/**   (REST)
                                              ├─ /actuator/health
                                              ├─ RateLimitFilter (Bucket4j + Caffeine)
                                              └─ /data/uploads  (ephemeral disk, UUID files)
                                                     │
                                                     ▼
                                           [ Neon PostgreSQL 17 ]   (ap-southeast-1, direct endpoint)
```

- 서버 **1대** 전제(메모리 rate limit). 다중 인스턴스 전환 경로는 §10.
- Render 컨테이너 디스크는 **휘발성**이다(재배포·재시작·슬립 시 삭제). 업로드 파일은 재배포 시 사라진다 → 의도적으로 수용(파일을 다시 읽는 기능이 없음 + 남용으로 디스크가 차도 재배포 시 초기화).

### 저장소 구조

```
/backend                Spring Boot (Gradle, Java 21)
  build.gradle
  compose.yaml          로컬 개발용 Postgres (spring-boot-docker-compose가 자동 기동)
  src/main/java/.../    §7 패키지
  src/main/resources/
    application.yml
    db/migration/V1__schema.sql, V2__seed_fixed.sql
  src/test/java/...     (테스트 세션 작성)
/frontend               React + Vite + TS
/db/reset.sql           운영자 수동 실행용 데모 리셋 (Flyway 밖)
/docs                   DESIGN.md, IMPL_NOTES.md, VERIFICATION.md, SECURITY_REVIEW.md
Dockerfile              멀티스테이지: node build → gradle build → JRE runtime
CONSIDERATIONS.md  README.md  PROMPT_LOG.md
```

---

## 4. DB 스키마

### 4-1. `V1__schema.sql`

```sql
CREATE TABLE extension (
  id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  name       VARCHAR(20) NOT NULL UNIQUE,
  type       VARCHAR(10) NOT NULL CHECK (type IN ('FIXED','CUSTOM')),
  blocked    BOOLEAN     NOT NULL DEFAULT FALSE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT extension_name_format CHECK (name ~ '^[a-z0-9]{1,20}$'),
  CONSTRAINT custom_always_blocked CHECK (type = 'FIXED' OR blocked)
);

CREATE TABLE policy_audit (
  id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  action         VARCHAR(20) NOT NULL
                 CHECK (action IN ('FIXED_BLOCK','FIXED_UNBLOCK','CUSTOM_ADD','CUSTOM_DELETE')),
  extension_name VARCHAR(20) NOT NULL,
  client_ip      VARCHAR(45),
  user_agent     VARCHAR(512),
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

### 4-2. `V2__seed_fixed.sql`

```sql
INSERT INTO extension (name, type, blocked) VALUES
  ('bat','FIXED',false), ('cmd','FIXED',false), ('com','FIXED',false), ('cpl','FIXED',false),
  ('exe','FIXED',false), ('scr','FIXED',false), ('js','FIXED',false)
ON CONFLICT (name) DO NOTHING;
```

### 4-3. `/db/reset.sql` (운영자 수동 실행, 감사 기록 남지 않음 — 문서화)

```sql
BEGIN;
DELETE FROM extension WHERE type = 'CUSTOM';
UPDATE extension SET blocked = false WHERE type = 'FIXED';
COMMIT;
```

### 4-4. 컬럼/제약 결정 근거

| 결정 | 근거 |
|---|---|
| 테이블 1개 + `type` | `UNIQUE(name)` 하나가 **중복 추가와 고정/커스텀 충돌을 모두** DB에서 막는다. 동시 요청 race도 함께 해결 |
| surrogate `id` | 향후 사용자별 정책으로 UNIQUE가 `(scope, name)`이 되어도 PK가 그대로 유지된다. API 경로 식별자로도 사용(§5) |
| `type`은 CHECK | Postgres ENUM은 값 제거·변경 마이그레이션이 번거롭다 |
| `extension_name_format` CHECK | 앱 정규화를 우회한 직접 INSERT도 차단하는 심층 방어 |
| `custom_always_blocked` CHECK | "커스텀 행의 blocked는 항상 true"라는 규칙을 문서가 아닌 제약으로 보장 |
| 감사 테이블 **FK 없음** | 감사 기록은 대상보다 오래 살아야 한다. FK면 삭제 시 CASCADE로 기록이 사라지거나 RESTRICT로 삭제가 막힌다 → 이름을 값으로 복사 |
| `client_ip VARCHAR(45)` | `INET`은 이상한 값에서 INSERT가 실패하고, 감사는 정책 변경과 같은 트랜잭션이라 **정책 변경까지 롤백**된다. 추적 단서 때문에 본 기능 가용성을 해치지 않는다. 45 = IPv6 최대 문자열 길이 |
| `client_ip`/`user_agent` 이름 | "식별"이 아니라 "추적 단서"임을 이름으로 드러낸다(`actor` 아님) |
| `updated_at` 없음 | 변경 이력은 감사 테이블이 담당 |
| `TIMESTAMPTZ` | UTC 저장, 표시 시 KST 변환 |
| 인덱스 = `UNIQUE(name)` 자동 인덱스 하나 | 최대 207행이라 판정 쿼리는 전체 스캔으로 충분. `blocked`(boolean)는 카디널리티가 2라 인덱스를 만들어도 옵티마이저가 쓰지 않는다. 감사 테이블은 조회 기능이 생기면 `created_at` 인덱스 추가 |

---

## 5. API 설계

### 5-1. 공통 에러 형식

```json
{ "code": "BLOCKED_EXTENSION",
  "message": "'.exe' 확장자는 업로드가 차단되어 있습니다.",
  "details": { "fileName": "a.exe.txt", "matched": ["exe"] } }
```

`code`는 기계용(프론트 분기), `message`는 사람용(그대로 표시). `details`는 선택.

### 5-2. 엔드포인트

| 메서드 · 경로 | 요청 | 성공 | 실패 |
|---|---|---|---|
| `GET /api/extensions` | — | 200 `{ fixed:[{id,name,blocked}], custom:[{id,name,createdAt}], customLimit, maxFileSize }` (maxFileSize는 바이트) | — |
| `PUT /api/extensions/fixed/{id}` | `{ "blocked": true }` | 200 `{id,name,blocked}` | 404 `EXTENSION_NOT_FOUND`(없음 또는 커스텀 id), 400 `INVALID_REQUEST` |
| `POST /api/extensions/custom` | `{ "name": ".SH " }` | 201 `{id,name:"sh",createdAt}` (정규화된 값) | 400 `INVALID_EXTENSION`, 409 `FIXED_EXTENSION_CONFLICT` / `DUPLICATE_EXTENSION` / `CUSTOM_LIMIT_EXCEEDED` |
| `DELETE /api/extensions/custom/{id}` | — | **항상 204** (멱등) | — |
| `POST /api/uploads` | multipart, 파트 `file` 정확히 1개 | 201 `{key,fileName,size}` | 400 `MISSING_FILE` / `TOO_MANY_FILES` / `INVALID_FILENAME` / `INVALID_FILENAME_CHAR`, 413 `FILE_TOO_LARGE`, 422 `BLOCKED_EXTENSION` / `BLOCKED_CONTENT`, 500 `STORAGE_FAILED` |
| `GET /actuator/health` | — | 200 | 503 |
| 공통 (rate limit) | — | — | 429 `RATE_LIMITED` |

### 5-3. ErrorCode 전체 목록

| code | HTTP | 기본 message |
|---|---|---|
| `INVALID_REQUEST` | 400 | 요청 형식이 올바르지 않습니다. |
| `INVALID_EXTENSION` | 400 | 확장자는 영문 소문자와 숫자만, 1~20자로 입력하세요. |
| `EXTENSION_NOT_FOUND` | 404 | 해당 고정 확장자를 찾을 수 없습니다. |
| `FIXED_EXTENSION_CONFLICT` | 409 | '{name}'은(는) 고정 확장자입니다. 위 체크박스를 사용하세요. |
| `DUPLICATE_EXTENSION` | 409 | '{name}'은(는) 이미 등록되어 있습니다. |
| `CUSTOM_LIMIT_EXCEEDED` | 409 | 커스텀 확장자는 최대 {limit}개까지 등록할 수 있습니다. |
| `MISSING_FILE` | 400 | 업로드할 파일이 없습니다. |
| `TOO_MANY_FILES` | 400 | 한 번에 하나의 파일만 업로드할 수 있습니다. |
| `INVALID_FILENAME` | 400 | 파일명이 없거나 255자를 초과합니다. |
| `INVALID_FILENAME_CHAR` | 400 | 파일명에 허용되지 않는 문자(제어문자)가 포함되어 있습니다. |
| `FILE_TOO_LARGE` | 413 | 파일 크기는 최대 {max}까지 가능합니다. |
| `BLOCKED_EXTENSION` | 422 | '.{ext}' 확장자는 업로드가 차단되어 있습니다. |
| `BLOCKED_CONTENT` | 422 | 파일 내용이 실행 파일(PE) 형식이라 차단되었습니다. |
| `STORAGE_FAILED` | 500 | 파일 저장에 실패했습니다. 잠시 후 다시 시도하세요. |
| `RATE_LIMITED` | 429 | 요청이 너무 많습니다. 잠시 후 다시 시도하세요. |
| `INTERNAL_ERROR` | 500 | 일시적인 오류가 발생했습니다. |

### 5-4. 판단 포인트

- **경로 식별자는 id**: 오래된 탭 시나리오 — `sh`(id 42)가 삭제 후 재추가되어 id 43이 된 상태에서 오래된 탭이 DELETE를 보내면, name 경로는 **본 적 없는 새 `sh`를 지운다.** id 경로는 42가 없으니 204로 끝난다("내가 본 그 항목만 지운다"). REST 관례·확장(scope)과도 일치.
- **409 vs 422**: 중복·상한은 "현재 상태와의 충돌" → 409. 업로드 차단은 "요청 자체가 정책 위반" → 422. **415는 쓰지 않는다** — 요청 Content-Type 미지원이라는 뜻인데, 우리는 Content-Type을 판정에 쓰지 않는다.
- **차단 사유를 공개**: 정책 조회가 이미 공개라 숨길 정보가 없고, MZ 탐지 사실을 알아도 PE는 MZ 없이 실행되지 않는다. 반면 사유를 숨기면 UX 손해가 확실하다.
- **조회 API 하나 + `customLimit`·`maxFileSize` 서버 제공**: 한도 값이 설정 한 곳(`AppProperties`)에만 존재한다. 한도를 바꿔도 프론트 수정이 필요 없다.

---

## 6. 핵심 로직

### 6-1. 업로드 판정 — `ExtensionJudge.judge(fileName, head, blocked)`

```
1. fileName == null/빈 값                       → INVALID_FILENAME  (컨트롤러에서 MISSING_FILE과 구분)
2. base = 마지막 '/' 또는 '\' 뒤                  (경로 포함 파일명 대응)
3. base 길이 == 0 또는 > 255                     → INVALID_FILENAME
3a. base에 제어문자(U+0000~001F, U+007F)           → INVALID_FILENAME_CHAR  (`a.exe\0` `a.exe<DEL>` `a.exe\t`)
4. trimmed = base 끝의 '.'·' ' 반복 제거          (Windows가 file.exe. → file.exe로 저장)
4a. trimmed 길이 == 0                             → INVALID_FILENAME  (`..` `...` `. .` `"   "` = 파일명 없음과 같다)
5. segments = trimmed.split('.') 에서 첫 구간 제외, 각 구간 대소문자 접기(toUpperCase→toLowerCase, ROOT), 빈 구간 제외
      a.exe.txt → {exe, txt} · .env → {env} · Makefile → {} · exe.txt → {txt}
6. matched = segments ∩ blocked                  비어있지 않으면 → BLOCKED_EXTENSION (우선)
7. head 앞 2바이트 == 'M','Z' 이고 {exe,com,scr,cpl} ∩ blocked ≠ ∅
                                                 → BLOCKED_CONTENT
8. 통과
```

- 순수 함수(정적 메서드). DB·IO 없음 → 엣지케이스를 매개변수화 단위 테스트로 빠르게 검증.
- `head`는 최대 2바이트, 길이 0·1이어도 예외 없이 처리.
- 확장자와 내용이 모두 해당하면 **`BLOCKED_EXTENSION` 우선** — 사용자가 이해하기 쉬운 사유.
- 0바이트 파일은 통과(차단 사유 없음).
- **4a단계 추가 (테스트 세션, 2026-10-24)**: 독립 테스트가 스펙 순서 결함을 발견했다. 원래 순서는 빈 값 검사(3단계)가 끝 정리(4단계)보다 **앞에만** 있어서, 정리 후 빈 이름이 되는 `...` 같은 파일명이 통과·저장됐다(구현은 스펙을 그대로 따랐음). 사람이 "파일명 없음과 같다"로 판정 → 4a 추가. 근거: `docs/VERIFICATION.md` B1, `CONSIDERATIONS.md` A-24.
- **3a단계 추가 (보안 세션, 2026-10-25)**: `filename*=UTF-8''a.exe%00` → 201 `"a.exe\u0000"`, `%7F`(DEL) → 화면에 `'a.exe' 업로드 성공`. `filename*`은 Tomcat이 디코딩하지 않아 Tomcat의 NUL 검사도 지나간다. 정상 파일명엔 제어문자가 없어(Windows는 금지) 오탐 비용 0 → 거부. 끝 정리(4단계) **앞**에 둬서 `file.exe.\n` 같은 조합도 정리 전에 걸린다. 사유가 "없음/길이"와 달라 code를 분리했다. 근거: `docs/SECURITY_REVIEW.md` S3, `CONSIDERATIONS.md` A-32.
- **5단계 접기 변경 (보안 세션, 2026-10-25)**: 소문자화만 하면 `ſ`(U+017F)·`ı`(U+0131)가 남는다. 대문자로 바꾸면 ASCII `S`·`I`가 되므로, Windows가 확장자를 대소문자 무시로 비교할 때 `a.ſcr`가 `.scr`로 취급될 가능성(미검증)을 1줄로 닫는다. ASCII에서는 기존 소문자화와 결과가 같아 정상 판정이 바뀌지 않는다(J30·J31). 근거: `CONSIDERATIONS.md` A-33.

### 6-2. 커스텀 입력 정규화 — `ExtensionNormalizer.normalize(raw)`

```
trim → 앞의 '.' 모두 제거 → 소문자화(Locale.ROOT) → ^[a-z0-9]{1,20}$ 불일치 시 INVALID_EXTENSION
```

- ASCII 영숫자만 허용하므로 바이트/코드포인트/그래펨 길이가 모두 같다 → "20자" 정의가 `VARCHAR(20)`과 정확히 일치.
- `Locale.ROOT`: 터키어 로케일에서 `"I".toLowerCase()`가 점 없는 ı가 되는 문제 회피.
- 프론트 `normalize.ts`도 같은 규칙(즉시 피드백용). 기준은 서버.

### 6-3. 트랜잭션별 쿼리

**커스텀 추가** — `@Transactional`
```sql
LOCK TABLE extension IN SHARE ROW EXCLUSIVE MODE;
SELECT id, type FROM extension WHERE name = :name;          -- FIXED → 409 CONFLICT, CUSTOM → 409 DUPLICATE
SELECT count(*) FROM extension WHERE type = 'CUSTOM';       -- >= limit → 409 LIMIT
INSERT INTO extension (name, type, blocked) VALUES (:name, 'CUSTOM', true)
  RETURNING id, name, created_at;                           -- DuplicateKeyException → 409 (최종 방어선)
INSERT INTO policy_audit (action, extension_name, client_ip, user_agent) VALUES ('CUSTOM_ADD', ...);
```
- 이 잠금 모드는 **자기 자신 및 UPDATE/DELETE와 충돌**하고 **SELECT(ACCESS SHARE)와는 충돌하지 않는다** → 추가 중 다른 추가·변경·삭제는 잠깐 대기하지만 **조회와 업로드 판정은 막히지 않는다.**
- 잠금 아래에서는 사전 조회 결과가 정확하므로 친절한 에러 메시지를 줄 수 있다. UNIQUE는 최종 방어선.
- 정책 변경 → 감사 INSERT가 같은 트랜잭션: 변경은 됐는데 기록이 없는 상태가 원천적으로 불가능.

**고정 체크 변경** — `@Transactional`
```sql
UPDATE extension SET blocked = :b WHERE id = :id AND type = 'FIXED' AND blocked <> :b;
-- 1행: 감사 INSERT ('FIXED_BLOCK' | 'FIXED_UNBLOCK')
-- 0행: SELECT 1 FROM extension WHERE id = :id AND type = 'FIXED' → 없으면 404, 있으면 이미 그 상태 → no-op (감사 없음)
```
- 조회 후 UPDATE로 짜면 동시 요청 둘이 모두 `false`를 읽고 감사 기록이 중복된다 → **조건부 UPDATE 하나로 원자적 처리.**

**커스텀 삭제** — `@Transactional`
```sql
DELETE FROM extension WHERE id = :id AND type = 'CUSTOM' RETURNING name;
-- 행 있으면 감사 INSERT ('CUSTOM_DELETE'). 응답은 항상 204
```
- `type = 'CUSTOM'` 조건이 고정 행 삭제를 막는다(테이블 1개 방안의 보완).

**업로드 판정 조회** — 트랜잭션 없음, 캐시 없음
```sql
SELECT name FROM extension WHERE blocked;
```

### 6-4. 업로드 흐름

```
RateLimitFilter(upload: IP당 10/분)
→ Spring multipart 제한 (max-file-size 10MB) — 초과 시 MaxUploadSizeExceededException → 413
→ UploadController: getFiles("file") 0개 → MISSING_FILE, 2개 이상 → TOO_MANY_FILES
→ UploadService:
     head = 앞 2바이트 읽기
     blocked = repository.findBlockedNames()
     verdict = ExtensionJudge.judge(originalFilename, head, blocked)
     차단 → ApiException(422)
     통과 → key = FileStorage.save(inputStream)
     구조화 로그 1줄 (결과, code, matched, size, ip, key, fileName)
→ 201 {key, fileName, size}
```

- **판정이 끝나기 전에는 최종 저장 위치에 쓰지 않는다.**
- 단, Spring multipart는 컨트롤러 전에 요청을 **임시 파일**로 쓴다(`file-size-threshold` 초과 시). 차단될 파일도 잠깐은 디스크에 닿지만, 요청 종료 시 Spring이 삭제하고 실행되지 않는 위치다 → 수용·문서화.
- 클라이언트 `Content-Type`은 **읽지도, 응답에 되돌리지도 않는다.**
- 크기 초과 시 Tomcat이 연결을 끊어(`max-swallow-size`) 413이 브라우저에 도달하지 않을 수 있다 → 클라이언트 사전 검사가 필수인 이유.

### 6-5. `FileStorage.save(InputStream)` — 저장 경계

```
key = UUID.randomUUID().toString()          (확장자 붙이지 않음)
target = storageDir.resolve(key)             (원본 파일명은 경로에 절대 사용하지 않음)
Files.copy(in, target)
실패(IOException) → Files.deleteIfExists(target) 후 ApiException(STORAGE_FAILED)
return key
```
- 외부 스토리지 전환 시 **이 메서드 본문만** 교체. 인터페이스는 만들지 않는다.
- 기동 시 `storageDir`가 없으면 생성.

---

## 7. 백엔드 구조

패키지는 **기능 단위**(기능이 2개뿐이라 계층별 분리는 파일만 흩뜨린다). 루트 패키지 `com.example.extblock`(코드 세션에서 확정 가능).

```
policy/
  ExtensionController      GET /api/extensions, PUT fixed/{id}, POST custom, DELETE custom/{id}
  ExtensionService         §6-3 트랜잭션 3개 (@Transactional)
  ExtensionRepository      JdbcClient. extension 테이블 SQL 전부
  PolicyAuditRepository    JdbcClient. policy_audit INSERT
  ExtensionNormalizer      §6-2 순수 함수
  dto: ExtensionView, FixedView, CustomView, SetBlockedRequest, AddCustomRequest (record)
upload/
  UploadController         POST /api/uploads
  UploadService            §6-4
  ExtensionJudge           §6-1 순수 함수 + Verdict record
  FileStorage              §6-5 save 경계
common/
  ErrorCode                §5-3 enum (HttpStatus, defaultMessage)
  ApiException             RuntimeException(ErrorCode, message, details)
  GlobalExceptionHandler   @RestControllerAdvice → §5-1 형식. MaxUploadSizeExceededException→413, 그 외→500 INTERNAL_ERROR(스택은 로그에만)
  RateLimitFilter          OncePerRequestFilter. Bucket4j + Caffeine ProxyManager
  ClientInfo               record(ip, userAgent). from(HttpServletRequest): ip=request.getRemoteAddr(), UA 512자 절단
config/
  AppProperties            @ConfigurationProperties("app") record (§9)
```

### 7-1. 주요 시그니처 (태스크 간 계약)

```java
// upload
public final class ExtensionJudge {
    public static final Set<String> PE_FAMILY = Set.of("exe", "com", "scr", "cpl");
    public static Verdict judge(String fileName, byte[] head, Set<String> blocked);
}
public record Verdict(ErrorCode code, String baseName, List<String> matched) {
    public boolean allowed() { return code == null; }
}
public class FileStorage { public String save(InputStream in); }

// policy
public final class ExtensionNormalizer { public static String normalize(String raw); } // throws ApiException(INVALID_EXTENSION)

public class ExtensionRepository {
    List<ExtensionRow> findAll();
    void lockTable();
    Optional<ExtensionRow> findByName(String name);
    int countCustom();
    ExtensionRow insertCustom(String name);
    int updateFixedBlocked(long id, boolean blocked);   // 영향받은 행 수
    boolean existsFixed(long id);
    Optional<String> deleteCustom(long id);             // 삭제된 name
    Set<String> findBlockedNames();
}
public record ExtensionRow(long id, String name, String type, boolean blocked, OffsetDateTime createdAt) {}

public class PolicyAuditRepository { void insert(AuditAction action, String extensionName, ClientInfo client); }
public enum AuditAction { FIXED_BLOCK, FIXED_UNBLOCK, CUSTOM_ADD, CUSTOM_DELETE }

public class ExtensionService {
    ExtensionView getAll();
    FixedView setFixedBlocked(long id, boolean blocked, ClientInfo client);
    CustomView addCustom(String rawName, ClientInfo client);
    void deleteCustom(long id, ClientInfo client);
}

// common
public record ClientInfo(String ip, String userAgent) { static ClientInfo from(HttpServletRequest req); }
public class ApiException extends RuntimeException {
    ApiException(ErrorCode code);
    ApiException(ErrorCode code, String message, Map<String, Object> details);
}
```

### 7-2. 클라이언트 IP 처리

- `server.forward-headers-strategy=native` → Tomcat `RemoteIpValve`가 **신뢰하는 프록시가 붙인 값만** 반영해 `getRemoteAddr()`를 교정한다.
- 앱 코드는 `X-Forwarded-For`를 **직접 파싱하지 않는다** — 맨 왼쪽 값은 클라이언트가 위조 가능.
- 배포 환경(Render)에서는 XFF 마지막 값이 Cloudflare 엣지 IP라 그대로는 클라이언트 IP가 되지 않는다 → 배포 환경변수 `SERVER_TOMCAT_REMOTEIP_REMOTE_IP_HEADER=CF-Connecting-IP`로 Cloudflare가 덮어쓰는 단일 값 헤더를 읽는다(CONSIDERATIONS A-38, 배포 세션 실측).

### 7-3. 구현 세부 규칙 (자족성 점검에서 보강)

- **RateLimitFilter는 에러 응답을 직접 쓴다**: 서블릿 필터는 `@RestControllerAdvice` 밖에서 실행된다. 429 응답 본문(§5-1 형식, `RATE_LIMITED`)과 `Content-Type: application/json`을 필터가 직접 작성한다.
- **RateLimitFilter는 `request.getParameter*()`·`getParts()`를 호출하지 않는다**: 호출하면 multipart 본문 파싱이 시작돼, 한도를 넘은 요청도 10MB를 끝까지 받게 된다. 판단은 메서드·URI·`getRemoteAddr()`만으로 한다.
- **[정정 — 보안 세션] 이 필터보다 먼저 본문을 읽는 필터가 있으면 위 규칙은 무의미하다**: Spring Boot 기본 `FormContentFilter`(order −9900)는 `PUT|PATCH|DELETE` + urlencoded 본문을 **이 필터보다 먼저, 경로와 무관하게** 통째로 메모리에 읽었다(인증 없는 요청 1개로 `OutOfMemoryError` 재현). 이 앱엔 form 엔드포인트가 없으므로 `spring.mvc.formcontent.filter.enabled=false`로 끈다. OOM 스택에서 확인한 순서는 CharacterEncodingFilter → ServerHttpObservationFilter → FormContentFilter → … → RateLimitFilter였고, 남은 앞쪽 필터들은 본문을 읽지 않는다. multipart 파싱과 Jackson은 그 뒤 DispatcherServlet에서 일어난다.
- **업로드 외 요청 본문 상한 16KB**: multipart 한도는 업로드에만 있어 JSON 본문은 무제한이었다(`name` 100MB → OOM). RateLimitFilter가 업로드 외 요청을 `ContentCachingRequestWrapper(request, 16KB)`로 감싸 `handleContentOverflow`에서 400 `INVALID_REQUEST`를 던진다. Content-Length가 아니라 **실제로 읽은 양** 기준이라 chunked도 막힌다. 가장 큰 정상 본문은 `{"name":"<20자>"}`. 근거: `docs/SECURITY_REVIEW.md` S1, `CONSIDERATIONS.md` A-30.
- **깨진 multipart는 400**: `MultipartException`(크기 초과 제외)은 클라이언트 오류 → `INVALID_REQUEST`. 이전엔 500 + 요청당 약 6KB ERROR 스택 로그(S2, A-31).
- **적용 대상**: `PUT|POST|DELETE /api/extensions/**` → policy 버킷, `POST /api/uploads` → upload 버킷. 키는 `"policy:" + ip` / `"upload:" + ip`. GET·정적 파일·actuator는 제한하지 않는다.
- **버킷 설정**: 용량 n, `refillIntervally(n, 1분)` (1분마다 n개 충전 — 고정 창과 유사해 설명이 쉽다). Caffeine: `expireAfterAccess(10분)`, `maximumSize(100_000)`.
  - **[정정 — 코드 세션 실측]** `expireAfterAccess`는 Bucket4j 8.14 `CaffeineProxyManager`와 함께 쓰면 `IllegalStateException`. 구현은 `expirationAfterWrite(basedOnTimeForRefillingBucketUpToMax(10분))` + `maximumSize(100_000)`(CONSIDERATIONS A-15, IMPL_NOTES §3-1).
- **MZ 읽기와 저장**: `MultipartFile.getInputStream()`은 호출할 때마다 새 스트림을 연다. 앞 2바이트는 첫 스트림에서 `readNBytes(2)`로 읽어 닫고, 저장은 새 스트림으로 `FileStorage.save()`에 넘긴다.
- **`MaxUploadSizeExceededException`**: DispatcherServlet이 multipart를 해석할 때 발생하므로 `GlobalExceptionHandler`에서 413으로 변환할 수 있다. 메시지의 `{max}`는 `AppProperties` 값으로 채운다.
- **에러 message 치환**: `{name}`, `{limit}`, `{max}`, `{ext}`는 `ApiException` 생성 시 `String.replace`로 채운다(템플릿 엔진 불필요). 여러 확장자가 걸리면 `{ext}`에는 첫 번째 값을 넣고, `details.matched`에 전체를 담는다.
- **업로드 로그 예시** (SLF4J fluent API, structured logging이 JSON 필드로 출력):
  `log.atInfo().addKeyValue("event","upload").addKeyValue("result","BLOCKED").addKeyValue("code",code).addKeyValue("matched",matched).addKeyValue("size",size).addKeyValue("ip",ip).addKeyValue("key",key).addKeyValue("fileName",name).log("upload")`
- **프론트 네트워크 실패**: `fetch`가 reject되거나 응답이 JSON이 아니면 `api.ts`가 클라이언트 전용 code `NETWORK_ERROR`("서버에 연결할 수 없습니다. 네트워크를 확인하고 다시 시도하세요.")로 변환한다. `types.ts`의 ErrorCode union에 포함한다.
- **컨테이너 실행 사용자**: Dockerfile에서 non-root 사용자로 실행하고, `/data/uploads`를 그 사용자 소유로 생성한다.

---

## 8. 프론트엔드 구조

```
frontend/src/
  main.tsx
  App.tsx                  레이아웃, 최초 로딩, 탭 포커스(visibilitychange) 시 재조회
  api.ts                   fetch 래퍼. 에러 응답을 ApiError{code,message,details}로 파싱
  types.ts                 ErrorCode union, ExtensionView 등 응답 타입
  normalize.ts             §6-2와 같은 규칙 (즉시 피드백용)
  components/
    FixedExtensionPanel.tsx   체크박스 7개. 요청 중 해당 체크박스 disabled
    CustomExtensionPanel.tsx  입력 폼(Enter 제출, maxLength=20), n/limit 표시, 칩 목록 + X
    UploadPanel.tsx           파일 선택, 크기 사전 검사, 업로드, 결과/차단 메시지
```

**UX 규칙**
- **비관적 UI**: 서버 성공 응답 후 반영. 성공 시 `GET /api/extensions` 재조회(서버가 기준). 롤백 코드 없음.
- 요청 중 해당 컨트롤 disabled — 더블클릭/응답 역전으로 인한 화면·DB 불일치 방지.
- 실패 시 화면 상태 유지 + 인라인 메시지(`message` 그대로) + 재시도 가능.
- 클라이언트 사전 검사: 커스텀 입력(정규화 규칙·목록 중복·상한 도달 시 입력 비활성화), 파일 크기(GET 응답의 `maxFileSize` 사용 — 하드코딩 없음).
- **업로드 확장자 사전 판정은 하지 않는다** — 규칙 중복으로 인한 불일치·오래된 정책(TOCTOU) 위험. 서버 차단 메시지가 곧 UX.
- 상태 관리는 `useState`만. 라우터·상태 라이브러리·UI 키트 없음.

**보안/접근성 규칙**
- `dangerouslySetInnerHTML` **금지**(grep으로 검증). 파일명은 항상 JSX 텍스트로 렌더링.
- 체크박스 `<label>`, X 버튼 `aria-label="{name} 삭제"`, 메시지 영역 `aria-live="polite"`, 추가 후 입력란 포커스 복귀, 목록 `flex-wrap`.

---

## 9. 설정값 (`AppProperties`, 환경변수로 덮어쓰기)

| 키 | 기본값 | 환경변수 | 비고 |
|---|---|---|---|
| `app.upload.max-file-size` | `10MB` | `APP_UPLOAD_MAX_FILE_SIZE` | `spring.servlet.multipart.max-file-size`에도 같은 값 연결, GET 응답 `maxFileSize`로 노출 |
| `app.upload.storage-dir` | `/data/uploads` | `APP_UPLOAD_STORAGE_DIR` | 웹 루트 밖 |
| `app.policy.custom-limit` | `200` | `APP_POLICY_CUSTOM_LIMIT` | GET 응답 `customLimit`로 노출 |
| `app.rate-limit.policy-per-minute` | `30` | `APP_RATE_LIMIT_POLICY_PER_MINUTE` | 변경 API(PUT/POST/DELETE) |
| `app.rate-limit.upload-per-minute` | `10` | `APP_RATE_LIMIT_UPLOAD_PER_MINUTE` | |
| `server.port` | `${PORT:8080}` | `PORT` | Render 주입(10000) |
| `spring.datasource.*` | — | `SPRING_DATASOURCE_URL/USERNAME/PASSWORD` | Neon 연결 문자열을 JDBC 형식으로 분해 |
| `server.tomcat.remoteip.remote-ip-header` | `X-Forwarded-For` | `SERVER_TOMCAT_REMOTEIP_REMOTE_IP_HEADER` | **배포에서만** `CF-Connecting-IP` (A-38) |

multipart 요청 한도 = `app.upload.max-file-size` + 1MB(경계·헤더 오버헤드)로 **자동 연동**한다(`MultipartConfigElement` 빈). 원래는 `max-request-size: 11MB` 고정값이었으나, 파일 한도를 올리면 메시지·`maxFileSize`와 실제 거절 지점이 어긋나는 것을 테스트 세션이 발견(VERIFICATION B2). `-1`(무제한)은 Tomcat `max-part-count` 기본 50 때문에 요청당 임시 디스크가 50배까지 커져 기각.
`management.endpoints.web.exposure.include=health`, `management.endpoint.health.show-details=never`.

---

## 10. 확장 지점 (면접 "규모가 커지면?" 대비)

| 지점 | 현재 | 한계 | 전환 방법 |
|---|---|---|---|
| 파일 저장 | `FileStorage.save()` 로컬 디스크 | 휘발성, 단일 서버 | 메서드 본문을 S3/R2 업로드로 교체 |
| Rate limit 저장소 | Bucket4j Caffeine `ProxyManager` | 인스턴스별 카운터, 재시작 시 초기화 | Redis `ProxyManager`로 교체 (카운터를 서버 간 공유) |
| 정책 캐시 | 없음(매 업로드 DB 조회) | 업로드 폭증 시 DB 부하 | 단일 서버: 메모리 캐시 + 쓰기 시 무효화 / 다중 서버: pub/sub 무효화 전파 |
| 다운로드 | 없음 | — | 메타데이터 테이블(key↔원본명) + `Content-Disposition: attachment` + `X-Content-Type-Options: nosniff` + `application/octet-stream` |
| 사용자별 정책 | 없음 | — | `scope`/`owner_id` 컬럼 + UNIQUE `(scope, name)` (Flyway V3) |
| 화이트리스트 | 없음 | 블랙리스트의 근본 한계 | `ExtensionJudge` 내부 판정만 교체 |
| 다중 파일 | 요청당 1개 | — | 응답을 배열로 확장 + 부분 성공 정책 결정 |
| 감사 조회 | 테이블만 | — | 조회 API + `created_at` 인덱스 |

코드에는 해당 위치에 `ponytail:` 주석으로 한계와 전환 경로를 남긴다.

---

## 11. 고려사항 처리 방침 요약

상세 근거는 `CONSIDERATIONS.md` 해당 번호 참조. ✅ 구현 / ⛔ 제외 / 🔀 다르게 처리.

| 영역 | 항목 | 방침 |
|---|---|---|
| 판정 | 이중 확장자 | ✅ 첫 구간 제외 모든 구간 검사 |
| | 대소문자 / 점 파일 / 확장자 없음 | ✅ 소문자화 / 점 뒤를 확장자로 / 허용 |
| | 끝의 점·공백, 경로 포함 파일명, 255자, 빈 파일명 | ✅ |
| | ADS·IIS `;`, null byte, RTL, 전각, NFC/NFD, `filename*` | ⛔ 위협 모델 밖 |
| 내용 | 매직넘버 | ✅ MZ 감지 시 PE 계열 추가 |
| | MIME 스푸핑 | 🔀 Content-Type을 아예 사용하지 않음 |
| | 폴리글롯, 압축 파일 내부 | ⛔ |
| 저장 | 원본 파일명 위험 | ✅ UUID 저장, 웹 루트 밖 |
| | 다운로드 / stored XSS | 🔀 서빙하지 않음 |
| | 크기·개수 | ✅ 10MB, 요청당 1개, 실패 시 부분 파일 삭제 |
| 정책 | 입력 검증 | ✅ 보정(trim·앞 점·소문자) + `^[a-z0-9]{1,20}$` |
| | 고정/커스텀 충돌, 중복 | ✅ 409 + `UNIQUE(name)` |
| | 200개 동시성 | ✅ `LOCK TABLE` + count |
| | 감사 | ✅ `policy_audit` 영속, 같은 트랜잭션 |
| | 인덱스·캐시 | ⛔ UNIQUE 외 인덱스·캐시 없음 (근거 있음) |
| 권한 | 인증 | ⛔ 의도적 제외, 감사 + rate limit으로 보완 |
| | 남용 | ✅ rate limit, 상태 무변화 시 감사 생략, 리셋 SQL |
| UX | 차단 메시지 | ✅ code/message 분리, 사유 공개 |
| | 로딩·에러·정합성 | ✅ 비관적 UI + 성공 후 재조회 + 요청 중 disabled |
| | 클라/서버 검증 분리 | ✅ 서버가 기준, 업로드 확장자 사전 판정은 ⛔ |
| | 접근성 | ✅ 네이티브 기본만 |
| 운영 | 동시 편집 | ✅ 항목 단위 변경 + 포커스 재조회, version ⛔ |
| | 로그·모니터링 | ✅ JSON stdout + Actuator health, 대시보드 ⛔ |
| | 향후 확장 | ⛔ 문서로만 (§10) |

---

## 12. 테스트가 검증해야 할 핵심 케이스

> 테스트 세션은 **이 목록(스펙)에서 역산**해 테스트를 쓴다. 코드에서 역산하면 버그가 정답으로 굳는다. P0 = 필수, P1 = 권장.

### T1. `ExtensionJudge` 단위 (DB 없음, 매개변수화) — 차단 집합 `{exe, sh, bat, gz, com}` 가정

| 입력 | 기대 | 우선 |
|---|---|---|
| `a.exe`, `a.EXE`, `a.txt.exe` | `BLOCKED_EXTENSION` [exe] | P0 |
| `a.exe.txt` | `BLOCKED_EXTENSION` | P0 |
| `archive.tar.gz` | `BLOCKED_EXTENSION` [gz] | P0 |
| `example.com.pdf` | `BLOCKED_EXTENSION` (감수한 오탐) | P1 |
| `exe.txt`, `js.pdf` | 통과 | P0 |
| `.bat` / `.env` | 차단 / 통과 | P0 |
| `Makefile` | 통과 | P0 |
| `file.exe.`, `file.exe `, `file.exe. .` | 차단 | P0 |
| `C:\fakepath\a.exe`, `../../a.exe` / `dir.v2/readme` | 차단 / 통과 | P0 |
| 255자 / 256자 파일명 | 통과 / `INVALID_FILENAME` | P0 |
| `null`, `""`, `"/"` | `INVALID_FILENAME` | P0 |
| `inv\u202Egpj.exe` | 차단 | P1 |
| `report.jpg` + head `MZ`, blocked ∋ exe | `BLOCKED_CONTENT` | P0 |
| `report.jpg` + `MZ`, blocked = `{scr}` | `BLOCKED_CONTENT` | P0 |
| `report.jpg` + `MZ`, PE 계열 미차단 | 통과 | P1 |
| head 길이 0 / 1(`M`) | 통과, 예외 없음 | P0 |
| `a.exe` + `MZ` | `BLOCKED_EXTENSION` (우선순위) | P0 |

### T2. `ExtensionNormalizer` 단위

| 입력 | 기대 | 우선 |
|---|---|---|
| `.SH `, `sh`, `..sh` | `sh` | P0 |
| `.`, `   `, `""`, `null` | `INVALID_EXTENSION` | P0 |
| `s h`, `s.h`, `tar.gz`, `ｅｘｅ`, `한글`, `ex-e` | `INVALID_EXTENSION` | P0 |
| 20자 / 21자 | 성공 / `INVALID_EXTENSION` | P0 |

### T3. 정책 API 통합 (Testcontainers Postgres + MockMvc)
- P0 커스텀 `exe` → 409 `FIXED_EXTENSION_CONFLICT` / `sh` 두 번 → 409 `DUPLICATE_EXTENSION`
- P0 200번째 201, 201번째 409 `CUSTOM_LIMIT_EXCEEDED`
- P0 같은 고정에 PUT `true` 2회 → 감사 1행
- P0 커스텀 id에 PUT / 없는 id → 404
- P0 **고정 id로 DELETE → 204, 행 유지**
- P0 DELETE 2회 → 둘 다 204, 감사 1행
- P0 오래된 탭: `sh`(A) 삭제 → `sh` 재추가(B) → DELETE A → **B 유지**
- P0 DB 제약: `EXE` 직접 INSERT 실패, 커스텀 `blocked=false` INSERT 실패
- P1 감사에 `client_ip`, `user_agent` 저장, 600자 UA → 512자
- P1 GET `customLimit`·`maxFileSize`가 설정값을 따름

### T4. 동시성 (Testcontainers, 스레드 2개 이상, 시작 동기화)
- P0 199개 상태에서 동시 2개 추가 → **정확히 200개**, 하나는 409
- P0 같은 이름 동시 2개 추가 → 1행, 하나는 409
- P1 같은 고정 동시 PUT → 감사 1행
- P1 커스텀 추가가 잠금 보유 중에도 `findBlockedNames()`는 대기하지 않음

### T5. 업로드 통합
- P0 exe 해제 직후 다음 요청에서 exe 업로드 성공 (캐시 없음)
- P0 exe를 `Content-Type: image/jpeg`로 보내도 차단
- P0 성공 시 저장 폴더에 UUID 이름 파일 생성, 원본 파일명·경로는 디스크에 없음
- P0 차단 파일은 저장 폴더에 남지 않음
- P0 파일 2개 → 400 `TOO_MANY_FILES`, file 파트 없음 → 400 `MISSING_FILE`
- P0 저장 폴더 쓰기 불가 → 500 `STORAGE_FAILED`, 부분 파일 없음
- P0 0바이트 파일 → 201
- P1 10MB+1 → 413 `FILE_TOO_LARGE` (MockMvc는 Tomcat 연결 끊김을 재현하지 못함 → T7에서 실제 확인)

### T6. 보안 (보안 세션)
- P0 `X-Forwarded-For` 맨 왼쪽 위조가 rate limit 키·감사 IP를 바꾸지 못함
- P0 업로드 11회/분 → 429, 헤더 위조로 우회 불가
- P0 `/actuator/env`, `/actuator/beans` → 404 (health만 노출)
- P0 `grep -r dangerouslySetInnerHTML frontend/src` → 0건
- P0 파일명 `<img src=x onerror=alert(1)>.txt`가 성공/차단 메시지에 **텍스트로** 표시
- P0 500 응답 본문에 스택 트레이스 없음
- P1 응답에 클라이언트 Content-Type이 되돌아오지 않음
- P1 개행이 들어간 파일명이 로그에서 JSON 한 줄로 이스케이프

### T7. E2E (테스트 세션, `claude-in-chrome`으로 배포 사이트 조작 + GIF)
- P0 체크 → 새로고침 → 유지 / 커스텀 추가·삭제 → 새로고침 → 유지
- P0 차단 파일 업로드 시 사유 메시지, 정상 파일 성공
- P0 10MB 초과 파일은 클라이언트에서 안내되고 요청이 나가지 않음
- P1 200개 도달 시 입력 비활성화 / 요청 중 체크박스 비활성화 / 탭 포커스 시 재조회

---

## 13. 구현 태스크 분해 (코드 세션용)

> **프로세스 메모**: writing-plans 기본 형식은 태스크마다 TDD(실패 테스트 → 구현)지만, 이 프로젝트는 **테스트 코드를 별도 테스트 세션이 스펙(§12)에서 독립 작성**한다(AGENT_PROMPTS.md). 따라서 각 태스크는 "구현 → 수동 확인 → 커밋"으로 구성하고, 완료 기준으로 §12 케이스 ID를 명시한다.

### Global Constraints
- Spring Boot **4.1.x**, Java **21**, Gradle. 의존성 좌표·패키지명은 공식 문서로 검증(Boot 3 패턴 금지).
- DB 접근은 **JdbcClient**. JPA 사용 금지.
- 인터페이스·팩토리 등 구현체 1개짜리 추상화 금지. 확장 지점은 `ponytail:` 주석.
- 클라이언트 `Content-Type`을 판정·응답에 사용 금지.
- 원본 파일명을 파일시스템 경로에 사용 금지.
- 앱 코드에서 `X-Forwarded-For` 직접 파싱 금지(`getRemoteAddr()`만).
- 프론트 `dangerouslySetInnerHTML` 금지.
- 모든 에러 응답은 §5-1 형식, code는 §5-3 목록만.
- 작업 중 설계에 없던 위험·판단을 발견하면 `CONSIDERATIONS.md` 부록에 append.

### Task 1: 백엔드 스캐폴딩 + DB 마이그레이션
**Files**: `backend/build.gradle`, `backend/settings.gradle`, `backend/compose.yaml`, `backend/src/main/resources/application.yml`, `db/migration/V1__schema.sql`, `V2__seed_fixed.sql`, `/db/reset.sql`, `config/AppProperties.java`, 메인 클래스
**Deps**: web, jdbc, validation, actuator, flyway(+ postgresql 모듈), postgresql driver, spring-boot-docker-compose(developmentOnly), bucket4j core + caffeine, caffeine, testcontainers(postgresql, junit), spring-boot-testcontainers
**완료 기준**: `./gradlew bootRun` → compose Postgres 기동, Flyway V1·V2 적용, `psql`로 extension 7행 확인, `/actuator/health` 200, `/actuator/env` 404.

### Task 2: 공통 에러 처리 + ClientInfo
**Files**: `common/ErrorCode.java`, `ApiException.java`, `GlobalExceptionHandler.java`, `ClientInfo.java`, `application.yml`(forward-headers-strategy, structured logging)
**Produces**: §7-1 `ErrorCode`, `ApiException`, `ClientInfo.from()`
**완료 기준**: 임시 엔드포인트 없이도 존재하지 않는 경로/잘못된 JSON이 §5-1 형식으로 응답, 500 응답에 스택 없음(T6).

### Task 3: 순수 함수 — `ExtensionNormalizer`, `ExtensionJudge`
**Files**: `policy/ExtensionNormalizer.java`, `upload/ExtensionJudge.java`, `upload/Verdict.java`
**Produces**: §7-1 시그니처
**완료 기준**: §6-1·§6-2 알고리즘 그대로. T1·T2 표의 모든 입력을 `jshell` 또는 임시 main으로 수동 확인(테스트 코드는 테스트 세션).

### Task 4: 정책 API
**Files**: `policy/ExtensionRepository.java`, `ExtensionRow.java`, `PolicyAuditRepository.java`, `AuditAction.java`, `ExtensionService.java`, `ExtensionController.java`, dto records
**Consumes**: Task 2·3
**완료 기준**: §6-3 SQL 그대로. curl로 T3 시나리오 수동 확인(충돌·중복·204 멱등·고정 id DELETE 무시·감사 행 수).

### Task 5: 업로드 API
**Files**: `upload/FileStorage.java`, `UploadService.java`, `UploadController.java`
**Consumes**: Task 2·3·4(`findBlockedNames`)
**완료 기준**: §6-4·§6-5 흐름. curl로 정상/차단/MZ 위장(`printf 'MZ' > fake.jpg`)/2파일/파트 없음/저장 폴더 권한 제거 시 `STORAGE_FAILED` 확인. 업로드 로그가 JSON 한 줄.

### Task 6: Rate limit
**Files**: `common/RateLimitFilter.java`
**Consumes**: `AppProperties`, `ClientInfo`
**완료 기준**: §7-3 규칙 준수. 변경 API 31회/분·업로드 11회/분 → 429 `RATE_LIMITED`(§5-1 형식). `X-Forwarded-For` 위조 헤더로 우회되지 않음. 코드에 Redis 전환 `ponytail:` 주석.

### Task 7: 프론트엔드
**Files**: §8 트리 전체, `frontend/vite.config.ts`(dev proxy `/api`·`/actuator` → 8080)
**완료 기준**: `npm run dev`로 §8 UX 규칙 전부 수동 확인. `grep dangerouslySetInnerHTML` 0건.

### Task 8: 빌드 통합 + 배포 준비
**Files**: `Dockerfile`(node:… build → gradle build(프론트 dist를 `src/main/resources/static`에 복사) → eclipse-temurin:21-jre), `.dockerignore`
**완료 기준**: `docker build` 후 컨테이너 단독 실행 시 같은 출처에서 화면과 API 동작. 메모리 설정(`-XX:MaxRAMPercentage`) 확인. non-root 사용자로 실행되고 업로드 저장이 동작(§7-3).

---

## 14. 세션 핸드오프 체크 항목

**배포 세션** — ✅ 완료(배포처는 Render + Neon으로 변경, 결과는 SECURITY_REVIEW §6·`docs/verification/deploy-check.log`)
- ✅ 실제 클라이언트 IP 기록 — 기본값으론 Cloudflare IP가 기록됨 → `CF-Connecting-IP`로 교정 (A-38)
- ✅ DB 변수 → `SPRING_DATASOURCE_*` 연결, `PORT`=10000 주입 확인
- ✅ 컨테이너 512MB → 힙 상한 384MB (`-Xlog:gc+init` 로그)
- 면접 전날 `/actuator/health` 확인, 필요 시 `/db/reset.sql` 실행 (README 배포 섹션)
- ✅ 배포 플랫폼 조건 공식 문서 재확인 (A-37)

**보안 세션**: §12 T6 전부 + Actuator 노출 범위 + 업로드 저장 경로 권한 + 의존성 취약점

**테스트 세션**: §12 T1~T7. T4 동시성은 `CountDownLatch` 등으로 시작 시점 동기화.

---

## 15. 결정 로그 (사람–AI 상호작용 요약, PROMPT_LOG 4-3 소재)

| # | 주제 | AI 최초 제안 | 사람 개입 | 최종 |
|---|---|---|---|---|
| 1 | 이중 확장자 | 모든 구간 검사 추천 | 사람은 "점 2개 이상 금지" 제안 → AI가 오탐 비용 근거로 반대 | 모든 구간 검사 |
| 2 | 파일명 정제 | `[.;:]` 분할 + 제어문자 거부 등 4단계 | 사람이 **과설계 지적** | 위협 모델로 재필터 → 2개만 유지 |
| 3 | 200개 동시성 | LOCK TABLE | 사람이 version 낙관적 락 제안 → AI가 "행 없음·정당 요청 충돌" 근거로 반대, 카운터 조건부 UPDATE를 대안 제시 | LOCK TABLE (조건부 UPDATE는 고정 체크 변경에 채택) |
| 4 | 감사 | 구조화 로그(테이블 없음) | 사람이 "서버는 언제든 다운 → 영속화해야 감사 의미" | `policy_audit` 테이블 |
| 5 | 인증 | 공유 토큰 추천 | 사람이 인증 없음 결정, IP/UA 대체 제안 → AI가 XFF 위조 함정 보강 | 무인증 + `client_ip`/`user_agent` |
| 6 | Rate limit | 메모리 카운터 | 사람이 분산 환경 대비 요구 | Bucket4j `ProxyManager` 경계 |
| 7 | 저장/한도 확장성 | — | 사람이 마이그레이션 여지 요구 | 함수 경계 1개 + 설정값, 추상화 없음 |
| 8 | 프론트 | Vanilla JS 추천 | 사람이 React 선택(익숙함 + 자동 이스케이프) | React + Vite + TS |
| 9 | API 식별자 | name 경로 | 사람이 "id가 일반적이지 않나" 질문 → AI 재검토 중 **오래된 탭 삭제 결함 발견** | id 경로 |
| 10 | 배포 | Railway 추천 | 사람이 Lightsail 문의 → 운영 부담 비교 | Railway Hobby |
| 11 | 배포처 변경 (배포 세션) | Railway Trial(사람이 무료 선택) | Railway 가입 불가(사고 후 가입 중단) → AI가 Render+Neon / Koyeb 비교, 사람이 Render+Neon 결정 | Render 무료 + Neon 무료 (A-37) |
| 12 | 클라이언트 IP (배포 세션) | 설계: `RemoteIpValve` 기본값으로 충분 | 실측에서 CF IP 기록·rate limit 우회 발견 → AI가 `CF-Connecting-IP` 1줄 제안, 사람 승인 | 배포 환경변수 1줄 (A-38) |
