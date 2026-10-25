# IMPL_NOTES — 코드 세션 산출물

> 기준 스펙은 `docs/DESIGN.md`. 이 문서는 **설계 대비 실제 구현에서 달라진 점, 검증한 것/못 한 것, 다음 세션 인계**만 담는다.
> 판단 근거의 본문은 `CONSIDERATIONS.md` 부록(A-1 ~ A-23)에 있다. 아래 `A-n`은 그 번호다.

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

## 3. 구현 중 내린 결정과 설계와 달라진 점

### 3-1. 설계와 달라진 점

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

### 3-2. 설계에 없던 구현 결정 (설계는 그대로, 빈칸을 채운 것)

| 결정 | 이유 | 번호 |
|---|---|---|
| `client_ip`도 45자 절단 | 길이 초과 한 번이 같은 트랜잭션의 정책 변경까지 롤백시킴 | A-5 |
| 끝의 점 제거 정규식에 `$` 대신 `\z` | `$`는 마지막 개행 앞에서도 매치 → `file.exe.\n`이 차단을 빠져나감 | A-6 |
| 255자를 코드포인트로 셈 | `length()`는 이모지를 2자로 셈. 설계의 "문자 수"에 맞춤 | A-7 |
| 내용 차단 시 `matched` = 차단 중인 PE 계열 | 어떤 정책 항목 때문에 막혔는지 추적 | A-8 |
| 고정 체크 변경 후 `findFixedById` | 감사·응답에 이름 필요(설계 계약 변경) | A-9 |
| 업로드는 `HttpServletRequest`로 받고 multipart 아니면 `MISSING_FILE` | 바인딩 예외로 500이 나는 것 방지 | A-11 |
| 응답 `fileName`은 basename, 로그는 원본 | 사용자에겐 의미 있는 이름, 로그는 조작 시도 추적 | A-12 |
| 판정용 읽기 실패 = `INTERNAL_ERROR`, 저장 실패만 `STORAGE_FAILED` | 원인이 다르면 code도 달라야 함 | A-14 |
| 저장 폴더 생성 실패 시 기동 실패 | 업로드가 전부 실패할 상태로 뜨는 것보다 빨리 드러남 | A-14 |
| rate limit 경로 비교는 `getServletPath()` | 원문 URI는 `;x=1`·`%75` 등 변형으로 우회 가능 | A-16 |
| 클라이언트는 입력 원문 그대로 전송 | JS/Java `trim` 차이로 서버 규칙을 우회하는 경로 차단 | A-18 |
| 고정 확장자 충돌은 클라이언트 사전 검사 안 함 | 클라이언트 규칙을 늘릴수록 서버와 어긋날 지점 증가 | A-19 |
| 재조회 실패 시 마지막 상태 유지 + 에러 표시 | 일시적 오류로 화면이 사라지지 않게 | A-20 |
| 체크박스는 요청 중 `disabled`, 상태는 서버 응답 뒤 반영 | 응답 역전 방지(설계 §8 그대로) | — |
| 429 본문은 기동 시 1회 직렬화해 재사용 | 필터는 advice 밖. 매 요청 직렬화 불필요 | — |

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
> **(마감 세션 주)** 둘 다 배포 세션(Render)에서 확인됨: 413은 JSON으로 도달(A-13), client IP는 결함 발견 후 교정(A-38). SECURITY_REVIEW §6.

## 6. 알려진 한계

| 한계 | 영향 | 비고 |
|---|---|---|
| `max-request-size` 11MB 고정 | 파일 한도를 올리면 메시지·프론트 안내와 실제 한도가 어긋남. 1MB 미만 한도는 "0MB" 표시 | A-22. 기본 10MB에선 무관. **테스트 세션에서 해소**(요청 한도 = 파일 한도 + 1MB, A-27). "0MB" 표시는 남음 |
| rate limit은 인스턴스 메모리 | 재시작 시 초기화, 다중 서버에서 무력 | Redis ProxyManager로 교체 (§4) |
| rate limit은 고정 창과 비슷한 충전 방식 | 창 경계에서 최대 2n회 연속 허용(예: 59초에 30회 + 61초에 30회) | 설명 쉬움을 택한 결과(설계 §7-3) |
| rate limit은 실패한 요청(400·409)도 1회로 셈 | 잘못된 입력을 반복하면 정상 요청도 막힘 | 남용 방지 목적상 의도 |
| 신뢰 프록시 설정 미검증 | 배포 환경에 따라 전 사용자 버킷 공유 또는 XFF 위조 허용 | A-17, 배포 세션 필수. **배포 세션에서 해소**(A-38) |
| 413이 브라우저에 보이는지 미확인 | 프록시가 연결을 끊으면 `NETWORK_ERROR`로 보일 수 있음 | A-13. 클라이언트 사전 검사로 정상 경로에선 요청 자체가 안 나감. **배포 세션에서 확인**(413 JSON 도달) |
| 깨진 요청 라인은 Tomcat HTML 400 | §5-1 형식 아님 | A-4. 정상 클라이언트는 못 만드는 요청 |
| 업로드 파일 휘발성 | 재배포 시 삭제 | 의도(CONSIDERATIONS 3-3) |
| 블랙리스트 방식 | 목록 밖 위험 확장자(`ps1`, `html` 등) 통과 | CONSIDERATIONS 7-5 |
| 텍스트 실행 파일(`bat`·`cmd`·`js`)은 내용 검증 불가 | 확장자를 바꾸면 통과 | CONSIDERATIONS 2-1 |
| 테스트 코드 없음 | 이 문서 §5의 수동 확인이 전부 | 테스트 세션 몫. **해소**: 테스트 세션 136개 → 보안 세션 166개 (VERIFICATION, SECURITY_REVIEW §9) |

## 7. 테스트 세션이 의심해야 할 지점

> 아직 자동 테스트가 없다는 전제. 수동 확인은 "내가 생각한 경로"만 지났다. 아래는 **코드를 쓴 쪽이 확신하지 못하는 곳**이다. 코드에서 기대값을 역산하지 말고 스펙(DESIGN §6·§12)으로 판단할 것.

**판정 (`ExtensionJudge`)**
1. `filename=""`인 파일 파트(브라우저가 파일 없이 폼을 보낼 때) → 지금은 400 `INVALID_FILENAME`(실측). 스펙상 `MISSING_FILE`이어야 하는지 판단 필요.
2. 점만 있는 이름 `...`, `. .` → 끝의 점·공백 제거 후 빈 문자열이 되어 **통과**한다(실측 201). 의도대로인가?
3. 끝에 탭·제어문자 `a.exe\t` → `[. ]`만 제거하므로 구간이 `exe\t`가 되어 **통과**(실측 201). 위협 모델 밖(1-11)이지만 동작을 테스트로 고정할지.
4. `a.exe/` → basename이 빈 문자열 → `INVALID_FILENAME`. 맞는가?
5. Spring `getOriginalFilename()`이 경로를 떼는지는 버전마다 다르다. MockMvc로 `C:\fakepath\a.exe`를 보냈을 때 응답 `fileName`이 `a.exe`인지 확인(판정은 어느 쪽이든 차단돼야 함).

**정책 API**
6. 같은 고정 확장자에 `true`와 `false`를 **동시에** PUT → 최종 상태·감사 행 수·두 응답 본문이 서로 모순되지 않는지.
7. 커스텀 추가가 잠금을 잡은 동안의 DELETE(ROW EXCLUSIVE 대기) — 대기 후 정상 처리되고 교착이 없는지.
8. `DuplicateKeyException` 경로는 API만으로는 도달하지 않는다(잠금 아래 사전 조회가 먼저 잡음). 잠금을 거치지 않는 직접 INSERT와 경합시켜야 검증 가능.
9. `customLimit`을 설정으로 낮춘 상태(예: 3)에서 이미 더 많은 행이 있으면 → 추가는 409, 삭제는 정상인지.

**업로드**
10. A-22: `APP_UPLOAD_MAX_FILE_SIZE`를 바꿨을 때 메시지·`maxFileSize`·실제 거절 지점이 일치하는지(현재는 어긋남이 예상됨).
11. `size` 응답값(`MultipartFile.getSize()`)이 실제로 저장된 바이트 수와 같은지.
12. 응답 헤더·본문 어디에도 클라이언트가 보낸 `Content-Type`이 되돌아오지 않는지(T6 P1).

**Rate limit**
13. 충전 경계: 1분 경계에서 2n회가 허용되는 것은 의도된 동작. "엄격한 슬라이딩 윈도우"로 테스트를 쓰면 틀린 실패가 난다.
14. 버킷 만료(`가득 찰 때까지 시간 + 10분`) 후 같은 IP가 새 버킷을 받는 시점에 한도가 풀리지 않는지 — 시계를 조작할 수 없어 수동으로 못 봤다.
15. 로컬에서 `127.0.0.1`과 `::1`은 서로 다른 키다(IPv4/IPv6). 테스트에서 클라이언트 주소를 고정할 것.

**프론트**
16. Enter를 아주 빠르게 두 번 → `adding` 상태가 렌더 전이면 POST가 2번 나갈 수 있다(가드가 React state 기반). 서버가 409로 막지만 "추가 성공 + 이미 등록" 메시지가 겹쳐 보일 수 있음.
17. 재조회 응답 순서 역전: 변경 성공 후 재조회와 `visibilitychange` 재조회가 겹치면 **늦게 시작한 요청의 응답이 먼저 오고, 오래된 응답이 나중에 덮어쓸 수** 있다. 재현은 지연 주입으로.
18. 프록시가 HTML 413/502를 돌려주면 `NETWORK_ERROR` 메시지("네트워크를 확인")로 보인다 — 원인과 맞지 않는 안내.

## 8. 다음 세션 인계

### 테스트 세션
- [ ] §7 "의심해야 할 지점" 18개를 스펙 기준으로 판정.
- [ ] §12 T1~T7 테스트 작성. Testcontainers는 Docker Engine 버전 확인 먼저 — 이 머신은 CLI 29.9.2 / **엔진 20.10.7(API 1.41)** 상태였다(A-1). docker-java가 최소 API 버전을 요구하면 Docker Desktop 재시작/업그레이드 필요.
- [ ] T7: 배포 사이트에서 10MB 초과 업로드 시 **413이 브라우저에 보이는지**, 아니면 `NETWORK_ERROR`로 보이는지 기록(A-13). 클라이언트 사전 검사로 요청이 안 나가는 것은 로컬 확인됨.
- [ ] 수동 확인의 재현: Task 3 케이스 목록은 위 §5 + DESIGN §12와 같다(코드에서 역산하지 말 것).

### 보안 세션
- [ ] A-4: 깨진 요청 라인은 Tomcat HTML 400(버전·스택 미노출 확인) — 수용 판단 재검토.
- [ ] A-16: rate limit 경로 우회 변형 추가 탐색(대소문자, 인코딩된 `/` 등).
- [ ] A-17: XFF 위조 시나리오를 배포 환경에서 재확인.

### 배포 세션 — ✅ 완료 (Render + Neon, 결과는 SECURITY_REVIEW §6)
- [x] **A-17**: 기본값으로는 Cloudflare 엣지 IP가 기록됨 → `SERVER_TOMCAT_REMOTEIP_REMOTE_IP_HEADER=CF-Connecting-IP`(배포 환경변수)로 교정, 내 공인 IP 기록·위조 거부·429 재검증 (A-38).
- [x] **A-21**: Neon 연결 문자열을 `SPRING_DATASOURCE_URL`(JDBC)·`USERNAME`·`PASSWORD`로 분해 (A-37).
- [x] `PORT`=10000 주입 확인, 컨테이너 512MB → 힙 384MB.
- [x] `/data/uploads` 볼륨 없음(Render 무료는 볼륨 불가 — 의도와 일치).
- [ ] 면접 전날 `/actuator/health`, 필요 시 `db/reset.sql` → README 배포 섹션.

### 마감 세션 — ✅ 완료
- [x] README 실행법에 §1 내용 + "최신 Docker Desktop 필요"(A-1) + 로컬 `APP_UPLOAD_STORAGE_DIR=./uploads`(A-2).
- [x] 스키마 문서 원본: `backend/src/main/resources/db/migration/V1__schema.sql` → README "DB 스키마"(컬럼·타입·제약·인덱스 이름은 `\d`로 확인).
- [x] CONSIDERATIONS 부록 A를 본문 섹션 번호와 연결 → 본문 제목에 `→ A-n` 포인터 + guide 매핑표.
