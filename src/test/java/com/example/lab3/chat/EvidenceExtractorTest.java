package com.example.lab3.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 근거 하이라이트 — 출처 문서의 <b>어느 문장</b>이 답변의 근거인지 고른다.
 *
 * <p>API 키 없이 도는 순수 계산 테스트다.
 */
class EvidenceExtractorTest {

    private static final String CHUNK = """
            # A/S 및 품질보증 규정

            ## 1. 무상 보증 기간

            | 상품군 | 무상 보증 기간 |
            |---|---|
            | 전자제품 (이어폰·키보드·스탠드 등) | 구매일로부터 **1년** |
            | 액세서리·케이블 | **6개월** |

            보증 기간은 **구매일 기준**이며, 주문내역에서 확인할 수 있습니다.
            """;

    @Test
    @DisplayName("수치가 적힌 본문 행을 고른다 — 제목·표 머리글에 밀리지 않는다")
    void 본문_우선() {
        List<String> highlights =
                EvidenceExtractor.highlight(CHUNK, "이어폰의 무상 보증 기간은 구매일로부터 1년입니다.");

        assertThat(highlights)
                .as("답이 적힌 행이 근거여야 한다")
                .anyMatch(s -> s.contains("이어폰") && s.contains("1년"));
        assertThat(highlights)
                .as("제목은 본문 요약이라 100% 겹치기 쉽다 — 근거로 쓰면 안 된다")
                .noneMatch(s -> s.startsWith("#"))
                .as("'## 1. 제목' 이 번호 뒤 마침표에서 쪼개져 제목 필터를 우회하면 안 된다")
                .doesNotContain("무상 보증 기간");
    }

    @Test
    @DisplayName("근거 문장은 원문에 그대로 있어야 한다 — 화면에서 강조하려면 문자열이 일치해야 한다")
    void 원문_보존() {
        for (String s : EvidenceExtractor.highlight(CHUNK, "무상 보증 기간은 구매일 기준입니다.")) {
            assertThat(CHUNK).contains(s);
        }
    }

    @Test
    @DisplayName("겹치는 어휘가 없으면 아무것도 강조하지 않는다 — 억지 근거를 만들지 않는다")
    void 근거_없음() {
        assertThat(EvidenceExtractor.highlight(CHUNK, "오늘 서울 날씨는 맑습니다.")).isEmpty();
        assertThat(EvidenceExtractor.highlight(CHUNK, "")).isEmpty();
        assertThat(EvidenceExtractor.highlight(null, "무상 보증")).isEmpty();
    }

    @Test
    @DisplayName("최대 3문장까지만 강조한다 — 다 칠하면 강조가 아니다")
    void 개수_제한() {
        assertThat(EvidenceExtractor.highlight(CHUNK, "무상 보증 기간 구매일 이어폰 케이블 개월 주문내역"))
                .hasSizeLessThanOrEqualTo(3);
    }
}
