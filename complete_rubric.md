```
Workshop 2: Testing and Deployment
```
For this exercise, you must configure the necessary pipelines for at least six of the
microservices in the provided code

When selecting the microservices, ensure that the ones you choose communicate with each other to
enable the subsequent implementation of tests involving them.

Activities to consider:

1. (10%) Configure Jenkins, Docker, and Kubernetes for use.
2. (15%) For the selected microservices, you must define the pipelines that enable the
    use of the application (dev environment), including build, testing at
    various levels, and deployment.
3. (30%) For some of the microservices, you must define unit, integration,
    E2E, and performance tests that involve the microservices.
       a. At least five new unit tests that validate individual components
       b. At least five new integration tests that validate communication between
          services
       c. At least five new E2E tests that validate complete user flows
       d. Performance and stress tests using Locust that simulate real-world use cases
          of the system.
All tests must be relevant to existing, modified, or added functionalities
and must include analysis of those functionalities.
4. (15%) For the selected microservices, you must define the pipelines that enable
  the build, including testing, of the application deployed on Kubernetes
  (stage environment).
5. (15%) For the selected microservices, you must execute a deployment pipeline
  that performs the build—including unit tests—validates the system tests,
  and subsequently deploys the application to Kubernetes. Define all the
    phases you deem appropriate (master environment). You must include the automatic
    generation of Release Notes following Change Management best practices.
    Management.
6. (15%) Adequate documentation of the process performed and a video that
    demonstrates all of the above points.

Reporting of results: You must submit a document and a short video (maximum 8
minutes) containing and explaining the following information for each of the pipelines:

- Configuration: Text describing the pipeline configurations, with screenshots of relevant configuration settings.
- Results: Screenshots of the successful execution of the pipelines with relevant details and
    results.
- Analysis: Interpretation of the test results, especially performance tests,
    with key metrics such as response time, throughput, and error rate.
- Additionally, a zip file containing the pipelines, the implemented tests, and the project if it was modified.