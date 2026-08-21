# SKALA HelpDesk AI

사내 규정과 실시간 데이터를 함께 다루는 상담 어시스턴트.
**RAG · Tool · 메모리 · 안전 · 감사**를 하나의 흐름 안에서 협력시킨다.

Spring Boot 4.1 · Spring AI 2.0 · Java 21 · Gradle 9.5

## 시나리오

| 누가 | 무엇을 묻고 | 무엇이 필요한가 |
|---|---|---|
| 고객 | "반품 규정이 어떻게 되나요?" | 사내 문서 근거 + 출처 (RAG) |
| 고객 | "제 주문 12345 지금 어디예요?" | 실시간 주문 데이터 (Tool) |
| 고객 | "그럼 그거 반품 돼요?" | 앞 대화의 맥락 (Memory) |
| 고객 | "교환으로 바꿔 주세요" | 티켓 생성 + 승인 게이트 (Tool·통제) |

## 실행

```bash
export OPENAI_API_KEY="sk-..."       # 키는 소스에 넣지 않는다
./gradlew bootRun                    # compose.yaml 의 pgvector 를 자동 기동·연결
```

Docker 없이 돌리려면 `local` 프로필을 쓴다. 인메모리로 동작하므로
재시작하면 색인과 대화 이력이 사라진다.

```bash
./gradlew bootRun --args='--spring.profiles.active=local'
```

| 주소 | 무엇 |
|---|---|
| <http://localhost:8080/> | **상담 챗봇 화면** — 여기서 대부분을 확인할 수 있다 |
| <http://localhost:8080/swagger-ui.html> | API 를 브라우저에서 직접 실행 |

첫 화면에서 오른쪽 **문서 색인** 카드의 문서를 고르고 `선택 주입`을 누르면 시작된다.

## 화면 — 판단 근거를 눈으로 본다

REST 응답의 JSON 을 그대로 보여 주는 대신, 무엇을 근거로 답했는지를 화면 요소로 만들었다.

| 요소 | 무엇을 증명하나 |
|---|---|
| 출처 칩 | 답변이 어느 문서에 근거했는지. **누르면 청크 원문이 펼쳐지고 근거 문장이 강조된다** |
| 도구 호출 배지 | 규정이 아니라 실시간 데이터를 썼는지 |
| 차단 배지 | 안전 장치가 걸렸는지와 그 사유 |
| 문서 색인 카드 | 문서별 체크박스 — **체크한 문서만 색인에 남는다** |
| 주문·신청 카드 | 대화로 바뀐 상태를 턴마다 다시 읽어 바뀐 줄만 강조 |
| 사용자 전환 | user1 ↔ user2 로 **권한 격리를 눈으로** 확인 |

## API

```bash
# ① 고를 수 있는 문서 목록 — 파일명·크기·현재 색인 청크 수
curl localhost:8080/api/admin/docs

# ② 전체 인제스트
curl -X POST "localhost:8080/api/admin/ingest?docType=policy&dept=CS"

# ②' 선택 인제스트 — 고른 문서만 색인에 남고 나머지는 빠진다.
#     selective=true 가 없으면 "sources 미지정 = 전부 주입"으로 해석된다.
curl -X POST "localhost:8080/api/admin/ingest?selective=true\
&sources=return-policy.md&sources=membership.md"

# ③ 무엇이 들어갔는지 확인 — 임계값을 정하는 근거
curl "localhost:8080/api/admin/chunks?q=반품%20기간&topK=5"

# ④ 검색 방식 비교 — 벡터 단독 / 키워드 단독 / 하이브리드 순위를 나란히
curl "localhost:8080/api/admin/search-compare?q=배송%20조회는%20어디에서%20하나요"

# ⑤ 규정 질문 (RAG)
curl -X POST localhost:8080/api/chat -H 'Content-Type: application/json' \
  -H 'X-User-Id: user1' \
  -d '{"question":"반품 규정이 어떻게 되나요?","sessionId":"s1"}'

# ⑥ 주문 질문 (Tool)
curl -X POST localhost:8080/api/chat -H 'Content-Type: application/json' \
  -H 'X-User-Id: user1' \
  -d '{"question":"제 주문 12345 지금 어디예요?","sessionId":"s1"}'

# ⑦ 후속 질문 (Memory) — ⑤⑥과 같은 sessionId 를 써야 한다
curl -X POST localhost:8080/api/chat -H 'Content-Type: application/json' \
  -H 'X-User-Id: user1' \
  -d '{"question":"그럼 그거 반품 돼요?","sessionId":"s1"}'

# ⑧ 스트리밍 (SSE) — token 이벤트에 이어 마지막에 sources 이벤트
curl -N -X POST localhost:8080/api/chat/stream -H 'Content-Type: application/json' \
  -H 'X-User-Id: user1' \
  -d '{"question":"배송비 얼마예요?","sessionId":"s1"}'

# ⑨ 내 현재 상태 — 모델을 거치지 않고 주문·티켓을 직접 읽는다
curl localhost:8080/api/me/state -H 'X-User-Id: user1'
```

응답 형태:

```json
{
  "answer": "상품 수령일로부터 **7일 이내**에 반품 신청이 가능합니다.",
  "sources": [{
    "document": "return-policy.md",
    "version": "2026-08-20",
    "text": "# 반품·교환·환불 규정\n\n## 1. 반품 신청 기간\n\n- 단순 변심: …",
    "highlights": ["- 단순 변심: 상품 수령일로부터 **7일 이내**"]
  }],
  "toolUsed": false
}
```

`text` 는 검색된 청크 원문, `highlights` 는 그중 **답변과 실제로 겹치는 문장**이다.
화면은 이 둘로 근거를 강조한다.

실패는 코드가 붙은 JSON 으로 온다. 본문 없는 500 이 아니라 **무엇을 고쳐야 하는지**가 온다.

```json
{ "error": "ai_unauthorized", "message": "모델 인증에 실패했습니다. OPENAI_API_KEY 를 확인하세요.", "traceId": "068af773" }
```

| 코드 | 상태 | 언제 |
|---|---|---|
| `ai_unauthorized` | 503 | 키가 없거나 폐기됨 |
| `ai_rate_limited` | 503 | 모델 호출 한도 초과 |
| `bad_request` | 400 | 잘못된 요청 |
| `internal_error` | 500 | 그 밖 — `traceId` 로 로그에서 찾는다 |

## 3턴 검증 — 반드시 해 볼 것

같은 `sessionId` 로 ⑤ → ⑥ → ⑦ 을 순서대로 호출한다.
⑦ 이 **"그거"를 12345 주문으로 알아듣고** 반품 규정과 함께 답하면
메모리·RAG·Tool 이 동시에 살아 있다는 뜻이다.

## Advisor 체인 순서 — 순서가 곧 정책이다

```
0    SafeGuard   민감어 차단
100  Audit       감사 로깅
200  Memory      대화 이력 주입·저장
300  RAG         문서 근거 주입
```

**차단은 저장보다 앞이어야 한다.** SafeGuard 가 Memory 뒤에 있으면 차단된 요청이
이미 대화 이력에 저장된 뒤라, 다음 턴에 그 내용이 다시 모델로 들어간다.

## 구현 위치

| 무엇 | 파일 |
|---|---|
| 설정 외부화 · Advisor 체인 | `config/HelpDeskProperties.java`, `config/AiConfig.java` |
| 문서 인제스트 · 선택 주입 | `rag/IngestService.java`, `web/AdminController.java` |
| 하이브리드 검색 (BM25 + 벡터) | `search/KeywordIndex.java`, `search/HybridDocumentRetriever.java` |
| RAG 답변 · 출처 · 근거 문장 | `chat/HelpDeskService.java`, `chat/EvidenceExtractor.java` |
| 주문·티켓 도구 | `tools/OrderTools.java`, `tools/TicketTools.java` |
| 대화 메모리 · 멀티턴 | `config/AiConfig.java#chatMemory`, `HelpDeskService#conversationId` |
| 구조화 응답 · SSE | `web/ChatController.java`, `chat/AnswerDto.java` |
| 사용자 상태 조회 | `web/MeController.java` |
| 감사 로깅 · 예외 처리 | `advisor/AuditAdvisor.java`, `web/ApiExceptionHandler.java` |
| 상담 화면 | `resources/static/index.html` (의존성 없는 단일 HTML) |
| 규정 문서 7종 | `resources/docs/*.md` — **앱이 읽는 런타임 리소스다** |

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

**임계값 0.35 는 측정해서 정했다.** 한국어 질의 + `text-embedding-3-small` 의
코사인 유사도는 0.2~0.5 대에 형성돼 관련 문서조차 0.5 를 넘기 어렵다.
0.62 로 두면 **검색이 전멸한다**(실제로 0건이었다).

**상태는 모델을 거치지 않고 읽는다.** 같은 값을 도구로도 볼 수 있지만 그건 모델이
부를 때만 오고, 요약된 값이 온다. 화면에 늘 떠 있어야 하는 값은 원본이어야 한다.

## 하이브리드 검색

임베딩은 **의미**가 가까운 것을 찾아서, 식별자("12345")와 어휘 중복이 없는 짧은 질의에 약하다.
실측에서 "배송 조회는 어디에서 하나요?" 가 유사도 0.30 으로 임계값 아래였다 — 검색 0건.

BM25 키워드 검색을 함께 돌리고 **Reciprocal Rank Fusion**(k=60)으로 순위를 합친다.
점수가 아니라 순위를 합치므로 코사인 유사도와 BM25 의 스케일 차이를 맞출 필요가 없다.

한국어는 형태소 분석기 없이 **문자 bi-gram** 으로 자르되, 만들기 전에 꼬리 조사를 떼고
어미·의문사 불용어를 뺀다. 둘 다 없으면 `"반품은"` 과 `"상품은"` 이 같은 `품은` 토큰을
만들어 엉뚱한 문서로 라우팅된다(실제로 그랬다).

같은 골든셋 10문항에서 벡터 단독 Recall@3 은 9/10, 하이브리드는 10/10 이었다.
설정 한 줄로 되돌려 비교할 수 있다.

```yaml
helpdesk:
  rag:
    hybrid: true    # false 로 두면 벡터 단독
```

## Spring AI 2.0 API 차이 — 교재 코드와 달라진 부분

교재 예제는 Spring AI 1.x 기준이라 그대로는 컴파일되지 않는다.

| 교재 | Spring AI 2.0 |
|---|---|
| `QuestionAnswerAdvisor.builder(vs).searchRequest(...)` | `RetrievalAugmentationAdvisor.builder().documentRetriever(...)` |
| `QuestionAnswerAdvisor.RETRIEVED_DOCUMENTS` | `RetrievalAugmentationAdvisor.DOCUMENT_CONTEXT` |
| `spring-ai-advisors-vector-store` | **존재하지 않음** → `spring-ai-rag` |
| `new TokenTextSplitter(...)` | `TokenTextSplitter.builder()` |

`SafeGuardAdvisor` · `MessageChatMemoryAdvisor` · `MessageWindowChatMemory` ·
`ChatMemory.CONVERSATION_ID` · `TikaDocumentReader` 는 교재와 동일하다.

기본 `ContextualQueryAugmenter` 프롬프트에는 *"Given the context information and
no prior knowledge…"* 가 들어 있어 **대화 이력을 억눌렀다.** 3턴에서 "그거"를 못 알아듣던
원인이 이것이라, `AiConfig` 에서 "사실은 문맥에서 / 대명사는 대화에서"로 나눠 교체했다.

## 범위 밖으로 둔 것

- **인증·인가** — 사용자 식별을 `Principal` 대신 `X-User-Id` 헤더로 받는다.
  헤더를 신뢰하는 구조이므로 **운영에 그대로 쓰면 안 된다**. Security 를 붙이면
  이 헤더를 `Principal#getName` 으로 바꾸기만 하면 된다.
- **토큰 계측·골든셋 자동 평가** — 골든셋 비교는 `search-compare` 로 수동 확인했다.

## 테스트

```bash
./gradlew test    # 15개 — API 키 · Docker 없이 동작
```

| 테스트 | 무엇을 지키나 |
|---|---|
| `HybridSearchTest` | BM25 토큰화·라우팅·RRF 융합 — 순수 자바 계산이라 네트워크가 필요 없다 |
| `EvidenceExtractorTest` | 근거 문장 선별 — 제목·표 머리글이 본문을 이기지 않는지 |
| `Lab3ContextTest` · `Lab3ApplicationTests` | `local` 프로필 컨텍스트 기동 |
