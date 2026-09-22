package in.simplifymoney.ledgersync.store;

import in.simplifymoney.ledgersync.model.NormalizedTxn;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Moves everything already in the SQL store into the document store.
 *
 * NOT IMPLEMENTED - this is yours.
 *
 * Two things to know before you start:
 *  - the SQL store is not clean. It has been running without a uniqueness
 *    guarantee for a long time
 *  - this will be run more than once, including after a partial failure
 */
public final class Backfill {

    private final SqlLedgerStore source;
    private final DocumentStore target;

    public Backfill(SqlLedgerStore source, DocumentStore target) {
        this.source = source;
        this.target = target;
    }

    public Result run() {
        List<NormalizedTxn> rows = source.all();
        Map<String, NormalizedTxn> unique = new LinkedHashMap<>();
        for (NormalizedTxn t : rows) unique.putIfAbsent(key(t), t);
        for (NormalizedTxn t : unique.values()) target.save(t);
        return new Result(rows.size(), unique.size(), rows.size() - unique.size());
    }

    private static String key(NormalizedTxn t) {
        return t.accountLast4() + "|" + t.occurredAt().toInstant().toEpochMilli()
                + "|" + t.direction() + "|" + t.amount().toPlainString();
    }

    public record Result(long read, long written, long skipped) {}
}
