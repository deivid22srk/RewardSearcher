package com.deivid22srk.rewardsearcher.data

object SearchTerms {
    private val terms = listOf(
        "climate change solutions 2026",
        "best programming languages to learn",
        "how does quantum computing work",
        "healthy meal prep ideas weekly",
        "electric vehicles comparison 2026",
        "machine learning tutorial beginners",
        "sustainable energy sources future",
        "space exploration latest news",
        "mental health tips daily life",
        "remote work productivity tools",
        "artificial intelligence ethics debate",
        "renewable solar panel efficiency",
        "ocean conservation efforts 2026",
        "cybersecurity best practices home",
        "urban gardening small spaces",
        "financial planning young adults",
        "biodiversity loss solutions",
        "5g technology impact society",
        "mindfulness meditation benefits",
        "electric battery technology advances",
        "mars colonization timeline",
        "gene editing crispr applications",
        "carbon capture technology progress",
        "smart home automation guide",
        "plant based nutrition science",
        "autonomous vehicles safety records",
        "blockchain beyond cryptocurrency",
        "water purification new methods",
        "vertical farming advantages",
        "neural interface technology 2026",
        "fusion energy breakthrough news",
        "digital privacy protection tips",
        "sustainable fashion brands",
        "robotics in healthcare applications",
        "climate adaptation strategies cities",
        "open source software contributions",
        "wildlife tracking technology",
        "3d printing construction homes",
        "augmented reality education uses",
        "green hydrogen fuel production",
        "microplastics ocean cleanup methods",
        "quantum encryption security",
        "personalized medicine genomics",
        "drone delivery services expansion",
        "biodegradable packaging innovations",
        "smart grid energy distribution",
        "lab grown meat sustainability",
        "holographic display technology",
        "deep sea exploration discoveries",
        "wearable health monitoring devices"
    )

    fun getShuffled(count: Int, prefix: String = ""): List<String> {
        val shuffled = terms.shuffled()
        val result = if (shuffled.size >= count) {
            shuffled.take(count)
        } else {
            val extra = (count - shuffled.size)
            shuffled + (1..extra).map { "search query $it ${System.currentTimeMillis()}" }
        }
        return if (prefix.isNotBlank()) {
            result.map { "$prefix $it" }
        } else {
            result
        }
    }
}
