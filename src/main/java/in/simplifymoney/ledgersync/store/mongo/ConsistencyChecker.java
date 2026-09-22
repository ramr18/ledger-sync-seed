package in.simplifymoney.ledgersync.store.mongo;

import in.simplifymoney.ledgersync.model.NormalizedTxn;
import in.simplifymoney.ledgersync.store.SqlLedgerStore;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Compares SQL and the document store field-by-field, per transaction, and
 * reports exactly which documents differ and how. A row-count comparison would
 * pass a store with a silently altered amount; this will not.
 */
public final class ConsistencyChecker {

    public record Diff(String txnKey, String field, String sqlValue, String docValue) {}

    public List<Diff> check(SqlLedgerStore sql, MongoDocumentStore doc) {
        Map<String, NormalizedTxn> sqlBy = key(sql.findAll());
        Map<String, NormalizedTxn> docBy = key(doc.all());
        List<Diff> diffs = new ArrayList<>();
        for (String k : new TreeSet<>(sqlBy.keySet())) {
            NormalizedTxn s = sqlBy.get(k);
            NormalizedTxn d = docBy.get(k);
            if (d == null) { diffs.add(new Diff(k, "MISSING_IN_DOC", "present", "absent")); continue; }
            if (cmp(s.amount(), d.amount()))
                diffs.add(new Diff(k, "amount", s.amount().toPlainString(), d.amount().toPlainString()));
            if (!s.category().equals(d.category()))
                diffs.add(new Diff(k, "category", s.category().name(), d.category().name()));
            if (!s.direction().equals(d.direction()))
                diffs.add(new Diff(k, "direction", s.direction().name(), d.direction().name()));
            if (!s.occurredAt().toInstant().equals(d.occurredAt().toInstant()))
                diffs.add(new Diff(k, "occurred_at",
                        s.occurredAt().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                        d.occurredAt().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)));
            if (!s.accountLast4().equals(d.accountLast4()))
                diffs.add(new Diff(k, "account", s.accountLast4(), d.accountLast4()));
            if (!new HashSet<>(s.sourceMessageIds()).equals(new HashSet<>(d.sourceMessageIds())))
                diffs.add(new Diff(k, "source_message_ids", setOf(s), setOf(d)));
        }
        for (String k : docBy.keySet())
            if (!sqlBy.containsKey(k))
                diffs.add(new Diff(k, "EXTRA_IN_DOC", "absent", "present"));
        return diffs;
    }

    private boolean cmp(BigDecimal a, BigDecimal b) { return a.compareTo(b) != 0; }

    private String setOf(NormalizedTxn t) {
        return t.sourceMessageIds().stream().sorted().collect(Collectors.joining(","));
    }

    private Map<String, NormalizedTxn> key(List<NormalizedTxn> txns) {
        Map<String, NormalizedTxn> m = new HashMap<>();
        for (NormalizedTxn t : txns)
            m.put(t.accountLast4() + "|" + t.occurredAt().toInstant().toEpochMilli()
                    + "|" + t.direction() + "|" + t.amount().toPlainString(), t);
        return m;
    }
}
