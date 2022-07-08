package com.github.panatchaiv22.coremigrationtool

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.util.ExecUtil.execAndGetOutput
import com.intellij.notification.Notification
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.editor.VisualPosition
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.progress.ProgressManager
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

    private fun updatePackageName(csvFiles: MutableList<Pair<String, String>>, project: Project): Boolean {
        csvFiles.forEach { pair ->
            try {
                val currentPath = pair.second
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
                return false
            }
        }
        return true
    }

    private fun newCommandLine(commands: List<String>, project: Project): GeneralCommandLine {
        val commandLine = GeneralCommandLine(commands)
        commandLine.charset = StandardCharsets.UTF_8
        commandLine.setWorkDirectory(project.basePath)
        return commandLine
    }

    // https://github.com/alexmojaki/birdseye-pycharm/blob/master/src/com/github/alexmojaki/birdseye/pycharm/ProcessMonitor.java
    private fun runCommandLine(command: GeneralCommandLine, project: Project): Boolean {
        return try {
            val r = Runnable {
                val gitCommandResult = execAndGetOutput(command)
                if (!gitCommandResult.checkSuccess(project.thisLogger())) {
                    println("Error running")
                    println(command.commandLineString)
                    val errorMsg = gitCommandResult.stderrLines.joinToString("\n")
                    println("Error message")
                    println(errorMsg)
                    throw RuntimeException(errorMsg)
                }
            }
            ProgressManager.getInstance()
                .runProcessWithProgressSynchronously(r, "Migration tool git command", true, project)
        } catch (e: Exception) {
            println(e)
            notify(e.toString(), project)
            false
        }
    }

    // https://stackoverflow.com/questions/1043388/record-file-copy-operation-with-git/46484848#46484848
    private fun copyFilesWithGitHistory(
        files: Array<File>, destination: File, dvf: VirtualFile, project: Project, dep: Int = 0
    ): Boolean {
        val csvFiles = mutableListOf<Pair<String, String>>()
        val gitMoveCommands = mutableListOf<GeneralCommandLine>()
        val csvBuilder = StringBuilder()
        val moveCommitMessageBuilder = StringBuilder()
        val restoreCommitMessageBuilder = StringBuilder()
        val gitRestoreCommands = mutableListOf<GeneralCommandLine>()

        // git checkout -b dup # create and switch to branch
        //
        // git mv orig apple # make the duplicate
        // git commit -m "duplicate orig to apple"
        //
        // git checkout HEAD~ orig # bring back the original
        // git commit -m "restore orig"
        //
        // git checkout - # switch back to source branch
        // git merge --no-ff dup # merge dup into source branch
        // git branch --delete dup # delete the dup branch
        // --------------------------------------
        // create a temporary branch to work with
        val gitCheckoutCommand = newCommandLine(listOf("git", "checkout", "-b", GIT_DUP_BRANCH), project)
        if (!runCommandLine(gitCheckoutCommand, project)) {
            return false
        }

        // recursively go through files and folders
        traverseFiles(files, destination, dvf, project, csvFiles, dep)
        // update the IDE
        dvf.refresh(true, true)

        moveCommitMessageBuilder.appendLine()
        restoreCommitMessageBuilder.appendLine()
        csvFiles.forEach { pair ->
            // move the files
            gitMoveCommands.add(newCommandLine(listOf("git", "mv", pair.first, pair.second), project))

            // command to restore the original files later
            gitRestoreCommands.add(newCommandLine(listOf("git", "checkout", "HEAD~", pair.first), project))

            csvBuilder.appendLine("${pair.first},${pair.second}")
            moveCommitMessageBuilder.appendLine("Moved ${pair.first} to ${pair.second}")
            restoreCommitMessageBuilder.appendLine("Restored ${pair.first}")
        }
        csvBuilder.deleteAt(csvBuilder.lastIndexOf("\n")) // remove the last empty line
        moveCommitMessageBuilder.deleteAt(moveCommitMessageBuilder.lastIndexOf("\n")) // remove the last empty line
        restoreCommitMessageBuilder.deleteAt(restoreCommitMessageBuilder.lastIndexOf("\n")) // remove the last empty line

        // move files
        // https://stackoverflow.com/questions/36853427/intellij-plugin-run-console-command
        gitMoveCommands.forEach { command ->
            if (!runCommandLine(command, project)) {
                return false
            }
        }
        gitMoveCommands.clear()

        // update the new files new package
        if (!updatePackageName(csvFiles, project)) return false

        // git stage files
        val stageNewFiles = newCommandLine(listOf("git", "add", "."), project)
        if (!runCommandLine(stageNewFiles, project)) {
            return false
        }

        // update the IDE
        // dvf.refresh(true, true)

        // commit the moved
        val commitGitMove = newCommandLine(listOf("git", "commit", "-m", moveCommitMessageBuilder.toString()), project)
        if (!runCommandLine(commitGitMove, project)) {
            return false
        }

        // restore the original files
        gitRestoreCommands.forEach { command ->
            if (!runCommandLine(command, project)) {
                return false
            }
        }

        // deprecate the original files
        csvFiles.forEach { pair ->
            if (!markDeprecated(pair.first, pair.second, project)) return false
        }

        val stageOldFiles = newCommandLine(listOf("git", "add", "."), project)
        if (!runCommandLine(stageOldFiles, project)) {
            return false
        }

        // update the IDE
        // dvf.refresh(true, true)

        // commit the restored original files with @Deprecated annotation
        val commitGitRestore =
            newCommandLine(listOf("git", "commit", "-m", restoreCommitMessageBuilder.toString()), project)
        if (!runCommandLine(commitGitRestore, project)) {
            return false
        }

        // checkout the previous branch
        val gitCheckoutPrevBranch = newCommandLine(listOf("git", "checkout", "-"), project)
        if (!runCommandLine(gitCheckoutPrevBranch, project)) {
            return false
        }

        // merge the restored files back into the feature branch
        val gitMerge = newCommandLine(
            listOf(
                "git",
                "merge",
                "--no-ff",
                GIT_DUP_BRANCH,
                "-m",
                "merged the restored original files"
            ), project
        )
        if (!runCommandLine(gitMerge, project)) {
            return false
        }

        // delete the temporary branch
        val gitDeleteDup = newCommandLine(listOf("git", "branch", "--delete", GIT_DUP_BRANCH), project)
        if (!runCommandLine(gitDeleteDup, project)) {
            return false
        }

        // update the IDE
        dvf.refresh(true, true)

        // Deprecate the original files
        generateCSV(csvBuilder, project)
        return true
    }

    private fun traverseFiles(
        files: Array<File>,
        destination: File,
        dvf: VirtualFile,
        project: Project,
        csvFiles: MutableList<Pair<String, String>>,
        dep: Int = 0
    ) {
        files.forEach { file ->
            if (file.isDirectory) {
                traverseFiles(file.listFiles(), destination, dvf, project, csvFiles, dep + 1)
            } else {
                val oldPath = file.path
                val newPath = destinationPath(file.path, destination.path, dep)
                csvFiles.add(Pair(oldPath, newPath))

                val newFile = File(newPath)
                if (!newFile.parentFile.exists()) {
                    newFile.parentFile.mkdirs()
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
                csvBuilder.lines().forEach { line ->
                    if (!builder.contains(line)) {
                        builder.appendLine(line)
                    }
                }

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
        oldPath: String, newPath: String, project: Project
    ): Boolean {
        //       /Users/panatchai/IdeaProjects/MyApplication/app/src/main/java/com/example/myapplication/pack1/A.kt
        // /Users/panatchai/IdeaProjects/MyApplication/libmodule/src/main/java/com/example/libmodule/test/pack1/A.kt

        val path: Path = Paths.get(oldPath)
        val lines = Files.readAllLines(path, StandardCharsets.UTF_8)
        val lineCount = lines.size
        val codeBuffer = StringBuilder()
        var hasImport = false
        var hasPackage = false
        var hasDeprecated = false

        lines.forEachIndexed { index, line ->
            if (line.startsWith("package ")) {
                hasPackage = true
            } else if (line.startsWith("import ")) {
                hasImport = true
            } else if (line.trim().startsWith("@Deprecated")) {
                hasDeprecated = true
            } else if (CLASS_AND_EXTENSION_DEF matches line) {
                if ((hasPackage || hasImport) && !hasDeprecated) {
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
                }
                hasDeprecated = false
            }
            if (index == lineCount - 1 && line.isBlank()) {
                codeBuffer.append(line)
            } else {
                codeBuffer.appendLine(line)
            }
        }

        try {
            Files.write(path, codeBuffer.lines(), StandardCharsets.UTF_8)
        } catch (e: Exception) {
            notify(e.toString(), project)
            return false
        }
        return true
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
            copyFilesWithGitHistory(
                selectedFiles.toTypedArray(), File(destination.path), destination, project
            )
        }
    }

    companion object {
        private const val NOTI_GROUP = "com.github.panatchaiv22.coremigrationtool"
        private const val CSV_FILE = "/app/src/test/resources/refactor/file_comparison_paths.csv"
        private const val GIT_DUP_BRANCH = "tmp/core-migration-duplication"

        private val CLASS_AND_EXTENSION_DEF =
            """\s*(?:public|protected|private|internal)*\s*(?:(?:abstract|enum|open|data)*\s*(?:class|interface|object)\s+\w+.*|(?:fun)+\s*(?:.*\..*\().*)""".toRegex()
    }
}
