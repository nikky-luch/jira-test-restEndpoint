import com.atlassian.jira.event.type.EventDispatchOption
import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.issue.IssueInputParametersImpl
import com.atlassian.jira.issue.Issue
import com.onresolve.scriptrunner.runner.rest.common.CustomEndpointDelegate
import groovy.json.JsonSlurper
import groovy.transform.BaseScript
import org.apache.log4j.Level

import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.issue.IssueInputParametersImpl
import groovy.json.JsonSlurper
import org.apache.log4j.Level

import javax.ws.rs.core.MultivaluedMap
import javax.ws.rs.core.MediaType
import javax.ws.rs.core.Response

@BaseScript CustomEndpointDelegate delegate

log.setLevel(Level.INFO)

def projectManager = ComponentAccessor.projectManager
def userManager = ComponentAccessor.userManager
def issueService = ComponentAccessor.issueService
def constantsManager = ComponentAccessor.constantsManager
def optionsManager = ComponentAccessor.getOptionsManager()
def issueManager = ComponentAccessor.issueManager

final allowedGroups = ['jira-administrators']
letCreateJiraTask(httpMethod: "POST", groups: allowedGroups) { MultivaluedMap queryParams, String body ->
    def form = new JsonSlurper().parseText(body) as Map<String, List>

    def bodyProject = form.project // TEST
    def bodyIssueType = form.issuetype // Task
    def bodySummary = "${form.client} from ${form.from} to ${form.to}."// Тема
    def bodyDescription = form.description // Описание

    // Получаем пользователей.
    final username = "nikkyluch" // Подставить userkey из ТЗ. В тестовой Jira учетка не создана.
    def user = userManager.getUserByName(username)
    def userAuth = ComponentAccessor.getJiraAuthenticationContext().getLoggedInUser()

    // Получаем кей проекта.
    def project = projectManager.getProjectObjByKey(bodyProject as String)

    def params = new IssueInputParametersImpl()
    params.with {
        setProjectId(project?.id)
        setReporterId(user?.name)
        setSummary(bodySummary as String)
        setDescription(bodyDescription as String)
        setIssueTypeId(constantsManager.allIssueTypeObjects.findByName(bodyIssueType as String).id)
    }

    def createValidationResult = issueService.validateCreate(user, params)
    if (createValidationResult.errorCollection.hasAnyErrors()) {
        log.error("Couldn't create issue: ${createValidationResult.errorCollection}")
        return Response.serverError().type(MediaType.APPLICATION_JSON).entity([errors: createValidationResult.errorCollection.errors]).build()
    }

    // Создание задачи
    def issue = issueService.create(user, createValidationResult).issue
    log.info "Created issue: ${issue.key}"

    // ---------- Обработка условий кастом полей --------------
    def cfSourceTown = ComponentAccessor.customFieldManager.getCustomFieldObjects().find { it.name == "Source town" }
    def sourceFieldConfig = cfSourceTown.getRelevantConfig(issue)
    def sourceOptions = optionsManager.getOptions(sourceFieldConfig)

    def cfTargetTown = ComponentAccessor.customFieldManager.getCustomFieldObjects().find { it.name == "Target town" }
    def targetFieldConfig = cfTargetTown.getRelevantConfig(issue)
    def targetOptions = optionsManager.getOptions(targetFieldConfig)

    // Если нет значения в поле селект, то добавляем.
    if (!sourceOptions.any { it.value == "derevnia Morshihinskaya" }) {
        sourceOptions.addOption(null, "derevnia Morshihinskaya")
        optionsManager.getOptions(sourceFieldConfig).sortOptionsByValue(null)
    }

    // Если нет значения в поле селект 2, возвращаем 400. Suzdal
    if (!targetOptions.any { it.value == "Suzdal" }) {
        return Response.status(400).entity("400 - Значение Суздаль в поле отсутствует.").build()
    }
    // ------------------------------------------------

    // Заполняем кастом поля
    // --------- Source town -----------
    def cfSourceSelect = ComponentAccessor.customFieldManager.getCustomFieldObjectsByName("Source town")[0]
    def cfSourceConfig = cfSourceSelect.getRelevantConfig(issue)
    def value1 = ComponentAccessor.optionsManager.getOptions(cfSourceConfig)?.find { it.value == form.from }
    issue.setCustomFieldValue(cfSourceSelect, value1)

    // -------- Target town ------------
    def cfTargetSelect = ComponentAccessor.customFieldManager.getCustomFieldObjectsByName("Target town")[0]
    def cfTargetConfig = cfTargetSelect.getRelevantConfig(issue)
    def value2 = ComponentAccessor.optionsManager.getOptions(cfTargetConfig)?.find { it.value == form.to }
    issue.setCustomFieldValue(cfTargetSelect, value2)

    // Применяем изменения обновлений
    ComponentAccessor.getIssueManager().updateIssue(userAuth, issue, EventDispatchOption.ISSUE_UPDATED, false)

    log.info("[TEST] The warning HTML list.")
    Response.ok("Issue created.".toString()).type(MediaType.TEXT_HTML).build()
}