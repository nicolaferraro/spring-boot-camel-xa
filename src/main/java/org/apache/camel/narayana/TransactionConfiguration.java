package org.apache.camel.narayana;

import org.apache.camel.CamelContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.transaction.jta.NarayanaJtaConfiguration;
import org.springframework.boot.jta.narayana.NarayanaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@AutoConfigureAfter(NarayanaJtaConfiguration.class)
@EnableScheduling
public class TransactionConfiguration {

    @Bean(initMethod = "start", destroyMethod = "stop")
    @DependsOn("narayanaRecoveryManagerService")
    public TransactionStatusController transactionStatusController(NarayanaProperties narayanaProperties, CamelContext context) {
        return new TransactionStatusController(narayanaProperties, context);
    }

    @Bean
    @DependsOn("transactionStatusController")
    public TransactionalClusterController transactionalClusterController(NarayanaProperties narayanaProperties,
                                                                         @Value("${transactional.cluster.controller.name}") String controllerName,
                                                                         @Value("${tx.nodename}") String nodeId) {
        return new TransactionalClusterController(narayanaProperties, controllerName, nodeId);
    }

}
