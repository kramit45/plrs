package com.plrs.web.quiz;

import com.plrs.domain.quiz.QuizItem;
import java.util.List;

/**
 * Sanitised view of one quiz item for the attempt page. The
 * underlying {@link QuizItem}'s {@code is_correct} flag on each option
 * is dropped at this boundary — see {@link QuizOptionView}.
 */
public record QuizItemView(int itemOrder, String stem, List<QuizOptionView> options) {

    public static QuizItemView from(QuizItem item) {
        return new QuizItemView(
                item.itemOrder(),
                item.stem(),
                item.options().stream().map(QuizOptionView::from).toList());
    }
}
