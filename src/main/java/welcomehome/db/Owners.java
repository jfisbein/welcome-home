package welcomehome.db;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Every owner, for the work that is done for each of them without anyone asking: the daily jobs and what runs on
 * start. Row-Level Security shows such work no rows until it names an owner, so it goes owner by owner, each in
 * their own transactions ({@link OwnerTransactions}).
 */
@Component
public class Owners {

    private static final Logger log = LoggerFactory.getLogger(Owners.class);

    private final OwnerIdsDao owners;

    Owners(OwnerIdsDao owners) {
        this.owners = owners;
    }

    /**
     * Lists every owner.
     *
     * @return their ids, the earliest registered first
     */
    public List<UUID> ids() {
        return owners.findAll();
    }

    /**
     * Does some work for every owner in turn. One owner's failure is logged and does not stop the others': the next
     * run tries again.
     *
     * @param task what the work is, for the log, such as "purge the links"
     * @param work the work for one owner
     */
    public void forEach(String task, Consumer<UUID> work) {
        for (var ownerId : ids()) {
            try {
                work.accept(ownerId);
            } catch (RuntimeException e) {
                log.warn("Could not {} of owner {}: {}", task, ownerId, e.getMessage());
            }
        }
    }
}
