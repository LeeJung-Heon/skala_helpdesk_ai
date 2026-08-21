package com.example.lab3.rag;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

import com.example.lab3.config.HelpDeskProperties;
import com.example.lab3.search.KeywordIndex;

/**
 * Phase 2 — 문서 인제스트 파이프라인.
 *
 * <p>사내 규정 문서를 읽어 청크로 나누고 <b>메타데이터를 붙여</b> 저장한다.
 * 메타데이터가 없으면 답변에 출처를 표기할 수 없다 — RAG 의 존재 이유가 사라진다.
 *
 * <h2>재색인이 먼저다</h2>
 * 같은 문서를 다시 넣으면서 {@code add} 만 반복하면 같은 청크가 쌓인다.
 * 검색 결과가 동일한 문장으로 도배되어 근거가 다양해지지 않는다.
 * 그래서 <b>문서 단위로 지우고 다시 넣는다</b>.
 */
@Service
public class IngestService {

    private static final Logger log = LoggerFactory.getLogger(IngestService.class);

    private final VectorStore vectorStore;
    private final HelpDeskProperties props;
    private final KeywordIndex keywordIndex;

    /** 키워드 색인은 전체 교체 방식이라, 지금까지 넣은 청크를 들고 있어야 한다. */
    private final List<Document> indexed = new ArrayList<>();

    public IngestService(VectorStore vectorStore, HelpDeskProperties props,
                         KeywordIndex keywordIndex) {
        this.vectorStore = vectorStore;
        this.props = props;
        this.keywordIndex = keywordIndex;
    }

    /** 인제스트 결과 — 어떤 문서가 몇 청크로 들어갔는지 */
    public record IngestResult(String source, int chunks) {}

    /**
     * 고를 수 있는 문서 한 건 — 파일 크기와 <b>지금 색인에 몇 청크가 있는지</b>.
     *
     * <p>{@code chunks == 0} 이면 아직 안 넣은 문서다. 화면에서 체크 상태를 이 값으로 정한다.
     */
    public record DocumentInfo(String source, long bytes, int chunks) {}

    /**
     * {@code classpath:docs/} 아래에서 고를 수 있는 문서 목록.
     *
     * <p>화면에서 <b>무엇을 넣을지 고르게</b> 하려면 먼저 무엇이 있는지 보여 줘야 한다.
     */
    public List<DocumentInfo> available() {
        Map<String, Integer> chunkCount = chunkCountBySource();
        return resources().stream()
                .map(r -> new DocumentInfo(
                        r.getFilename(), sizeOf(r),
                        chunkCount.getOrDefault(r.getFilename(), 0)))
                .sorted(Comparator.comparing(DocumentInfo::source))
                .toList();
    }

    /**
     * {@code classpath:docs/} 아래 문서를 모두 인제스트한다.
     */
    public List<IngestResult> ingestAll(String docType, String dept) {
        List<IngestResult> results = new ArrayList<>();
        for (Resource resource : resources()) {
            results.add(ingest(resource, docType, dept));
        }
        return results;
    }

    /**
     * <b>고른 문서만</b> 색인에 남긴다.
     *
     * <p>고르지 않은 문서는 색인에서 <b>뺀다</b>. 단순히 추가만 하면
     * "이 문서를 빼면 답이 어떻게 달라지나"를 확인할 수 없고, 한 번 넣은 문서를
     * 되돌리려면 서버를 재시작해야 한다. 화면의 체크 상태가 곧 색인의 상태가 되게 한다.
     *
     * @param selected 색인에 남길 파일명 집합
     * @return 선택된 문서의 인제스트 결과 (이미 들어가 있던 문서는 다시 넣지 않는다)
     */
    public List<IngestResult> ingestSelected(Set<String> selected, String docType, String dept) {
        Map<String, Integer> before = chunkCountBySource();

        // ① 선택에서 빠진 문서를 색인에서 제거한다
        for (String source : before.keySet()) {
            if (!selected.contains(source)) {
                remove(source);
            }
        }

        // ② 선택됐지만 아직 안 들어간 문서를 넣는다.
        //    이미 같은 내용이 들어가 있으면 다시 임베딩할 이유가 없다 — 호출 비용이 든다.
        List<IngestResult> results = new ArrayList<>();
        for (Resource resource : resources()) {
            String source = resource.getFilename();
            if (!selected.contains(source)) {
                continue;
            }
            if (before.containsKey(source)) {
                results.add(new IngestResult(source, before.get(source)));
                continue;
            }
            results.add(ingest(resource, docType, dept));
        }

        log.info("[ingest] 선택 주입 — 선택 {}건 · 색인 문서 {}건", selected.size(), chunkCountBySource().size());
        return results;
    }

    /** 색인에서 문서 하나를 뺀다 — 벡터 저장소와 키워드 색인 양쪽에서. */
    public void remove(String source) {
        deleteBySource(source);
        synchronized (indexed) {
            indexed.removeIf(d -> source.equals(d.getMetadata().get("source")));
            keywordIndex.replaceAll(List.copyOf(indexed));
        }
        log.info("[ingest] {} → 색인에서 제거", source);
    }

    /** 지금 색인에 들어 있는 문서별 청크 수. */
    public Map<String, Integer> chunkCountBySource() {
        synchronized (indexed) {
            Map<String, Integer> counts = new LinkedHashMap<>();
            for (Document d : indexed) {
                counts.merge(String.valueOf(d.getMetadata().get("source")), 1, Integer::sum);
            }
            return counts;
        }
    }

    private List<Resource> resources() {
        try {
            var resolver = new PathMatchingResourcePatternResolver();
            // classpath* — 폴더가 없어도 예외 대신 빈 결과를 준다
            return List.of(resolver.getResources("classpath*:docs/*"));
        } catch (IOException e) {
            throw new IllegalStateException("문서 목록을 읽지 못했습니다: " + e.getMessage(), e);
        }
    }

    private long sizeOf(Resource resource) {
        try {
            return resource.contentLength();
        } catch (IOException e) {
            return -1;
        }
    }

    /**
     * 문서 하나를 인제스트한다.
     *
     * @param docType 문서 종류(예: policy) — 필터 검색에 쓸 수 있게 메타데이터로 남긴다
     * @param dept    담당 부서
     */
    public IngestResult ingest(Resource file, String docType, String dept) {
        String source = file.getFilename();

        // ① 재색인 대비 — 같은 source 의 기존 청크를 먼저 지운다
        deleteBySource(source);

        // ② 읽기 — Tika 가 md·pdf·docx 를 모두 처리한다
        List<Document> raw = new TikaDocumentReader(file).get();

        var chunks = TokenTextSplitter.builder()
                .withChunkSize(props.rag().chunkSize())
                .withMinChunkSizeChars(props.rag().minChunkSizeChars())
                .build()
                .apply(raw);

        // ③ 메타데이터 — source·docType·dept·version 은 출처 표기의 재료다
        String version = LocalDate.now().toString();
        List<Document> enriched = chunks.stream().map(c -> {
            Map<String, Object> metadata = new HashMap<>(c.getMetadata());
            metadata.put("source", source);
            metadata.put("docType", docType);
            metadata.put("dept", dept);
            metadata.put("version", version);
            return new Document(c.getText(), metadata);
        }).toList();

        // ④ 임베딩 + 저장
        vectorStore.add(enriched);

        // 키워드 색인도 같은 청크로 갱신한다. 두 색인이 어긋나면 융합 결과를 믿을 수 없다.
        synchronized (indexed) {
            indexed.removeIf(d -> source.equals(d.getMetadata().get("source")));
            indexed.addAll(enriched);
            keywordIndex.replaceAll(List.copyOf(indexed));
        }

        log.info("[ingest] {} → 청크 {}개 (docType={} dept={} version={})",
                source, enriched.size(), docType, dept, version);
        return new IngestResult(source, enriched.size());
    }

    /**
     * 같은 출처의 청크를 지운다.
     *
     * <p>필터식 삭제를 지원하지 않는 저장소(예: SimpleVectorStore)도 있으므로
     * 실패를 치명적으로 다루지 않는다 — 최초 인제스트라면 지울 것도 없다.
     */
    private void deleteBySource(String source) {
        try {
            vectorStore.delete("source == '" + source + "'");
        } catch (RuntimeException e) {
            log.debug("[ingest] 기존 청크 삭제 생략 ({}): {}", source, e.getMessage());
        }
    }
}
