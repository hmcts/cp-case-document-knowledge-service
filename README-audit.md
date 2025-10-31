# audit integration

Bringing in the cp-audit-filter jarfile pulls in artemis dependencies

It requires artemis to be accessible and properties configured to connect

We want to be able to run the app natively 
i.e. gradle bootRun
i.e. Run Application.java in idea to debug
i.e. Run integration tests inside the app without requiring loads of wiring
To do this we do need a running postgres and a running artemis
Which is best done with docker
So we need to spin up a minimal docker-compose stack with these 2 apps
But not with the actual app in the docker-file
... integration tests do not run against an app in docker but against the locally built app running in test mode


## Run with Docker Compose ( the one in the root of the project )
docker compose up
gradle bootRun
# Excluded from audit ... but can debug AuditFilter.shouldNotFilter
curl http://localhost:8082/actuator/health

# Not excluded
curl http://localhost:8082/actuator/info
curl http://localhost:8082/answers/181da2ad-44bc-43eb-b5de-5fd970b37a1b/559a79fa-56e9-4243-8c38-fcf96c07a6a4
/answers/{caseId}/{queryId} parameters of "version" and "at"
Will return 404 unless we insert some test data


Why not use default port 5432 ... just confusing for anybody new to the project ... like Colin who missed the 55432

Love the gradle task docker composeUp :)
Explained in main README.md
But hate the complexity of the build.gradle :(

systemProperty 'app.baseUrl', 'http://localhost:8082/casedocumentknowledge-service'
Took me ages to find this .. spring boot starts up with message
"Tomcat started on port 8082 (http) with context path '/'"
But hidden away in the hundreds of lines of build.gradle 
Explains why my curls were not working ... all this confusing comnfig takes time to work through
I just wanted to hit some endpoints !

Another complex thing ... I added a simple controller get /hello 
And it did not get picked up till I removed a ton of stuff from build.gradle
Suspect its openapi related, kind of makes sense. But no error on startup just ignored