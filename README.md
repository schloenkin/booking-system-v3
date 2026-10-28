# Booking System v3

[![CI](https://github.com/schloenkin/booking-system-v3/actions/workflows/ci.yml/badge.svg)](https://github.com/schloenkin/booking-system-v3/actions/workflows/ci.yml)

**A Java and Spring Boot backend engineering project focused on modular architecture, database performance, caching, concurrency, and system design.**

## Project Evolution

Booking System v3 is the continuation and architectural evolution of [Booking System v2](https://github.com/schloenkin/booking-system-v2).

Version 2 established the foundation of the booking platform, including REST APIs, JWT authentication, role-based authorization, PostgreSQL persistence, automated testing, Docker, CI/CD, and local Kubernetes deployment.

Version 3 builds upon that experience with a stronger focus on backend performance, caching, concurrency, and maintainable application architecture.

## What v3 adds

- Availability calculation for a selected service and date.
- A business scheduling policy covering working days, next-working-day booking, opening hours and lunch breaks.
- Redis Cache-Aside for availability responses, using a 30-second time-to-live (TTL).
- Transaction-aware cache invalidation **after a successful database commit**.
- Graceful fallback to PostgreSQL when Redis read/write operations fail.
- One bulk overlapping-bookings query per availability cache miss instead of a database conflict query for each time slot.
- PostgreSQL integration coverage with Testcontainers, service-level tests with Mockito, and concurrency-focused cache behavior tests.

## Technology stack

| Layer | Technology |
| --- | --- |
| Language | Java 17 |
| Framework | Spring Boot |
| Build | Maven multi-module project |
| Persistence | Spring Data JPA / Hibernate, PostgreSQL |
| Availability cache | Redis via Spring `StringRedisTemplate` |
| Tests | JUnit 5, Mockito, Testcontainers (PostgreSQL 17 Alpine) |
| Documentation / API | REST; see the actual v3 controllers and OpenAPI configuration |

The previous v2 project also documented Spring Security, JWT, Flyway, Docker, GitHub Actions, GHCR and Kubernetes. Their **exact configuration and deployment state in v3 must be checked in the current repository**; the v2 deployment history is not automatically a v3 deployment claim.

## Architecture

The application is organized into four Maven modules:

```text
booking-system-v3/
├── booking-domain/          # Business entities and scheduling rules
├── booking-application/     # Use cases, repository/cache ports
├── booking-infrastructure/  # JPA adapters, Redis cache, infrastructure
├── booking-api/             # REST API and Spring Boot configuration
├── pom.xml
└── README.md
```

### Responsibilities

**`booking-domain`** contains booking rules, including `BookingSchedulePolicy`. The domain does not depend on Redis or PostgreSQL.

**`booking-application`** orchestrates use cases such as `BookingService` and `BookingAvailabilityService`. It depends on interfaces such as `BookingRepository`, `AvailabilityCache`, and `AvailabilityCacheInvalidator`, rather than infrastructure details.

**`booking-infrastructure`** implements those interfaces with Spring Data JPA/PostgreSQL and Redis. Relevant implementations include `JpaBookingRepositoryAdapter`, `RedisAvailabilityCache` and `TransactionalAvailabilityCacheInvalidator`.

**`booking-api`** exposes the REST endpoints and application configuration. The current development configuration uses the `jpa` Spring profile.

Dependency direction:

```text
booking-api ----------> booking-application ----------> booking-domain
                              ^                            ^
                              |                            |
booking-infrastructure -------+----------------------------+
```

## Availability and scheduling rules

The scheduling policy currently uses:

| Rule | Value |
| --- | --- |
| Business timezone | `Europe/Berlin` |
| Working days | Monday–Friday |
| Earliest bookable day | Next working day |
| Opening time | 09:00 |
| Lunch interval | 13:00–14:00 |
| Closing time | 18:00 |
| Slot start interval | 30 minutes |
| Booking duration | Derived from the selected service |

An availability slot is marked unavailable when the schedule policy rejects it or its time interval overlaps an existing non-cancelled booking.

Interval overlap follows the half-open interval rule:

```java
existingBooking.getStartTime().isBefore(candidateEnd)
    && existingBooking.getEndTime().isAfter(candidateStart)
```

For example, a booking from **10:00 to 11:00** blocks both 10:00 and 10:30 starts for a 30-minute service. A slot ending exactly at 10:00 does not overlap.

### Calendar-day validation

Before reading Redis, the availability service checks whether the requested date is earlier than the next working day. If so, it returns unavailable slots without consulting Redis or loading bookings. This prevents a cached result calculated shortly before midnight from making a now-disallowed date appear bookable.

This check is **not** an atomic guarantee across a midnight boundary for a request that is already in progress.

## PostgreSQL availability-query optimization

On a cache miss, the service loads overlapping bookings for the selected calendar day with one repository call:

```java
LocalDateTime dayStart = date.atStartOfDay();
LocalDateTime dayEnd = date.plusDays(1).atStartOfDay();

List<Booking> existingBookings = bookingRepository.findOverlappingBookings(
    serviceId, dayStart, dayEnd
);
```

The Spring Data repository excludes cancelled bookings and applies interval overlap conditions:

```java
findByService_IdAndStatusNotAndStartTimeLessThanAndEndTimeGreaterThan(
    serviceId,
    BookingStatus.CANCELLED,
    dayEnd,
    dayStart
);
```

Slot conflicts are then evaluated against the fetched list in memory. This avoids repeated per-slot conflict queries during availability calculation. The optimization reduces repository calls on this path; no production latency or throughput benchmark is claimed.

**Important:** The authoritative conflict check during booking creation is separate from the cached availability display. It continues to query PostgreSQL while the booking workflow holds the necessary transaction/locking protection.

## Redis availability cache

### Cache-Aside flow

```text
GET availability
    |
    v
Check requested business date
    |-- date not bookable --> return unavailable slots
    |
    v
Read Redis
    |-- HIT ---------------> return cached slots
    |
    `-- MISS / Redis error
             |
             v
       Query PostgreSQL once
             |
             v
       Calculate availability
             |
             v
       Try to write Redis (TTL 30s)
             |
             v
       Return availability
```

Cache key format:

```text
booking:availability:{serviceId}:{date}
```

Example:

```text
booking:availability:1:2026-11-02
```

The cache contains the **calculated availability slots**, not the authoritative booking records. A cache hit skips the overlapping-bookings query; other reads needed by the use case, such as loading service details, can still occur.

### Invalidation after commit

`TransactionalAvailabilityCacheInvalidator` registers a Spring transaction synchronization and calls `AvailabilityCache.evict(serviceId, date)` only in `afterCommit()`.

- Successful booking transaction: invalidate affected cached availability.
- Rolled-back transaction: do not invalidate as if the booking had succeeded.
- Redis invalidation failure: log the failure without attempting to roll back an already committed database transaction.

The precise cache invalidation call sites for create/cancel/reschedule should be checked against their actual v3 use cases.

### Failure behavior

The application handles Redis read and write failures by calculating/returning availability through PostgreSQL rather than treating the cache as a required source of truth.

### Consistency model and known race condition

Availability responses are **eventually consistent**. A concurrent sequence can cause a stale cache write:

1. Request A misses Redis and reads the old database state.
2. Request B commits a booking and evicts the cache.
3. Request A finishes calculating and writes its old result into Redis.

The Redis commands themselves may all succeed; the issue is their ordering across requests. A 30-second TTL bounds the lifetime of a particular stale cache entry under normal Redis expiry behavior. It does **not** guarantee that every availability response is fresh.

This is an **accepted limitation** for the v3 availability display. It does not replace the transaction-protected PostgreSQL conflict check when creating a booking. The concurrency regression-style test intentionally documents the currently possible stale write, rather than claiming it has been eliminated.

For stricter cache correctness, a future implementation could use generation/version checks and atomic conditional writes; that additional complexity is not part of the current design.

## Booking correctness and concurrency

PostgreSQL is the authoritative system for booking writes. The booking creation path obtains a pessimistic lock on the relevant service record and checks for conflicting bookings inside the transaction before saving.

This protects booking creation independently of Redis cache freshness, assuming every relevant booking write follows the same locking and conflict-validation rules.

The **v2 README** also described JPA optimistic locking (`@Version`) for concurrent updates. Check the corresponding v3 entities/migrations before retaining that as a verified v3 feature.

## API

The availability resource is exposed at:

```http
GET /api/services/{id}/availability
```

Check the v3 controller for the exact date query parameter, response schema, authentication requirements, and any other endpoints. Do not assume the v2 API contract or deployment port is unchanged.

The previous v2 project documented authentication, user/service/booking management, role-based access, OpenAPI and unified error responses. Those details can be incorporated here once verified against the current v3 API and security configuration.

## Running locally

### Prerequisites

- Java 17
- Maven
- Docker Desktop or another Docker-compatible runtime for PostgreSQL/Testcontainers
- A configured PostgreSQL instance
- Redis for exercising the actual cache path (the application has a fallback when Redis is unavailable)

From the project root:

```powershell
mvn clean verify
```

This is the verification command used during the v3 availability and Redis work.

The previous v2 README contained a Docker Compose configuration and local `.env` instructions. Before publishing v3-specific `docker compose` startup commands, verify that the v3 repository contains a compatible `compose.yaml`, the required Redis service and environment variables, and any appropriate health checks. Do not copy the v2 Compose instructions without validating them.

## Testing

Tests created or exercised during the v3 work cover:

- Cache HIT: return cached availability without querying overlapping bookings.
- Cache MISS: load overlapping bookings once and calculate slots.
- Overlap: one-hour booking blocks two consecutive 30-minute starts.
- Redis read failure: calculate and return availability using PostgreSQL.
- Redis write failure: still return the calculated result.
- Post-commit eviction, rollback and Redis invalidation failure scenarios.
- PostgreSQL adapter's overlapping-bookings query using Testcontainers.
- Deterministic illustration of the accepted stale-write race using `CountDownLatch`.
- Requests for no-longer-bookable dates bypass the availability cache.

Run the full build:

```powershell
mvn clean verify
```

A successful local Maven build confirms that the configured local test suite passed. It is not, by itself, evidence of successful GitHub CI, a Docker image build, or a production deployment.

## CI/CD and deployment status

The repository for this version is:

`https://github.com/schloenkin/booking-system-v3`

The CI badge above assumes `.github/workflows/ci.yml` exists in this repository. **Verify that workflow path** before relying on the badge.

The earlier v2 project documented:

- GitHub Actions verification and Docker image publishing to GHCR;
- automated Docker Compose deployment through `booking-system-deploy` and a Windows self-hosted runner;
- a separately managed local Kubernetes deployment.

Those are **v2 historical details**, not automatically verified v3 capabilities. If they are present and configured for v3, replace this paragraph with the actual current workflows, image tags, Compose stack and deployment steps.

## Architectural trade-offs

| Decision | Benefit | Trade-off |
| --- | --- | --- |
| PostgreSQL as source of truth | Transactional booking integrity | Database participates in every authoritative write |
| Redis Cache-Aside | Fewer repeated availability queries | Availability display may be briefly stale |
| 30-second TTL | Short-lived cached entries | Some requests will recalculate after expiry |
| Invalidate after commit | Avoids invalidating for rolled-back writes | Does not prevent an in-flight stale cache `PUT` |
| Bulk overlap query | Avoids per-slot database calls | Application examines the returned booking intervals in memory |
| Date validation before cache read | Enforces current date eligibility on ordinary requests | In-flight cross-midnight requests are not fully synchronized |

## Future Development

Booking System v3 is designed for continuous, incremental improvement rather than being replaced by a new project version.

Potential future enhancements include:

- **Apache Kafka** for event-driven communication.
- **Kubernetes** deployment and orchestration.
- **Observability** with metrics, monitoring, and distributed tracing.
- **Performance testing** and load testing.
- **Further database optimizations** and advanced concurrency scenarios.

These improvements will be introduced when they address concrete architectural or functional requirements rather than simply increasing the number of technologies used.

## Project purpose

Booking System v3 is a backend engineering learning and portfolio project. The focus is on understanding not only **how** to integrate infrastructure components, but **why** they are appropriate, what correctness guarantees they provide, and what limitations they introduce.

The practical lessons include modular Java/Spring design, transactional PostgreSQL persistence, concurrency control, efficient querying, cache-aside behavior, graceful cache fallback, deterministic tests and explicit documentation of architectural trade-offs.

## Development workflow

1. Create a task branch when appropriate.
2. Implement the change and run focused tests.
3. Run `mvn clean verify` from the project root.
4. Review `git status` and `git diff`.
5. Commit and push changes to the v3 repository.
6. Verify the actual GitHub Actions result before claiming CI success.
