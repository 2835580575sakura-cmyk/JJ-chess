package dev.sakura.xiangqi.core

object FenCodec {
    fun decode(fen: String): Result<PositionSnapshot> = runCatching {
        require(fen.length <= 300) { "局面内容过长" }
        val parts = fen.trim().split(Regex("\\s+"))
        require(parts.size == 6 && parts[2] == "-" && parts[3] == "-") { "FEN 格式无效" }
        val side = when(parts[1]) { "w" -> Side.RED; "b" -> Side.BLACK; else -> error("行棋方无效") }
        val half = parts[4].toIntOrNull(); val full = parts[5].toIntOrNull()
        require(half != null && half in 0..1000000 && full != null && full in 1..1000000) { "回合计数无效" }
        val rows = parts[0].split('/')
        require(rows.size == 10) { "棋盘必须有十行" }
        val cells = MutableList<Piece?>(90) { null }
        rows.forEachIndexed { row, text ->
            var file = 0
            for (c in text) {
                if (c in '1'..'9') file += c - '0' else {
                    val kind = PieceKind.entries.find { it.fen == c.lowercaseChar() } ?: error("未知棋子")
                    require(file in 0..8) { "棋盘行超出九格" }
                    cells[(9 - row) * 9 + file++] = Piece(if (c.isUpperCase()) Side.RED else Side.BLACK, kind)
                }
                require(file <= 9) { "棋盘行超出九格" }
            }
            require(file == 9) { "棋盘每行必须有九格" }
        }
        for (color in Side.entries) {
            val pieces = cells.filterNotNull().filter { it.side == color }
            require(pieces.count { it.kind == PieceKind.KING } == 1) { "双方各需一个将帅" }
            require(pieces.size <= 16) { "棋子数量过多" }
            for (kind in PieceKind.entries) {
                val limit = when(kind) { PieceKind.KING -> 1; PieceKind.PAWN -> 5; else -> 2 }
                require(pieces.count { it.kind == kind } <= limit) { "棋子数量不合法" }
            }
            val kingIndex = cells.indexOfFirst { it == Piece(color, PieceKind.KING) }
            require(kingIndex % 9 in 3..5 && kingIndex / 9 in if (color == Side.RED) 0..2 else 7..9) { "将帅必须在九宫内" }
        }
        PositionSnapshot(Board(cells.toList()), side, half, full)
    }
    fun encode(snapshot: PositionSnapshot): String {
        val rows = (9 downTo 0).map { rank -> buildString {
            var empty = 0
            for (file in 0..8) {
                val p = snapshot.board[Square(file, rank)]
                if (p == null) empty++ else {
                    if (empty > 0) { append(empty); empty = 0 }
                    append(if (p.side == Side.RED) p.kind.fen.uppercaseChar() else p.kind.fen)
                }
            }
            if (empty > 0) append(empty)
        } }
        return rows.joinToString("/") + " ${if(snapshot.sideToMove == Side.RED) "w" else "b"} - - ${snapshot.halfmoveClock} ${snapshot.fullmoveNumber}"
    }
}
