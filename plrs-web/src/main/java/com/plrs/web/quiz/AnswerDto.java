package com.plrs.web.quiz;

/**
 * Wire shape of one student answer in the
 * {@link SubmitQuizAttemptRequest} body.
 */
public record AnswerDto(int itemOrder, int selectedOptionOrder) {}
