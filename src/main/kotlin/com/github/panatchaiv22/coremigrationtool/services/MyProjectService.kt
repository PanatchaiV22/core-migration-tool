package com.github.panatchaiv22.coremigrationtool.services

import com.intellij.openapi.project.Project
import com.github.panatchaiv22.coremigrationtool.MyBundle

class MyProjectService(project: Project) {

    init {
        println(MyBundle.message("projectService", project.name))
    }
}
