/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.example;

import com.arjuna.ats.jbossatx.jta.RecoveryManagerService;
import com.example.crash.DummyXAResourceRecovery;
import org.apache.activemq.camel.component.ActiveMQComponent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.jta.narayana.DbcpXADataSourceWrapper;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;

import javax.jms.ConnectionFactory;

@SpringBootApplication
@Import(DbcpXADataSourceWrapper.class)
public class SpringBootNarayanaApplication {

	private static final Logger LOG = LoggerFactory.getLogger(SpringBootNarayanaApplication.class);

	public static void main(String[] args) {
		SpringApplication.run(SpringBootNarayanaApplication.class, args);
	}

    @Bean(name = "activemq-component")
    public ActiveMQComponent activemq(ConnectionFactory xaJmsConnectionFactory, PlatformTransactionManager jtaTansactionManager){
        ActiveMQComponent activemq = new ActiveMQComponent();
        activemq.setTransacted(true);
        activemq.setTransactionManager(jtaTansactionManager);
        activemq.setConnectionFactory(xaJmsConnectionFactory);

        return activemq;
    }


    /**
     * Dummy xa resource recovery to simulate a crash before final commit.
     *
     * This is (obviously) not needed in production and must be removed.
     */
    @Component
    static class ApplicationCrashConfiguration implements ApplicationListener<ApplicationReadyEvent> {

        @Autowired
        private RecoveryManagerService recoveryManagerService;

        @Override
        public void onApplicationEvent(ApplicationReadyEvent applicationReadyEvent) {
            LOG.warn("Adding DummyXAResourceRecovery to recovery manager service");
            DummyXAResourceRecovery dummyRecovery = new DummyXAResourceRecovery();
            recoveryManagerService.addXAResourceRecovery(dummyRecovery);
        }

    }

}
