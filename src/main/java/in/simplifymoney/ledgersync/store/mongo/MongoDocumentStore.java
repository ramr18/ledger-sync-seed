package in.simplifymoney.ledgersync.store.mongo;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Updates;
import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import in.simplifymoney.ledgersync.store.DocumentStore;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.Decimal128;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Sorts.descending;

/**
 * MongoDB-backed DocumentStore. Chosen over DynamoDB because local mongo comes
 * up from docker compose with no localstack/table-creation dance, the query
 * engine (totalDocsExamined/nReturned) reports examined-vs-returned directly,
 * and a single composite index per query is all this service needs.
 *
 * Document shape: one document per transaction, keyed on an id of
 * account|time|direction|amount|first-message. source_message_ids lives in the
 * document, which lets Q3 (message -> txn) be answered from an index on that
 * array without a second collection to keep in sync.
 */
public final class MongoDocumentStore implements DocumentStore, AutoCloseable {

    public static final String DB_NAME = "ledger";
    private final MongoClient client;
    private final MongoCollection<Document> txns;

    public MongoDocumentStore(String uri) {
        this.client = MongoClients.create(uri);
        MongoDatabase db = client.getDatabase(DB_NAME);
        this.txns = db.getCollection("transactions");
        txns.createIndex(Indexes.compoundIndex(
                Indexes.ascending("account"), Indexes.descending("occurred_at")),
                new IndexOptions().name("q1_account_time"));
        txns.createIndex(Indexes.ascending("source_message_ids"),
                new IndexOptions().name("q3_message"));
        // uniqueness guarantee the SQL store never had
        txns.createIndex(Indexes.ascending("txn_key"), new IndexOptions().name("u_key").unique(true));
    }

    public static MongoDocumentStore connect(String uri) {
        return new MongoDocumentStore(uri);
    }

    private Document toDoc(NormalizedTxn t) {
        return new Document("txn_key", t.accountLast4() + "|" + t.occurredAt().toInstant().toEpochMilli()
                        + "|" + t.direction() + "|" + t.amount().toPlainString())
                .append("account", t.accountLast4())
                .append("occurred_at", java.util.Date.from(t.occurredAt().toInstant()))
                .append("direction", t.direction().name())
                .append("amount", new Decimal128(t.amount()))
                .append("category", t.category().name())
                .append("merchant", t.merchant())
                .append("source_message_ids", t.sourceMessageIds());
    }

    private NormalizedTxn toTxn(Document d) {
        return new NormalizedTxn(
                d.getString("account"),
                java.time.OffsetDateTime.ofInstant(
                        ((java.util.Date) d.get("occurred_at")).toInstant(), ZoneOffset.of("+05:30")),
                Direction.valueOf(d.getString("direction")),
                amount(d.get("amount")),
                Category.valueOf(d.getString("category")),
                d.getString("merchant"),
                (List<String>) d.get("source_message_ids"));
    }

    private BigDecimal amount(Object value) {
        if (value instanceof Decimal128 decimal) return decimal.bigDecimalValue().setScale(2);
        return new BigDecimal(value.toString()).setScale(2);
    }

    @Override
    public List<NormalizedTxn> forAccountMonth(String accountLast4, YearMonth month) {
        ZoneOffset ist = ZoneOffset.ofHoursMinutes(5, 30);
        Instant start = month.atDay(1).atStartOfDay(ist).toInstant();
        Instant end = month.plusMonths(1).atDay(1).atStartOfDay(ist).toInstant();
        Bson filter = com.mongodb.client.model.Filters.and(eq("account", accountLast4),
                com.mongodb.client.model.Filters.gte("occurred_at", java.util.Date.from(start)),
                com.mongodb.client.model.Filters.lt("occurred_at", java.util.Date.from(end)));
        List<NormalizedTxn> out = new ArrayList<>();
        for (Document d : txns.find(filter).sort(descending("occurred_at"))) {
            out.add(toTxn(d));
        }
        return out;
    }

    @Override
    public Map<Category, BigDecimal> categoryTotals(String accountLast4) {
        var agg = txns.aggregate(List.of(
                new Document("$match", new Document("account", accountLast4)),
                new Document("$group", new Document("_id", "$category")
                    .append("total", new Document("$sum", "$amount"))));
        Map<String, BigDecimal> byCat = StreamSupport.stream(agg.spliterator(), false)
                .collect(Collectors.toMap(d -> d.getString("_id"),
                    d -> amount(d.get("total"))));
        Map<Category, BigDecimal> out = new java.util.EnumMap<>(Category.class);
        for (Category c : Category.values()) out.put(c, byCat.getOrDefault(c.name(), BigDecimal.ZERO.setScale(2)));
        return out;
    }

    @Override
    public Optional<NormalizedTxn> byMessageId(String messageId) {
        Document d = txns.find(eq("source_message_ids", messageId)).first();
        return d == null ? Optional.empty() : Optional.of(toTxn(d));
    }

    @Override
    public void save(NormalizedTxn txn) {
        String key = txn.accountLast4() + "|" + txn.occurredAt().toInstant().toEpochMilli()
            + "|" + txn.direction() + "|" + txn.amount().toPlainString();
        Document existing = txns.find(eq("txn_key", key)).first();
        if (existing == null) {
            txns.insertOne(toDoc(txn));
            return;
        }
        List<String> ids = new ArrayList<>((List<String>) existing.get("source_message_ids"));
        ids.addAll(txn.sourceMessageIds());
        String oldMerchant = existing.getString("merchant");
        txns.updateOne(eq("txn_key", key), Updates.combine(
            Updates.set("source_message_ids", ids.stream().distinct().sorted().toList()),
            Updates.set("merchant", oldMerchant == null || oldMerchant.isBlank()
                ? txn.merchant() : oldMerchant)));
    }

    /** Distinct documents present, for consistency checking. */
    public List<NormalizedTxn> all() {
        List<NormalizedTxn> out = new ArrayList<>();
        for (Document d : txns.find()) out.add(toTxn(d));
        return out;
    }

    @Override
    public void close() {
        client.close();
    }
}
