package in.simplifymoney.ledgersync.report;

import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import in.simplifymoney.ledgersync.parse.ParsedTxn;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** Builds the ledger, summary, and reconciliation output documents. */
public final class Reports {
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

    private Reports() {}

    public static Map<String, Object> summary(List<NormalizedTxn> ledger) {
        Map<String, Object> accounts = new LinkedHashMap<>();
        for (String accountId : new TreeSet<>(ledger.stream()
                .map(NormalizedTxn::accountLast4).toList())) {
            BigDecimal spend = ZERO;
            BigDecimal income = ZERO;
            BigDecimal microTotal = ZERO;
            BigDecimal transferredOut = ZERO;
            BigDecimal transferredIn = ZERO;
            long microCount = 0;
            for (NormalizedTxn txn : ledger) {
                if (!txn.accountLast4().equals(accountId)) continue;
                switch (txn.category()) {
                    case SPEND -> spend = spend.add(txn.amount());
                    case INCOME -> income = income.add(txn.amount());
                    case MICRO -> {
                        microCount++;
                        microTotal = microTotal.add(txn.amount());
                    }
                    case TRANSFER -> {
                        if (txn.direction() == Direction.DEBIT)
                            transferredOut = transferredOut.add(txn.amount());
                        else transferredIn = transferredIn.add(txn.amount());
                    }
                }
            }
            Map<String, Object> account = new LinkedHashMap<>();
            account.put("spend", spend.toPlainString());
            account.put("income", income.toPlainString());
            account.put("micro_count", microCount);
            account.put("micro_total", microTotal.toPlainString());
            account.put("transferred_out", transferredOut.toPlainString());
            account.put("transferred_in", transferredIn.toPlainString());
            accounts.put(accountId, account);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("accounts", accounts);
        return result;
    }

    public static Map<String, Object> ledgerDocument(List<NormalizedTxn> ledger) {
        List<Object> rows = ledger.stream().map(txn -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("account_last4", txn.accountLast4());
            row.put("occurred_at", txn.occurredAt().toString());
            row.put("direction", txn.direction().name().toLowerCase());
            row.put("amount", txn.amount().toPlainString());
            row.put("category", txn.category().name());
            row.put("merchant", txn.merchant());
            row.put("source_message_ids", txn.sourceMessageIds());
            return (Object) row;
        }).toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("transactions", rows);
        return result;
    }

    public static Map<String, Object> reconciliation(List<NormalizedTxn> ledger) {
        return reconciliation(ledger, List.of(), Map.of());
    }

    public static Map<String, Object> reconciliation(List<NormalizedTxn> ledger,
            List<ParsedTxn> evidence, Map<String, BigDecimal> openingBalances) {
        Map<String, NormalizedTxn> transactions = new HashMap<>();
        for (NormalizedTxn txn : ledger) transactions.put(key(txn), txn);

        Map<String, ParsedTxn> uniqueEvidence = new LinkedHashMap<>();
        for (ParsedTxn event : evidence) uniqueEvidence.putIfAbsent(key(event), event);

        Map<String, BigDecimal> running = new HashMap<>(openingBalances);
        List<Object> discrepancies = new ArrayList<>();
        for (ParsedTxn event : uniqueEvidence.values().stream()
                .filter(event -> openingBalances.containsKey(event.accountLast4()))
                .sorted(Comparator.comparing(ParsedTxn::occurredAt)
                        .thenComparing(ParsedTxn::sourceMessageId)).toList()) {
            if (!transactions.containsKey(key(event))) continue;
            BigDecimal balance = running.get(event.accountLast4());
            balance = event.direction() == Direction.DEBIT
                    ? balance.subtract(event.amount()) : balance.add(event.amount());
            running.put(event.accountLast4(), balance);
            if (event.statedBalance() == null || balance.compareTo(event.statedBalance()) == 0)
                continue;
            Map<String, Object> discrepancy = new LinkedHashMap<>();
            discrepancy.put("account_last4", event.accountLast4());
            discrepancy.put("occurred_at", event.occurredAt().toString());
            discrepancy.put("amount", event.amount().toPlainString());
            discrepancy.put("note", "derived balance " + balance.toPlainString()
                    + " differs from bank-stated balance "
                    + event.statedBalance().toPlainString());
            discrepancies.add(discrepancy);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("discrepancies", discrepancies);
        return result;
    }

    private static String key(NormalizedTxn txn) {
        return txn.accountLast4() + "|" + txn.occurredAt().toInstant().toEpochMilli()
                + "|" + txn.direction() + "|" + txn.amount().toPlainString();
    }

    private static String key(ParsedTxn event) {
        return event.accountLast4() + "|" + event.occurredAt().toInstant().toEpochMilli()
                + "|" + event.direction() + "|" + event.amount().toPlainString();
    }

    public static Map<Category, BigDecimal> byCategory(List<NormalizedTxn> ledger) {
        Map<Category, BigDecimal> result = new LinkedHashMap<>();
        for (Category category : Category.values()) result.put(category, ZERO);
        for (NormalizedTxn txn : ledger)
            result.put(txn.category(), result.get(txn.category()).add(txn.amount()));
        return result;
    }
}
