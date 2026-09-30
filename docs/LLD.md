# Low-Level Design — Ledger Engine

Companion to [HLD.md](HLD.md). Schema: [DATA_MODELS.md](DATA_MODELS.md). HTTP shapes: [API_CONTRACTS.md](API_CONTRACTS.md). Invariants (I1–I7): [CORRECTNESS_INVARIANTS.md](CORRECTNESS_INVARIANTS.md).

## 1. Package layout (`com.mukundagrawal.ledger`)

```
api/
  AccountController        POST /accounts, GET /accounts/{id}
  TransferController       POST /transfers, GET /transfers/{id}
  JsonStrictnessConfig     Jackson 3 strictness (§6)
  dto/                     CreateAccountRequest, AccountResponse, CreateTransferRequest, TransferResponse, EntryResponse
service/
  AccountService           createAccount, getAccount
  TransferService          transfer(TransferCommand) -> TransferResult, getTransfer(UUID)
  TransferResult           record (Transfer transfer, boolean replayed)
  RequestHasher            canonical SHA-256 of a TransferCommand (§5)
  LockOrder                the single comparator for account lock ordering (§4)
repository/
  AccountRepository        insertUserAccount (accounts + account_balances), findById → read model Account
  BalanceRepository        lockForUpdate(ids), applyDelta(id, delta)
  LedgerRepository         insertTransaction, insertTransferEntries, findTransfer(txId)
  IdempotencyRepository    tryClaim(key, hash, txId) -> boolean, find(key)
domain/
  Account, AccountKind, Currency, Entry, Transfer, TransferCommand   (records/enums, see DATA_MODELS §6)
error/
  LedgerException          sealed base: carries `code` + HTTP status
  AccountNotFoundException, TransferNotFoundException, SameAccountException,
  CurrencyMismatchException, InsufficientFundsException, IdempotencyKeyReusedException,
  InvalidIdempotencyKeyException (missing_idempotency_key)
  ApiExceptionHandler      @RestControllerAdvice → ProblemDetail (§6); also maps lock timeouts (55P03) → service_busy
```

Dependency rule: `api → service → repository → (JdbcClient)`, and everything may use `domain` and `error`. Repositories never call each other, and nothing below `service` knows about HTTP.

## 2. Transaction boundaries

- `@Transactional` appears **only** on public `service` methods. Default for writes: `@Transactional(isolation = READ_COMMITTED)`, stated explicitly even though it's the Postgres default, so the choice is visible in code review. Reads use `@Transactional(readOnly = true)`.
- Every JDBC connection runs `SET lock_timeout = '5s'` (Hikari `connection-init-sql`). A wait longer than that on a row lock or unique-index lock raises `55P03`, which becomes `service_busy`.
- No `REQUIRES_NEW`, no nested transactions, no self-invocation of `@Transactional` methods (Spring proxies would skip them).

## 3. Transfer algorithm — `TransferService.transfer(cmd)`

The pre-transaction checks are pure and done first, so they don't touch the DB:
- **P1** `cmd.from == cmd.to` → `SameAccountException`.
- Shape rules (`1 ≤ amount ≤ 1e15`, key length, description length) were already enforced by Bean Validation in the controller.

Inside one `READ COMMITTED` transaction:

| # | Step | SQL (abridged) | Outcome / branch |
|---|---|---|---|
| 1 | Generate ids | — | `txId = UUID.randomUUID()`; `hash = RequestHasher.hash(cmd)` |
| 2 | **Claim the key** | `INSERT INTO idempotency_keys(key, request_hash, transaction_id) VALUES (…) ON CONFLICT (key) DO NOTHING` | 1 row → new request, continue to 3. 0 rows → go to **R** (replay). May **block** here while another in-flight transaction holds the same key (§5) |
| 3 | **Lock balances** in `LockOrder` | for each id, in order: `SELECT b.account_id, b.balance, b.allow_negative, a.currency FROM account_balances b JOIN accounts a ON a.id = b.account_id WHERE b.account_id = :id FOR UPDATE OF b` | Row missing → `AccountNotFoundException` (rollback also releases the key claim) |
| 4 | Currency check | — | `cmd.currency ≠ from.currency` or `≠ to.currency` → `CurrencyMismatchException` |
| 5 | Funds check (reads the **locked** row) | — | `!from.allowNegative && from.balance < amount` → `InsufficientFundsException` |
| 6 | Write the log | `INSERT INTO transactions(id, kind, description) VALUES (:txId, 'TRANSFER', :desc)`; `INSERT INTO entries(transaction_id, account_id, amount) VALUES (:txId, :from, -:amt), (:txId, :to, +:amt)` | |
| 7 | Update the cache | `UPDATE account_balances SET balance = balance + :delta, version = version + 1, updated_at = now() WHERE account_id = :id` for each account (`-amt` for from, `+amt` for to), in lock order | Exactly 1 row updated each time; anything else is a bug and throws `IllegalStateException` |
| 8 | Read back | `LedgerRepository.findTransfer(txId)` | Returns `TransferResult(transfer, replayed = false)` |
| 9 | COMMIT | — | Deferred checks run: the `entries_balanced` trigger (I1) and the deferred FK `idempotency_keys → transactions`. Failure here = bug → 500 |

**R — Replay branch** (step 2 inserted 0 rows):
1. `SELECT request_hash, transaction_id FROM idempotency_keys WHERE key = :key`. Under READ COMMITTED this new statement sees the now-committed row.
2. `request_hash ≠ hash` → `IdempotencyKeyReusedException` (nothing has been written; the rollback is a no-op).
3. Otherwise return `TransferResult(findTransfer(transaction_id), replayed = true)`. **No** balance locks are taken, and the result is the original even if replaying it *now* would fail (e.g. the account has since been drained).

Why the key claim is step 2, *before* the balance locks: it gives every transfer the same global acquisition order (key → balances ascending), so no key-vs-balance lock cycle can form. It also means a duplicate request does the cheapest possible work before blocking.

## 4. Locking & deadlock avoidance

- Both balance rows are locked with **separate** `SELECT … FOR UPDATE OF b` statements, issued in `LockOrder` order. `OF b` locks only the balance row, never the `accounts` metadata row, so `GET /accounts/{id}` metadata reads are never blocked. (Plain SELECTs don't block on row locks in Postgres anyway, thanks to MVCC.)
- `LockOrder` compares `UUID.toString()` lexicographically. This matches Postgres's `uuid` ordering (unsigned byte order). **Pitfall avoided:** Java's `UUID.compareTo` compares the two halves as *signed* longs, which disagrees with Postgres for ids whose high bit is set. That is harmless as long as all locking goes through Java, but it would deadlock against any future SQL path that locks with `ORDER BY account_id FOR UPDATE` (e.g. a batch reconciliation job). One comparator, matching the DB, used everywhere.
- Why this prevents deadlock: every transaction acquires balance locks in one global total order, so the wait-for graph can't contain a cycle. Any `40P01` is therefore a bug. It is **not** retried: it's logged at ERROR and returned as 500, and the stress test asserts the count is 0 (I7b).
- Lock hold time is minimised: no network calls, logging I/O, or hashing happen between step 3 and COMMIT. The hash is computed in step 1.

## 5. Idempotency

**Canonical request hash** (`RequestHasher`): SHA-256 over a length-prefixed encoding of the fields in fixed order. Each field is written as `<utf8-byte-length>:<value>`, so a `|` or `:` inside `description` can't make two different requests collide:
```
fromAccountId (lowercase canonical UUID) · toAccountId · amount (decimal) · currency · description (null → "")
```
JSON formatting, field order and whitespace don't affect the hash. The key itself is *not* part of the hash, since it is the lookup handle.

**Concurrency walk-through**, with two identical requests R1 and R2 racing:
1. R1 `INSERT … ON CONFLICT DO NOTHING` inserts, uncommitted.
2. R2's identical INSERT finds a conflicting *uncommitted* tuple in the unique index and **waits for R1's transaction to finish** (standard Postgres behaviour for unique checks).
3. a. R1 commits → R2's `ON CONFLICT` sees a committed conflict → 0 rows → replay branch → same transfer, `200`.
   b. R1 rolls back (e.g. `insufficient_funds`) → R2's INSERT proceeds → R2 executes normally. The key was never "consumed" by the failure.
4. There is no "processing" status column, so there's no stuck state after a crash. Postgres releases the lock with the dead connection.

This is why the idempotency row is written **inside** the same DB transaction as the transfer: the key commits if and only if the transfer commits (API_CONTRACTS §4, policy 1).

## 6. Error handling

`ApiExceptionHandler` maps to `ProblemDetail` with `type = https://ledger-engine.dev/problems/<code-with-dashes>` and a `code` property:

| Source | → `code` / status |
|---|---|
| `MethodArgumentNotValidException`, `HttpMessageNotReadableException`, `MethodArgumentTypeMismatchException` | `validation_failed` / 400 (+ `errors[]` for field errors) |
| `Idempotency-Key` header missing (`MissingRequestHeaderException`), blank, > 255 chars, or containing non-printable-ASCII (explicit check in `TransferController`) | `missing_idempotency_key` / 400 |
| `LedgerException` subclasses | their own `code`/status: `account_not_found` 404, `transfer_not_found` 404, `same_account` 422, `currency_mismatch` 422, `insufficient_funds` 422, `idempotency_key_reused` 422 |
| `DataAccessException` whose root `SQLException.getSQLState()` is `55P03` (lock timeout) | `service_busy` / 503 + `Retry-After: 1` |
| Anything else, including `40P01` and trigger/CHECK violations at commit | `internal_error` / 500, logged at ERROR with the transfer id. These represent bugs, and the invariant tests exist to catch them |

Jackson strictness (Spring Boot 4 / Jackson 3): fail on unknown properties, fail on floats coerced to integer, no scalar coercion (`"100"` is not accepted as `100`). All of this is set in `api/JsonStrictnessConfig` (a `JsonMapperBuilderCustomizer`; Jackson 3 coercion config for `LogicalType.Integer`) and covered by a controller test.

## 7. Account creation — `AccountService.createAccount(name, currency)`

One `READ COMMITTED` transaction: `INSERT INTO accounts(name, kind, currency) VALUES (:name, 'USER', :cur) RETURNING id, created_at`, then `INSERT INTO account_balances(account_id, allow_negative) VALUES (:id, false)`. The API only ever creates USER accounts; SYSTEM accounts exist only via the V2 seed.

## 8. Configuration (`application.yml` additions in Phase 2)

| Property | Value | Why |
|---|---|---|
| `spring.datasource.hikari.maximum-pool-size` | 20 | Sized for the stress test; revisit with benchmarks |
| `spring.datasource.hikari.connection-init-sql` | `SET lock_timeout = '5s'` | Bounded waits (§2) |
| `spring.jackson.*` strictness flags | see §6 | Reject ambiguous input |

## 9. Reconciliation (Phase 4)

`service/ReconciliationService` re-runs the I1–I4 queries over the whole database on a schedule (`ledger.reconciliation.interval`, default `PT5M`, after a `PT30S` initial delay; `ledger.reconciliation.enabled=false` turns it off). Each invariant yields up to 20 sample offending rows.

- **Why, if the DB constraints already prevent violations?** Constraints only guard writes that go *through* them. A manual `UPDATE` on `account_balances`, a bad migration, or a partial restore can still desynchronise the cache from the log (I3) or break conservation (I2). Reconciliation is the detector for exactly that.
- **One copy of the SQL:** the test helper `LedgerAssertions` delegates to this service, so the queries that prove the invariants in tests are the queries that watch them in production.
- **Health:** `health/LedgerHealthIndicator` exposes the *last* report as the `ledger` component of `/actuator/health`: `UNKNOWN` before the first run, `UP` with `lastRun`/`durationMs`, or `DOWN` with the samples. It never runs the full-scan queries on a health poll, and it's deliberately **not** in the liveness group: a data-integrity alarm needs a human, and restarting the app wouldn't fix it.
- **Testing:** `ReconciliationIT` corrupts a cached balance inside a transaction, calls `check()` (which has no `@Transactional` of its own, so it joins that transaction and sees the uncommitted corruption), asserts that both I3 and I2 flag it, and rolls back.

## 10. Test strategy

| Layer | Type | Examples |
|---|---|---|
| Pure logic | `*Test` (no Spring, no DB) | `RequestHasherTest` (field-order independence, `|` collision case), `LockOrderTest` (matches Postgres ordering for high-bit UUIDs) |
| Web contract | `*Test` with `@WebMvcTest` + mocked service | Status codes, ProblemDetail shape, strict JSON, `Idempotent-Replayed` header |
| Service + DB | `*IT` on Testcontainers | `TransferServiceIT`, `IdempotencyIT`, `SchemaGuardsIT`, `LedgerInvariantsIT` (see CORRECTNESS_INVARIANTS) |
| Load / experiment | `*IT` under the Maven profile `-Pstress` (kept out of the default `verify` so CI stays fast) | `ConcurrencyStressIT`, `IsolationExperimentIT` |

All `*IT` classes share one Postgres container through `TestcontainersConfiguration` + Spring's context cache. Tests never delete rows (I6); each test creates its own accounts.
