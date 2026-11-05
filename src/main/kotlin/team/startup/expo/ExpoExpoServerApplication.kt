package team.startup.expo

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.cloud.openfeign.EnableFeignClients
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling
@EnableFeignClients
class ExpoExpoServerApplication

fun main(args: Array<String>) {
    runApplication<ExpoExpoServerApplication>(*args)
}
