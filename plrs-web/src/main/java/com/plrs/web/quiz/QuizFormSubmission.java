package com.plrs.web.quiz;

import com.plrs.domain.quiz.AnswerSubmission;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Form-binding bean for the Thymeleaf attempt page. The radio buttons
 * are named {@code answers[<itemOrder>]} so Spring binds them into
 * this map directly. {@link #toAnswerSubmissions()} drops blank
 * entries and converts the remainder to domain
 * {@link AnswerSubmission}s.
 */
public class QuizFormSubmission {

    private Map<Integer, Integer> answers = new HashMap<>();

    public Map<Integer, Integer> getAnswers() {
        return answers;
    }

    public void setAnswers(Map<Integer, Integer> answers) {
        this.answers = answers == null ? new HashMap<>() : answers;
    }

    public List<AnswerSubmission> toAnswerSubmissions() {
        return answers.entrySet().stream()
                .filter(e -> e.getKey() != null && e.getValue() != null)
                .map(e -> new AnswerSubmission(e.getKey(), e.getValue()))
                .toList();
    }
}
