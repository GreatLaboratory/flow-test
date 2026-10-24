# 프롬프트 캡처 워크플로우 (새 세션 agent용)

> 이 문서는 **다른 세션의 agent가 읽고 바로 실행**하기 위한 것이다.
> 목적: 과제 제출물 `PROMPT_LOG.md`의 원본 프롬프트를 조작 불가능한 형태로 자동 수집.
> 배경 전략은 전략 토론 세션에서 합의됨. 핵심만 아래에 정리.

---

## 왜 이렇게 하나 (1분 요약)

- 과제 평가의 절반이 "AI를 어떻게 썼는가"다. `PROMPT_LOG.md` 제출 필수.
- 손으로 프롬프트를 모으면 누락되고, 사후 재구성하면 가짜 티가 난다.
- Claude Code는 **모든 세션을 JSONL로 자동 기록**한다. 네가 친 프롬프트가 timestamp와 함께 verbatim 저장됨 → 이걸 추출하면 조작 불가능한 원본이 공짜로 생김.
- **2층 구조**로 제출한다:
  - **1층 (원본, 자동)**: JSONL에서 user 프롬프트만 추출 → `prompts/raw/*.md`. 조작 불가 증거.
  - **2층 (큐레이션, 수동)**: 사람이 각 프롬프트에 "의도 / AI 응답 / 내 판단(그대로/고쳐/버림)"을 한 줄씩 얹음 → `PROMPT_LOG.md`. 최종 제출물.

---

## 1층: 자동 추출 (매 작업 세션 종료 시 실행)

세션 로그 위치: Claude Code는 현재 작업 디렉토리 경로의 `/`를 `-`로 바꾼 이름으로 로그 폴더를 만든다.
예: cwd `/Users/greatlaboratory/Desktop/projects/flow-test` → `~/.claude/projects/-Users-greatlaboratory-Desktop-projects-flow-test/`
세션 1개 = JSONL 파일 1개. 파일명 = 세션 ID. **가장 최근 수정된 파일 = 방금 작업한 세션.**
아래 명령은 cwd에서 폴더명을 자동 계산하므로 어느 레포에서 실행하든 그 세션을 집는다.

### 추출 명령 (검증 완료 — 작업 레포 루트에서 그대로 복붙)

```bash
PROJECT_DIR=~/.claude/projects/$(pwd | sed 's#/#-#g')   # cwd 기반 자동 계산
SESSION=$(ls -t "$PROJECT_DIR"/*.jsonl | head -1)   # 가장 최근 세션
OUT=prompts/raw/$(date +%Y%m%d-%H%M)-$(basename "$SESSION" .jsonl).md
mkdir -p prompts/raw

jq -r '
  select(.type=="user" and (.message.content|type=="string"))
  | .message.content as $c
  # 슬래시 명령/훅 주입 노이즈 제거
  | select($c | test("^<(local-command|command-name|command-message)") | not)
  | select($c | startswith("## Context Usage") | not)
  | "## [" + (.timestamp // "?") + "]\n\n" + $c + "\n"
' "$SESSION" > "$OUT"

echo "추출됨: $OUT"
```

원본 JSONL도 함께 보관 (리뷰어 대조용, 신뢰 신호):
```bash
cp "$SESSION" prompts/raw/
```

### 이 필터가 제거하는 것
- `type=="user"`지만 content가 **배열**인 것 = 도구 실행 결과(tool_result). 네가 친 게 아니므로 제외.
- `<local-command-caveat>`, `<command-name>`, `<command-message>` 로 시작하는 줄 = `/model`, `/context` 등 슬래시 명령 배관.
- `## Context Usage` = `/context` 출력 덤프.

### 남는 엣지 케이스 (일부러 완벽 추구 안 함)
- 드물게 슬래시 명령 출력이 샐 수 있음. **2층 큐레이션에서 사람이 버린다.** 1층은 "대략 다 긁기"가 목표이지 완벽한 파서가 아니다. 과하게 다듬지 말 것.
- 세션이 깔끔히 3개(계획/코드/테스트)로 안 나뉠 수 있음(한 세션에 주제 섞임). 파일 분리는 "대략"이고, 2층에서 논리적으로 재그룹핑한다.

---

## 2층: 큐레이션 (사람이 작성, `PROMPT_LOG.md`)

1층 추출물을 열고, 각 프롬프트(또는 묶음) 위에 아래 템플릿으로 주석을 단다.
그대로 붙여넣기 금지 — **의도와 취사선택**이 드러나야 평가에서 먹힌다.

```markdown
### [계획 세션] 2026-10-22

#### [12:58] "대량 확장자 조회/저장 성능, 인덱스 고려해야 하는 거 아니야?"
- **의도**: 가이드의 성능·인덱스 항목에 대응. 그냥 인덱스 걸면 되는지 확인.
- **AI 응답**: 처음엔 인덱스 추가를 제안.
- **내 판단 (버림 → 재설계)**: 커스텀 최대 200행이면 인덱스 성능 효과 사실상 0.
  진짜 레버는 매 업로드마다 도는 hot-path 검증을 **블록리스트 메모리 캐싱**으로
  잡는 것. 인덱스는 성능이 아니라 중복방지(unique) 목적으로만 유지.
  → AI의 1차 답을 반박하고 방향을 틀었음.
```

### 채워야 할 4가지 (가이드 4-1, 4-3 대응)
1. **시간순 프롬프트** — 1층에서 자동 확보됨.
2. **의도** — 왜 그렇게 물었나.
3. **그대로 쓴 것 / 고쳐 쓴 것 / 버린 것** — 각각 이유 포함.
4. **AI가 놓쳤거나 틀렸는데 내가 잡은 부분** — 특히 강조. 가점 핵심.

---

## 스킬/도구 기록 (`PROMPT_LOG.md` 하단 or 별도 섹션)

작업 중 쓴 스킬·플러그인·MCP·에이전트·라이브러리를 **어디에 왜 썼는지 한 줄씩**:
```
- superpowers:brainstorming — 요구사항/설계 발산에 사용
- (예) magic-number 검증 라이브러리(Apache Tika) — 시그니처 기반 파일 타입 판별에 사용
```

---

## 체크리스트 (각 작업 세션 끝날 때)

- [ ] 위 추출 명령 실행 → `prompts/raw/*.md` 생성
- [ ] 원본 JSONL `prompts/raw/`에 복사
- [ ] (나중에) 2층 큐레이션으로 `PROMPT_LOG.md` 갱신
- [ ] 이번 세션에 쓴 스킬/도구 기록
