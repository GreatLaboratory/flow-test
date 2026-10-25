# 파일 확장자 차단

> 이 README는 마감 세션에서 완성된다. 지금은 **배포 섹션**만 있다.

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
