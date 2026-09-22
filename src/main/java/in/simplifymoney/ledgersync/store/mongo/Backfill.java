package in.simplifymoney.ledgersync.store.mongo;

import in.simplifymoney.ledgersync.model.NormalizedTxn;
import in.simplifymoney.ledgersync.store.SqlLedgerStore;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Moves everything already in SQL into the document store. Idempotent:
 * upserts on the txn key, so it can be run twice or resumed after a partial
 * failure without creating duplicates.
 */
public final class Backfill {

    public int run(SqlLedgerStore sql, MongoDocumentStore doc) {
        List<NormalizedTxn> all = sql.all();
        Map<String, NormalizedTxn> unique = new LinkedHashMap<>();
        for (NormalizedTxn t : all) unique.putIfAbsent(key(t), t);
        int n = 0;
        for (NormalizedTxn t : unique.values()) {
            doc.save(t);
            n++;
        }
        return n;
    }

    private static String key(NormalizedTxn t) {
        return t.accountLast4() + "|" + t.occurredAt().toInstant().toEpochMilli()
                + "|" + t.direction() + "|" + t.amount().toPlainString();
    }
}
