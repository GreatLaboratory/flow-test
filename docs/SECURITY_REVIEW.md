# SECURITY_REVIEW — 보안 세션 산출물

> 기준: `docs/DESIGN.md` §1 위협 모델 + `CONSIDERATIONS.md` §1~§7·부록 A + `docs/IMPL_NOTES.md` §8 인계.
> 자세: **코드를 믿지 않는다.** 문서가 "구현함"이라 적은 방어를 요청 경로 끝까지 추적했고, 판단은 전부 **실제 Tomcat에 보낸 요청의 응답·로그로** 확정했다(MockMvc가 아님).
> 진행: ① 리뷰·시제품 검증(레포 밖 복사본) → ② 사람 승인(S1~S3 + 문서 정정 전부, `ſ` 접기 방어 추가 지시) → ③ 운영 코드·테스트·스펙 반영 + 수정된 jar 재공격(§9). **최종 상태: S1~S3 수정 반영, 백엔드 166/0 · 프론트 26/0.**

## 0. 요약

| ID | 심각도 | 한 줄 요약 | 상태 |
|---|---|---|---|
| **S1** | **Medium** | 업로드 외 요청은 **본문 크기 상한이 없다** → 인증 없이 요청 1개로 `OutOfMemoryError` (urlencoded는 rate limit보다 **먼저**, 모든 경로에서) | ✅ 수정 반영 (A-30) |
| **S2** | Low | 깨진 multipart → **500 + 요청당 약 6KB ERROR 스택 로그**. 경로·rate limit 무관 → 로그 증폭, 클라이언트 오류가 서버 장애로 집계됨 | ✅ 수정 반영 (A-31) |
| **S3** | Low | 제어문자 파일명 통과: `filename*=UTF-8''a.exe%00` → 201 `"a.exe\u0000"`, DEL(`%7F`)은 화면에 **`'a.exe' 업로드 성공`** 으로 보임. CONSIDERATIONS 1-11 제외 근거를 뒤집음 | ✅ 수정 반영 (A-32) + `ſ` 접기 (A-33) |
| **S6** | **Medium** | (배포 세션) 배포 환경에서 클라이언트 IP가 Cloudflare 엣지 IP로 기록 → per-IP rate limit 우회(업로드 14/14 통과)·사용자 간 버킷 공유·감사 IP 무의미 | ✅ 배포 환경변수 1줄로 수정·재검증 (A-38, §6) |
| S4 | Info | multipart 파싱은 **모든 경로**에서 일어난다(`POST /nope` + 12MB → 413). rate limit 밖에서 요청당 임시 디스크 최대 11MB를 쓸 수 있음(영구 저장은 아님) | ⛔ 수용 (A-36) |
| S5 | Info | 업로드는 교차 출처 simple request라 다른 사이트가 방문자 브라우저로 업로드시킬 수 있음(방문자 IP로 rate limit 분산). JSON API는 preflight에서 막힘(확인) | ⛔ 수용 (A-36) |
| D1 | 문서 불일치 | CONSIDERATIONS 1-15 "파서가 하나"는 사실이 아님 — Tomcat FileUpload와 Spring `ContentDisposition` **두 개**. 결론(제외)은 유지 | ✅ 문서 정정 (A-34) |
| D2 | 문서 불일치 | `RateLimitFilter` 주석·DESIGN §7-3 "본문 파싱 전에 판단" — 업로드 경로는 맞지만, `FormContentFilter`가 **앞에서** PUT/DELETE urlencoded 본문을 끝까지 읽는다(S1의 원인) | ✅ 정정 + 필터 제거 (DESIGN §7-3, A-35) |

**인계 3건**: A-4 ✅ 버전·스택 미노출 확인 · A-16 ✅ 변형 24개 중 우회 0 · A-17 로컬 동작 ✅ / 배포 동작 → §6에서 **결함 발견(Cloudflare IP 기록) → 수정·재검증 ✅** (A-38).

---

## 1. 방법

- `security-review` 스킬은 `origin/HEAD` 기준 diff 리뷰라 이 레포(원격 HEAD 없음)에서 로드가 실패했다. 이번 과제는 diff가 아니라 전체 코드 적대적 리뷰라, 스킬 방법론(요청 경로 추적 → 실제 악용 가능한 것만 → 오탐 배제)을 수동으로 따랐다.
- 환경: `bootJar`를 **JDK 21 + `-Xmx384m`**(운영 컨테이너 512MB × 75%와 같은 힙 상한)로 실행, compose Postgres 17. 로컬 접속은 127.0.0.1 = `RemoteIpValve` 신뢰 대역이라 `X-Forwarded-For`로 프로브마다 다른 IP 버킷을 썼다(이 사실 자체가 A-17 로컬 확인).
- 시제품 검증(승인 전): `backend/`를 scratchpad로 복사 → 수정 4개 적용 → 8081 포트로 같은 공격 재실행 + 기존 테스트 136개 실행. 승인 전에는 레포 파일을 바꾸지 않았다. 테스트 후 `db/reset.sql`로 DB 원복.

---

## 2. Findings (실제 취약점)

### S1. 업로드 외 요청 본문 크기 상한 없음 → 단일 요청 OOM — **Medium**

**경로 추적**
- 업로드(`/api/uploads`)는 `MultipartConfigElement`(B2)가 11MB로 막는다. 그런데 **그 외 요청 본문에는 아무 상한이 없다.** Tomcat `maxPostSize`(2MB)는 Tomcat 자신이 form 파라미터를 파싱할 때만 적용된다.
- (a) **urlencoded**: Spring Boot 기본 `FormContentFilter`(order −9900)는 `PUT|PATCH|DELETE` + `application/x-www-form-urlencoded`이면 경로와 무관하게 본문 전체를 `StreamUtils.copyToString`으로 메모리에 읽는다. `RateLimitFilter`(기본 order = 가장 나중)보다 **먼저** 실행된다.
- (b) **JSON**: `@RequestBody` 문자열은 Jackson이 통째로 만든다. 100MB `name`도 막히지 않았다(Jackson 문자열 상한이 이 크기에서 작동하지 않음, 실측).

**재현 (실측)**
| 요청 | 결과 |
|---|---|
| rate limit 소진 IP, `PUT /api/extensions/fixed/1` urlencoded 50MB | **50MB 전부 수신** 후 429 (0.25초). 같은 크기 JSON 대조군은 클라이언트 송신 785KB에서 연결 종료(앱이 즉시 응답 → Tomcat swallow 후 종료) |
| 인증·경로 무관 `PUT /nope` urlencoded 300MB | 136MB 수신 시점 `java.lang.OutOfMemoryError: Java heap space` — 스택 `FormContentFilter.parseIfNecessary → FormHttpMessageConverter.read → StreamUtils.copyToString` |
| `POST /api/extensions/custom` JSON `name` 100MB | `ServletException: Handler dispatch failed: OutOfMemoryError` → 500 |
| 200MB 동시 4개 공격 중 정상 업로드 40회 | 40/40 성공, JVM 생존, OOM은 공격 스레드에서만 |

**공격 시나리오**: 인증 없이 아무 경로에 큰 urlencoded PUT을 동시에 몇 개 보낸다. rate limit은 본문을 다 읽은 뒤에야 판단하므로 소용없다. 힙 384MB에서 요청 하나가 OOM을 낸다.

**심각도 근거**: 무인증 + 요청 1개로 OOM이라 Medium. High가 아닌 이유는 실측에서 JVM이 살아남았고 정상 요청 부수 피해도 0/40이었기 때문이다. 다만 동시성이 높아지면 같은 순간에 할당하는 다른 요청이 실패할 수 있고, 배포 프록시가 본문 크기를 제한하는지는 모른다(§6 체크리스트).

**수정 (반영됨)** — 뿌리는 "업로드 외 요청에 본문 상한이 없다" 하나라서, 요청이 모두 지나는 곳(`RateLimitFilter`) 한 군데에서 막는다.
```yaml
# application.yml — 이 앱엔 form 엔드포인트가 없다(JSON + multipart만). 쓰지 않는 공격면 제거
spring:
  mvc:
    formcontent:
      filter:
        enabled: false
```
```java
// RateLimitFilter — 업로드 외 요청은 16KB에서 끊는다. Content-Length가 아니라 실제 읽은 양 기준이라 chunked도 막힌다.
// ContentCachingRequestWrapper는 Spring에 이미 있는 클래스이고, handleContentOverflow가 바로 이 용도의 확장 지점이다.
private static final int MAX_BODY = 16 * 1024;
...
chain.doFilter("upload".equals(bucket) ? request : limitBody(request), response);

private static HttpServletRequest limitBody(HttpServletRequest request) {
	return new ContentCachingRequestWrapper(request, MAX_BODY) {
		@Override
		protected void handleContentOverflow(int limit) {
			throw new ApiException(ErrorCode.INVALID_REQUEST);
		}
	};
}
```
**시제품 결과**: urlencoded 300MB → 404, 0.016초(클라이언트 송신 785KB — 앱이 읽은 양이 아니라 Tomcat swallow + 소켓 버퍼, §9에서 정정) · JSON 100MB(chunked) → 400, 0.03초 · JSON 100MB(Content-Length) → 400, 0.005초 · OOM 0건 · 정상 추가·삭제·체크 변경·9MB 업로드(201)·12MB 업로드(413) 그대로 · 기존 테스트에서 S1 때문에 깨진 것 없음.
**감수할 비용**: 16KB를 넘는 JSON은 400. 현재 가장 큰 정상 본문은 `{"name":"<20자>"}`라 영향 없음.

### S2. 깨진 multipart → 500 + 큰 ERROR 스택 로그 — **Low**

- **경로**: `MultipartException`(`MaxUploadSizeExceededException`이 아닌 것)은 `ErrorResponse`가 아니라서 `GlobalExceptionHandler.isClientError`에 걸리지 않고 `log.error(스택)` + 500 `INTERNAL_ERROR`가 된다.
- **재현**: 끝 경계가 없는 본문 / `boundary` 없는 `multipart/form-data` / 이름 중간에 NUL(`a.exe\0.jpg` → Tomcat `InvalidFileNameException`) → 모두 500, 요청당 ERROR 로그 약 6.2KB. **어느 경로로 보내도 같고**(`POST /nope`) rate limit도 없다.
- **영향**: 100바이트 요청 하나가 6KB 스택 로그가 된다(약 60배 증폭). 로그 보관량이 작은 호스팅에서 진짜 장애 로그를 밀어내고, 클라이언트 실수가 "서버 500"으로 집계된다. 응답에는 스택이 없다(확인).
- **수정 (반영됨)**: `isClientError`에 `|| e instanceof MultipartException` 1줄. 크기 초과는 더 구체적인 `@ExceptionHandler(MaxUploadSizeExceededException)`가 먼저 잡으므로 413은 그대로다(시제품 확인).
- **감수할 비용**: multipart 임시 파일을 쓰다가 디스크가 가득 찬 경우도 400으로 분류된다. 그 상황에서는 최종 저장(`STORAGE_FAILED`)도 함께 실패해 원인이 드러나므로 수용 가능하다고 판단.

### S3. 제어문자 파일명 통과 — **Low** (CONSIDERATIONS 1-11 제외 근거 재도전)

- **재현**
  | 파일명 (multipart) | 현재 | 의미 |
  |---|---|---|
  | `filename*=UTF-8''a.exe%00` | **201** `"fileName":"a.exe\u0000"` | 구간이 `exe\0`이라 `exe` 차단을 피함 |
  | `filename*=UTF-8''a.exe%7F` (DEL) | **201**, 화면에 `'a.exe' 업로드 성공` | DEL은 보이지 않아 **exe가 통과한 것처럼 보임** |
  | `filename="a.exe\x01"` | **201** | |
  | `filename="a.exe\0.jpg"` | 500 (Tomcat이 거부, S2) | |
- **왜 1-11 근거가 약한가**: "Java 문자열은 `\0`에서 잘리지 않는다"는 맞다. 그런데 확장자 판정의 목적은 **이 이름이 결국 OS·사용자에게 무엇으로 보이는가**다. C 계열 API는 `a.exe\0`을 `a.exe`로 자르고, DEL은 화면에서 사라진다. 게다가 `filename*`은 Tomcat이 디코딩하지 않아 Tomcat의 NUL 검사도 지나간다(D1과 같은 뿌리).
- **실행 위험은 여전히 없다**: 저장은 UUID이고, Windows는 0x00~0x1F를 파일명에 허용하지 않으며 DEL이 붙은 이름은 확장자가 `exe`가 아니다. 그래서 Low다. 그래도 고치자고 하는 이유: **정상 파일명에는 제어문자가 없어 오탐 비용이 0이고**, 면접관이 "exe를 막았는데 `a.exe 업로드 성공`이 뜬다"를 보는 것은 실제 감점 위험이다.
- **수정 (반영됨, DESIGN §6-1 3a)**: basename에 `\p{Cntrl}`(U+0000~001F, U+007F)이 있으면 400 **`INVALID_FILENAME_CHAR`** "파일명에 허용되지 않는 문자(제어문자)가 포함되어 있습니다." 사유가 "없음/255자 초과"와 달라 사람 요청으로 code를 분리했다(프론트 `types.ts` union 1줄).
  ```java
  private static final Pattern CONTROL = Pattern.compile("\\p{Cntrl}");
  ...
  if (CONTROL.matcher(baseName).find()) {
      return new Verdict(ErrorCode.INVALID_FILENAME_CHAR, baseName, List.of());
  }
  ```
  결과: 위 세 경우 모두 400, `report.pdf`·`홍길동 이력서.v2.pdf` 201 (U19·J31).
- **함께 바꾼 것**: 테스트 **J28 `controlCharTail`** 기대값 2줄(`a.exe\t`·`file.exe.\n` → `INVALID_FILENAME_CHAR`). DESIGN §6-1 3a, VERIFICATION O4에 변경 표시.
- **추가 방어 (사람 지시, A-33)**: §4의 `ſ` 가설에 대해 판정 구간을 `toUpperCase(ROOT).toLowerCase(ROOT)`로 접는다. `a.ſcr`·`a.jſ`·`setup.msı` 차단(J29, U21), ASCII 전체에서 기존 소문자화와 같음(J30), 정상 파일명 10종 통과(J31).

### S4. multipart 파싱은 모든 경로에서 일어남 — Info (수용 제안)

- `DispatcherServlet.checkMultipart`는 핸들러를 찾기 **전에** 실행된다. 실측: rate limit을 소진한 IP가 `POST /nope`에 12MB multipart → **413**(파싱했다는 증거), 9MB → 다 받은 뒤 404.
- 영향: rate limit 밖에서 요청당 최대 11MB 임시 파일 쓰기와 대역폭. 요청이 끝나면 지워지므로 디스크 고갈은 아니다. S1 수정의 래퍼는 이걸 막지 못한다(Tomcat `getParts()`는 래퍼 스트림이 아니라 내부 스트림을 읽는다).
- 판단: 상한(11MB)이 있고 영구적이지 않아 수용. 막으려면 `spring.servlet.multipart.resolve-lazily=true`가 후보지만(실제로 `getFiles`를 부를 때만 파싱) **검증하지 않았다**.

### S5. 업로드의 교차 출처 요청 — Info (수용 제안)

- JSON API: 다른 출처의 preflight에 `Access-Control-Allow-Origin`이 없어 브라우저가 막는다(확인). `text/plain`으로 보내는 simple request는 400(Jackson이 거부, 확인).
- 업로드: `multipart/form-data` POST는 simple request라 `Origin: https://evil.example`로도 201. 무인증이라 공격자가 직접 호출하는 것과 권한은 같다. 남는 것은 "방문자들의 IP로 업로드를 분산시켜 per-IP rate limit을 피하는 수단"뿐이고, 디스크 고갈 판단(§3 5-4)에 포함된다.

---

## 3. 문서가 "구현함"이라 적은 방어 — 코드 경로 대조

| 문서 주장 | 코드 위치 | 확인 | 결과 |
|---|---|---|---|
| UUID로 저장, 확장자 없음, 원본명은 경로에 안 씀 (3-1) | `FileStorage.save` — `storageDir.resolve(UUID)`, `Files.copy`(덮어쓰기 없음) | 코드 + 저장 폴더 | ✅ |
| 저장 파일을 서빙하지 않음 (3-2) | static 위치는 Boot 기본 `classpath:` 4곳뿐, `/data/uploads` 매핑 없음 | 코드 + yml | ✅ |
| 판정 전 최종 위치에 안 씀 (6-4) | `UploadService` — 판정 → `toException` 또는 `save` | 코드 | ✅ |
| 클라이언트 Content-Type 미사용·미반환 (2-2) | `getContentType()` 호출 0건, `UploadResult`·`details`에 없음 | grep + 응답 | ✅ |
| 메모리에 통째로 올리지 않음 (3-4) | multipart는 threshold 0(디스크) | 코드 | ⚠️ **multipart만 맞음** — 그 외 본문은 S1 |
| rate limit은 본문 파싱 전에 판단 (DESIGN §7-3, 필터 주석) | `RateLimitFilter` 자체는 본문을 안 읽음 | 실측 | ⚠️ **D2** — 앞선 `FormContentFilter`가 읽음 |
| 경로 비교 `getServletPath()` (A-16) | `RateLimitFilter.bucketFor` | 변형 24개 (§5) | ✅ |
| XFF 직접 파싱 안 함, `RemoteIpValve` (5-2) | `ClientInfo.from` = `getRemoteAddr()`만 | 실측 (§5) | ✅ (배포 동작은 §6) |
| 스택은 응답에 안 넣음 (A-3) | `GlobalExceptionHandler` | 500 응답 본문 | ✅ (단 S2의 분류 문제) |
| 로그 인젝션 → JSON 한 줄 (7-2) | `logging.structured.format.console: ecs` | 개행·NUL 파일명 로그 | ✅ 한 줄, `\n`·`\u0000` 이스케이프 |
| Actuator health만, 상세 숨김 (5-7) | yml | `/actuator/health` 응답 | ✅ |
| 단일 파서 (1-15) | Tomcat FileUpload가 파트를 파싱, Spring이 `Content-Disposition`을 **다시** 파싱 | NUL 실측 | ❌ **D1** — 파서는 2개 (§4) |
| 커스텀 입력 서버 검증 + DB CHECK (4-1) | `ExtensionNormalizer` + `extension_name_format` | 테스트 세션이 검증 | ✅ (재검증 생략) |

---

## 4. "의도적 제외" 재도전

| 항목 (CONSIDERATIONS) | 실제로 시도한 공격 | 판정 |
|---|---|---|
| 1-10 NTFS ADS | `a.exe:$DATA`, `a.exe::$DATA` → 201 | **유지.** 원본명으로 파일을 만드는 코드가 없고, 브라우저 다운로드는 `:`를 바꿔 저장한다. 다운로드 기능이 생기면 재검토 목록에 넣을 것 |
| 1-11 제어문자·null byte | §2 S3 | **뒤집기 제안** (S3) |
| 1-12 RTL override | `inv‮gpj.exe` → 422(정상 차단) / `inv‮exe.jpg` → 201, 화면에 `invgpj.exe`처럼 보임 | **유지.** 실제 확장자는 `jpg`이고, 화면에 보이는 사람은 올린 본인뿐이다. `\p{Cf}`로 넓게 막으면 정상 텍스트의 ZWJ(이모지)·ZWNJ(페르시아어 등)까지 걸려 오탐 비용이 생긴다 |
| 1-13 전각·호모글리프 | `a.ｅxe` → 201 | **전각은 유지** (OS가 exe로 보지 않음). **대소문자 접기 계열은 방어 추가(A-33, 사람 결정)** — 📝 근거가 된 **미검증 가설**: `ſ`(U+017F)·`ı`(U+0131)는 대문자로 바꾸면 ASCII `S`·`I`가 된다. Windows가 확장자 연결을 대소문자 무시로 비교할 때 이렇게 접는다면 `a.ſcr`(현재 201)가 `.scr`로 취급될 수 있다. Windows에서 확인하지 못했으므로 결함으로 올리지 않는다. → 확인 비용보다 1줄 방어가 싸서 반영 |
| 1-14 NFC/NFD | — | **유지.** 확장자가 ASCII라 정규화 형태가 바뀌어도 같다 |
| 1-15 `filename*` 불일치 | `filename="a.txt"; filename*=…a.exe` → 422 / 반대 → 201(`a.txt`) / RFC 2047 `=?UTF-8?B?YS5leGU=?=` → 422 / `filename` 2개 → 첫 값 | **결론 유지, 근거 정정(D1).** 파서는 2개다. 다만 판정·응답·로그가 모두 Spring 값 **하나**만 쓰므로 판정이 엇갈리는 지점이 없다. `filename*` 우선은 RFC 6266(브라우저)과 같은 방향이다 |
| 2-2 MIME 미사용 | — | **유지** (§3에서 코드로 확인) |
| 7-5 블랙리스트 | — | **유지** (요구사항). 목록 밖 위험 확장자는 이미 명시된 한계 |
| 5-1 무인증 | S5 | **유지.** 보완 장치(감사·rate limit)는 S1 수정 후에야 본문 크기 공격에도 유효해진다 |
| 5-4 디스크 총량 미제한 | 계산 | **유지하되 근거 교체.** "매 업로드마다 폴더 용량 계산은 비싸다"는 허수아비다 — `FileStore.getUsableSpace()`는 statfs 1회라 싸다. 진짜 근거: 여유 공간 하한을 둬도 **가득 찬 뒤의 업로드 가용성은 돌아오지 않는다**(multipart 임시 파일도 같은 디스크). 속도: IP 1개당 10회/분 × 10MB = 100MB/분 ≈ 6GB/시간. IPv6 /64를 가진 공격자는 주소를 바꿔 가며 per-IP 한도를 무력화할 수 있다 → 배포 디스크 크기와 IPv6 수신 여부를 §6에서 확인 |

---

## 5. 인계 3건 처리

### A-4 깨진 요청 라인 → Tomcat HTML 400 — ✅ 수용 유지
- `GE T / HTTP/1.1` → 400, `HTTP/9.9` → 505, `/%zz` → 400. 응답 본문은 `<h1>HTTP Status 400 – Bad Request</h1>`뿐이고 **Tomcat 버전, 스택, `Server` 헤더가 없다.** 수용 판단 유지.

### A-16 rate limit 경로 우회 — ✅ 우회 없음
업로드 버킷을 소진한 IP로 변형 24개를 `--path-as-is`로 보냈다.
| 결과 | 변형 |
|---|---|
| **429** (같은 버킷으로 정규화됨) | `/api;x/uploads` `/api/uploads;` `/api/x/../uploads` `/api/%2e%2e/api/uploads` `/api//uploads` `/./api/uploads` `/api/upl%6Fads` `/%61pi/uploads` `?x=1` `#x` |
| 404 (컨트롤러에 도달하지 않음 — 우회 아님) | 대소문자 `/API/uploads` `/api/Uploads`, 끝 슬래시 `/api/uploads/`, `.json`, `%2e` `%20` `%09` `%3B` |
| Tomcat 400 (도달 전 거부) | 인코딩된 `/` `%2F` `%2f`, `%5C`, `\`, `%00` |
- 판단 근거: Spring 경로 매칭은 대소문자를 구분하고 끝 슬래시를 매칭하지 않으며, Tomcat은 인코딩된 `/`·`\`를 거부한다. 컨트롤러에 닿는 변형은 모두 `getServletPath()`가 `/api/uploads`로 정규화한다.

### A-17 XFF 위조 / trust proxy — 로컬 ✅, 배포 **미검증**
- 로컬(127.0.0.1 = 신뢰 프록시) 실측, 감사 `client_ip`:
  `XFF: 6.6.6.6, 1.1.1.1` → `1.1.1.1` · `1.1.1.1, 10.0.0.5` → `1.1.1.1`(오른쪽 사설 IP 건너뜀) · `1.1.1.1, 2.2.2.2` → `2.2.2.2` · `evil-not-an-ip` → **`evil-not-an-ip`**
- 해석: 오른쪽부터 첫 번째 비신뢰 값을 쓰므로 **왼쪽 위조는 무력**하다. 대신 신뢰 프록시가 넘긴 값이면 IP 형식이 아니어도 그대로 믿는다. 즉 안전성은 전적으로 "Railway 프록시가 클라이언트 XFF 뒤에 실제 IP를 붙이는가"에 달려 있다 → §6.

---

## 6. 배포 세션 체크리스트 — 실행 결과 (배포 세션, 2026-10-25)

`$URL` = `https://extblock.onrender.com` (Render 무료 + Neon, 배포처 변경 경위는 CONSIDERATIONS A-37). 원본 출력: `docs/verification/deploy-check.log`.
내 공인 IP = `175.113.88.156`.

**A-17 신뢰 프록시 (필수)** — ❌ 발견 → ✅ 수정·재검증 (A-38, Medium)
- [x] 감사 `client_ip` = 내 공인 IP인가? → **수정 전 ❌** `104.22.66.249`/`.50`(Cloudflare 엣지). `server.tomcat.remoteip.remote-ip-header=CF-Connecting-IP`(Render 환경변수, 사람 승인) 후 **✅ `175.113.88.156`**.
- [x] 위조 `X-Forwarded-For: 9.9.9.9` → 수정 전·후 모두 9.9.9.9 **기록 안 됨 ✅**. 수정 후 추가 위조 `CF-Connecting-IP: 9.9.9.9` → Cloudflare가 엣지에서 **403 `error code: 1000`**(origin 미도달) ✅.
- [x] `X-Forwarded-For: 9.9.9.9, 10.0.0.1` → 수정 전 CF IP, 수정 후 같은 형태 + `CF-Connecting-IP: 10.0.0.1` → 403 ✅.
- [ ] 서로 다른 네트워크 2곳에서 업로드 11회씩 → **사람 결정으로 생략**(이 회선 하나뿐). 근거: 키가 실제 클라이언트 IP임은 감사 `client_ip`와 아래 429 결과로 확인됨.

**A-13 / §7-18 413 노출** — ✅
- [x] curl 12MB → **413 JSON** `FILE_TOO_LARGE`(1.2초). 프록시 HTML·연결 끊김 아님.
- [x] 브라우저 `fetch` 12MB `FormData`(클라이언트 검사 우회) → **413 `application/json`** → 화면은 `FILE_TOO_LARGE` 메시지 경로(`NETWORK_ERROR` 아님). `docs/verification/deploy/a13-browser-413-json.jpg`.

**프록시 뒤 rate limit** — ❌ → ✅
- [x] 수정 전: 같은 IP 업로드 14회 **모두 201**(버킷이 CF IP별로 분산). 수정 후: 업로드 10×201 → 11번째 **429** ✅, 정책 변경 30회 → 31번째부터 **429** ✅.
- [x] IPv6 수신: `extblock.onrender.com`에 **AAAA 레코드 없음** → IPv4 전용. /64 회전 우회는 현재 배포에서 해당 없음(플랫폼이 IPv6를 열면 재검토).

**S1 (수정 반영 후)** — ✅
- [x] `PUT /nope` urlencoded 300MB → 404, 6.2초. **로그 `OutOfMemoryError` 0, ERROR 0**, health UP. JSON 100MB → 400.
- [x] 프록시 본문 크기 제한: **없음**(300MB 전부 전송됨) → 앱 상한이 유일한 방어선(A-39).

**디스크 (5-4)** — ⚠️ 미확인
- [ ] `df -h`: Render 무료 인스턴스는 셸 미지원(`render ssh` → "Host key verification failed"), 공식 문서에도 무료 디스크 크기 없음 → **크기 미확인**. 대신: 디스크는 재배포·재시작·**15분 슬립마다 초기화**된다(Render 무료 문서). per-IP 10회/분 × 10MB = 100MB/분이 A-38 수정 후에야 실제로 IP당 상한이 된다. 가득 차면 `render restart`로 복구.

**배포 환경 기본값 확인**
- `PORT` 주입 → `Tomcat started on port 10000` ✅ · Flyway V1·V2 적용 ✅ · 컨테이너 `Memory: 512M` → `Heap Max Capacity: 384M` ✅ (보안 세션 `-Xmx384m`과 같은 조건) · 기동 81초(0.1 CPU).

---

## 7. 반영 내역 (사람 승인 후)

| # | 파일 | 변경 | 막는 것 | 테스트 영향 |
|---|---|---|---|---|
| 1 | `application.yml` | `spring.mvc.formcontent.filter.enabled: false` (4줄) | S1(a) urlencoded OOM, D2 | 없음 |
| 2 | `RateLimitFilter.java` | 업로드 외 요청 본문 16KB 상한 (`ContentCachingRequestWrapper` + `handleContentOverflow`, 약 10줄) | S1(b) JSON OOM, chunked 포함 | 없음 |
| 3 | `GlobalExceptionHandler.java` | `isClientError`에 `MultipartException` (1줄 + import) | S2 | 없음 |
| 4 | `ExtensionJudge.java` + `ErrorCode` + `UploadService` + `types.ts` | basename 제어문자 → `INVALID_FILENAME_CHAR` | S3 | J28 기대값 2줄 갱신 |
| 5 | `ExtensionJudge.java` | 구간 접기 `toUpperCase(ROOT).toLowerCase(ROOT)` (1줄) | A-33 `ſ`·`ı` | 없음 |

| 추가 테스트 | 내용 |
|---|---|
| `common/RequestBodyLimitTest` R1~R6 (신규) | FormContentFilter 빈 없음 / 16384바이트 201 / 16385바이트 400 / chunked 1MB 400 / PUT 초과 400·감사 없음 / 업로드는 상한 밖 |
| `UploadApiTest` U19~U21 | `filename*` `%00`·`%7F`·`%01` → 400 `INVALID_FILENAME_CHAR` + 메시지 / 깨진 multipart 3종 → 400 / 실제 파서로 `a.ſcr` 422, 정상 이름 201 |
| `ExtensionJudgeTest` J28 갱신, J29~J32 | 제어문자 6종 / 접기 호모글리프 4종 / ASCII 불변 / 정상 파일명 10종 |

- 스펙(DESIGN §5-2·§5-3·§6-1·§7-3)·코드·테스트·CONSIDERATIONS(A-30~A-36, 본문 1-11·1-15·5-4 포인터)를 한 커밋으로 묶었다.

## 8. 범위 밖으로 둔 것

- IMPL_NOTES §7-13~15(rate limit): 13 충전 경계 2n회는 설계 의도(§7-3). 14 버킷 만료 후 재발급은 시계를 조작할 수 없어 실행 검증하지 못했다 — 코드상 "가득 찰 때까지 시간 + 10분" 뒤 제거라 가득 찬 버킷만 사라지므로 한도가 풀리지 않는다(A-15 논증). 15 IPv4/IPv6 키 분리는 §6 IPv6 항목으로 넘긴다.

- 보안 응답 헤더(`X-Content-Type-Options`, `X-Frame-Options`, CSP) 없음 — 지금은 파일을 서빙하지 않고 응답이 JSON이며, 관리 화면은 무인증이라 clickjacking으로 얻을 권한이 없다. 다운로드 기능을 만들 때 3-2 확장 경로와 함께 넣을 것.
- Slowloris·연결 수준 DoS — 플랫폼 프록시가 맡는 영역.
- 감사 테이블 증가 — 정책 변경 30회/분/IP로 이미 상한이 있음(5-3). IPv6 회전 시 가속되는 문제는 §6 IPv6 항목과 같다.

---

## 9. 반영 후 검증 (수정된 jar, 증거: `docs/verification/security-test-run.log`)

- **RED → GREEN**: 운영 코드를 되돌린 상태(ErrorCode 1줄만 남겨 컴파일)에서 신규 테스트 **18개 실패** → 반영 후 `./gradlew clean test` **166개 실패 0**(이전 136 + 신규 30). 프론트 `npm test` 26/0, `npm run build` 성공.
- **같은 공격 재발사** (JDK 21, `-Xmx384m`)
  | 공격 | 수정 전 | 수정 후 |
  |---|---|---|
  | `PUT /nope` urlencoded 300MB | 136MB 수신 후 `OutOfMemoryError` | 404, 0.006초 |
  | `PUT /api/extensions/fixed/1` urlencoded 300MB | (rate limit 소진 IP도 50MB 전부 수신) | 400, 0.016초 |
  | `POST /api/extensions/custom` JSON `name` 100MB | 500, `OutOfMemoryError` | 400, 0.010초 |
  | 같은 JSON 100MB 동시 4개 | — | 4개 모두 400 |
  | 로그 | OOM·ERROR 스택 | **OOM 0, ERROR 0**, health UP |
- **"본문이 16KB에서 끊기는가" 점검**: 기본 설정에서 curl이 보고한 송신량(686KB~1.5MB)은 앱이 읽은 양이 **아니다** — 앱이 응답한 뒤 Tomcat이 남은 본문을 최대 2MB까지 읽어 버리는 swallow + 소켓 버퍼다. 시제품에서 본 785KB도 같은 이유이고, urlencoded는 FormContentFilter가 없어 앱이 본문을 아예 읽지 않는다. 앱이 읽는 양을 따로 보려고 송신 속도를 묶었다: 64KB/s에서 JSON 100MB는 **첫 64KB 버스트 안에서** 400, 16KB/s에서 JSON 200KB는 49KB 시점에 400(16KB 상한 + Jackson 읽기 버퍼), 정상 15KB는 201. 정확한 경계는 통합 테스트 R2(16384바이트 → 201)·R3(16385바이트 → 400)가 고정한다.
- **로컬 상태 원복**: 앱 종료, `db/reset.sql`, compose Postgres 정지. 시제품·jar·로그는 scratchpad에만 있다.
