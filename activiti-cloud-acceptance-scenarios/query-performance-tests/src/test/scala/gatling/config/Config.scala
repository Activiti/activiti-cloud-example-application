package gatling.config

object Config {

  val proto = System.getProperty("proto", "https").toString()
  val domain = System.getProperty("domain", "aae-3896.envalfresco.com").toString()
  val path = System.getProperty("path", "/query-performance-test").toString()
  val sso = System.getProperty("sso", "aae-3896.envalfresco.com").toString()
  val realm = System.getProperty("realm", "alfresco").toString()
}