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
            /**
             * 유사도 임계값. <b>0.62 를 기본값으로 두면 안 된다</b> — 실측에서 한국어 질의 +
             * text-embedding-3-small 의 코사인 유사도는 0.2~0.5 대에 형성돼, 관련 문서조차
             * 0.5 를 넘기 어렵다. 0.62 로 두면 검색이 전멸한다(실제로 0건이었다).
             * application.yaml 이 같은 값을 다시 지정하지만, 설정을 안 준 환경에서도
             * 동작해야 하므로 코드 기본값도 실측치에 맞춘다.
             */
            @DefaultValue("0.35") double threshold,
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
