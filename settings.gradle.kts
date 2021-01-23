import java.util.regex.Pattern

include(":annotations")
include(":core")

include(":lib-ratelimit")
project(":lib-ratelimit").projectDir = File("lib/ratelimit")

include(":duktape-stub")
project(":duktape-stub").projectDir = File("lib/duktape-stub")

include(":lib-dataimage")
project(":lib-dataimage").projectDir = File("lib/dataimage")


val includePattern: Pattern = Pattern.compile(
        "\\n+\\s*\\/{0}s*include\\s*=\\s*true",
        java.util.regex.Pattern.UNICODE_CHARACTER_CLASS
)

fun shouldInclude(langDir: File, extDir: File): Boolean {
    return langDir.name in arrayOf("all", "en") // only these languages
            && extDir.listFiles()?.firstOrNull { it.name == "build.gradle" }
                ?.readText()?.matches(includePattern) == true // only if ext "include" is present and set as true
}

// Loads extensions
File(rootDir, "src").listDirectories { langDir ->
    langDir.listDirectories()?.filter { shouldInclude(langDir, it) }?.forEach { extDir ->
        val name = ":${langDir.name}-${extDir.name}"
        include(name)
        project(name).projectDir = File("src/${langDir.name}/${extDir.name}")
    }
}

// Use this to load a single extension during development
// val lang = "all"
// val name = "mmrcms"
// include(":${lang}-${name}")
// project(":${lang}-${name}").projectDir = File("src/${lang}/${name}")

fun String.matches(pattern: java.util.regex.Pattern): Boolean {
    return pattern.matcher(this).find()
}

fun File.listDirectories(): List<File>? {
    return listFiles()?.filter { it.isDirectory }
}

inline fun File.listDirectories(block: (File) -> Unit) {
    listFiles()?.filter { it.isDirectory }?.forEach { block(it) }
}
