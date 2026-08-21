package com.example.lab3.chat;

import java.util.List;

/**
 * 구조화 응답.
 *
 * <p>답변만 던지지 않는다. 화면이 <b>근거를 확인시켜야</b> 하므로
 * 답변·출처·도구 사용 여부가 구분되어 와야 한다.
 *
 * @param answer   모델이 생성한 답변 본문
 * @param sources  근거로 쓰인 문서 목록(중복 제거)
 * @param toolUsed 이번 응답에 도구가 쓰였는지
 */
public record AnswerDto(String answer, List<Source> sources, boolean toolUsed) {

    /**
     * 출처 한 건 — <b>파일명만으로는 근거를 확인할 수 없다</b>.
     *
     * <p>"출처: return-policy.md" 라고만 하면 사용자는 그 문서 어디에 그런 말이 있는지
     * 알 수 없다. 그래서 검색된 청크 원문({@code text})과, 답변과 실제로 겹치는
     * 문장({@code highlights})을 함께 보낸다. 화면은 이걸로 근거를 펼쳐 보여 준다.
     *
     * @param document   문서 파일명
     * @param version    인제스트 시점의 버전
     * @param text       검색된 청크 원문
     * @param highlights 답변과 어휘가 겹치는 문장 — 화면에서 강조된다
     */
    public record Source(String document, String version, String text, List<String> highlights) {}
}
