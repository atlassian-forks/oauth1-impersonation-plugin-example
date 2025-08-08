
# oauth1-impersonation-plugin-example

Atlassian Data Center Plugin created to present OAuth 1 with impersonation

mvn clean install

mvn clean install -DskipTests

mvn amps:run -DinstanceId=confluence

mvn amps:debug -DinstanceId=confluence

mvn amps:run -DinstanceId=jira

mvn amps:debug -DinstanceId=jira
