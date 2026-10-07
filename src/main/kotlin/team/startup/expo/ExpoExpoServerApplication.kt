package team.startup.expo

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling
class ExpoExpoServerApplication

fun main(args: Array<String>) {
    runApplication<ExpoExpoServerApplication>(*args)
}
