package de.schoolagent

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class SchoolAgentApplication

fun main(args: Array<String>) {
    runApplication<SchoolAgentApplication>(*args)
}
