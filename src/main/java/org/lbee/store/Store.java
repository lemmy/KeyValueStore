package org.lbee.store;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.lbee.instrumentation.trace.TLATracer;
import org.lbee.instrumentation.trace.VirtualField;

public class Store {

    private static final String NO_VALUE = "a value that cannot be";
    private static final long MAX_NB_TX = 4;

    private final Map<String, String> store;
    private final Map<Transaction, Map<String, String>> snapshots;
    private final List<Transaction> openTransactions;
    private long nbOpenTransactions;
    private long lastTransactionId = 0;

    private final Map<Transaction, Set<String>> written;
    private final Map<Transaction, Set<String>> missed;

    // Tracing
    private TLATracer tracer;
    private VirtualField traceTx;
    private VirtualField traceWritten;
    private VirtualField snapshot;

    public Store() {
        this.store = new HashMap<>();
        this.snapshots = new HashMap<>();
        this.openTransactions = new ArrayList<>();
        this.nbOpenTransactions = 0;
        this.written = new HashMap<>();
        this.missed = new HashMap<>();
    }

    public Store(TLATracer tracer) {
        this();
        this.tracer = tracer;
        this.traceTx = tracer.getVariableTracer("tx");
        this.traceWritten = tracer.getVariableTracer("written");
        this.snapshot = tracer.getVariableTracer("snapshotStore");
    }

    public synchronized Transaction open() throws IOException, TransactionsException {
        if (this.nbOpenTransactions >= MAX_NB_TX) {
            throw new TransactionsException();
        }
        Transaction transaction = new Transaction(this.lastTransactionId++ % MAX_NB_TX);
        this.nbOpenTransactions++;
        openTransactions.add(transaction);
        snapshots.put(transaction, new HashMap<>());
        written.put(transaction, new HashSet<>());
        missed.put(transaction, new HashSet<>());

        System.out.println("Open " + transaction);

        this.traceTx.add(transaction.getId() + "");
        // either the "OpenTx" or the "CloseTx" should be specified to
        // detect wrong commits
        this.tracer.log("OpenTx");
        // this.tracer.log();

        return transaction;
    }

    public void add(Transaction transaction, String key, String value) throws KeyExistsException, IOException {
        System.out.println("Add (" + transaction + "): " + key + " " + value);

        final Map<String, String> snapshot = snapshots.get(transaction);
        // if key already exists because of a previous write operation
        // (not cancelled by a remove operation) in the local snapshot
        // or exists in the global store then, throw exception
        if ((snapshot.containsKey(key) && !snapshot.get(key).equals(NO_VALUE))
                || store.containsKey(key)) {
            throw new KeyExistsException();
        }
        // Change value in snapshot store
        snapshot.put(key, value);
        written.get(transaction).add(key);

        // trace
        this.traceWritten.getField(transaction.getId() + "").add(key);
        this.snapshot.getField(transaction.getId() + "").setKey(key, value);
        this.tracer.log("Add");
        // this.tracer.log();
    }

    public void update(Transaction transaction, String key, String value)
            throws KeyNotExistsException, ValueExistsException, IOException {
        System.out.println("Update (" + transaction + "): " + key + " " + value);

        final Map<String, String> snapshot = snapshots.get(transaction);
        // if key doesn't already exist (local operation on the snapshot or
        // in the store) throw exception
        if (!(snapshot.containsKey(key) && !snapshot.get(key).equals(NO_VALUE))
                && !store.containsKey(key)) {
            throw new KeyNotExistsException();
        }
        if ((snapshot.containsKey(key) && !snapshot.get(key).equals(NO_VALUE) && snapshot.get(key).equals(value))
                || (store.containsKey(key) && store.get(key).equals(value))) {
            throw new ValueExistsException();
        }
        // Change value in snapshot store
        snapshot.put(key, value);
        written.get(transaction).add(key);

        // trace
        this.traceWritten.getField(transaction.getId() + "").add(key);
        this.tracer.log("Update");
        // this.tracer.log();
    }

    public void remove(Transaction transaction, String key) throws KeyNotExistsException, IOException {
        System.out.println("Remove (" + transaction + "): " + key);

        final Map<String, String> snapshot = snapshots.get(transaction);
        // if key already exists because of a previous write operation
        // (not cancelled by a remove operation) in the local snapshot
        // or exists in the global store then, throw exception
        if ((snapshot.containsKey(key) && !snapshot.get(key).equals(NO_VALUE))
                || store.containsKey(key)) {
            throw new KeyNotExistsException();
        }
        // Change value to NO_VALUE in snapshot in order
        // to remove the key at commit time
        snapshot.put(key, NO_VALUE);
        written.get(transaction).add(key);

        // trace
        this.traceWritten.getField(transaction.getId() + "").add(key);
        this.tracer.log();
    }

    public String read(String key) {
        System.out.println("Read " + key);

        return store.get(key);
    }

    public synchronized boolean close(Transaction transaction) throws IOException {
        // compute the intersection between written and missed
        Set<String> intersection = new HashSet<>(written.get(transaction));
        // if we forget to make a defensive copy, the intersection computation
        // modifies the original set and the rollback will not work
        // Set<String> intersection = written.get(transaction);
        intersection.retainAll(missed.get(transaction));
        // System.out.println("Close: ("+transaction+"): written:
        // "+written.get(transaction)+", missed: "+missed.get(transaction)+",
        // intersection: "+intersection);
        // check if the the intersection of written and missed is empty; rollback if not
        if (!intersection.isEmpty()) {
            // remove the transaction from the pool, snapshots, written and missed
            openTransactions.remove(transaction);
            this.nbOpenTransactions--;
            snapshots.remove(transaction);
            written.remove(transaction);
            missed.remove(transaction);
            System.out.println("Rollback (" + transaction + "): " + intersection);

            // trace
            this.traceTx.remove(transaction.getId() + "");
            this.traceWritten.getField(transaction.getId() + "").clear();
            // this.tracer.log();
            this.tracer.log("RollbackTx");
            return false;
        }
        // add the operation from snapshot to store
        final Map<String, String> snapshot = snapshots.get(transaction);
        for (String key : snapshot.keySet()) {
            if (snapshot.get(key).equals(NO_VALUE)) {
                store.remove(key);
            } else {
                store.put(key, snapshot.get(key));
            }
        }
        // Add written log as missed for other open transactions
        for (Transaction tx : openTransactions) {
            missed.get(tx).addAll(written.get(transaction));
        }
        // remove the transaction from the pool, snapshots, written and missed
        openTransactions.remove(transaction);
        snapshots.remove(transaction);
        this.nbOpenTransactions--;
        written.remove(transaction);
        missed.remove(transaction);
        System.out.println("Commit (" + transaction + "): " + snapshot);

        // trace
        this.traceTx.remove(transaction.getId() + "");
        this.traceWritten.getField(transaction.getId() + "").clear();
        // this.tracer.log();
        this.tracer.log("CloseTx");
        return true;
    }

    public String toString() {
        return store.toString() + " - " + openTransactions.toString();
    }
}
