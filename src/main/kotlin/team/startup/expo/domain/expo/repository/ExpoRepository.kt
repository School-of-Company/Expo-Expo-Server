package team.startup.expo.domain.expo.repository

import org.springframework.data.jpa.repository.JpaRepository
import team.startup.expo.domain.expo.entity.Expo

interface ExpoRepository : JpaRepository<Expo, String>
