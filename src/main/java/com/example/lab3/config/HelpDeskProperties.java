package com.example.lab3.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Phase 1 — 설정 외부화.
 *
 * <p>공급자·모델·임계값을 <b>코드에 상수로 남기지 않는다</b>.
 * 검색 파라미터를 바꿔 실험할 때 재컴파일이 필요 없어야 하고,
 * 환경(dev/stage/prod)마다 다른 값을 써야 하기 때문이다.
 */
@ConfigurationProperties(prefix = "helpdesk")
public record HelpDeskProperties(
        @DefaultValue Rag rag,
        @DefaultValue Memory memory,
        @DefaultValue Safety safety) {

    /** 검색(RAG) 파라미터 */
    public record Rag(
            @DefaultValue("5") int topK,
            @DefaultValue("0.62") double threshold,
            @DefaultValue("800") int chunkSize,
            @DefaultValue("350") int minChunkSizeChars,
            /** 하이브리드 검색(벡터+BM25) 사용 여부. 끄면 벡터 단독으로 돌아간다 — 비교 실험용. */
            @DefaultValue("true") boolean hybrid) {}

    /** 대화 메모리 파라미터 */
    public record Memory(
            @DefaultValue("20") int maxMessages) {}

    /** 안전 장치 */
    public record Safety(
            @DefaultValue({"주민등록번호", "카드번호", "계좌번호"}) java.util.List<String> sensitiveWords,
            @DefaultValue("민감정보가 포함된 요청은 처리할 수 없습니다.") String failureResponse) {}
}
