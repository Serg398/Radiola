/*
 * Copyright (c) Oona Räisänen
 *
 * Permission to use, copy, modify, and/or distribute this software for any
 * purpose with or without fee is hereby granted, provided that the above
 * copyright notice and this permission notice appear in all copies.
 *
 * THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES
 * WITH REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR
 * ANY SPECIAL, DIRECT, INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES
 * WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN
 * ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF
 * OR IN CONNECTION WITH THE USE OR PERFORMANCE OF THIS SOFTWARE.
 *
 */
// Basic RDS alphabet table from windytan/redsea src/text/rdsstring.cc.
package com.example.sdr

internal object RdsCharset {
    private val table = charArrayOf(
        '\u0020', '\u0020', '\u0020', '\u0020', '\u0020', '\u0020', '\u0020', '\u0020', '\u0020', '\u0020', '\u000a', '\u0020', '\u0020', '\u000d', '\u0020', '\u0020',
        '\u0020', '\u0020', '\u0020', '\u0020', '\u0020', '\u0020', '\u0020', '\u0020', '\u0020', '\u0020', '\u0020', '\u0020', '\u0020', '\u0020', '\u0020', '\u00ad',
        '\u0020', '\u0021', '\u0022', '\u0023', '\u00a4', '\u0025', '\u0026', '\u0027', '\u0028', '\u0029', '\u002a', '\u002b', '\u002c', '\u002d', '\u002e', '\u002f',
        '\u0030', '\u0031', '\u0032', '\u0033', '\u0034', '\u0035', '\u0036', '\u0037', '\u0038', '\u0039', '\u003a', '\u003b', '\u003c', '\u003d', '\u003e', '\u003f',
        '\u0040', '\u0041', '\u0042', '\u0043', '\u0044', '\u0045', '\u0046', '\u0047', '\u0048', '\u0049', '\u004a', '\u004b', '\u004c', '\u004d', '\u004e', '\u004f',
        '\u0050', '\u0051', '\u0052', '\u0053', '\u0054', '\u0055', '\u0056', '\u0057', '\u0058', '\u0059', '\u005a', '\u005b', '\u005c', '\u005d', '\u2015', '\u005f',
        '\u2016', '\u0061', '\u0062', '\u0063', '\u0064', '\u0065', '\u0066', '\u0067', '\u0068', '\u0069', '\u006a', '\u006b', '\u006c', '\u006d', '\u006e', '\u006f',
        '\u0070', '\u0071', '\u0072', '\u0073', '\u0074', '\u0075', '\u0076', '\u0077', '\u0078', '\u0079', '\u007a', '\u007b', '\u007c', '\u007d', '\u00af', '\u0020',
        '\u00e1', '\u00e0', '\u00e9', '\u00e8', '\u00ed', '\u00ec', '\u00f3', '\u00f2', '\u00fa', '\u00f9', '\u00d1', '\u00c7', '\u015e', '\u03b2', '\u00a1', '\u0132',
        '\u00e2', '\u00e4', '\u00ea', '\u00eb', '\u00ee', '\u00ef', '\u00f4', '\u00f6', '\u00fb', '\u00fc', '\u00f1', '\u00e7', '\u015f', '\u01e7', '\u0131', '\u0133',
        '\u00aa', '\u03b1', '\u00a9', '\u2030', '\u01e6', '\u011b', '\u0148', '\u0151', '\u03c0', '\u20ac', '\u00a3', '\u0024', '\u2190', '\u2191', '\u2192', '\u2193',
        '\u00ba', '\u00b9', '\u00b2', '\u00b3', '\u00b1', '\u0130', '\u0144', '\u0171', '\u00b5', '\u00bf', '\u00f7', '\u00b0', '\u00bc', '\u00bd', '\u00be', '\u00a7',
        '\u00c1', '\u00c0', '\u00c9', '\u00c8', '\u00cd', '\u00cc', '\u00d3', '\u00d2', '\u00da', '\u00d9', '\u0158', '\u010c', '\u0160', '\u017d', '\u00d0', '\u013f',
        '\u00c2', '\u00c4', '\u00ca', '\u00cb', '\u00ce', '\u00cf', '\u00d4', '\u00d6', '\u00db', '\u00dc', '\u0159', '\u010d', '\u0161', '\u017e', '\u0111', '\u0140',
        '\u00c3', '\u00c5', '\u00c6', '\u0152', '\u0177', '\u00dd', '\u00d5', '\u00d8', '\u00de', '\u014a', '\u0154', '\u0106', '\u015a', '\u0179', '\u0166', '\u00f0',
        '\u00e3', '\u00e5', '\u00e6', '\u0153', '\u0175', '\u00fd', '\u00f5', '\u00f8', '\u00fe', '\u014b', '\u0155', '\u0107', '\u015b', '\u017a', '\u0167', '\u0020',
    )
    fun decode(bytes: ByteArray, start: Int, count: Int): String = buildString {
        for (index in start until start + count) {
            val code = bytes[index].toInt() and 255
            if (code == 13) break
            append(if (code == 0 || code == 10 || code == 31) ' ' else table[code])
        }
    }.trim()
}
