package gatling

import scala.concurrent.duration._

import ch.qos.logback.classic.{Level, LoggerContext}
import org.slf4j.LoggerFactory
import io.gatling.core.Predef._
import io.gatling.core.structure.{ ChainBuilder, ScenarioBuilder }
import io.gatling.http.Predef._
import io.gatling.http.protocol.HttpProtocolBuilder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import io.gatling.commons.validation._
import gatling.config.Config
import scala.util.Random

class LoadTest extends Simulation {

  val context: LoggerContext = LoggerFactory.getILoggerFactory.asInstanceOf[LoggerContext]
  // Log all HTTP requests
  //context.getLogger("io.gatling.http").setLevel(Level.valueOf("TRACE"))
  // Log failed HTTP requests
  // context.getLogger("io.gatling.http").setLevel(Level.valueOf("DEBUG"))

  var authenticated = new CountDownLatch(1);

  val httpConf = http.disableFollowRedirect

  val ssoUrl = "${proto}://${domain}/auth/realms/${realm}/protocol/openid-connect/token"
  val rbUrl = "${proto}://${domain}${path}/rb"
  val queryUrl = "${proto}://${domain}${path}/query"
  val graphqlUrl = "${proto}://${domain}${path}/query/graphql"
  val auditUrl = "${proto}://${domain}${path}/audit"

  var access_token: String = ""
  val sessionHeaders = Map(
    "Authorization" -> "Bearer ${access_token}",
    "Content-Type" -> "application/json")

  val httpProtocol: HttpProtocolBuilder = http

  val authenticate = scenario("Get access_token")
    .exec(session => session
      .set("proto", Config.proto)
      .set("domain", Config.domain)
      .set("realm", Config.realm))
    .exec(http("Login")
      .post(ssoUrl)
      .header("Content-Type", "application/x-www-form-urlencoded")
      .formParam("client_id", "alfresco")
      .formParam("grant_type", "password")
      .formParam("username", "testadmin")
      .formParam("password", "password")
      .check(status.is(200))
      .check(jsonPath("$.access_token").exists.saveAs("access_token")))
    .exec(session => {
      access_token = session("access_token").as[String]
      println("============ Access Token ============")
      println(access_token)
      authenticated.countDown()
      session
    })

  object Scenarios {
    val healthCheck: ChainBuilder =
      exec(session => session
        .set("proto", Config.proto)
        .set("path", Config.path)
        .set("domain", Config.domain))
      .exec(http("Query is Up")
        .get(queryUrl + "/actuator/health")
        .header("Accept", "*/*")
        .check(status.is(200)))

    val slaCheck: ChainBuilder =
      exec(session => session
        .set("proto", Config.proto)
        .set("path", Config.path)
        .set("domain", Config.domain)
        .set("access_token", access_token)
      )
      .exec(http("Query Process Instance")
        .get(queryUrl + "/v1/process-instances")
        .headers(sessionHeaders)
        .check(status.is(200))
      )
      .exitHereIfFailed
  }

  val pages: Int = 10
  val page: Int = 10
  val limit: Int = 10
  val maxProcessInstances: Int = 1000
  val maxTasks: Int = 1000
  val maxEvents: Int = 1000

  object GraphQL {
    val tasks: ChainBuilder = repeat(pages - 1, "i") {
      exec(session => {
         session.set("page", Random.nextInt(page)+1)
                .set("limit", limit)
                .set("domain", Config.domain)
                .set("access_token", access_token)
      })
      .exec(http("query{Tasks(page:{start:${page},limit:${limit}})")
        .post(graphqlUrl)
        .body(StringBody("""{
            "query": "query{Tasks(where:{taskCandidateGroups:{groupId:{IN: \"hr\"}},status:{EQ:CREATED}},page:{start:${page},limit:${limit}}){select{id,name,status,variables{id,name,value,type},taskCandidateUsers{userId},taskCandidateGroups{groupId},processInstance{id,status,variables{id,name,value,type}}}}}",
            "variables": null
          }"""))
        .headers(sessionHeaders)
        .check(status is 200)
        .check(jsonPath("$.data.Tasks.select")))
        .pause(1)
    }
  }

  object Query {
    val processInstances: ChainBuilder = repeat(pages - 1, "i") {
      exec(session => {
         session.set("page", Random.nextInt(page)+1)
                .set("limit", limit)
                .set("processInstanceId", Random.nextInt(maxProcessInstances)+1)
                .set("taskId", Random.nextInt(maxTasks)+1)
                .set("domain", Config.domain)
                .set("proto", Config.proto)
                .set("path", Config.path)
                .set("access_token", access_token)
      })
      .exec(http("Process by Page")
        .get(queryUrl + "/v1/process-instances?page=${page}&size=${limit}")
        .headers(sessionHeaders)
        .check(status.is(200))
      )
      .exec(http("Process Tasks")
        .get(queryUrl + "/v1/process-instances/${processInstanceId}/tasks")
        .headers(sessionHeaders)
        .check(status.is(200))
      )
      .exec(http("Process Variables")
        .get(queryUrl + "/v1/process-instances/${processInstanceId}/variables")
        .headers(sessionHeaders)
        .check(status.is(200))
      )
      .exec(http("Process Tasks Variables")
        .get(queryUrl + "/v1/tasks/${taskId}/variables")
        .headers(sessionHeaders)
        .check(status.is(200))
      )
      .pause(1)
    }
    
    val tasks: ChainBuilder = 
      exec(session => {
         session.set("processInstanceId", Random.nextInt(maxProcessInstances)+1)
         		.set("taskId", Random.nextInt(maxTasks)+1)
                .set("domain", Config.domain)
                .set("proto", Config.proto)
                .set("path", Config.path)
                .set("access_token", access_token)
      })
      .exec(http("tasks")
        .get(queryUrl + "/v1/process-instances/${processInstanceId}/tasks")
        .headers(sessionHeaders)
        .check(status.is(200))
      )
      .exec(http("tasks")
        .get(queryUrl + "/v1/tasks/${taskId}/variables")
        .headers(sessionHeaders)
        .check(status.is(200))
      )
      .pause(1)

    val processVariables: ChainBuilder = 
      exec(session => {
         session.set("processInstanceId", Random.nextInt(maxProcessInstances)+1)
                .set("domain", Config.domain)
                .set("proto", Config.proto)
                .set("path", Config.path)
                .set("access_token", access_token)
      })
      .exec(http("/v1/process-instances/${processInstanceId}/variables")
        .get(queryUrl + "/v1/process-instances/${processInstanceId}/variables")
        .headers(sessionHeaders)
        .check(status.is(200))
      )
      .pause(1)
      
    val taskVariables: ChainBuilder = 
      exec(session => {
         session.set("taskId", Random.nextInt(maxTasks)+1)
                .set("domain", Config.domain)
                .set("proto", Config.proto)
                .set("path", Config.path)
                .set("access_token", access_token)
      })
      .exec(http("/v1/tasks/${taskId}/variables")
        .get(queryUrl + "/v1/tasks/${taskId}/variables")
        .headers(sessionHeaders)
        .check(status.is(200))
      )
      .pause(1)
  }
  
  object Audit {
    val events: ChainBuilder = repeat(pages - 1, "i") {
      exec(session => {
         session.set("page", Random.nextInt(page)+1)
                .set("limit", limit)
                .set("eventId", Random.nextInt(maxEvents * 2)+1)
                .set("domain", Config.domain)
                .set("proto", Config.proto)
                .set("path", Config.path)
                .set("access_token", access_token)
      })
      .exec(http("Audit Events by Page")
        .get(queryUrl + "/v1/events?page=${page}&size=${limit}")
        .headers(sessionHeaders)
        .check(status.is(200))
      )
      .exec(http("Audit Event By Id")
        .get(queryUrl + "/v1/events/${eventId}")
        .headers(sessionHeaders)
        .check(status.is(200))
      )
      .pause(1)
    }
  }

  val awaitAuthenticated = exec(session => {
      authenticated.await(2, TimeUnit.SECONDS)
      session
  }).exitHereIfFailed

  val healthCheck: ScenarioBuilder = scenario("Health Check").exec(Scenarios.healthCheck)
  
  val slaCheck: ScenarioBuilder = scenario("Rb -> Query Sync SLA").exec(awaitAuthenticated)
                                                                  .exec(Scenarios.slaCheck)
  
  val graphQLCheck: ScenarioBuilder = scenario("Query GraphQL by Page").exec(awaitAuthenticated)
                                                                                .exec(GraphQL.tasks)
  
  val query: ScenarioBuilder = scenario("Query REST by Page").exec(awaitAuthenticated)
                                                             .exec(Query.processInstances)

  val audit: ScenarioBuilder = scenario("Audit REST by Page").exec(awaitAuthenticated)
                                                             .exec(Audit.events)

  setUp(authenticate.inject(constantUsersPerSec(1) during (1 seconds)),
        healthCheck.inject(constantUsersPerSec(1) during (1 seconds)),
        query.inject(incrementConcurrentUsers(1)
          .times(10)
          .eachLevelLasting(10 seconds)
          .separatedByRampsLasting(1 seconds)
          .startingFrom(1))
        .protocols(httpConf))
        .assertions(global.successfulRequests.percent.is(100))
}