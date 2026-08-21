package com.example.lab3;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.ai.document.Document;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import com.example.lab3.search.HybridDocumentRetriever;
import com.example.lab3.search.KeywordIndex;

/**
 * 하이브리드 검색의 <b>키워드 축</b>을 검증한다.
 *
 * <p>BM25 는 순수 자바 계산이라 <b>API 키도 네트워크도 필요 없다</b>.
 * 벡터 검색이 놓친 질의를 키워드가 잡아내는지를 실제 사내 문서로 확인한다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HybridSearchTest {

    private final KeywordIndex index = new KeywordIndex();
    private List<Document> documents;

    @BeforeAll
    void buildIndex() throws IOException {
        var resolver = new PathMatchingResourcePatternResolver();
        Resource[] resources = resolver.getResources("classpath*:docs/*.md");

        documents = new ArrayList<>();
        for (Resource resource : resources) {
            try (InputStream in = resource.getInputStream()) {
                Map<String, Object> metadata = new HashMap<>();
                metadata.put("source", resource.getFilename());
                documents.add(new Document(
                        new String(in.readAllBytes(), StandardCharsets.UTF_8), metadata));
            }
        }
        // 실제 IngestService 는 문서 전체가 아니라 <b>청크</b>를 색인한다.
        // 문서 전체로 색인하면 길이 정규화(B=0.75)가 긴 문서를 불리하게 만들어
        // 운영과 다른 결과가 나온다 — 테스트를 실제 동작에 맞춘다.
        var splitter = org.springframework.ai.transformer.splitter.TokenTextSplitter.builder()
                .withChunkSize(800).withMinChunkSizeChars(350).build();
        List<Document> chunks = splitter.apply(documents);
        index.replaceAll(chunks);
        assertThat(chunks).as("청크가 문서 수보다 많아야 한다").hasSizeGreaterThanOrEqualTo(documents.size());
        assertThat(documents.stream()
                .map(d -> String.valueOf(d.getMetadata().get("source"))).toList())
                .as("사내 규정 문서가 모두 색인돼야 한다")
                .contains("return-policy.md", "shipping-policy.md", "membership.md",
                        "payment-policy.md", "warranty-policy.md",
                        "privacy-policy.md", "support-guide.md");
    }

    private String topSource(String query) {
        var hits = index.search(query, 3);
        return hits.isEmpty() ? "(없음)"
                : String.valueOf(hits.getFirst().document().getMetadata().get("source"));
    }

    /**
     * 상위 N개 안에 정답 문서가 있는지.
     *
     * <p>주제가 여러 문서에 걸치는 질문(예: 주문 취소 — 결제 규정과 고객센터 안내에
     * 모두 등장)은 1등만 보는 것이 실제보다 엄격하다. RAG 는 topK 개를 문맥으로 넘기므로
     * <b>후보에 들었는지</b>가 실질적인 기준이다.
     */
    private List<String> topSources(String query, int n) {
        return index.search(query, n).stream()
                .map(h -> String.valueOf(h.document().getMetadata().get("source")))
                .toList();
    }

    @Test
    @DisplayName("벡터가 놓쳤던 질의 — 어휘 중복이 없어도 키워드가 잡는다")
    void 어휘_중복이_없는_질의() {
        // 실측: 벡터 단독에서 이 질문의 유사도는 0.291 로 임계값(0.35) 아래였다.
        // 질문에 정답 단어("마이페이지")가 없어 의미 거리가 벌어진 경우다.
        assertThat(topSources("배송 조회는 어디에서 할 수 있나요?", 3))
                .as("벡터는 0.291 로 임계값 아래였다 — 키워드는 후보에 올려야 한다")
                .contains("shipping-policy.md");
    }

    @Test
    @DisplayName("조사가 붙어도 매칭된다 — 문자 bi-gram 이 형태소 분석기를 대신한다")
    void 조사_변형() {
        // "반품은 / 반품이 / 반품을" 이 모두 '반품' bi-gram 을 공유한다
        for (String q : List.of("반품은 며칠까지", "반품이 가능한 기간", "반품을 하고 싶어요")) {
            assertThat(topSource(q)).as("질의: %s", q).isEqualTo("return-policy.md");
        }
    }

    @Test
    @DisplayName("등급·적립률 같은 고유 어휘를 정확히 가른다")
    void 문서_구분() {
        assertThat(topSource("골드 등급 포인트 적립률")).isEqualTo("membership.md");
        assertThat(topSource("제주도 도서산간 추가 배송비")).isEqualTo("shipping-policy.md");
    }

    @Test
    @DisplayName("확충한 주제도 정확한 문서로 라우팅된다 — 질문 빈틈 확인")
    void 신규_주제_커버리지() {
        // 문서를 늘리면 후보가 많아져 오히려 틀린 문서를 고르기 쉬워진다.
        // 주제별로 제대로 갈리는지 확인해야 확충이 의미를 갖는다.
        assertThat(topSource("무이자 할부 되나요?")).isEqualTo("payment-policy.md");
        assertThat(topSource("세금계산서 발행해 주세요")).isEqualTo("payment-policy.md");
        assertThat(topSource("이어폰 무상 보증 기간이 얼마나 되나요?")).isEqualTo("warranty-policy.md");
        assertThat(topSource("A/S 수리 기간은 얼마나 걸리나요?")).isEqualTo("warranty-policy.md");
        assertThat(topSource("회원 탈퇴하면 포인트는 어떻게 되나요?")).isEqualTo("privacy-policy.md");
        assertThat(topSource("휴면 계정은 언제 전환되나요?")).isEqualTo("privacy-policy.md");
        assertThat(topSource("고객센터 전화 운영 시간 알려줘")).isEqualTo("support-guide.md");
    }

    @Test
    @DisplayName("문서가 늘어도 기존 주제가 밀리지 않는다")
    void 기존_주제_유지() {
        // 주문 취소는 결제 규정과 고객센터 안내에 모두 등장한다 — 후보에 들면 충분하다
        assertThat(topSources("주문 취소는 언제까지 가능한가요?", 3)).contains("payment-policy.md");
        assertThat(topSource("묶음 배송은 어떻게 되나요?")).isEqualTo("shipping-policy.md");
        assertThat(topSource("부분 반품이 가능한가요?")).isEqualTo("return-policy.md");
    }

    @Test
    @DisplayName("영숫자 식별자는 단어 통째로 색인된다 — 임베딩이 가장 약한 지점")
    void 식별자_토큰화() {
        assertThat(KeywordIndex.tokenize("주문 12345 반품은"))
                .contains("12345")            // 숫자는 쪼개지 않는다
                .contains("반품")             // 한글은 조사를 뗀 뒤 bi-gram
                .doesNotContain("품은");      // 조사 경계를 넘는 가짜 토큰은 만들지 않는다
    }

    @Test
    @DisplayName("RRF — 한쪽에서만 1등인 문서가 합쳐서 상위로 올라온다")
    void 순위_융합() {
        Document a = doc("a"), b = doc("b"), c = doc("c");

        // 벡터: b, a, c   /   키워드: a, c, b   →  a 가 양쪽 상위라 1등이어야 한다
        List<Document> fused = HybridDocumentRetriever.fuse(
                List.of(b, a, c), List.of(a, c, b), 3);

        assertThat(fused.getFirst().getMetadata().get("source")).isEqualTo("a");
        assertThat(fused).hasSize(3);
    }

    @Test
    @DisplayName("한쪽에만 있는 문서도 결과에 포함된다 — 누락 없이 합친다")
    void 한쪽에만_있는_문서() {
        Document a = doc("a"), b = doc("b");

        List<Document> fused = HybridDocumentRetriever.fuse(List.of(a), List.of(b), 5);

        assertThat(HybridDocumentRetriever.sourcesOf(fused)).containsExactlyInAnyOrder("a", "b");
    }

    private Document doc(String source) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("source", source);
        return new Document(source, "본문 " + source, metadata);
    }
}
