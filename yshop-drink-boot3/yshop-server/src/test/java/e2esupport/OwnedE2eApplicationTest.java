package e2esupport;

import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class OwnedE2eApplicationTest {
    @Test void statsBoundaryFixtureRejectsOrdersNotCompletedThroughThisRunBridge() {
        Set<String> completed=Set.of("owned-order-a","owned-order-b");
        assertDoesNotThrow(()->OwnedE2eApplication.requireRunOwnedStatsOrders(completed,"owned-order-a","owned-order-b"));
        assertThrows(IllegalArgumentException.class,
                ()->OwnedE2eApplication.requireRunOwnedStatsOrders(completed,"owned-order-a","other-run-order"));
    }
}
