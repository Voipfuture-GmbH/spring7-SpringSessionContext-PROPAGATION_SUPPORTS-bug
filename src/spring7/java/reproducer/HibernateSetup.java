package reproducer;

import javax.sql.DataSource;
import org.hibernate.SessionFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.orm.jpa.hibernate.HibernateTransactionManager;
import org.springframework.orm.jpa.hibernate.LocalSessionFactoryBuilder;

/**
 * Hibernate setup for Spring Framework 7 ({@code org.springframework.orm.jpa.hibernate}); the package differs between Spring versions, the test does not.
 */
final class HibernateSetup {

    private HibernateSetup() {
    }

    static SessionFactory sessionFactory(DataSource dataSource, Class<?> entityClass) {
        return new LocalSessionFactoryBuilder(dataSource)
                .addAnnotatedClass(entityClass)
                .setProperty("hibernate.hbm2ddl.auto", "create-drop")
                .buildSessionFactory();
    }

    static PlatformTransactionManager transactionManager(SessionFactory sessionFactory) {
        return new HibernateTransactionManager(sessionFactory);
    }
}
