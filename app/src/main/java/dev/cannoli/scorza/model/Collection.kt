package dev.cannoli.scorza.model

import java.io.File

data class Collection(
    val name: String,
    val file: File,
    val entries: List<File>
)
