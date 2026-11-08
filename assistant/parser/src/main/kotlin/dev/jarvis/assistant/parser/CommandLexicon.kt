package dev.jarvis.assistant.parser

/** Small local vocabulary for reversible device actions; personal text stays free-form. */
object CommandLexicon {
    const val MAX_PHRASES = 2500
    private const val MAX_APPLICATIONS = 256
    private val parser = CommandParser()
    private val forbidden = setOf(IntentType.UNKNOWN, IntentType.RECALL, IntentType.CALL,
        IntentType.MESSAGE, IntentType.CONFIRM, IntentType.CANCEL, IntentType.REMEMBER, IntentType.HELP)

    fun phrases(appLabels: List<String>): List<String> {
        val plain = linkedSetOf(
            "battery percentage", "battery percent", "battery status", "battery level", "check battery",
            "what is my battery percentage", "what is my battery level", "how much battery do i have",
            "check battery percentage", "please check battery", "tell me my battery percentage",
            "turn on flashlight", "turn off flashlight", "turn on the flashlight", "turn off the flashlight",
            "enable flashlight", "disable flashlight", "flashlight on", "flashlight off",
            "please turn on flashlight", "please turn off flashlight",
            "volume up", "volume down", "increase volume", "decrease volume", "mute volume",
            "open camera", "camera", "please open camera", "open settings", "settings",
            "play music", "pause music", "resume music", "next song", "previous song", "next track", "previous track",
            "show device information", "device information", "check memory usage", "memory usage",
            "check storage", "storage", "show system status", "system status", "network status",
            "what apps do i have", "show installed apps", "show my apps", "what applications do i have",
            "what apps did i install recently", "show recent apps",
            "what apps do i use most", "what apps do i rarely use"
        )
        for (category in listOf("editing", "programming", "development", "social", "education", "productivity", "entertainment", "games")) {
            plain += "show my $category apps"
            plain += "find my $category apps"
        }
        for ((word, count) in listOf("one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
            "ten" to 10, "fifteen" to 15, "twenty" to 20, "thirty" to 30, "sixty" to 60)) {
            for (unit in listOf(if (count == 1) "second" else "seconds", if (count == 1) "minute" else "minutes")) {
                plain += "set a timer for $word $unit"
                plain += "timer for $word $unit"
            }
        }

        val labels = appLabels.asSequence().filter { it.isNotBlank() }.distinct()
            .take(MAX_APPLICATIONS).toList()
        val exactOwners = labels.indices.groupBy { normalize(labels[it]) }
        val compactOwners = labels.indices.groupBy { SpokenApplicationNames.canonical(labels[it]) }
        val wordOwners = labels.indices.flatMap { index ->
            normalize(labels[index]).split(Regex("[^a-z0-9]+")).distinct().map { it to index }
        }.groupBy({ it.first }, { it.second })
        fun aliasOwner(alias: String): Int? {
            exactOwners[normalize(alias)]?.let { return it.singleOrNull() }
            compactOwners[SpokenApplicationNames.canonical(alias)]?.let { return it.singleOrNull() }
            return if (alias.length >= 4) wordOwners[normalize(alias)]?.singleOrNull() else null
        }
        fun addAppPhrase(index: Int, alias: String, verb: String = "open") {
            if (alias.isBlank() || alias.length > 80 || alias.split(' ').size > 8) return
            // Do not teach a phrase that maps an application's name to another device skill.
            if (parser.parse("$verb $alias").type != IntentType.OPEN_APPLICATION) return
            if (aliasOwner(alias) == index) plain += "$verb $alias"
        }
        // Prioritize each app's full label before optional pronunciation and verb variants.
        labels.forEachIndexed { index, label -> addAppPhrase(index, SpokenApplicationNames.aliases(label).firstOrNull().orEmpty()) }
        labels.forEachIndexed { index, label -> SpokenApplicationNames.aliases(label).drop(1).forEach { addAppPhrase(index, it) } }
        labels.forEachIndexed { index, label -> addAppPhrase(index, SpokenApplicationNames.aliases(label).firstOrNull().orEmpty(), "launch") }

        val result = linkedSetOf("jarvis", "[unk]")
        for (phrase in plain) {
            if (result.size + 2 > MAX_PHRASES) break
            val command = parser.parse(phrase)
            if (command.confidence < 0.7 || command.type in forbidden) continue
            result += phrase
            result += "jarvis $phrase"
        }
        return result.toList()
    }
}

/** Shared between decoding hints and matching so spoken brand spellings resolve consistently. */
object SpokenApplicationNames {
    fun compact(value: String): String = normalize(value).replace(Regex("[^a-z0-9]"), "")
    fun canonical(value: String): String = when (val joined = compact(value)) {
        "ticktock", "ticktok", "tiktok" -> "tiktok"
        "whatsup", "whatsap", "whatsapp" -> "whatsapp"
        "utube", "youtube" -> "youtube"
        "chatgeepeetee", "chatgpt" -> "chatgpt"
        "geemail", "gmail" -> "gmail"
        else -> joined
    }
    fun aliases(label: String): List<String> {
        val clean = normalize(label).replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
        if (clean.isBlank()) return emptyList()
        val names = linkedSetOf(clean)
        when (canonical(label)) {
            "discord" -> names += "dis cord"
            "facebook" -> names += "face book"
            "youtube" -> names += listOf("you tube", "u tube")
            "tiktok" -> names += listOf("tik tok", "tick tock")
            "whatsapp" -> names += listOf("whats app", "what's app")
            "instagram" -> names += "insta gram"
            "chatgpt" -> names += listOf("chat g p t", "chat gee pee tee")
            "gmail" -> names += listOf("g mail", "gee mail")
        }
        val camelWords = label.replace(Regex("([a-z])([A-Z])"), "$1 $2")
        names += normalize(camelWords).replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
        val words = clean.split(' ')
        if (words.size > 1) {
            val generic = setOf("google", "microsoft", "samsung", "android", "the", "app", "lite", "pro", "free", "beta")
            names += words.filter { it.length >= 4 && it !in generic }
        }
        return names.filter { it.isNotBlank() }.distinct().take(5)
    }
}
