# VERIFICATION — 테스트/검증 세션

> 기준: DESIGN §5·§6·§12 + guide.md 고려사항. **기대값은 스펙에서 역산**했고 코드에서 가져오지 않았다.
> 순서: ① 스펙만 보고 케이스 목록 작성 → 사람 확인 ② 확정한 뒤에야 IMPL_NOTES §7(구현자가 의심한 18개)을 열어 대조 ③ 테스트 작성·실행 ④ 실패는 근본원인 분석 → 스펙상 정답으로 판정 ⑤ 사람 결정에 따라 수정 → 회귀 확인 ⑥ 브라우저 E2E.

## 1. 실행 방법과 최종 결과

```bash
cd backend && ./gradlew test        # Testcontainers(Postgres 17) — Docker 필요
cd frontend && npm test             # vitest (normalize.ts ↔ 서버 규칙)
```

| 스위트 | 파일 | 케이스 | 수정 전 실패 | 수정 후 실패 |
|---|---|---|---|---|
| 판정 단위 (J) | `backend/.../upload/ExtensionJudgeTest` | 56 | 5 (B1) | 0 |
| 정규화 단위 (N) | `backend/.../policy/ExtensionNormalizerTest` | 25 | 0 | 0 |
| 정책 API (P) | `backend/.../policy/PolicyApiTest` | 27 | 0 | 0 |
| 동시성 (C) | `backend/.../policy/ConcurrencyTest` | 7 | 0 | 0 |
| 업로드 API (U) | `backend/.../upload/UploadApiTest` | 16 | 1 (B1) | 0 |
| 설정 변경 컨텍스트 | `backend/.../ConfiguredLimitsTest` | 5 | 1 (B2) | 0 |
| 프론트 정규화 (F1) | `frontend/src/normalize.test.ts` | 26 | 2 (D1) | 0 (D1은 "수용된 분기"로 현재 동작을 고정) |

**최종(2026-10-24 22:41 KST): 백엔드 136개 실패 0개(BUILD SUCCESSFUL), 프론트 26개 실패 0개(vitest exit 0), `npm run build` exit 0.** 실행 로그: `docs/verification/test-run-after-fix.log`.

- 수정 전 RED는 B1·B2 테스트가 직접 증명한다: 수정 전 실행에서 7개 실패(로그는 아래 §2 각 항목의 재현에 포함). 운영 코드 수정은 3곳이다: B1 `ExtensionJudge`, B2 `ExtblockApplication`+`application.yml`, §7-17 `frontend/src/App.tsx`. 테스트 기대값은 바꾸지 않았다.
- 테스트 환경 결정: rate limit은 보안 세션 몫이라 테스트 프로필에서 한도를 크게 올렸다(200개 상한 테스트가 30회/분에 걸리지 않게, 사람 승인). 업로드 테스트는 MockMvc가 아니라 **실제 Tomcat(RANDOM_PORT)** 에 multipart를 보낸다 — Spring 파서의 파일명 처리와 크기 제한이 실제로 동작해야 하기 때문.

## 2. Findings와 처리

### B1. 점·공백만으로 된 파일명이 통과·저장됨 — P0 · ✅ 수정
- **재현(수정 전)**: `..` `...` `. .` `.` `"   "` → 판정 통과, API 201, 저장 폴더에 UUID 파일 생성. 브라우저에서도 `'...' 업로드 성공`.
- **뿌리 = 스펙 순서 결함**: DESIGN §6-1은 빈 값 검사(3단계)를 끝 정리(4단계) **앞에만** 두었다. 구현(`ExtensionJudge`)은 스펙을 그대로 따랐다. 코드에서 테스트를 역산했다면 "201이 정답"으로 굳었을 케이스다.
- **판정**: "파일명이 없는 것과 같다" → `INVALID_FILENAME` (사람 결정).
- **수정**: `ExtensionJudge` 끝 정리 직후 `trimmed.isEmpty()` → `INVALID_FILENAME` (3줄). DESIGN §6-1에 **4a단계**를 추가하고 "독립 테스트가 스펙 순서 결함을 발견"으로 경위를 기록.
- **회귀**: J13b 5개 + U11(API, 저장 폴더 비어 있음 확인)이 RED에서 GREEN으로. 다른 판정 케이스 50개는 그대로 통과.

### B2. 파일 크기 한도를 올리면 메시지와 실제 거절 지점이 어긋남 — P1 · ✅ 수정
- **재현(수정 전)**: `app.upload.max-file-size=12MB` → GET `maxFileSize`=12MB인데, 11.5MB 업로드에 **413 "최대 12MB까지 가능합니다"** (스스로 모순).
- **근본원인**: `application.yml`의 `max-request-size: 11MB`가 고정값이라 파일 한도와 연결되지 않음(IMPL_NOTES A-22).
- **수정**: 요청 한도 = 파일 한도 + 1MB로 자동 연동(`ExtblockApplication#multipartConfigElement` 빈). yml은 덧셈을 못 해 빈으로 계산하고, yml의 multipart 크기 설정 2줄은 삭제.
- **`-1`(무제한)을 쓰지 않은 이유**: 수정 전에 Boot 4.1.2 메타데이터를 확인했더니 `server.tomcat.max-part-count` 기본값이 **50**이었다. `-1`이면 파일 파트 50개 × 10MB = **요청당 임시 디스크 최대 500MB**(기존 11MB의 약 45배). 컨트롤러가 `TOO_MANY_FILES`를 판정하기 전에 Spring이 파트를 임시 파일로 쓰기 때문이다. 보안 회귀라 기각.
- **회귀**: U18(12MB 한도에서 11.5MB → 201) RED에서 GREEN으로. U15(기본 한도에서 정확히 10MB → 201, 10MB+1 → 413)도 통과 — 빈이 실제로 적용됐다는 증거다(무시됐다면 Boot 기본값 1MB에 걸려 실패).

### D1. 클라이언트·서버 정규화 분기 — P2 · 🔀 수용 (수정하지 않음)
- **사실**: `　sh`(전각 공백)는 클라이언트 통과 / 서버 400. `sh\0`은 반대로 클라이언트 거부 / 서버 `sh`로 수락. JS `trim()`(유니코드 공백 제거) ≠ Java `String.trim()`(U+0020 이하 제거).
- **수용 근거 (서버 권위 설계, CONSIDERATIONS A-18)**: 클라이언트는 정규화한 값이 아니라 **사용자 입력 원문을 전송**하고, 서버가 유일한 최종 판정자다. 그래서 두 규칙이 어긋나도 DB에 들어가는 값은 항상 서버 규칙을 통과한 값이고, 차이는 "사전 안내가 뜨는가/안 뜨는가" 수준의 UX 차이로만 남는다 → **보안 영향 없음**. 규칙을 완전히 똑같이 맞추려 하면 두 언어의 문자열 의미론을 계속 따라가야 하는데, 그 비용이 얻는 이득보다 크다.
- **서버 거부 메시지 확인 (브라우저)**: `　sh` 입력 → 클라이언트 사전 검사 통과 → POST 400 → 입력란 아래에 **"확장자는 영문 소문자와 숫자만, 1~20자로 입력하세요."** 표시, 입력값은 그대로 남아 고칠 수 있다(`docs/verification/d1_server_message.png`). 무엇을 고쳐야 하는지가 메시지에 들어 있어 명확하다. `sh\0`은 키보드로 입력할 수 없는 값이라 UX 영향이 없다.
- **테스트**: `normalize.test.ts`의 "accepted divergence" 블록이 현재 동작을 고정한다. 분기가 바뀌면(예: 누군가 규칙을 바꿈) 실패로 드러난다.

## 3. §7-16 / §7-17 프론트 레이스 — 지연 주입으로 검증

자동화 방식: 실제 브라우저(claude-in-chrome)에서 `window.fetch`를 감싸 **응답 지연을 결정적으로 주입**하고, 요청 로그(메서드·상태·시각)와 DB를 대조했다. vitest + jsdom + Testing Library로 옮기려면 의존성 3개가 더 필요해(과설계) 브라우저 주입으로 대신했다. 재현 스크립트는 아래에 그대로 남긴다. 증거: `docs/verification/extblock_races_d1.gif`.

### §7-16 (E7) Enter 2연타 — 실제 입력으로는 막힘 / 합성 이벤트로는 뚫림 (UX 수준)
| 시나리오 | 결과 |
|---|---|
| POST에 800ms 지연 주입 후 키보드 `Return Return` | **POST 1건**(201), 에러 메시지 없음 ✅ |
| 같은 JS 태스크에서 `form.requestSubmit()` 2회 (사람 입력으로는 불가능) | POST 2건(201 + 409), DB 1행 정상, 화면에 "'yy'은(는) 이미 등록되어 있습니다." — **성공했는데 실패 메시지** |
- **현재 설계가 막는 이유**: ① 이벤트 사이에 React가 렌더링하면 `if (adding) return`이 두 번째 제출을 막는다. React는 submit 같은 이산 이벤트 뒤 상태를 동기 반영한다. ② `<button disabled={adding}>` — HTML 규칙상 기본 제출 버튼이 disabled면 Enter로 하는 암묵적 제출도 일어나지 않는다. 키 입력은 별도 태스크라 두 장치가 모두 작동한다.
- **못 막는 경우**: 두 제출이 렌더링 없이 같은 태스크에서 일어나면 둘 다 같은 클로저(`adding=false`)를 본다. 가드가 React state 기반이라서다.
- **판정**: 데이터 정합성은 서버(LOCK + UNIQUE → 409)가 보장한다. 남는 것은 사람 입력으로 재현되지 않는 메시지 혼동뿐 → P2, 수정하지 않음. 고치려면 `useRef` 동기 가드 1줄.

### §7-17 재조회 응답 역전 — 재현됨 → ✅ 수정 → 재현 안 됨
- **재현 절차**: ① 첫 GET의 응답을 서버에서 받은 **뒤** 1.5초 붙잡아 둠 ② `visibilitychange` 재조회 시작(이 GET은 변경 **전** 상태를 읽음) ③ 0.3초 뒤 exe 체크박스 해제(PUT `blocked=false` → 성공 → 재조회 GET이 즉시 도착해 화면 갱신) ④ 붙잡아 둔 옛 GET이 1.8초에 도착.
- **결과**: DB `exe=false`(감사 `FIXED_UNBLOCK`)인데 **화면은 체크됨(`checked=true`)** — 옛 응답이 새 상태를 덮어씀(`docs/verification/7-17_stale_ui_checked.png`). 요청 로그: `GET#0 서버 응답 319ms → 화면 반영 1821ms`, `PUT 897→930ms`, `GET#2 930→945ms`.
- **현재 설계가 막지 못하는 이유**: `App.reload()`에 요청 순번·취소 장치가 없다. 비관적 UI와 요청 중 disabled는 **같은 컨트롤의 요청 순서**만 지키고, 서로 다른 출처의 재조회(포커스·변경 후)끼리의 순서는 지키지 않는다.
- **영향**: 데이터는 안전하다(서버가 기준). 화면만 다음 재조회 전까지 틀린다. 사용자가 틀린 화면을 보고 다시 클릭해도 목표 상태 PUT이라 no-op이고(감사도 남지 않음), 이어서 재조회가 화면을 바로잡는다. 발생 조건은 "탭 복귀 직후 GET이 도착하기 전에 변경을 누름" — 로컬 GET은 약 10ms라 사실상 불가능하고, 배포 지연(수백 ms)에서는 가능하다.
- **수정 (사람 결정)**: `App.reload()`에 요청 순번(`useRef` 카운터)을 둔다. 응답이 도착했을 때 자기 순번이 최신이 아니면 버린다 — 마지막에 시작한 재조회만 화면에 반영(로직 3줄). AbortController 대신 순번을 택한 이유: 취소는 `api.ts`의 fetch 래퍼에 signal을 뚫어야 해서 변경 범위가 넓고, 필요한 것은 "옛 응답 무시"뿐이다.
- **수정 후 재검증 (같은 스크립트)**: 같은 타이밍(GET#0이 PUT 전에 서버를 읽고 PUT 후 재조회보다 늦게 도착)에서 화면 `checked=false` = DB `false`. 방향을 바꿔 3라운드 반복(false→true, true→false, false→true) 모두 일치. 증거: `docs/verification/7-17_fixed_ui_matches_db.png`, `extblock_7-17_fixed.gif`, `test-run-after-fix.log` 끝부분.

<details><summary>재현 스크립트 (브라우저 콘솔)</summary>

```js
// 응답을 받은 뒤 지연 (요청 전 지연은 서버를 늦게 읽게 만들어 재현이 안 됨 — 첫 시도에서 확인)
window.__log=[]; const of=window.fetch;
window.fetch=async(u,o={})=>{const m=o.method||'GET', id=__log.length; __log.push({id,m,u:String(u)});
  const r=await of(u,o); const d=window.__delay?.(m,String(u))||0; if(d) await new Promise(s=>setTimeout(s,d));
  __log[id].status=r.status; return r};
let once=true; window.__delay=(m,u)=>(m==='GET'&&u.includes('/api/extensions')&&once)?(once=false,1500):0;
document.dispatchEvent(new Event('visibilitychange'));   // 이후 0.3초 안에 체크박스 클릭
```
</details>

## 4. 관찰 (버그 아님 — 스펙대로이거나, 스펙에 규정이 없고 무해)

| # | 관찰 | 판단 |
|---|---|---|
| O1 | `sh\0` → 서버가 `sh`로 저장 | §6-2 trim(Java)의 정의대로. 처음 세운 내 기대값(거부)이 틀렸던 것 — 테스트를 스펙에 맞게 정정 |
| O2 | `{"name":123}` → 201 `"123"` | Jackson scalar coercion. 스펙 미규정, 결과가 정상 확장자라 무해 |
| O3 | 켈빈 기호 `K`(U+212A) → `k`로 허용 | §6-2 순서상 소문자화 후 ASCII가 됨. 스펙대로 허용(사람 결정) |
| O4 | `a.exe\t` 통과 | §6-1은 끝의 점·공백만 정리. Windows는 파일명에 제어문자를 허용하지 않아 실행 경로 없음(1-11) → **보안 세션에서 변경**: 제어문자는 §6-1 3a에서 400 `INVALID_FILENAME_CHAR`(SECURITY_REVIEW S3, J28 갱신) |
| O5 | 한도를 넘는 요청은 Tomcat이 연결을 끊어 클라이언트가 413을 못 받기도 함 | DESIGN §6-4가 예고한 현상. 클라이언트 사전 검사로 대응(E4) |
| O6 | DuplicateKeyException 경로는 API로 도달 불가 | 잠금 아래에서는 INSERT 경쟁이 없음. spy로 사전 조회를 무력화해 409로 수렴함을 확인(P23) |

## 5. 스펙 목록 vs 구현자 의심 목록 (IMPL_NOTES §7) 대조

- **구현자가 놓친 것 (스펙 역산에서만 나온 것)**: 터키어 로케일 우회(J27·N7) / 확장자 없는 파일·점 파일 + MZ(J22·J23) / 반대 방향 MIME 스푸핑(U5) / 기본 unCheck 상태에서 exe 허용(U1) / 커스텀 확장자의 업로드 강제(U3) / 상한 상태에서 검사 순서(P6) / 클라이언트 `type`·`blocked` 무시(P10) / `mz`·`ZM`(J24) / 10MB·200개·20자 경계 / 클라이언트·서버 규칙 일치(F1 → D1).
- **구현자만 의심한 것**: §7-3, §7-6(반대 값 동시 PUT), §7-7(잠금 중 DELETE), §7-8(UNIQUE 최종 방어선), §7-9(한도 낮춤), §7-10(→ **B2**), §7-16(→ 합성 이벤트에서만 재현), §7-17(→ **재현 → 수정**), §7-18.
- **양쪽 모두 걸렸고 B1이 된 것**: §7-2 "점만 있는 이름이 통과(실측 201)". 구현자는 "의도대로인가?"라는 질문만 남겼다. 스펙에 답이 없어 사람 결정으로 버그 판정 → 스펙 §6-1 순서 결함으로 뿌리를 확정.
- **§7 판정 요약**: 1 스펙대로(INVALID_FILENAME) · 2 B1 수정 · 3 스펙대로(O4) · 4 스펙대로 · 5 통과(실제 파서에서 `details.fileName=a.exe`) · 6·7·8·9 통과 · 10 B2 수정 · 11·12 통과 · 13~15 보안 세션 몫 · 16 사람 입력으로는 안전, 합성 이벤트로 메시지 혼동(P2) · 17 재현 → 수정 → 재현 안 됨 · 18 스펙 결함(non-JSON 응답 = NETWORK_ERROR 규칙이 프록시 413/502에 맞지 않는 안내) — 배포 후 관찰 필요.

## 6. E2E (claude-in-chrome, 로컬: Vite 5173 → Spring 8080 + compose Postgres)

배포 URL이 아직 없어(배포 세션 전) 로컬에서 구동. 증거: `docs/verification/extblock_e2e_local.gif`(50프레임 상한에서 잘림 — 200개 상한 장면은 `e6_limit_200.png`), `extblock_races_d1.gif`.

| ID | 시나리오 | 결과 |
|---|---|---|
| E1 | exe 체크 → 새로고침 → 유지 / sh 추가 → X 삭제 → 새로고침 → 없음 | ✅ |
| E2 | `exe` 입력 → 고정 확장자 안내 / `sh` 뒤 `SH` → "이미 등록" / 26자 입력 → 20자에서 잘림 | ✅ |
| E3 | `a.exe.txt` → "'.exe' 확장자는 업로드가 차단" / `fake.jpg`(MZ) → "실행 파일(PE) 형식이라 차단" / `report.pdf` → 성공 | ✅ |
| E4 | 11MB → 클라이언트 안내, 업로드 버튼 비활성, `/api/uploads` 요청 0건 | ✅ |
| E5 | 파일명 `<img src=x onerror=alert(1)>.txt` → 텍스트로 표시 (사전에 `dangerouslySetInnerHTML`/`innerHTML` 0건 확인) | ✅ |
| E6 | 199개 시드 + UI로 200번째 추가 → `200/200`, 입력·버튼 disabled, 안내 | ✅ |
| E7 | Enter 2연타 (§3) | ✅ 실제 입력 / ⚠️ 합성 이벤트 |
| B1 | 파일명 `...` (수정 전) | ❌ → 수정 후 API 테스트로 400 확인 |

E2E 후 로컬 DB는 매번 `db/reset.sql`로 초기화.

## 7. 다음 세션에 남기는 것
- **배포**: 배포 URL에서 E1~E6 재실행 + 10MB 초과 업로드 시 브라우저에 413이 보이는지, NETWORK_ERROR로 보이는지(A-13, §7-18). §7-17은 수정됨 — 배포 지연에서 E1을 돌릴 때 함께 관찰.
- **보안**: rate limit(§7-13~15), XFF 위조, actuator — 테스트에서는 rate limit 한도를 올려 두었으므로 보안 세션이 별도로 검증.
