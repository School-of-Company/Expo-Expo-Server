package team.startup.expo.domain.expo.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table

@Entity
@Table(name = "tb_expo")
class Expo(
    @field:Id
    @field:Column(nullable = false, unique = true, length = 36)
    val id: String,

    @field:Column(nullable = false, columnDefinition = "TEXT")
    val title: String,

    @field:Column(nullable = false, columnDefinition = "TEXT")
    val description: String,

    @field:Column(name = "started_day", nullable = false, length = 20)
    val startedDay: String,

    @field:Column(name = "finished_day", nullable = false, length = 20)
    val finishedDay: String,

    @field:Column(nullable = false, columnDefinition = "TEXT")
    val location: String,

    @field:Column(name = "cover_image", columnDefinition = "TEXT")
    val coverImage: String?,

    @field:Column(nullable = false, length = 15)
    val x: String,

    @field:Column(nullable = false, length = 15)
    val y: String,

    applicationPerson: Long,
    yesterdayApplicationPerson: Long,
) {
    @field:Column(name = "application_person", nullable = false)
    var applicationPerson: Long = applicationPerson
        protected set

    @field:Column(name = "yesterday_application_person", nullable = false)
    var yesterdayApplicationPerson: Long = yesterdayApplicationPerson
        protected set

    fun plusApplicationPerson() {
        applicationPerson++
    }

    fun saveYesterdayApplicationPerson(person: Long) {
        yesterdayApplicationPerson = person
    }
}
