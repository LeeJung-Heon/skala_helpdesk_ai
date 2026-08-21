package com.example.lab3.search;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

/**
 * BM25 키워드 검색 — 벡터 검색이 못 잡는 것을 잡는다.
 *
 * <h2>왜 필요한가</h2>
 * 임베딩은 <b>의미</b>가 비슷한 것을 찾는다. 그래서 두 경우에 약하다.
 * <ol>
 *   <li><b>고유명사·식별자</b> — "12345" 같은 주문번호는 의미 공간에서 위치가 없다시피 하다.</li>
 *   <li><b>어휘 중복이 없는 짧은 질의</b> — 실측에서 "배송 조회는 어디에서 하나요?" 가
 *       유사도 0.291 로 유일하게 임계값 아래였다. 문서에는 "마이페이지"라는 답이 있는데
 *       질문에 그 단어가 없어 의미 거리가 벌어졌다.</li>
 * </ol>
 * 키워드 검색은 정확히 이 두 경우에 강하다. 그래서 섞는다.
 *
 * <h2>한국어 토큰화 — 형태소 분석기 없이</h2>
 * 공백 단위로 자르면 조사 때문에 "반품은"과 "반품이"가 다른 토큰이 된다.
 * 형태소 분석기(은전한닢 등)를 붙이면 정확해지지만 사전·의존성이 무거워진다.
 *
 * <p>그래서 <b>한글은 문자 bi-gram</b>으로 자른다.
 *
 * <p>다만 bi-gram 만으로는 <b>조사 경계를 넘는 가짜 토큰</b>이 생긴다.
 * "반품은" 은 {반품, 품은} 을 만드는데 "상품은" 도 {상품, <b>품은</b>} 을 만든다.
 * 실측에서 "반품은 며칠까지" 가 배송 규정으로 잘못 라우팅됐다 — '반품' 은 여러 문서에
 * 흔해 IDF 가 낮은데, 가짜 토큰 '품은' 은 두 문서에만 있어 IDF 가 높았던 탓이다.
 * 그래서 bi-gram 을 만들기 <b>전에</b> 꼬리 조사를 떼어 낸다. "반품은" → 반품.
 * 조사가 붙어도 어간 bi-gram 이 겹쳐 매칭된다. 영숫자는 단어 통째로 남겨
 * "12345" 같은 식별자가 정확히 일치하게 한다.
 */
@Component
public class KeywordIndex {

    private static final Logger log = LoggerFactory.getLogger(KeywordIndex.class);

    /** BM25 용어 빈도 포화 계수. 같은 단어가 반복돼도 점수가 무한정 오르지 않게 한다. */
    private static final double K1 = 1.2;

    /** BM25 문서 길이 정규화 계수. 긴 문서가 단지 길다는 이유로 유리해지지 않게 한다. */
    private static final double B = 0.75;

    private static final Pattern HANGUL = Pattern.compile("[가-힣]+");
    private static final Pattern ALNUM = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

    /** 색인된 문서 하나 */
    private record Entry(Document document, Map<String, Integer> termFrequency, int length) {}

    /** 검색 결과 한 건 */
    public record Hit(Document document, double score) {}

    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    private List<Entry> entries = List.of();
    private Map<String, Integer> documentFrequency = Map.of();
    private double averageLength = 0;

    /**
     * 색인을 통째로 교체한다.
     *
     * <p>증분 갱신을 지원하지 않는 것은 의도적이다 — 인제스트가 문서 단위 재색인이므로
     * 부분 갱신을 만들면 벡터 저장소와 키워드 색인의 내용이 어긋날 수 있다.
     */
    public void replaceAll(List<Document> documents) {
        List<Entry> built = new ArrayList<>(documents.size());
        Map<String, Integer> df = new HashMap<>();

        for (Document document : documents) {
            List<String> tokens = tokenize(document.getText());
            Map<String, Integer> tf = new HashMap<>();
            for (String token : tokens) {
                tf.merge(token, 1, Integer::sum);
            }
            tf.keySet().forEach(term -> df.merge(term, 1, Integer::sum));
            built.add(new Entry(document, tf, tokens.size()));
        }

        double avg = built.isEmpty() ? 0
                : built.stream().mapToInt(Entry::length).average().orElse(0);

        lock.writeLock().lock();
        try {
            this.entries = List.copyOf(built);
            this.documentFrequency = Map.copyOf(df);
            this.averageLength = avg;
        } finally {
            lock.writeLock().unlock();
        }
        log.info("[keyword] 색인 교체 — 문서 {}개 · 고유 토큰 {}개 · 평균 길이 {}",
                built.size(), df.size(), Math.round(avg));
    }

    /** 상위 topK 를 BM25 점수 순으로 반환한다. */
    public List<Hit> search(String query, int topK) {
        lock.readLock().lock();
        try {
            if (entries.isEmpty()) {
                return List.of();
            }
            List<String> queryTokens = tokenize(query);
            int total = entries.size();

            List<Hit> hits = new ArrayList<>();
            for (Entry entry : entries) {
                double score = 0;
                for (String term : queryTokens) {
                    Integer tf = entry.termFrequency().get(term);
                    if (tf == null) {
                        continue;
                    }
                    int df = documentFrequency.getOrDefault(term, 0);
                    // BM25 의 IDF — 흔한 단어일수록 가중치가 낮다
                    double idf = Math.log(1 + (total - df + 0.5) / (df + 0.5));
                    double norm = tf + K1 * (1 - B + B * entry.length() / Math.max(1, averageLength));
                    score += idf * (tf * (K1 + 1)) / norm;
                }
                if (score > 0) {
                    hits.add(new Hit(entry.document(), score));
                }
            }
            hits.sort(Comparator.comparingDouble(Hit::score).reversed());
            return hits.size() > topK ? List.copyOf(hits.subList(0, topK)) : List.copyOf(hits);
        } finally {
            lock.readLock().unlock();
        }
    }

    public int size() {
        lock.readLock().lock();
        try {
            return entries.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 꼬리에서 떼어 낼 조사 — <b>긴 것부터</b> 본다.
     *
     * <p>"에서"를 "서"로 먼저 자르면 어간이 망가지므로 순서가 규칙의 일부다.
     * 형태소 분석기 없이 쓰는 근사이므로, 어간이 한 글자만 남는 경우엔 떼지 않는다
     * (예: "종이" 의 '이' 를 조사로 오인하지 않게).
     */
    private static final String[] JOSA = {
            "에서는", "에서도", "으로는", "이라는", "에서", "에게", "한테", "으로", "까지", "부터",
            "이나", "라도", "처럼", "보다", "마다", "조차", "밖에", "께서", "이라", "든지", "이며",
            "은", "는", "이", "가", "을", "를", "에", "의", "로", "도", "과", "와", "만", "랑"
    };

    /**
     * 내용이 없는 <b>기능어 bi-gram</b> — 질문 말투에서 나오는 어미·의문사다.
     *
     * <p>없애지 않으면 사고가 난다. 실측: "반품을 하고 싶어요" 가 개인정보 규정으로 갔다.
     * '하고' 가 그 문서에 <b>딱 한 번</b> 있어 IDF 가 폭발했고, 정작 12번 나오는 '반품'
     * (여러 문서에 흔해 IDF 가 낮다)을 눌러 버렸다. 영어 BM25 가 "the/is/want" 를
     * 빼는 것과 같은 처리다.
     */
    private static final java.util.Set<String> STOPWORDS = java.util.Set.of(
            "하고", "하는", "하기", "하면", "하나", "해요", "해서", "해야", "합니", "니다",
            "습니", "있습", "있는", "있나", "있어", "없나", "없는", "나요", "되나", "되는",
            "되어", "된다", "어요", "싶어", "싶습", "싶다", "주세", "세요", "알려", "려주",
            "려줘", "어떻", "떻게", "무엇", "언제", "어디", "누가", "인가", "인지", "얼마",
            "마나", "그리", "리고", "그런", "런데", "제가", "저는", "좀요", "부탁");

    /**
     * 한글은 <b>조사를 떼고</b> 문자 bi-gram, 영숫자는 단어 단위로 자른다.
     *
     * <p>한 글자로 남은 한글(할·수·것 …)은 버린다 — 의존명사·어미라 내용이 없다.
     *
     * <p>예: {@code "주문 12345 반품은"} → {@code [12345, 반품]}
     */
    public static List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return tokens;
        }

        Matcher hangul = HANGUL.matcher(text);
        while (hangul.find()) {
            String run = stripJosa(hangul.group());
            if (run.length() == 1) {
                continue;   // 한 글자는 조사·의존명사 — 내용이 없다
            }
            for (int i = 0; i + 1 < run.length(); i++) {
                String bigram = run.substring(i, i + 2);
                if (!STOPWORDS.contains(bigram)) {
                    tokens.add(bigram);
                }
            }
        }

        Matcher alnum = ALNUM.matcher(text);
        while (alnum.find()) {
            tokens.add(alnum.group().toLowerCase());
        }
        return tokens;
    }

    /**
     * 어절 꼬리의 조사를 한 번만 떼어 낸다.
     *
     * <p>질의와 문서에 <b>같은 규칙</b>을 적용하므로, 일부를 잘못 떼더라도
     * 양쪽이 같은 토큰이 되어 매칭 자체는 깨지지 않는다. 중요한 건 일관성이다.
     */
    private static String stripJosa(String run) {
        for (String josa : JOSA) {
            if (run.length() >= josa.length() + 2 && run.endsWith(josa)) {
                return run.substring(0, run.length() - josa.length());
            }
        }
        return run;
    }
}
