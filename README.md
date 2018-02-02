# Spring-Boot Camel Narayana Quickstart

This quickstart uses Narayana TX manager with Spring Boot and Apache Camel on Openshift to test 2PC/XA transactions with a JMS resource (ActiveMQ) and a database (PostgreSQL).

The application uses a *in-process* recovery manager and a persistent volume to store transaction logs.

The application **does not support scaling**. Having two pods running can lead to inconsistencies because multiple Narayana recovery managers (using the same transaction manager id) cannot 
run in parallel.

The `DeploymentConfig` uses a `Recreate` strategy to avoid running two instances in parallel during re-deployment.
To avoid issues with network partitioning, the user must ensure that the storage provider supports *innate locking*, 
to prevent multiple pods in different nodes to mount the same persistent volume (`spring-boot-camel-narayana`) concurrently.

## Installation

Setup a Openshift instance, login, create a project, then execute the following command to deploy the quickstart: 

```
mvn clean fabric8:deploy
```

This command will deploy a PostgreSQL database, a ActiveMQ broker and a Spring-Boot application.

## Usage

Once the application is deployed you can get the base service URL using the following command:
 
```
NARAYANA_HOST=$(oc get route spring-boot-camel-narayana -o jsonpath={.spec.host})
```

The application exposes the following rest URLs:

- GET on `http://$NARAYANA_HOST/api/`: list all messages in the `audit_log` table (ordered)
- POST on `http://$NARAYANA_HOST/api/?entry=xxx`: put a message `xxx` in the `incoming` queue for processing

### Simple workflow

First get a list of messages in the `audit_log` table:

```
curl -w "\n" http://$NARAYANA_HOST/api/
```

The list should be empty at the beginning. Now you can put the first element.

```
curl -w "\n" -X POST http://$NARAYANA_HOST/api/?entry=hello
# wait a bit
curl -w "\n" http://$NARAYANA_HOST/api/
```

The new list should contain two messages: `hello-1` and `hello-1-ok`. The first part of each audit log 
is the message sent to the queue (`hello`), the second number is the progressive number of 
 delivery when it has been processed correctly (`1` is the first delivery attempt).
 The `hello-1-ok` confirms that the message has been sent to a `outgoing` queue and then logged.
 
You can add multiple messages and see the logs. The following actions force the application in some corner cases 
to examine the behavior.

#### Sporadic exception handling

Send a message named `failOnce`:

```
curl -w "\n" -X POST http://$NARAYANA_HOST/api/?entry=failOnce
# wait a bit
curl -w "\n" http://$NARAYANA_HOST/api/
```

This message produces an exception in the first delivery, so that the transaction is rolled back.
A subsequent redelivery (`JMSXDeliveryCount` > 1) is processed correctly.

In this case you should find **two log records** in the `audit_log` table: `failOnce-2`, `failOnce-2-ok` (the message is processed correctly at **delivery number 2**).

#### Repeatable exception handling

Send a message named `failForever`:

```
curl -w "\n" -X POST http://$NARAYANA_HOST/api/?entry=failForever
# wait a bit
curl -w "\n" http://$NARAYANA_HOST/api/
```

This message produces an exception in all redeliveries, so that the transaction is always rolled back.

You should **not** find any trace of the message in the `audit_log` table.
If you check the application log, you'll find out that the message has been sent to the dead letter queue.


#### Safe system crash

Send a message named `killOnce`:

```
curl -w "\n" -X POST http://$NARAYANA_HOST/api/?entry=killOnce
# wait a bit (the pod should be restarted)
curl -w "\n" http://$NARAYANA_HOST/api/
```

This message produces a **immediate crash** of the application in the first delivery, so that the transaction is not committed.
After **the pod is restarted** by Openshift, a subsequent redelivery (`JMSXDeliveryCount` > 1) is processed correctly.

In this case you should find **two log records** in the `audit_log` table: `killOnce-2`, `killOnce-2-ok` (the message is processed correctly at **delivery number 2**).

#### Unsafe system crash

Send a message named `killBeforeCommit`:

```
curl -w "\n" -X POST http://$NARAYANA_HOST/api/?entry=killBeforeCommit
# wait a bit (the pod should be restarted)
curl -w "\n" http://$NARAYANA_HOST/api/
```

This message produces a **immediate crash after the first phase of the 2pc protocol and before the final commit**.
The message **must not** be processed again, but the transaction manager was not able to send a confirmation to all resources.
If you check the `audit_log` table in the database while the application is down, you'll not find any trace of the message (it will appear later).

After **the pod is restarted** by Openshift, the **recovery manager will recover all pending transactions by communicatng with the participating resources** (database and JMS broker).

When the recovery manager has finished processing failed transactions, you should find **two log records** in the `audit_log` table: `killBeforeCommit-1`, `killBeforeCommit-1-ok` (no redeliveries here, the message is processed correctly at **delivery number 1**).


## Credits

This quickstart is based on the work of:

- Christian Posta ([christian-posta/spring-boot-camel-narayana](https://github.com/christian-posta/spring-boot-camel-narayana))
- Gytis Trikleris ([gytis/spring-boot-narayana-stateful-set-example](https://github.com/gytis/spring-boot-narayana-stateful-set-example))