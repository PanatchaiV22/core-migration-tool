package com.github.panatchaiv22.coremigrationtool

import com.intellij.notification.Notification
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.VisualPosition
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile

@Deprecated(
    "Please use DuplicateHereMigration instead!",
    ReplaceWith("DuplicateHereMigration", "com.github.panatchaiv22.coremigrationtool.DuplicateHereMigration"),
    DeprecationLevel.WARNING
)
class DuplicateDeprecateMigration : AnAction() {

    private fun removeRootDir(file: VirtualFile): String {
        val names = file.path.split("/")
        val total = names.size
        var prevDir = ""
        val buffer = StringBuilder()
        var isStarted = false
        // /Users/panatchai/nowinandroid/build-logic/convention/src/main/kotlin/AndroidApplicationComposeConventionPlugin.kt
        for (i in 0 until total) {
            if (isStarted) {
                buffer.append("/${names[i]}")
            } else if (names[i] == "src") {
                buffer.append("$prevDir/${names[i]}")
                isStarted = true
            }
            prevDir = names[i]
        }
        return buffer.toString()
    }

    private fun isSameFile(firstFile: VirtualFile, secondFile: VirtualFile): Boolean {
        return firstFile.name == secondFile.name
    }

    private fun generateCSV(files: Array<VirtualFile>, project: Project) {
        val totalLine = files.size
        val midLine = totalLine / 2
        val csvBuffer = StringBuilder()
        for (l in 0 until midLine) {
            for (m in midLine until totalLine) {
                if (isSameFile(files[l], files[m])) {
                    if (l == midLine - 1) {
                        csvBuffer.append(
                            "${removeRootDir(files[l])},${removeRootDir(files[m])}"
                        )
                    } else {
                        csvBuffer.appendLine(
                            "${removeRootDir(files[l])},${removeRootDir(files[m])}"
                        )
                    }
                    break
                }
            }
        }

        val csvFile = LocalFileSystem.getInstance().findFileByPath("${project.basePath}$CSV_FILE")
        val openTextEditor = csvFile?.let { file ->
            FileEditorManager.getInstance(project).openTextEditor(
                OpenFileDescriptor(
                    project, file
                ), true // request focus to editor
            )
        } ?: run {
            notify("Cannot open csv file for modification!", project)
            return@generateCSV
        }

        try {
            val r = Runnable {
                val builder = StringBuilder(openTextEditor.document.text)
                builder.appendLine(csvBuffer.toString())
                openTextEditor.document.setReadOnly(false)
                openTextEditor.document.setText(builder.toString())
                openTextEditor.caretModel.moveToVisualPosition(
                    VisualPosition(
                        openTextEditor.document.lineCount, 0
                    )
                )
            }
            WriteCommandAction.runWriteCommandAction(project, r)
        } catch (e: Exception) {
            notify(e.toString(), project)
        }
    }

    private fun validateSelection(files: Array<VirtualFile>): Boolean {
        return files.isNotEmpty() && files.size % 2 == 0 && files.none { it.isDirectory }
    }

    private fun notify(msg: String, project: Project) {
        val notiGroup = NotificationGroupManager.getInstance().getNotificationGroup(NOTI_GROUP)
        val noti: Notification = notiGroup.createNotification(
            "Core migration", "Error", msg, NotificationType.ERROR
        )
        noti.notify(project)
    }

    private fun markDeprecated(files: Array<VirtualFile>, project: Project) {
        val mid = files.size / 2
        for (l in 0 until mid) {
            val openTextEditor = FileEditorManager.getInstance(project).openTextEditor(
                OpenFileDescriptor(
                    project, files[l]
                ), false // request focus to editor
            ) ?: run {
                notify("Cannot open source file for modification!", project)
                return@markDeprecated
            }

            val lines = openTextEditor.document.text.split("\n")
            val lineCount = lines.size
            val codeBuffer = StringBuilder()
            var hasImport = false
            var hasPackage = false
            var classLineNo = 0
            lines.forEachIndexed { index, line ->
                if (line.startsWith("package ")) {
                    hasPackage = true
                } else if (line.startsWith("import ")) {
                    hasImport = true
                } else if (CLASS_DEF matches line) {
                    if (hasPackage || hasImport) {
                        // End if the imports and start the class/interface definition
                        var deprecatedMessage = """
                            @Deprecated(
                                message = "This file " +
                                        "${removeRootDir(files[l])}" + 
                                        "has been deprecated and copied to" +
                                        "${removeRootDir(files[l + mid])}" +
                                        "It will completely be removed and replaced with the Core implementation in the next release." +
                                        "If you modify the code, please make sure they are in sync.",
                                level = DeprecationLevel.WARNING
                            )
                        """.trimIndent()
                        val findSpace = """\s*""".toRegex()
                        val spaceCount = findSpace.find(line)?.value?.length ?: 0
                        if (spaceCount > 0) {
                            for (i in 0 until (spaceCount / 4)) {
                                deprecatedMessage = deprecatedMessage.prependIndent()
                            }
                        }
                        codeBuffer.appendLine(deprecatedMessage)
                        classLineNo = index
                    }
                }
                if (index == lineCount - 1 && line.isBlank()) {
                    codeBuffer.append(line)
                } else {
                    codeBuffer.appendLine(line)
                }
            }

            try {
                val r = Runnable {
                    openTextEditor.document.setReadOnly(false)
                    openTextEditor.document.setText(codeBuffer.toString())
                    openTextEditor.caretModel.moveToVisualPosition(
                        VisualPosition(classLineNo, 0)
                    )
                }
                WriteCommandAction.runWriteCommandAction(project, r)
            } catch (e: Exception) {
                notify(e.toString(), project)
            }
        }
    }

    // app/src/test/resources/refactor/file_comparison_paths.csv
    override fun update(e: AnActionEvent) {
        super.update(e)
        val files = e.dataContext.getData(PlatformDataKeys.VIRTUAL_FILE_ARRAY) ?: emptyArray()
        // always visible so the user knows that the migration option is there
        e.presentation.isVisible = true
        // however, only allow the user to select the option iif they select
        // files correctly (e.g. at least a pair of files).
        e.presentation.isEnabled = true // validateSelection(files)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val files: Array<VirtualFile> = e.dataContext.getData(PlatformDataKeys.VIRTUAL_FILE_ARRAY) ?: emptyArray()
        if (!validateSelection(files)) {
            e.project?.let { project ->
                notify("Invalid selection. Please makes sure you have selected pairs of files and no folders.", project)
            }
            return
        }

        e.project?.let { project ->
            markDeprecated(files, project)
            generateCSV(files, project)
        } ?: run {
            println("project is null")
        }
    }

    companion object {
        private const val CSV_FILE = "/app/src/test/resources/refactor/file_comparison_paths.csv"
        private const val NOTI_GROUP = "com.github.panatchaiv22.coremigrationtool"
        private val CLASS_DEF =
            """\s*(?:public|protected|private|internal)*\s*(?:abstract|enum|open)*\s*(?:class|interface)\s+\w+.*""".toRegex(
                RegexOption.DOT_MATCHES_ALL
            )
    }
}
