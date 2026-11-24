package com.leko.forge.intellij.wizard.gradle

import com.intellij.openapi.project.Project
import com.leko.forge.intellij.wizard.ForgeGradleLinker
import org.jetbrains.plugins.gradle.service.project.open.linkAndRefreshGradleProject
import java.nio.file.Path

/** Imports the Gradle build of a new Forge project, so that its tasks (generateForge, run) are in the Gradle tool window. */
class ForgeGradleLinkerImpl : ForgeGradleLinker {
    override fun link(project: Project, directory: Path) {
        linkAndRefreshGradleProject(directory.toString(), project)
    }
}
