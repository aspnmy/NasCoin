package nxt;

import nxt.util.Convert;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

final class TransactionDb {

    static Transaction findTransaction(Long transactionId) {
        try (Connection con = Db.getConnection();
             PreparedStatement pstmt = con.prepareStatement("SELECT * FROM transaction WHERE id = ?")) {
            pstmt.setLong(1, transactionId);
            ResultSet rs = pstmt.executeQuery();
            Transaction transaction = null;
            if (rs.next()) {
                transaction = loadTransaction(con, rs);
            }
            rs.close();
            return transaction;
        } catch (SQLException e) {
            throw new RuntimeException(e.toString(), e);
        } catch (NxtException.ValidationException e) {
            throw new RuntimeException("Transaction already in database, id = " + transactionId + ", does not pass validation!");
        }
    }

    static Transaction findTransaction(String hash) {
        try (Connection con = Db.getConnection();
             PreparedStatement pstmt = con.prepareStatement("SELECT * FROM transaction WHERE hash = ?")) {
            pstmt.setBytes(1, Convert.parseHexString(hash));
            ResultSet rs = pstmt.executeQuery();
            Transaction transaction = null;
            if (rs.next()) {
                transaction = loadTransaction(con, rs);
            }
            rs.close();
            return transaction;
        } catch (SQLException e) {
            throw new RuntimeException(e.toString(), e);
        } catch (NxtException.ValidationException e) {
            throw new RuntimeException("Transaction already in database, hash = " + hash + ", does not pass validation!");
        }
    }

    static boolean hasTransaction(Long transactionId) {
        try (Connection con = Db.getConnection();
             PreparedStatement pstmt = con.prepareStatement("SELECT 1 FROM transaction WHERE id = ?")) {
            pstmt.setLong(1, transactionId);
            ResultSet rs = pstmt.executeQuery();
            return rs.next();
        } catch (SQLException e) {
            throw new RuntimeException(e.toString(), e);
        }
    }

    static TransactionImpl loadTransaction(Connection con, ResultSet rs) throws NxtException.ValidationException {
        try {

            byte type = rs.getByte("type");
            byte subtype = rs.getByte("subtype");
            int timestamp = rs.getInt("timestamp");
            short deadline = rs.getShort("deadline");
            byte[] senderPublicKey = rs.getBytes("sender_public_key");
            Long recipientId = rs.getLong("recipient_id");
            Long amount = rs.getLong("amount");
            int fee = rs.getInt("fee");
            Long referencedTransactionId = rs.getLong("referenced_transaction_id");
            if (rs.wasNull()) {
                referencedTransactionId = null;
            }
            byte[] signature = rs.getBytes("signature");
            Long blockId = rs.getLong("block_id");
            int height = rs.getInt("height");
            Long id = rs.getLong("id");
            Long senderId = rs.getLong("sender_id");
            Attachment attachment = (Attachment)rs.getObject("attachment");
            byte[] hash = rs.getBytes("hash");
            int blockTimestamp = rs.getInt("block_timestamp");

            TransactionType transactionType = TransactionType.findTransactionType(type, subtype);
            return new TransactionImpl(transactionType, timestamp, deadline, senderPublicKey, recipientId, amount, fee,
                    referencedTransactionId, signature, blockId, height, id, senderId, attachment, hash, blockTimestamp);

        } catch (SQLException e) {
            throw new RuntimeException(e.toString(), e);
        }
    }

    static List<TransactionImpl> findBlockTransactions(Connection con, Long blockId) {
        List<TransactionImpl> list = new ArrayList<>();
        try (PreparedStatement pstmt = con.prepareStatement("SELECT * FROM transaction WHERE block_id = ? ORDER BY id")) {
            pstmt.setLong(1, blockId);
            ResultSet rs = pstmt.executeQuery();
            while (rs.next()) {
                list.add(loadTransaction(con, rs));
            }
            rs.close();
            return list;
        } catch (SQLException e) {
            throw new RuntimeException(e.toString(), e);
        } catch (NxtException.ValidationException e) {
            throw new RuntimeException("Transaction already in database for block_id = " + blockId + " does not pass validation!");
        }
    }

    static void saveTransactions(Connection con, List<TransactionImpl> transactions) {
        try {
            for (Transaction transaction : transactions) {
                try (PreparedStatement pstmt = con.prepareStatement("INSERT INTO transaction (id, deadline, sender_public_key, recipient_id, "
                        + "amount, fee, referenced_transaction_id, height, block_id, signature, timestamp, type, subtype, sender_id, attachment, "
                        + "hash, block_timestamp) "
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                    int i = 0;
                    pstmt.setLong(++i, transaction.getId());
                    pstmt.setShort(++i, transaction.getDeadline());
                    pstmt.setBytes(++i, transaction.getSenderPublicKey());
                    pstmt.setLong(++i, transaction.getRecipientId());
                    pstmt.setLong(++i, transaction.getAmount());
                    pstmt.setInt(++i, transaction.getFee());
                    if (transaction.getReferencedTransactionId() != null) {
                        pstmt.setLong(++i, transaction.getReferencedTransactionId());
                    } else {
                        pstmt.setNull(++i, Types.BIGINT);
                    }
                    pstmt.setInt(++i, transaction.getHeight());
                    pstmt.setLong(++i, transaction.getBlockId());
                    pstmt.setBytes(++i, transaction.getSignature());
                    pstmt.setInt(++i, transaction.getTimestamp());
                    pstmt.setByte(++i, transaction.getType().getType());
                    pstmt.setByte(++i, transaction.getType().getSubtype());
                    pstmt.setLong(++i, transaction.getSenderId());
                    if (transaction.getAttachment() != null) {
                        pstmt.setObject(++i, transaction.getAttachment());
                    } else {
                        pstmt.setNull(++i, Types.JAVA_OBJECT);
                    }
                    pstmt.setBytes(++i, Convert.parseHexString(transaction.getHash()));
                    pstmt.setInt(++i, transaction.getBlockTimestamp());
                    pstmt.executeUpdate();
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e.toString(), e);
        }
    }

}
