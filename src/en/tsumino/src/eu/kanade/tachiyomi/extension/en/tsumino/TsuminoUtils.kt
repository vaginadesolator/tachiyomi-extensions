package eu.kanade.tachiyomi.extension.en.tsumino

import android.annotation.SuppressLint
import eu.kanade.tachiyomi.source.model.SChapter
import okhttp3.Response
import org.jsoup.nodes.Document
import java.text.SimpleDateFormat

class TsuminoUtils {
    companion object {

        @SuppressLint("SimpleDateFormat")
        val UPLOAD_DATE_FORMAT = SimpleDateFormat("yyyy MMM dd")

        fun getArtists(document: Document): String {
            val stringBuilder = StringBuilder()
            val artists = document.select("#Artist a")

            artists.forEach {
                stringBuilder.append(it.text())

                if (it != artists.last())
                    stringBuilder.append(", ")
            }

            return stringBuilder.toString()
        }

        private fun getGroups(document: Document): String? {
            val stringBuilder = StringBuilder()
            val groups = document.select("#Group a")

            groups.forEach {
                stringBuilder.append(it.text())

                if (it != groups.last())
                    stringBuilder.append(", ")
            }

            return if (stringBuilder.toString().isEmpty()) null else stringBuilder.toString()
        }

        fun getPageCount(document: Document): Int {
            return document.select("#Pages").text().toInt()
        }

        fun getTags(document: Document): List<String> {
            return document.select("#Tag a").map { it.text() }
        }

        fun getDesc(document: Document): String {
            val stringBuilder = StringBuilder()
            val parodies = document.select("#Parody a")
            val characters = document.select("#Character a")

            if (parodies.size > 0) {
                stringBuilder.append("\n\n")
                stringBuilder.append("Parodies: ")

                parodies.forEach {
                    stringBuilder.append(it.text())

                    if (it != parodies.last())
                        stringBuilder.append(", ")
                }
            }

            if (characters.size > 0) {
                stringBuilder.append("\n\n")
                stringBuilder.append("Characters: ")

                characters.forEach {
                    stringBuilder.append(it.text())

                    if (it != characters.last())
                        stringBuilder.append(", ")
                }
            }

            return stringBuilder.toString()
        }

        fun getCollection(document: Document, selector: String): List<SChapter> {
            return document.select(selector).map { element ->
                SChapter.create().apply {
                    val details = element.select("span.tcell")
                    name = details[1].text()
                    scanlator = getGroups(document)
                    url = element.attr("href").replace("entry", "Read/Index")
                    chapter_number = details[0].text().toFloat()
                    page_count = details[3].text().toInt()

                    if (element.hasClass("book-collection-is-me"))
                        date_upload = UPLOAD_DATE_FORMAT.parse(document.select("#Uploaded").text())?.time ?: 0
                }
            }.reversed()
        }

        fun getChapter(document: Document, response: Response): List<SChapter> {
            return listOf(
                SChapter.create().apply {
                    name = "Chapter"
                    scanlator = getGroups(document)
                    chapter_number = 1f
                    url = response.request().url().encodedPath()
                        .replace("entry", "Read/Index")
                    page_count = getPageCount(document)
                    date_upload = UPLOAD_DATE_FORMAT.parse(document.select("#Uploaded").text())?.time ?: 0
                }
            )
        }
    }
}
