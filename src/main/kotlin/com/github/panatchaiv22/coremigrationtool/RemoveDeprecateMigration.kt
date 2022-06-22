package com.github.panatchaiv22.coremigrationtool

import com.intellij.notification.*
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

class RemoveDeprecateMigration : AnAction() {

    private fun validateSelection(files: Array<VirtualFile>): Boolean {
        return files.isNotEmpty() && files.none { it.isDirectory }
    }

    private fun notify(msg: String, project: Project) {
        val notiGroup = NotificationGroupManager.getInstance().getNotificationGroup(NOTI_GROUP)
        val noti: Notification = notiGroup.createNotification(
            "Core migration", "Error", msg, NotificationType.ERROR
        )
        noti.notify(project)
    }

    private fun removeDeprecated(files: Array<VirtualFile>, project: Project) {
        for (file in files) {
            try {
                val r = Runnable {
                    file.delete(project)
                }
                WriteCommandAction.runWriteCommandAction(project, r)
            } catch (e: Exception) {
                notify(e.toString(), project)
            }
        }
    }

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

    private fun removeFromCSV(files: Array<VirtualFile>, project: Project) {
        val csvFile = LocalFileSystem.getInstance().findFileByPath("${project.basePath}$CSV_FILE")
        val openTextEditor = csvFile?.let { file ->
            FileEditorManager.getInstance(project).openTextEditor(
                OpenFileDescriptor(
                    project, file
                ), true // request focus to editor
            )
        } ?: run {
            notify("Cannot open csv file for modification!", project)
            return@removeFromCSV
        }

        val builder = StringBuilder()
        val lines = openTextEditor.document.text.split("\n")
        val fileList = mutableListOf<String>()
        for (file in files) {
            fileList.add(removeRootDir(file))
        }
        lines.forEachIndexed { index, line ->
            val hasFile = !fileList.firstOrNull { file ->
                line.startsWith(file)
            }.isNullOrEmpty()

            if (!hasFile) {
                if (index == lines.size - 1 && line.isBlank()) {
                    builder.append(line)
                } else {
                    builder.appendLine(line)
                }
            }
        }

        try {
            val r = Runnable {
                openTextEditor.document.setReadOnly(false)
                openTextEditor.document.setText(builder.toString())
            }
            WriteCommandAction.runWriteCommandAction(project, r)
        } catch (e: Exception) {
            notify(e.toString(), project)
        }
    }

    override fun update(e: AnActionEvent) {
        super.update(e)
        e.presentation.isEnabledAndVisible = true
    }

    override fun actionPerformed(e: AnActionEvent) {
        val files: Array<VirtualFile> = e.dataContext.getData(PlatformDataKeys.VIRTUAL_FILE_ARRAY) ?: emptyArray()
        if (!validateSelection(files)) {
            e.project?.let { project ->
                notify("Invalid selection. Please makes sure you have selected files and not folders.", project)
            }
            return
        }

        e.project?.let { project ->
            if (DeleteConfirmationDialog(project, files.size).showAndGet()) {
                // User pressed OK
                removeDeprecated(files, project)
                removeFromCSV(files, project)
            }
        } ?: run {
            println("project is null")
        }
    }

    class DeleteConfirmationDialog constructor(
        project: Project, private val fileCount: Int
    ) : DialogWrapper(project) {

        init {
            title = "Delete Confirmation"
            init()
        }

        @Override
        override fun createCenterPanel(): JComponent {
            val dialogPanel = JPanel(BorderLayout())
            val subject = if (fileCount > 1) {
                "They"
            } else {
                "It"
            }
            val files = if (fileCount > 1) {
                "files"
            } else {
                "file"
            }
            val label1 =
                JLabel("You have selected $fileCount $files. $subject will be permanently deleted and cannot be undone! ")
            val label2 =
                JLabel("Are you sure you want to continue?")
            dialogPanel.add(label1, BorderLayout.LINE_START)
            dialogPanel.add(label2, BorderLayout.AFTER_LAST_LINE)
            return dialogPanel
        }
    }

    companion object {
        private const val NOTI_GROUP = "com.github.panatchaiv22.coremigrationtool"
        private const val CSV_FILE = "/app/src/test/resources/refactor/file_comparison_paths.csv"
    }
}