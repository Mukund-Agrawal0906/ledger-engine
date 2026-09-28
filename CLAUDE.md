# CLAUDE.md

Persistent project memory for AI-assisted development on this repo. Read this before making changes.

## What this project is

Ledger Engine — an ACID double-entry ledger / payments engine whose selling point is **provable correctness under concurrency**: no lost updates, no double-spends, idempotent transfers, and a total balance that is conserved across 50k+ concurrent transfers. See `README.md` for the pitch and `docs/` for the full design.

## Stack (locked — do not swap without updating this file)

- **Language/runtime:** Java 21 (LTS).
- **Framework:** Spring Boot 4.1 (`spring-boot-starter-webmvc`, `-jdbc`, `-validation`, `-actuator`, `-flyway`).
- **DB access:** Spring `JdbcClient` with hand-written SQL. **No JPA/Hibernate** — locking (`SELECT … FOR UPDATE`), isolation levels and lock ordering must be explicit and visible. See `docs/HLD.md` §6.
- **Database:** PostgreSQL 16. Schema managed **only** by Flyway migrations in `src/main/resources/db/migration/V<n>__<desc>.sql` — never edit an applied migration, add a new one.
- **Build:** Maven via the wrapper (`./mvnw`). No global Maven needed.
- **Testing:** JUnit 5 + AssertJ + Testcontainers (`postgres:16-alpine`). **Never H2** — isolation/locking semantics are Postgres-specific.
- **Containerization:** Docker Compose (`db` on host port **5433**, `app` on **8080**; 5432/8000 belong to rag-eval-platform).
- **CI:** GitHub Actions, `./mvnw -B verify`.

## Folder structure

```
ledger-engine/
├── src/main/java/com/mukundagrawal/ledger/
│   ├── LedgerApplication.java
│   ├── api/          # (Phase 2+) REST controllers + request/response records — thin
│   ├── service/      # (Phase 2+) business logic; owns @Transactional boundaries
│   ├── repository/   # (Phase 2+) JdbcClient SQL, one class per aggregate
│   ├── domain/       # (Phase 2+) immutable records: Account, Transfer, Entry, Money
│   └── error/        # (Phase 2+) domain exceptions + ProblemDetail mapping
├── src/main/resources/
│   ├── application.yml
│   └── db/migration/ # Flyway V*.sql
├── src/test/java/com/mukundagrawal/ledger/
│   ├── TestcontainersConfiguration.java   # shared Postgres container (@ServiceConnection)
│   ├── TestLedgerApplication.java         # run the app locally against a throwaway container
│   └── *IT.java / *Test.java
├── docs/             # HLD, LLD, API_CONTRACTS, DATA_MODELS, CORRECTNESS_INVARIANTS
├── Dockerfile
├── docker-compose.yml
└── .github/workflows/ci.yml
```

## Conventions

- **Money is `long` minor units** (paise/cents) everywhere — Java and SQL (`BIGINT`). Never `double`/`float`; no `BigDecimal` in the MVP since there's no FX/division.
- **The log is append-only.** `transactions` and `entries` are only ever INSERTed. Balances in `account_balances` are a cache derivable from `entries`.
- **Services own transactions.** `@Transactional` lives on service methods only — never on controllers or repositories.
- **Lock order is ascending account id.** Any code path that locks more than one balance row must sort ids first (deadlock avoidance — `docs/LLD.md` §4).
- **Controllers are thin:** validate → call a service → map to a response record. Errors are thrown as domain exceptions and mapped to RFC 7807 `ProblemDetail` in one `@RestControllerAdvice`.
- **Test naming decides the runner:** `*Test` = fast unit tests (surefire, `test` phase); `*IT` = Testcontainers-backed (failsafe, `verify` phase). Correctness claims must be backed by an `*IT` against real Postgres.
- **Invariants first.** Don't write transfer/ledger code before the relevant invariant in `docs/CORRECTNESS_INVARIANTS.md` names the test that will prove it.

## How to run

```bash
# Full stack (Postgres + app)
cp .env.example .env          # optional, defaults work
docker compose up -d --build
curl http://localhost:8080/actuator/health
docker compose down            # add -v to wipe the DB volume

# App on host against compose Postgres (needs local JDK 21)
docker compose up -d db
./mvnw spring-boot:run
```

## How to run tests

```bash
./mvnw verify                  # unit + integration (needs Docker running for Testcontainers)
./mvnw test                    # unit tests only
```

**No local JDK 21?** (this machine has JDK 20 as of 2026-09-28) Run the build in a container — Testcontainers reaches the host Docker through the mounted socket:

```bash
MSYS_NO_PATHCONV=1 docker run --rm -v "$(pwd -W):/src" -w /src -v ledger-m2:/root/.m2 \
  -v /var/run/docker.sock:/var/run/docker.sock -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal \
  eclipse-temurin:21-jdk ./mvnw -B verify
```

## Current phase status

- **Phase 0 (Foundation):** done — Boot app, Flyway V1 baseline, `/actuator/health` with live DB check, `HealthIT` on Testcontainers, Dockerfile + compose, CI workflow.
- **Phase 1 (Design docs):** done — see `docs/`.
- **Phase 2 (Vertical slice):** done — V2 schema with DB backstops (I1 deferred trigger, I4 CHECK, I6 append-only triggers), `POST/GET /accounts`, `POST/GET /transfers` with idempotency, ProblemDetail error catalog, strict JSON. 45 tests (`./mvnw verify`):
  - unit/web: `RequestHasherTest`, `LockOrderTest`, `AccountControllerTest`, `TransferControllerTest`
  - IT: `SchemaGuardsIT` (I1/I4/I6 via raw SQL), `TransferServiceIT` (rejections have no side effects; opposing-transfer deadlock test), `IdempotencyIT` (I5 incl. 50-thread race), `LedgerInvariantsIT` (I1–I4), `TransferApiIT` (full HTTP slice), `HealthIT`
  - Shared helpers: `LedgerITSupport` (base class), `LedgerAssertions` (global I1–I4 SQL checks, reuse in the stress test)
  - **Lesson (mutation-checked):** the deadlock test must use ≥ 8 threads. With 2 threads a deliberately broken lock order still passed, because the threads fall into a lock-step convoy. Mutation-check new concurrency tests before trusting them.
- **Phase 3 (depth):** done. Statement API (`GET /accounts/{id}/entries`, keyset). `@Tag("stress")` suites, excluded from default `verify` and run with `./mvnw verify -Pstress` (or the manual `stress.yml` CI workflow): `stress/ConcurrencyStressIT` (51,200 transfers, ~6 min locally), `stress/HttpStressIT`, `experiment/IsolationExperimentIT` (own container, ~4 min). Reports go to `target/*-report.md`, and measured results are in the README. **Keep experiment strategies writing in `LockOrder`:** unordered writes turned the first run into a 70-minute deadlock storm.
- **Phase 4 (next):** reconciliation job + health indicator, deploy (Fly.io/AWS) with IaC, shorter lock hold time (single-statement transfer), then re-benchmark.
- **Old Phase 3+ roadmap (for reference):** concurrency stress test (I2/I4/I5 at 50k transfers), isolation-level experiment (lost update under READ COMMITTED vs FOR UPDATE vs SERIALIZABLE), statement API, benchmarks, deploy.
