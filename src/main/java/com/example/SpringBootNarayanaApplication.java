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

import org.apache.camel.component.jms.JmsComponent;
import org.apache.camel.component.servlet.CamelHttpTransportServlet;
import org.apache.camel.component.sql.SqlComponent;
import org.apache.camel.spring.spi.SpringTransactionPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.jta.narayana.DbcpXADataSourceWrapper;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;

import javax.jms.ConnectionFactory;
import javax.sql.DataSource;

@SpringBootApplication
@Import(DbcpXADataSourceWrapper.class)
public class SpringBootNarayanaApplication {

	private static final Logger LOG = LoggerFactory.getLogger(SpringBootNarayanaApplication.class);

	public static void main(String[] args) throws Exception {
		SpringApplication.run(SpringBootNarayanaApplication.class, args);
		System.out.println("Running....");
	}

	@Bean
	public JmsComponent jms(ConnectionFactory xaJmsConnectionFactory, PlatformTransactionManager jtaTansactionManager){
		return  JmsComponent.jmsComponentTransacted(xaJmsConnectionFactory, jtaTansactionManager);
	}

	@Bean
	public SqlComponent sql(DataSource dataSource) {
		SqlComponent rc = new SqlComponent();
		rc.setDataSource(dataSource);
		return rc;
	}

	@Bean(name = "PROPAGATION_REQUIRED")
	public SpringTransactionPolicy propagationRequired(PlatformTransactionManager jtaTransactionManager){
		SpringTransactionPolicy propagationRequired = new SpringTransactionPolicy();
		propagationRequired.setTransactionManager(jtaTransactionManager);
		propagationRequired.setPropagationBehaviorName("PROPAGATION_REQUIRED");
		return propagationRequired;
	}

	@Bean
	public ServletRegistrationBean servletRegistrationBean() {
		ServletRegistrationBean servlet = new ServletRegistrationBean(
				new CamelHttpTransportServlet(), "/api/*");
		servlet.setName("CamelServlet");
		return servlet;
	}

}
