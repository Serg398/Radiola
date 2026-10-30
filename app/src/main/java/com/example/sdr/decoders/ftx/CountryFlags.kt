package com.example.sdr.decoders.ftx

import java.text.Normalizer
import java.util.Locale

private fun countryKey(name: String) = Normalizer.normalize(name, Normalizer.Form.NFD)
    .replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT).replace("&", "and")

/** ISO flags describe the associated country/territory; DXCC entities need aliases. */
private val countryCodes: Map<String, String> = buildMap {
    Locale.getISOCountries().forEach { code ->
        put(countryKey(Locale("", code).getDisplayCountry(Locale.ENGLISH)), code)
    }
    val aliases = mapOf(
        "RU" to "European Russia|Asiatic Russia|Kaliningrad|Franz Josef Land",
        "GB" to "England|Scotland|Wales|Northern Ireland|Shetland Islands|UK Base Areas on Cyprus",
        "DE" to "Fed. Rep. of Germany", "TR" to "Asiatic Turkey|European Turkey",
        "US" to "Alaska|Hawaii|Guantanamo Bay|Navassa Island",
        "UM" to "Baker & Howland Islands|Johnston Island|Midway Island|Palmyra & Jarvis Islands|Wake Island|Kure Island",
        "IT" to "African Italy|Sardinia|Sicily", "ES" to "Balearic Islands|Canary Islands|Ceuta & Melilla",
        "FR" to "Corsica|Clipperton Island", "PT" to "Madeira Islands|Azores",
        "CA" to "Sable Island|St. Paul Island", "FI" to "Market Reef", "AX" to "Aland Islands",
        "NO" to "Peter 1 Island", "BV" to "Bouvet", "SJ" to "Svalbard|Bear Island|Jan Mayen",
        "KR" to "Republic of Korea|South Korea", "KP" to "DPR of Korea|North Korea",
        "CZ" to "Czech Republic", "SK" to "Slovak Republic", "SZ" to "Kingdom of Eswatini",
        "CD" to "Dem. Rep. of the Congo", "CG" to "Republic of the Congo", "VN" to "Vietnam",
        "VA" to "Vatican City", "TL" to "Timor - Leste", "GM" to "The Gambia",
        "FM" to "Micronesia", "SS" to "Republic of South Sudan", "XK" to "Republic of Kosovo",
        "SH" to "St. Helena|Ascension Island|Tristan da Cunha & Gough",
        "VC" to "St. Vincent", "MF" to "St. Martin", "BL" to "St. Barthelemy",
        "PM" to "St. Pierre & Miquelon", "RE" to "Reunion Island",
        "TF" to "Glorioso Islands|Juan de Nova, Europa|Tromelin Island|Crozet Island|Kerguelen Islands|Amsterdam & St. Paul Is.",
        "WF" to "Wallis & Futuna Islands", "PF" to "Austral Islands|Marquesas Islands",
        "NC" to "Chesterfield Islands", "MU" to "Agalega & St. Brandon|Rodriguez Island",
        "FJ" to "Conway Reef|Rotuma Island", "MY" to "West Malaysia|East Malaysia",
        "CL" to "San Felix & San Ambrosio|Easter Island|Juan Fernandez Islands",
        "CK" to "North Cook Islands|South Cook Islands", "SB" to "Temotu Province",
        "EC" to "Galapagos Islands", "CO" to "San Andres & Providencia|Malpelo Island",
        "JP" to "Minami Torishima|Ogasawara", "AS" to "Swains Island", "MP" to "Mariana Islands",
        "PR" to "Desecheo Island", "VI" to "US Virgin Islands",
        "BQ" to "Bonaire|Saba & St. Eustatius", "BR" to "Fernando de Noronha|St. Peter & St. Paul|Trindade & Martim Vaz",
        "GR" to "Mount Athos|Dodecanese|Crete", "KI" to "Western Kiribati|Central Kiribati|Eastern Kiribati|Banaba Island",
        "CR" to "Cocos Island", "AU" to "Macquarie Island|Lord Howe Island|Mellish Reef|Willis Island",
        "HM" to "Heard Island", "PN" to "Pitcairn Island|Ducie Island",
        "GS" to "South Georgia Island|South Sandwich Islands", "AQ" to "South Shetland Islands|South Orkney Islands",
        "IO" to "Chagos Islands", "IN" to "Andaman & Nicobar Is.|Lakshadweep Islands",
        "MX" to "Revillagigedo", "VE" to "Aves Island", "TK" to "Tokelau Islands",
        "NZ" to "Chatham Islands|Kermadec Islands|N.Z. Subantarctic Is.",
        "ZA" to "Pr. Edward & Marion Is.", "UN" to "ITU HQ|United Nations HQ|Vienna Intl Ctr",
        "EH" to "Western Sahara", "TW" to "Pratas Island"
    )
    aliases.forEach { (code, names) -> names.split('|').forEach { put(countryKey(it), code) } }
}

internal fun countryFlag(country: String?): String {
    val code = country?.let { countryCodes[countryKey(it)] } ?: return "🌐"
    return code.map { Character.toChars(0x1F1E6 + (it - 'A')).concatToString() }.joinToString("")
}

internal fun countryDisplayName(country: String?): String = when (country) {
    null -> "—"
    "Fed. Rep. of Germany" -> "Germany"
    "Asiatic Russia", "European Russia", "Kaliningrad" -> "Russia"
    "Asiatic Turkey", "European Turkey" -> "Turkey"
    "Republic of Korea" -> "South Korea"
    "DPR of Korea" -> "North Korea"
    "Kingdom of Eswatini" -> "Eswatini"
    "Republic of South Sudan" -> "South Sudan"
    "Republic of Kosovo" -> "Kosovo"
    else -> country
}
