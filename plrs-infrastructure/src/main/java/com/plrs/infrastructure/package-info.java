/**
 * Infrastructure adapters for the Personalized Learning Recommendation System (PLRS).
 *
 * <p>This module contains outbound adapters — JPA repositories, Redis caches,
 * and (later) Kafka producers/consumers — that implement ports declared in
 * {@code com.plrs.application}. All framework coupling to persistence and
 * messaging technology is confined to this module; the domain and application
 * layers stay unaware of specific vendors.
 */
package com.plrs.infrastructure;
