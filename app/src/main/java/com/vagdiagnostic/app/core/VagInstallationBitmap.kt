package com.vagdiagnostic.app.core

/** Part-number-selected UDS installation bitmaps. This decoder alone does not
 * enable a platform, identify an ECU, or establish a transport connection. */
object VagInstallationBitmap {
    data class Entry(val id: Int, val tp: Int?, val udsTx: Int?, val is29Bit: Boolean)
    data class Result(val format: String, val entries: List<Entry>, val unknownBits: List<Int>) {
        val ids: List<Int> get() = entries.map { it.id }.distinct()
    }
    private data class Mapping(val name: String, val owner: Int, val parts: List<String>,
        val sizes: Set<Int>, val idsByBit: Map<Int, Entry>)
    private val entries = listOf(
        Entry(0x19, 0x1F, 0x710, false),
        Entry(0x22, 0xA, 0x70F, false),
        Entry(0x9, 0x20, 0x70E, false),
        Entry(0x16, 0x2A, 0x70C, false),
        Entry(0x65, 0x29, 0x70B, false),
        Entry(0x10, 0x1D, 0x70A, false),
        Entry(0x1B, 0x1E, 0x716, false),
        Entry(0x15, 0x5, 0x715, false),
        Entry(0x17, 0x7, 0x714, false),
        Entry(0x3, 0x3, 0x713, false),
        Entry(0x44, 0x9, 0x712, false),
        Entry(0x25, 0x14, 0x711, false),
        Entry(0x32, 0x18, 0x71E, false),
        Entry(0x71, 0xE, 0x71D, false),
        Entry(0xA9, null, 0x71C, false),
        Entry(0x82, null, 0x71B, false),
        Entry(0x28, 0x45, 0x71A, false),
        Entry(0x40, null, 0x719, false),
        Entry(0x84, null, 0x727, false),
        Entry(0x85, null, 0x726, false),
        Entry(0x6B, 0x17, 0x724, false),
        Entry(0x6D, 0x34, 0x723, false),
        Entry(0x3B, null, 0x721, false),
        Entry(0x3A, null, 0x72F, false),
        Entry(0xBA, null, 0x72E, false),
        Entry(0x26, 0x30, 0x72D, false),
        Entry(0x3D, 0x3A, 0x72C, false),
        Entry(0x30, null, 0x72B, false),
        Entry(0xAC, null, 0x72A, false),
        Entry(0x21, null, 0x728, false),
        Entry(0x8A, null, 0x737, false),
        Entry(0x89, null, 0x736, false),
        Entry(0x88, null, 0x735, false),
        Entry(0xBF, null, 0x734, false),
        Entry(0xBE, null, 0x733, false),
        Entry(0x5, 0x31, 0x732, false),
        Entry(0x2B, null, 0x731, false),
        Entry(0x20, 0x16, 0x730, false),
        Entry(0xBC, null, 0x73F, false),
        Entry(0xBB, null, 0x73E, false),
        Entry(0xB8, null, 0x73D, false),
        Entry(0xB9, null, 0x73C, false),
        Entry(0x23, 0xD, 0x73B, false),
        Entry(0x8D, null, 0x738, false),
        Entry(0x69, 0x43, 0x747, false),
        Entry(0x8, 0x2C, 0x746, false),
        Entry(0x6F, 0x3C, 0x745, false),
        Entry(0xC6, null, 0x744, false),
        Entry(0xC3, null, 0x743, false),
        Entry(0xC5, null, 0x742, false),
        Entry(0xC4, null, 0x741, false),
        Entry(0xA5, null, 0x74F, false),
        Entry(0x3C, 0x1C, 0x74E, false),
        Entry(0x6, 0x35, 0x74D, false),
        Entry(0x36, 0x26, 0x74C, false),
        Entry(0x52, 0x23, 0x74B, false),
        Entry(0x42, 0x22, 0x74A, false),
        Entry(0xA7, null, 0x749, false),
        Entry(0x13, 0xB, 0x757, false),
        Entry(0x8B, null, 0x756, false),
        Entry(0x34, 0x4, 0x755, false),
        Entry(0x55, 0x6, 0x754, false),
        Entry(0x81, null, 0x753, false),
        Entry(0x53, 0x19, 0x752, false),
        Entry(0x4, 0x13, 0x751, false),
        Entry(0x1C, 0x40, 0x17FC0050, true),
        Entry(0x90, null, 0x75F, false),
        Entry(0x8F, null, 0x75E, false),
        Entry(0x7F, 0x4E, 0x75A, false),
        Entry(0xD0, null, 0x759, false),
        Entry(0x8E, null, 0x758, false),
        Entry(0x75, 0x55, 0x767, false),
        Entry(0xBD, null, 0x765, false),
        Entry(0xC0, null, 0x764, false),
        Entry(0xA6, null, 0x763, false),
        Entry(0xAD, null, 0x762, false),
        Entry(0xC, null, 0x761, false),
        Entry(0xCB, null, 0x760, false),
        Entry(0x47, 0x53, 0x76F, false),
        Entry(0x2E, 0x54, 0x17FC006E, true),
        Entry(0x57, 0x57, 0x76D, false),
        Entry(0x37, 0x5B, 0x76C, false),
        Entry(0x77, 0x5A, 0x76B, false),
        Entry(0x18, 0x2F, 0x76A, false),
        Entry(0x6C, 0x49, 0x769, false),
        Entry(0xC7, null, 0x768, false),
        Entry(0x2, 0x2, 0x7E1, false),
        Entry(0x1, 0x1, 0x7E0, false),
        Entry(0x5F, 0x4D, 0x773, false),
        Entry(0x14, 0xC, 0x772, false),
        Entry(0x60, null, 0x771, false),
        Entry(0xE, 0x58, 0x770, false),
        Entry(0x51, null, 0x7E6, false),
        Entry(0x61, 0x33, 0x17FC009C, true),
        Entry(0xC2, null, 0x7E3, false),
        Entry(0x11, 0x15, 0x7E2, false),
        Entry(0x48, 0x37, 0x17FC0087, true),
        Entry(0xCC, null, 0x17FC0085, true),
        Entry(0xCA, null, 0x17FC0084, true),
        Entry(0x39, 0x39, 0x17FC0083, true),
        Entry(0x29, 0x38, 0x17FC0082, true),
        Entry(0x74, 0x1B, 0x17FC0080, true),
        Entry(0x2A, null, 0x17FC008F, true),
        Entry(0x7, 0x3F, 0x17FC008E, true),
        Entry(0x46, 0x21, 0x17FC008B, true),
        Entry(0xCF, null, 0x17FC008A, true),
        Entry(0xCD, null, 0x17FC0089, true),
        Entry(0x5B, null, 0x17FC0088, true),
        Entry(0xD7, null, 0x17FC0097, true),
        Entry(0xD6, null, 0x17FC0096, true),
        Entry(0x50, null, 0x17FC0095, true),
        Entry(0x27, 0x3E, 0x17FC0093, true),
        Entry(0xD5, null, 0x17FC0092, true),
        Entry(0xD4, null, 0x17FC0091, true),
        Entry(0xDC, null, 0x17FC009E, true),
        Entry(0xDB, null, 0x17FC009D, true),
        Entry(0x8C, null, 0x7E5, false),
        Entry(0xD9, null, 0x17FC0099, true),
        Entry(0xD8, null, 0x17FC0098, true),
        Entry(0x4E, 0x5D, 0x17FC00A7, true),
        Entry(0xDF, null, 0x17FC00A6, true),
        Entry(0xDE, null, 0x17FC00A5, true),
        Entry(0xDD, null, 0x17FC00A0, true),
        Entry(0x80, null, 0x17FC00AE, true),
        Entry(0xE0, null, 0x17FC00AA, true),
        Entry(0x4B, null, 0x17FC00A9, true),
        Entry(0x5E, 0x5E, 0x17FC00A8, true),
        Entry(0xAE, null, 0x17FC00B4, true),
        Entry(0xCE, null, 0x17FC00B8, true),
        Entry(0x43, null, 0x17FC00C8, true),
        Entry(0xFF, null, 0x17FC0076, true),
        Entry(0x1E, 0x50, null, false),
        Entry(0x3E, 0x62, null, false),
        Entry(0x56, 0x52, null, false),
        Entry(0x62, 0x24, null, false),
        Entry(0x38, 0x27, null, false),
        Entry(0x72, 0x25, null, false)
    )
    private val mappings = listOf(
        Mapping("common", 0x19, listOf("2Q*", "3Q*", "4K*", "4M*", "4N*", "5Q*", "6C*", "80*", "8S*", "8W*"), setOf(32), mapOf(
            7 to entries[0], 8 to entries[1], 9 to entries[2], 11 to entries[3], 12 to entries[4], 13 to entries[5],
            17 to entries[6], 18 to entries[7], 19 to entries[8], 20 to entries[9], 21 to entries[10], 22 to entries[11],
            25 to entries[12], 26 to entries[13], 27 to entries[14], 28 to entries[15], 29 to entries[16], 30 to entries[17],
            32 to entries[18], 33 to entries[19], 35 to entries[20], 36 to entries[21], 38 to entries[22], 40 to entries[23],
            41 to entries[24], 42 to entries[25], 43 to entries[26], 44 to entries[27], 45 to entries[28], 47 to entries[29],
            48 to entries[30], 49 to entries[31], 50 to entries[32], 51 to entries[33], 52 to entries[34], 53 to entries[35],
            54 to entries[36], 55 to entries[37], 56 to entries[38], 57 to entries[39], 58 to entries[40], 59 to entries[41],
            60 to entries[42], 63 to entries[43], 64 to entries[44], 65 to entries[45], 66 to entries[46], 67 to entries[47],
            68 to entries[48], 69 to entries[49], 70 to entries[50], 72 to entries[51], 73 to entries[52], 74 to entries[53],
            75 to entries[54], 76 to entries[55], 77 to entries[56], 78 to entries[57], 80 to entries[58], 81 to entries[59],
            82 to entries[60], 83 to entries[61], 84 to entries[62], 85 to entries[63], 86 to entries[64], 87 to entries[65],
            88 to entries[66], 89 to entries[67], 93 to entries[68], 94 to entries[69], 95 to entries[70], 96 to entries[71],
            98 to entries[72], 99 to entries[73], 100 to entries[74], 101 to entries[75], 102 to entries[76], 103 to entries[77],
            104 to entries[78], 105 to entries[79], 106 to entries[80], 107 to entries[81], 108 to entries[82], 109 to entries[83],
            110 to entries[84], 111 to entries[85], 112 to entries[86], 113 to entries[87], 116 to entries[88], 117 to entries[89],
            118 to entries[90], 119 to entries[91], 123 to entries[92], 124 to entries[93], 126 to entries[94], 127 to entries[95],
            128 to entries[96], 130 to entries[97], 131 to entries[98], 132 to entries[99], 133 to entries[100], 135 to entries[101],
            136 to entries[102], 137 to entries[103], 140 to entries[104], 141 to entries[105], 142 to entries[106], 143 to entries[107],
            144 to entries[108], 145 to entries[109], 146 to entries[110], 148 to entries[111], 149 to entries[112], 150 to entries[113],
            153 to entries[114], 154 to entries[115], 155 to entries[116], 158 to entries[117], 159 to entries[118], 160 to entries[119],
            161 to entries[120], 162 to entries[121], 167 to entries[122], 169 to entries[123], 173 to entries[124], 174 to entries[125],
            175 to entries[126], 179 to entries[127], 191 to entries[128], 207 to entries[129]
        )),
        Mapping("mk8", 0x19, listOf("5WA*"), setOf(32), mapOf(
            2 to entries[7], 3 to entries[8], 4 to entries[9], 5 to entries[10], 7 to entries[0], 8 to entries[125],
            9 to entries[12], 12 to entries[15], 20 to entries[21], 26 to entries[25], 27 to entries[26], 35 to entries[33],
            36 to entries[34], 37 to entries[35], 38 to entries[36], 40 to entries[38], 41 to entries[39], 44 to entries[42],
            48 to entries[44], 49 to entries[45], 51 to entries[47], 56 to entries[51], 57 to entries[52], 58 to entries[53],
            59 to entries[54], 60 to entries[55], 61 to entries[56], 64 to entries[58], 80 to entries[71], 88 to entries[78],
            93 to entries[83], 94 to entries[84], 96 to entries[86], 97 to entries[130], 100 to entries[88], 101 to entries[89],
            102 to entries[90], 114 to entries[97], 115 to entries[98], 121 to entries[71], 134 to entries[84], 137 to entries[78],
            139 to entries[93], 142 to entries[26], 145 to entries[88], 158 to entries[125], 161 to entries[125], 169 to entries[8],
            175 to entries[128], 198 to entries[15], 211 to entries[125], 248 to entries[1], 249 to entries[2], 252 to entries[4],
            253 to entries[5]
        )),
        Mapping("mk8doordriver", 0x42, listOf("*"), setOf(1), mapOf(
            6 to entries[39], 7 to entries[56]
        )),
        Mapping("mk8doorpassenger", 0x52, listOf("*"), setOf(1), mapOf(
            6 to entries[38], 7 to entries[55]
        )),
        Mapping("mk8centralelec", 0x09, listOf("*"), setOf(2), mapOf(
            3 to entries[35], 7 to entries[2]
        )),
        Mapping("c7", 0x19, listOf("4G*", "4H*", "4S*"), setOf(9, 10, 12), mapOf(
            0 to entries[87], 1 to entries[86], 2 to entries[9], 3 to entries[9], 4 to entries[64], 5 to entries[35],
            6 to entries[53], 7 to entries[45], 8 to entries[2], 9 to entries[91], 10 to entries[5], 11 to entries[95],
            12 to entries[58], 13 to entries[59], 14 to entries[60], 15 to entries[7], 16 to entries[3], 17 to entries[8],
            18 to entries[8], 19 to entries[83], 20 to entries[0], 21 to entries[6], 22 to entries[131], 23 to entries[37],
            24 to entries[1], 25 to entries[16], 26 to entries[79], 27 to entries[27], 28 to entries[54], 29 to entries[52],
            30 to entries[26], 31 to entries[132], 32 to entries[56], 33 to entries[104], 34 to entries[78], 35 to entries[55],
            36 to entries[63], 37 to entries[61], 38 to entries[133], 39 to entries[80], 40 to entries[19], 41 to entries[88],
            42 to entries[134], 43 to entries[135], 44 to entries[4], 45 to entries[44], 46 to entries[84], 47 to entries[21],
            48 to entries[46], 49 to entries[136], 50 to entries[22], 51 to entries[68], 52 to entries[15], 53 to entries[18],
            54 to entries[96], 55 to entries[107], 56 to entries[32], 57 to entries[31], 58 to entries[30], 59 to entries[43],
            60 to entries[67], 61 to entries[66], 62 to entries[28], 63 to entries[10], 64 to entries[62], 65 to entries[70],
            66 to entries[73], 67 to entries[14], 68 to entries[17], 69 to entries[92], 70 to entries[116], 71 to entries[24],
            72 to entries[89], 73 to entries[74], 74 to entries[94], 75 to entries[89], 76 to entries[69], 77 to entries[88],
            78 to entries[47], 79 to entries[92], 80 to entries[49], 81 to entries[116], 82 to entries[71], 83 to entries[71],
            84 to entries[84], 85 to entries[50], 86 to entries[72]
        ))
    )

    /** 2A26 is the installation bitmap; 2A28 is the fault-indication bitmap.
     * Fault indications never substitute for reading an ECU's DTC memory. */
    fun decode(owner: Int, partNumber: String, reply: List<Int>, faults: Boolean = false): Result {
        val part = partNumber.filterNot { it.isWhitespace() }.uppercase()
        val mapping = mappings.singleOrNull { it.owner == owner && it.parts.any { pattern ->
            pattern == "*" || (pattern.endsWith("*") && part.isNotEmpty() && part.startsWith(pattern.dropLast(1))) || part == pattern
        } } ?: error("Нет подтверждённого формата списка для этого ECU")
        val body = VagOemReadOnly.positive(reply, 0x22)
        require(body.take(2) == listOf(0x2A, if (faults) 0x28 else 0x26)) { "Не совпал DID списка" }
        val data = body.drop(2)
        require(data.size in mapping.sizes && data.all { it in 0..255 }) { "Неполный или неизвестный формат битового списка" }
        val entries = linkedSetOf<Entry>()
        val unknown = mutableListOf<Int>()
        for (bit in 0 until data.size * 8) {
            if (data[bit / 8] and (0x80 ushr (bit % 8)) == 0) continue
            val id = mapping.idsByBit[bit]
            if (id != null) entries += id else unknown += bit
        }
        return Result(mapping.name, entries.toList(), unknown)
    }
}
