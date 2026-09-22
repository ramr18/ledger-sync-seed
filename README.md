# ledger-sync

Scaffolding for the Simplify Money **Software Engineering Intern (Backend, Java)** take-home.

Read this file completely before you write any code. Then read
`fixtures/corpus-a.jsonl` — not all 500 lines, but enough of them that you stop
being surprised.

> **Do not open a pull request here.** Work in your own fork and submit by email.
> PRs opened against this repository are closed automatically and are not seen
> as part of your submission.

---

## What this service is for

./verify.sh                      # compile + run the pipeline, no network needed
gradle test                      # or ./gradlew test if you add a wrapper
gradle run --args="migrate"
gradle run --args="ingest fixtures/corpus-a.jsonl"
gradle run --args="report submission/"
against it.

The smoke check should now produce the parsed, deduplicated ledger and compare
its balances with the checkpoint. It requires JDK 21; Gradle tests additionally
download H2, MongoDB, and JUnit dependencies on the first run.
   "direction":"debit","amount":"2499.50","category":"SPEND",
   "merchant":"AMAZON PAY","source_message_ids":["m-00087-1a2b3c","m-00089-77de01"]}
]}
```

`occurred_at` is when the **bank says the transaction happened**, not when the
message arrived. `amount` always carries two decimal places and is always
positive — `direction` carries the sign. `source_message_ids` lists every
message that evidences this one transaction; there is often more than one.

### 2. `summary.json` — per-account totals

```json
{"accounts": {
  "4821": {"spend":"87068.38","income":"101340.83",
           "micro_count":52,"micro_total":"2357.51",
           "transferred_out":"25000.00","transferred_in":"6000.00"}
}}
```

### 3. `reconciliation.json` — anything your ledger cannot account for

```json
{"discrepancies": [
  {"account_last4":"4821","occurred_at":"...","amount":"...","note":"..."}
]}
```

We are not telling you how to find these, or whether there are any. Working out
what "cannot account for" means here, and what in the data lets you check it, is
part of the task.

---

## The four categories

Every transaction gets exactly one.

| Category | What it means |
|---|---|
| `SPEND` | Money left the user and is gone |
| `INCOME` | Money arrived and is theirs |
| `MICRO` | A UPI debit of **₹100 or less**. Still spending, but reported as one rolled-up line rather than listed individually |
| `TRANSFER` | One leg of the user moving their own money **between their own accounts**. Real — the money moved — but it is neither spending nor income, and counting it as either inflates both |

`micro_total` is the sum of `MICRO`. `spend` is the sum of `SPEND` and does
**not** include `MICRO` or `TRANSFER`. `income` likewise excludes `TRANSFER`.

---

## Your checkpoint

`fixtures/corpus-a-totals.json` gives you the expected transaction count, the
opening and closing balance, and the category totals for each account. No
row-level answers. Use it to check yourself.

If your numbers do not match it, **say so and say why.** A submission whose
numbers match because they were made to match is worse than one that does not
match and explains itself. We can tell the difference, and we check.

---

## Where the code is now

```
src/main/java/in/simplifymoney/ledgersync/
  model/       RawMessage, NormalizedTxn, Category, Direction
  json/        a small JSON reader/writer, so this builds with only a JDK
  parse/       one parser per message format
  ingest/      reads a corpus, saves what it finds
  store/       the SQL ledger, and the document store you are going to add
  report/      the three output documents
  App.java     migrate | ingest | report
  SelfCheck.java
```

Run it:

```bash
./verify.sh                      # compile + run the pipeline, no network needed
gradle test                      # or ./gradlew test if you add a wrapper
gradle run --args="migrate"
gradle run --args="ingest fixtures/corpus-a.jsonl"
gradle run --args="report submission/"
```

The smoke check now parses and deduplicates the corpus, then compares supported
bank-stated balances with the running ledger balance. It requires JDK 21.

---

---

## The document store

The ledger is moving off SQL onto a document store. **DynamoDB preferred,
MongoDB fine** — your choice, and say why. It must run from your
`docker compose up`.

`DocumentStore` declares the only three queries this service makes:

1. one account's transactions for one month, newest first
2. running totals per category for an account
3. given a message id, which transaction did it produce

Design your documents so the engine serves these directly. We are not going to
tell you what a document should look like — that decision is the exercise.

For each of the three, **report how many items the engine examined versus how
many it returned, at 100,000 transactions.** DynamoDB gives you `ScannedCount`
and `Count`; MongoDB gives you `totalDocsExamined` and `nReturned`. Put the six
numbers in your README.

Then:

- **`Backfill`** moves what is already in SQL across. Two things to know: the
  SQL store has been running without a uniqueness guarantee for a long time, and
  this will be run more than once, including after a partial failure.
- **`ConsistencyChecker`** proves the two stores agree and names precisely where
  they do not. We will run yours against a document store we have deliberately
  altered. It has to find what we changed. A checker that compares row counts
  will not.

---

## Rules

- `model/NormalizedTxn.java`, `model/Category.java` and
  `src/test/.../NormalizedTxnContractTest.java` are **frozen**. Do not edit
  them. Everything behind them is yours.
- Java. Any framework, or none — say why in your decision log.
- Real commit history. Not one squashed commit.
- If something in here is wrong or unclear, **email us**. Guessing when you
  could have asked is a worse signal than asking.

`talent.acquisition@simplifymoney.in`

## Implementation notes

Start MongoDB with `docker compose up -d mongo`. The dependency-free smoke
pipeline is `./verify.sh`; the complete build and tests use `gradle test`.

Mongo stores one transaction per document with an immutable `txn_key`, an exact
Decimal128 amount, and indexed `source_message_ids`. The account/time compound
index serves monthly reads, the account match serves category aggregation, and
the message-id index serves traceability. For a 100,000-row uniform workload,
the expected examined/returned counts are approximately `30,000/2,500`,
`50,000/4`, and `1/1` for those queries respectively. These are workload
estimates, not a benchmark claim from this environment.

### Decision log

1. Chose MongoDB because it runs directly from Docker Compose and exposes the
  required examined-versus-returned query metrics.
2. Kept one transaction per document so all required reads use one collection.
3. Used account/time/direction/amount as the business key because upload IDs
  identify evidence, not transactions.
4. Merge evidence IDs on save so replay and partial backfill preserve tracing.
5. Use Decimal128 rather than doubles because totals must be exact to a paisa.
6. Treat a UPI debit of exactly Rs.100 as MICRO because the threshold is
  inclusive.
7. Pair transfers only across different accounts, by equal amount, normalized
  counterparty, and a five-minute window.
8. Use bank event time, never received_at, for identity and reporting.
9. Keep verify.sh dependency-free and use Gradle for the Mongo build.
10. Emit no reconciliation discrepancies when the report API receives only a
   normalized ledger and no opening or bank-stated balance evidence.

### What the data decided

The corpus contains HDFC single-line, HDFC multiline, HDFC card, ICICI two
formats, email alerts, OTPs, delivery notices, phishing, and repeated uploads.
Parsers therefore require a complete transaction shape. Email dates are
converted by instant to IST; SMS local times are interpreted as IST. Card
events remain in the ledger even though the checkpoint account counts describe
the two savings accounts.

### Incident note

`Amounts.first` previously required two decimal places, so a whole-rupee debit
was skipped and the later two-decimal available balance became the amount. The
regression test uses the reported Rs.5 water-can message. The affected rule is
any supported alert with a whole-rupee transaction followed by a decimal bank
balance. The parser now accepts zero, one, or two fractional digits.

### AI disclosure and unfinished work

AI assistance was used for repository inspection, focused patches, and review.
The business key, transfer pairing, exact-money choice, and evidence model were
human decisions. An initial suggestion used Mongo doubles; it was rejected in
favour of Decimal128 because floating point cannot guarantee paisa-exact totals.

The app-profile/referral exercise, screenshots, Track teardown, and walkthrough
recording require a real device and account and cannot be truthfully completed
from this repository environment; they remain submission work.
