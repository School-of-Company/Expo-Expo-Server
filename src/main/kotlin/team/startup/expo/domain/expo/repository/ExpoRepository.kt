package team.startup.expo.domain.expo.repository

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import team.startup.expo.domain.expo.entity.Expo

interface ExpoRepository : JpaRepository<Expo, String> {
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
        update Expo expo
           set expo.title = :title,
               expo.description = :description,
               expo.startedDay = :startedDay,
               expo.finishedDay = :finishedDay,
               expo.location = :location,
               expo.coverImage = :coverImage,
               expo.x = :x,
               expo.y = :y
         where expo.id = :id
        """,
    )
    fun updateInfo(
        @Param("id") id: String,
        @Param("title") title: String,
        @Param("description") description: String,
        @Param("startedDay") startedDay: String,
        @Param("finishedDay") finishedDay: String,
        @Param("location") location: String,
        @Param("coverImage") coverImage: String?,
        @Param("x") x: String,
        @Param("y") y: String,
    ): Int
}
