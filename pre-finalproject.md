For the final project, you must have an environment that allows you to collect logs, metrics, and alerts from a running system so that you can analyze the data obtained.
Initial Preparation:

Understand the functionality of the following applications:

·         Grafana and Grafana Loki, including Grafana Agent or Alloy

·         Prometheus, including Alert Manager and Node Explorer

·        SonarQube and Trivy for static analysis and vulnerability scanning

·         ELK Stack (Elasticsearch, Logstash, Kibana)
Install and configure the tools so that Prometheus metrics and Grafana Loki logs can be visualized in Grafana. You can start by deploying the Loki Quickstart found in the Loki documentation.

Obtaining metrics:

1.       Create a Spring Boot application and include the micrometer dependency

2.       Define a component in the application that simulates the execution of operations and include counters in them using micrometer.

3.       In the application properties, enable Prometheus and expose the info, health, and prometheus endpoints.

4.       Deploy the application to use the metrics in Prometheus

5.       Retrieve and graph the information in Prometheus and Grafana

You can then connect the Workshop 2 application to retrieve the logs and integrate the metrics into this application. You’ll need to run tests that use implementations involving logs or set up schedulers to call these functions at regular intervals.