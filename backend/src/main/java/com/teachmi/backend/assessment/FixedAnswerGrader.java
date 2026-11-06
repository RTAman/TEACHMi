package com.teachmi.backend.assessment;

import java.math.BigDecimal;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Demo only: compares currency amounts, not the reasoning in work. */
@Component
@ConditionalOnProperty(name = "teachmi.assessment.demo-enabled", havingValue = "true")
class FixedAnswerGrader implements AnswerGrader {
    private static final Pattern AMOUNT = Pattern.compile("^([0-9]+(?:\\.[0-9]+)?)(만원|원)$");
    @Override public Grade grade(Input input) {
        var expected = amount(input.expectedAnswer());
        var actual = amount(input.answer());
        if (expected != null && actual != null && expected.compareTo(actual) == 0) {
            return new Grade("correct", null);
        }
        return new Grade("wrong", "정액법에서 배분할 원가와 잔존가치, 사용 기간의 관계를 다시 생각해 보세요.");
    }
    private BigDecimal amount(String answer) {
        var match = AMOUNT.matcher(answer.replaceAll("[\\s,]", ""));
        if (!match.matches()) return null;
        var number = new BigDecimal(match.group(1));
        return match.group(2).equals("만원") ? number.multiply(BigDecimal.valueOf(10_000)) : number;
    }
}
