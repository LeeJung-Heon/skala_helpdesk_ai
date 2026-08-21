package com.example.lab3.search;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

/**
 * 하이브리드 검색 — 벡터와 키워드를 <b>순위</b>로 합친다.
 *
 * <p>Spring AI 의 {@link DocumentRetriever} 를 구현하므로
 * {@code RetrievalAugmentationAdvisor} 에 그대로 꽂힌다. RAG 파이프라인의 나머지는
 * 이 클래스가 무엇을 하는지 알 필요가 없다.
 *
 * <h2>왜 점수가 아니라 순위를 합치나 — RRF</h2>
 * 코사인 유사도(0~1)와 BM25 점수(상한 없음)는 <b>단위가 다르다</b>.
 * 정규화해서 더하면 문서 집합이 바뀔 때마다 가중치를 다시 맞춰야 한다.
 *
 * <p>Reciprocal Rank Fusion 은 점수를 버리고 <b>순위만</b> 쓴다.
 * <pre>score(d) = Σ 1 / (k + rank_i(d))</pre>
 * 각 검색기에서 몇 등이었는지만 보므로 점수 스케일에 영향받지 않는다.
 * {@code k=60} 은 원 논문(Cormack et al., 2009)의 값으로, 상위권 순위 차이를
 * 과하게 벌리지 않으면서 하위권을 충분히 눌러 준다.
 *
 * <h2>실측 근거</h2>
 * 벡터 단독에서 "배송 조회는 어디에서 하나요?" 가 유사도 0.291 로 임계값 아래였다.
 * 질문에 "마이페이지"라는 정답 단어가 없어 의미 거리가 벌어진 경우인데,
 * 키워드 검색은 "배송", "조회" bi-gram 으로 같은 문서를 상위에 올린다.
 */
public class HybridDocumentRetriever implements DocumentRetriever {

    private static final Logger log = LoggerFactory.getLogger(HybridDocumentRetriever.class);

    /** RRF 상수. 원 논문 권장값. */
    private static final int RRF_K = 60;

    private final VectorStore vectorStore;
    private final KeywordIndex keywordIndex;
    private final int topK;
    private final double similarityThreshold;

    /**
     * 각 검색기가 후보를 몇 개까지 내놓을지.
     *
     * <p>최종 topK 보다 넉넉해야 융합이 의미를 갖는다 — 한쪽에서 5등인 문서가
     * 다른 쪽에서 1등이면 합쳐서 올라와야 하는데, 후보를 topK 만큼만 뽑으면
     * 그 문서가 애초에 후보에 없다.
     */
    private final int candidateSize;

    public HybridDocumentRetriever(VectorStore vectorStore, KeywordIndex keywordIndex,
                                   int topK, double similarityThreshold) {
        this.vectorStore = vectorStore;
        this.keywordIndex = keywordIndex;
        this.topK = topK;
        this.similarityThreshold = similarityThreshold;
        this.candidateSize = Math.max(topK * 2, 10);
    }

    @Override
    public List<Document> retrieve(Query query) {
        List<Document> vector = vectorSearch(query.text());
        List<Document> keyword = keywordSearch(query.text());

        List<Document> fused = fuse(vector, keyword, topK);
        log.debug("[hybrid] '{}' → 벡터 {}건 · 키워드 {}건 → 융합 {}건",
                query.text(), vector.size(), keyword.size(), fused.size());
        return fused;
    }

    /**
     * 벡터 검색.
     *
     * <p><b>임계값을 여기서는 낮춰 잡는다.</b> 융합 단계에서 키워드 순위가 함께 반영되므로
     * 벡터가 애매하다고 미리 버릴 이유가 없다 — 최종 컷은 RRF 가 한다.
     */
    public List<Document> vectorSearch(String text) {
        return vectorStore.similaritySearch(SearchRequest.builder()
                .query(text)
                .topK(candidateSize)
                .similarityThreshold(Math.min(similarityThreshold, 0.2))
                .build());
    }

    public List<Document> keywordSearch(String text) {
        return keywordIndex.search(text, candidateSize).stream()
                .map(KeywordIndex.Hit::document)
                .toList();
    }

    /** 두 순위 목록을 RRF 로 합친다. */
    public static List<Document> fuse(List<Document> vector, List<Document> keyword, int topK) {
        Map<String, Document> byId = new LinkedHashMap<>();
        Map<String, Double> scores = new LinkedHashMap<>();

        accumulate(vector, byId, scores);
        accumulate(keyword, byId, scores);

        List<Map.Entry<String, Double>> ranked = new ArrayList<>(scores.entrySet());
        ranked.sort(Map.Entry.<String, Double>comparingByValue().reversed());

        List<Document> result = new ArrayList<>();
        for (Map.Entry<String, Double> entry : ranked) {
            if (result.size() >= topK) {
                break;
            }
            result.add(byId.get(entry.getKey()));
        }
        return result;
    }

    private static void accumulate(List<Document> ranked,
                                   Map<String, Document> byId, Map<String, Double> scores) {
        for (int rank = 0; rank < ranked.size(); rank++) {
            Document document = ranked.get(rank);
            String id = keyOf(document);
            byId.putIfAbsent(id, document);
            scores.merge(id, 1.0 / (RRF_K + rank + 1), Double::sum);
        }
    }

    /**
     * 문서 동일성 판별 키.
     *
     * <p>벡터 저장소와 키워드 색인이 같은 {@code Document} 인스턴스를 돌려준다는 보장이 없어
     * id 를 쓰되, 없으면 본문으로 대체한다.
     */
    private static String keyOf(Document document) {
        String id = document.getId();
        return (id == null || id.isBlank()) ? document.getText() : id;
    }

    /** 순위 비교용 — 진단 API 에서 쓴다. */
    public static List<String> sourcesOf(List<Document> documents) {
        return documents.stream()
                .map(d -> String.valueOf(d.getMetadata().getOrDefault("source", "unknown")))
                .toList();
    }

    static Comparator<Document> byNothing() {
        return (a, b) -> 0;
    }
}
