package eu.kanade.tachiyomi.animeextension.es.animefenix

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object AnimeFenixFilters {

    open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) :
        AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    private class GenreFilter : UriPartFilter(
        "Géneros",
        arrayOf(
            Pair("<Seleccionar>", ""),
            Pair("Acción", "accion"),
            Pair("Ahegao", "ahegao"),
            Pair("Anal", "anal"),
            Pair("Antropomórfico", "antropomorfico"),
            Pair("Artes Marciales", "artes-marciales"),
            Pair("Aventura", "aventura"),
            Pair("Bondage", "bondage"),
            Pair("Carreras", "carreras"),
            Pair("Casadas", "casadas"),
            Pair("Chikan", "chikan"),
            Pair("Ciencia Ficción", "ciencia-ficcion"),
            Pair("Comedia", "comedia"),
            Pair("Deportes", "deportes"),
            Pair("Detectives", "detectives"),
            Pair("Drama", "drama"),
            Pair("Ecchi", "ecchi"),
            Pair("Elenco Adulto", "elenco-adulto"),
            Pair("Elfas", "elfas"),
            Pair("Enfermeras", "enfermeras"),
            Pair("Escolares", "escolares"),
            Pair("Espacial", "espacial"),
            Pair("Fantasía", "fantasia"),
            Pair("Futanari", "futanari"),
            Pair("Gal", "gal"),
            Pair("Gore", "gore"),
            Pair("Gourmet", "gourmet"),
            Pair("Hardcore", "hardcore"),
            Pair("Harem", "harem"),
            Pair("Histórico", "historico"),
            Pair("Idols Hombre", "idols-hombre"),
            Pair("Idols Mujer", "idols-mujer"),
            Pair("Incesto", "incesto"),
            Pair("Infantil", "infantil"),
            Pair("Isekai", "isekai"),
            Pair("Josei", "josei"),
            Pair("Maids", "maids"),
            Pair("Mecha", "mecha"),
            Pair("Milfs", "milfs"),
            Pair("Militar", "militar"),
            Pair("Misterio", "misterio"),
            Pair("Mitología", "mitologia"),
            Pair("Música", "musica"),
            Pair("Netorare", "netorare"),
            Pair("Ninfomanía", "ninfomania"),
            Pair("Ninjas", "ninjas"),
            Pair("Orgías", "orgias"),
            Pair("Parodia", "parodia"),
            Pair("Psicológico", "psicologico"),
            Pair("Romance", "romance"),
            Pair("Samurai", "samurai"),
            Pair("Seinen", "seinen"),
            Pair("Shota", "shota"),
            Pair("Shoujo", "shoujo"),
            Pair("Shounen", "shounen"),
            Pair("Sobrenatural", "sobrenatural"),
            Pair("Superpoderes", "superpoderes"),
            Pair("Suspenso", "suspenso"),
            Pair("Tentáculos", "tentaculos"),
            Pair("Terror", "terror"),
            Pair("Tetonas", "tetonas"),
            Pair("Vanilla", "vanilla"),
            Pair("Vampiros", "vampiros"),
            Pair("Yaoi", "yaoi"),
            Pair("Yuri", "yuri"),
        ),
    )

    val FILTER_LIST get() = AnimeFilterList(
        AnimeFilter.Header("La búsqueda por texto ignora el filtro"),
        GenreFilter(),
    )
}
