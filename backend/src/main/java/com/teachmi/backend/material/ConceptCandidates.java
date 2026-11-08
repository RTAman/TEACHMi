package com.teachmi.backend.material;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 페이지·슬라이드 제목으로 개념 후보를 만든다. 같은 제목이 이어지는 페이지는 하나의 개념(예: p.6~9)으로 묶는다.
 * AI 없이 동작하므로 빠르고 비용이 없다. 후보 이름·범위는 사용자가 S02-5에서 고칠 수 있다.
 */
final class ConceptCandidates {
    static final int MAX_CANDIDATES = 8;
    /** 개념 하나에 연결하는 원문 총량 상한. B의 AI 원문 한도(기본 16,000자)보다 작게 둔다. */
    static final int MAX_CONCEPT_CHARS = 15000;
    private static final int MAX_NAME = 60;
    private static final int MAX_SCOPE = 200;
    private static final Pattern NUMBERING = Pattern.compile(
            "^(?:제\\s*\\d+\\s*[장절강]\\s*|chapter\\s*\\d+\\s*[:.\\-]?\\s*|\\(\\s*[0-9a-zA-Z]{1,3}\\s*\\)\\s*|[0-9ⅠⅡⅢⅣⅤⅥⅦⅧⅨⅩIVX]+(?:[.\\-][0-9]+)*[.)]\\s*)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern BULLET = Pattern.compile("^[\\-–—•·∙▪▸►◦○●■□※*]+\\s*");
    private static final Pattern HAS_LETTER = Pattern.compile("[\\p{IsHangul}\\p{IsAlphabetic}]");
    private static final Set<String> GENERIC = Set.of("목차", "차례", "contents", "table of contents", "agenda", "outline",
            "학습목표", "학습 목표", "학습내용", "학습 내용", "개요", "요약", "정리", "summary", "review", "q&a", "qna",
            "질문", "질의응답", "감사합니다", "thank you", "thanks", "참고문헌", "참고 문헌", "references", "reference",
            "과제", "quiz", "퀴즈", "연습문제", "exercise", "exercises");

    private ConceptCandidates() {}

    record Source(Object id, String title, String text) {}

    record Candidate(String name, String scope, List<Object> sourceIds) {}

    static List<Candidate> from(List<Source> sources) {
        if (sources.isEmpty()) {
            return List.of();
        }
        // 대부분의 페이지에 같은 첫 줄이 있으면 머리말(과목명·주차)로 보고 다음 줄을 제목으로 쓴다.
        var firstLines = sources.stream().map(source -> lines(source.text())).toList();
        var counts = new HashMap<String, Integer>();
        firstLines.forEach(lines -> {
            if (!lines.isEmpty()) {
                counts.merge(key(lines.getFirst()), 1, Integer::sum);
            }
        });
        int pages = sources.size();
        var headers = new java.util.HashSet<String>();
        if (pages >= 4) {
            counts.forEach((line, count) -> {
                if (count * 2 > pages) {
                    headers.add(line);
                }
            });
        }

        var titles = new ArrayList<String>();
        for (int i = 0; i < pages; i++) {
            var source = sources.get(i);
            var lines = firstLines.get(i);
            String title = source.title();
            if (title == null && !lines.isEmpty()) {
                int index = 0;
                if (headers.contains(key(lines.getFirst())) && lines.size() > 1) {
                    index = 1;
                }
                title = lines.get(index);
            }
            titles.add(validTitle(title));
        }

        // 자료가 4쪽 이상이고 첫 쪽이 짧으면 표지로 보고 제외한다.
        int start = pages >= 4 && sources.getFirst().text().length() < 200 ? 1 : 0;

        var groups = new LinkedHashMap<String, Group>();
        String previous = null;
        Group current = null;
        for (int i = start; i < pages; i++) {
            String title = titles.get(i);
            if (title == null) {
                previous = null;
                current = null;
                continue;
            }
            String key = key(title);
            if (current == null || !key.equals(previous)) {
                current = groups.computeIfAbsent(key, ignored -> new Group(title, groups.size()));
            }
            current.add(sources.get(i));
            previous = key;
        }

        return groups.values().stream()
                .filter(group -> !group.sourceIds.isEmpty())
                .sorted(Comparator.comparingInt(Group::chars).reversed())
                .limit(MAX_CANDIDATES)
                .sorted(Comparator.comparingInt(group -> group.order))
                .map(group -> group.candidate(headers))
                .toList();
    }

    private static final class Group {
        final String name;
        final int order;
        final List<Object> sourceIds = new ArrayList<>();
        final List<String> texts = new ArrayList<>();
        int chars;

        Group(String name, int order) {
            this.name = name;
            this.order = order;
        }

        void add(Source source) {
            if (chars + source.text().length() > MAX_CONCEPT_CHARS && !sourceIds.isEmpty()) {
                return;
            }
            sourceIds.add(source.id());
            texts.add(source.text());
            chars += source.text().length();
        }

        int chars() {
            return chars;
        }

        Candidate candidate(Set<String> headers) {
            return new Candidate(name, scope(headers), List.copyOf(sourceIds));
        }

        /** 제목 다음에 나오는 첫 설명 문장을 범위로 쓴다. 머리말과 제목 줄은 건너뛴다. 없으면 기본 문구. */
        private String scope(Set<String> headers) {
            String nameKey = key(name);
            for (String text : texts) {
                for (String line : lines(text)) {
                    String lineKey = key(line);
                    if (headers.contains(lineKey) || lineKey.endsWith(nameKey) || line.length() < 8
                            || !HAS_LETTER.matcher(line).find()) {
                        continue;
                    }
                    return cut(BULLET.matcher(line).replaceFirst(""), MAX_SCOPE);
                }
            }
            return cut(name + "의 의미와 핵심 내용", MAX_SCOPE);
        }
    }

    private static List<String> lines(String text) {
        return text.lines().map(String::strip).filter(line -> !line.isEmpty()).toList();
    }

    private static String validTitle(String raw) {
        if (raw == null) {
            return null;
        }
        String title = NUMBERING.matcher(raw.strip().replaceAll("\\s+", " ")).replaceFirst("").strip();
        if (title.length() < 2 || title.length() > 40 || !HAS_LETTER.matcher(title).find()
                || GENERIC.contains(title.toLowerCase(Locale.ROOT))) {
            return null;
        }
        return cut(title, MAX_NAME);
    }

    private static String key(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    private static String cut(String value, int max) {
        if (value.length() <= max) {
            return value;
        }
        int end = Character.isHighSurrogate(value.charAt(max - 1)) ? max - 1 : max;
        return value.substring(0, end);
    }
}
