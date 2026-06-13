# Chaos Engineering Result

- Date: 2026-06-12T22:18:25
- Namespace: circleguard-dev
- Experiment: gateway-pod-kill
- Dry run: False
- Kubernetes fallback allowed: True

## Before deployments
```text
NAME                               READY   UP-TO-DATE   AVAILABLE   AGE   CONTAINERS   IMAGES                                             SELECTOR
circleguard-auth-service           1/1     1            1           33d   app          circleguard/circleguard-auth-service:dev           app=circleguard-auth-service
circleguard-form-service           1/1     1            1           33d   app          circleguard/circleguard-form-service:dev           app=circleguard-form-service
circleguard-gateway-service        1/1     1            1           33d   app          circleguard/circleguard-gateway-service:dev        app=circleguard-gateway-service
circleguard-identity-service       1/1     1            1           33d   app          circleguard/circleguard-identity-service:dev       app=circleguard-identity-service
circleguard-notification-service   1/1     1            1           33d   app          circleguard/circleguard-notification-service:dev   app=circleguard-notification-service
circleguard-promotion-service      1/1     1            1           33d   app          circleguard/circleguard-promotion-service:dev      app=circleguard-promotion-service
kafka                              1/1     1            1           33d   kafka        confluentinc/cp-kafka:7.6.0                        app=kafka
neo4j                              1/1     1            1           33d   neo4j        neo4j:5.26                                         app=neo4j
openldap                           1/1     1            1           33d   openldap     osixia/openldap:1.5.0                              app=openldap
postgres                           1/1     1            1           33d   postgres     postgres:16                                        app=postgres
redis                              1/1     1            1           33d   redis        redis:7.2                                          app=redis
zookeeper                          1/1     1            1           33d   zookeeper    confluentinc/cp-zookeeper:7.6.0                    app=zookeeper
```

## Before pods
```text
NAME                                                READY   STATUS    RESTARTS        AGE     IP          NODE             NOMINATED NODE   READINESS GATES
circleguard-auth-service-69f579877-zq2qq            1/1     Running   7 (159m ago)    33d     10.1.1.37   docker-desktop   <none>           <none>
circleguard-form-service-78974c5767-8z5h4           1/1     Running   9 (157m ago)    33d     10.1.1.5    docker-desktop   <none>           <none>
circleguard-gateway-service-79d95fd587-g24nc        1/1     Running   0               3m32s   10.1.1.56   docker-desktop   <none>           <none>
circleguard-identity-service-756dd77c7-x9wfb        1/1     Running   7 (159m ago)    33d     10.1.1.24   docker-desktop   <none>           <none>
circleguard-notification-service-77f99dbc9b-cgg8x   1/1     Running   5 (159m ago)    33d     10.1.1.53   docker-desktop   <none>           <none>
circleguard-promotion-service-774cc489fb-bj6kw      1/1     Running   10 (156m ago)   33d     10.1.1.11   docker-desktop   <none>           <none>
kafka-5fcdc5db8b-ch56g                              1/1     Running   4 (159m ago)    33d     10.1.1.29   docker-desktop   <none>           <none>
neo4j-7fb9f8f8f8-2x4r4                              1/1     Running   4 (159m ago)    33d     10.1.1.52   docker-desktop   <none>           <none>
openldap-5f697fdbbc-9xdcx                           1/1     Running   4 (159m ago)    33d     10.1.1.49   docker-desktop   <none>           <none>
postgres-54b5856bd4-f4vc9                           1/1     Running   4 (159m ago)    33d     10.1.1.44   docker-desktop   <none>           <none>
redis-6f4659cd9b-tmfg9                              1/1     Running   4 (159m ago)    33d     10.1.1.25   docker-desktop   <none>           <none>
zookeeper-548b556f44-5gqzv                          1/1     Running   4 (159m ago)    33d     10.1.1.23   docker-desktop   <none>           <none>
```

## Before endpoints
```text
NAME                               ENDPOINTS                       AGE
circleguard-auth-service           10.1.1.37:8180                  33d
circleguard-form-service           10.1.1.5:8086                   33d
circleguard-gateway-service        10.1.1.56:8087                  33d
circleguard-identity-service       10.1.1.24:8083                  33d
circleguard-notification-service   10.1.1.53:8082                  33d
circleguard-promotion-service      10.1.1.11:8088                  33d
kafka                              10.1.1.29:9092                  33d
neo4j                              10.1.1.52:7687,10.1.1.52:7474   33d
openldap                           10.1.1.49:389                   33d
postgres                           10.1.1.44:5432                  33d
redis                              10.1.1.25:6379                  33d
zookeeper                          10.1.1.23:2181                  33d

Warning: v1 Endpoints is deprecated in v1.33+; use discovery.k8s.io/v1 EndpointSlice
```

## Before recent events
```text
LAST SEEN   TYPE     REASON             OBJECT                                              MESSAGE
3m33s       Normal   Scheduled          pod/circleguard-gateway-service-79d95fd587-g24nc    Successfully assigned circleguard-dev/circleguard-gateway-service-79d95fd587-g24nc to docker-desktop
3m33s       Normal   Killing            pod/circleguard-gateway-service-79d95fd587-qlzll    Stopping container app
3m33s       Normal   SuccessfulCreate   replicaset/circleguard-gateway-service-79d95fd587   Created pod: circleguard-gateway-service-79d95fd587-g24nc
3m32s       Normal   Pulled             pod/circleguard-gateway-service-79d95fd587-g24nc    Container image "circleguard/circleguard-gateway-service:dev" already present on machine
3m32s       Normal   Created            pod/circleguard-gateway-service-79d95fd587-g24nc    Created container: app
3m32s       Normal   Started            pod/circleguard-gateway-service-79d95fd587-g24nc    Started container app
```

## Before metrics
```text

error: Metrics API not available
```

## Execution

Chaos Mesh CRDs were not found. Running Kubernetes-native pod kill fallback for the same target.
Deleting pod: circleguard-gateway-service-79d95fd587-g24nc
## Fallback pod delete
```text
pod "circleguard-gateway-service-79d95fd587-g24nc" deleted from circleguard-dev namespace
```

## Gateway rollout recovery
```text
Waiting for deployment "circleguard-gateway-service" rollout to finish: 0 of 1 updated replicas are available...
deployment "circleguard-gateway-service" successfully rolled out
```


## After deployments
```text
NAME                               READY   UP-TO-DATE   AVAILABLE   AGE   CONTAINERS   IMAGES                                             SELECTOR
circleguard-auth-service           1/1     1            1           33d   app          circleguard/circleguard-auth-service:dev           app=circleguard-auth-service
circleguard-form-service           1/1     1            1           33d   app          circleguard/circleguard-form-service:dev           app=circleguard-form-service
circleguard-gateway-service        1/1     1            1           33d   app          circleguard/circleguard-gateway-service:dev        app=circleguard-gateway-service
circleguard-identity-service       1/1     1            1           33d   app          circleguard/circleguard-identity-service:dev       app=circleguard-identity-service
circleguard-notification-service   1/1     1            1           33d   app          circleguard/circleguard-notification-service:dev   app=circleguard-notification-service
circleguard-promotion-service      1/1     1            1           33d   app          circleguard/circleguard-promotion-service:dev      app=circleguard-promotion-service
kafka                              1/1     1            1           33d   kafka        confluentinc/cp-kafka:7.6.0                        app=kafka
neo4j                              1/1     1            1           33d   neo4j        neo4j:5.26                                         app=neo4j
openldap                           1/1     1            1           33d   openldap     osixia/openldap:1.5.0                              app=openldap
postgres                           1/1     1            1           33d   postgres     postgres:16                                        app=postgres
redis                              1/1     1            1           33d   redis        redis:7.2                                          app=redis
zookeeper                          1/1     1            1           33d   zookeeper    confluentinc/cp-zookeeper:7.6.0                    app=zookeeper
```

## After pods
```text
NAME                                                READY   STATUS    RESTARTS        AGE   IP          NODE             NOMINATED NODE   READINESS GATES
circleguard-auth-service-69f579877-zq2qq            1/1     Running   7 (159m ago)    33d   10.1.1.37   docker-desktop   <none>           <none>
circleguard-form-service-78974c5767-8z5h4           1/1     Running   9 (158m ago)    33d   10.1.1.5    docker-desktop   <none>           <none>
circleguard-gateway-service-79d95fd587-7752p        1/1     Running   0               35s   10.1.1.57   docker-desktop   <none>           <none>
circleguard-identity-service-756dd77c7-x9wfb        1/1     Running   7 (159m ago)    33d   10.1.1.24   docker-desktop   <none>           <none>
circleguard-notification-service-77f99dbc9b-cgg8x   1/1     Running   5 (159m ago)    33d   10.1.1.53   docker-desktop   <none>           <none>
circleguard-promotion-service-774cc489fb-bj6kw      1/1     Running   10 (157m ago)   33d   10.1.1.11   docker-desktop   <none>           <none>
kafka-5fcdc5db8b-ch56g                              1/1     Running   4 (159m ago)    33d   10.1.1.29   docker-desktop   <none>           <none>
neo4j-7fb9f8f8f8-2x4r4                              1/1     Running   4 (159m ago)    33d   10.1.1.52   docker-desktop   <none>           <none>
openldap-5f697fdbbc-9xdcx                           1/1     Running   4 (159m ago)    33d   10.1.1.49   docker-desktop   <none>           <none>
postgres-54b5856bd4-f4vc9                           1/1     Running   4 (159m ago)    33d   10.1.1.44   docker-desktop   <none>           <none>
redis-6f4659cd9b-tmfg9                              1/1     Running   4 (159m ago)    33d   10.1.1.25   docker-desktop   <none>           <none>
zookeeper-548b556f44-5gqzv                          1/1     Running   4 (159m ago)    33d   10.1.1.23   docker-desktop   <none>           <none>
```

## After endpoints
```text
NAME                               ENDPOINTS                       AGE
circleguard-auth-service           10.1.1.37:8180                  33d
circleguard-form-service           10.1.1.5:8086                   33d
circleguard-gateway-service        10.1.1.57:8087                  33d
circleguard-identity-service       10.1.1.24:8083                  33d
circleguard-notification-service   10.1.1.53:8082                  33d
circleguard-promotion-service      10.1.1.11:8088                  33d
kafka                              10.1.1.29:9092                  33d
neo4j                              10.1.1.52:7687,10.1.1.52:7474   33d
openldap                           10.1.1.49:389                   33d
postgres                           10.1.1.44:5432                  33d
redis                              10.1.1.25:6379                  33d
zookeeper                          10.1.1.23:2181                  33d

Warning: v1 Endpoints is deprecated in v1.33+; use discovery.k8s.io/v1 EndpointSlice
```

## After recent events
```text
LAST SEEN   TYPE     REASON             OBJECT                                              MESSAGE
4m12s       Normal   Scheduled          pod/circleguard-gateway-service-79d95fd587-g24nc    Successfully assigned circleguard-dev/circleguard-gateway-service-79d95fd587-g24nc to docker-desktop
4m12s       Normal   Killing            pod/circleguard-gateway-service-79d95fd587-qlzll    Stopping container app
4m12s       Normal   SuccessfulCreate   replicaset/circleguard-gateway-service-79d95fd587   Created pod: circleguard-gateway-service-79d95fd587-g24nc
4m11s       Normal   Pulled             pod/circleguard-gateway-service-79d95fd587-g24nc    Container image "circleguard/circleguard-gateway-service:dev" already present on machine
4m11s       Normal   Created            pod/circleguard-gateway-service-79d95fd587-g24nc    Created container: app
4m11s       Normal   Started            pod/circleguard-gateway-service-79d95fd587-g24nc    Started container app
36s         Normal   Scheduled          pod/circleguard-gateway-service-79d95fd587-7752p    Successfully assigned circleguard-dev/circleguard-gateway-service-79d95fd587-7752p to docker-desktop
36s         Normal   Killing            pod/circleguard-gateway-service-79d95fd587-g24nc    Stopping container app
36s         Normal   SuccessfulCreate   replicaset/circleguard-gateway-service-79d95fd587   Created pod: circleguard-gateway-service-79d95fd587-7752p
35s         Normal   Pulled             pod/circleguard-gateway-service-79d95fd587-7752p    Container image "circleguard/circleguard-gateway-service:dev" already present on machine
35s         Normal   Created            pod/circleguard-gateway-service-79d95fd587-7752p    Created container: app
35s         Normal   Started            pod/circleguard-gateway-service-79d95fd587-7752p    Started container app
```

## After metrics
```text

error: Metrics API not available
```

## Final gateway rollout
```text
deployment "circleguard-gateway-service" successfully rolled out
```

