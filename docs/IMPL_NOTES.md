# IMPL_NOTES — 코드 세션 산출물

> 기준 스펙은 `docs/DESIGN.md`. 이 문서는 **설계 대비 실제 구현에서 달라진 점, 검증한 것/못 한 것, 다음 세션 인계**만 담는다.
> 판단 근거의 본문은 `CONSIDERATIONS.md` 부록(A-1 ~ A-21)에 있다. 아래 `A-n`은 그 번호다.

---

## 1. 로컬 실행

```bash
# 요구: Docker Desktop 최신(A-1), Node 22+. JDK 21은 Gradle toolchain이 자동 다운로드.

# 백엔드 (compose Postgres 자동 기동 + Flyway V1·V2 적용)
cd backend
APP_UPLOAD_STORAGE_DIR=./uploads ./gradlew bootRun      # http://localhost:8080 (A-2)

# 프론트 개발 서버 (/api, /actuator → 8080 프록시)
cd frontend && npm install && npm run dev                # http://localhost:5173

# 단일 컨테이너 (운영과 같은 형태)
docker build -t extblock .
docker run -p 8080:8080 \
  -e SPRING_DATASOURCE_URL=jdbc:postgresql://<host>:5432/extblock \
  -e SPRING_DATASOURCE_USERNAME=... -e SPRING_DATASOURCE_PASSWORD=... extblock

# 데모 리셋 (감사 기록 남지 않음)
docker exec -i backend-postgres-1 psql -U extblock -d extblock < db/reset.sql
```

## 2. 코드 지도

| 위치 | 역할 |
|---|---|
| `backend/.../upload/ExtensionJudge.java` | **업로드 판정의 유일한 기준** (§6-1, 순수 함수) |
| `backend/.../policy/ExtensionNormalizer.java` | **커스텀 입력 검증의 유일한 기준** (§6-2, 순수 함수) |
| `backend/.../policy/ExtensionService.java` | 정책 트랜잭션 3개 (LOCK TABLE / 조건부 UPDATE / id 삭제) + 같은 트랜잭션 감사 |
| `backend/.../policy/ExtensionRepository.java` | extension 테이블 SQL 전부 (JdbcClient) |
| `backend/.../upload/UploadService.java` | 앞 2바이트 → 차단 목록 조회 → 판정 → 저장 → 로그 1줄 |
| `backend/.../upload/FileStorage.java` | 저장 경계 (UUID, 부분 파일 삭제) |
| `backend/.../common/GlobalExceptionHandler.java` | 모든 예외 → `{code,message,details}`, 스택은 로그만 |
| `backend/.../common/RateLimitFilter.java` | IP당 rate limit, 429 본문 직접 작성 |
| `backend/.../common/ClientInfo.java` | `getRemoteAddr()`만 사용, IP 45자·UA 512자 절단 |
| `frontend/src/normalize.ts` | 서버 규칙의 **UX용 사본** |
| `frontend/src/components/*` | 비관적 UI, 요청 중 해당 컨트롤 disabled |

### 서버 검증 vs 클라이언트 검증 — 코드상 어디서 갈리나

| 검증 | 서버 (최종 방어선) | 클라이언트 (UX 보조) |
|---|---|---|
| 커스텀 형식 | `ExtensionNormalizer` + DB CHECK `extension_name_format` | `normalize.ts` — 통과해도 **원문을 그대로 전송**(A-18) |
| 중복 / 고정 충돌 | `ExtensionService.addCustom` 잠금 아래 조회 + `UNIQUE(name)` | 목록 중복만 안내. 고정 충돌은 서버 메시지(A-19) |
| 200개 상한 | `LOCK TABLE` + `countCustom()` | `items.length >= limit`이면 입력 비활성화 |
| 업로드 확장자·내용 | `ExtensionJudge` | **하지 않음** (설계 §8, TOCTOU) |
| 파일 크기 | Spring multipart 제한 → 413 | `file.size > maxFileSize`(서버가 내려준 값)이면 버튼 비활성화 |
| 파일 개수 | `UploadController` 1개 강제 | `<input type="file">` 단일 선택 |
| 남용 | `RateLimitFilter` | 없음 |

## 3. 설계 대비 달라진 점

| 항목 | 설계 | 구현 | 근거 |
|---|---|---|---|
| Boot 버전 | 4.1.x | **4.1.2** 고정, Boot 4 모듈 starter 좌표(`spring-boot-starter-webmvc`, `-flyway` 등)는 start.spring.io 생성 결과 사용 | 공식 생성기로 좌표 검증 |
| ErrorCode | §5-3 15개 | **`NOT_FOUND`(404) 추가**, 프레임워크 4xx는 `INVALID_REQUEST` | A-3 (사람 승인) |
| 리포지토리 계약 | `existsFixed(id)` | **`findFixedById(id)`** | 감사·응답에 이름 필요. A-9 |
| Caffeine 만료 | `expireAfterAccess(10분)` | **`expirationAfterWrite(basedOnTimeForRefillingBucketUpToMax(10분))`** + `maximumSize` 유지 | Bucket4j와 공존 불가(실행 검증). A-15 (사람 승인) |
| `BLOCKED_CONTENT`의 `matched` | 미정 | 현재 차단 중인 PE 계열(정렬) | A-8 |
| compose 포트 | — | `5432:5432` 고정 | psql 접근 편의 |
| 생성기 테스트 파일 | — | 삭제 | 테스트는 테스트 세션 몫 |
| 프론트 템플릿 lint(oxlint) | — | 제거 | 필요 없는 의존성. 타입체크는 `tsc -b`가 담당 |

설계에 없던 구현 세부 판단: A-5(IP 절단), A-6(`\z`), A-7(코드포인트), A-11(비-multipart → `MISSING_FILE`), A-12(basename 응답·원본 로그), A-14(읽기 실패 vs 저장 실패), A-16(`getServletPath()`), A-20(재조회 실패 시 상태 유지).

## 4. 확장 지점 (`ponytail:` 주석)

| 파일 | 현재 한계 | 전환 |
|---|---|---|
| `RateLimitFilter.java` | 인스턴스 메모리 카운터 | Redis `ProxyManager`로 필드 교체 |
| `ExtensionRepository.findBlockedNames` | 캐시 없음 | 메모리 캐시 + 쓰기 시 무효화 |
| `ExtensionJudge` | 블랙리스트 | 클래스 내부만 화이트리스트로 교체 |
| `FileStorage.save` | 로컬 휘발성 디스크 | 본문만 S3/R2 업로드로 교체 |

## 5. 수동 확인 결과 (테스트 코드 아님)

| Task | 확인한 것 | 방법 |
|---|---|---|
| 1 | Flyway 적용, 7행, DB CHECK 2개 위반 거부, health 200, env/beans 404 | bootRun + psql + curl |
| 2 | 없는 경로 404 `NOT_FOUND`, 잘못된 JSON·Content-Type 400, 500에 스택 없음(Task 4에서 DB 중지로 확인) | curl |
| 3 | §12 T1·T2 표 전 입력 + 추가 3건(`file.exe.\n`, 터키어 로케일, head null) | scratchpad 임시 main |
| 4 | T3 전부, T4 P0 2건(199+동시 2 → 200, 같은 이름 동시 5 → 1행) + P1 2건(동시 PUT 감사 1행, 잠금 중 GET 0.01s) | curl 병렬 + psql |
| 5 | T5 P0 전부, 10MB 정확히 201 / 11MB·30MB 413, 개행 파일명 로그 1줄 | curl, raw multipart |
| 6 | 31회/11회 → 429, XFF 왼쪽 위조 무력, 경로 변형 4종 우회 불가, 한도 초과 30MB 0.001s 거절 | curl |
| 7 | §8 UX·접근성 규칙 전부, XSS 파일명 텍스트 렌더링, 11MB 요청 미발생, `dangerouslySetInnerHTML` 0건 | Claude in Chrome + 사람 동시 확인 |
| 8 | non-root(uid 10001), 힙 384MB/512MB, 같은 출처 UI+API, 업로드 저장 | docker run |

**확인하지 못한 것**: 배포 환경(Railway 프록시)에서의 413 도달(A-13)과 클라이언트 IP 교정(A-17). 로컬에서는 재현 불가.

## 6. 다음 세션 인계

### 테스트 세션
- [ ] §12 T1~T7 테스트 작성. Testcontainers는 Docker Engine 버전 확인 먼저 — 이 머신은 CLI 29.9.2 / **엔진 20.10.7(API 1.41)** 상태였다(A-1). docker-java가 최소 API 버전을 요구하면 Docker Desktop 재시작/업그레이드 필요.
- [ ] T7: 배포 사이트에서 10MB 초과 업로드 시 **413이 브라우저에 보이는지**, 아니면 `NETWORK_ERROR`로 보이는지 기록(A-13). 클라이언트 사전 검사로 요청이 안 나가는 것은 로컬 확인됨.
- [ ] 수동 확인의 재현: Task 3 케이스 목록은 위 §5 + DESIGN §12와 같다(코드에서 역산하지 말 것).

### 보안 세션
- [ ] A-4: 깨진 요청 라인은 Tomcat HTML 400(버전·스택 미노출 확인) — 수용 판단 재검토.
- [ ] A-16: rate limit 경로 우회 변형 추가 탐색(대소문자, 인코딩된 `/` 등).
- [ ] A-17: XFF 위조 시나리오를 배포 환경에서 재확인.

### 배포 세션
- [ ] **A-17 (필수)**: Railway 프록시가 `RemoteIpValve` 신뢰 대역에 드는지. 배포 후 커스텀 1개 추가 → `policy_audit.client_ip`가 **내 공인 IP**인지 확인. 프록시 IP가 찍히면 전 사용자가 rate limit 버킷 하나를 공유한다 → `server.tomcat.remoteip.internal-proxies`만 조정(너무 넓히면 위조 허용).
- [ ] **A-21 (필수)**: `DATABASE_URL`은 JDBC 형식 아님. `SPRING_DATASOURCE_URL=jdbc:postgresql://${PGHOST}:${PGPORT}/${PGDATABASE}`, `SPRING_DATASOURCE_USERNAME/PASSWORD` 각각 연결.
- [ ] `PORT` 주입 확인(`server.port: ${PORT:8080}`), 컨테이너 메모리 한도 확인(힙 = 75%).
- [ ] `/data/uploads`는 휘발성(의도). 볼륨을 붙이지 말 것 — 붙이면 디스크 고갈이 재배포로 초기화되지 않는다(CONSIDERATIONS 3-3, 5-4).
- [ ] 면접 전날 `/actuator/health`, 필요 시 `db/reset.sql`.

### 마감 세션
- [ ] README 실행법에 §1 내용 + "최신 Docker Desktop 필요"(A-1) + 로컬 `APP_UPLOAD_STORAGE_DIR=./uploads`(A-2).
- [ ] 스키마 문서 원본: `backend/src/main/resources/db/migration/V1__schema.sql`.
- [ ] CONSIDERATIONS 부록 A-1~A-21을 본문 섹션 번호와 연결할지 결정(현재는 부록에 append만).
