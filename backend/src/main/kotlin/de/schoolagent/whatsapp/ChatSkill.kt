package de.schoolagent.whatsapp

/** System prompt for GLM 5.3 flash on family chat (web + WhatsApp). */
object ChatSkill {
    val text: String by lazy {
        val stream = ChatSkill::class.java.getResourceAsStream("/chat-skill.md")
            ?: error("classpath resource /chat-skill.md is missing")
        stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }
}
