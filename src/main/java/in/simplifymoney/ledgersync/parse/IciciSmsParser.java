package in.simplifymoney.ledgersync.parse;

import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.RawMessage;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ICICI Bank SMS.
 *
 * Reads both ICICI shapes seen in production:
 *
 *   V1: "Dear Customer, Acct XX9075 is debited with INR 18,000 on 01/07/2026 21:14."
 *   V2: "ICICI Bank Acct XX9075 Dr INR 30.00 on 30-Jul-2026 17:12; UPI/KIRANA ref no ..."
 *
 * V2 (the Dr/Cr shorthand) was falling straight through until INC-2026-09-11
 * triage; every V2 message was being dropped.
 */
public final class IciciSmsParser implements MessageParser {

    public static final String SENDER = "VM-ICICIB-T";

    private static final Pattern V1 = Pattern.compile(
            "Acct XX(?<acct>\\d{4}) is (?<dir>debited|credited) with .*? "
                    + "on (?<when>\\d{2}/\\d{2}/\\d{4} \\d{2}:\\d{2})\\. "
                    + "Info: (?<merchant>[^.]+)\\.");

    private static final Pattern V2 = Pattern.compile(
            "ICICI Bank Acct XX(?<acct>\\d{4}) (?<dir>Dr|Cr) "
                    + "(?:Rs\\.?|INR)\\s*(?<amount>[0-9][0-9,]*(?:\\.[0-9]{1,2})?) "
                    + "on (?<when>\\d{2}-\\w{3}-\\d{4} \\d{2}:\\d{2}); "
                    + "(?<merchant>.+?)(?: ref no \\d+)?(?:\\. BalAvl|$)");

    @Override
    public boolean supports(RawMessage m) {
        return "sms".equals(m.channel()) && SENDER.equals(m.sender());
    }

    @Override
    public Optional<ParsedTxn> parse(RawMessage m) {
        Matcher v1 = V1.matcher(m.body());
        if (v1.find()) {
            BigDecimal amount = Amounts.first(m.body());
            OffsetDateTime at = Dates.ist(v1.group("when"));
            if (amount == null || at == null) return Optional.empty();
            Direction d = "debited".equals(v1.group("dir")) ? Direction.DEBIT : Direction.CREDIT;
            return Optional.of(new ParsedTxn(v1.group("acct"), at, d, amount,
                    v1.group("merchant").trim(), Amounts.statedBalance(m.body()),
                    m.messageId()));
        }

        // V2 quotes the amount inline; take it from the match, not from the body,
        // so a two-decimal balance quoted later can never win (see Amounts).
        Matcher v2 = V2.matcher(m.body());
        if (v2.find()) {
            BigDecimal amount = Amounts.toDecimal(v2.group("amount"));
            OffsetDateTime at = Dates.ist(v2.group("when"));
            if (at == null) return Optional.empty();
            Direction d = "Dr".equals(v2.group("dir")) ? Direction.DEBIT : Direction.CREDIT;
            return Optional.of(new ParsedTxn(v2.group("acct"), at, d, amount,
                    v2.group("merchant").trim(), Amounts.statedBalance(m.body()),
                    m.messageId()));
        }

        return Optional.empty();
    }
}
