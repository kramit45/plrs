package com.plrs.domain.interaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.plrs.domain.common.DomainValidationException;
import com.plrs.domain.content.ContentId;
import com.plrs.domain.user.UserId;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class InteractionEventTest {

    private static final UserId USER = UserId.newId();
    private static final ContentId CONTENT = ContentId.of(42L);
    private static final Instant T0 = Instant.parse("2026-04-25T10:00:00Z");

    /**
     * Invokes {@link InteractionEvent}'s private canonical constructor via
     * reflection. Used for white-box tests that exercise invariant checks
     * unreachable from the public factories (which deliberately exclude
     * impossible combinations at compile time).
     */
    private static InteractionEvent newEvent(
            UserId userId,
            ContentId contentId,
            Instant occurredAt,
            EventType eventType,
            Optional<Integer> dwellSec,
            Optional<Rating> rating,
            Optional<String> clientInfo)
            throws Exception {
        Constructor<InteractionEvent> ctor =
                InteractionEvent.class.getDeclaredConstructor(
                        UserId.class,
                        ContentId.class,
                        Instant.class,
                        EventType.class,
                        Optional.class,
                        Optional.class,
                        Optional.class);
        ctor.setAccessible(true);
        return ctor.newInstance(
                userId, contentId, occurredAt, eventType, dwellSec, rating, clientInfo);
    }

    @Test
    void viewWithDwellValid() {
        InteractionEvent e =
                InteractionEvent.view(USER, CONTENT, T0, Optional.of(30), Optional.empty());

        assertThat(e.eventType()).isEqualTo(EventType.VIEW);
        assertThat(e.dwellSec()).contains(30);
        assertThat(e.rating()).isEmpty();
    }

    @Test
    void viewWithoutDwellValid() {
        InteractionEvent e =
                InteractionEvent.view(USER, CONTENT, T0, Optional.empty(), Optional.empty());

        assertThat(e.dwellSec()).isEmpty();
    }

    @Test
    void completeWithDwellValid() {
        InteractionEvent e =
                InteractionEvent.complete(USER, CONTENT, T0, Optional.of(120), Optional.empty());

        assertThat(e.eventType()).isEqualTo(EventType.COMPLETE);
        assertThat(e.dwellSec()).contains(120);
    }

    @Test
    void bookmarkWithDwellThrows() {
        // White-box: factory `bookmark` doesn't accept dwell, so we hit the
        // canonical ctor directly to verify the dwell_only_vc invariant.
        assertThatThrownBy(
                        () ->
                                newEvent(
                                        USER,
                                        CONTENT,
                                        T0,
                                        EventType.BOOKMARK,
                                        Optional.of(5),
                                        Optional.empty(),
                                        Optional.empty()))
                .isInstanceOf(InvocationTargetException.class)
                .hasCauseInstanceOf(DomainValidationException.class)
                .satisfies(
                        e ->
                                assertThat(e.getCause().getMessage())
                                        .contains("dwell")
                                        .contains("BOOKMARK"));
    }

    @Test
    void likeWithDwellThrows() {
        assertThatThrownBy(
                        () ->
                                newEvent(
                                        USER,
                                        CONTENT,
                                        T0,
                                        EventType.LIKE,
                                        Optional.of(5),
                                        Optional.empty(),
                                        Optional.empty()))
                .isInstanceOf(InvocationTargetException.class)
                .hasCauseInstanceOf(DomainValidationException.class);
    }

    @Test
    void rateWithoutRatingThrows() {
        // White-box: factory `rate` requires a non-null Rating, so we hit
        // the canonical ctor directly to verify the rating_iff_rate invariant.
        assertThatThrownBy(
                        () ->
                                newEvent(
                                        USER,
                                        CONTENT,
                                        T0,
                                        EventType.RATE,
                                        Optional.empty(),
                                        Optional.empty(),
                                        Optional.empty()))
                .isInstanceOf(InvocationTargetException.class)
                .hasCauseInstanceOf(DomainValidationException.class)
                .satisfies(
                        e ->
                                assertThat(e.getCause().getMessage())
                                        .contains("rating")
                                        .contains("RATE"));
    }

    @Test
    void nonRateWithRatingThrows() {
        // White-box: factories never pass rating for non-RATE events.
        assertThatThrownBy(
                        () ->
                                newEvent(
                                        USER,
                                        CONTENT,
                                        T0,
                                        EventType.VIEW,
                                        Optional.empty(),
                                        Optional.of(Rating.of(5)),
                                        Optional.empty()))
                .isInstanceOf(InvocationTargetException.class)
                .hasCauseInstanceOf(DomainValidationException.class);
    }

    @Test
    void negativeDwellThrows() {
        assertThatThrownBy(
                        () ->
                                InteractionEvent.view(
                                        USER, CONTENT, T0, Optional.of(-1), Optional.empty()))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining(">= 0");
    }

    @Test
    void clientInfoOver200CharsThrows() {
        String tooLong = "x".repeat(201);

        assertThatThrownBy(
                        () ->
                                InteractionEvent.view(
                                        USER,
                                        CONTENT,
                                        T0,
                                        Optional.empty(),
                                        Optional.of(tooLong)))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("200");
    }

    @Test
    void clientInfoExactly200CharsAccepted() {
        InteractionEvent e =
                InteractionEvent.view(
                        USER,
                        CONTENT,
                        T0,
                        Optional.empty(),
                        Optional.of("x".repeat(200)));

        assertThat(e.clientInfo()).isPresent();
    }

    @Test
    void factoryMethodsProduceCorrectEventType() {
        assertThat(
                        InteractionEvent.view(
                                        USER, CONTENT, T0, Optional.empty(), Optional.empty())
                                .eventType())
                .isEqualTo(EventType.VIEW);
        assertThat(
                        InteractionEvent.complete(
                                        USER, CONTENT, T0, Optional.empty(), Optional.empty())
                                .eventType())
                .isEqualTo(EventType.COMPLETE);
        assertThat(
                        InteractionEvent.bookmark(USER, CONTENT, T0, Optional.empty())
                                .eventType())
                .isEqualTo(EventType.BOOKMARK);
        assertThat(InteractionEvent.like(USER, CONTENT, T0, Optional.empty()).eventType())
                .isEqualTo(EventType.LIKE);
        assertThat(
                        InteractionEvent.rate(
                                        USER, CONTENT, T0, Rating.of(4), Optional.empty())
                                .eventType())
                .isEqualTo(EventType.RATE);
    }

    @Test
    void rateFactoryRejectsNullRating() {
        assertThatThrownBy(
                        () ->
                                InteractionEvent.rate(
                                        USER, CONTENT, T0, null, Optional.empty()))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("Rating");
    }

    @Test
    void toStringExcludesClientInfo() {
        String secret = "ip=192.0.2.1 ua=secret-user-agent";
        InteractionEvent e =
                InteractionEvent.view(USER, CONTENT, T0, Optional.empty(), Optional.of(secret));

        String s = e.toString();

        assertThat(s).contains("VIEW").contains("ContentId(42)");
        assertThat(s).doesNotContain(secret).doesNotContain("192.0.2.1").doesNotContain("clientInfo");
    }

    @Test
    void equalsAndHashCodeUseNaturalKey() {
        InteractionEvent a =
                InteractionEvent.view(USER, CONTENT, T0, Optional.of(30), Optional.empty());
        // Same natural key, different dwell + clientInfo → still equal.
        InteractionEvent b =
                InteractionEvent.view(
                        USER, CONTENT, T0, Optional.of(99), Optional.of("different"));
        InteractionEvent differentTime =
                InteractionEvent.view(
                        USER, CONTENT, T0.plusSeconds(1), Optional.empty(), Optional.empty());

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a).isNotEqualTo(differentTime);
    }
}
