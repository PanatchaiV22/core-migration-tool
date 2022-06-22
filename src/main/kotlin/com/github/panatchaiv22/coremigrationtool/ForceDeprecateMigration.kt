package com.github.panatchaiv22.coremigrationtool

import com.intellij.notification.*
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

class ForceDeprecateMigration : AnAction() {

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

    private fun forceDeprecated(files: Array<VirtualFile>, project: Project) {
        for (file in files) {
            val openTextEditor = FileEditorManager.getInstance(project).openTextEditor(
                OpenFileDescriptor(project, file), false
            ) ?: run {
                notify("Cannot open source file for modification!", project)
                return@forceDeprecated
            }

            val codeText = openTextEditor.document.text
                .replace(DEPRECATION_WARNING, DEPRECATION_ERROR)
                .replace(DEPRECATE_MESSAGE, DEPRECATE_MESSAGE_NEW)

            try {
                val r = Runnable {
                    openTextEditor.document.setReadOnly(false)
                    openTextEditor.document.setText(codeText)
                }
                WriteCommandAction.runWriteCommandAction(project, r)
            } catch (e: Exception) {
                notify(e.toString(), project)
            }
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
            forceDeprecated(files, project)
        } ?: run {
            println("project is null")
        }
    }

    companion object {
        private const val NOTI_GROUP = "com.github.panatchaiv22.coremigrationtool"
        private const val DEPRECATION_ERROR = "DeprecationLevel.ERROR"
        private const val DEPRECATE_MESSAGE_NEW = "is deprecated and must not be used. Please instead use"
        private val DEPRECATION_WARNING = """DeprecationLevel\.WARNING""".toRegex()
        private val DEPRECATE_MESSAGE = """has been deprecated and copied to""".toRegex(RegexOption.LITERAL)
    }
}
