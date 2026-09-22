package in.simplifymoney.ledgersync.parse;

import java.math.BigDecimal;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rupee amounts as banks write them.
 *
 * Handles the prefixes we see in practice - "Rs.", "Rs ", "INR " - and strips
 * the thousands separators before handing back a BigDecimal.
 */
public final class Amounts {

    private Amounts() {}

    /**
     * INC-2026-09-11: this used to be ([0-9,]+\.[0-9]{2}) - it only matched a
     * figure written with exactly two decimals. Banks routinely write whole
     * rupees without any decimals ("Rs.5", "INR 18,000"), so on those messages
     * the FIRST match was not the transaction at all but the two-decimal
     * balance quoted later in the same body. That is how a Rs.5 water-can
     * debit was recorded as Rs.92,213.10. Amounts are now read with an
     * optional fractional part.
     */
    private static final Pattern AMOUNT =
            Pattern.compile("(?:Rs\\.?|INR)\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)");

    private static final Pattern BALANCE = Pattern.compile(
            "(?:Avl\\s*Bal|Available\\s*Balance|BalAvl|Avl\\s*Limit)\\s*:?\\s*"
                    + "(?:Rs\\.?|INR)\\s*([0-9,]+\\.[0-9]{2})",
            Pattern.CASE_INSENSITIVE);

    /** The transaction amount: the first rupee figure in the message. */
    public static BigDecimal first(String body) {
        Matcher m = AMOUNT.matcher(body);
        if (!m.find()) return null;
        return toDecimal(m.group(1));
    }

    /** The balance the bank quoted, if it quoted one. */
    public static BigDecimal statedBalance(String body) {
        Matcher m = BALANCE.matcher(body);
        if (!m.find()) return null;
        return toDecimal(m.group(1));
    }

    /** Also used by parsers that capture the amount inline (ICICI V2). */
    static BigDecimal toDecimal(String raw) {
        return new BigDecimal(raw.replace(",", "")).setScale(2);
    }
}
