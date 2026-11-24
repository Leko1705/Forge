package com.leko.forge.intellij.wizard

import com.intellij.openapi.application.EDT
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.LocalFileSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path

/** When a project the wizard created is open: opens its program, and imports its Gradle build. */
class ForgeNewProjectActivity : ProjectActivity {

    /** What the wizard left to do: the [program] (relative to [directory]) to open, and whether to import a Gradle build. */
    data class Pending(val directory: Path, val program: String, val gradle: Boolean)

    override suspend fun execute(project: Project) {
        val pending = project.getUserData(PENDING) ?: return
        project.putUserData(PENDING, null)
        val program = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(pending.directory.resolve(pending.program))
        if (program != null) {
            withContext(Dispatchers.EDT) { OpenFileDescriptor(project, program).navigate(true) }
        }
        if (pending.gradle) ForgeGradleLinker.EP_NAME.extensionList.forEach { it.link(project, pending.directory) }
    }

    companion object {
        val PENDING = Key.create<Pending>("forge.newProject.pending")
    }
}
