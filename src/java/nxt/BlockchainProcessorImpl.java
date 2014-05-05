package nxt;

import nxt.crypto.Crypto;
import nxt.peer.Peer;
import nxt.peer.Peers;
import nxt.util.Convert;
import nxt.util.DbIterator;
import nxt.util.JSON;
import nxt.util.Listener;
import nxt.util.Listeners;
import nxt.util.Logger;
import nxt.util.ThreadPool;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.JSONStreamAware;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;

final class BlockchainProcessorImpl implements BlockchainProcessor {

    private static final byte[] CHECKSUM_TRANSPARENT_FORGING = new byte[]{27, -54, -59, -98, 49, -42, 48, -68, -112, 49, 41, 94, -41, 78, -84, 27, -87, -22, -28, 36, -34, -90, 112, -50, -9, 5, 89, -35, 80, -121, -128, 112};

    private static final BlockchainProcessorImpl instance = new BlockchainProcessorImpl();

    static BlockchainProcessorImpl getInstance() {
        return instance;
    }

    private final BlockchainImpl blockchain = BlockchainImpl.getInstance();
    private final TransactionProcessorImpl transactionProcessor = TransactionProcessorImpl.getInstance();

    private final Listeners<Block, Event> blockListeners = new Listeners<>();
    private volatile Peer lastBlockchainFeeder;

    private final Runnable getMoreBlocksThread = new Runnable() {

        private final JSONStreamAware getCumulativeDifficultyRequest;

        {
            JSONObject request = new JSONObject();
            request.put("requestType", "getCumulativeDifficulty");
            getCumulativeDifficultyRequest = JSON.prepareRequest(request);
        }

        private boolean peerHasMore;

        @Override
        public void run() {

            try {
                try {
                    peerHasMore = true;
                    Peer peer = Peers.getAnyPeer(Peer.State.CONNECTED, true);
                    if (peer == null) {
                        return;
                    }
                    lastBlockchainFeeder = peer;
                    JSONObject response = peer.send(getCumulativeDifficultyRequest);
                    if (response == null) {
                        return;
                    }
                    BigInteger curCumulativeDifficulty = blockchain.getLastBlock().getCumulativeDifficulty();
                    String peerCumulativeDifficulty = (String) response.get("cumulativeDifficulty");
                    if (peerCumulativeDifficulty == null) {
                        return;
                    }
                    BigInteger betterCumulativeDifficulty = new BigInteger(peerCumulativeDifficulty);
                    if (betterCumulativeDifficulty.compareTo(curCumulativeDifficulty) <= 0) {
                        return;
                    }

                    Long commonBlockId = Genesis.GENESIS_BLOCK_ID;

                    if (! blockchain.getLastBlock().getId().equals(Genesis.GENESIS_BLOCK_ID)) {
                        commonBlockId = getCommonMilestoneBlockId(peer);
                    }
                    if (commonBlockId == null || !peerHasMore) {
                        return;
                    }

                    commonBlockId = getCommonBlockId(peer, commonBlockId);
                    if (commonBlockId == null || !peerHasMore) {
                        return;
                    }

                    final Block commonBlock = BlockDb.findBlock(commonBlockId);
                    if (blockchain.getLastBlock().getHeight() - commonBlock.getHeight() >= 720) {
                        return;
                    }

                    Long currentBlockId = commonBlockId;
                    List<BlockImpl> forkBlocks = new ArrayList<>();

                    while (true) {

                        JSONArray nextBlocks = getNextBlocks(peer, currentBlockId);
                        if (nextBlocks == null || nextBlocks.size() == 0) {
                            break;
                        }

                        synchronized (blockchain) {

                            for (Object o : nextBlocks) {
                                JSONObject blockData = (JSONObject) o;
                                BlockImpl block;
                                try {
                                    block = parseBlock(blockData);
                                } catch (NxtException.ValidationException e) {
                                    peer.blacklist(e);
                                    return;
                                }
                                currentBlockId = block.getId();

                                if (blockchain.getLastBlock().getId().equals(block.getPreviousBlockId())) {
                                    try {

                                        pushBlock(block);

                                    } catch (BlockNotAcceptedException e) {
                                        peer.blacklist(e);
                                        return;
                                    }
                                } else if (! BlockDb.hasBlock(block.getId())) {

                                    forkBlocks.add(block);

                                }

                            }

                        } //synchronized

                    }

                    if (! forkBlocks.isEmpty() && blockchain.getLastBlock().getHeight() - commonBlock.getHeight() < 720) {
                        processFork(peer, forkBlocks, commonBlock);
                    }

                } catch (Exception e) {
                    Logger.logDebugMessage("Error in milestone blocks processing thread", e);
                }
            } catch (Throwable t) {
                Logger.logMessage("CRITICAL ERROR. PLEASE REPORT TO THE DEVELOPERS.\n" + t.toString());
                t.printStackTrace();
                System.exit(1);
            }

        }

        private Long getCommonMilestoneBlockId(Peer peer) {

            String lastMilestoneBlockId = null;

            while (true) {
                JSONObject milestoneBlockIdsRequest = new JSONObject();
                milestoneBlockIdsRequest.put("requestType", "getMilestoneBlockIds");
                if (lastMilestoneBlockId == null) {
                    milestoneBlockIdsRequest.put("lastBlockId", blockchain.getLastBlock().getStringId());
                } else {
                    milestoneBlockIdsRequest.put("lastMilestoneBlockId", lastMilestoneBlockId);
                }

                JSONObject response = peer.send(JSON.prepareRequest(milestoneBlockIdsRequest));
                if (response == null) {
                    return null;
                }
                JSONArray milestoneBlockIds = (JSONArray) response.get("milestoneBlockIds");
                if (milestoneBlockIds == null) {
                    return null;
                }
                if (milestoneBlockIds.isEmpty()) {
                    return Genesis.GENESIS_BLOCK_ID;
                }
                // prevent overloading with blockIds
                if (milestoneBlockIds.size() > 20) {
                    Logger.logDebugMessage("Obsolete or rogue peer " + peer.getPeerAddress() + " sends too many milestoneBlockIds, blacklisting");
                    peer.blacklist();
                    return null;
                }
                if (Boolean.TRUE.equals(response.get("last"))) {
                    peerHasMore = false;
                }
                for (Object milestoneBlockId : milestoneBlockIds) {
                    Long blockId = Convert.parseUnsignedLong((String) milestoneBlockId);
                    if (BlockDb.hasBlock(blockId)) {
                        if (lastMilestoneBlockId == null && milestoneBlockIds.size() > 1) {
                            peerHasMore = false;
                        }
                        return blockId;
                    }
                    lastMilestoneBlockId = (String) milestoneBlockId;
                }
            }

        }

        private Long getCommonBlockId(Peer peer, Long commonBlockId) {

            while (true) {
                JSONObject request = new JSONObject();
                request.put("requestType", "getNextBlockIds");
                request.put("blockId", Convert.toUnsignedLong(commonBlockId));
                JSONObject response = peer.send(JSON.prepareRequest(request));
                if (response == null) {
                    return null;
                }
                JSONArray nextBlockIds = (JSONArray) response.get("nextBlockIds");
                if (nextBlockIds == null || nextBlockIds.size() == 0) {
                    return null;
                }
                // prevent overloading with blockIds
                if (nextBlockIds.size() > 1440) {
                    Logger.logDebugMessage("Obsolete or rogue peer " + peer.getPeerAddress() + " sends too many nextBlockIds, blacklisting");
                    peer.blacklist();
                    return null;
                }

                for (Object nextBlockId : nextBlockIds) {
                    Long blockId = Convert.parseUnsignedLong((String) nextBlockId);
                    if (! BlockDb.hasBlock(blockId)) {
                        return commonBlockId;
                    }
                    commonBlockId = blockId;
                }
            }

        }

        private JSONArray getNextBlocks(Peer peer, Long curBlockId) {

            JSONObject request = new JSONObject();
            request.put("requestType", "getNextBlocks");
            request.put("blockId", Convert.toUnsignedLong(curBlockId));
            JSONObject response = peer.send(JSON.prepareRequest(request));
            if (response == null) {
                return null;
            }

            JSONArray nextBlocks = (JSONArray) response.get("nextBlocks");
            if (nextBlocks == null) {
                return null;
            }
            // prevent overloading with blocks
            if (nextBlocks.size() > 1440) {
                Logger.logDebugMessage("Obsolete or rogue peer " + peer.getPeerAddress() + " sends too many nextBlocks, blacklisting");
                peer.blacklist();
                return null;
            }

            return nextBlocks;

        }

        private void processFork(Peer peer, final List<BlockImpl> forkBlocks, final Block commonBlock) {

            synchronized (blockchain) {
                BigInteger curCumulativeDifficulty = blockchain.getLastBlock().getCumulativeDifficulty();
                boolean needsRescan;

                try {
                    while (! blockchain.getLastBlock().getId().equals(commonBlock.getId()) && popLastBlock()) {
                    }

                    if (blockchain.getLastBlock().getId().equals(commonBlock.getId())) {
                        for (BlockImpl block : forkBlocks) {
                            if (blockchain.getLastBlock().getId().equals(block.getPreviousBlockId())) {
                                try {
                                    pushBlock(block);
                                } catch (BlockNotAcceptedException e) {
                                    peer.blacklist(e);
                                    break;
                                }
                            }
                        }
                    }

                    needsRescan = blockchain.getLastBlock().getCumulativeDifficulty().compareTo(curCumulativeDifficulty) < 0;
                    if (needsRescan) {
                        Logger.logDebugMessage("Rescan caused by peer " + peer.getPeerAddress() + ", blacklisting");
                        peer.blacklist();
                    }
                } catch (TransactionType.UndoNotSupportedException e) {
                    Logger.logDebugMessage(e.getMessage());
                    Logger.logDebugMessage("Popping off last block not possible, will do a rescan");
                    needsRescan = true;
                }

                if (needsRescan) {
                    // this relies on the database cascade trigger to delete all blocks after commonBlock
                    if (commonBlock.getNextBlockId() != null) {
                        Logger.logDebugMessage("Last block is " + blockchain.getLastBlock().getStringId() + " at " + blockchain.getLastBlock().getHeight());
                        Logger.logDebugMessage("Deleting blocks after height " + commonBlock.getHeight());
                        BlockDb.deleteBlock(commonBlock.getNextBlockId());
                    }
                    Logger.logMessage("Will do a re-scan");
                    blockListeners.notify(commonBlock, BlockchainProcessor.Event.RESCAN_BEGIN);
                    scan();
                    blockListeners.notify(commonBlock, BlockchainProcessor.Event.RESCAN_END);
                    Logger.logDebugMessage("Last block is " + blockchain.getLastBlock().getStringId() + " at " + blockchain.getLastBlock().getHeight());
                }
            }

        }

    };

    private BlockchainProcessorImpl() {

        blockListeners.addListener(new Listener<Block>() {
            @Override
            public void notify(Block block) {
                if (block.getHeight() % 5000 == 0) {
                    Logger.logDebugMessage("processed block " + block.getHeight());
                }
            }
        }, Event.BLOCK_SCANNED);

        ThreadPool.runBeforeStart(new Runnable() {
            @Override
            public void run() {
                addGenesisBlock();
                scan();
            }
        });

        ThreadPool.scheduleThread(getMoreBlocksThread, 1);

    }

    @Override
    public boolean addListener(Listener<Block> listener, BlockchainProcessor.Event eventType) {
        return blockListeners.addListener(listener, eventType);
    }

    @Override
    public boolean removeListener(Listener<Block> listener, Event eventType) {
        return blockListeners.removeListener(listener, eventType);
    }

    @Override
    public Peer getLastBlockchainFeeder() {
        return lastBlockchainFeeder;
    }

    @Override
    public void processPeerBlock(JSONObject request) throws NxtException {
        BlockImpl block = parseBlock(request);
        pushBlock(block);
    }

    @Override
    public void fullReset() {
        synchronized (blockchain) {
            Logger.logMessage("Deleting blockchain...");
            //BlockDb.deleteBlock(Genesis.GENESIS_BLOCK_ID); // fails with stack overflow in H2
            BlockDb.deleteAll();
            addGenesisBlock();
            scan();
        }
    }

    private void addBlock(BlockImpl block) {
        try (Connection con = Db.getConnection()) {
            try {
                BlockDb.saveBlock(con, block);
                blockchain.setLastBlock(block);
                con.commit();
            } catch (SQLException e) {
                con.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e.toString(), e);
        }
    }

    private void addGenesisBlock() {
        if (BlockDb.hasBlock(Genesis.GENESIS_BLOCK_ID)) {
            Logger.logMessage("Genesis block already in database");
            return;
        }
        Logger.logMessage("Genesis block not in database, starting from scratch");
        try {
            SortedMap<Long,TransactionImpl> transactionsMap = new TreeMap<>();

            for (int i = 0; i < Genesis.GENESIS_RECIPIENTS.length; i++) {
                TransactionImpl transaction = new TransactionImpl(TransactionType.Payment.ORDINARY, 0, (short) 0, Genesis.CREATOR_PUBLIC_KEY,
                        Genesis.GENESIS_RECIPIENTS[i], Genesis.GENESIS_AMOUNTS[i], 0, null, Genesis.GENESIS_SIGNATURES[i]);
                transactionsMap.put(transaction.getId(), transaction);
            }

            MessageDigest digest = Crypto.sha256();
            for (Transaction transaction : transactionsMap.values()) {
                digest.update(transaction.getBytes());
            }

            BlockImpl genesisBlock = new BlockImpl(-1, 0, null, 1000000000, 0, transactionsMap.size() * 128, digest.digest(),
                    Genesis.CREATOR_PUBLIC_KEY, new byte[64], Genesis.GENESIS_BLOCK_SIGNATURE, null, new ArrayList<>(transactionsMap.values()));

            genesisBlock.setPrevious(null);

            addBlock(genesisBlock);

        } catch (NxtException.ValidationException e) {
            Logger.logMessage(e.getMessage());
            throw new RuntimeException(e.toString(), e);
        }
    }

    private byte[] calculateTransactionsChecksum() {
        PriorityQueue<Transaction> sortedTransactions = new PriorityQueue<>(blockchain.getTransactionCount(), new Comparator<Transaction>() {
            @Override
            public int compare(Transaction o1, Transaction o2) {
                long id1 = o1.getId();
                long id2 = o2.getId();
                return id1 < id2 ? -1 : (id1 > id2 ? 1 : (o1.getTimestamp() < o2.getTimestamp() ? -1 : (o1.getTimestamp() > o2.getTimestamp() ? 1 : 0)));
            }
        });
        try (DbIterator<TransactionImpl> iterator = blockchain.getAllTransactions()) {
            while (iterator.hasNext()) {
                sortedTransactions.add(iterator.next());
            }
        }
        MessageDigest digest = Crypto.sha256();
        while (! sortedTransactions.isEmpty()) {
            digest.update(sortedTransactions.poll().getBytes());
        }
        return digest.digest();
    }

    private void pushBlock(final BlockImpl block) throws BlockNotAcceptedException {

        int curTime = Convert.getEpochTime();

        synchronized (blockchain) {
            try {

                BlockImpl previousLastBlock = blockchain.getLastBlock();

                if (! previousLastBlock.getId().equals(block.getPreviousBlockId())) {
                    throw new BlockOutOfOrderException("Previous block id doesn't match");
                }

                if (block.getVersion() != (previousLastBlock.getHeight() < Constants.TRANSPARENT_FORGING_BLOCK ? 1 : 2)) {
                    throw new BlockNotAcceptedException("Invalid version " + block.getVersion());
                }

                if (previousLastBlock.getHeight() == Constants.TRANSPARENT_FORGING_BLOCK) {
                    byte[] checksum = calculateTransactionsChecksum();
                    if (CHECKSUM_TRANSPARENT_FORGING == null) {
                        Logger.logMessage("Checksum calculated:\n" + Arrays.toString(checksum));
                    } else if (!Arrays.equals(checksum, CHECKSUM_TRANSPARENT_FORGING)) {
                        Logger.logMessage("Checksum failed at block " + Constants.TRANSPARENT_FORGING_BLOCK);
                        throw new BlockNotAcceptedException("Checksum failed");
                    } else {
                        Logger.logMessage("Checksum passed at block " + Constants.TRANSPARENT_FORGING_BLOCK);
                    }
                }

                if (block.getVersion() != 1 && ! Arrays.equals(Crypto.sha256().digest(previousLastBlock.getBytes()), block.getPreviousBlockHash())) {
                    throw new BlockNotAcceptedException("Previous block hash doesn't match");
                }
                if (block.getTimestamp() > curTime + 15 || block.getTimestamp() <= previousLastBlock.getTimestamp()) {
                    throw new BlockOutOfOrderException("Invalid timestamp: " + block.getTimestamp()
                            + " current time is " + curTime + ", previous block timestamp is " + previousLastBlock.getTimestamp());
                }
                if (block.getId().equals(Long.valueOf(0L)) || BlockDb.hasBlock(block.getId())) {
                    throw new BlockNotAcceptedException("Duplicate block or invalid id");
                }
                if (! block.verifyGenerationSignature() || ! block.verifyBlockSignature()) {
                    throw new BlockNotAcceptedException("Signature verification failed");
                }

                Map<TransactionType, Set<String>> duplicates = new HashMap<>();
                Map<Long, Long> accumulatedAmounts = new HashMap<>();
                Map<Long, Map<Long, Long>> accumulatedAssetQuantities = new HashMap<>();
                int calculatedTotalAmount = 0, calculatedTotalFee = 0;
                MessageDigest digest = Crypto.sha256();

                for (TransactionImpl transaction : block.getTransactions()) {

                    // cfb: Block 303 contains a transaction which expired before the block timestamp
                    if (transaction.getTimestamp() > curTime + 15 || transaction.getTimestamp() > block.getTimestamp() + 15
                            || (transaction.getExpiration() < block.getTimestamp() && previousLastBlock.getHeight() != 303)) {
                        throw new BlockNotAcceptedException("Invalid transaction timestamp " + transaction.getTimestamp()
                                + " for transaction " + transaction.getStringId() + ", current time is " + curTime
                                + ", block timestamp is " + block.getTimestamp());
                    }
                    if (TransactionDb.hasTransaction(transaction.getId())) {
                        throw new BlockNotAcceptedException("Transaction " + transaction.getStringId() + " is already in the blockchain");
                    }
                    if ((transaction.getReferencedTransactionId() != null
                            && ! TransactionDb.hasTransaction(transaction.getReferencedTransactionId())
                            && Collections.binarySearch(block.getTransactionIds(), transaction.getReferencedTransactionId()) < 0)) {
                        throw new BlockNotAcceptedException("Missing referenced transaction " + Convert.toUnsignedLong(transaction.getReferencedTransactionId())
                                +" for transaction " + transaction.getStringId());
                    }
                    if (! transaction.verify()) {
                        throw new BlockNotAcceptedException("Signature verification failed for transaction " + transaction.getStringId());
                    }
                    if (transaction.getId().equals(Long.valueOf(0L))) {
                        throw new BlockNotAcceptedException("Invalid transaction id");
                    }
                    if (transaction.isDuplicate(duplicates)) {
                        throw new BlockNotAcceptedException("Transaction is a duplicate: " + transaction.getStringId());
                    }
                    try {
                        transaction.validateAttachment();
                    } catch (NxtException.ValidationException e) {
                        throw new BlockNotAcceptedException(e.getMessage());
                    }

                    calculatedTotalAmount += transaction.getAmount();

                    transaction.updateTotals(accumulatedAmounts, accumulatedAssetQuantities);

                    calculatedTotalFee += transaction.getFee();

                    digest.update(transaction.getBytes());

                }

                if (calculatedTotalAmount != block.getTotalAmount() || calculatedTotalFee != block.getTotalFee()) {
                    throw new BlockNotAcceptedException("Total amount or fee don't match transaction totals");
                }
                if (!Arrays.equals(digest.digest(), block.getPayloadHash())) {
                    throw new BlockNotAcceptedException("Payload hash doesn't match");
                }
                for (Map.Entry<Long, Long> accumulatedAmountEntry : accumulatedAmounts.entrySet()) {
                    Account senderAccount = Account.getAccount(accumulatedAmountEntry.getKey());
                    if (senderAccount.getBalance() < accumulatedAmountEntry.getValue()) {
                        throw new BlockNotAcceptedException("Not enough funds in sender account: " + Convert.toUnsignedLong(senderAccount.getId()));
                    }
                }

                for (Map.Entry<Long, Map<Long, Long>> accumulatedAssetQuantitiesEntry : accumulatedAssetQuantities.entrySet()) {
                    Account senderAccount = Account.getAccount(accumulatedAssetQuantitiesEntry.getKey());
                    for (Map.Entry<Long, Long> accountAccumulatedAssetQuantitiesEntry : accumulatedAssetQuantitiesEntry.getValue().entrySet()) {
                        Long assetId = accountAccumulatedAssetQuantitiesEntry.getKey();
                        Long quantity = accountAccumulatedAssetQuantitiesEntry.getValue();
                        if (senderAccount.getAssetBalance(assetId) < quantity) {
                            throw new BlockNotAcceptedException("Asset balance not sufficient in sender account " + Convert.toUnsignedLong(senderAccount.getId()));
                        }
                    }
                }

                block.setPrevious(previousLastBlock);

                Transaction duplicateTransaction = transactionProcessor.checkTransactionHashes(block);
                if (duplicateTransaction != null) {
                    throw new BlockNotAcceptedException("Duplicate hash of transaction " + duplicateTransaction.getStringId());
                }

                addBlock(block);

            } catch (RuntimeException e) {
                Logger.logMessage("Error pushing block", e);
                throw new BlockNotAcceptedException(e.toString());
            }

            blockListeners.notify(block, Event.BEFORE_BLOCK_APPLY);

            transactionProcessor.apply(block);

            blockListeners.notify(block, Event.BLOCK_PUSHED);

            transactionProcessor.updateUnconfirmedTransactions(block);

        } // synchronized

        if (block.getTimestamp() >= Convert.getEpochTime() - 15) {
            JSONObject request = block.getJSONObject();
            request.put("requestType", "processBlock");
            Peers.sendToSomePeers(request);
        }

    }

    private boolean popLastBlock() throws TransactionType.UndoNotSupportedException {
        try {
            BlockImpl block;

            synchronized (blockchain) {
                block = blockchain.getLastBlock();
                Logger.logDebugMessage("Will pop block " + block.getStringId() + " at height " + block.getHeight());
                if (block.getId().equals(Genesis.GENESIS_BLOCK_ID)) {
                    return false;
                }
                BlockImpl previousBlock = BlockDb.findBlock(block.getPreviousBlockId());
                if (previousBlock == null) {
                    Logger.logMessage("Previous block is null");
                    throw new IllegalStateException();
                }
                blockListeners.notify(block, Event.BEFORE_BLOCK_UNDO);
                blockchain.setLastBlock(block, previousBlock);
                transactionProcessor.undo(block);
                BlockDb.deleteBlock(block.getId());
            } // synchronized

            blockListeners.notify(block, Event.BLOCK_POPPED);

        } catch (RuntimeException e) {
            Logger.logMessage("Error popping last block", e);
            return false;
        }
        return true;
    }

    void generateBlock(String secretPhrase) {

        Set<TransactionImpl> sortedTransactions = new TreeSet<>();

        for (TransactionImpl transaction : transactionProcessor.getAllUnconfirmedTransactions()) {
            if (transaction.getReferencedTransactionId() == null || TransactionDb.hasTransaction(transaction.getReferencedTransactionId())) {
                sortedTransactions.add(transaction);
            }
        }

        SortedMap<Long, TransactionImpl> newTransactions = new TreeMap<>();
        Map<TransactionType, Set<String>> duplicates = new HashMap<>();
        Map<Long, Long> accumulatedAmounts = new HashMap<>();

        int totalAmount = 0;
        int totalFee = 0;
        int payloadLength = 0;

        int blockTimestamp = Convert.getEpochTime();

        while (payloadLength <= Constants.MAX_PAYLOAD_LENGTH) {

            int prevNumberOfNewTransactions = newTransactions.size();

            for (TransactionImpl transaction : sortedTransactions) {

                int transactionLength = transaction.getSize();
                if (newTransactions.get(transaction.getId()) != null || payloadLength + transactionLength > Constants.MAX_PAYLOAD_LENGTH) {
                    continue;
                }

                Long sender = transaction.getSenderId();
                Long accumulatedAmount = accumulatedAmounts.get(sender);
                if (accumulatedAmount == null) {
                    accumulatedAmount = 0L;
                }

                long amount = (transaction.getAmount() + transaction.getFee()) * 100L;
                if (accumulatedAmount + amount > Account.getAccount(sender).getBalance()) {
                    continue;
                }

                if (transaction.getTimestamp() > blockTimestamp + 15 || (transaction.getExpiration() < blockTimestamp)) {
                    continue;
                }

                if (transaction.isDuplicate(duplicates)) {
                    continue;
                }

                try {
                    transaction.validateAttachment();
                } catch (NxtException.ValidationException e) {
                    continue;
                }

                accumulatedAmounts.put(sender, accumulatedAmount + amount);

                newTransactions.put(transaction.getId(), transaction);
                payloadLength += transactionLength;
                totalAmount += transaction.getAmount();
                totalFee += transaction.getFee();

            }

            if (newTransactions.size() == prevNumberOfNewTransactions) {
                break;
            }
        }

        final byte[] publicKey = Crypto.getPublicKey(secretPhrase);

        MessageDigest digest = Crypto.sha256();
        for (Transaction transaction : newTransactions.values()) {
            digest.update(transaction.getBytes());
        }

        byte[] payloadHash = digest.digest();

        BlockImpl previousBlock = blockchain.getLastBlock();
        if (previousBlock.getHeight() < Constants.TRANSPARENT_FORGING_BLOCK) {
            Logger.logDebugMessage("Generate block below " + Constants.TRANSPARENT_FORGING_BLOCK + " no longer supported");
            return;
        }

        digest.update(previousBlock.getGenerationSignature());
        byte[] generationSignature = digest.digest(publicKey);

        BlockImpl block;
        //int version = previousBlock.getHeight() < Constants.TRANSPARENT_FORGING_BLOCK ? 1 : 2;
        int version = 2;
        byte[] previousBlockHash = Crypto.sha256().digest(previousBlock.getBytes());

        try {

            block = new BlockImpl(version, blockTimestamp, previousBlock.getId(), totalAmount, totalFee, payloadLength,
                        payloadHash, publicKey, generationSignature, null, previousBlockHash, new ArrayList<>(newTransactions.values()));

        } catch (NxtException.ValidationException e) {
            // shouldn't happen because all transactions are already validated
            Logger.logMessage("Error generating block", e);
            return;
        }

        block.sign(secretPhrase);

        block.setPrevious(previousBlock);

        try {
            pushBlock(block);
            blockListeners.notify(block, Event.BLOCK_GENERATED);
            Logger.logDebugMessage("Account " + Convert.toUnsignedLong(block.getGeneratorId()) + " generated block " + block.getStringId());
        } catch (BlockNotAcceptedException e) {
            Logger.logDebugMessage("Generate block failed: " + e.getMessage());
        }

    }

    private BlockImpl parseBlock(JSONObject blockData) throws NxtException.ValidationException {
        try {
            int version = ((Long)blockData.get("version")).intValue();
            int timestamp = ((Long)blockData.get("timestamp")).intValue();
            Long previousBlock = Convert.parseUnsignedLong((String) blockData.get("previousBlock"));
            int totalAmount = ((Long)blockData.get("totalAmount")).intValue();
            int totalFee = ((Long)blockData.get("totalFee")).intValue();
            int payloadLength = ((Long)blockData.get("payloadLength")).intValue();
            byte[] payloadHash = Convert.parseHexString((String) blockData.get("payloadHash"));
            byte[] generatorPublicKey = Convert.parseHexString((String) blockData.get("generatorPublicKey"));
            byte[] generationSignature = Convert.parseHexString((String) blockData.get("generationSignature"));
            byte[] blockSignature = Convert.parseHexString((String) blockData.get("blockSignature"));
            byte[] previousBlockHash = version == 1 ? null : Convert.parseHexString((String) blockData.get("previousBlockHash"));

            SortedMap<Long, TransactionImpl> blockTransactions = new TreeMap<>();
            JSONArray transactionsData = (JSONArray)blockData.get("transactions");
            for (Object transactionData : transactionsData) {
                TransactionImpl transaction = transactionProcessor.parseTransaction((JSONObject) transactionData);
                if (blockTransactions.put(transaction.getId(), transaction) != null) {
                    throw new NxtException.ValidationException("Block contains duplicate transactions: " + transaction.getStringId());
                }
            }

            return new BlockImpl(version, timestamp, previousBlock, totalAmount, totalFee, payloadLength, payloadHash, generatorPublicKey,
                    generationSignature, blockSignature, previousBlockHash, new ArrayList<>(blockTransactions.values()));

        } catch (RuntimeException e) {
            throw new NxtException.ValidationException(e.toString(), e);
        }
    }

    private void scan() {
        synchronized (blockchain) {
            Logger.logMessage("Scanning blockchain...");
            Account.clear();
            Alias.clear();
            Asset.clear();
            Order.clear();
            Poll.clear();
            Trade.clear();
            Vote.clear();
            transactionProcessor.clear();
            try (Connection con = Db.getConnection(); PreparedStatement pstmt = con.prepareStatement("SELECT * FROM block ORDER BY db_id ASC")) {
                Long currentBlockId = Genesis.GENESIS_BLOCK_ID;
                BlockImpl currentBlock;
                ResultSet rs = pstmt.executeQuery();
                try {
                    while (rs.next()) {
                        currentBlock = BlockDb.loadBlock(con, rs);
                        if (! currentBlock.getId().equals(currentBlockId)) {
                            throw new NxtException.ValidationException("Database blocks in the wrong order!");
                        }
                        blockchain.setLastBlock(currentBlock);
                        blockListeners.notify(currentBlock, Event.BEFORE_BLOCK_APPLY);
                        transactionProcessor.apply(currentBlock);
                        blockListeners.notify(currentBlock, Event.BLOCK_SCANNED);
                        currentBlockId = currentBlock.getNextBlockId();
                    }
                } catch (RuntimeException e) {
                    Logger.logDebugMessage(e.toString(), e);
                    Logger.logDebugMessage("Applying block " + Convert.toUnsignedLong(currentBlockId) + " failed, deleting from database");
                    BlockDb.deleteBlock(currentBlockId);
                    scan();
                }
            } catch (NxtException.ValidationException|SQLException e) {
                throw new RuntimeException(e.toString(), e);
            }
            Logger.logMessage("...done");
        }
    }

}
