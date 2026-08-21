package com.example.lab3.web;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.lab3.rag.IngestService;
import com.example.lab3.search.HybridDocumentRetriever;
import com.example.lab3.search.KeywordIndex;
import com.example.lab3.config.HelpDeskProperties;
import com.example.lab3.rag.IngestService.IngestResult;

/**
 * Phase 2 — 인제스트와 <b>결과 확인</b>.
 *
 * <p>인제스트는 성공 메시지가 아니라 결과물로 확인한다.
 * 무엇이 들어갔는지 눈으로 볼 창구가 없으면, Phase 3 에서 답이 이상할 때
 * 검색이 문제인지 생성이 문제인지 가릴 수 없다.
 *
 * <p>운영에서는 관리자 권한을 걸어야 한다(Phase 7 범위).
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final IngestService ingestService;
    private final VectorStore vectorStore;
    private final KeywordIndex keywordIndex;
    private final HelpDeskProperties props;

    public AdminController(IngestService ingestService, VectorStore vectorStore,
                           KeywordIndex keywordIndex, HelpDeskProperties props) {
        this.ingestService = ingestService;
        this.vectorStore = vectorStore;
        this.keywordIndex = keywordIndex;
        this.props = props;
    }

    /**
     * 검색 방식 비교 — <b>벡터 단독 / 키워드 단독 / 하이브리드</b>의 순위를 나란히 본다.
     *
     * <p>하이브리드가 좋아졌다고 주장하려면 <b>무엇이 어떻게 달라졌는지</b>를
     * 볼 수 있어야 한다. 임계값을 정할 때 점수 분포를 봐야 했던 것과 같은 이유다.
     */
    @GetMapping("/search-compare")
    public Map<String, Object> compare(@RequestParam String q,
                                       @RequestParam(defaultValue = "5") int topK) {
        var hybrid = new HybridDocumentRetriever(vectorStore, keywordIndex, topK, props.rag().threshold());

        List<Document> vectorOnly = hybrid.vectorSearch(q);
        List<Document> keywordOnly = hybrid.keywordSearch(q);
        List<Document> fused = HybridDocumentRetriever.fuse(vectorOnly, keywordOnly, topK);

        // 임계값을 적용한 '실제 벡터 단독' 결과 — 이게 하이브리드 이전의 동작이다
        List<Document> vectorThresholded = vectorStore.similaritySearch(
                SearchRequest.builder().query(q).topK(topK)
                        .similarityThreshold(props.rag().threshold()).build());

        return Map.of(
                "query", q,
                "threshold", props.rag().threshold(),
                "vectorOnly(임계값 적용)", HybridDocumentRetriever.sourcesOf(vectorThresholded),
                "vectorOnly(임계값 없이)", withScores(vectorOnly),
                "keywordOnly", keywordIndex.search(q, topK).stream()
                        .map(h -> "%s (BM25 %.3f)".formatted(
                                h.document().getMetadata().get("source"), h.score())).toList(),
                "hybrid(RRF)", HybridDocumentRetriever.sourcesOf(fused),
                "keywordIndexSize", keywordIndex.size(),
                "queryTokens", KeywordIndex.tokenize(q));
    }

    private List<String> withScores(List<Document> documents) {
        return documents.stream()
                .map(d -> "%s (%.3f)".formatted(d.getMetadata().get("source"),
                        d.getScore() == null ? -1.0 : d.getScore()))
                .toList();
    }

    /**
     * 고를 수 있는 문서 목록 — 파일명·크기·현재 색인 청크 수.
     *
     * <p>화면에서 무엇을 넣을지 고르려면 먼저 무엇이 있는지 보여야 한다.
     */
    @GetMapping("/docs")
    public List<IngestService.DocumentInfo> docs() {
        return ingestService.available();
    }

    /**
     * 사내 문서를 인제스트한다. 같은 문서를 다시 넣어도 중복되지 않는다.
     *
     * @param sources   넣을 파일명 목록. <b>주면 그 문서만</b> 색인에 남고 나머지는 빠진다.
     * @param selective 선택 주입임을 명시한다. <b>하나도 안 고른 경우와 파라미터를 안 준 경우를
     *                  구분하기 위해</b> 필요하다 — 빈 목록은 쿼리스트링에서 사라지므로,
     *                  이 플래그가 없으면 "전부 해제"가 "전부 주입"으로 뒤집힌다(실제로 겪었다).
     */
    @PostMapping("/ingest")
    public List<IngestResult> ingest(
            @RequestParam(required = false) List<String> sources,
            @RequestParam(defaultValue = "false") boolean selective,
            @RequestParam(defaultValue = "policy") String docType,
            @RequestParam(defaultValue = "CS") String dept) {

        if (sources == null && !selective) {
            return ingestService.ingestAll(docType, dept);
        }
        Set<String> picked = sources == null ? Set.of() : new LinkedHashSet<>(sources);
        return ingestService.ingestSelected(picked, docType, dept);
    }

    /** 무엇이 들어갔는지 눈으로 본다 — 임계값을 정하는 근거가 된다. */
    @GetMapping("/chunks")
    public List<Map<String, Object>> inspect(
            @RequestParam String q,
            @RequestParam(defaultValue = "5") int topK) {

        List<Document> hits = vectorStore.similaritySearch(
                SearchRequest.builder().query(q).topK(topK).build());

        return hits.stream().map(d -> Map.<String, Object>of(
                "source", String.valueOf(d.getMetadata().get("source")),
                "version", String.valueOf(d.getMetadata().get("version")),
                "score", d.getScore() == null ? -1.0 : d.getScore(),
                "preview", d.getText().substring(0, Math.min(160, d.getText().length()))
        )).toList();
    }
}
