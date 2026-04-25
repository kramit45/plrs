package com.plrs.web.quiz;

import com.plrs.domain.quiz.PerItemFeedback;
import com.plrs.domain.quiz.QuizItem;
import com.plrs.domain.quiz.QuizItemOption;
import java.util.List;

/**
 * Result-page enrichment: pairs the use case's {@link PerItemFeedback}
 * with the human-readable text of the student's pick and the correct
 * option, so the result template doesn't have to walk the quiz again.
 */
public record ResolvedFeedback(
        int itemOrder,
        String stem,
        String selectedText,
        String correctText,
        boolean isCorrect) {

    public static List<ResolvedFeedback> from(
            List<PerItemFeedback> perItem, List<QuizItem> items) {
        return perItem.stream()
                .map(
                        fb -> {
                            QuizItem item =
                                    items.stream()
                                            .filter(it -> it.itemOrder() == fb.itemOrder())
                                            .findFirst()
                                            .orElseThrow();
                            String selected =
                                    item.options().stream()
                                            .filter(
                                                    o ->
                                                            o.optionOrder()
                                                                    == fb.selectedOptionOrder())
                                            .map(QuizItemOption::optionText)
                                            .findFirst()
                                            .orElse("(blank)");
                            String correct =
                                    item.options().stream()
                                            .filter(
                                                    o ->
                                                            o.optionOrder()
                                                                    == fb.correctOptionOrder())
                                            .map(QuizItemOption::optionText)
                                            .findFirst()
                                            .orElse("(?)");
                            return new ResolvedFeedback(
                                    fb.itemOrder(),
                                    item.stem(),
                                    selected,
                                    correct,
                                    fb.isCorrect());
                        })
                .toList();
    }
}
