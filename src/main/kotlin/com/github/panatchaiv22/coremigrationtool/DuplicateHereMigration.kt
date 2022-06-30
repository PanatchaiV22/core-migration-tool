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
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import java.awt.datatransfer.DataFlavor
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

class DuplicateHereMigration : AnAction() {

    private val csvBuilder = StringBuilder()

    private fun validateSelection(files: Array<VirtualFile>): Boolean {
        return files.isNotEmpty()
    }

    private fun notify(msg: String, project: Project) {
        val notiGroup = NotificationGroupManager.getInstance().getNotificationGroup(NOTI_GROUP)
        val noti: Notification = notiGroup.createNotification(
            "Core migration", "Error", msg, NotificationType.ERROR
        )
        noti.notify(project)
    }

    override fun update(e: AnActionEvent) {
        super.update(e)
        e.presentation.isEnabledAndVisible = true
    }

    private val packagePattern = """(?:.*kotlin|java/)(.*)(/.*\.kt)""".toRegex()
    private fun newPackage(path: String): String {
        val result = packagePattern.find(path)
        return result?.groups?.get(1)?.value?.replace("/", ".") ?: ""
    }

    private fun copyFiles(
        files: Array<File>, destination: File, dvf: VirtualFile, project: Project, dep: Int = 0
    ) {
        files.forEach { file ->
            // println("Copying ${file.path}")

            if (file.isDirectory) {
                copyFiles(file.listFiles(), destination, dvf, project, dep + 1)
            } else {
                val oldPath = file.path
                val newPath = destinationPath(file.path, destination.path, dep)
                csvBuilder.appendLine("$oldPath,$newPath")

                val newFile = File(newPath)
                if (!newFile.parentFile.exists()) {
                    newFile.parentFile.mkdirs()
                }
                try {
                    file.copyTo(newFile, false)
                    LocalFileSystem.getInstance().findFileByIoFile(file)?.let { vf ->
                        markDeprecated(vf, oldPath, newPath, project)
                    }

                    val currentPath = newFile.path
                    val newPackage = newPackage(currentPath)
                    val path: Path = Paths.get(currentPath)
                    val lines: MutableList<String> = Files.readAllLines(path, StandardCharsets.UTF_8)
                    val packageLine = lines.indexOfFirst { line ->
                        line.startsWith("package ")
                    }
                    lines[packageLine] = "package $newPackage"
                    Files.write(path, lines, StandardCharsets.UTF_8)
                } catch (e: Exception) {
                    notify("$e", project)
                }
            }
        }
    }

    private fun removeRootDir(path: String): String {
        val names = path.split("/")
        val total = names.size
        var prevDir = ""
        val buffer = StringBuilder()
        var isStarted = false

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

    private fun generateCSV(csvBuilder: StringBuilder, project: Project) {
        csvBuilder.deleteAt(csvBuilder.lastIndexOf("\n"))
        val csvFile = File("${project.basePath}${CSV_FILE}")
        if (!csvFile.exists()) {
            csvFile.parentFile.mkdirs()
            csvFile.createNewFile()
        }
        val csvVFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(csvFile)
        val openTextEditor = csvVFile?.let { file ->
            FileEditorManager.getInstance(project).openTextEditor(
                OpenFileDescriptor(
                    project, file
                ), true // request focus to editor
            )
        } ?: run {
            notify("Cannot open ${csvFile.path} for modification!", project)
            return@generateCSV
        }

        try {
            val r = Runnable {
                val builder = StringBuilder(openTextEditor.document.text)
                builder.appendLine(csvBuilder.toString())
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

    private fun destinationPath(oldPath: String, desPath: String, dep: Int): String {
        val originalPath = oldPath.split("/")
        val childPath = StringBuilder()
        if (dep == 0) {
            childPath.append("/").append(originalPath.last())
        } else {
            for (i in originalPath.size - dep - 1 until originalPath.size) {
                childPath.append("/").append(originalPath[i])
            }
        }
        childPath.insert(0, desPath)
        return childPath.toString()
    }

    private fun markDeprecated(
        file: VirtualFile, oldPath: String, newPath: String, project: Project
    ) {
        val openTextEditor = FileEditorManager.getInstance(project).openTextEditor(
            OpenFileDescriptor(project, file), false // do not request focus to editor
        ) ?: run {
            notify("Cannot open source file for modification!", project)
            return@markDeprecated
        }

        //       /Users/panatchai/IdeaProjects/MyApplication/app/src/main/java/com/example/myapplication/pack1/A.kt
        // /Users/panatchai/IdeaProjects/MyApplication/libmodule/src/main/java/com/example/libmodule/test/pack1/A.kt

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
                                        "${removeRootDir(oldPath)}" + 
                                        "has been deprecated and copied to" +
                                        "${removeRootDir(newPath)}" +
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

    override fun actionPerformed(e: AnActionEvent) {
        val files: Array<VirtualFile> = e.dataContext.getData(PlatformDataKeys.VIRTUAL_FILE_ARRAY) ?: emptyArray()
        if (!validateSelection(files)) {
            e.project?.let { project ->
                notify("Invalid selection. Please makes sure you have selected files and not folders.", project)
            }
            return
        }

        val destination: VirtualFile = if (files[0].isDirectory) {
            files[0]
        } else {
            files[0].parent
        }

        val selectedFiles: List<File> =
            CopyPasteManager.getInstance().getContents<List<File>>(DataFlavor.javaFileListFlavor) ?: listOf()

        e.project?.let { project ->
            csvBuilder.clear()
            copyFiles(selectedFiles.toTypedArray(), File(destination.path), destination, project)
            destination.refresh(true, true)
            generateCSV(csvBuilder, project)
        }
    }

    companion object {
        private const val NOTI_GROUP = "com.github.panatchaiv22.coremigrationtool"
        private const val CSV_FILE = "/app/src/test/resources/refactor/file_comparison_paths.csv"
        private val CLASS_DEF =
            """\s*(?:public|protected|private|internal)*\s*(?:abstract|enum|open)*\s*(?:class|interface)\s+\w+.*""".toRegex(
                RegexOption.DOT_MATCHES_ALL
            )
    }
}
