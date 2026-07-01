package io.github.chrisjenx.serialkompat.gradle

import org.gradle.testfixtures.ProjectBuilder
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SerialkompatPluginTest {
    @Test
    fun `registers serialkompatCheck as a verification task`() {
        val project = ProjectBuilder.builder().build()
        project.pluginManager.apply("io.github.chrisjenx.serialkompat")

        val task = project.tasks.findByName(SerialkompatPlugin.CHECK_TASK_NAME)
        assertNotNull(task)
        assertTrue(task.group == "verification")
    }

    @Test
    fun `wires serialkompatCheck into the check lifecycle`() {
        val project = ProjectBuilder.builder().build()
        project.pluginManager.apply("io.github.chrisjenx.serialkompat")

        val check = project.tasks.getByName("check")
        val dependencyNames = check.taskDependencies.getDependencies(check).map { it.name }
        assertTrue(dependencyNames.contains(SerialkompatPlugin.CHECK_TASK_NAME))
    }

    @Test
    fun `registers serialkompatExtract and the serialkompat extension`() {
        val project = ProjectBuilder.builder().build()
        project.pluginManager.apply("io.github.chrisjenx.serialkompat")

        assertNotNull(project.tasks.findByName(SerialkompatPlugin.EXTRACT_TASK_NAME))
        assertNotNull(project.extensions.findByName("serialkompat"))
    }
}
