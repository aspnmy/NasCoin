package nxt.http;

import nxt.Account;
import nxt.Attachment;
import nxt.Constants;
import nxt.Genesis;
import nxt.Nxt;
import nxt.NxtException;
import nxt.Transaction;
import nxt.crypto.Crypto;
import nxt.util.Convert;
import org.json.simple.JSONObject;
import org.json.simple.JSONStreamAware;

import javax.servlet.http.HttpServletRequest;
import java.util.Arrays;

import static nxt.http.JSONResponses.INCORRECT_DEADLINE;
import static nxt.http.JSONResponses.INCORRECT_FEE;
import static nxt.http.JSONResponses.INCORRECT_REFERENCED_TRANSACTION;
import static nxt.http.JSONResponses.MISSING_DEADLINE;
import static nxt.http.JSONResponses.MISSING_FEE;
import static nxt.http.JSONResponses.MISSING_SECRET_PHRASE;
import static nxt.http.JSONResponses.NOT_ENOUGH_FUNDS;

abstract class CreateTransaction extends APIServlet.APIRequestHandler {

    private static String[] addCommonParameters(String[] parameters) {
        String[] result = Arrays.copyOf(parameters, parameters.length + 5);
        System.arraycopy(new String[]{"secretPhrase", "publicKey", "fee", "deadline", "referencedTransaction"}, 0,
                result, parameters.length, 5);
        return result;
    }

    CreateTransaction(String... parameters) {
        super(addCommonParameters(parameters));
    }

    final Account getAccount(HttpServletRequest req) {
        String secretPhrase = Convert.emptyToNull(req.getParameter("secretPhrase"));
        if (secretPhrase != null) {
            return Account.getAccount(Crypto.getPublicKey(secretPhrase));
        }
        String publicKeyString = Convert.emptyToNull(req.getParameter("publicKey"));
        if (publicKeyString == null) {
            return null;
        }
        try {
            return Account.getAccount(Convert.parseHexString(publicKeyString));
        } catch (RuntimeException e) {
            return null;
        }
    }

    final JSONStreamAware createTransaction(HttpServletRequest req, Account senderAccount, Attachment attachment)
        throws NxtException.ValidationException {
        return createTransaction(req, senderAccount, Genesis.CREATOR_ID, 0L, attachment);
    }

    final JSONStreamAware createTransaction(HttpServletRequest req, Account senderAccount, Long recipientId,
                                            Long amount, Attachment attachment) throws NxtException.ValidationException {
        String deadlineValue = req.getParameter("deadline");
        String feeValue = req.getParameter("fee");
        String referencedTransactionValue = Convert.emptyToNull(req.getParameter("referencedTransaction"));
        String secretPhrase = Convert.emptyToNull(req.getParameter("secretPhrase"));
        String publicKeyValue = Convert.emptyToNull(req.getParameter("publicKey"));

        if (secretPhrase == null && publicKeyValue == null) {
            return MISSING_SECRET_PHRASE;
        } else if (feeValue == null) {
            return MISSING_FEE;
        } else if (deadlineValue == null) {
            return MISSING_DEADLINE;
        }

        short deadline;
        try {
            deadline = Short.parseShort(deadlineValue);
            if (deadline < 1 || deadline > 1440) {
                return INCORRECT_DEADLINE;
            }
        } catch (NumberFormatException e) {
            return INCORRECT_DEADLINE;
        }

        int fee;
        try {
            fee = Integer.parseInt(feeValue);
            if (fee < minimumFee() || fee >= Constants.MAX_BALANCE) {
                return INCORRECT_FEE;
            }
        } catch (NumberFormatException e) {
            return INCORRECT_FEE;
        }

        if ((amount + fee) * 100L > senderAccount.getUnconfirmedBalance()) {
            return NOT_ENOUGH_FUNDS;
        }

        Long referencedTransaction;
        try {
            referencedTransaction = referencedTransactionValue == null ? null : Convert.parseUnsignedLong(referencedTransactionValue);
        } catch (RuntimeException e) {
            return INCORRECT_REFERENCED_TRANSACTION;
        }

        // shouldn't try to get publicKey from senderAccount as it may have not been set yet
        byte[] publicKey = secretPhrase != null ? Crypto.getPublicKey(secretPhrase) : Convert.parseHexString(publicKeyValue);

        Transaction transaction = attachment == null ?
                Nxt.getTransactionProcessor().newTransaction(deadline, publicKey, recipientId,
                        amount, fee, referencedTransaction)
                :
                Nxt.getTransactionProcessor().newTransaction(deadline, publicKey, recipientId,
                        amount, fee, referencedTransaction, attachment);

        JSONObject response = new JSONObject();
        if (secretPhrase != null) {
            transaction.sign(secretPhrase);
            Nxt.getTransactionProcessor().broadcast(transaction);
            response.put("transaction", transaction.getStringId());
        }
        response.put("transactionBytes", Convert.toHexString(transaction.getBytes()));
        response.put("hash", transaction.getHash());
        return response;

    }

    @Override
    final boolean requirePost() {
        return true;
    }

    int minimumFee() {
        return 1;
    }

}
