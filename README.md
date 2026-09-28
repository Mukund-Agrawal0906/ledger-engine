# Ledger Engine

**A financial ledger that never loses a cent:** ACID double-entry accounting, idempotent transfers, and correctness you can check under heavy concurrent load.

Every money movement is recorded as balanced, append-only debit/credit entries in PostgreSQL. Transfers are atomic, retries are safe (idempotency keys), and the system's core invariant — *the sum of all balances never changes* — is asserted by a stress test that fires tens of thousands of overlapping transfers.

> **Status:** Phase 0 (skeleton) + Phase 1 (design) complete. Ledger features land in Phase 2.

## Stack

Java 21 · Spring Boot 4 · PostgreSQL 16 · Flyway · plain SQL via `JdbcClient` (deliberately no ORM) · JUnit 5 + Testcontainers · Docker Compose · GitHub Actions

## Correctness guarantees

The invariants this system guarantees, and the test that proves each one, are defined in [docs/CORRECTNESS_INVARIANTS.md](docs/CORRECTNESS_INVARIANTS.md). *(Results and the isolation-level experiment write-up will go here.)*

## Design

- [HLD](docs/HLD.md): architecture, transfer data flow, concurrency strategy, tech rationale
- [LLD](docs/LLD.md): packages, the transfer algorithm, locking and idempotency details
- [Data models](docs/DATA_MODELS.md) · [API contracts](docs/API_CONTRACTS.md)

## Run it

```bash
docker compose up -d --build
curl http://localhost:8080/actuator/health
```

## Test it

```bash
./mvnw verify    # requires Docker (Testcontainers spins up a real Postgres)
```
