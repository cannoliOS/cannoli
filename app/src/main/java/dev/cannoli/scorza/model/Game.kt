package dev.cannoli.scorza.model

import java.io.File

data class Game(
    val file: File,
    val displayName: String,
    val platformTag: String,
    val isSubfolder: Boolean = false,
    val artFile: File? = null,
    val launchTarget: LaunchTarget = LaunchTarget.RetroArch
)
