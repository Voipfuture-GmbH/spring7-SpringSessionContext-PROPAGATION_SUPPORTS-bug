package reproducer;

import static org.junit.jupiter.api.Assertions.assertEquals;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import org.h2.jdbcx.JdbcDataSource;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Reproduces a regression in Spring Framework 7's {@code org.springframework.orm.jpa.hibernate.SpringSessionContext}
 * (run {@code mvn test}); {@code mvn test -Pspring62} runs the same test against Spring 6.2 for comparison.
 * <p>
 * Scenario: inside a {@code PROPAGATION_SUPPORTS} scope (no actual transaction, synchronization active),
 * {@code getCurrentSession()} opens a session on a Spring-supplied JDBC connection. A nested
 * {@code PROPAGATION_REQUIRED} transaction suspends that session, which calls
 * {@code LogicalConnectionImplementor.manualDisconnect()} and drops the provided connection. Resuming never
 * reconnects it.
 * <p>
 * Expected (and Spring 6.2 behaviour): the outer session keeps working after the nested transaction.<br>
 * Actual (Spring 7.0.10): the next query on the outer session fails with a {@code NullPointerException}
 * in {@code StatementPreparerImpl.connection()}.
 */
class SupportsThenRequiredTest {

    @Entity(name = "Item")
    public static class Item {
        @Id
        Long id;
    }

    private SessionFactory sessionFactory;
    private PlatformTransactionManager txManager;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:repro;DB_CLOSE_DELAY=-1");
        sessionFactory = HibernateSetup.sessionFactory(dataSource, Item.class);
        txManager = HibernateSetup.transactionManager(sessionFactory);

        template(TransactionDefinition.PROPAGATION_REQUIRED, false).executeWithoutResult(status -> {
            Item item = new Item();
            item.id = 1L;
            sessionFactory.getCurrentSession().persist(item);
        });
    }

    @AfterEach
    void tearDown() {
        sessionFactory.close();
    }

    /** Fails on Spring 7.0.10 with an NPE on the second outer query. */
    @Test
    void supportsOuterSurvivesNestedRequired() {
        runOuterNestedOuter(TransactionDefinition.PROPAGATION_SUPPORTS);
    }

    /** Control case: same sequence with a real outer transaction works. */
    @Test
    void requiredOuterSurvivesNestedRequired() {
        runOuterNestedOuter(TransactionDefinition.PROPAGATION_REQUIRED);
    }

    private void runOuterNestedOuter(int outerPropagation) {
        template(outerPropagation, true).executeWithoutResult(outer -> {
            assertEquals(1L, countItems());
            template(TransactionDefinition.PROPAGATION_REQUIRED, false).executeWithoutResult(inner -> countItems());
            assertEquals(1L, countItems());
        });
    }

    private long countItems() {
        return sessionFactory.getCurrentSession()
                .createSelectionQuery("select count(i) from Item i", Long.class)
                .getSingleResult();
    }

    private TransactionTemplate template(int propagation, boolean readOnly) {
        TransactionTemplate template = new TransactionTemplate(txManager);
        template.setPropagationBehavior(propagation);
        template.setReadOnly(readOnly);
        return template;
    }
}
