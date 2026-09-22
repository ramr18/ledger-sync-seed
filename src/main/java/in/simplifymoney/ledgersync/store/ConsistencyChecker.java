package in.simplifymoney.ledgersync.store;

import in.simplifymoney.ledgersync.model.NormalizedTxn;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/**
 * Proves the two stores agree, and says precisely where they do not.
 *
 * NOT IMPLEMENTED - this is yours.
 *
 * We will run your checker against a document store we have deliberately
 * altered. It has to find what we changed and name it. A checker that only
 * compares row counts will not.
 */
public final class ConsistencyChecker {

    private final SqlLedgerStore sql;
    private final DocumentStore documents;

    public ConsistencyChecker(SqlLedgerStore sql, DocumentStore documents) {
        this.sql = sql;
        this.documents = documents;
    }

    public List<Divergence> check() {
        Map<String, NormalizedTxn> left = byKey(sql.all());
        Map<String, NormalizedTxn> right = byKey(documents.all());
        List<Divergence> out = new ArrayList<>();
        for (String key : new TreeSet<>(left.keySet())) {
            if (!right.containsKey(key)) {
                out.add(new Divergence(key, "present", "absent"));
                continue;
            }
            compare(key, left.get(key), right.get(key), out);
        }
        for (String key : new TreeSet<>(right.keySet())) {
            if (!left.containsKey(key)) out.add(new Divergence(key, "absent", "present"));
        }
        return out;
    }

    private static void compare(String key, NormalizedTxn sql, NormalizedTxn doc,
                                List<Divergence> out) {
        if (!Objects.equals(sql.category(), doc.category())) out.add(new Divergence(
                key + ".category", sql.category().name(), doc.category().name()));
        if (!Objects.equals(sql.merchant(), doc.merchant())) out.add(new Divergence(
                key + ".merchant", sql.merchant(), doc.merchant()));
        if (!new java.util.HashSet<>(sql.sourceMessageIds()).equals(
                new java.util.HashSet<>(doc.sourceMessageIds()))) out.add(new Divergence(
                key + ".source_message_ids", String.join(",", sql.sourceMessageIds()),
                String.join(",", doc.sourceMessageIds())));
    }

    private static Map<String, NormalizedTxn> byKey(List<NormalizedTxn> rows) {
        Map<String, NormalizedTxn> out = new HashMap<>();
        for (NormalizedTxn t : rows) out.put(key(t), t);
        return out;
    }

    private static String key(NormalizedTxn t) {
        return t.accountLast4() + "|" + t.occurredAt().toInstant().toEpochMilli()
                + "|" + t.direction() + "|" + t.amount().toPlainString();
    }

    /** One place the two stores disagree. */
    public record Divergence(String what, String inSql, String inDocuments) {}
}
