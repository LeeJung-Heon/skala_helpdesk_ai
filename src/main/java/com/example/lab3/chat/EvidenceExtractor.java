package com.example.lab3.chat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import com.example.lab3.search.KeywordIndex;

/**
 * 답변의 근거가 된 <b>문장</b>을 청크에서 골라낸다.
 *
 * <p>RAG 는 "이 문서를 참고했다"까지는 알려 주지만 "그 문서의 어느 문장이 근거인지"는
 * 알려 주지 않는다. 사용자 입장에서 확인하려면 문서 전체를 읽어야 한다.
 *
 * <h2>어떻게 고르나</h2>
 * 답변과 청크 문장의 <b>어휘 겹침</b>으로 점수를 매긴다.
 * 토큰화는 {@link KeywordIndex#tokenize} 를 그대로 쓴다 — 하이브리드 검색에서
 * 한국어 조사 문제를 풀려고 만든 문자 bi-gram 방식이 여기서도 필요하다.
 * ("7일 이내에 신청" ↔ "7일 이내에 반품 신청이 가능합니다" 가 겹쳐야 한다)
 *
 * <p>LLM 을 한 번 더 부르는 방법도 있지만, 호출당 비용과 지연이 두 배가 된다.
 * 근거 <b>표시</b>가 목적이라면 어휘 겹침으로 충분하다.
 */
final class EvidenceExtractor {

    /**
     * 문장 분리 — 마침표·물음표·줄바꿈 기준.
     *
     * <p>{@code (?<![0-9]\.)} 가 핵심이다. 이게 없으면 "## 1. 무상 보증 기간" 이
     * "## 1." 과 "무상 보증 기간" 으로 쪼개져, 제목 걸러내기를 <b>우회</b>한다.
     * 실측에서 이 조각이 답변과 100% 겹쳐 1등 근거로 뽑혔다.
     */
    private static final Pattern SENTENCE = Pattern.compile("(?<=[.!?])(?<![0-9]\\.)\\s+|\\n+");

    /** 근거로 표시할 최대 문장 수. 너무 많으면 강조가 의미를 잃는다. */
    private static final int MAX_HIGHLIGHTS = 3;

    /** 겹치는 토큰이 이보다 적으면 우연이다 — 근거로 보지 않는다. */
    private static final int MIN_HITS = 2;

    private EvidenceExtractor() {
    }

    /**
     * 청크에서 답변과 가장 많이 겹치는 문장을 고른다.
     *
     * @return 원문에 등장한 순서대로 정렬된 근거 문장. 겹치는 게 없으면 빈 목록.
     */
    static List<String> highlight(String chunk, String answer) {
        if (chunk == null || chunk.isBlank() || answer == null || answer.isBlank()) {
            return List.of();
        }

        Set<String> answerTokens = new HashSet<>(KeywordIndex.tokenize(answer));
        if (answerTokens.isEmpty()) {
            return List.of();
        }

        record Scored(int order, String sentence, double score) {}
        List<Scored> scored = new ArrayList<>();

        String[] sentences = SENTENCE.split(chunk);
        for (int i = 0; i < sentences.length; i++) {
            String sentence = sentences[i].strip();
            // 제목·표 구분선은 근거가 될 수 없다.
            // 제목은 본문의 요약이라 답변과 100% 겹치기 쉬운데, 정작 <b>수치는 본문에</b> 있다.
            if (sentence.length() < 8
                    || sentence.startsWith("#")
                    || sentence.matches("^[#\\-|\\s]+$")) {
                continue;
            }
            List<String> tokens = KeywordIndex.tokenize(sentence);
            if (tokens.isEmpty()) {
                continue;
            }
            long hit = tokens.stream().filter(answerTokens::contains).count();
            if (hit < MIN_HITS) {
                continue;
            }
            // 겹친 비율(hit/전체)로 재면 <b>짧은 줄이 항상 이긴다</b>.
            // 실측: "이어폰 보증 기간" 질문에서 표 머리글 "| 상품군 | 무상 보증 기간 |" 이
            // 100% 겹쳐 1등이 됐고, 정작 답이 적힌 "전자제품 … 구매일로부터 1년" 행은
            // 다른 상품군 어휘에 희석돼 밀렸다. 제곱근으로만 길이를 눌러
            // "많이 겹치는 긴 문장"이 이기게 한다.
            scored.add(new Scored(i, sentence, hit / Math.sqrt(tokens.size())));
        }

        return scored.stream()
                .sorted(Comparator.comparingDouble(Scored::score).reversed())
                .limit(MAX_HIGHLIGHTS)
                .sorted(Comparator.comparingInt(Scored::order))   // 원문 순서로 되돌린다
                .map(Scored::sentence)
                .toList();
    }
}
