package com.fankes.coloros.notify.rules

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.InputStream
import java.security.MessageDigest

class RuleParseException(
    message: String,
    cause: Exception? = null,
) : IllegalArgumentException(message, cause)

data class RuleOverrides(
    val enabled: Map<String, Boolean> = emptyMap(),
    val enabledAll: Map<String, Boolean> = emptyMap(),
)

class RuleCatalog internal constructor(
    val contentSha256: String,
    val definitions: List<RuleDefinition>,
) {
    val byPackage: Map<String, RuleDefinition> = definitions.associateBy(RuleDefinition::packageName)
    val size: Int get() = definitions.size

    init {
        require(byPackage.size == definitions.size) { "Rule packages must be unique" }
    }

    fun resolve(overrides: RuleOverrides = RuleOverrides()): ResolvedRuleCatalog {
        val rules = definitions.map { definition ->
            IconRule(
                definition = definition,
                isEnabled = overrides.enabled[definition.packageName] ?: definition.enabledByDefault,
                isEnabledAll = overrides.enabledAll[definition.packageName] ?: definition.enabledAllByDefault,
            )
        }
        return ResolvedRuleCatalog(rules)
    }
}

class ResolvedRuleCatalog internal constructor(
    val rules: List<IconRule>,
) {
    val byPackage: Map<String, IconRule> = rules.associateBy(IconRule::packageName)
    val size: Int get() = rules.size
}

internal data class RuleInput(
    val appName: String,
    val packageName: String,
    val iconBase64: String,
    val iconColor: String,
    val contributorName: String,
    val enabledByDefault: Boolean,
    val enabledAllByDefault: Boolean,
)

object RuleCatalogParser {

    fun parse(
        json: String,
        expectedSha256: String? = null,
    ): RuleCatalog {
        val contentSha256 = validatedSha256(json)
        if (expectedSha256 != null && !contentSha256.equals(expectedSha256, ignoreCase = true)) {
            throw RuleParseException(
                "Rule payload SHA-256 mismatch: expected ${expectedSha256.lowercase()}, actual $contentSha256"
            )
        }
        return parseTrusted(json, contentSha256)
    }

    internal fun parseTrusted(
        json: String,
        contentSha256: String,
    ): RuleCatalog {
        if (json.length > MAX_JSON_CHARACTERS) {
            throw RuleParseException("Rule payload exceeds $MAX_JSON_CHARACTERS characters")
        }
        if (json.isBlank()) return RuleCatalog(contentSha256, emptyList())

        val array = try {
            JSONArray(json)
        } catch (exception: JSONException) {
            throw RuleParseException("Rule payload is not a JSON array", exception)
        }
        if (array.length() > MAX_RULE_COUNT) {
            throw RuleParseException("Rule payload exceeds $MAX_RULE_COUNT entries")
        }
        val inputs = ArrayList<RuleInput>(array.length())
        for (index in 0 until array.length()) {
            try {
                inputs += array.getJSONObject(index).toRuleInput()
            } catch (exception: Exception) {
                if (exception is RuleParseException) throw exception
                throw RuleParseException("Invalid rule at index $index", exception)
            }
        }
        return fromInputs(inputs, contentSha256)
    }

    internal fun fromInputs(
        inputs: List<RuleInput>,
        contentSha256: String = "test",
        iconFactory: (String) -> IconAsset = IconAsset::fromBase64,
    ): RuleCatalog {
        if (inputs.size > MAX_RULE_COUNT) {
            throw RuleParseException("Rule payload exceeds $MAX_RULE_COUNT entries")
        }
        val seenPackages = HashSet<String>(inputs.size)
        val definitions = inputs.mapIndexed { index, input ->
            try {
                val packageName = input.packageName.trim()
                val appName = input.appName.trim()
                val contributorName = input.contributorName.trim()
                require(packageName.isNotEmpty()) { "packageName is empty" }
                require(packageName.length <= MAX_PACKAGE_NAME_CHARACTERS) { "packageName is too long" }
                require(PACKAGE_NAME.matches(packageName)) { "packageName is invalid: $packageName" }
                require(appName.length <= MAX_DISPLAY_FIELD_CHARACTERS) { "appName is too long" }
                require(contributorName.length <= MAX_DISPLAY_FIELD_CHARACTERS) {
                    "contributorName is too long"
                }
                require(seenPackages.add(packageName)) { "Duplicate packageName: $packageName" }
                RuleDefinition(
                    appName = appName,
                    packageName = packageName,
                    icon = iconFactory(input.iconBase64),
                    iconColor = parseColor(input.iconColor),
                    contributorName = contributorName,
                    enabledByDefault = input.enabledByDefault,
                    enabledAllByDefault = input.enabledAllByDefault,
                )
            } catch (exception: Exception) {
                if (exception is RuleParseException) throw exception
                throw RuleParseException("Invalid rule at index $index", exception)
            }
        }
        return RuleCatalog(contentSha256, definitions)
    }

    fun sha256(json: String): String = sha256(json.toByteArray(Charsets.UTF_8))

    internal fun validatedSha256(json: String): String {
        if (json.length > MAX_JSON_CHARACTERS) {
            throw RuleParseException("Rule payload exceeds $MAX_JSON_CHARACTERS characters")
        }
        val bytes = json.toByteArray(Charsets.UTF_8)
        if (bytes.size > RulePayloadIO.MAX_PAYLOAD_BYTES) {
            throw RuleParseException(
                "Rule payload exceeds ${RulePayloadIO.MAX_PAYLOAD_BYTES} UTF-8 bytes"
            )
        }
        return sha256(bytes)
    }

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

    fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(HASH_BUFFER_BYTES)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            require(total <= RulePayloadIO.MAX_PAYLOAD_BYTES) {
                "Rule payload exceeds ${RulePayloadIO.MAX_PAYLOAD_BYTES} bytes"
            }
            digest.update(buffer, 0, read)
        }
        return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private fun JSONObject.toRuleInput() = RuleInput(
        appName = optString("appName"),
        packageName = getString("packageName"),
        iconBase64 = getString("iconBitmap"),
        iconColor = optString("iconColor"),
        contributorName = optString("contributorName"),
        enabledByDefault = optionalBoolean("isEnabled", true),
        enabledAllByDefault = optionalBoolean("isEnabledAll", false),
    )

    private fun JSONObject.optionalBoolean(key: String, default: Boolean): Boolean =
        if (has(key)) getBoolean(key) else default

    private fun parseColor(raw: String): Int {
        if (raw.isBlank()) return 0
        if (!raw.startsWith('#')) return 0
        val hex = raw.drop(1)
        val argb = when (hex.length) {
            6 -> "ff$hex"
            8 -> hex
            else -> return 0
        }
        return argb.toLongOrNull(16)?.toInt() ?: 0
    }

    private const val HASH_BUFFER_BYTES = 16 * 1024
    private const val MAX_JSON_CHARACTERS = RulePayloadIO.MAX_PAYLOAD_BYTES
    private const val MAX_RULE_COUNT = 4096
    private const val MAX_PACKAGE_NAME_CHARACTERS = 255
    private const val MAX_DISPLAY_FIELD_CHARACTERS = 256
    private val PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)*")

}
