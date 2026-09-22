DELETE FROM ledger
WHERE id NOT IN (
    SELECT MIN(id)
    FROM ledger
    GROUP BY account_last4, occurred_at, direction, amount
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_ledger_business_key
    ON ledger (account_last4, occurred_at, direction, amount);
