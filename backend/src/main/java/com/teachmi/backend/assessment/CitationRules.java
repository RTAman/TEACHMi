package com.teachmi.backend.assessment;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * C의 프롬프트가 약속한 출처 표기 규칙을 실제로 검사한다.
 * B의 공통 포트는 인용이 원문과 글자 그대로 같은지, 6자 이상인지, 출처 ID가 중복되지 않는지를 이미 확인한다.
 * 여기서는 그 위에 C가 추가로 정한 규칙(인용 40자 이하, 출처 ID당 evidence 1개, 출처 개수 제한)을 검사한다.
 * 규칙을 바꾸면 이 규칙을 쓰는 생성기·채점기의 PROMPT_VERSION도 함께 올린다.
 */
final class CitationRules {
    static final int MIN_QUOTE_LENGTH = 6;
    static final int MAX_QUOTE_LENGTH = 40;

    private CitationRules() {}

    /** 규칙을 지키면 출처 ID 목록을 응답 순서대로 돌려주고, 하나라도 어기면 응답 전체를 거절한다. */
    static List<String> check(JsonNode response, int maxSources) {
        var ids = response.path("sourceChunkIds");
        if (!ids.isArray() || ids.isEmpty()) {
            throw new IllegalArgumentException("출처가 없습니다.");
        }
        if (ids.size() > maxSources) {
            throw new IllegalArgumentException("출처는 " + maxSources + "개까지만 쓸 수 있습니다.");
        }
        var sources = new ArrayList<String>();
        for (var id : ids) {
            var value = id.asText("");
            if (value.isEmpty() || sources.contains(value)) {
                throw new IllegalArgumentException("출처 ID가 비어 있거나 중복됩니다.");
            }
            sources.add(value);
        }

        var evidence = response.path("evidence");
        if (!evidence.isArray() || evidence.size() != sources.size()) {
            throw new IllegalArgumentException("출처 ID마다 evidence가 정확히 1개씩 있어야 합니다.");
        }
        var grounded = new HashSet<String>();
        for (var entry : evidence) {
            var id = entry.path("sourceChunkId").asText("");
            if (!sources.contains(id) || !grounded.add(id)) {
                // 같은 ID에 evidence가 두 개면 다른 ID의 evidence가 빠진 것이다.
                throw new IllegalArgumentException("출처 ID마다 evidence가 정확히 1개씩 있어야 합니다.");
            }
            // 포트와 같은 기준(String.length)으로 센다. 한글·숫자·기호는 모두 한 글자다.
            int length = entry.path("quote").asText("").length();
            if (length < MIN_QUOTE_LENGTH || length > MAX_QUOTE_LENGTH) {
                throw new IllegalArgumentException("인용은 " + MIN_QUOTE_LENGTH + "~" + MAX_QUOTE_LENGTH + "자여야 합니다.");
            }
        }
        return List.copyOf(sources);
    }
}