# Correctness Invariants

> This is the ledger's equivalent of an eval spec. It is written **before** any ledger code. Every invariant has
> (a) a precise definition, (b) where it is enforced, and (c) the concrete test that proves it.
> A feature is only "done" when the tests for the invariants it touches are green against real Postgres.

Conventions used below: amounts are signed `BIGINT` minor units; a **debit** to the source is a negative entry, a **credit** to the destination is a positive entry (see [DATA_MODELS.md](DATA_MODELS.md) §1). SYSTEM accounts (one per currency) represent money outside the ledger and may go negative. USER accounts may not.

---

## I1 — Every transaction balances

**Definition:** for every `transaction t`: `Σ entries.amount WHERE transaction_id = t = 0`, all of `t`'s entries are in the same currency, and `t` has at least 2 entries.

**Enforced by:**
- The app: a transfer always writes exactly `(-amount, +amount)`.
- The DB backstop: a `DEFERRABLE INITIALLY DEFERRED` constraint trigger on `entries` checks the sum and the currency count **at commit time**, so a buggy code path cannot commit an unbalanced transaction ([DATA_MODELS.md](DATA_MODELS.md) §3).

**Proved by:**
- `LedgerInvariantsIT.everyTransactionBalances`: after any test workload, `SELECT transaction_id FROM entries GROUP BY 1 HAVING SUM(amount) <> 0 OR COUNT(*) < 2` returns 0 rows.
- `SchemaGuardsIT.unbalancedTransactionIsRejectedAtCommit`: inserting a single `+100` entry directly via JDBC throws at commit (SQLSTATE `23514`, raised by the trigger).

## I2 — Global conservation of money

**Definition:** for each currency `c`: `Σ account_balances.balance over all accounts in c = 0`, at every committed point in time. Money enters only by moving out of the SYSTEM account, which goes negative by exactly the amount in circulation.

**Enforced by:** I1 (each transaction nets to zero) plus balances only changing inside the transaction that writes the entries.

**Proved by:**
- `LedgerInvariantsIT.moneyIsConserved`: `SELECT currency, SUM(balance) … GROUP BY currency` gives 0 for every currency.
- Also asserted at the end of the stress test (§ Stress test), including *while* transfers are still running: a concurrent reader thread samples the sum every 50 ms, and every sample must be 0. This proves no committed state is ever observably unbalanced.

## I3 — The cached balance equals the log

**Definition:** for every account `a`: `account_balances.balance(a) = COALESCE(Σ entries.amount WHERE account_id = a, 0)`.

**Enforced by:** the balance `UPDATE` and the entry `INSERT` happen in the same DB transaction while the row lock is held ([LLD.md](LLD.md) §3).

**Proved by:**
- `LedgerInvariantsIT.cachedBalancesMatchLog`: a reconciliation query (LEFT JOIN of balances against aggregated entries, filtered to mismatches) returns 0 rows. The same query later becomes the Phase 3 reconciliation job.

## I4 — USER accounts never go negative

**Definition:** for every account with `allow_negative = false`: `balance ≥ 0` at every committed point in time.

**Enforced by:**
- The app: a funds check against the **locked** balance row (`SELECT … FOR UPDATE`) returns `insufficient_funds`.
- The DB backstop: `CHECK (allow_negative OR balance >= 0)` on `account_balances`.

**Proved by:**
- `TransferServiceIT.overdraftIsRejected`: a single-threaded case.
- Stress test: the workload is deliberately sized so many transfers **should** fail. It asserts `MIN(balance) ≥ 0` over USER accounts **and** `insufficient_funds` rejections > 0. The second assertion proves the test actually exercised the race rather than passing vacuously.

## I5 — Exactly-once transfers per idempotency key

**Definition:** for any `Idempotency-Key k`, at most one transaction is ever committed for `k`. Repeating the same request with `k` returns the **same** transfer. Using `k` with a different request payload is rejected and changes nothing.

**Enforced by:** `idempotency_keys.key` is the PRIMARY KEY, and the row is inserted **first**, inside the transfer's own DB transaction. A concurrent duplicate blocks on the unique index until the first request commits or rolls back ([LLD.md](LLD.md) §5).

**Proved by:**
- `IdempotencyIT.concurrentRetriesCommitOnce`: 50 threads send the same key and body at once (released by a `CountDownLatch`). Assertions: exactly 1 row in `transactions` for that key, all 50 responses carry the same transfer id, and the source is debited exactly once.
- `IdempotencyIT.keyReuseWithDifferentBodyIsRejected`: the second call returns `idempotency_key_reused` and the balances are unchanged.
- `IdempotencyIT.failedTransferDoesNotConsumeKey`: a transfer rejected for `insufficient_funds` succeeds after funding when retried with the same key (see the policy in [API_CONTRACTS.md](API_CONTRACTS.md) §4).

## I6 — The log is append-only

**Definition:** rows in `transactions` and `entries` are never updated or deleted.

**Enforced by:** `BEFORE UPDATE OR DELETE` row triggers and a `BEFORE TRUNCATE` statement trigger on both tables that `RAISE EXCEPTION`. Corrections are made with new compensating transactions, never with edits. (A later hardening step: the app's DB role gets only `INSERT, SELECT` on these tables.)

**Proved by:** `SchemaGuardsIT.logIsAppendOnly`, where `UPDATE`, `DELETE`, and `TRUNCATE` on each table all throw.

> Consequence for tests: tests **never clean up** by deleting rows. Each test creates its own accounts and asserts relative to them. I2 holds globally regardless.

## I7 — No lost updates, no deadlocks under contention

**Definition:** under any interleaving of concurrent transfers:
(a) every account's final balance = its opening balance + Σ credits of *successful* transfers − Σ debits of *successful* transfers, as reported by the API responses;
(b) no request fails with deadlock (`40P01`).

**Enforced by:** pessimistic row locks (`FOR UPDATE`) on both balance rows, always acquired in **ascending account-id order** ([LLD.md](LLD.md) §4).

**Proved by:** the stress test. (a) is computed client-side from the responses and compared with the DB. This is the check that *detects* a lost update, which I2 alone can miss if two bugs cancel out. (b) comes from counting exceptions by SQLSTATE. `TransferServiceIT.opposingTransfersDoNotDeadlock` runs A→B and B→A in tight loops on 2 threads × 2,000 iterations.

---

## Stress test (Phase 3) — `ConcurrencyStressIT`

| Parameter | Value | Why |
|---|---|---|
| Accounts | 1 SYSTEM (INR), 10 USER | A small hot set maximises lock contention |
| Opening balance | each USER funded 1,000,000 from SYSTEM | |
| Workload | 32 threads × 1,600 = **51,200 transfers** | ≥ 50k target from the blueprint |
| Pair selection | uniform random (from ≠ to) over the 10 USER accounts | Includes opposing-direction pairs (deadlock bait) |
| Amount | uniform 1 … 50,000 | Large enough that many attempts overdraw (I4 must be exercised) |
| Retries | 5% of requests re-send an earlier (key, body) | Exercises I5 under load |
| Entry point | `TransferService` directly (throughput), plus a 5k-request HTTP variant | Separates DB correctness from web-layer overhead |
| Pool | Hikari `maximum-pool-size=20` | Threads > connections, so pool waiting is realistic |

**Asserted at the end:** I1, I2 (plus live sampling), I3, I4 (with rejections > 0), I5 (distinct committed keys = distinct keys that got a success response), I7(a) and I7(b).

**Recorded, not asserted (these go in the README):** committed / rejected / replayed counts, throughput (transfers/s), and latency p50 / p95 / p99.

## Isolation-level experiment (Phase 3) — `IsolationExperimentIT`

The purpose is to *show* the anomalies rather than just claim immunity. The same contended workload runs against three implementations of the balance update:

| Variant | Balance write | Expected result |
|---|---|---|
| A. Naive read-modify-write, READ COMMITTED | `SELECT balance` (no lock) → compute in Java → `UPDATE SET balance = :new` | **Lost updates** (I3/I7a violated) and overdrafts caught only by the CHECK backstop |
| B. `SELECT … FOR UPDATE`, READ COMMITTED (production) | Lock, then check, then `balance = balance + :delta` | 0 anomalies |
| C. No explicit lock, SERIALIZABLE + retry on `40001` | Same SQL as A | 0 anomalies; report the serialization-failure retry rate as the cost |

Output: an anomaly count and throughput for each variant, written up in the README under "Correctness guarantees".
Also noted: a plain atomic `SET balance = balance + :delta` *does* prevent lost updates without a lock, but not the **check-then-act** overdraft race. That is why the funds check needs the lock.
