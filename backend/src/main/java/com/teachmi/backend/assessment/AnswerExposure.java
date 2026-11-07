package com.teachmi.backend.assessment;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/** Deterministic checks for explicit answer disclosure; source grounding is checked by the shared AI port. */
final class AnswerExposure {
    private static final Pattern ANSWER_LABEL = Pattern.compile(
            "(?:정답|해답|답)(?:은|는|:|=)|(?:the)?answer(?:is|:|=)");

    private AnswerExposure() {}

    static String normalize(String value) {
        return Normalizer.normalize(value,Normalizer.Form.NFKC)
                .replaceAll("[\\s\\p{Z}\\p{Cf}]+","").toLowerCase(Locale.ROOT);
    }

    static boolean questionRevealsAnswer(String question, String expectedAnswer) {
        var text=normalize(question);
        var answer=normalize(expectedAnswer);
        if (answer.isEmpty()) return false;
        var labelledAnswer=Pattern.compile("^[\\p{P}\\p{S}]*"+Pattern.quote(answer));
        var labels=ANSWER_LABEL.matcher(text);
        while (labels.find()) {
            var value=text.substring(labels.end());
            // Keep a leading minus sign in an answer; also accept quoted/Markdown-labelled answers.
            if (labelledAnswer.matcher(value).find()) return true;
        }
        // A question may legitimately repeat a given number. Reject answer declarations, not all occurrences.
        return text.contains(answer+"가정답") || text.contains(answer+"이정답")
                || text.contains(answer+"가해답") || text.contains(answer+"이해답");
    }
}
