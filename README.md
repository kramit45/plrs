# PLRS — Personalized Learning Recommendation System

[![build](https://github.com/kramit45/plrs/actions/workflows/build.yml/badge.svg?branch=main)](https://github.com/kramit45/plrs/actions/workflows/build.yml)

PLRS is the capstone project submitted for IGNOU MCA course **MCSP-232**. It explores a recommendation system that tailors learning resources to individual learners based on their profile, progress, and performance signals, combining content-based and collaborative filtering techniques into a single deployable Java application.

## Status

- Iteration 1 in progress

## Architecture

_TBD_

## Quick Start

Prerequisites: JDK 17, Maven 3.9+, and a running Docker daemon (required for integration tests — `mvn -pl plrs-infrastructure verify` spins up PostgreSQL via Testcontainers).

If you use Colima instead of Docker Desktop, export these so Testcontainers can find the socket:

```sh
export DOCKER_HOST="unix://$HOME/.colima/default/docker.sock"
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE="/var/run/docker.sock"
```

## Testing

_TBD_
