/**
 * Application services for the Personalized Learning Recommendation System (PLRS).
 *
 * <p>This layer orchestrates use cases by composing domain types and driving
 * outbound ports. It depends only on {@code com.plrs.domain} and the ports
 * declared within the application itself — never on persistence, web, or any
 * other infrastructure adapter. Spring is permitted here purely for dependency
 * injection and transaction orchestration.
 */
package com.plrs.application;
