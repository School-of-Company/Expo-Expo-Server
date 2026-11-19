package team.startup.expo.domain.expo

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyList
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.http.HttpStatus
import team.startup.expo.domain.expo.entity.ExpoFeatureSetting
import team.startup.expo.domain.expo.repository.ExpoFeatureSettingRepository
import team.startup.expo.domain.expo.service.ExpoFeatureCatalog
import team.startup.expo.domain.expo.service.ExpoFeatureDefinition
import team.startup.expo.domain.expo.service.ExpoFeatureGraph
import team.startup.expo.domain.expo.service.ExpoFeatureGroup
import team.startup.expo.domain.expo.service.ExpoFeatureSupport
import team.startup.expo.global.exception.ExpectedException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.io.File

// 프론트와 같은 예제 파일(docs/expo-feature-examples.json)로 선택값·실효 값·변경안을 고정한다.
class ExpoFeatureCatalogTests {
    private val examples: JsonNode = JsonMapper().readTree(File("docs/expo-feature-examples.json"))

    @Test
    fun `공유 예제의 변경안과 실효 값이 서버 계산과 같다`() {
        val plans = examples.get("plans")
        plans.size() shouldBe 5
        plans.forEach { example ->
            val name = example.get("name").asString()
            val graph = graph(example)
            val plan = graph.plan(onlyOn(graph, strings(example.get("before"))), changes(example))
            val effective = graph.effective(plan.configured)
            listOf(
                "configuredOn" to
                    plan.configured
                        .filterValues { it }
                        .keys
                        .toList(),
                "effectiveOn" to effective.filterValues { it }.keys.toList(),
                "autoEnabled" to plan.autoEnabled,
                "reactivated" to plan.reactivated,
                "deactivated" to plan.deactivated,
            ).forEach { (field, actual) -> (name to actual.toSet()) shouldBe (name to strings(example.get(field)).toSet()) }
            val blocked =
                plan.configured
                    .filterValues { it }
                    .keys
                    .associateWith {
                        graph.blockedBy(
                            it,
                            effective,
                        )
                    }.filterValues { it.isNotEmpty() }
            (name to blocked) shouldBe (name to example.get("blockedBy").properties().associate { (key, value) -> key to strings(value) })
        }
    }

    @Test
    fun `모순되거나 알 수 없는 변경은 400이다`() {
        examples.get("invalidPlans").forEach { example ->
            val error =
                assertThrows(ExpectedException::class.java) {
                    ExpoFeatureCatalog.plan(onlyOn(ExpoFeatureCatalog, strings(example.get("before"))), changes(example))
                }
            error.status shouldBe HttpStatus.BAD_REQUEST
        }
    }

    @Test
    fun `중복 키·없는 선행 키·순환 카탈로그는 만들 수 없다`() {
        examples.get("invalidCatalogs").forEach { example ->
            assertThrows(IllegalArgumentException::class.java) { graph(example) }
        }
    }

    @Test
    fun `운영 카탈로그는 13개 기능과 발표된 선행 관계를 가진다`() {
        ExpoFeatureCatalog.keys.size shouldBe 13
        ExpoFeatureCatalog.definitions.filter { it.requires.isNotEmpty() }.associate { it.key to it.requires } shouldBe
            mapOf(
                "PAPER_QR" to listOf("QR_ADMISSION"),
                "STANDARD_ATTENDANCE" to listOf("STANDARD_PROGRAM"),
                "TRAINING_ATTENDANCE" to listOf("TRAINING_PROGRAM"),
                "SURVEY_LOTTERY" to listOf("SURVEY"),
            )
        // 새 박람회는 사전등록만 ON, 행이 없는 기존 박람회는 모두 ON.
        ExpoFeatureCatalog.defaults(newExpo = true).filterValues { it }.keys shouldBe setOf("PRE_REGISTRATION")
        ExpoFeatureCatalog.configured(emptyMap()).values.all { it } shouldBe true
    }

    @Test
    fun `새 박람회 기본값은 플래그가 켜지면 사전등록만 ON이고 생성 요청에 있는 프로그램 종류는 함께 켠다`() {
        fun initialized(
            newDefaults: Boolean,
            standard: Boolean,
        ): Map<String, Boolean> {
            val repository = mock(ExpoFeatureSettingRepository::class.java)
            var saved: Map<String, Boolean> = emptyMap()
            `when`(repository.saveAllAndFlush(anyList<ExpoFeatureSetting>())).thenAnswer { invocation ->
                saved = invocation.getArgument<List<ExpoFeatureSetting>>(0).associate { it.featureKey to it.enabled }
                invocation.getArgument<List<ExpoFeatureSetting>>(0)
            }
            ExpoFeatureSupport(repository, newDefaults).initialize("expo", hasStandardPrograms = standard, hasTrainingPrograms = false)
            return saved
        }
        initialized(newDefaults = true, standard = false).filterValues { it }.keys shouldBe setOf("PRE_REGISTRATION")
        initialized(newDefaults = true, standard = true).filterValues { it }.keys shouldBe setOf("PRE_REGISTRATION", "STANDARD_PROGRAM")
        initialized(newDefaults = false, standard = false).values.all { it } shouldBe true
        initialized(newDefaults = false, standard = false).keys shouldBe ExpoFeatureCatalog.keys
    }

    private fun graph(example: JsonNode): ExpoFeatureGraph =
        example.get("catalog")?.let { catalog ->
            ExpoFeatureGraph(
                items(catalog).map {
                    ExpoFeatureDefinition(
                        it.get("key").asString(),
                        it.get("key").asString(),
                        "",
                        ExpoFeatureGroup.REGISTRATION,
                        strings(it.get("requires")),
                    )
                },
            )
        } ?: ExpoFeatureCatalog

    private fun onlyOn(
        graph: ExpoFeatureGraph,
        on: List<String>,
    ): Map<String, Boolean> = graph.keys.associateWith { it in on }

    private fun changes(example: JsonNode): Map<String, Boolean> =
        example.get("changes").properties().associate { (key, value) ->
            key to
                value.asBoolean()
        }

    private fun strings(node: JsonNode): List<String> = items(node).map { it.asString() }

    private fun items(node: JsonNode): List<JsonNode> = (0 until node.size()).map(node::get)
}
