package in.simplifymoney.ledgersync.parse;

import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.RawMessage;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bank transaction alert emails, both HDFC (alerts@hdfcbank.net) and ICICI
 * (alerts@icicibank.com). Same sentence shape, so one parser handles both.
 *
 * The Date: header carries an explicit offset, usually +0530 but occasionally
 * +0000 (a mail server that stamps UTC). We honour the offset in the header and
 * then normalise to IST, because occurredAt is defined as when the bank says
 * the transaction happened, in IST - not when the mail server stamped it.
 *
 * These emails duplicate the SMS for the same transaction. IngestService folds
 * them into one ledger row via the dedupe key; this parser just has to agree
 * with the SMS parsers on account, time, direction and amount.
 */
public final class EmailParser implements MessageParser {

    private static final Pattern DATE = Pattern.compile(
            "^Date: \\w{3}, (\\d{2}) (\\w{3}) (\\d{4}) (\\d{2}):(\\d{2}):(\\d{2}) ([+-]\\d{4})$",
            Pattern.MULTILINE);

    private static final Pattern TXN = Pattern.compile(
            "account ending (?<acct>\\d{4}) has been (?<dir>credited|debited) with "
                    + "(?:Rs\\.?|INR)\\s*(?<amount>[0-9][0-9,]*(?:\\.[0-9]{1,2})?)");

    private static final Pattern MERCHANT =
            Pattern.compile("Merchant / Remarks:\\s*(?<merchant>[^\\r\\n]+)");

    private static final String[] MONTHS = {
            "Jan", "Feb", "Mar", "Apr", "May", "Jun",
            "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"
    };

    @Override
    public boolean supports(RawMessage m) {
        return "email".equals(m.channel());
    }

    @Override
    public Optional<ParsedTxn> parse(RawMessage m) {
        String body = m.body();

        Matcher txn = TXN.matcher(body);
        if (!txn.find()) return Optional.empty(); // marketing, digest, etc.

        Matcher date = DATE.matcher(body);
        if (!date.find()) return Optional.empty();

        OffsetDateTime at = parseDate(date);
        if (at == null) return Optional.empty();

        BigDecimal amount = Amounts.toDecimal(txn.group("amount"));
        Direction d = "debited".equals(txn.group("dir")) ? Direction.DEBIT : Direction.CREDIT;

        String merchant = "";
        Matcher merch = MERCHANT.matcher(body);
        if (merch.find()) merchant = merch.group("merchant").trim();

        return Optional.of(new ParsedTxn(txn.group("acct"), at, d, amount, merchant,
                Amounts.statedBalance(body), m.messageId()));
    }

    /** Header date with its own offset, normalised to IST. */
    private OffsetDateTime parseDate(Matcher date) {
        int month = -1;
        for (int i = 0; i < MONTHS.length; i++) {
            if (MONTHS[i].equalsIgnoreCase(date.group(2))) { month = i + 1; break; }
        }
        if (month < 0) return null;

        LocalDateTime local = LocalDateTime.of(
                Integer.parseInt(date.group(3)), month, Integer.parseInt(date.group(1)),
                Integer.parseInt(date.group(4)), Integer.parseInt(date.group(5)),
                Integer.parseInt(date.group(6)));

        String off = date.group(7);
        int sign = off.charAt(0) == '-' ? -1 : 1;
        ZoneOffset offset = ZoneOffset.ofHoursMinutes(
                sign * Integer.parseInt(off.substring(1, 3)),
                sign * Integer.parseInt(off.substring(3, 5)));

        return local.atOffset(offset).withOffsetSameInstant(Dates.IST);
    }
}
