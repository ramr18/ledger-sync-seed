package in.simplifymoney.ledgersync.ingest;

import in.simplifymoney.ledgersync.json.Json;
import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import in.simplifymoney.ledgersync.model.RawMessage;
import in.simplifymoney.ledgersync.parse.ParsedTxn;
import in.simplifymoney.ledgersync.parse.Parsers;
import in.simplifymoney.ledgersync.store.LedgerStore;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Reads a corpus of raw messages and puts transactions in the ledger.
 *
 * One transaction is not one message. The same underlying transaction reaches
 * us several times: the same SMS re-read from the inbox is uploaded again under
 * a new message_id, and both banks also send an email for the same
 * transaction. So parsing produces one ParsedTxn per message, and then we fold
 * them: messages that agree on (account, occurred-at, direction, amount) are
 * one transaction, and every one of them is cited as evidence for it.
 *
 * Categories: a UPI debit of Rs.100 or less is MICRO. Money that moved between
 * the user's OWN accounts is TRANSFER - both legs of it - because counting a
 * self-transfer as spending and income inflates both sides. The pairing is by
 * counterparty + amount + a small time window across two different accounts.
 */
public final class IngestService {

    /** A transfer's two legs are never further apart than this. */
    static final Duration TRANSFER_WINDOW = Duration.ofMinutes(5);

    /** UPI debits at or below this are MICRO. */
    static final BigDecimal MICRO_LIMIT = new BigDecimal("100.00");

    private final Parsers parsers;
    private final LedgerStore store;

    public IngestService(Parsers parsers, LedgerStore store) {
        this.parsers = parsers;
        this.store = store;
    }

    public Stats ingestFile(Path corpus) throws IOException {
        List<RawMessage> messages = readCorpus(corpus);
        List<ParsedTxn> parsed = parse(messages);
        int skipped = messages.size() - parsed.size();

        // 2. fold: one transaction, every message that evidences it
        List<NormalizedTxn> ledger = fold(parsed);

        // 3. write; the store is idempotent on the business key, so re-running
        //    an overlapping corpus changes nothing
        for (NormalizedTxn t : ledger) store.save(t);

        return new Stats(messages.size(), ledger.size(), skipped);
    }

    public List<ParsedTxn> parseFile(Path corpus) throws IOException {
        return parse(readCorpus(corpus));
    }

    private List<ParsedTxn> parse(List<RawMessage> messages) {
        List<ParsedTxn> parsed = new ArrayList<>();
        for (RawMessage message : messages) {
            Optional<ParsedTxn> transaction = parsers.parse(message);
            transaction.ifPresent(parsed::add);
        }
        return parsed;
    }

    /** Groups agreeing parsed messages into one transaction, then categorises. */
    static List<NormalizedTxn> fold(List<ParsedTxn> parsed) {
        // sort so the fold is deterministic regardless of corpus order
        List<ParsedTxn> sorted = parsed.stream()
                .sorted(Comparator.comparing(ParsedTxn::occurredAt)
                        .thenComparing(ParsedTxn::sourceMessageId))
                .collect(Collectors.toList());

        Map<Key, List<ParsedTxn>> groups = new LinkedHashMap<>();
        for (ParsedTxn p : sorted) groups.computeIfAbsent(Key.of(p), k -> new ArrayList<>()).add(p);

        List<NormalizedTxn> out = new ArrayList<>();
        for (Map.Entry<Key, List<ParsedTxn>> e : groups.entrySet()) {
            List<ParsedTxn> evidence = e.getValue();
            ParsedTxn first = evidence.get(0);
            List<String> ids = evidence.stream()
                    .map(ParsedTxn::sourceMessageId).sorted().distinct().toList();
            String merchant = evidence.stream().map(ParsedTxn::merchant)
                    .filter(s -> s != null && !s.isBlank()).findFirst().orElse("");
            out.add(new NormalizedTxn(e.getKey().account(), e.getKey().at(), first.direction(),
                    first.amount(), first.direction() == Direction.DEBIT
                            ? Category.SPEND : Category.INCOME, merchant, ids));
        }

        categorise(out);
        return out;
    }

    /** MICRO first, then TRANSFER (both legs); whatever is left is SPEND/INCOME. */
    private static void categorise(List<NormalizedTxn> ledger) {
        for (int i = 0; i < ledger.size(); i++) {
            NormalizedTxn t = ledger.get(i);
            if (t.direction() == Direction.DEBIT
                    && t.merchant() != null && t.merchant().toUpperCase().startsWith("UPI")
                    && t.amount().compareTo(MICRO_LIMIT) <= 0) {
                ledger.set(i, replace(t, Category.MICRO));
            }
        }
        pairTransfers(ledger);
    }

    private static void pairTransfers(List<NormalizedTxn> ledger) {
        for (int i = 0; i < ledger.size(); i++) {
            NormalizedTxn debit = ledger.get(i);
            if (debit.direction() != Direction.DEBIT || debit.category() == Category.TRANSFER)
                continue;
            for (int j = 0; j < ledger.size(); j++) {
                NormalizedTxn credit = ledger.get(j);
                if (credit.direction() != Direction.CREDIT
                        || credit.category() == Category.TRANSFER) continue;
                if (credit.accountLast4().equals(debit.accountLast4())) continue;
                if (credit.amount().compareTo(debit.amount()) != 0) continue;
                if (!sameCounterparty(credit.merchant(), debit.merchant())) continue;
                Duration gap = Duration.between(debit.occurredAt(), credit.occurredAt()).abs();
                if (gap.compareTo(TRANSFER_WINDOW) > 0) continue;
                ledger.set(i, replace(debit, Category.TRANSFER));
                ledger.set(j, replace(credit, Category.TRANSFER));
                break;
            }
        }
    }

    /**
     * Is the credit the same money as the debit? Both banks name the
     * counterparty their own way, so compare loosely: strip the channel
     * prefix (IMPS/P2A/, UPI/...), case, punctuation, and accept a match on
     * the remaining name.
     */
    static boolean sameCounterparty(String a, String b) {
        if (a == null || b == null || a.isBlank() || b.isBlank()) return false;
        String sa = normaliseMerchant(a), sb = normaliseMerchant(b);
        return sa.equals(sb);
    }

    private static String normaliseMerchant(String m) {
        return m.toUpperCase()
                .replaceAll("^(IMPS|NEFT|UPI|RTGS)/([PNR]?\\d?/[AT]?/?)?", "")
                .replaceAll("\\b(INWARD|OUTWARD)\\b", " ")
                .replaceAll("[^A-Z0-9 ]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static NormalizedTxn replace(NormalizedTxn t, Category c) {
        return new NormalizedTxn(t.accountLast4(), t.occurredAt(), t.direction(),
                t.amount(), c, t.merchant(), t.sourceMessageIds());
    }

    /** The identity of a real transaction, independent of which message said so. */
    record Key(String account, OffsetDateTime at, Direction direction, BigDecimal amount) {
        static Key of(ParsedTxn p) {
            return new Key(p.accountLast4(), p.occurredAt(), p.direction(), p.amount());
        }
    }

    public static List<RawMessage> readCorpus(Path corpus) throws IOException {
        List<RawMessage> out = new ArrayList<>();
        try (Stream<String> lines = Files.lines(corpus)) {
            for (String line : (Iterable<String>) lines.filter(s -> !s.isBlank())::iterator) {
                Map<String, Object> o = Json.parseObject(line);
                out.add(new RawMessage(
                        (String) o.get("message_id"),
                        (String) o.get("channel"),
                        (String) o.get("sender"),
                        OffsetDateTime.parse((String) o.get("received_at")),
                        (String) o.get("device_id"),
                        (String) o.get("body")));
            }
        }
        return out;
    }

    public record Stats(int messagesRead, int transactionsWritten, int messagesSkipped) {}
}
