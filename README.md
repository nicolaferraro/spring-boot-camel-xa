# Spring-Boot Camel Narayana Quickstart

This quickstart uses Narayana TX manager with Spring Boot and Apache Camel on Openshift to test 2PC/XA transactions with a JMS resource (ActiveMQ) and a database (PostgreSQL).

The application uses a *in-process* recovery manager and a persistent volume to store transaction logs.

The application **supports scaling** on the `StatefulSet` resource.

## Installation

Setup a Openshift instance, login, create a project, then execute the following command to deploy the quickstart: 

```
mvn clean fabric8:deploy
```

This command will deploy a PostgreSQL database, a ActiveMQ broker and a Spring-Boot application.

## Usage

Once the application is deployed you can get the base service URL using the following command:
 
```
NARAYANA_HOST=$(oc get route spring-boot-camel-xa -o jsonpath={.spec.host})
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

The new list should contain two messages: `hello` and `hello-ok`.

The `hello-ok` confirms that the message has been sent to a `outgoing` queue and then logged.
 
You can add multiple messages and see the logs. The following actions force the application in some corner cases 
to examine the behavior.

#### Exception handling

Send a message named `fail`:

```
curl -w "\n" -X POST http://$NARAYANA_HOST/api/?entry=failForever
# wait a bit
curl -w "\n" http://$NARAYANA_HOST/api/
```

This message produces an exception at the end of the route, so that the transaction is always rolled back.

You should **not** find any trace of the message in the `audit_log` table.
If you check the application log, you'll find out that the message has been sent to the dead letter queue.

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

After **the pod is restarted** by Openshift, the **recovery manager will recover all pending transactions by communicating with the participating resources** (database and JMS broker).

When the recovery manager has finished processing failed transactions, you should find **two log records** in the `audit_log` table: `killBeforeCommit`, `killBeforeCommit-ok`.


## Credits

This quickstart is based on the work of:

- Christian Posta ([christian-posta/spring-boot-camel-narayana](https://github.com/christian-posta/spring-boot-camel-narayana))
- Gytis Trikleris ([gytis/spring-boot-narayana-stateful-set-example](https://github.com/gytis/spring-boot-narayana-stateful-set-example))