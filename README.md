# Payara Cloud Connectors

This is a forked copy of the Payara Cloud Connectors that focuses on just the Apache Kafka connector and adds transactional support to it. It is specifically designed to support the [Echo Three](https://gitlab.echothree.com/echothree/echothree/) project.

Payara Cloud Connectors is a project to provide Jakarta EE standards based connectivity 
to common Cloud infrastructure. Utilising JCA we provide connectivity to many different 
services provided by the leading cloud providers and open source technologies. Payara Cloud Connectors enable the creation of Cloud Native applications using Jakarta EE apis with the ability to build Event Sourcing and Message Driven architectures simply on public clouds. 

Payara Cloud Connectors are proven to work with Payara Server and Payara Micro 172+. The JCA connectors should work on other Jakarta EE application servers as they do not use any Payara specific code.

Currently we have JCA adapters for;
* Apache Kafka - sending messages using a Connection Factory and receiving messages via an MDB

## Why Use JCA

One of the benefits of using these JCA adapters rather than crafting your own clients, using the standard apis for the messaging technologies, is that the JCA adapters are fully integrated into your Jakarta EE environment.
That means your Jakarta EE application can use familiar Jakarta EE constructs such as Message Driven Beans and Connection Factories. Using MDBs to receive messages means that any threads are automatically provided via the 
Jakarta EE application server which means they can take advantage of Container Transactions, Security, integration with EJBs, CDI and the full range of Jakarta EE components.
Connection factories for outbound messaging also benefit from connection pooling and configuration via the administration console or via annotations in your code.
