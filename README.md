# 파일 확장자 차단

파일 업로드 시 **확장자 차단 정책**을 관리하고(고정 7개 + 커스텀 최대 200개), 그 정책을 **실제 업로드에서 서버가 강제**하는 기능. 과제 원문은 `guide.md`.

- **배포 URL**: https://extblock.onrender.com (무료 인스턴스 — 첫 접속 1~2분, [배포](#배포) 참고)
- **고려사항**: [`CONSIDERATIONS.md`](CONSIDERATIONS.md) · **AI 활용 기록**: [`PROMPT_LOG.md`](PROMPT_LOG.md)

## 개요

| 기능 | 동작 |
|---|---|
| 고정 확장자 | `bat cmd com cpl exe scr js` 체크박스. 기본 unCheck, 체크/해제 즉시 DB 저장 → 새로고침해도 유지 |
| 커스텀 확장자 | 입력 최대 20자, 최대 200개, 추가·`X` 삭제 즉시 DB 저장. 중복 → 409, 고정 확장자 입력(`exe`) → 409 + "위 체크박스를 사용하세요" |
| 업로드 | 파일 1개, 최대 10MB. 차단 확장자 → 422 + 무엇이 왜 막혔는지 메시지, 정상 파일 → 201 (UUID 이름으로 저장) |

판정 규칙 요약 (상세: `docs/DESIGN.md` §6-1, 근거: `CONSIDERATIONS.md` §1·§2)
- 파일명의 **첫 구간을 뺀 모든 구간**을 확장자로 본다: `a.exe.txt` → 차단, `archive.tar.gz` → `gz` 차단, `exe.txt` → 통과.
- 대소문자 무시, 끝의 점·공백 제거(`file.exe.`), 경로 제거(`C:\fakepath\a.exe`), 제어문자·빈 이름·255자 초과 거부.
- 파일 앞 2바이트가 `MZ`(PE 실행 파일)면 `exe com scr cpl` 중 차단된 것이 있을 때 확장자와 무관하게 차단(`report.jpg`로 위장한 exe).
- 클라이언트가 보낸 `Content-Type`은 판정에 쓰지 않는다.

## 아키텍처

```
Browser ──▶ Spring Boot 4.1 (Java 21)  ── 한 컨테이너, 같은 출처 ──────────────────────┐
             ├─ /            React 19 + Vite + TS 빌드 결과 (static)                     │
             ├─ RateLimitFilter   IP당 정책 변경 30회/분 · 업로드 10회/분 (Bucket4j+Caffeine)│
             ├─ /api/extensions/** 정책 API  ── ExtensionService (@Transactional)          │
             ├─ /api/uploads       업로드     ── UploadService → ExtensionJudge → FileStorage│
             └─ /actuator/health                                  │ /data/uploads (UUID)  │
                                   │ JdbcClient                                           │
                                   ▼                                                      │
                          PostgreSQL 17 (Flyway V1 스키마, V2 고정 7개 시드) ─────────────┘
```

| 영역 | 선택 | 한 줄 근거 (상세: `docs/DESIGN.md` §2) |
|---|---|---|
| 백엔드 | Spring Boot 4.1.2 / Java 21 / Gradle | 트랜잭션·정합성 |
| DB | PostgreSQL 17 | `LOCK TABLE`이 트랜잭션 안에서 동작(200개 상한 race 방어). MySQL `LOCK TABLES`는 암묵 커밋 |
| 데이터 접근 | JdbcClient | 정합성 장치(UNIQUE·LOCK·조건부 UPDATE)가 SQL 수준이라 SQL이 보이는 쪽 |
| 마이그레이션 | Flyway | 스키마 원본이 버전 관리되는 SQL |
| 프론트 | React + Vite + TS, Spring이 같은 출처로 서빙 | JSX 자동 이스케이프(XSS), CORS 없음 |
| Rate limit | Bucket4j core + Caffeine `ProxyManager` | Redis 전환 시 `ProxyManager`만 교체 |
| 로그 | Spring Boot structured logging(ECS JSON) | 파일명 개행 이스케이프 → 로그 인젝션 차단 |

- **서버 검증이 유일한 기준.** 클라이언트 검증(입력 형식·중복·상한·파일 크기)은 UX용이고, 업로드 확장자 판정은 클라이언트에서 하지 않는다(규칙 중복·오래된 정책 위험).
- **비관적 UI**: 서버 성공 응답 후에만 화면 반영, 요청 중 해당 컨트롤 비활성화, 성공 후 전체 재조회.
- 패키지는 기능 단위(`policy/`, `upload/`, `common/`, `config/`). 코드 지도: `docs/IMPL_NOTES.md` §2.
- API 목록·에러 code: `docs/DESIGN.md` §5. 모든 에러는 `{code, message, details}`.

## DB 스키마

원본: [`backend/src/main/resources/db/migration/V1__schema.sql`](backend/src/main/resources/db/migration/V1__schema.sql) (Flyway가 첫 기동 때 적용). 고정 확장자와 커스텀 확장자를 **테이블 하나**(`type` 컬럼)에 둔다 — `UNIQUE(name)` 하나가 중복 추가와 고정/커스텀 충돌을 동시 요청까지 DB에서 막는다.

### `extension` — 확장자 차단 정책

| 컬럼 | 타입 | NULL | 기본값 | 제약 |
|---|---|---|---|---|
| `id` | `BIGINT` | NOT NULL | `GENERATED ALWAYS AS IDENTITY` | **PK** (`extension_pkey`) |
| `name` | `VARCHAR(20)` | NOT NULL | | **UNIQUE** (`extension_name_key`), CHECK `extension_name_format`: `name ~ '^[a-z0-9]{1,20}$'` |
| `type` | `VARCHAR(10)` | NOT NULL | | CHECK `extension_type_check`: `type IN ('FIXED','CUSTOM')` |
| `blocked` | `BOOLEAN` | NOT NULL | `FALSE` | CHECK `custom_always_blocked`: `type = 'FIXED' OR blocked` (커스텀은 항상 차단) |
| `created_at` | `TIMESTAMPTZ` | NOT NULL | `now()` | |

### `policy_audit` — 정책 변경 감사 (append-only)

| 컬럼 | 타입 | NULL | 기본값 | 제약 |
|---|---|---|---|---|
| `id` | `BIGINT` | NOT NULL | `GENERATED ALWAYS AS IDENTITY` | **PK** (`policy_audit_pkey`) |
| `action` | `VARCHAR(20)` | NOT NULL | | CHECK `policy_audit_action_check`: `FIXED_BLOCK` `FIXED_UNBLOCK` `CUSTOM_ADD` `CUSTOM_DELETE` |
| `extension_name` | `VARCHAR(20)` | NOT NULL | | FK 없음 — 이름을 값으로 복사(감사 기록은 대상 행보다 오래 산다) |
| `client_ip` | `VARCHAR(45)` | NULL | | 추적 단서(식별 아님). `INET`이 아닌 이유: 이상한 값에 INSERT가 실패하면 같은 트랜잭션의 정책 변경까지 롤백됨 |
| `user_agent` | `VARCHAR(512)` | NULL | | 앱에서 512자 절단 |
| `created_at` | `TIMESTAMPTZ` | NOT NULL | `now()` | |

### 인덱스

| 인덱스 | 테이블 | 종류 | 용도 |
|---|---|---|---|
| `extension_pkey` | extension | btree `(id)` PK | API 경로 식별자(`/fixed/{id}`, `/custom/{id}`) |
| `extension_name_key` | extension | btree `(name)` UNIQUE | 중복·고정 충돌 방지, 이름 조회 |
| `policy_audit_pkey` | policy_audit | btree `(id)` PK | |

**추가 인덱스를 만들지 않은 이유**: 최대 207행(고정 7 + 커스텀 200)이라 판정 쿼리 `WHERE blocked`는 전체 스캔으로 충분하고, boolean은 카디널리티가 2라 인덱스를 만들어도 옵티마이저가 쓰지 않는다. 감사 테이블은 조회 기능이 없다(생기면 `created_at` 인덱스). → `CONSIDERATIONS.md` 4-10.

위 컬럼·제약·인덱스 이름은 V1을 Postgres 17에 적용한 뒤 `\d`로 확인한 값이다. 시드는 `V2__seed_fixed.sql`(고정 7개, 모두 `blocked=false`), 데모 리셋은 `db/reset.sql`(Flyway 밖, 운영자 수동 실행 — 감사 기록 남지 않음).

## 로컬 실행

**요구**: Docker Desktop **최신**(구버전 Compose `2.0.0-beta`는 `spring-boot-docker-compose`가 출력 파싱에 실패해 bootRun이 안 뜬다 — CONSIDERATIONS A-1), Node 22+. JDK 21은 Gradle toolchain이 자동으로 받는다. 로컬 5432 포트가 비어 있어야 한다.

```bash
# 1) 백엔드 — compose Postgres 17 자동 기동 + Flyway V1·V2 적용
cd backend
APP_UPLOAD_STORAGE_DIR=./uploads ./gradlew bootRun     # http://localhost:8080
#    기본 저장 경로 /data/uploads 는 macOS에서 쓰기 불가 → 로컬은 ./uploads 로 덮어쓴다 (A-2)

# 2) 프론트 개발 서버 — /api, /actuator 를 8080으로 프록시
cd frontend && npm install && npm run dev               # http://localhost:5173
```

운영과 같은 단일 컨테이너로 실행:
```bash
docker build -t extblock .
docker run -p 8080:8080 \
  -e SPRING_DATASOURCE_URL=jdbc:postgresql://<host>:5432/extblock \
  -e SPRING_DATASOURCE_USERNAME=... -e SPRING_DATASOURCE_PASSWORD=... extblock
# http://localhost:8080  (React + API 같은 출처)
```

로컬 데모 데이터 초기화: `docker exec -i backend-postgres-1 psql -U extblock -d extblock < db/reset.sql`

설정값(모두 환경변수로 덮어쓰기 가능, `docs/DESIGN.md` §9): `APP_UPLOAD_MAX_FILE_SIZE`(10MB), `APP_POLICY_CUSTOM_LIMIT`(200), `APP_RATE_LIMIT_POLICY_PER_MINUTE`(30), `APP_RATE_LIMIT_UPLOAD_PER_MINUTE`(10), `APP_UPLOAD_STORAGE_DIR`(`/data/uploads`). multipart 요청 한도는 파일 한도 + 1MB로 자동 연동된다(A-27).

## 테스트

```bash
cd backend && ./gradlew test     # JUnit 5 + Testcontainers(Postgres 17) — Docker 필요
cd frontend && npm test          # vitest — normalize.ts 가 서버 규칙과 같은지
```

결과(2026-10-25 마감 세션 재실행): **백엔드 166개 실패 0, 프론트 26개 실패 0.**

| 스위트 | 무엇을 |
|---|---|
| `upload/ExtensionJudgeTest` | 판정 순수 함수 — 이중 확장자, 점 파일, 끝 점, 경로, 제어문자, MZ, 대소문자 접기 |
| `policy/ExtensionNormalizerTest` | 커스텀 입력 정규화 — trim·앞 점·소문자·`^[a-z0-9]{1,20}$`, 터키어 로케일 |
| `policy/PolicyApiTest` | 정책 API — 409 충돌·중복·상한, 204 멱등 삭제, 오래된 탭, DB 제약, 감사 |
| `policy/ConcurrencyTest` | 199개에서 동시 2개 → 정확히 200, 같은 이름 동시 추가, 잠금 중 조회 비대기 |
| `upload/UploadApiTest` | 실제 Tomcat multipart — 차단/허용, MIME 스푸핑, 크기·개수, 저장 실패, 깨진 multipart |
| `common/RequestBodyLimitTest` | 업로드 외 요청 본문 16KB 상한(OOM 방어) |
| `ConfiguredLimitsTest` | 한도를 설정으로 바꿨을 때 메시지·실제 거절 지점 일치 |

테스트는 **코드가 아니라 스펙(DESIGN §6·§12)에서 기대값을 역산**했다. 그 과정에서 찾은 결함과 처리: `docs/VERIFICATION.md`. 보안 리뷰(실제 공격 재현·수정): `docs/SECURITY_REVIEW.md`.

## 문서 지도

| 문서 | 내용 | 작성 세션 |
|---|---|---|
| `docs/DESIGN.md` | 기준 스펙 — 스키마, API, 판정 알고리즘, 트랜잭션 SQL, 테스트 케이스 | 계획 (이후 세션이 근거와 함께 정정) |
| `CONSIDERATIONS.md` | 고려사항별 판단(구현/제외/다르게 처리)과 근거 | 전 세션 누적 |
| `docs/IMPL_NOTES.md` | 설계 대비 구현 차이, 수동 확인, 인계 | 코드 |
| `docs/VERIFICATION.md` | 스펙 기반 테스트 결과, 결함 B1·B2, 레이스 재현 | 테스트 |
| `docs/SECURITY_REVIEW.md` | 적대적 리뷰 S1~S6, 배포 체크리스트 결과 | 보안 · 배포 |
| `PROMPT_LOG.md`, `prompts/raw/` | AI 활용 기록 (원본 프롬프트 + 큐레이션 + 스킬/도구) | 전 세션 |
| `docs/verification/` | 실행 로그, 스크린샷, GIF | 테스트 · 보안 · 배포 |

## 배포

**URL: https://extblock.onrender.com**

> ⏱ 무료 인스턴스라 15분 동안 요청이 없으면 잠든다. **첫 접속은 1~2분** 걸린다(깨우기 + 앱 기동 약 81초). 한 번 뜨면 이후 응답은 빠르다.

### 구성

```
Browser ─HTTPS─▶ Cloudflare ─▶ Render LB ─▶ [ Docker: Spring Boot + React static ]  Render Free, Singapore
                                                   │  /data/uploads (휘발성, 볼륨 없음)
                                                   ▼
                                     [ Neon Postgres 17 ]  Free, AWS ap-southeast-1, direct endpoint
```

| 항목 | 값 | 이유 |
|---|---|---|
| 웹 | Render Free Web Service, 루트 `Dockerfile`, Auto-Deploy **Off** | push가 면접 버전을 바꾸지 않게 수동 배포 |
| DB | Neon Free (pooler 아님) | Render 무료 Postgres는 30일 만료. Flyway·`LOCK TABLE`을 PgBouncer 없이 |
| 리소스 | 512MB / 0.1 CPU → JVM 힙 384MB (`MaxRAMPercentage=75`) | |
| 원래 계획(Railway)에서 바뀐 이유 | Railway 10/21 사고 후 신규 가입 중단 | CONSIDERATIONS A-37 |

### 환경변수 (Render → Environment)

| 키 | 값 | 비고 |
|---|---|---|
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://<neon-host>/neondb?sslmode=require` | Neon 연결 문자열의 `postgresql://` URL은 JDBC 형식이 아니므로 분해 (A-21) |
| `SPRING_DATASOURCE_USERNAME` | Neon 사용자 | |
| `SPRING_DATASOURCE_PASSWORD` | Neon 비밀번호 | |
| `SERVER_TOMCAT_REMOTEIP_REMOTE_IP_HEADER` | `CF-Connecting-IP` | **필수.** 없으면 Cloudflare IP가 클라이언트 IP가 되어 rate limit·감사가 무력해진다 (A-38) |
| `JAVA_TOOL_OPTIONS` | `-Xlog:gc+init` | 선택. 기동 로그에 컨테이너 메모리·힙 상한을 찍는다 |

`PORT`는 Render가 주입한다(10000). `server.port: ${PORT:8080}`.

### 처음부터 재현

1. **Neon**: 프로젝트 생성(Postgres 17, AWS Asia Pacific Singapore) → Connect에서 **Connection pooling 끔** → 연결 문자열 확보.
2. **Render**: New → Web Service → Public Git Repository `https://github.com/GreatLaboratory/flow-test` → Branch `main`, Language **Docker**, Region **Singapore**, Instance **Free**, Health Check Path `/actuator/health`, Auto-Deploy **Off** → 위 환경변수 입력 → Deploy.
3. 스키마는 첫 기동 때 Flyway가 만든다(V1 스키마, V2 고정 확장자 7개).
4. 확인:
   ```bash
   URL=https://extblock.onrender.com
   curl -s $URL/actuator/health                         # {"status":"UP",...}
   curl -s $URL/api/extensions | head -c 200            # 고정 7개
   ```

### 재배포 / 로그 (Render CLI)

```bash
brew install render && render login
render services                                          # 서비스 id 확인
render deploys create <srv-id> --wait --confirm          # main 최신 커밋으로 수동 재배포
render logs -r <srv-id> --limit 200 --confirm
```

### 면접 전날 / 당일 체크

```bash
URL=https://extblock.onrender.com
curl -s -m 180 $URL/actuator/health      # 잠들어 있으면 최대 1~2분 뒤 UP
# 데모 데이터 초기화(커스텀 전부 삭제, 고정 전부 해제). NEON_URL = Neon 연결 문자열(postgresql://...)
docker run --rm -i postgres:17 psql "$NEON_URL" < db/reset.sql
```
- 면접 **직전에 한 번 접속**해서 깨워 둔다.
- 업로드 파일은 재배포·재시작·슬립 때 사라진다(의도, CONSIDERATIONS 3-3).
- rate limit 카운터는 메모리에 있어 재시작하면 초기화된다.

### 배포 검증 결과

`docs/SECURITY_REVIEW.md` §6 (원본 출력 `docs/verification/deploy-check.log`, 화면 `docs/verification/deploy/`).
핵심 플로우 정책 저장 → 차단 파일 거부 → 정상 파일 성공은 `docs/verification/deploy/deploy_core_flow.gif`.
