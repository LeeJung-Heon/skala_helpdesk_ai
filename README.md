# Lab3 — SKALA HelpDesk AI (종합 실습)

사내 규정과 실시간 데이터를 함께 다루는 상담 어시스턴트.
**RAG · Tool · 메모리 · 안전 · 감사**를 하나의 흐름 안에서 협력시킨다.

## 시나리오

| 누가 | 무엇을 묻고 | 무엇이 필요한가 |
|---|---|---|
| 고객 | "반품 규정이 어떻게 되나요?" | 사내 문서 근거 + 출처 (RAG) |
| 고객 | "제 주문 12345 지금 어디예요?" | 실시간 주문 데이터 (Tool) |
| 고객 | "그럼 그거 반품 돼요?" | 앞 대화의 맥락 (Memory) |
| 고객 | "교환으로 바꿔 주세요" | 티켓 생성 + 승인 게이트 (Tool·통제) |

## Phase 별 구현 위치

| Phase | 무엇을 만들었나 | 파일 |
|---|---|---|
| 1 | 설정 외부화 · Advisor 체인 | `config/HelpDeskProperties.java`, `config/AiConfig.java` |
| 2 | 문서 인제스트 · 청크 검사 | `rag/IngestService.java`, `web/AdminController.java` |
| 3 | RAG 답변 + 출처 표기 | `chat/HelpDeskService.java#ask` |
| 4 | 주문·티켓 도구 | `tools/OrderTools.java`, `tools/TicketTools.java` |
| 5 | 대화 메모리 · 멀티턴 | `config/AiConfig.java#chatMemory`, `HelpDeskService#conversationId` |
| 6 | 구조화 응답 · SSE | `web/ChatController.java`, `chat/AnswerDto.java` |
| — | 감사 로깅 | `advisor/AuditAdvisor.java` |

> Phase 7(인증·인가)과 Phase 8(토큰 계측·골든셋)은 교재에서 취소선 처리된 범위 밖 항목이라 제외했다.

## Advisor 체인 순서 — 순서가 곧 정책이다

```
0    SafeGuard   민감어 차단
100  Audit       감사 로깅
200  Memory      대화 이력 주입·저장
300  RAG         문서 근거 주입
```

**차단은 저장보다 앞이어야 한다.** SafeGuard 가 Memory 뒤에 있으면 차단된 요청이
이미 대화 이력에 저장된 뒤라, 다음 턴에 그 내용이 다시 모델로 들어간다.

## 실행

브라우저에서 `http://localhost:8080/` 을 열면 상담창구 화면이 나온다.
왼쪽 시연 흐름을 순서대로 누르면 규정 → 주문 → 후속 → 교환 → 차단을 같은 세션으로 볼 수 있다.

### 기본 — pgvector (권장)

```bash
export OPENAI_API_KEY="sk-..."
docker compose up -d       # pgvector (선택 — bootRun 이 자동으로 올려 주기도 한다)
./gradlew bootRun          # docker-compose.yml 의 pgvector 를 자동 기동·연결
```

### local 프로필 — Docker 없이

```bash
export OPENAI_API_KEY="sk-..."
./gradlew bootRun --args='--spring.profiles.active=local'
```

인메모리로 동작하므로 재시작 시 문서·대화 이력이 사라진다.

## API

```bash
# ① 문서 인제스트 (먼저 호출)
curl -X POST "localhost:8080/api/admin/ingest?docType=policy&dept=CS"

# ② 무엇이 들어갔는지 확인 — 임계값을 정하는 근거
curl "localhost:8080/api/admin/chunks?q=반품%20기간&topK=5"

# ③ 규정 질문 (RAG)
curl -X POST localhost:8080/api/chat -H 'Content-Type: application/json' \
  -H 'X-User-Id: user1' \
  -d '{"question":"반품 규정이 어떻게 되나요?","sessionId":"s1"}'

# ④ 주문 질문 (Tool)
curl -X POST localhost:8080/api/chat -H 'Content-Type: application/json' \
  -H 'X-User-Id: user1' \
  -d '{"question":"제 주문 12345 지금 어디예요?","sessionId":"s1"}'

# ⑤ 후속 질문 (Memory) — ③④와 같은 sessionId 를 써야 한다
curl -X POST localhost:8080/api/chat -H 'Content-Type: application/json' \
  -H 'X-User-Id: user1' \
  -d '{"question":"그럼 그거 반품 돼요?","sessionId":"s1"}'

# ⑥ 스트리밍 (SSE) — token 이벤트에 이어 마지막에 sources 이벤트
curl -N -X POST localhost:8080/api/chat/stream -H 'Content-Type: application/json' \
  -H 'X-User-Id: user1' \
  -d '{"question":"배송비 얼마예요?","sessionId":"s1"}'
```

응답 형태:

```json
{
  "answer": "상품 수령일로부터 7일 이내에 반품 신청이 가능합니다.",
  "sources": [{ "document": "return-policy.md", "version": "2026-08-20" }],
  "toolUsed": false
}
```

## 3턴 검증 — 반드시 해 볼 것

같은 `sessionId` 로 ③ → ④ → ⑤ 를 순서대로 호출한다.
⑤ 가 **"그거"를 12345 주문으로 알아듣고** 반품 규정과 함께 답하면
메모리·RAG·Tool 이 동시에 살아 있다는 뜻이다.

## 설계 결정

**소유자 검증은 도구 안에서.** 모델이 넘긴 `orderId` 는 사용자의 것이 아닐 수 있다.
사용자 ID 는 도구 인자가 아니라 `ToolContext` 에서만 꺼낸다(`tools/ToolUsers.java`).
`OrderRepository` 에 `findById` 를 두지 않고 `findOwned` 만 둔 것도 같은 이유다 —
존재하면 검증을 잊고 부를 위험이 생긴다.

**쓰기 도구는 승인 게이트를 거친다.** `createTicket` 은 접수만 하고
상태는 항상 `PENDING_APPROVAL` 로 시작한다. 도구 설명에 "승인 후 처리된다"를
명시해, 모델이 "환불해 드렸습니다"라고 단정하지 않게 했다.

**재색인이 먼저다.** 같은 문서를 `add` 만 반복하면 같은 청크가 쌓여
검색 결과가 한 문장으로 도배된다. 그래서 `source` 기준으로 지우고 다시 넣는다.

**`allowEmptyContext(true)`.** 근거가 없다고 즉시 중단하면 주문 조회 같은
도구 전용 질문까지 막힌다. 규정 질문에서 지어내는 것은 시스템 프롬프트가 막는다.

## Spring AI 2.0 API 차이 — 교재 코드와 달라진 부분

교재 예제는 Spring AI 1.x 기준이라 그대로는 컴파일되지 않는다.

| 교재 | Spring AI 2.0 |
|---|---|
| `QuestionAnswerAdvisor.builder(vs).searchRequest(...)` | `RetrievalAugmentationAdvisor.builder().documentRetriever(VectorStoreDocumentRetriever.builder()...)` |
| `QuestionAnswerAdvisor.RETRIEVED_DOCUMENTS` | `RetrievalAugmentationAdvisor.DOCUMENT_CONTEXT` |
| `spring-ai-advisors-vector-store` | **존재하지 않음** → `spring-ai-rag` |
| `new TokenTextSplitter(...)` | `TokenTextSplitter.builder()` |

`SafeGuardAdvisor` · `MessageChatMemoryAdvisor` · `MessageWindowChatMemory` ·
`ChatMemory.CONVERSATION_ID` · `TikaDocumentReader` 는 교재와 동일하다.

## 범위 밖으로 둔 것

- **인증·인가(Phase 7)** — 사용자 식별을 `Principal` 대신 `X-User-Id` 헤더로 받는다.
  헤더를 신뢰하는 구조이므로 **운영에 그대로 쓰면 안 된다**. Security 를 붙이면
  이 헤더를 `Principal#getName` 으로 바꾸기만 하면 된다.
- **토큰 계측·골든셋(Phase 8)** — Lab2 에서 다룬 골든셋 평가 방식을 그대로 적용할 수 있다.

## 테스트

```bash
./gradlew test    # API 키 · Docker 없이 동작 (local 프로필 컨텍스트 검증)
```
