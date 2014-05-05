package nxt;

import java.util.Calendar;
import java.util.TimeZone;

public final class Constants {

    public static final int BLOCK_HEADER_LENGTH = 224;
    public static final int MAX_NUMBER_OF_TRANSACTIONS = 255;
    public static final int MAX_PAYLOAD_LENGTH = MAX_NUMBER_OF_TRANSACTIONS * 128;
    public static final long MAX_BALANCE = 10000000000L;//设置nas总量
    public static final long INITIAL_BASE_TARGET = 153722867;
    public static final long MAX_BASE_TARGET = MAX_BALANCE * INITIAL_BASE_TARGET;

    public static final int MAX_ALIAS_URI_LENGTH = 1000;
    public static final int MAX_ALIAS_LENGTH = 100;
    public static final int MAX_ARBITRARY_MESSAGE_LENGTH = 1000;
    public static final long MAX_ASSET_QUANTITY = 1000000000;
    public static final int ASSET_ISSUANCE_FEE = 1000;
    public static final int MAX_POLL_NAME_LENGTH = 100;
    public static final int MAX_POLL_DESCRIPTION_LENGTH = 1000;
    public static final int MAX_POLL_OPTION_LENGTH = 100;

    public static final boolean isTestnet = Nxt.getBooleanProperty("nas.isTestnet");

    public static final int ALIAS_SYSTEM_BLOCK = 0;
    public static final int TRANSPARENT_FORGING_BLOCK = 1;
    public static final int ARBITRARY_MESSAGES_BLOCK = 0;
    public static final int TRANSPARENT_FORGING_BLOCK_2 = 1441;
    public static final int TRANSPARENT_FORGING_BLOCK_3 = 2881;
    public static final int TRANSPARENT_FORGING_BLOCK_4 = 4321;
    public static final int TRANSPARENT_FORGING_BLOCK_5 = 5760;
    public static final int ASSET_EXCHANGE_BLOCK = isTestnet ? 0 : 211111;
    public static final int VOTING_SYSTEM_BLOCK = isTestnet ? 0 : 222222;

    public static final long EPOCH_BEGINNING;
    static {
        Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        calendar.set(Calendar.YEAR, 2014);
        calendar.set(Calendar.MONTH, Calendar.MARCH);
        calendar.set(Calendar.DAY_OF_MONTH, 25);
        calendar.set(Calendar.HOUR_OF_DAY, 12);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        EPOCH_BEGINNING = calendar.getTimeInMillis();
    }

    public static final String ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyz";

    static void init() {}

    private Constants() {} // never

}
