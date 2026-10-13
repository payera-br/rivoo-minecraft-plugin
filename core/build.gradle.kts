plugins {
    `java-library`
    `java-test-fixtures`
    jacoco
}

dependencies {
    api("com.google.code.gson:gson:2.11.0")
    testFixturesImplementation(platform("org.junit:junit-bom:5.11.4"))
    testFixturesImplementation("org.assertj:assertj-core:3.27.7")
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports { xml.required.set(true); html.required.set(true) }
}

tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.test)
    violationRules {
        rule { limit { counter = "LINE"; minimum = "0.80".toBigDecimal() } }
    }
}

// Fora do check desde a 1.0.0: os testes do protocolo antigo saíram com a migração para /v1/agent.
// Rode à mão (./gradlew jacocoTestCoverageVerification) quando houver testes do protocolo novo.
