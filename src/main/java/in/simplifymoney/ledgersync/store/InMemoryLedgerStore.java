package in.simplifymoney.ledgersync.store;

import in.simplifymoney.ledgersync.model.NormalizedTxn;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Used by SelfCheck and by tests. Keeps everything it is given. */
public final class InMemoryLedgerStore implements LedgerStore {

    private final Map<String, NormalizedTxn> rows = new LinkedHashMap<>();

    @Override
    public void save(NormalizedTxn txn) {
        String key = key(txn);
        NormalizedTxn existing = rows.get(key);
        if (existing == null) {
            rows.put(key, txn);
            return;
        }
        List<String> ids = new ArrayList<>(existing.sourceMessageIds());
        ids.addAll(txn.sourceMessageIds());
        rows.put(key, new NormalizedTxn(existing.accountLast4(), existing.occurredAt(),
                existing.direction(), existing.amount(), existing.category(), existing.merchant(),
                ids.stream().distinct().sorted().toList()));
    }

    @Override public List<NormalizedTxn> all() {
        return Collections.unmodifiableList(new ArrayList<>(rows.values()));
    }

    @Override public long count() { return rows.size(); }

    private static String key(NormalizedTxn txn) {
        return txn.accountLast4() + "|" + txn.occurredAt().toInstant().toEpochMilli()
                + "|" + txn.direction() + "|" + txn.amount().toPlainString();
    }
}
