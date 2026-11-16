# Spring 7 `SpringSessionContext`: outer `SUPPORTS` session loses its connection after a nested `REQUIRED` transaction

## Versions
| Maven profile | Spring Framework | Hibernate ORM | Spring's Hibernate package |
|---|---|---|---|
| `spring7` (default) | 7.0.10 | 7.2.6.Final | `org.springframework.orm.jpa.hibernate` |
| `spring62` | 6.2.19 | 6.6.59.Final | `org.springframework.orm.hibernate5` |

Both use H2 2.3.232 and need JDK 17+. The test is shared; only the small `HibernateSetup` class differs per profile
(`src/spring7/java`, `src/spring62/java`), because the Hibernate integration moved to a different package in 7.0.

## Scenario
With `HibernateTransactionManager` (default `SYNCHRONIZATION_ALWAYS`):

1. In a `PROPAGATION_SUPPORTS` (read-only) scope with no existing transaction, query via `sessionFactory.getCurrentSession()`.
2. Inside that scope, run a nested `PROPAGATION_REQUIRED` transaction that runs any query.
3. Query again via `sessionFactory.getCurrentSession()` in the outer scope.

## Run
```
mvn test             # Spring 7.0.10: fails (see below)
mvn test -Pspring62  # Spring 6.2.19: both tests pass
```

## Expected
Step 3 works, as it does with Spring 6.2 (`mvn test -Pspring62`).

## Actual
`supportsOuterSurvivesNestedRequired` fails in step 3:
```
java.lang.NullPointerException: Cannot invoke "java.sql.Connection.prepareStatement(String, int, int)" because the return value of "org.hibernate.engine.jdbc.internal.StatementPreparerImpl.connection()" is null
	at org.hibernate.engine.jdbc.internal.StatementPreparerImpl$4.doPrepare(StatementPreparerImpl.java:155)
	at org.hibernate.engine.jdbc.internal.StatementPreparerImpl$StatementPreparationTemplate.prepareStatement(StatementPreparerImpl.java:183)
	at org.hibernate.engine.jdbc.internal.StatementPreparerImpl.prepareQueryStatement(StatementPreparerImpl.java:157)
	...
```
The control test `requiredOuterSurvivesNestedRequired` (outer `PROPAGATION_REQUIRED` + read-only) passes.

## Root cause
- In the `SUPPORTS` scope, `SpringSessionContext.currentSession()` opens the session with a user-supplied connection:
  `sessionFactory.withOptions().connection(DataSourceUtils.getConnection(dataSource)).openSession()`,
  so Hibernate uses `LogicalConnectionProvidedImpl`.
- The nested `REQUIRED` transaction suspends the outer synchronizations. `SpringSessionSynchronization.suspend()` calls
  `getJdbcCoordinator().getLogicalConnection().manualDisconnect()`, which for `LogicalConnectionProvidedImpl` sets
  `providedConnection = null`.
- `SpringSessionSynchronization.resume()` only re-binds the `SessionHolder` and never reconnects, so the next statement
  on the outer session has no connection.

Spring 6.2's `orm.hibernate5.SpringSessionContext` used a plain `sessionFactory.openSession()` there (Hibernate-managed
connection), so after `manualDisconnect()` the session simply acquired a new connection.
