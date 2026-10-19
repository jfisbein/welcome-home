package welcomehome.db;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs work in a transaction bound to one owner. Row-Level Security policies read {@code app.owner_id},
 * which is set with {@code is_local = true}, so it ends with the transaction and never leaks to the
 * next user of the pooled connection.
 */
@Component
public class OwnerTransactions {

    private final TransactionTemplate transactionTemplate;
    private final JdbcClient jdbc;

    OwnerTransactions(TransactionTemplate transactionTemplate, JdbcClient jdbc) {
        this.transactionTemplate = transactionTemplate;
        this.jdbc = jdbc;
    }

    /**
     * Runs {@code work} in a transaction where Row-Level Security sees only the rows of {@code ownerId}.
     * Joins the current transaction if there is one, unless it is bound to another owner: the owner context
     * lasts until the transaction ends, so switching owners inside one would let the rest of it see the second
     * owner's rows. Any runtime exception rolls the transaction back.
     *
     * @param ownerId the owner whose rows the work may read and write
     * @param work    the work to run inside the transaction
     * @param <T>     the result type
     * @return the result of {@code work}
     * @throws IllegalStateException if the current transaction is bound to another owner
     */
    public <T> T execute(UUID ownerId, Supplier<T> work) {
        Objects.requireNonNull(ownerId, "ownerId");
        var joining = TransactionSynchronizationManager.isActualTransactionActive();
        return transactionTemplate.execute(_ -> {
            if (joining) {
                refuseAnotherOwner(ownerId);
            }
            // SET LOCAL does not accept parameters; set_config does.
            jdbc.sql("SELECT set_config('app.owner_id', :ownerId, true)")
                    .param("ownerId", ownerId.toString())
                    .query(String.class)
                    .single();
            return work.get();
        });
    }

    private void refuseAnotherOwner(UUID ownerId) {
        var current = jdbc.sql("SELECT current_setting('app.owner_id', true)").query(String.class).single();
        if (current != null && !current.isEmpty() && !current.equals(ownerId.toString())) {
            throw new IllegalStateException("The transaction is already bound to another owner");
        }
    }

    /**
     * Same as {@link #execute(UUID, Supplier)}, for work that returns nothing.
     *
     * @param ownerId the owner whose rows the work may read and write
     * @param work    the work to run inside the transaction
     */
    public void run(UUID ownerId, Runnable work) {
        execute(ownerId, () -> {
            work.run();
            return null;
        });
    }
}
