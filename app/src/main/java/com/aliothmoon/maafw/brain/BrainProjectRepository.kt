package com.aliothmoon.maafw.brain

import com.aliothmoon.maafw.domain.ProjectDefinition
import com.aliothmoon.maafw.domain.TaskDefinition
import com.aliothmoon.maafw.domain.TaskGroupDefinition
import com.aliothmoon.maafw.project.ProjectRepository
import com.aliothmoon.maafw.project.ProjectState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Decorates the PI project with live brain pipelines.
 *
 * The Tasks tab, its Add-tasks sheet and RunLauncher all read
 * [ProjectRepository.state]; injecting the learned pipelines here means a
 * pipeline approved in Review can be pinned to a normal user configuration
 * and executed by the normal MaaFW run path.
 */
class BrainProjectRepository(
    private val base: ProjectRepository,
    private val catalog: BrainPipelineCatalog,
    scope: CoroutineScope,
) : ProjectRepository {

    private val _state = MutableStateFlow<ProjectState>(ProjectState.Loading)
    override val state: StateFlow<ProjectState> = _state.asStateFlow()

    init {
        scope.launch { catalog.refresh() }
        scope.launch {
            combine(base.state, catalog.snapshot) { project, snapshot ->
                withBrainPipelines(project, snapshot)
            }.collect { _state.value = it }
        }
    }

    override suspend fun reload() {
        base.reload()
        catalog.refresh()
    }

    private fun withBrainPipelines(
        project: ProjectState,
        snapshot: BrainPipelineSnapshot,
    ): ProjectState {
        if (project !is ProjectState.Ready || snapshot.pipelines.isEmpty()) return project

        val brainTasks = snapshot.pipelines.mapNotNull { pipeline ->
            val label = pipeline.name.ifBlank { pipeline.goal }.ifBlank { "brain_pipeline_${pipeline.id}" }
            TaskDefinition(
                name = taskName(pipeline.id),
                entry = pipeline.entry,
                label = label,
                description = pipeline.goal.takeIf { it.isNotBlank() },
                groups = listOf(BRAIN_GROUP),
                optionNames = emptyList(),
                pipelineOverride = pipeline.graph,
                controllers = emptyList(),
                resources = emptyList(),
                defaultCheck = false,
            )
        }
        if (brainTasks.isEmpty()) return project

        val definition = project.definition
        return project.copy(
            definition = definition.copy(
                resources = definition.resources.map { resource ->
                    resource.copy(paths = (resource.paths + snapshot.resourcePaths).distinct())
                },
                tasks = definition.tasks.filterNot { it.name.startsWith(BRAIN_TASK_PREFIX) } + brainTasks,
                groups = definition.groups.filterNot { it.name == BRAIN_GROUP } +
                    TaskGroupDefinition(
                        name = BRAIN_GROUP,
                        label = "AI Pipelines",
                        description = "Approved pipelines learned in the Assistant; replay uses 0 AI tokens",
                        defaultExpand = true,
                    ),
            ),
        )
    }

    private fun taskName(id: Long): String = "$BRAIN_TASK_PREFIX$id"

    companion object {
        const val BRAIN_TASK_PREFIX = "brain_pipeline_"
        const val BRAIN_GROUP = "brain_pipelines"
    }
}
