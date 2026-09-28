# API Contracts

JSON over HTTP, base URL `http://localhost:8080`. MVP has **no authentication** (non-goal, see [HLD.md](HLD.md) §8).
Entities: [DATA_MODELS.md](DATA_MODELS.md). Invariants: [CORRECTNESS_INVARIANTS.md](CORRECTNESS_INVARIANTS.md).

## 1. Conventions

- `Content-Type: application/json`; errors use `application/problem+json` (RFC 9457, formerly 7807).
- **Amounts are JSON integers in minor units** (`12550` = ₹125.50). Range `1 … 1_000_000_000_000_000`. Decimal strings and fractions are rejected.
- IDs are UUID strings. Timestamps are ISO-8601 UTC (`2026-09-28T10:15:30.123Z`).
- Unknown JSON fields are rejected (`validation_failed`). This makes a typo like `ammount` fail loudly instead of being silently ignored.

## 2. Endpoints

| Method | Path | Purpose | Phase |
|---|---|---|---|
| `POST` | `/accounts` | Create a USER account (balance starts at 0) | 2 ✅ |
| `GET` | `/accounts/{id}` | Account + current balance | 2 ✅ |
| `POST` | `/transfers` | Move money (requires `Idempotency-Key`) | 2 ✅ |
| `GET` | `/transfers/{id}` | Fetch a committed transfer | 2 ✅ |
| `GET` | `/accounts/{id}/entries` | Statement: an account's entries, newest first, keyset-paginated | 3 ✅ |
| `GET` | `/actuator/health` | Liveness + DB check | 0 ✅ |

Funding: `POST /transfers` from the SYSTEM account for that currency (`00000000-0000-0000-0000-000000000001` = INR, `…0002` = USD).

---

### `POST /accounts`

Request:
```json
{ "name": "Alice", "currency": "INR" }
```
| Field | Rules |
|---|---|
| `name` | required, 1–100 chars after trim |
| `currency` | required, one of `INR`, `USD` |

`201 Created`, with `Location: /accounts/{id}`:
```json
{
  "id": "3f6c1a52-8a0e-4d7e-9a57-6f7d0f3b2c11",
  "name": "Alice",
  "kind": "USER",
  "currency": "INR",
  "balance": 0,
  "version": 0,
  "createdAt": "2026-09-28T10:15:30.123Z"
}
```
Errors: `validation_failed` (400).

### `GET /accounts/{id}`
`200 OK`, same shape as above. `balance` and `version` reflect the latest committed state.
Errors: `account_not_found` (404). A malformed UUID gives `validation_failed` (400).

---

### `POST /transfers`

Headers: `Idempotency-Key: <1–255 printable ASCII chars>` (**required**; a client-generated UUIDv4 is recommended).

Request:
```json
{
  "fromAccountId": "00000000-0000-0000-0000-000000000001",
  "toAccountId": "3f6c1a52-8a0e-4d7e-9a57-6f7d0f3b2c11",
  "amount": 500000,
  "currency": "INR",
  "description": "initial deposit"
}
```
| Field | Rules |
|---|---|
| `fromAccountId`, `toAccountId` | required UUIDs, must differ, both must exist |
| `amount` | required integer, `1 ≤ amount ≤ 10^15` |
| `currency` | required. Must equal **both** accounts' currency (it is stated explicitly so a client can't move USD believing it is INR) |
| `description` | optional, ≤ 255 chars |

**First successful execution:** `201 Created`, with `Location: /transfers/{id}`.
**Replay (same key, same request):** `200 OK` with header `Idempotent-Replayed: true` and a body **identical** to the original, including the original `id` and `createdAt`.

```json
{
  "id": "b7d2e0c4-1f3a-4c5e-8d9b-0a1b2c3d4e5f",
  "fromAccountId": "00000000-0000-0000-0000-000000000001",
  "toAccountId": "3f6c1a52-8a0e-4d7e-9a57-6f7d0f3b2c11",
  "amount": 500000,
  "currency": "INR",
  "description": "initial deposit",
  "createdAt": "2026-09-28T10:16:02.456Z",
  "entries": [
    { "accountId": "00000000-0000-0000-0000-000000000001", "amount": -500000 },
    { "accountId": "3f6c1a52-8a0e-4d7e-9a57-6f7d0f3b2c11", "amount": 500000 }
  ]
}
```
Errors: `missing_idempotency_key` (400), `validation_failed` (400), `account_not_found` (404), `same_account` (422), `currency_mismatch` (422), `insufficient_funds` (422), `idempotency_key_reused` (422), `service_busy` (503).

### `GET /transfers/{id}`
`200 OK`, same body as the transfer response above. Errors: `transfer_not_found` (404), `validation_failed` (400).

### `GET /accounts/{id}/entries` *(Phase 3)*
Query: `limit` (1–200, default 50) and `before` (an entry id cursor; omit for the newest).
```json
{
  "items": [
    { "entryId": 1042, "transactionId": "b7d2…", "amount": 500000, "createdAt": "2026-09-28T10:16:02.456Z", "description": "initial deposit" }
  ],
  "nextCursor": 1041
}
```
`nextCursor` is `null` on the last page. Keyset pagination on `(account_id, id)` is used instead of OFFSET, so deep pages stay O(limit).
Errors: `account_not_found` (404), `validation_failed` (400).

---

## 3. Error model

Every error is a `ProblemDetail`:
```json
{
  "type": "https://ledger-engine.dev/problems/insufficient-funds",
  "title": "Insufficient funds",
  "status": 422,
  "detail": "Account 3f6c… has balance 1200, transfer requires 500000.",
  "instance": "/transfers",
  "code": "insufficient_funds"
}
```
`code` is the stable, machine-readable field; `detail` is for humans and may change. `validation_failed` adds `"errors": [{"field": "amount", "message": "must be ≥ 1"}]`.

### Error catalog
| `code` | HTTP | When | Retry? |
|---|---|---|---|
| `validation_failed` | 400 | Malformed JSON, unknown field, missing/invalid field, bad UUID or path param | No, fix the request |
| `missing_idempotency_key` | 400 | `POST /transfers` without a valid `Idempotency-Key` | No |
| `account_not_found` | 404 | An account id in the path or body doesn't exist | No |
| `transfer_not_found` | 404 | Unknown transfer id | No |
| `same_account` | 422 | `fromAccountId == toAccountId` | No |
| `currency_mismatch` | 422 | Request `currency` ≠ either account's currency | No |
| `insufficient_funds` | 422 | Source is a USER account and `balance < amount` (checked under the row lock) | Yes, after funding, **with the same key** |
| `idempotency_key_reused` | 422 | Key already used by a committed transfer with a *different* request hash | No, use a new key |
| `service_busy` | 503 (+ `Retry-After: 1`) | `lock_timeout` hit waiting for a contended row (SQLSTATE `55P03`) | Yes, same key, with backoff |
| `internal_error` | 500 | Unexpected failure, e.g. a DB invariant backstop fired at commit. Indicates a bug; the transaction rolled back, so nothing was written | Yes, same key (safe: the key wasn't consumed) |

HTTP status choices for idempotency follow the IETF `Idempotency-Key` header draft (422 for payload mismatch). The draft's `409` for "concurrent request in flight" is **not** needed: the concurrent duplicate *waits* on the unique index and then replays ([LLD.md](LLD.md) §5).

## 4. Idempotency policy

1. The key is **consumed only by a committed transfer.** Rejected requests (`4xx`) and `service_busy` roll back entirely and leave no idempotency row, so the same key can be retried, e.g. after `insufficient_funds` is resolved.
2. "Same request" means the same **canonical request hash**: `fromAccountId | toAccountId | amount | currency | description`, normalised, so JSON whitespace or field order don't matter ([LLD.md](LLD.md) §5).
3. A replay is served from the ledger (the stored `transaction_id`), not from a cached response blob, so a replay can never disagree with the log.
4. Keys never expire in the MVP.
